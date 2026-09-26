package com.glez.frontendservice.services;

import com.glez.frontendservice.components.NovelFileManager;
import com.glez.frontendservice.dtos.NovelsCrawlStatus;
import com.glez.frontendservice.exception.NovelsApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("NovelsUpdaterService Tests")
class NovelsUpdaterServiceTest {

    private static final String BASE_URL = "https://server.elscione.com/Officially%20Translated%20Light%20Novels/";
    private static final String ROOT = "/Officially%20Translated%20Light%20Novels/";
    private static final String SERIES_DIR = "/Officially%20Translated%20Light%20Novels/Book%20A/";
    private static final String README_FILE = "/Officially%20Translated%20Light%20Novels/Readme.txt";
    private static final String VOLUME_FILE = "/Officially%20Translated%20Light%20Novels/Book%20A/Volume%201.epub";

    @TempDir
    Path tempDir;

    private ElscioneApiClient apiClient;
    private NovelFileManager fileManager;
    private NovelsUpdaterService service;

    @BeforeEach
    void setUp() {
        apiClient = mock(ElscioneApiClient.class);
        fileManager = new NovelFileManager(tempDir.resolve("output").toString());
        service = new NovelsUpdaterService(apiClient, fileManager, BASE_URL, 2);
    }

    @Test
    @DisplayName("sync crawls directories recursively and downloads new files")
    void sync_crawlsDirectoriesAndDownloadsFiles() throws Exception {
        stubDefaultTree();
        stubDownloadWritingContent();

        NovelsCrawlStatus initial = service.startSync();
        assertEquals(NovelsCrawlStatus.State.RUNNING, initial.getState());

        NovelsCrawlStatus result = awaitTerminalStatus();

        assertEquals(NovelsCrawlStatus.State.COMPLETED, result.getState());
        assertEquals(ROOT, result.getStartPath());
        assertEquals(2, result.getFilesFound());
        assertEquals(2, result.getFilesDownloaded());
        assertEquals(0, result.getFilesSkippedExisting());
        assertEquals(0, result.getFilesFailed());
        assertEquals(2, result.getDirectoriesExplored());
        assertEquals(28L, result.getBytesDownloaded());
        assertNull(result.getLastError());
        assertTrue(Files.exists(tempDir.resolve("output").resolve("Readme.txt")));
        assertTrue(Files.exists(tempDir.resolve("output").resolve("Book A").resolve("Volume 1.epub")));
    }

    @Test
    @DisplayName("sync skips files that already exist locally")
    void sync_skipsExistingFiles() throws Exception {
        Path existing = tempDir.resolve("output").resolve("Readme.txt");
        Files.writeString(existing, "already there");
        stubDefaultTree();
        stubDownloadWritingContent();

        NovelsCrawlStatus result = awaitTerminalStatusAfterStart();

        assertEquals(NovelsCrawlStatus.State.COMPLETED, result.getState());
        assertEquals(2, result.getFilesFound());
        assertEquals(1, result.getFilesSkippedExisting());
        assertEquals(1, result.getFilesDownloaded());
        assertEquals("already there", Files.readString(existing));
    }

    @Test
    @DisplayName("sync ignores items outside the current remote path, like the Python filter")
    void sync_ignoresItemsOutsideCurrentPath() throws Exception {
        when(apiClient.listContents(ROOT)).thenReturn(List.of(
                new ElscioneApiClient.ApiItem("/Other/place.epub", 50L),
                new ElscioneApiClient.ApiItem(null, 100L)));

        NovelsCrawlStatus result = awaitTerminalStatusAfterStart();

        assertEquals(NovelsCrawlStatus.State.COMPLETED, result.getState());
        assertEquals(0, result.getFilesFound());
        assertEquals(0, result.getFilesDownloaded());
        assertEquals(1, result.getDirectoriesExplored());
    }

