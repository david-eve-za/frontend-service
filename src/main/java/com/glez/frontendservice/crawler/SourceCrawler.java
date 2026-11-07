package com.glez.frontendservice.crawler;

import java.util.List;

/**
 * Interface that all novel source crawlers must implement.
 * 
 * Each crawler represents a single source (e.g., "novelasligeras", "elscione", "novelaenespanol")
 * and provides methods to search, retrieve novel details, list chapters, and fetch chapter content.
 * 
 * Crawlers are automatically registered via Spring's {@code @Component} mechanism.
 * The {@link SourceRegistry} collects all implementations and exposes them via REST API.
 * 
 * <p><b>Implementation Notes:</b></p>
 * <ul>
 *   <li>All methods should be thread-safe (stateless or properly synchronized)</li>
 *   <li>HTTP requests should respect {@link #getRateLimitRpm()}</li>
 *   <li>Use {@link CloudflareBypassService} for Cloudflare-protected sources</li>
 *   <li>Cache novel details and chapter lists where appropriate</li>
 *   <li>Handle rate limiting with exponential backoff</li>
 * </ul>
 * 
 * @see SourceRegistry
 * @see SourceMetadata
 */
public interface SourceCrawler {

    /**
     * Unique identifier for this source (kebab-case).
     * Used in URLs and for registry lookup.
     * Examples: "elscione", "novelasligeras", "novelaenespanol"
     * 
     * @return source ID, never null or blank
     */
    String getSourceId();

    /**
     * Human-readable display name for the frontend menu.
     * Examples: "Elscione", "Novelas Ligeras", "Novela en Español"
     * 
     * @return display name, never null or blank
     */
    String getDisplayName();

    /**
     * Icon for the frontend menu.
     * Should be a PrimeNG icon class (e.g., "pi pi-book", "pi pi-globe")
     * or an emoji.
     * 
     * @return icon class, never null
     */
    String getIcon();

    /**
     * Primary language of this source (ISO 639-1 code).
     * Examples: "es", "en", "ja", "zh", "pt", "ru"
     * 
     * @return language code, never null or blank
     */
    String getLanguage();

    /**
     * Base URL of the source website.
     * Used for health checks and display.
     * 
     * @return base URL, never null or blank
     */
    String getBaseUrl();

    /**
     * Maximum requests per minute this source allows.
     * Return 0 for no limit (will use global default).
     * 
     * @return rate limit in RPM, >= 0
     */
    int getRateLimitRpm();

    /**
     * Whether this source requires login for full access.
     * If true, the frontend will show login option and call {@link #login(String, String)}.
     * 
     * @return true if login required
     */
    boolean requiresLogin();

    /**
     * Search for novels by query string.
     * 
     * @param query search query (title, author, keywords)
     * @return list of search results, never null (empty list if no results)
     * @throws CrawlerException if search fails
     */
    List<NovelSearchResult> search(String query);

    /**
     * Get full novel details including volumes and chapters.
     * 
     * @param novelUrl URL of the novel on the source (from search result)
     * @return novel detail with volumes and chapters, never null
     * @throws CrawlerException if retrieval fails or novel not found
     */
    NovelDetail getNovelDetails(String novelUrl);

    /**
     * Get chapter list for a novel (lightweight, without content).
     * 
     * @param novelUrl URL of the novel on the source
     * @return list of chapter info, never null
     * @throws CrawlerException if retrieval fails
     */
    List<ChapterInfo> getChapters(String novelUrl);

    /**
     * Get full chapter content (HTML body).
     * 
     * @param chapterUrl URL of the chapter on the source
     * @return chapter content with HTML and images
     * @throws CrawlerException if retrieval fails
     */
    ChapterContent getChapterContent(String chapterUrl);

    /**
     * Login to the source with credentials.
     * Only called if {@link #requiresLogin()} returns true.
     * 
     * @param username username or email
     * @param password password or token
     * @throws CrawlerException if login fails
     */
    void login(String username, String password);

    /**
     * Health check - can we reach the source and perform basic operations?
     * Used for monitoring and frontend status indicators.
     * 
     * @return true if source is reachable and functional
     */
    boolean isHealthy();

    /**
     * Get all novel IDs from this source (for batch operations).
     * 
     * @return list of novel IDs/URLs, never null
     */
    default List<String> getAllNovelIds() {
        return List.of();
    }

    /**
     * Get metadata for this source (convenience method).
     * Default implementation builds from getters.
     * 
     * @return source metadata
     */
    default SourceMetadata getMetadata() {
        return new SourceMetadata(
                getSourceId(),
                getDisplayName(),
                getIcon(),
                getLanguage(),
                getBaseUrl(),
                getRateLimitRpm(),
                requiresLogin(),
                true,
                List.of()
        );
    }
}