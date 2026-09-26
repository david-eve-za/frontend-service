package com.glez.frontendservice.dtos;

import java.time.Instant;

/**
 * Snapshot inmutable del estado de una sincronización de metadatos del
 * catálogo de novelas (patrón de NovelsCrawlStatus).
 */
public record NovelsCatalogSyncStatus(
        State state,
        String currentPath,
        int novelsSeen,
        int volumesSeen,
        int filesSeen,
        int newNovels,
        int newVolumes,
        int removedVolumes,
        Instant startedAt,
        Instant finishedAt,
        String lastError) {

    public enum State {
        IDLE,
        RUNNING,
        COMPLETED,
        CANCELLED,
        FAILED
    }

    public static NovelsCatalogSyncStatus idle() {
        return new NovelsCatalogSyncStatus(State.IDLE, null, 0, 0, 0, 0, 0, 0, null, null, null);
    }
}
