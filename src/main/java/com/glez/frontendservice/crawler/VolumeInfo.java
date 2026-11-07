package com.glez.frontendservice.crawler;

import java.util.List;

/**
 * Volume information containing its chapters.
 */
public record VolumeInfo(
        /** Volume number/identifier */
        int id,

        /** Volume title (e.g. "Volume 1", "Arc 1") */
        String title,

        /** Chapters belonging to this volume */
        List<ChapterInfo> chapters
) {
    public VolumeInfo {
        if (title == null || title.isBlank()) {
            title = "Volume " + id;
        }
        if (chapters == null) {
            chapters = List.of();
        }
    }

    /**
     * Number of chapters in this volume.
     */
    public int chaptersCount() {
        return chapters.size();
    }

    public static VolumeInfo of(int id, String title, List<ChapterInfo> chapters) {
        return new VolumeInfo(id, title, chapters);
    }

    public static VolumeInfo of(int id, List<ChapterInfo> chapters) {
        return new VolumeInfo(id, "Volume " + id, chapters);
    }
}