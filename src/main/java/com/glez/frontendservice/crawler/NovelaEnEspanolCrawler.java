package com.glez.frontendservice.crawler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glez.frontendservice.config.CrawlerProperties;
import com.glez.frontendservice.config.RateLimiterConfig;
import com.glez.frontendservice.crawler.CloudflareBypassService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NovelaEnEspanol.com crawler implementation.
 * Port of Python lncrawl NovelaEnEspanolCrawler (WpCategoryTemplate).
 * 
 * Source: https://novelaenespanol.com/
 * Pattern: WordPress category-based novel site using REST API.
 * Uses WebClient for HTTP, Jackson for JSON parsing.
 * Respects per-source rate limits via shared TokenBucketRateLimiter.
 */
@Slf4j
@Component
@Qualifier("novelaenespanol")
public class NovelaEnEspanolCrawler implements SourceCrawler {

    private static final String API_ROOT = "wp-json/wp/v2";
    private static final Pattern NOVEL_URL_PATTERN = Pattern.compile("/novela-ligera/([^/?#]+)");
    private static final int POSTS_PER_PAGE = 100;

    private final WebClient webClient;
    private final CloudflareBypassService cloudflareBypassService;
    private final ObjectMapper objectMapper;
    private final CrawlerProperties.SourceConfig config;
    private final RateLimiter rateLimiter;

