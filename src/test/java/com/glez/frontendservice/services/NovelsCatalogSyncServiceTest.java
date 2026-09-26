package com.glez.frontendservice.services;

import com.glez.frontendservice.components.NovelFileNameParser;
import com.glez.frontendservice.dtos.NovelsCatalogSyncStatus;
import com.glez.frontendservice.model.Novel;
import com.glez.frontendservice.model.NovelVolume;
import com.glez.frontendservice.model.NovelVolumeFile;
import com.glez.frontendservice.model.NovelVolumeFile;
import com.glez.frontendservice.repository.NovelRepository;
import com.glez.frontendservice.repository.NovelVolumeFileRepository;
import com.glez.frontendservice.repository.NovelVolumeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Prueba el upsert del catálogo contra JPA real (H2 embebido de @DataJpaTest):
 * creación de novelas/volúmenes/archivos, deduplicación entre ejecuciones y
 * marcado removedFromSource sin perder estado local.
 */
@DataJpaTest
@DisplayName("NovelsCatalogSyncService catalog upsert")
class NovelsCatalogSyncServiceTest {

    private static final String BASE = "/Officially%20Translated%20Light%20Novels/";
    private static final String DECODED_BASE = "/Officially Translated Light Novels/";

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private NovelRepository novelRepository;

    @Autowired
    private NovelVolumeRepository volumeRepository;

    @Autowired
    private NovelVolumeFileRepository fileRepository;

    @TempDir
    Path tempDir;

    private ElscioneApiClient apiClient;
    private NovelsCatalogSyncService service;

    @BeforeEach
    void setUp() {
        apiClient = mock(ElscioneApiClient.class);
        service = new NovelsCatalogSyncService(apiClient, novelRepository, volumeRepository,
                fileRepository, new NovelFileNameParser(),
                "https://server.elscione.com" + BASE, 3, true, false);
    }

    private static ElscioneApiClient.ApiItem dir(String href) {
        return new ElscioneApiClient.ApiItem(href, null);
    }

    private static ElscioneApiClient.ApiItem file(String href, long size) {
        return new ElscioneApiClient.ApiItem(href, size);
    }

