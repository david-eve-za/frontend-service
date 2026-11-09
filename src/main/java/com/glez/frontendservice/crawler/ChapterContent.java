package com.glez.frontendservice.crawler;

import java.util.List;

/**
 * Full chapter content from a source.
 * Contains the cleaned HTML body and associated images.
 */
public record ChapterContent(
        /** Clean HTML content of the chapter */
        String html,

        /** List of image URLs found in the chapter */
        List<String> images,

        /** Whether the content was successfully retrieved */
        boolean success,

        /** Error message if retrieval failed */
        String errorMessage
) {
    public ChapterContent {
        if (html == null) {
            html = "";
        }
        if (images == null) {
            images = List.of();
        }
        if (!success && (errorMessage == null || errorMessage.isBlank())) {
            errorMessage = "Unknown error retrieving chapter content";
        }
    }

    public static ChapterContent success(String html, List<String> images) {
        return new ChapterContent(html, images, true, null);
    }

    public static ChapterContent success(String html) {
        return new ChapterContent(html, List.of(), true, null);
    }

    public static ChapterContent failure(String errorMessage) {
        return new ChapterContent("", List.of(), false, errorMessage);
    }

    public boolean hasContent() {
        return success && (html != null && !html.isBlank());
    }
}