package com.glez.frontendservice.services;

import com.glez.frontendservice.components.NovelFileManager;
import com.glez.frontendservice.dtos.NovelsCrawlStatus;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
/**
 * Port del crawler NovelCrawler de NovelsUpdater.py: explora recursivamente el
 * árbol remoto vía API, descarga en paralelo los archivos de cada directorio
 * (pool fijo de maxWorkers hilos, igual que ThreadPoolExecutor del script),
 * omite archivos ya existentes y deduplica rutas visitadas. Se ejecuta de
 * forma asíncrona y expone progreso y cancelación como servicio REST.
 */
@Slf4j
@Service
public class NovelsUpdaterService {

    private enum DownloadOutcome {DOWNLOADED, SKIPPED_EXISTING, FAILED}

    private final ElscioneApiClient apiClient;
    private final NovelFileManager fileManager;
    private final String startPath;
    private final int maxWorkers;

    private final AtomicReference<CrawlRun> activeRun = new AtomicReference<>();
    private final ExecutorService crawlExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "novels-crawler");
        thread.setDaemon(true);
        return thread;
    });

    public NovelsUpdaterService(ElscioneApiClient apiClient,
                                NovelFileManager fileManager,
                                @Value("${app.novels.base-url}") String baseUrl,
                                @Value("${app.novels.max-workers:3}") int maxWorkers) {
        this.apiClient = apiClient;
        this.fileManager = fileManager;
        this.startPath = URI.create(baseUrl).getRawPath();
        this.maxWorkers = maxWorkers;
    }

    @PreDestroy
    void shutdown() {
        crawlExecutor.shutdownNow();
    }

    public synchronized NovelsCrawlStatus startSync() {
        CrawlRun current = activeRun.get();
        if (current != null && current.isRunning()) {
            throw new IllegalStateException("A novels synchronization is already running since " + current.startedAt);
        }
        CrawlRun run = new CrawlRun(startPath);
        activeRun.set(run);
        log.info("Starting novels synchronization from {} with {} download workers", startPath, maxWorkers);
        crawlExecutor.submit(() -> execute(run));
        return snapshot(run);
    }

    public NovelsCrawlStatus getStatus() {
        CrawlRun run = activeRun.get();
        return run != null ? snapshot(run) : idleStatus();
    }

    public boolean cancel() {
        CrawlRun run = activeRun.get();
        if (run == null || !run.isRunning()) {
            return false;
        }
        run.cancelled = true;
        Thread crawlThread = run.crawlThread;
        if (crawlThread != null) {
            crawlThread.interrupt();
        }
        log.info("Cancellation requested for novels synchronization started at {}", run.startedAt);
        return true;
    }

    void execute(CrawlRun run) {
        run.crawlThread = Thread.currentThread();
        ExecutorService downloadPool = Executors.newFixedThreadPool(maxWorkers, runnable -> {
            Thread thread = new Thread(runnable, "novels-downloader");
            thread.setDaemon(true);
            return thread;
        });
        try {
            crawl(run, startPath, List.of(), downloadPool);
            run.state = run.cancelled ? NovelsCrawlStatus.State.CANCELLED : NovelsCrawlStatus.State.COMPLETED;
            log.info("Novels synchronization finished with state {}", run.state);
        } catch (Exception e) {
            run.state = NovelsCrawlStatus.State.FAILED;
            run.lastError = e.getMessage();
            log.error("Novels synchronization failed", e);
        } finally {
            run.finishedAt = Instant.now();
            downloadPool.shutdownNow();
        }
    }

    private void crawl(CrawlRun run, String remotePath, List<String> localSegments, ExecutorService downloadPool) {
        if (run.cancelled) {
            return;
        }
        if (!run.visited.add(remotePath)) {
            return;
        }
        run.currentPath.set(remotePath);
        run.directoriesExplored.incrementAndGet();
        log.debug("Exploring {}", remotePath);

        List<ElscioneApiClient.ApiItem> contents;
        try {
            contents = apiClient.listContents(remotePath);
        } catch (Exception e) {
            run.lastError = remotePath + ": " + e.getMessage();
            log.warn("Could not list {}: {}", remotePath, e.getMessage());
            return;
        }

        List<ElscioneApiClient.ApiItem> files = new ArrayList<>();
        List<ElscioneApiClient.ApiItem> directories = new ArrayList<>();
        for (ElscioneApiClient.ApiItem item : contents) {
            String href = item.href();
            if (href == null || href.equals(remotePath) || !href.contains(remotePath)) {
                continue;
            }
            if (item.size() != null) {
                files.add(item);
            } else {
                directories.add(item);
            }
        }
        run.filesFound.addAndGet(files.size());

        if (!files.isEmpty()) {
            List<Future<DownloadOutcome>> futures = new ArrayList<>();
            for (ElscioneApiClient.ApiItem file : files) {
                futures.add(downloadPool.submit(() -> downloadItem(run, file, localSegments)));
            }
            for (Future<DownloadOutcome> future : futures) {
                try {
                    recordOutcome(run, future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    run.cancelled = true;
                    return;
                } catch (ExecutionException e) {
                    run.filesFailed.incrementAndGet();
                    log.warn("Download task failed: {}", e.getCause().getMessage());
                }
            }
        }

        for (ElscioneApiClient.ApiItem directory : directories) {
            if (run.cancelled) {
                return;
            }
            String directoryName = lastSegmentOf(decodeHref(directory.href()));
            List<String> childSegments = new ArrayList<>(localSegments);
            childSegments.add(fileManager.sanitizePath(directoryName));
            fileManager.ensureDirectory(fileManager.getLocalPath(childSegments));
            crawl(run, directory.href(), childSegments, downloadPool);
        }
    }

    private void recordOutcome(CrawlRun run, DownloadOutcome outcome) {
        switch (outcome) {
            case DOWNLOADED -> run.filesDownloaded.incrementAndGet();
            case SKIPPED_EXISTING -> run.filesSkippedExisting.incrementAndGet();
            case FAILED -> run.filesFailed.incrementAndGet();
        }
    }

    private DownloadOutcome downloadItem(CrawlRun run, ElscioneApiClient.ApiItem item, List<String> localSegments) {
        String fileName = lastSegmentOf(decodeHref(item.href()));
        List<String> targetSegments = new ArrayList<>(localSegments);
        targetSegments.add(fileName);
        Path target = fileManager.getLocalPath(targetSegments);
        if (fileManager.fileExists(target)) {
            log.debug("Skipping existing file {}", target);
            return DownloadOutcome.SKIPPED_EXISTING;
        }
        try {
            apiClient.downloadToFile(apiClient.getFullUrl(item.href()), target, run.bytesDownloaded::addAndGet);
            log.debug("Downloaded {}", target);
            return DownloadOutcome.DOWNLOADED;
        } catch (Exception e) {
            run.lastError = fileName + ": " + e.getMessage();
            log.warn("Download failed for {}: {}", fileName, e.getMessage());
            return DownloadOutcome.FAILED;
        }
    }

    private String decodeHref(String href) {
        return URLDecoder.decode(href.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private String lastSegmentOf(String path) {
        String[] segments = path.split("/");
        for (int i = segments.length - 1; i >= 0; i--) {
            if (!segments[i].isEmpty()) {
                return segments[i];
            }
        }
        return "";
    }

    private NovelsCrawlStatus snapshot(CrawlRun run) {
        Instant end = run.finishedAt != null ? run.finishedAt : Instant.now();
        return NovelsCrawlStatus.builder()
                .state(run.state)
                .startPath(run.startPath)
                .currentPath(run.currentPath.get())
                .startedAt(run.startedAt)
                .finishedAt(run.finishedAt)
                .elapsedSeconds(Duration.between(run.startedAt, end).getSeconds())
                .directoriesExplored(run.directoriesExplored.get())
                .filesFound(run.filesFound.get())
                .filesDownloaded(run.filesDownloaded.get())
                .filesSkippedExisting(run.filesSkippedExisting.get())
                .filesFailed(run.filesFailed.get())
                .bytesDownloaded(run.bytesDownloaded.get())
                .lastError(run.lastError)
                .build();
    }

    private NovelsCrawlStatus idleStatus() {
        return NovelsCrawlStatus.builder()
                .state(NovelsCrawlStatus.State.IDLE)
                .startPath(startPath)
                .build();
    }

    static final class CrawlRun {
        final String startPath;
        final Instant startedAt = Instant.now();
        final Set<String> visited = ConcurrentHashMap.newKeySet();
        final AtomicReference<String> currentPath = new AtomicReference<>();
        final AtomicLong directoriesExplored = new AtomicLong();
        final AtomicLong filesFound = new AtomicLong();
        final AtomicLong filesDownloaded = new AtomicLong();
        final AtomicLong filesSkippedExisting = new AtomicLong();
        final AtomicLong filesFailed = new AtomicLong();
        final AtomicLong bytesDownloaded = new AtomicLong();
        volatile NovelsCrawlStatus.State state = NovelsCrawlStatus.State.RUNNING;
        volatile Instant finishedAt;
        volatile String lastError;
        volatile boolean cancelled;
        volatile Thread crawlThread;

        CrawlRun(String startPath) {
            this.startPath = startPath;
        }

        boolean isRunning() {
            return state == NovelsCrawlStatus.State.RUNNING;
        }
    }
}
