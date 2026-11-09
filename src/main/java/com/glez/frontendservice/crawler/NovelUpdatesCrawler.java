package com.glez.frontendservice.crawler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glez.frontendservice.config.CrawlerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * NovelUpdates.com crawler implementation.
 * Port of Python lncrawl NovelUpdatesCrawler (API-based).
 * 
 * Source: https://www.novelupdates.com/
 * Pattern: Directory/API site - provides novel metadata and links to translators.
 * Uses WebClient for HTTP, Jackson for JSON parsing.
 */
@Slf4j
@Component
@Qualifier("novelupdates")
public class NovelUpdatesCrawler implements SourceCrawler {

    private static final String BASE_URL = "https://www.novelupdates.com/";
    private static final String API_URL = "https://www.novelupdates.com/api/";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final CrawlerProperties.SourceConfig config;
    private final int rateLimitRpm;

    public NovelUpdatesCrawler(WebClient.Builder webClientBuilder, ObjectMapper objectMapper, CrawlerProperties properties) {
        this.objectMapper = objectMapper;
        this.config = properties.getSources().get("novelupdates");
        this.rateLimitRpm = config != null ? config.getRateLimitRpm() : 120;
        
        this.webClient = webClientBuilder
                .baseUrl(BASE_URL)
                .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build();
    }

    @Override
    public String getSourceId() {
        return "novelupdates";
    }

    @Override
    public String getDisplayName() {
        return "NovelUpdates";
    }

    @Override
    public String getIcon() {
        return "pi pi-globe";
    }

    @Override
    public String getLanguage() {
        return "en"; // Multi-language directory, default English
    }

    @Override
    public String getBaseUrl() {
        return BASE_URL;
    }

    @Override
    public int getRateLimitRpm() {
        return rateLimitRpm;
    }

    @Override
    public boolean requiresLogin() {
        return false; // Public directory
    }

    @Override
    public List<NovelSearchResult> search(String query) {
        // Handle null or empty query - return empty results
        if (query == null || query.isBlank()) {
            return List.of();
        }
        
        log.debug("Searching NovelUpdates for: {}", query);
        
        try {
            // NovelUpdates has a search API endpoint
            String searchUrl = UriComponentsBuilder.fromPath("api/novels/search")
                    .queryParam("q", query)
                    .queryParam("limit", 20)
                    .build()
                    .toUriString();
            
            String json = webClient.get()
                    .uri(searchUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            
            JsonNode response = objectMapper.readTree(json);
            List<NovelSearchResult> results = new ArrayList<>();
            
            if (response.hasNonNull("data") && response.get("data").isArray()) {
                for (JsonNode item : response.get("data")) {
                    String title = item.hasNonNull("title") ? item.get("title").asText() : "";
                    String url = item.hasNonNull("url") ? item.get("url").asText() : "";
                    String coverUrl = item.hasNonNull("image") ? item.get("image").asText() : "";
                    
                    // Build info string with author, genre, status
                    StringBuilder info = new StringBuilder();
                    if (item.hasNonNull("author")) info.append(item.get("author").asText());
                    if (item.hasNonNull("genre")) {
                        if (!info.isEmpty()) info.append(" | ");
                        info.append(item.get("genre").asText());
                    }
                    if (item.hasNonNull("status")) {
                        if (!info.isEmpty()) info.append(" | ");
                        info.append(item.get("status").asText());
                    }
                    
                    if (!title.isEmpty() && !url.isEmpty()) {
                        results.add(new NovelSearchResult(
                                title,
                                url,
                                info.toString(),
                                coverUrl,
                                item.hasNonNull("id") ? item.get("id").asText() : ""
                        ));
                    }
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
        log.debug("Getting novel details from NovelUpdates: {}", novelUrl);
        
        try {
            // NovelUpdates novel page has an API endpoint
            // Extract novel ID from URL
            String novelId = extractNovelId(novelUrl);
            if (novelId == null) {
                throw new CrawlerException(getSourceId(), "getNovelDetails", "Invalid novel URL: " + novelUrl);
            }
            
            String detailUrl = UriComponentsBuilder.fromPath("api/novels/" + novelId)
                    .build()
                    .toUriString();
            
            String json = webClient.get()
                    .uri(detailUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            
            JsonNode item = objectMapper.readTree(json);
            if (item.hasNonNull("data")) {
                item = item.get("data");
            }
            
            String title = item.hasNonNull("title") ? item.get("title").asText() : "";
            String author = item.hasNonNull("author") ? item.get("author").asText() : "";
            String coverUrl = item.hasNonNull("image") ? item.get("image").asText() : "";
            String synopsis = item.hasNonNull("description") ? item.get("description").asText() : "";
            
            // Tags/genres
            List<String> tags = new ArrayList<>();
            if (item.hasNonNull("genres") && item.get("genres").isArray()) {
                item.get("genres").forEach(g -> tags.add(g.asText()));
            }
            
            // Alternative names
            if (item.hasNonNull("alternative_names") && item.get("alternative_names").isArray()) {
                item.get("alternative_names").forEach(a -> tags.add(a.asText()));
            }
            
            // Status and type
            String status = item.hasNonNull("status") ? item.get("status").asText() : "Ongoing";
            String type = item.hasNonNull("type") ? item.get("type").asText() : "";
            if (!type.isEmpty()) tags.add(type);
            
            // NovelUpdates is a directory - it links to translators, doesn't host chapters directly
            // For this implementation, we'll return empty chapters
            List<VolumeInfo> volumes = List.of();
            
            return new NovelDetail(
                    title,
                    author,
                    coverUrl,
                    synopsis,
                    tags,
                    volumes,
                    "multi", // Multi-language
                    false,
                    false,
                    novelUrl,
                    status,
                    item.hasNonNull("last_updated") ? item.get("last_updated").asText() : ""
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
        
        // NovelUpdates doesn't host chapters directly - it links to translator sites
        // Return empty list; the frontend should show translator links instead
        return List.of();
    }

    @Override
    public ChapterContent getChapterContent(String chapterUrl) {
        return ChapterContent.failure("NovelUpdates is a directory - chapters are hosted on translator sites");
    }

    @Override
    public void login(String username, String password) {
        // NovelUpdates has optional login for tracking
        throw new UnsupportedOperationException("Login not required for basic directory access");
    }

    @Override
    public boolean isHealthy() {
        try {
            String html = webClient.get()
                    .uri("/")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return html != null && html.contains("NovelUpdates");
        } catch (Exception e) {
            log.warn("NovelUpdates health check failed: {}", e.getMessage());
            return false;
        }
    }

    private String extractNovelId(String novelUrl) {
        // URL pattern: https://www.novelupdates.com/series/novel-name/ or similar
        // The ID is typically in the URL path
        String[] parts = novelUrl.split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i];
            if (!part.isEmpty() && part.matches("\\d+")) {
                return part;
            }
        }
        // If no numeric ID, return the slug
        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i];
            if (!part.isEmpty() && !part.equals("series") && !part.equals("www.novelupdates.com")) {
                return part;
            }
        }
        return null;
    }
}