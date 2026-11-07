package com.glez.frontendservice.crawler;

import com.glez.frontendservice.config.CrawlerProperties;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NovelasLigeras.net crawler implementation.
 * Port of Python lncrawl NovelasLigerasCrawler (LegacyCrawler).
 * 
 * Source: https://novelasligeras.net/
 * Pattern: WooCommerce product catalog with custom chapter listing.
 * Uses Jsoup for HTML parsing, WebClient for HTTP.
 */
@Slf4j
@Component
@Qualifier("novelasligeras")
public class NovelasLigerasCrawler implements SourceCrawler {

    private static final String SEARCH_URL_TEMPLATE = 
        "https://novelasligeras.net/?post_type=product&title=1&excerpt=1&content=0&categories=1&attributes=1" +
        "&tags=1&sku=0&orderby=title-DESC&ixwps=1&s=%s";
    
    private static final Pattern VOLUME_PATTERN = Pattern.compile("-volumen-(\\d+)-");
    private static final Pattern CHAPTER_URL_PATTERN = Pattern.compile(
        "https?://[^/]+/index\\.php/\\d{4}/\\d{2}/\\d{2}/");

    private final WebClient webClient;
    private final CrawlerProperties.SourceConfig config;
    private final int rateLimitRpm;

    public NovelasLigerasCrawler(WebClient.Builder webClientBuilder, CrawlerProperties properties) {
        this.config = properties.getSources().get("novelasligeras");
        this.rateLimitRpm = config != null ? config.getRateLimitRpm() : 30;
        
        this.webClient = webClientBuilder
                .baseUrl("https://novelasligeras.net")
                .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build();
    }

    @Override
    public String getSourceId() {
        return "novelasligeras";
    }

    @Override
    public String getDisplayName() {
        return "Novelas Ligeras";
    }

    @Override
    public String getIcon() {
        return "pi pi-book";
    }

    @Override
    public String getLanguage() {
        return "es";
    }

    @Override
    public String getBaseUrl() {
        return "https://novelasligeras.net/";
    }

    @Override
    public int getRateLimitRpm() {
        return rateLimitRpm;
    }

    @Override
    public boolean requiresLogin() {
        return false; // Catalog browsing doesn't require login
    }

