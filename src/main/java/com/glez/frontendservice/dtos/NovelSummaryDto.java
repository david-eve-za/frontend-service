package com.glez.frontendservice.dtos;

import java.time.Instant;
import java.util.UUID;

/**
 * Fila de nivel 1 (obra) del catálogo de novelas.
 */
public record NovelSummaryDto(
        UUID id,
        String title,
        String category,
        long volumeCount,
        Instant lastSyncedAt) {
}
