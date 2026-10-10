package com.glez.frontendservice.dtos;

import java.time.Instant;

/**
 * Snapshot inmutable del estado de una sincronización de metadatos del
 * catálogo de novelas (patrón de NovelsCrawlStatus).
 *
 * <p>Progreso del ciclo de carpetas: {@code foldersComplete}/{@code foldersPending}
 * reflejan cuántas carpetas de obra están marcadas como listadas por completo
 * en la pasada actual (las corridas incrementales las saltan) y cuántas faltan
 * para considerar el catálogo 100% sincronizado — condición que dispara el
 * reinicio de todos los flags y una pasada completa nueva ({@code fullCycle}
 * indica si la corrida en curso es una de esas pasadas de ciclo).</p>
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
        String lastError,
        Instant pauseUntil,
        boolean fullCycle,
        long foldersComplete,
        long foldersPending,
        int skippedFolders) {

    public enum State {
        IDLE,
        RUNNING,
        COMPLETED,
        CANCELLED,
        FAILED
    }

    public static NovelsCatalogSyncStatus idle() {
        return new NovelsCatalogSyncStatus(State.IDLE, null, 0, 0, 0, 0, 0, 0,
                null, null, null, null, false, 0, 0, 0);
    }
}
