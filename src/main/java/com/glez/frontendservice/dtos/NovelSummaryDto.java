package com.glez.frontendservice.dtos;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Fila de nivel 1 (obra) del catálogo de novelas.
 */
public record NovelSummaryDto(
        UUID id,
        String title,
        String category,
        long volumeCount,
        Instant lastSyncedAt,
        // --- Campos de enriquecimiento AniList ---
        Integer anilistId,
        String anilistCoverUrl,
        String anilistDescription,
        List<String> anilistGenres,
        List<String> anilistTags,
        String anilistStatus,
        Integer anilistAverageScore,
        String anilistFormat,
        String anilistSource,
        String anilistCountryOfOrigin,
        Integer anilistVolumes,
        Integer anilistChapters) {
}
