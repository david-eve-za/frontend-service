package com.glez.frontendservice.crawler;

import java.util.List;

/**
 * Full novel metadata from a source.
 * Contains all details needed for the novel catalog and chapter listing.
 */
public record NovelDetail(
        /** Title of the novel */
        String title,

        /** Author(s) of the novel */
        String author,

        /** Cover image URL */
        String coverUrl,

        /** Synopsis/description of the novel */
        String synopsis,

        /** Tags/genres associated with the novel */
        List<String> tags,

        /** Volumes with their chapters */
        List<VolumeInfo> volumes,

        /** Language code (ISO 639-1) */
        String language,

        /** Whether this is a machine translation (MTL) */
        boolean isMtl,

        /** Whether this is a manga/manhwa/manhua */
        boolean isManga,

        /** Source-specific novel identifier (for internal mapping) */
        String sourceNovelId,

        /** Novel status (Ongoing, Completed, Hiatus, etc.) */
        String status,

        /** Last update timestamp from source */
        String lastUpdate
) {
    public NovelDetail {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Title cannot be null or blank");
        }
        if (author == null) {
            author = "";
        }
        if (coverUrl == null) {
            coverUrl = "";
        }
        if (synopsis == null) {
            synopsis = "";
        }
        if (tags == null) {
            tags = List.of();
        }
        if (volumes == null) {
            volumes = List.of();
        }
        if (language == null || language.isBlank()) {
            language = "en";
        }
        if (sourceNovelId == null) {
            sourceNovelId = "";
        }
        if (status == null) {
            status = "Unknown";
        }
        if (lastUpdate == null) {
            lastUpdate = "";
        }
    }

    /**
     * Total number of chapters across all volumes.
     */
    public int totalChapters() {
        return volumes.stream().mapToInt(VolumeInfo::chaptersCount).sum();
    }

    /**
     * Total number of volumes.
     */
    public int totalVolumes() {
        return volumes.size();
    }
}