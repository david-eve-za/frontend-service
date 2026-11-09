package com.glez.frontendservice.crawler.generated;

import com.glez.frontendservice.crawler.ChapterContent;
import com.glez.frontendservice.crawler.ChapterInfo;
import com.glez.frontendservice.crawler.CrawlerException;
import com.glez.frontendservice.crawler.NovelDetail;
import com.glez.frontendservice.crawler.NovelSearchResult;
import com.glez.frontendservice.crawler.RateLimiter;
import com.glez.frontendservice.crawler.SourceCrawler;
import com.glez.frontendservice.crawler.VolumeInfo;
import com.glez.frontendservice.config.CrawlerProperties;
import com.glez.frontendservice.config.RateLimiterConfig;
import com.glez.frontendservice.model.source.SourceChapter;
import com.glez.frontendservice.model.source.SourceChapterContent;
import com.glez.frontendservice.model.source.SourceNovel;
import com.glez.frontendservice.model.source.SourceVolume;
import com.glez.frontendservice.model.source.dto.CrawlerChapterModel;
import com.glez.frontendservice.model.source.dto.CrawlerNovelModel;
import com.glez.frontendservice.model.source.dto.CrawlerVolumeModel;
import com.glez.frontendservice.services.source.LanguageDetectionService;
import com.glez.frontendservice.services.source.SourceCrawlerPersistenceService;
import com.glez.frontendservice.services.source.SourceFileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Clase base abstracta para crawlers generados automáticamente.
 * 
 * Implementa la lógica común usando Jsoup y selectores CSS configurados
 * en las subclases. Cada crawler hijo solo necesita definir sus selectores
 * y URLs base.
 */
@Slf4j
public abstract class AbstractGeneratedCrawler implements SourceCrawler {

    protected final WebClient webClient;
    protected final RateLimiter rateLimiter;
    protected final String sourceId;
    protected final List<String> baseUrls;
    protected final float rateLimitRpm;
    protected final boolean requiresLogin;
    protected final String language;
    protected final String displayName;
    protected final String icon;

    // Reglas de limpieza personalizables
    protected final Set<String> badCss = ConcurrentHashMap.newKeySet();
    protected final List<String> badTextPatterns = new ArrayList<>();

    protected AbstractGeneratedCrawler(WebClient.Builder webClientBuilder,
                                       CrawlerProperties properties,
                                       RateLimiterConfig.RateLimiterProvider rateLimiterProvider,
                                       String sourceId,
                                       List<String> baseUrls,
                                       float rateLimitRpm,
                                       boolean requiresLogin,
                                       String language,
                                       String displayName,
                                       String icon) {
        this.sourceId = sourceId;
        this.baseUrls = baseUrls != null && !baseUrls.isEmpty() ? baseUrls : List.of("https://example.com/");
        this.rateLimitRpm = rateLimitRpm;
        this.requiresLogin = requiresLogin;
        this.language = language;
        this.displayName = displayName;
        this.icon = icon;

        // WebClient con base URL primaria
        this.webClient = webClientBuilder
                .baseUrl(this.baseUrls.get(0))
                .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build();

        // Rate limiter
        this.rateLimiter = rateLimiterProvider.getLimiter(sourceId);

        // Inicializar limpiador
        initializeCleaner();
    }

    protected void initializeCleaner() {
        // Sobrescribir en subclase para añadir reglas específicas
    }

    // ─── Persistence Integration ───

    @Autowired
    protected SourceCrawlerPersistenceService persistence;

    @Autowired
    protected SourceFileStorageService fileStorage;

    @Autowired
    protected LanguageDetectionService langDetector;

    // ─── SourceCrawler interface ───