    @Override
    public List<NovelSearchResult> search(String query) {
        log.debug("Searching NovelasLigeras for: {}", query);
        String encodedQuery = query.toLowerCase().replace(" ", "+");
        String searchUrl = String.format(SEARCH_URL_TEMPLATE, encodedQuery);
        
        try {
            String html = webClient.get()
                    .uri(searchUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            
            Document doc = Jsoup.parse(html);
            Elements resultElements = doc.select(".wf-cell[data-post-id]");
            
            List<NovelSearchResult> results = new ArrayList<>();
            for (Element tab : resultElements) {
                String title = tab.attr("data-name");
                String rating = "N/A";
                
                Element ratingEl = tab.selectFirst(".star-rating");
                if (ratingEl != null && ratingEl.hasAttr("aria-label")) {
                    rating = ratingEl.attr("aria-label");
                }
                
                Element urlEl = tab.selectFirst(".alignnone");
                if (urlEl == null || !urlEl.hasAttr("href")) {
                    continue;
                }
                String url = urlEl.attr("href");
                
                results.add(new NovelSearchResult(
                        title.strip(),
                        toAbsoluteUrl(url),
                        "Clasificación: " + rating,
                        "", // cover URL not in search results
                        ""  // sourceNovelId - use URL as ID
                ));
            }
            
            log.debug("Found {} results for query '{}'", results.size(), query);
            return results;
            
        } catch (Exception e) {
            log.error("Search failed for query '{}': {}", query, e.getMessage());
            throw new CrawlerException(getSourceId(), "search", "Search failed: " + e.getMessage(), e);
        }
    }

    @Override
    public NovelDetail getNovelDetails(String novelUrl) {
        log.debug("Getting novel details from NovelasLigeras: {}", novelUrl);
        
        try {
            String html = webClient.get()
                    .uri(novelUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            
            Document doc = Jsoup.parse(html);
            
            // Title
            Element titleEl = doc.selectFirst("h1.product_title");
            if (titleEl == null) {
                throw new CrawlerException(getSourceId(), "getNovelDetails", "Title not found: " + novelUrl);
            }
            String title = titleEl.text().strip();
            
            // Author
            String author = "";
            Element authorEl = doc.selectFirst("tr.woocommerce-product-attributes-item--attribute_pa_escritor a[rel=tag]");
            if (authorEl != null) {
                author = authorEl.text().strip();
            }
            
            // Cover
            String coverUrl = "";
            Element coverEl = doc.selectFirst("meta[property=og:image]");
            if (coverEl != null && coverEl.hasAttr("content")) {
                coverUrl = toAbsoluteUrl(coverEl.attr("content"));
            }
            
            // Synopsis
            String synopsis = "";
            Element synopsisEl = doc.selectFirst(".woocommerce-product-details__short-description");
            if (synopsisEl != null) {
                synopsis = synopsisEl.text();
            }
            
            // Tags/Genres - extract from product attributes
            List<String> tags = new ArrayList<>();
            Elements tagElements = doc.select("tr.woocommerce-product-attributes-item--attribute_pa_genero a[rel=tag]");
            for (Element tagEl : tagElements) {
                tags.add(tagEl.text().strip());
            }
            
            // Chapters and Volumes
            List<VolumeInfo> volumes = extractVolumesAndChapters(doc, novelUrl);
            
            return new NovelDetail(
                    title,
                    author,
                    coverUrl,
                    synopsis,
                    tags,
                    volumes,
                    "es",
                    false, // isMtl
                    false, // isManga
                    novelUrl, // sourceNovelId
                    "Ongoing", // status - default
                    "" // lastUpdate
            );
            
        } catch (CrawlerException e) {
            throw e;
        } catch (Exception e) {
            log.error("Get novel details failed for {}: {}", novelUrl, e.getMessage());
            throw new CrawlerException(getSourceId(), "getNovelDetails", "Failed to get novel details: " + e.getMessage(), e);
        }
    }

    @Override
    public List<ChapterInfo> getChapters(String novelUrl) {
        log.debug("Getting chapters for novel: {}", novelUrl);
        
        try {
            String html = webClient.get()
                    .uri(novelUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            
            Document doc = Jsoup.parse(html);
            return extractChapters(doc, novelUrl);
            
        } catch (Exception e) {
            log.error("Get chapters failed for {}: {}", novelUrl, e.getMessage());
            throw new CrawlerException(getSourceId(), "getChapters", "Failed to get chapters: " + e.getMessage(), e);
        }
    }

    @Override
    public ChapterContent getChapterContent(String chapterUrl) {
        log.debug("Getting chapter content: {}", chapterUrl);
        
        try {
            String html = webClient.get()
                    .uri(chapterUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            
            Document doc = Jsoup.parse(html);
            
            // Chapter content selector from Python: .wpb_text_column > div:nth-child(1)
            Element contentEl = doc.selectFirst(".wpb_text_column > div:nth-child(1)");
            if (contentEl == null) {
                return ChapterContent.failure("Chapter content element not found");
            }
            
            // Clean content - remove bad elements
            cleanChapterContent(contentEl);
            
            // Extract images
            List<String> images = new ArrayList<>();
            Elements imgElements = contentEl.select("img[src]");
            for (Element img : imgElements) {
                String src = img.attr("src");
                if (!src.isEmpty()) {
                    images.add(toAbsoluteUrl(src));
                }
            }
            
            String cleanHtml = contentEl.html();
            
            return ChapterContent.success(cleanHtml, images);
            
        } catch (Exception e) {
            log.error("Get chapter content failed for {}: {}", chapterUrl, e.getMessage());
            return ChapterContent.failure("Failed to get chapter content: " + e.getMessage());
        }
    }

    @Override
    public void login(String username, String password) {
        log.warn("Login not implemented for NovelasLigeras - catalog browsing doesn't require login");
        throw new UnsupportedOperationException("Login not required for catalog access");
    }

    @Override
    public boolean isHealthy() {
        try {
            String html = webClient.get()
                    .uri("/")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return html != null && html.contains("novelasligeras");
        } catch (Exception e) {
            log.warn("NovelasLigeras health check failed: {}", e.getMessage());
            return false;
        }
    }

    // ==================== Private Helper Methods ====================

    private List<VolumeInfo> extractVolumesAndChapters(Document doc, String novelUrl) {
        List<VolumeInfo> volumes = new ArrayList<>();
        List<ChapterInfo> allChapters = extractChapters(doc, novelUrl);
        
        // Group chapters by volume
        allChapters.stream()
                .collect(java.util.stream.Collectors.groupingBy(ChapterInfo::volumeId))
                .forEach((volId, chapters) -> {
                    String volTitle = chapters.isEmpty() ? "Volumen " + volId : chapters.get(0).title();
                    // Use first chapter's volume title if available
                    for (ChapterInfo ch : chapters) {
                        if (ch.title().contains("Volumen")) {
                            volTitle = ch.title();
                            break;
                        }
                    }
                    volumes.add(new VolumeInfo(volId, volTitle, chapters));
                });
        
        // Sort by volume ID
        volumes.sort((a, b) -> Integer.compare(a.id(), b.id()));
        return volumes;
    }

    private List<ChapterInfo> extractChapters(Document doc, String novelUrl) {
        List<ChapterInfo> chapters = new ArrayList<>();
        
        // Python selector: .wpb_wrapper a:not([id],[title],[href$='suscripciones/'],[href*='patreon'],[href*='paypal'])
        Elements links = doc.select(".wpb_wrapper a");
        
        int chapterCount = 0;
        int lastVolId = 0;
        
        // Extract hostname for pattern matching
        String hostname = "";
        try {
            hostname = new URI(novelUrl).getHost();
        } catch (Exception ignored) {}
        Pattern urlPattern = Pattern.compile(
            Pattern.quote(hostname) + "/index\\.php/\\d{4}/\\d{2}/\\d{2}/");
        
        for (Element a : links) {
            // Skip excluded links
            if (a.hasAttr("id") || a.hasAttr("title")) continue;
            String href = a.attr("href");
            if (href.endsWith("suscripciones/") || href.contains("patreon") || href.contains("paypal")) continue;
            
            // Match chapter URL pattern
            if (!urlPattern.matcher(href).find()) continue;
            
            chapterCount++;
            int chapId = chapterCount;
            
            // Extract volume from URL
            Matcher volMatcher = VOLUME_PATTERN.matcher(href);
            int volId = lastVolId;
            if (volMatcher.find()) {
                volId = Integer.parseInt(volMatcher.group(1));
                lastVolId = volId;
            }
            
            String tempTitle = a.text().strip();
            tempTitle = tempTitle.replaceAll("\\bCapitulo\\b", "Capítulo");
            
            String title;
            if (tempTitle.contains("Parte") && tempTitle.contains("Capítulo")) {
                String[] partes = tempTitle.split(" – ");
                title = String.join(" – ", java.util.List.of(partes).reversed());
            } else {
                title = tempTitle;
            }
            
            chapters.add(new ChapterInfo(
                    chapId,
                    title,
                    toAbsoluteUrl(href),
                    volId
            ));
        }
        
        return chapters;
    }

    private void cleanChapterContent(Element contentEl) {
        // Remove bad elements from Python cleaner
        contentEl.select("div[style]").remove();
        contentEl.select(":containsOwn(Publicidad)").remove();
        
        // Remove scripts, styles, etc.
        contentEl.select("script, style, noscript, iframe").remove();
    }

    private String toAbsoluteUrl(String url) {
        if (url == null || url.isEmpty()) return "";
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        if (url.startsWith("//")) return "https:" + url;
        if (url.startsWith("/")) return "https://novelasligeras.net" + url;
        return "https://novelasligeras.net/" + url;
    }
}