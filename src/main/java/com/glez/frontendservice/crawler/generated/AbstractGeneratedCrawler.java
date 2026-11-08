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
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
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
        log.debug("Searching {} for: {}", getSourceId(), query);
        
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
            return parseSearchResults(doc, query);

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
        log.debug("Getting novel details from {}: {}", getSourceId(), novelUrl);

        try {
            acquirePermit();
            String html = webClient.get()
                    .uri(novelUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            Document doc = Jsoup.parse(html);
            return parseNovelDetails(doc, novelUrl);

        } catch (Exception e) {
            log.error("Get novel details failed for {}: {}", novelUrl, e.getMessage());
            throw new CrawlerException(getSourceId(), "getNovelDetails", "Failed: " + e.getMessage(), e);
        }
    }

    protected NovelDetail parseNovelDetails(Document doc, String novelUrl) {
        String title = extractNovelTitle(doc);
        String author = extractNovelAuthor(doc);
        String cover = extractNovelCover(doc);
        String synopsis = extractNovelSynopsis(doc);
        List<String> tags = extractNovelTags(doc);
        List<VolumeInfo> volumes = extractVolumesAndChapters(doc, novelUrl);

        return new NovelDetail(
                title,
                author,
                cover,
                synopsis,
                tags,
                volumes,
                language,
                false, // isMtl
                false, // isManga
                novelUrl,
                "Ongoing",
                ""
        );
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
        log.debug("Getting chapters for novel: {}", novelUrl);

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
        log.debug("Getting chapter content: {}", chapterUrl);

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
}
