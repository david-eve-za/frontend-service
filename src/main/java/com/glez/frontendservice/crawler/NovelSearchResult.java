package com.glez.frontendservice.crawler;

/**
 * Search result for a novel from a source.
 * Lightweight representation for search listings.
 */
public record NovelSearchResult(
        /** Title of the novel */
        String title,

        /** URL to the novel detail page on the source */
        String url,

        /** Additional info (e.g. "50 chapters", "Ongoing", rating, last update) */
        String info,

        /** Cover image URL (optional) */
        String coverUrl,

        /** Source-specific novel identifier (optional, for internal mapping) */
        String sourceNovelId
) {
    public NovelSearchResult {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Title cannot be null or blank");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL cannot be null or blank");
        }
        if (info == null) {
            info = "";
        }
        if (coverUrl == null) {
            coverUrl = "";
        }
        if (sourceNovelId == null) {
            sourceNovelId = "";
        }
    }

    public static NovelSearchResult of(String title, String url) {
        return new NovelSearchResult(title, url, "", "", "");
    }

    public static NovelSearchResult of(String title, String url, String info) {
        return new NovelSearchResult(title, url, info, "", "");
    }

    public static NovelSearchResult of(String title, String url, String info, String coverUrl) {
        return new NovelSearchResult(title, url, info, coverUrl, "");
    }
}