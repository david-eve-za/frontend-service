package com.glez.frontendservice.crawler;

import java.util.List;

/**
 * Metadata for a novel source/crawler, used for frontend menu registration
 * and source discovery via REST API.
 */
public record SourceMetadata(
        /** Unique identifier (kebab-case, e.g. "novelasligeras", "elscione") */
        String id,

        /** Display name for UI (e.g. "Novelas Ligeras", "Elscione") */
        String displayName,

        /** Icon (PrimeNG icon class or emoji) */
        String icon,

        /** Language code (ISO 639-1: es, en, ja, zh, pt, etc.) */
        String language,

        /** Base URL of the source */
        String baseUrl,

        /** Requests per minute limit (0 = no limit) */
        int rateLimitRpm,

        /** Whether source requires login for full access */
        boolean requiresLogin,

        /** Whether this source is currently enabled */
        boolean enabled,

        /** Optional: source-specific configuration hints */
        List<String> tags
) {
    public SourceMetadata {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Source id cannot be null or blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Display name cannot be null or blank");
        }
        if (language == null || language.isBlank()) {
            throw new IllegalArgumentException("Language cannot be null or blank");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Base URL cannot be null or blank");
        }
        if (rateLimitRpm < 0) {
            throw new IllegalArgumentException("Rate limit RPM cannot be negative");
        }
        if (tags == null) {
            tags = List.of();
        }
    }

    /**
     * Create a minimal metadata record.
     */
    public static SourceMetadata minimal(String id, String displayName, String language, String baseUrl) {
        return new SourceMetadata(id, displayName, "pi pi-book", language, baseUrl, 30, false, true, List.of());
    }
}