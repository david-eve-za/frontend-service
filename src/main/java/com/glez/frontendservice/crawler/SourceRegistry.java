package com.glez.frontendservice.crawler;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Central registry for all {@link SourceCrawler} implementations.
 * 
 * Automatically discovers and registers all Spring beans implementing SourceCrawler
 * at startup. Provides lookup by source ID and exposes metadata for the frontend.
 * 
 * <p><b>Registration:</b></p>
 * <ul>
 *   <li>Each crawler is a {@code @Component} with {@code @Qualifier(sourceId)}</li>
 *   <li>Registry autowires {@code List<SourceCrawler>} for automatic discovery</li>
 *   <li>Metadata cached for fast {@code GET /api/sources} responses</li>
 * </ul>
 * 
 * @see SourceCrawler
 * @see SourceMetadata
 */
@Slf4j
@Component
public class SourceRegistry {

    private final Map<String, SourceCrawler> crawlers = new ConcurrentHashMap<>();
    private volatile List<SourceMetadata> metadataCache = Collections.emptyList();

    @Autowired(required = false)
    private List<SourceCrawler> crawlerBeans;

    private final ApplicationContext applicationContext;

    public SourceRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * Register all discovered crawlers.
     * Called automatically by Spring after bean initialization.
     */
    @PostConstruct
    public void registerAll() {
        if (crawlerBeans == null || crawlerBeans.isEmpty()) {
            log.warn("No SourceCrawler beans found - registry will be empty");
            return;
        }

        log.info("Registering {} source crawlers...", crawlerBeans.size());
        
        for (SourceCrawler crawler : crawlerBeans) {
            register(crawler);
        }
        
        rebuildMetadataCache();
        log.info("Source registry initialized with {} crawlers: {}", 
                crawlers.size(), 
                crawlers.keySet().stream().sorted().collect(Collectors.joining(", ")));
    }

    /**
     * Register a single crawler.
     * 
     * @param crawler the crawler to register
     * @throws IllegalStateException if source ID already registered
     */
    public void register(SourceCrawler crawler) {
        String id = crawler.getSourceId();
        if (crawlers.containsKey(id)) {
            throw new IllegalStateException("Source crawler with id '" + id + "' already registered");
        }
        crawlers.put(id, crawler);
        rebuildMetadataCache();
        log.debug("Registered source crawler: {} ({})", crawler.getDisplayName(), id);
    }

    /**
     * Unregister a crawler (for testing or dynamic reload).
     * 
     * @param sourceId the source ID to unregister
     * @return true if was registered
     */
    public boolean unregister(String sourceId) {
        SourceCrawler removed = crawlers.remove(sourceId);
        if (removed != null) {
            rebuildMetadataCache();
            log.info("Unregistered source crawler: {}", sourceId);
            return true;
        }
        return false;
    }

    /**
     * Get a crawler by its source ID.
     * 
     * @param sourceId the source identifier
     * @return the crawler, or empty if not found
     */
    public java.util.Optional<SourceCrawler> getSource(String sourceId) {
        return java.util.Optional.ofNullable(crawlers.get(sourceId));
    }

    /**
     * Get a crawler by its source ID, throwing if not found.
     * 
     * @param sourceId the source identifier
     * @return the crawler
     * @throws IllegalArgumentException if source not found
     */
    public SourceCrawler requireSource(String sourceId) {
        return getSource(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown source: " + sourceId));
    }

    /**
     * Get all registered source metadata for frontend menu.
     * 
     * @return unmodifiable list of source metadata, sorted by display name
     */
    public List<SourceMetadata> getAllSources() {
        return metadataCache;
    }

    /**
     * Get all enabled source metadata.
     * 
     * @return filtered list of enabled sources
     */
    public List<SourceMetadata> getEnabledSources() {
        return metadataCache.stream()
                .filter(SourceMetadata::enabled)
                .toList();
    }

    /**
     * Get sources filtered by language.
     * 
     * @param language ISO 639-1 language code
     * @return filtered list
     */
    public List<SourceMetadata> getSourcesByLanguage(String language) {
        return metadataCache.stream()
                .filter(s -> language.equalsIgnoreCase(s.language()))
                .toList();
    }

    /**
     * Check if a source is registered and enabled.
     * 
     * @param sourceId the source identifier
     * @return true if registered and enabled
     */
    public boolean isSourceEnabled(String sourceId) {
        return getSource(sourceId)
                .map(SourceCrawler::getMetadata)
                .map(SourceMetadata::enabled)
                .orElse(false);
    }

    /**
     * Get all registered source IDs.
     * 
     * @return unmodifiable set of source IDs
     */
    public Collection<String> getAllSourceIds() {
        return Collections.unmodifiableSet(crawlers.keySet());
    }

    /**
     * Get the number of registered crawlers.
     * 
     * @return count
     */
    public int size() {
        return crawlers.size();
    }

    /**
     * Rebuild the metadata cache from registered crawlers.
     * Sorted by display name for consistent frontend ordering.
     */
    private void rebuildMetadataCache() {
        this.metadataCache = crawlers.values().stream()
                .map(SourceCrawler::getMetadata)
                .filter(SourceMetadata::enabled)
                .sorted((a, b) -> a.displayName().compareToIgnoreCase(b.displayName()))
                .toList();
    }

    /**
     * Health check all registered sources.
     * 
     * @return map of sourceId -> healthy status
     */
    public Map<String, Boolean> healthCheckAll() {
        return crawlers.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> {
                            try {
                                return e.getValue().isHealthy();
                            } catch (Exception ex) {
                                log.warn("Health check failed for {}: {}", e.getKey(), ex.getMessage());
                                return false;
                            }
                        }
                ));
    }

    /**
     * Refresh a specific source's metadata (after config change).
     * 
     * @param sourceId the source identifier
     */
    public void refreshSource(String sourceId) {
        getSource(sourceId).ifPresent(crawler -> {
            // Re-register to update metadata
            crawlers.put(sourceId, crawler);
            rebuildMetadataCache();
        });
    }
}