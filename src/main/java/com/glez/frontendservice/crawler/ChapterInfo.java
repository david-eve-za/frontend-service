package com.glez.frontendservice.crawler;

/**
 * Chapter metadata (lightweight, without content).
 * Used for chapter listings in the TOC.
 */
public record ChapterInfo(
        /** Chapter number/identifier */
        int id,

        /** Chapter title */
        String title,

        /** URL to the chapter content on the source */
        String url,

        /** Volume this chapter belongs to */
        int volumeId
) {
    public ChapterInfo {
        if (title == null || title.isBlank()) {
            title = "Chapter " + id;
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Chapter URL cannot be null or blank");
        }
    }

    public static ChapterInfo of(int id, String title, String url) {
        return new ChapterInfo(id, title, url, 1);
    }

    public static ChapterInfo of(int id, String title, String url, int volumeId) {
        return new ChapterInfo(id, title, url, volumeId);
    }
}