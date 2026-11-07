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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Foxaholic.com crawler implementation.
 * Port of Python lncrawl FoxaholicCrawler (LegacyCrawler).
 * 
 * Sources: https://foxaholic.com/, https://www.foxaholic.com/, https://18.foxaholic.com/, https://global.foxaholic.com/
 * Pattern: WordPress manga theme with AJAX chapter loading on some subdomains.
 * Uses Jsoup for HTML parsing, WebClient for HTTP.
 */
@Slf4j
@Component
@Qualifier("foxaholic")
public class FoxaholicCrawler implements SourceCrawler {

    private static final String[] BASE_URLS = {
        "https://foxaholic.com/",
        "https://www.foxaholic.com/",
        "https://18.foxaholic.com/",
        "https://global.foxaholic.com/"
    };
    private static final String SEARCH_URL_TEMPLATE = "https://www.foxaholic.com/?s=%s&post_type=wp-manga";
    private static final String AJAX_CHAPTER_URL = "/wp-admin/admin-ajax.php";
    
    private static final Pattern SUBDOMAIN_PATTERN = Pattern.compile("https?://([^/]+)");

    private final WebClient webClient;
    private final CrawlerProperties.SourceConfig config;
    private final int rateLimitRpm;

    public FoxaholicCrawler(WebClient.Builder webClientBuilder, CrawlerProperties properties) {
        this.config = properties.getSources().get("foxaholic");
        this.rateLimitRpm = config != null ? config.getRateLimitRpm() : 30;
        
        this.webClient = webClientBuilder
                .baseUrl(BASE_URLS[1]) // www.foxaholic.com
                .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build();
    }

    @Override
    public String getSourceId() {
        return "foxaholic";
    }

    @Override
    public String getDisplayName() {
        return "Foxaholic";
    }

    @Override
    public String getIcon() {
        return "pi pi-globe";
    }