    @Override
    public String getSourceId() {
        return sourceId;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String getIcon() {
        return icon;
    }

    @Override
    public String getLanguage() {
        return language;
    }

    @Override
    public String getBaseUrl() {
        return baseUrls.isEmpty() ? "" : baseUrls.get(0);
    }

    @Override
    public int getRateLimitRpm() {
        return Math.round(rateLimitRpm);
    }

    @Override
    public boolean requiresLogin() {
        return requiresLogin;
    }

    @Override
    public List<NovelSearchResult> search(String query) {
        // 1. Si query vacío o null → listar de BD (paginado, primeras 50)
        if (query == null || query.isBlank()) {
            return persistence.findAllBySource(getSourceId(), 0, 50)
                    .stream().map(this::toSearchResult).toList();
        }

        // 2. Buscar en BD primero (full-text en title/author/synopsis)
        List<SourceNovel> cached = persistence.searchBySource(getSourceId(), query, 0, 50);
        if (!cached.isEmpty()) {
            log.debug("Found {} cached results for query '{}' in {}", cached.size(), query, getSourceId());
            return cached.stream().map(this::toSearchResult).toList();
        }

        // 3. Fallback: HTTP crawl + persistir
        log.debug("Cache miss for query '{}', falling back to HTTP crawl", query);
        String searchUrl = buildSearchUrl(query);
        if (searchUrl == null) {
            log.warn("No search URL template for {}", getSourceId());
            return List.of();
        }

        try {
            acquirePermit();
            String html = webClient.get()
                    .uri(searchUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (html == null || html.isEmpty()) {
                return List.of();
            }

            Document doc = Jsoup.parse(html);
            List<NovelSearchResult> results = parseSearchResults(doc, query);

            // 4. Para cada resultado, obtener/persistir detalle completo (async en background)
            return results.stream().map(r -> {
                SourceNovel novel = persistence.getOrFetchNovel(getSourceId(), r.url(), 
                        url -> parseNovelDetailsToModel(Jsoup.parse(fetchHtml(url)), url));
                return toSearchResult(novel);
            }).toList();

        } catch (Exception e) {
            log.error("Search failed for query '{}': {}", query, e.getMessage());
            throw new CrawlerException(getSourceId(), "search", "Search failed: " + e.getMessage(), e);
        }
    }

    protected String buildSearchUrl(String query) {
        String template = getSearchUrlTemplate();
        if (template == null) return null;
        String encodedQuery = query.toLowerCase().replace(" ", "+");
        return String.format(template, encodedQuery);
    }

    protected String getSearchUrlTemplate() {
        return null; // Sobrescribir en subclase si hay template
    }

    protected List<NovelSearchResult> parseSearchResults(Document doc, String query) {
        String listSelector = getSearchItemListSelector();
        if (listSelector == null || listSelector.isEmpty()) {
            log.warn("No search item list selector for {}", getSourceId());
            return List.of();
        }

        Elements results = doc.select(listSelector);
        List<NovelSearchResult> searchResults = new ArrayList<>();

        for (Element item : results) {
            String title = extractTitle(item);
            String url = extractUrl(item);
            String info = extractInfo(item);
            String cover = extractCover(item);

            if (url != null && !url.isEmpty()) {
                searchResults.add(new NovelSearchResult(
                        title != null ? title.strip() : "Unknown",
                        toAbsoluteUrl(url),
                        info != null ? info : "",
                        cover != null ? cover : "",
                        url // sourceNovelId
                ));
            }
        }

        log.debug("Found {} results for query '{}'", searchResults.size(), query);
        return searchResults;
    }

    protected String getSearchItemListSelector() { return null; }
    protected String getSearchItemTitleSelector() { return null; }
    protected String getSearchItemInfoSelector() { return null; }

    protected String extractTitle(Element item) {
        String titleSel = getSearchItemTitleSelector();
        if (titleSel != null) {
            Element el = item.selectFirst(titleSel);
            if (el != null) return el.attr("title").isEmpty() ? el.text() : el.attr("title");
        }
        return item.attr("title").isEmpty() ? item.text() : item.attr("title");
    }

    protected String extractUrl(Element item) {
        Element link = item.selectFirst("a[href]");
        return link != null ? link.attr("href") : "";
    }

    protected String extractInfo(Element item) {
        String infoSel = getSearchItemInfoSelector();
        if (infoSel != null) {
            Element el = item.selectFirst(infoSel);
            if (el != null) return el.text();
        }
        return "";
    }

    protected String extractCover(Element item) {
        Element img = item.selectFirst("img[src]");
        return img != null ? toAbsoluteUrl(img.attr("src")) : "";
    }

    @Override
    public NovelDetail getNovelDetails(String novelUrl) {
        // 1. Buscar en BD por URL hash
        SourceNovel cached = persistence.findBySourceIdAndNovelUrl(getSourceId(), novelUrl).orElse(null);
        if (cached != null) {
            log.debug("Cache hit for novel details: {}", novelUrl);
            return toNovelDetail(cached);
        }

        // 2. Fallback: HTTP crawl + persist
        log.debug("Cache miss for novel details: {}, crawling...", novelUrl);
        try {
            acquirePermit();
            String html = webClient.get()
                    .uri(novelUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            Document doc = Jsoup.parse(html);
            CrawlerNovelModel model = parseNovelDetailsToModel(doc, novelUrl);

            // Persistir con upsert
            SourceNovel novel = persistence.upsertNovel(getSourceId(), model, getVersion(), getTier());
            return toNovelDetail(novel);

        } catch (Exception e) {
            log.error("Get novel details failed for {}: {}", novelUrl, e.getMessage());
            throw new CrawlerException(getSourceId(), "getNovelDetails", "Failed: " + e.getMessage(), e);
        }
    }

    /**
     * Extrae detalles de novela y retorna modelo para persistencia.
     * Sobrescribe parseNovelDetails para devolver modelo en lugar de DTO.
     */
    protected CrawlerNovelModel parseNovelDetailsToModel(Document doc, String novelUrl) {
        String title = extractNovelTitle(doc);
        String author = extractNovelAuthor(doc);
        String cover = extractNovelCover(doc);
        String synopsis = extractNovelSynopsis(doc);
        List<String> tags = extractNovelTags(doc);
        List<VolumeInfo> volumeInfos = extractVolumesAndChapters(doc, novelUrl);

        // Convertir VolumeInfo a CrawlerVolumeModel (using builder pattern)
        List<CrawlerVolumeModel> volumes = new ArrayList<>();
        int chapterSerial = 0;
        for (VolumeInfo vi : volumeInfos) {
            List<CrawlerChapterModel> chapters = new ArrayList<>();
            for (ChapterInfo ci : vi.chapters()) {
                chapterSerial++;
                chapters.add(CrawlerChapterModel.builder()
                        .id(chapterSerial)
                        .title(ci.title())
                        .url(ci.url())
                        .volume(vi.id())
                        .build());
            }
            volumes.add(CrawlerVolumeModel.builder()
                    .id(vi.id())
                    .title(vi.title())
                    .chapters(chapters)
                    .build());
        }

        return CrawlerNovelModel.builder()
                .url(novelUrl)
                .title(title)
                .author(author)
                .coverUrl(cover)
                .synopsis(synopsis)
                .language(language)
                .tags(tags)
                .volumes(volumes)
                .build();
    }

    protected String extractNovelTitle(Document doc) {
        String sel = getNovelTitleSelector();
        if (sel != null) {
            Element el = doc.selectFirst(sel);
            if (el != null) return el.text().strip();
        }
        Element h1 = doc.selectFirst("h1");
        return h1 != null ? h1.text().strip() : "Unknown Title";
    }

    protected String getNovelTitleSelector() { return null; }

    protected String extractNovelAuthor(Document doc) {
        String sel = getNovelAuthorSelector();
        if (sel != null) {
            Element el = doc.selectFirst(sel);
            if (el != null) return el.text().strip();
        }
        return "";
    }

    protected String getNovelAuthorSelector() { return null; }

    protected String extractNovelCover(Document doc) {
        String sel = getNovelCoverSelector();
        if (sel != null) {
            Element el = doc.selectFirst(sel);
            if (el != null && el.hasAttr("src")) return toAbsoluteUrl(el.attr("src"));
            if (el != null && el.hasAttr("content")) return toAbsoluteUrl(el.attr("content"));
        }
        Element og = doc.selectFirst("meta[property=og:image]");
        if (og != null && og.hasAttr("content")) return toAbsoluteUrl(og.attr("content"));
        return "";
    }

    protected String getNovelCoverSelector() { return null; }

    protected String extractNovelSynopsis(Document doc) {
        String sel = getNovelSynopsisSelector();
        if (sel != null) {
            Element el = doc.selectFirst(sel);
            if (el != null) return el.text();
        }
        Element og = doc.selectFirst("meta[property=og:description]");
        if (og != null && og.hasAttr("content")) return og.attr("content");
        return "";
    }

    protected String getNovelSynopsisSelector() { return null; }

    protected List<String> extractNovelTags(Document doc) {
        String sel = getNovelTagsSelector();
        if (sel != null) {
            Elements els = doc.select(sel);
            return els.stream().map(Element::text).map(String::strip).collect(Collectors.toList());
        }
        return List.of();
    }

    protected String getNovelTagsSelector() { return null; }

    @Override
    public List<ChapterInfo> getChapters(String novelUrl) {
        // 1. Buscar novela en BD
        SourceNovel novel = persistence.findBySourceIdAndNovelUrl(getSourceId(), novelUrl).orElse(null);
        if (novel != null) {
            log.debug("Cache hit for chapters: {}", novelUrl);
            return novel.getVolumes().stream()
                    .flatMap(v -> v.getChapters().stream()
                            .map(c -> new ChapterInfo(c.getSerial(), c.getTitle(), c.getUrl(), v.getSerial())))
                    .toList();
        }

        // 2. Fallback: HTTP crawl (para compatibilidad con novelas no persistidas aún)
        log.debug("Cache miss for chapters: {}, crawling...", novelUrl);
        try {
            acquirePermit();
            String html = webClient.get()
                    .uri(novelUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            Document doc = Jsoup.parse(html);
            return extractChapters(doc, novelUrl);

        } catch (Exception e) {
            log.error("Get chapters failed for {}: {}", novelUrl, e.getMessage());
            throw new CrawlerException(getSourceId(), "getChapters", "Failed: " + e.getMessage(), e);
        }
    }

    protected List<ChapterInfo> extractChapters(Document doc, String novelUrl) {
        String listSel = getChapterListSelector();
        if (listSel == null || listSel.isEmpty()) {
            log.warn("No chapter list selector for {}", getSourceId());
            return List.of();
        }

        Elements links = doc.select(listSel);
        List<ChapterInfo> chapters = new ArrayList<>();

        int chapterCount = 0;
        for (Element a : links) {
            String href = a.attr("href");
            if (href == null || href.isEmpty()) continue;

            chapterCount++;
            String title = a.text().strip();
            if (title.isEmpty()) title = "Chapter " + chapterCount;

            chapters.add(new ChapterInfo(
                    chapterCount,
                    title,
                    toAbsoluteUrl(href),
                    1 // volumeId por defecto
            ));
        }

        return chapters;
    }

    protected String getChapterListSelector() { return null; }

    @Override
    public ChapterContent getChapterContent(String chapterUrl) {
        // 1. Buscar capítulo por URL en BD
        SourceChapter chapter = persistence.findChapterByUrl(getSourceId(), chapterUrl).orElse(null);
        if (chapter != null) {
            // Verificar caché de contenido en filesystem
            SourceChapterContent content = persistence.findChapterContentById(chapter.getId()).orElse(null);
            boolean stale = content == null || persistence.isStale(content, getVersion(), getTier());

            if (!stale && fileStorage.exists(content.getContentFile())) {
                log.debug("Cache hit for chapter content: {}", chapterUrl);
                String html = fileStorage.loadText(content.getContentFile());
                List<String> images = content.getImages().stream()
                        .map(SourceChapterContent.ChapterImageInfo::getUrl)
                        .toList();
                return ChapterContent.success(html, images);
            }

            // 3. Cache miss o stale: re-crawl + persistir
            log.debug("Cache miss/stale for chapter content: {}, re-crawling...", chapterUrl);
            return crawlAndSaveChapterContent(chapter);
        }

        // 4. Capítulo no en BD: HTTP crawl + persistir (para compatibilidad)
        log.debug("Chapter not in DB: {}, crawling...", chapterUrl);
        try {
            acquirePermit();
            String html = webClient.get()
                    .uri(chapterUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            Document doc = Jsoup.parse(html);
            return parseChapterContent(doc, chapterUrl);

        } catch (Exception e) {
            log.error("Get chapter content failed for {}: {}", chapterUrl, e.getMessage());
            return ChapterContent.failure("Failed: " + e.getMessage());
        }
    }

    /**
     * Crawlea y guarda contenido de capítulo en filesystem + BD.
     */
    protected ChapterContent crawlAndSaveChapterContent(SourceChapter chapter) {
        try {
            acquirePermit();
            String html = webClient.get()
                    .uri(chapter.getUrl())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            Document doc = Jsoup.parse(html);
            ChapterContent result = parseChapterContent(doc, chapter.getUrl());

            if (result.success()) {
                // Guardar en filesystem + BD
                persistence.saveChapterContent(
                        chapter.getId(),
                        result.html(),
                        result.images(),
                        "jsoup",
                        getVersion(),
                        getTier()
                );
            }

            return result;

        } catch (Exception e) {
            log.error("Crawl and save chapter failed for {}: {}", chapter.getUrl(), e.getMessage());
            return ChapterContent.failure("Failed: " + e.getMessage());
        }
    }

    /**
     * Parsea el contenido del capítulo desde el documento HTML.
     * Método protegido para ser sobrescrito por subclases.
     */
    protected ChapterContent parseChapterContent(Document doc, String chapterUrl) {
        String contentSel = getChapterContentSelector();
        Element contentEl = contentSel != null ? doc.selectFirst(contentSel) : null;

        if (contentEl == null) {
            contentEl = findDenseContent(doc);
        }

        if (contentEl == null) {
            return ChapterContent.failure("Chapter content element not found");
        }

        cleanChapterContent(contentEl);

        List<String> images = new ArrayList<>();
        Elements imgElements = contentEl.select("img[src]");
        for (Element img : imgElements) {
            String src = img.attr("src");
            if (!src.isEmpty()) {
                images.add(toAbsoluteUrl(src));
            }
        }

        return ChapterContent.success(contentEl.html(), images);
    }

    protected String getChapterContentSelector() { return null; }

    protected Element findDenseContent(Document doc) {
        Element best = null;
        int maxParagraphs = 0;

        for (Element el : doc.select("div, article, section, main, td")) {
            int paragraphs = el.select("p").size();
            if (paragraphs > maxParagraphs && paragraphs >= 3) {
                maxParagraphs = paragraphs;
                best = el;
            }
        }
        return best;
    }

    protected void cleanChapterContent(Element contentEl) {
        for (String badCss : badCss) {
            contentEl.select(badCss).remove();
        }

        contentEl.select("script, style, noscript, iframe").remove();

        for (String pattern : badTextPatterns) {
            contentEl.select(":matchesOwn(" + Pattern.quote(pattern) + ")").remove();
        }
    }

    @Override
    public void login(String username, String password) {
        throw new UnsupportedOperationException("Login no implementado para " + getSourceId());
    }

    @Override
    public boolean isHealthy() {
        try {
            acquirePermit();
            String html = webClient.get()
                    .uri("/")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return html != null && !html.isEmpty();
        } catch (Exception e) {
            log.warn("Health check failed for {}: {}", getSourceId(), e.getMessage());
            return false;
        }
    }

    /**
     * Obtiene HTML de una URL (para uso interno en persistencia).
     */
    protected String fetchHtml(String url) {
        try {
            acquirePermit();
            return webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (Exception e) {
            log.error("Failed to fetch HTML from {}: {}", url, e.getMessage());
            throw new CrawlerException(getSourceId(), "fetchHtml", "Failed: " + e.getMessage(), e);
        }
    }

    // ─── Helpers ───

    protected void acquirePermit() {
        if (!rateLimiter.acquire(Duration.ofMinutes(5))) {
            log.warn("Rate limiter timeout for {}", getSourceId());
        }
    }

    protected String toAbsoluteUrl(String url) {
        if (url == null || url.isEmpty()) return "";
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        if (url.startsWith("//")) return "https:" + url;
        if (url.startsWith("/")) return baseUrls.get(0).replaceAll("/+$", "") + url;
        return baseUrls.get(0).replaceAll("/+$", "") + "/" + url;
    }

    protected List<VolumeInfo> extractVolumesAndChapters(Document doc, String novelUrl) {
        List<ChapterInfo> allChapters = extractChapters(doc, novelUrl);
        
        List<VolumeInfo> volumes = new ArrayList<>();
        for (int i = 0; i < allChapters.size(); i += 50) {
            int volNum = (i / 50) + 1;
            int end = Math.min(i + 50, allChapters.size());
            List<ChapterInfo> volChapters = allChapters.subList(i, end);
            volumes.add(new VolumeInfo(volNum, "Volume " + volNum, volChapters));
        }

        if (volumes.isEmpty() && !allChapters.isEmpty()) {
            volumes.add(new VolumeInfo(1, "Volume 1", allChapters));
        }

        return volumes;
    }

    // ─── Persistence DTO Conversion Helpers ───

    /**
     * Convierte SourceNovel persistido a NovelSearchResult para API.
     */
    protected NovelSearchResult toSearchResult(SourceNovel novel) {
        String info = String.format("%d vols · %d chs · %s",
                novel.getVolumeCount(),
                novel.getChapterCount(),
                novel.getStatus() != null ? novel.getStatus() : "Unknown");
        return new NovelSearchResult(
                novel.getTitle(),
                novel.getNovelUrl(),
                info,
                novel.getCoverUrl() != null ? novel.getCoverUrl() : "",
                novel.getNovelUrl()
        );
    }

    /**
     * Convierte SourceNovel persistido a NovelDetail para API.
     */
    protected NovelDetail toNovelDetail(SourceNovel novel) {
        List<VolumeInfo> volumes = novel.getVolumes().stream()
                .map(v -> new VolumeInfo(
                        v.getSerial(),
                        v.getTitle(),
                        v.getChapters().stream()
                                .map(c -> new ChapterInfo(
                                        c.getSerial(),
                                        c.getTitle(),
                                        c.getUrl(),
                                        v.getSerial()
                                ))
                                .toList()
                ))
                .toList();

        return new NovelDetail(
                novel.getTitle(),
                novel.getAuthor() != null ? novel.getAuthor() : "",
                novel.getCoverUrl() != null ? novel.getCoverUrl() : "",
                novel.getSynopsis() != null ? novel.getSynopsis() : "",
                novel.getTags() != null ? novel.getTags() : List.of(),
                volumes,
                novel.getLanguage() != null ? novel.getLanguage() : "en",
                novel.isMtl(),
                novel.isManga(),
                novel.getNovelUrl(),
                novel.getStatus() != null ? novel.getStatus() : "Unknown",
                novel.getLastUpdate() != null ? novel.getLastUpdate() : ""
        );
    }

    /**
     * Obtiene versión del crawler para versionado de caché.
     * Sobrescribir en subclase si se tiene versión específica.
     */
    protected String getVersion() {
        return "1.0.0";
    }

    /**
     * Obtiene tier del crawler para versionado de caché.
     * Sobrescribir en subclase si se tiene tier específico.
     */
    protected String getTier() {
        return "generated";
    }
}
