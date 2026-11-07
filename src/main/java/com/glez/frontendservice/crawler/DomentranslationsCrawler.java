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

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Domentranslations.wordpress.com / Cclawtranslations.home.blog crawler implementation.
 * Port of Python lncrawl CclawTranslations (LegacyCrawler).
 * 
 * Sources: https://domentranslations.wordpress.com/ and https://cclawtranslations.home.blog/
 * Pattern: WordPress.com blog with chapter links in entry-content.
 * Uses Jsoup for HTML parsing, WebClient for HTTP.
 */
@Slf4j
@Component
@Qualifier("domentranslations")
public class DomentranslationsCrawler implements SourceCrawler {

    private static final String[] BASE_URLS = {
        "https://cclawtranslations.home.blog/",
        "https://domentranslations.wordpress.com/"
    };
    
    private static final Pattern CHAPTER_URL_PATTERN = Pattern.compile("https?://[^/]+/\\d{4}/\\d{2}/");

    private final WebClient webClient;
    private final CrawlerProperties.SourceConfig config;
    private final int rateLimitRpm;

    public DomentranslationsCrawler(WebClient.Builder webClientBuilder, CrawlerProperties properties) {
        this.config = properties.getSources().get("domentranslations");
        this.rateLimitRpm = config != null ? config.getRateLimitRpm() : 60;
        
        // Use the primary base URL
        this.webClient = webClientBuilder
                .baseUrl(BASE_URLS[1]) // domentranslations.wordpress.com
                .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build();
    }

    @Override
    public String getSourceId() {
        return "domentranslations";
    }

    @Override
    public String getDisplayName() {
        return "Domentranslations";
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
        return BASE_URLS[1];
    }

    @Override
    public int getRateLimitRpm() {
        return rateLimitRpm;
    }

    @Override
    public boolean requiresLogin() {
        return false;
    }