    @Override
    public String getLanguage() {
        return "multi";
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
        log.debug("Searching Foxaholic for: {}", query);
        
        try {
            String encodedQuery = URLEncoder.encode(query.toLowerCase().replace(" ", "+"), StandardCharsets.UTF_8);
            String searchUrl = String.format(SEARCH_URL_TEMPLATE, encodedQuery);
            
            String html = webClient.get()
                    .uri(searchUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            
            Document doc = Jsoup.parse(html);
            
            Elements resultElements = doc.select(".c-tabs-item__content");
            List<NovelSearchResult> results = new ArrayList<>();
            
            for (Element tab : resultElements) {
                Element titleLink = tab.selectFirst(".post-title h3 a");
                if (titleLink == null) continue;
                
                String title = titleLink.text().strip();
                String url = toAbsoluteUrl(titleLink.absUrl("href"));
                
                StringBuilder info = new StringBuilder();
                
                Element latestEl = tab.selectFirst(".latest-chap .chapter a");
                if (latestEl != null) {
                    info.append(latestEl.text().strip());
                }
                
                Element statusEl = tab.selectFirst(".mg_release .summary-content a");
                if (statusEl != null) {
                    if (!info.isEmpty()) info.append(" | ");
                    info.append("Status: ").append(statusEl.text().strip());
                }
                
                if (!title.isEmpty() && !url.isEmpty()) {
                    results.add(new NovelSearchResult(
                            title,
                            url,
                            info.toString(),
                            "", // cover not in search results
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
        log.debug("Getting novel details from Foxaholic: {}", novelUrl);
        
        try {
            // Determine base URL from the novel URL
            String baseUrl = extractBaseUrl(novelUrl);
            
            String html = webClient.get()
                    .uri(novelUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            
            Document doc = Jsoup.parse(html);
            
            // Title from og:title
            Element titleEl = doc.selectFirst("meta[property=og:title]");
            if (titleEl == null || !titleEl.hasAttr("content")) {
                throw new CrawlerException(getSourceId(), "getNovelDetails", "Title not found: " + novelUrl);
            }
            String title = titleEl.attr("content");
            
            // Cover from summary_image
            String coverUrl = "";
            Element coverEl = doc.selectFirst(".summary_image a img");
            if (coverEl != null) {
                if (coverEl.hasAttr("data-src")) {
                    coverUrl = toAbsoluteUrl(coverEl.attr("data-src"));
                } else if (coverEl.hasAttr("src")) {
                    coverUrl = toAbsoluteUrl(coverEl.attr("src"));
                }
            }
            
            // Author
            String author = "";
            Elements authorLinks = doc.select(".author-content a[href*=novel-author]");
            for (Element a : authorLinks) {
                if (!author.isEmpty()) author += " ";
                author += a.text().strip();
            }
            
            // Check if this subdomain uses AJAX for chapters
            List<ChapterInfo> chapters = new ArrayList<>();
            if (novelUrl.contains("18.foxaholic.com") || novelUrl.contains("global.foxaholic.com")) {
                chapters = fetchChaptersViaAjax(doc, baseUrl);
            } else {
                chapters = fetchChaptersFromHtml(doc);
            }
            
            List<VolumeInfo> volumes = groupChaptersIntoVolumes(chapters);
            
            return new NovelDetail(
                    title,
                    author,
                    coverUrl,
                    "", // synopsis not easily available
                    List.of(),
                    volumes,
                    "multi",
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
            String baseUrl = extractBaseUrl(novelUrl);
            
            String html = webClient.get()
                    .uri(novelUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            
            Document doc = Jsoup.parse(html);
            
            List<ChapterInfo> chapters;
            if (novelUrl.contains("18.foxaholic.com") || novelUrl.contains("global.foxaholic.com")) {
                chapters = fetchChaptersViaAjax(doc, baseUrl);
            } else {
                chapters = fetchChaptersFromHtml(doc);
            }
            
            return chapters;
            
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
            
            // Check for blocked chapter redirect
            Element blockedLink = doc.selectFirst(".text-left");
            if (blockedLink != null) {
                Element chapterLink = blockedLink.selectFirst("a:contains(Chapter)");
                if (chapterLink != null && chapterLink.hasAttr("href")) {
                    String redirectUrl = toAbsoluteUrl(chapterLink.absUrl("href"));
                    log.info("Blocked chapter detected, following redirect: {}", redirectUrl);
                    return getChapterContent(redirectUrl);
                }
            }
            
            // Chapter content: .entry-content_wrap
            Element contentEl = doc.selectFirst(".entry-content_wrap");
            if (contentEl == null) {
                // Fallback
                contentEl = doc.selectFirst(".entry-content, .chapter-content, .text-left");
            }
            if (contentEl == null) {
                return ChapterContent.failure("Chapter content element not found");
            }
            
            // Clean content
            contentEl.select("script, style, noscript, iframe").remove();
            
            // Extract images
            List<String> images = new ArrayList<>();
            Elements imgElements = contentEl.select("img[src]");
            for (Element img : imgElements) {
                String src = img.absUrl("src");
                if (!src.isEmpty()) {
                    images.add(src);
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
            return html != null && html.contains("foxaholic");
        } catch (Exception e) {
            log.warn("Foxaholic health check failed: {}", e.getMessage());
            return false;
        }
    }

    // ==================== Private Helper Methods ====================

    private String extractBaseUrl(String novelUrl) {
        try {
            URI uri = new URI(novelUrl);
            return uri.getScheme() + "://" + uri.getHost();
        } catch (Exception e) {
            return BASE_URLS[1];
        }
    }

    private List<ChapterInfo> fetchChaptersFromHtml(Document doc) {
        List<ChapterInfo> chapters = new ArrayList<>();
        
        // Selector from Python: .wp-manga-chapter a (reversed)
        Elements links = doc.select(".wp-manga-chapter a");
        
        // Reverse to get chronological order
        List<Element> reversed = new ArrayList<>(links);
        java.util.Collections.reverse(reversed);
        
        int chapterCount = 0;
        for (Element a : reversed) {
            chapterCount++;
            int volId = 1 + (chapterCount - 1) / 100;
            
            String title = a.text().strip();
            String href = toAbsoluteUrl(a.absUrl("href"));
            
            chapters.add(new ChapterInfo(
                    chapterCount,
                    title,
                    href,
                    volId
            ));
        }
        
        return chapters;
    }

    private List<ChapterInfo> fetchChaptersViaAjax(Document doc, String baseUrl) {
        List<ChapterInfo> chapters = new ArrayList<>();
        
        try {
            // Get manga ID from #manga-chapters-holder data-id
            Element holder = doc.selectFirst("#manga-chapters-holder");
            if (holder == null || !holder.hasAttr("data-id")) {
                log.warn("Manga chapters holder not found, falling back to HTML parsing");
                return fetchChaptersFromHtml(doc);
            }
            
            String mangaId = holder.attr("data-id");
            String ajaxUrl = baseUrl + AJAX_CHAPTER_URL;
            
            // POST form data
            Map<String, String> formData = Map.of(
                    "action", "manga_get_chapters",
                    "manga", mangaId
            );
            
            String responseHtml = webClient.post()
                    .uri(ajaxUrl)
                    .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue("action=manga_get_chapters&manga=" + mangaId)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            
            Document ajaxDoc = Jsoup.parse(responseHtml);
            
            // Parse chapters from AJAX response
            Elements links = ajaxDoc.select(".wp-manga-chapter a");
            List<Element> reversed = new ArrayList<>(links);
            java.util.Collections.reverse(reversed);
            
            int chapterCount = 0;
            for (Element a : reversed) {
                chapterCount++;
                int volId = 1 + (chapterCount - 1) / 100;
                
                String title = a.text().strip();
                String href = toAbsoluteUrl(a.absUrl("href"));
                
                chapters.add(new ChapterInfo(
                        chapterCount,
                        title,
                        href,
                        volId
                ));
            }
            
        } catch (Exception e) {
            log.error("AJAX chapter fetch failed: {}", e.getMessage());
            // Fallback to HTML parsing
            return fetchChaptersFromHtml(doc);
        }
        
        return chapters;
    }

    private List<VolumeInfo> groupChaptersIntoVolumes(List<ChapterInfo> chapters) {
        if (chapters.isEmpty()) {
            return List.of();
        }
        
        java.util.Map<Integer, List<ChapterInfo>> volumeMap = new java.util.LinkedHashMap<>();
        for (ChapterInfo chapter : chapters) {
            volumeMap.computeIfAbsent(chapter.volumeId(), k -> new ArrayList<>()).add(chapter);
        }
        
        List<VolumeInfo> volumes = new ArrayList<>();
        for (Map.Entry<Integer, List<ChapterInfo>> entry : volumeMap.entrySet()) {
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
            return "https://www.foxaholic.com" + url;
        }
        return "https://www.foxaholic.com/" + url;
    }
}