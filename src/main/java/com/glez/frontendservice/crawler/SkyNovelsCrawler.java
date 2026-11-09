package com.glez.frontendservice.crawler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glez.frontendservice.config.CrawlerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.text.Normalizer;

/**
 * SkyNovels.net crawler implementation.
 * Port of Python lncrawl SkyNovelsCrawler (SoupTemplate with API).
 * 
 * Source: https://www.skynovels.net/
 * Pattern: API-based (Angular frontend with REST backend).
 * Uses WebClient for HTTP, Jackson for JSON parsing.
 */
@Slf4j
@Component
@Qualifier("skynovels")
public class SkyNovelsCrawler implements SourceCrawler {

    private static final String BASE_URL = "https://www.skynovels.net/";
    private static final String API_URL = "https://api.skynovels.net/api";
    private static final String IMAGE_URL = "https://api.skynovels.net/api/get-image";
    private static final Pattern NOVEL_ID_PATTERN = Pattern.compile("/(\\d+)(?:/|$)");
    private static final int CATALOGUE_SIZE = 500;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final CrawlerProperties.SourceConfig config;
    private final int rateLimitRpm;
    
    // Cached catalogue
    private volatile List<JsonNode> catalogueCache;

    public SkyNovelsCrawler(WebClient.Builder webClientBuilder, ObjectMapper objectMapper, CrawlerProperties properties) {
        this.objectMapper = objectMapper;
        this.config = properties.getSources().get("skynovels");
        this.rateLimitRpm = config != null ? config.getRateLimitRpm() : 30;
        
        this.webClient = webClientBuilder
                .baseUrl(API_URL)
                .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build();
    }

    @Override
    public String getSourceId() {
        return "skynovels";
    }

    @Override
    public String getDisplayName() {
        return "SkyNovels";
    }

    @Override
    public String getIcon() {
        return "pi pi-cloud";
    }

    @Override
    public String getLanguage() {
        return "es";
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
        return false;
    }