    @Test
    @DisplayName("walks the remote tree: novel dirs, category subdirs and loose root files")
    void createsCatalogFromRemoteTree() {
        when(apiClient.listContents(BASE)).thenReturn(List.of(
                // Contexto de ancestros que debe ignorarse
                dir("/"), dir("/Books/"),
                // Carpeta de novela directa
                dir(BASE + "Isle%20of%20Paramounts/"),
                // Categoría con una novela dentro
                dir(BASE + "!Yaoi/"),
                // Archivo suelto en la raíz
                file(BASE + "Loose%20Novel%20-%20Volume%2001%20%5BX%5D.epub", 1024)));
        when(apiClient.listContents(BASE + "Isle%20of%20Paramounts/")).thenReturn(List.of(
                dir(BASE),
                file(BASE + "Isle%20of%20Paramounts/Isle%20of%20Paramounts%20-%20Volume%2004%20%5BJ-Novel%20Club%5D.epub", 100),
                file(BASE + "Isle%20of%20Paramounts/Isle%20of%20Paramounts%20-%20Volume%2004%20%5BJ-Novel%20Club%5D.pdf", 200),
                file(BASE + "Isle%20of%20Paramounts/Isle%20of%20Paramounts%20-%20Volume%2005%20%5BJ-Novel%20Club%5D.epub", 300)));
        when(apiClient.listContents(BASE + "!Yaoi/")).thenReturn(List.of(
                dir(BASE + "!Yaoi/Ai%20no%20Kusabi/"),
                file(BASE + "!Yaoi/GenerateCompletedLinksExecute.bat", 1)));
        when(apiClient.listContents(BASE + "!Yaoi/Ai%20no%20Kusabi/")).thenReturn(List.of(
                dir(BASE + "!Yaoi/"),
                file(BASE + "!Yaoi/Ai%20no%20Kusabi/Ai%20no%20Kusabi%20-%20Volume%2001%20%5BJune%5D%5BScans%5D.pdf", 400)));

        service.execute(new NovelsCatalogSyncService.SyncRun());

        assertEquals(3, novelRepository.count(), "Two dir novels + one virtual from the loose file");

        Novel isle = novelRepository.findByRemotePath(BASE + "Isle%20of%20Paramounts/").orElseThrow();
        assertEquals("Isle of Paramounts", isle.getTitle());
        assertNull(isle.getCategory());
        List<NovelVolume> isleVolumes = volumeRepository.findByNovel(isle);
        assertEquals(2, isleVolumes.size(), "Volume 04 (epub+pdf) and Volume 05");
        NovelVolume volume04 = isleVolumes.stream()
                .filter(v -> v.getVolumeNumber() == 4).findFirst().orElseThrow();
        assertEquals(2, fileRepository.findByVolumeId(volume04.getId()).size(),
                "epub and pdf variants of the same volume");
        assertEquals("J-Novel Club", volume04.getTranslatorGroup());
        assertFalse(volume04.isRemovedFromSource());

        Novel yaoiNovel = novelRepository.findByRemotePath(BASE + "!Yaoi/Ai%20no%20Kusabi/").orElseThrow();
        assertEquals("!Yaoi", yaoiNovel.getCategory(), "First-level folder becomes the category");

        Novel loose = novelRepository.findByTitleAndRemotePathIsNull("Loose Novel").orElseThrow();
        List<NovelVolume> looseVolumes = volumeRepository.findByNovel(loose);
        assertEquals(1, looseVolumes.size());
        assertEquals(1, looseVolumes.get(0).getVolumeNumber());
    }

    @Test
    @DisplayName("re-sync is idempotent and marks disappeared volumes without deleting them")
    void reSyncUpsertsAndMarksRemoved() {
        when(apiClient.listContents(BASE)).thenReturn(List.of(
                dir(BASE + "NovelA/")));
        when(apiClient.listContents(BASE + "NovelA/")).thenReturn(List.of(
                dir(BASE),
                file(BASE + "NovelA/NovelA%20-%20Volume%2001.epub", 10),
                file(BASE + "NovelA/NovelA%20-%20Volume%2002.epub", 20)));
        service.execute(new NovelsCatalogSyncService.SyncRun());
        entityManager.clear();

        assertEquals(1, novelRepository.count());
        assertEquals(2, volumeRepository.count());

        // Segunda ejecución: mismo árbol, el Volume 02 reporta un tamaño nuevo.
        when(apiClient.listContents(BASE + "NovelA/")).thenReturn(List.of(
                dir(BASE),
                file(BASE + "NovelA/NovelA%20-%20Volume%2001.epub", 10),
                file(BASE + "NovelA/NovelA%20-%20Volume%2002.epub", 21)));
        service.execute(new NovelsCatalogSyncService.SyncRun());
        entityManager.clear();

        assertEquals(1, novelRepository.count(), "Idempotent upsert: no duplicated novels");
        assertEquals(2, volumeRepository.count(), "Volumes are keyed by sourceKey, not recreated");

        Novel novelA = novelRepository.findByRemotePath(BASE + "NovelA/").orElseThrow();
        List<NovelVolume> volumes = volumeRepository.findByNovel(novelA);
        assertEquals(2, volumes.size());
        for (NovelVolume volume : volumes) {
            assertFalse(volume.isRemovedFromSource());
        }
        NovelVolumeFile updatedFile = fileRepository
                .findByRemoteHref(BASE + "NovelA/NovelA%20-%20Volume%2002.epub").orElseThrow();
        assertEquals(21L, updatedFile.getSizeBytes(), "Metadata (size) is refreshed on re-sync");

        // Tercera ejecución: el Volume 02 desaparece de la fuente.
        when(apiClient.listContents(BASE + "NovelA/")).thenReturn(List.of(
                dir(BASE),
                file(BASE + "NovelA/NovelA%20-%20Volume%2001.epub", 10)));
        service.execute(new NovelsCatalogSyncService.SyncRun());
        entityManager.clear();

        List<NovelVolume> afterRemoval = volumeRepository.findByNovel(novelA);
        assertEquals(2, afterRemoval.size(), "Removed volumes are kept (pipeline state survives)");
        assertTrue(afterRemoval.stream().anyMatch(v -> v.getVolumeNumber() == 2 && v.isRemovedFromSource()));
        assertTrue(afterRemoval.stream().anyMatch(v -> v.getVolumeNumber() == 1 && !v.isRemovedFromSource()));
    }