    @Override
    public List<NovelSearchResult> search(String query) {
        log.debug("Searching Domentranslations for: {}", query);
        
        try {
            // WordPress.com search
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String searchUrl = BASE_URLS[1] + "?s=" + encodedQuery;
            
            String html = webClient.get()
                    .uri(searchUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            
            Document doc = Jsoup.parse(html);
            
            // Search results on WordPress.com
            Elements resultElements = doc.select(".post, .entry, article, .search-result");
            
            List<NovelSearchResult> results = new ArrayList<>();
            for (Element el : resultElements) {
                Element titleEl = el.selectFirst("h1 a, h2 a, h3 a, .entry-title a, .post-title a");
                Element linkEl = el.selectFirst("a[href]");
                Element imgEl = el.selectFirst("img[src]");
                
                if (titleEl == null || linkEl == null) continue;
                
                String title = titleEl.text().strip();
                String url = linkEl.absUrl("href");
                String coverUrl = imgEl != null ? imgEl.absUrl("src") : "";
                
                if (!title.isEmpty() && !url.isEmpty()) {
                    results.add(new NovelSearchResult(
                            title,
                            url,
                            "",
                            coverUrl,
                            ""
                    ));
                }
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
        log.debug("Getting novel details from Domentranslations: {}", novelUrl);
        
        try {
            String html = webClient.get()
                    .uri(novelUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            
            Document doc = Jsoup.parse(html);
            
            // Title from og:title meta
            Element titleEl = doc.selectFirst("meta[property=og:title]");
            if (titleEl == null || !titleEl.hasAttr("content")) {
                // Fallback to h1
                titleEl = doc.selectFirst("h1.entry-title, h1.post-title, h1");
            }
            if (titleEl == null) {
                throw new CrawlerException(getSourceId(), "getNovelDetails", "Title not found: " + novelUrl);
            }
            String title = titleEl.hasAttr("content") ? titleEl.attr("content") : titleEl.text().strip();
            
            // Cover from og:image
            String coverUrl = "";
            Element coverEl = doc.selectFirst("meta[property=og:image]");
            if (coverEl != null && coverEl.hasAttr("content")) {
                coverUrl = toAbsoluteUrl(coverEl.attr("content"));
            }
            
            // Author from og:site_name
            String author = "";
            Element authorEl = doc.selectFirst("meta[property=og:site_name]");
            if (authorEl != null && authorEl.hasAttr("content")) {
                author = authorEl.attr("content");
            }
            
            // Extract chapters from entry-content links
            List<ChapterInfo> chapters = extractChapters(doc, novelUrl);
            List<VolumeInfo> volumes = groupChaptersIntoVolumes(chapters);
            
            return new NovelDetail(
                    title,
                    author,
                    coverUrl,
                    "", // synopsis not easily available
                    List.of(),
                    volumes,
                    "es",
                    false,
                    false,
                    novelUrl,
                    "Ongoing",
                    ""
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
            
            // Chapter body: div.entry-content
            Element contentEl = doc.selectFirst("div.entry-content");
            if (contentEl == null) {
                return ChapterContent.failure("Chapter content element not found");
            }
            
            // Remove non-TOC elements from Python implementation
            contentEl.select(".sharedaddy, .inline-ad-slot, .code-block, script, hr, .adsbygoogle").remove();
            contentEl.select("script, style, noscript, iframe").remove();
            
            // Fix images - ensure they have proper src
            Elements images = contentEl.select("img[data-orig-file]");
            for (Element img : images) {
                String src = img.attr("data-orig-file");
                if (!src.isEmpty()) {
                    img.attr("src", src);
                }
            }
            
            // Extract images
            List<String> imageUrls = new ArrayList<>();
            Elements imgElements = contentEl.select("img[src]");
            for (Element img : imgElements) {
                String src = img.absUrl("src");
                if (!src.isEmpty()) {
                    imageUrls.add(src);
                }
            }
            
            String cleanHtml = contentEl.html();
            
            return ChapterContent.success(cleanHtml, imageUrls);
            
        } catch (Exception e) {
            log.error("Get chapter content failed for {}: {}", chapterUrl, e.getMessage());
            return ChapterContent.failure("Failed to get chapter content: " + e.getMessage());
        }
    }

    @Override
    public void login(String username, String password) {
        throw new UnsupportedOperationException("Login not required for catalog access");
    }

    @Override
    public boolean isHealthy() {
        try {
            String html = webClient.get()
                    .uri(BASE_URLS[1])
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return html != null && (html.contains("domentranslations") || html.contains("cclawtranslations"));
        } catch (Exception e) {
            log.warn("Domentranslations health check failed: {}", e.getMessage());
            return false;
        }
    }

    // ==================== Private Helper Methods ====================

    private List<ChapterInfo> extractChapters(Document doc, String novelUrl) {
        List<ChapterInfo> chapters = new ArrayList<>();
        
        // Extract hostname for URL filtering
        String hostname = "";
        try {
            hostname = new URI(novelUrl).getHost();
        } catch (Exception ignored) {}
        
        Pattern pattern = Pattern.compile(Pattern.quote(hostname) + "/\\d{4}/\\d{2}/");
        
        Elements links = doc.select("div.entry-content a");
        int chapterCount = 0;
        
        for (Element a : links) {
            String href = a.absUrl("href");
            if (href.isEmpty()) continue;
            
            // Filter by pattern: hostname/YYYY/MM/
            if (!pattern.matcher(href).find()) continue;
            
            chapterCount++;
            int volId = (chapterCount - 1) / 100 + 1;
            
            String title = a.text().strip();
            if (title.isEmpty()) {
                title = "Capítulo " + chapterCount;
            }
            
            chapters.add(new ChapterInfo(
                    chapterCount,
                    title,
                    href,
                    volId
            ));
        }
        
        return chapters;
    }

    private List<VolumeInfo> groupChaptersIntoVolumes(List<ChapterInfo> chapters) {
        if (chapters.isEmpty()) {
            return List.of();
        }
        
        // Group by volume ID
        java.util.Map<Integer, List<ChapterInfo>> volumeMap = new java.util.LinkedHashMap<>();
        for (ChapterInfo chapter : chapters) {
            volumeMap.computeIfAbsent(chapter.volumeId(), k -> new ArrayList<>()).add(chapter);
        }
        
        List<VolumeInfo> volumes = new ArrayList<>();
        for (java.util.Map.Entry<Integer, List<ChapterInfo>> entry : volumeMap.entrySet()) {
            volumes.add(new VolumeInfo(
                    entry.getKey(),
                    "Volumen " + entry.getKey(),
                    entry.getValue()
            ));
        }
        
        return volumes;
    }

    private String toAbsoluteUrl(String url) {
        if (url == null || url.isEmpty()) return "";
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        if (url.startsWith("//")) return "https:" + url;
        if (url.startsWith("/")) {
            // Determine base from known URLs
            return "https://domentranslations.wordpress.com" + url;
        }
        return "https://domentranslations.wordpress.com/" + url;
    }
}