    @Test
    @DisplayName("sync aborts the failing branch but completes when a listing fails")
    void sync_listingFailureAbortsBranchButCompletes() throws Exception {
        when(apiClient.listContents(ROOT)).thenThrow(new NovelsApiException("boom"));

        NovelsCrawlStatus result = awaitTerminalStatusAfterStart();

        assertEquals(NovelsCrawlStatus.State.COMPLETED, result.getState());
        assertEquals(1, result.getDirectoriesExplored());
        assertEquals(0, result.getFilesFound());
        assertNotNull(result.getLastError());
        assertTrue(result.getLastError().contains("boom"));
    }

    @Test
    @DisplayName("sync counts failed downloads without aborting the crawl")
    void sync_downloadFailuresAreCounted() throws Exception {
        when(apiClient.listContents(ROOT)).thenReturn(List.of(
                new ElscioneApiClient.ApiItem(README_FILE, 100L)));
        when(apiClient.getFullUrl(anyString())).thenAnswer(inv -> "https://server.elscione.com" + inv.getArgument(0));
        doThrow(new NovelsApiException("connection reset"))
                .when(apiClient).downloadToFile(anyString(), any(Path.class), any());

        NovelsCrawlStatus result = awaitTerminalStatusAfterStart();

        assertEquals(NovelsCrawlStatus.State.COMPLETED, result.getState());
        assertEquals(1, result.getFilesFound());
        assertEquals(1, result.getFilesFailed());
        assertEquals(0, result.getFilesDownloaded());
        assertNotNull(result.getLastError());
    }

    @Test
    @DisplayName("startSync rejects a second run while one is already running")
    void startSync_rejectsConcurrentRuns() throws Exception {
        when(apiClient.listContents(ROOT)).thenAnswer(invocation -> {
            Thread.sleep(300);
            return List.of();
        });

        service.startSync();
        assertThrows(IllegalStateException.class, () -> service.startSync());

        NovelsCrawlStatus result = awaitTerminalStatus();
        assertEquals(NovelsCrawlStatus.State.COMPLETED, result.getState());
    }

    @Test
    @DisplayName("cancel returns false when no synchronization is running")
    void cancel_returnsFalseWhenIdle() {
        assertFalse(service.cancel());
    }

    @Test
    @DisplayName("getStatus reports IDLE with the configured start path before any sync")
    void getStatus_returnsIdleBeforeAnySync() {
        NovelsCrawlStatus status = service.getStatus();

        assertEquals(NovelsCrawlStatus.State.IDLE, status.getState());
        assertEquals(ROOT, status.getStartPath());
    }

    private void stubDefaultTree() {
        when(apiClient.listContents(ROOT)).thenReturn(List.of(
                new ElscioneApiClient.ApiItem(README_FILE, 10L),
                new ElscioneApiClient.ApiItem(SERIES_DIR, null)));
        when(apiClient.listContents(SERIES_DIR)).thenReturn(List.of(
                new ElscioneApiClient.ApiItem(VOLUME_FILE, 100L)));
    }

    private void stubDownloadWritingContent() {
        when(apiClient.getFullUrl(anyString())).thenAnswer(inv -> "https://server.elscione.com" + inv.getArgument(0));
        doAnswer(invocation -> {
            Path target = invocation.getArgument(1);
            Files.createDirectories(target.getParent());
            Files.writeString(target, "novel content");
            LongConsumer progress = invocation.getArgument(2);
            progress.accept(14L);
            return null;
        }).when(apiClient).downloadToFile(anyString(), any(Path.class), any());
    }

    private NovelsCrawlStatus awaitTerminalStatusAfterStart() throws InterruptedException {
        service.startSync();
        return awaitTerminalStatus();
    }

    private NovelsCrawlStatus awaitTerminalStatus() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        NovelsCrawlStatus status = service.getStatus();
        while (status.getState() == NovelsCrawlStatus.State.RUNNING && System.nanoTime() < deadline) {
            Thread.sleep(25);
            status = service.getStatus();
        }
        return status;
    }
}