    @Test
    @DisplayName("novel dirs that disappear from the source get their volumes marked")
    void unvisitedNovelMarkedRemoved() {
        when(apiClient.listContents(BASE)).thenReturn(List.of(
                dir(BASE + "NovelA/")));
        when(apiClient.listContents(BASE + "NovelA/")).thenReturn(List.of(
                dir(BASE),
                file(BASE + "NovelA/NovelA%20-%20Volume%2001.epub", 10)));
        service.execute(new NovelsCatalogSyncService.SyncRun());
        entityManager.clear();

        // La segunda pasada ya no lista NovelA.
        when(apiClient.listContents(BASE)).thenReturn(List.of());
        service.execute(new NovelsCatalogSyncService.SyncRun());
        entityManager.clear();

        Novel novelA = novelRepository.findByRemotePath(BASE + "NovelA/").orElseThrow();
        List<NovelVolume> volumes = volumeRepository.findByNovel(novelA);
        assertTrue(volumes.stream().allMatch(NovelVolume::isRemovedFromSource),
                "Volumes of a vanished folder must be flagged, never deleted");
    }

    @Test
    @DisplayName("API failures are reported per directory without aborting the walk")
    void listingFailureIsRecorded() {
        when(apiClient.listContents(BASE)).thenThrow(new IllegalStateException("Invalid JSON response"));

        NovelsCatalogSyncService.SyncRun run = new NovelsCatalogSyncService.SyncRun();
        service.execute(run);

        assertNotNull(run.lastError);
        assertEquals(com.glez.frontendservice.dtos.NovelsCatalogSyncStatus.State.COMPLETED, run.state,
                "A listing failure is recorded but does not fail the whole sync");
    }

    @Test
    @DisplayName("startup sync triggers the catalog walk when enabled")
    void startupSync_triggersWhenEnabled() throws Exception {
        java.util.concurrent.CountDownLatch firstListing = new java.util.concurrent.CountDownLatch(1);
        when(apiClient.listContents(anyString())).thenAnswer(invocation -> {
            firstListing.countDown();
            return List.of();
        });
        NovelsCatalogSyncService enabled = new NovelsCatalogSyncService(apiClient, novelRepository,
                volumeRepository, fileRepository, new NovelFileNameParser(),
                "https://server.elscione.com" + BASE, 3, true, true);

        enabled.syncOnStartup();

        assertTrue(firstListing.await(2, java.util.concurrent.TimeUnit.SECONDS),
                "The startup-triggered sync should reach the first listing");
        assertNotEquals(com.glez.frontendservice.dtos.NovelsCatalogSyncStatus.State.IDLE,
                enabled.getStatus().state());
    }

    @Test
    @DisplayName("startup sync is skipped when the flag is disabled")
    void startupSync_skippedWhenDisabled() {
        service.syncOnStartup();

        assertEquals(com.glez.frontendservice.dtos.NovelsCatalogSyncStatus.State.IDLE,
                service.getStatus().state());
        verify(apiClient, never()).listContents(anyString());
    }
}