    @Override
    public List<NovelSearchResult> search(String query) {
        // Handle null or empty query - return empty results
        if (query == null || query.isBlank()) {
            return List.of();
        }
        
        log.debug("Searching SkyNovels for: {}", query);
        
        try {
            ensureCatalogueLoaded();
            
            String needle = fold(query.strip());
            List<NovelSearchResult> results = new ArrayList<>();
            
            for (JsonNode item : catalogueCache) {
                String title = item.hasNonNull("nvl_title") ? item.get("nvl_title").asText() : "";
                if (title.isEmpty()) continue;
                
                if (fold(title).contains(needle)) {
                    int id = item.hasNonNull("id") ? item.get("id").asInt() : 0;
                    String writer = item.hasNonNull("nvl_writer") ? item.get("nvl_writer").asText() : "";
                    String url = BASE_URL + "novelas/" + id + "/" + 
                        (item.hasNonNull("nvl_name") ? item.get("nvl_name").asText() : "");
                    
                    results.add(new NovelSearchResult(
                            title,
                            url,
                            writer,
                            "", // cover URL
                            String.valueOf(id)
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
        log.debug("Getting novel details from SkyNovels: {}", novelUrl);
        
        try {
            String novelId = extractNovelId(novelUrl);
            if (novelId == null) {
                throw new CrawlerException(getSourceId(), "getNovelDetails", "Invalid novel URL: " + novelUrl);
            }
            
            // Get novel chapters payload
            String chaptersUrl = API_URL + "/novel-chapters/" + novelId;
            String chaptersJson = webClient.get()
                    .uri(chaptersUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            
            JsonNode chaptersResponse = objectMapper.readTree(chaptersJson);
            JsonNode payload = chaptersResponse.hasNonNull("novel") && chaptersResponse.get("novel").isArray() && chaptersResponse.get("novel").size() > 0
                    ? chaptersResponse.get("novel").get(0)
                    : chaptersResponse;
            
            // Get synopsis from catalogue
            ensureCatalogueLoaded();
            JsonNode catalogueEntry = catalogueCache.stream()
                    .filter(n -> String.valueOf(n.hasNonNull("id") ? n.get("id").asInt() : 0).equals(novelId))
                    .findFirst()
                    .orElse(null);
            
            String title = payload.hasNonNull("nvl_title") ? payload.get("nvl_title").asText() : "";
            String author = "";
            if (payload.hasNonNull("nvl_writer")) {
                author = payload.get("nvl_writer").asText();
            } else if (payload.hasNonNull("nvl_translator")) {
                author = payload.get("nvl_translator").asText();
            }
            
            String synopsis = "";
            if (catalogueEntry != null && catalogueEntry.hasNonNull("nvl_content")) {
                String content = catalogueEntry.get("nvl_content").asText();
                // Clean HTML content
                org.jsoup.nodes.Document contentDoc = org.jsoup.Jsoup.parse(content);
                synopsis = contentDoc.text();
            }
            
            String coverUrl = "";
            if (payload.hasNonNull("image") && !payload.get("image").isNull()) {
                String image = payload.get("image").asText();
                coverUrl = IMAGE_URL + "/" + image + "/novels/false";
            }
            
            // Fetch chapters
            List<ChapterInfo> chapters = new ArrayList<>();
            if (payload.hasNonNull("chapters") && payload.get("chapters").isArray()) {
                List<JsonNode> chapterList = new ArrayList<>();
                payload.get("chapters").forEach(chapterList::add);
                
                chapterList.sort((a, b) -> {
                    int numA = a.hasNonNull("chp_number") ? a.get("chp_number").asInt() : 0;
                    int numB = b.hasNonNull("chp_number") ? b.get("chp_number").asInt() : 0;
                    return Integer.compare(numA, numB);
                });
                
                for (JsonNode chapter : chapterList) {
                    String status = chapter.hasNonNull("chp_status") ? chapter.get("chp_status").asText() : "";
                    if (!"Active".equals(status)) continue;
                    
                    int chpNumber = chapter.hasNonNull("chp_number") ? chapter.get("chp_number").asInt() : 0;
                    String chapterId = chapter.hasNonNull("id") ? chapter.get("id").asText() : "";
                    
                    if (!chapterId.isEmpty()) {
                        chapters.add(new ChapterInfo(
                                chpNumber,
                                "Capítulo " + chpNumber,
                                API_URL + "/chapters/" + chapterId,
                                1
                        ));
                    }
                }
            }
            
            List<VolumeInfo> volumes = List.of(new VolumeInfo(1, "Volume 1", chapters));
            
            return new NovelDetail(
                    title,
                    author,
                    coverUrl,
                    synopsis,
                    List.of(), // tags not available
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
            String novelId = extractNovelId(novelUrl);
            if (novelId == null) {
                throw new CrawlerException(getSourceId(), "getChapters", "Invalid novel URL: " + novelUrl);
            }
            
            // Reuse getNovelDetails logic for chapters
            NovelDetail detail = getNovelDetails(novelUrl);
            List<ChapterInfo> allChapters = new ArrayList<>();
            for (VolumeInfo vol : detail.volumes()) {
                allChapters.addAll(vol.chapters());
            }
            return allChapters;
            
        } catch (Exception e) {
            log.error("Get chapters failed for {}: {}", novelUrl, e.getMessage());
            throw new CrawlerException(getSourceId(), "getChapters", "Failed to get chapters: " + e.getMessage(), e);
        }
    }

    @Override
    public ChapterContent getChapterContent(String chapterUrl) {
        log.debug("Getting chapter content: {}", chapterUrl);
        
        try {
            String json = webClient.get()
                    .uri(chapterUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            
            JsonNode response = objectMapper.readTree(json);
            JsonNode chapter = response.hasNonNull("chapter") ? response.get("chapter") : response;
            
            // Handle both array and object
            if (chapter.isArray() && chapter.size() > 0) {
                chapter = chapter.get(0);
            }
            
            String content = chapter.hasNonNull("chp_content") ? chapter.get("chp_content").asText() : "";
            
            if (content.isEmpty()) {
                return ChapterContent.failure("Chapter content is empty");
            }
            
            // Clean HTML content
            org.jsoup.nodes.Document contentDoc = org.jsoup.Jsoup.parse(content);
            contentDoc.select("script, style, noscript, iframe").remove();
            
            // Extract images
            List<String> images = new ArrayList<>();
            org.jsoup.select.Elements imgElements = contentDoc.select("img[src]");
            for (org.jsoup.nodes.Element img : imgElements) {
                String src = img.absUrl("src");
                if (!src.isEmpty()) {
                    images.add(src);
                }
            }
            
            String cleanHtml = contentDoc.html();
            
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
            String json = webClient.get()
                    .uri(API_URL + "/novels?limit=1")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return json != null && json.contains("novels");
        } catch (Exception e) {
            log.warn("SkyNovels health check failed: {}", e.getMessage());
            return false;
        }
    }

    // ==================== Private Helper Methods ====================

    private synchronized void ensureCatalogueLoaded() {
        if (catalogueCache == null) {
            try {
                String json = webClient.get()
                        .uri(UriComponentsBuilder.fromUriString(API_URL + "/novels")
                                .queryParam("limit", CATALOGUE_SIZE)
                                .build()
                                .toUriString())
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();
                
                JsonNode response = objectMapper.readTree(json);
                if (response.hasNonNull("novels") && response.get("novels").isArray()) {
                    List<JsonNode> novels = new ArrayList<>();
                    response.get("novels").forEach(novels::add);
                    catalogueCache = novels;
                } else {
                    catalogueCache = List.of();
                }
                log.debug("Loaded catalogue with {} novels", catalogueCache.size());
            } catch (Exception e) {
                log.error("Failed to load catalogue: {}", e.getMessage());
                catalogueCache = List.of();
            }
        }
    }

    private String extractNovelId(String novelUrl) {
        Matcher matcher = NOVEL_ID_PATTERN.matcher(novelUrl);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * Fold text for search: lowercase and strip accents.
     * Equivalent to Python's fold() function.
     */
    private String fold(String text) {
        if (text == null) return "";
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder();
        for (char c : normalized.toCharArray()) {
            if (Character.getType(c) != Character.NON_SPACING_MARK) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }
}