    public NovelaEnEspanolCrawler(WebClient.Builder webClientBuilder,
                                   ObjectMapper objectMapper,
                                   CrawlerProperties properties,
                                   RateLimiterConfig.RateLimiterProvider rateLimiterProvider,
                                   CloudflareBypassService cloudflareBypassService) {
        this.objectMapper = objectMapper;
        this.config = properties.getSources().get("novelaenespanol");
        this.rateLimiter = rateLimiterProvider.getLimiter("novelaenespanol");
        this.cloudflareBypassService = cloudflareBypassService;
        
        this.webClient = webClientBuilder
                .baseUrl("https://novelaenespanol.com")
                .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    // ==================== Cloudflare Bypass Helpers ====================

    /**
     * Fetch HTML using Cloudflare bypass service if enabled for this source.
     */
    private String fetchWithCloudflareBypass(String url) {
        // Check if Cloudflare bypass is configured for this source
        if (config != null && config.isCloudflareBypass()) {
            String baseUrl = getBaseUrl();
            CloudflareBypassService.CloudflareSession session = cloudflareBypassService.getSession(baseUrl);
            if (session != null) {
                log.debug("Using Cloudflare bypass session for {}", baseUrl);
                String clearanceCookie = extractClearanceCookie(session.cookies());
                try {
                    acquirePermit();
                    return webClient.get()
                            .uri(url)
                            .header("User-Agent", session.userAgent())
                            .cookie("cf_clearance", clearanceCookie)
                            .retrieve()
                            .bodyToMono(String.class)
                            .block();
                } catch (Exception e) {
                    log.warn("Cloudflare bypass request failed, invalidating session: {}", e.getMessage());
                    cloudflareBypassService.invalidateSession(baseUrl);
                    throw e;
                }
            }
        }
        
        // Fallback to direct request
        acquirePermit();
        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    /**
     * Extract cf_clearance cookie from cookie string.
     */
    private String extractClearanceCookie(String cookies) {
        if (cookies == null || cookies.isBlank()) {
            return null;
        }
        
        // Try to parse as JSON array first (FlareSolverr returns array of cookie objects)
        if (cookies.trim().startsWith("[")) {
            try {
                com.fasterxml.jackson.databind.JsonNode cookiesArray = objectMapper.readTree(cookies);
                if (cookiesArray.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode cookie : cookiesArray) {
                        if (cookie.has("name") && "cf_clearance".equals(cookie.get("name").asText()) 
                                && cookie.has("value")) {
                            return cookie.get("value").asText();
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to parse cookies as JSON array: {}", e.getMessage());
            }
        }
        
        // Fallback: try semicolon-separated format
        for (String cookie : cookies.split(";")) {
            cookie = cookie.trim();
            if (cookie.startsWith("cf_clearance=")) {
                return cookie.substring("cf_clearance=".length());
            }
        }
        return null;
    }

    /**
     * Acquire rate limiter permit before making an HTTP request.
     * Blocks until permit available or timeout (5 min).
     */
    private void acquirePermit() {
        if (!rateLimiter.acquire(Duration.ofMinutes(5))) {
            log.warn("Rate limiter timeout for {}", getSourceId());
        }
    }

    @Override
    public String getSourceId() {
        return "novelaenespanol";
    }

    @Override
    public String getDisplayName() {
        return "Novela en Español";
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
        return "https://novelaenespanol.com/";
    }

    @Override
    public int getRateLimitRpm() {
        return rateLimiter.getRateLimitRpm();
    }

    @Override
    public boolean requiresLogin() {
        return false;
    }

    @Override
    public List<NovelSearchResult> search(String query) {
        // Handle null or empty query - return empty results
        if (query == null || query.isBlank()) {
            return List.of();
        }
        
        log.debug("Searching NovelaEnEspanol for: {}", query);
        
        try {
            String searchUrl = UriComponentsBuilder.fromPath(API_ROOT + "/categories")
                    .queryParam("search", query)
                    .queryParam("per_page", 20)
                    .queryParam("_fields", "id,name,slug,count,parent,link")
                    .build()
                    .toUriString();
            
            String json = fetchWithCloudflareBypass(searchUrl);
            
            JsonNode results = objectMapper.readTree(json);
            List<NovelSearchResult> searchResults = new ArrayList<>();
            
            for (JsonNode row : results) {
                // Skip parent categories and empty ones
                if (row.hasNonNull("parent") && row.get("parent").asInt() != 0) continue;
                if (!row.hasNonNull("count") || row.get("count").asInt() == 0) continue;
                
                String title = row.hasNonNull("name") ? row.get("name").asText() : "";
                String url = row.hasNonNull("link") ? row.get("link").asText() : "";
                int count = row.hasNonNull("count") ? row.get("count").asInt() : 0;
                
                if (!title.isEmpty() && !url.isEmpty()) {
                    searchResults.add(new NovelSearchResult(
                            title.strip(),
                            url,
                            count + " chapters",
                            "",
                            ""
                    ));
                }
            }
            
            log.debug("Found {} results for query '{}'", searchResults.size(), query);
            return searchResults;
            
        } catch (Exception e) {
            log.error("Search failed for query '{}': {}", query, e.getMessage());
            throw new CrawlerException(getSourceId(), "search", "Search failed: " + e.getMessage(), e);
        }
    }

    @Override
    public NovelDetail getNovelDetails(String novelUrl) {
        log.debug("Getting novel details from NovelaEnEspanol: {}", novelUrl);
        
        try {
            String slug = extractSlug(novelUrl);
            if (slug == null) {
                throw new CrawlerException(getSourceId(), "getNovelDetails", "Invalid novel URL: " + novelUrl);
            }
            
            // Get category info via API
            String categoryUrl = UriComponentsBuilder.fromPath(API_ROOT + "/categories")
                    .queryParam("slug", slug)
                    .queryParam("_fields", "id,name,slug,count,description")
                    .build()
                    .toUriString();
            
            String categoryJson = fetchWithCloudflareBypass(categoryUrl);
            
            JsonNode categories = objectMapper.readTree(categoryJson);
            if (categories.isEmpty()) {
                throw new CrawlerException(getSourceId(), "getNovelDetails", "Novel not found: " + novelUrl);
            }
            
            JsonNode category = categories.get(0);
            int categoryId = category.hasNonNull("id") ? category.get("id").asInt() : 0;
            
            // Also get the HTML page for cover and additional tags
            String html = fetchWithCloudflareBypass(novelUrl);
            
            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html);
            
            // Title from category API
            String title = category.hasNonNull("name") ? category.get("name").asText() : "";
            
            // Cover from HTML page
            String coverUrl = "";
            org.jsoup.nodes.Element coverEl = doc.selectFirst(".arh-thumb img[src], article img[src], .entry-content img[src]");
            if (coverEl != null && coverEl.hasAttr("src")) {
                coverEl.attr("src");
                coverUrl = toAbsoluteUrl(coverEl.attr("src"));
            }
            
            // Synopsis from category description
            String synopsis = "";
            if (category.hasNonNull("description")) {
                String desc = category.get("description").asText();
                if (!desc.isEmpty()) {
                    org.jsoup.nodes.Document descDoc = org.jsoup.Jsoup.parse(desc);
                    synopsis = descDoc.text();
                }
            }
            
            // Tags from HTML - genre links
            List<String> tags = new ArrayList<>();
            org.jsoup.select.Elements tagLinks = doc.select("a[href*='/novelas-ligeras/']");
            for (org.jsoup.nodes.Element anchor : tagLinks) {
                String href = anchor.attr("href");
                String text = anchor.text().strip();
                if (!text.isEmpty() && !href.endsWith("/completed") && !href.endsWith("/ongoing")) {
                    tags.add(text);
                }
            }
            
            // Fetch chapters via posts API
            List<ChapterInfo> chapters = fetchChapters(categoryId);
            List<VolumeInfo> volumes = groupChaptersIntoVolumes(chapters);
            
            return new NovelDetail(
                    title,
                    "", // author not easily available
                    coverUrl,
                    synopsis,
                    tags,
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
            String slug = extractSlug(novelUrl);
            if (slug == null) {
                throw new CrawlerException(getSourceId(), "getChapters", "Invalid novel URL: " + novelUrl);
            }
            
            // Get category ID first
            String categoryUrl = UriComponentsBuilder.fromPath(API_ROOT + "/categories")
                    .queryParam("slug", slug)
                    .queryParam("_fields", "id")
                    .build()
                    .toUriString();
            
            String categoryJson = fetchWithCloudflareBypass(categoryUrl);
            
            JsonNode categories = objectMapper.readTree(categoryJson);
            if (categories.isEmpty()) {
                throw new CrawlerException(getSourceId(), "getChapters", "Novel not found: " + novelUrl);
            }
            
            int categoryId = categories.get(0).hasNonNull("id") ? categories.get(0).get("id").asInt() : 0;
            return fetchChapters(categoryId);
            
        } catch (Exception e) {
            log.error("Get chapters failed for {}: {}", novelUrl, e.getMessage());
            throw new CrawlerException(getSourceId(), "getChapters", "Failed to get chapters: " + e.getMessage(), e);
        }
    }

    @Override
    public ChapterContent getChapterContent(String chapterUrl) {
        log.debug("Getting chapter content: {}", chapterUrl);
        
        try {
            String html = fetchWithCloudflareBypass(chapterUrl);
            
            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html);
            
            // Chapter body selector from Python: .entry-content
            org.jsoup.nodes.Element contentEl = doc.selectFirst(".entry-content");
            if (contentEl == null) {
                return ChapterContent.failure("Chapter content element not found");
            }
            
            // Clean content
            contentEl.select("script, style, noscript, iframe, .sharedaddy, .jp-relatedposts, .yarpp-related").remove();
            
            // Extract images
            List<String> images = new ArrayList<>();
            org.jsoup.select.Elements imgElements = contentEl.select("img[src]");
            for (org.jsoup.nodes.Element img : imgElements) {
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
        throw new UnsupportedOperationException("Login not required for catalog access");
    }

    @Override
    public boolean isHealthy() {
        try {
            String html = fetchWithCloudflareBypass("/");
            return html != null && html.contains("novelaenespanol");
        } catch (Exception e) {
            log.warn("NovelaEnEspanol health check failed: {}", e.getMessage());
            return false;
        }
    }

    // ==================== Private Helper Methods ====================

    private String extractSlug(String novelUrl) {
        Matcher matcher = NOVEL_URL_PATTERN.matcher(novelUrl);
        if (matcher.find()) {
            return matcher.group(1);
        }
        // Fallback: try category pattern from WpCategoryTemplate
        Pattern categoryPattern = Pattern.compile("/category/([^/?#]+)");
        matcher = categoryPattern.matcher(novelUrl);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private List<ChapterInfo> fetchChapters(int categoryId) {
        List<ChapterInfo> chapters = new ArrayList<>();
        int page = 1;
        int chapterNumber = 0;
        
        try {
            while (true) {
                acquirePermit();
                String postsUrl = UriComponentsBuilder.fromPath(API_ROOT + "/posts")
                        .queryParam("categories", categoryId)
                        .queryParam("per_page", POSTS_PER_PAGE)
                        .queryParam("page", page)
                        .queryParam("orderby", "date")
                        .queryParam("order", "asc")
                        .queryParam("_fields", "link,title")
                        .build()
                        .toUriString();
                
                org.springframework.http.ResponseEntity<String> response = webClient.get()
                        .uri(postsUrl)
                        .retrieve()
                        .toEntity(String.class)
                        .block();
                
                if (response == null || response.getBody() == null) break;
                
                JsonNode posts = objectMapper.readTree(response.getBody());
                if (posts.isEmpty()) break;
                
                // Get total pages from header
                String totalPagesHeader = response.getHeaders().getFirst("X-WP-TotalPages");
                int totalPages = totalPagesHeader != null ? Integer.parseInt(totalPagesHeader) : 1;
                
                for (JsonNode post : posts) {
                    String link = post.hasNonNull("link") ? post.get("link").asText() : "";
                    String title = "";
                    if (post.hasNonNull("title") && post.get("title").hasNonNull("rendered")) {
                        title = post.get("title").get("rendered").asText();
                    }
                    
                    if (link.isEmpty() || title.isEmpty()) continue;
                    
                    // Skip landing page (the "read" button)
                    // Landing URL would be the novel page itself
                    if (link.contains("/novela-ligera/") && link.endsWith(slugFromLink(link))) {
                        // This is likely the landing page, skip it
                        continue;
                    }
                    
                    chapterNumber++;
                    chapters.add(new ChapterInfo(
                            chapterNumber,
                            cleanChapterTitle(title),
                            toAbsoluteUrl(link),
                            1 // All chapters in volume 1 for this template
                    ));
                }
                
                if (page >= totalPages) break;
                page++;
            }
        } catch (Exception e) {
            log.error("Failed to fetch chapters for category {}: {}", categoryId, e.getMessage());
        }
        
        return chapters;
    }

    private String slugFromLink(String link) {
        try {
            URI uri = new URI(link);
            String path = uri.getPath();
            String[] parts = path.split("/");
            return parts.length > 0 ? parts[parts.length - 1] : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String cleanChapterTitle(String title) {
        // Decode HTML entities
        title = org.apache.commons.text.StringEscapeUtils.unescapeHtml4(title).strip();
        
        // The WpCategoryTemplate.chapter_title logic: drop the novel's name prefix
        // Since we don't have the novel title here, we'll do a basic cleanup
        // In a full implementation, we'd pass the novel title to clean the chapter titles
        
        return title;
    }

    private List<VolumeInfo> groupChaptersIntoVolumes(List<ChapterInfo> chapters) {
        // For WpCategoryTemplate, chapters are typically not organized into volumes
        // All chapters go into a single volume
        if (chapters.isEmpty()) {
            return List.of();
        }
        
        // Update volume ID for all chapters
        for (ChapterInfo chapter : chapters) {
            // ChapterInfo is immutable, would need to recreate
        }
        
        return List.of(new VolumeInfo(1, "Volume 1", chapters));
    }

    private String toAbsoluteUrl(String url) {
        if (url == null || url.isEmpty()) return "";
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        if (url.startsWith("//")) return "https:" + url;
        if (url.startsWith("/")) return "https://novelaenespanol.com" + url;
        return "https://novelaenespanol.com/" + url;
    }
}