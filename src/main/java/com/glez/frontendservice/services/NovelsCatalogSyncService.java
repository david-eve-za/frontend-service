package com.glez.frontendservice.services;

import com.glez.frontendservice.components.NovelFileNameParser;
import com.glez.frontendservice.components.NovelFileManager;
import com.glez.frontendservice.dtos.NovelsCatalogSyncStatus;
import com.glez.frontendservice.model.Novel;
import com.glez.frontendservice.model.NovelVolume;
import com.glez.frontendservice.model.NovelVolumeFile;
import com.glez.frontendservice.repository.NovelRepository;
import com.glez.frontendservice.repository.NovelVolumeFileRepository;
import com.glez.frontendservice.repository.NovelVolumeRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sincroniza el catálogo de novelas desde elscione: recorre el árbol remoto
 * vía {@link ElscioneApiClient} pero, a diferencia del crawler de descargas,
 * solo persiste meta-información (obras, volúmenes y archivos con su
 * formato/tamaño). Es idempotente por remotePath/href, nunca borra estado
 * local (volúmenes desaparecidos se marcan removedFromSource) y se ejecuta
 * cada 3 horas vía @Scheduled además del trigger manual.
 *
 * Anti-ban por lotes: los listados se procesan en grupos de
 * {@code sync-batch-size} peticiones separados por pausas de
 * {@code sync-batch-pause-minutes} (default 100 listados / 10 minutos), de
 * modo que el patrón de tráfico sea de ráfagas cortas con recuperación larga
 * en lugar de una sesión sostenida de horas. La pausa es cancelable al
 * instante y se expone en el status como {@code pauseUntil}.
 */
@Slf4j
@Service
public class NovelsCatalogSyncService {

    private final ElscioneApiClient apiClient;
    private final NovelRepository novelRepository;
    private final NovelVolumeRepository volumeRepository;
    private final NovelVolumeFileRepository fileRepository;
    private final NovelFileNameParser fileNameParser;
    private final String startPath;
    private final String decodedStartPath;
    private final int maxDepth;
    private final boolean autoSyncEnabled;
    private final boolean syncOnStartupEnabled;
    /** Listados (peticiones al servidor) por lote; <=0 deshabilita el batching. */
    private final int syncBatchSize;
    /** Pausa entre lotes en milisegundos (config en minutos). */
    private final long batchPauseMillis;

    private final AtomicReference<SyncRun> activeRun = new AtomicReference<>();
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "novels-catalog-sync");
        thread.setDaemon(true);
        return thread;
    });

    public NovelsCatalogSyncService(ElscioneApiClient apiClient,
                                     NovelRepository novelRepository,
                                     NovelVolumeRepository volumeRepository,
                                     NovelVolumeFileRepository fileRepository,
                                     NovelFileNameParser fileNameParser,
                                     @Value("${app.novels.base-url}") String baseUrl,
                                     @Value("${app.novels-manager.sync-max-depth:3}") int maxDepth,
                                     @Value("${app.novels-manager.auto-sync-enabled:true}") boolean autoSyncEnabled,
                                     @Value("${app.novels-manager.sync-on-startup:true}") boolean syncOnStartupEnabled,
                                     @Value("${app.novels-manager.sync-batch-size:100}") int syncBatchSize,
                                     @Value("${app.novels-manager.sync-batch-pause-minutes:10}") double syncBatchPauseMinutes) {
        this.apiClient = apiClient;
        this.novelRepository = novelRepository;
        this.volumeRepository = volumeRepository;
        this.fileRepository = fileRepository;
        this.fileNameParser = fileNameParser;
        this.startPath = URI.create(baseUrl).getRawPath();
        this.decodedStartPath = URLDecoder.decode(startPath, StandardCharsets.UTF_8);
        this.maxDepth = maxDepth;
        this.autoSyncEnabled = autoSyncEnabled;
        this.syncOnStartupEnabled = syncOnStartupEnabled;
        this.syncBatchSize = syncBatchSize;
        this.batchPauseMillis = (long) (syncBatchPauseMinutes * 60_000);
    }

    /**
     * Dispara la sincronización de metadatos al arrancar la aplicación para
     * que el catálogo esté actualizado sin esperar al primer cron de 3 horas.
     * Idempotente: si ya hay una sincronización en curso se omite.
     */
    @EventListener(ApplicationReadyEvent.class)
    void syncOnStartup() {
        if (!syncOnStartupEnabled) {
            log.debug("Catalog startup sync disabled; skipping");
            return;
        }
        try {
            startSync();
            log.info("Catalog sync triggered by application startup");
        } catch (IllegalStateException e) {
            log.info("Skipping startup catalog sync: {}", e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        syncExecutor.shutdownNow();
    }

    /** Trigger manual de la sincronización (REST); rechazado si ya hay una en curso. */
    public synchronized NovelsCatalogSyncStatus startSync() {
        SyncRun current = activeRun.get();
        if (current != null && current.state == NovelsCatalogSyncStatus.State.RUNNING) {
            throw new IllegalStateException("A catalog synchronization is already running since " + current.startedAt);
        }
        SyncRun run = new SyncRun();
        activeRun.set(run);
        log.info("Starting novels catalog metadata sync from {}", startPath);
        syncExecutor.submit(() -> execute(run));
        return snapshot(run);
    }

    public NovelsCatalogSyncStatus getStatus() {
        SyncRun run = activeRun.get();
        return run != null ? snapshot(run) : NovelsCatalogSyncStatus.idle();
    }

    public boolean cancel() {
        SyncRun run = activeRun.get();
        if (run == null || run.state != NovelsCatalogSyncStatus.State.RUNNING) {
            return false;
        }
        run.cancelled = true;
        log.info("Cancellation requested for catalog sync started at {}", run.startedAt);
        return true;
    }

    @Scheduled(cron = "${app.novels-manager.sync-cron:0 0 */3 * * *}")
    void scheduledSync() {
        if (!autoSyncEnabled) {
            log.debug("Catalog auto-sync disabled; skipping scheduled run");
            return;
        }
        try {
            startSync();
        } catch (IllegalStateException e) {
            log.info("Skipping scheduled catalog sync: {}", e.getMessage());
        }
    }

    void execute(SyncRun run) {
        try {
            Set<String> visitedNovelPaths = new HashSet<>();
            walk(run, startPath, null, 0, visitedNovelPaths);
            markUnvisitedNovelsRemoved(run, visitedNovelPaths);
            if (run.cancelled) {
                run.state = NovelsCatalogSyncStatus.State.CANCELLED;
            } else {
                run.state = NovelsCatalogSyncStatus.State.COMPLETED;
            }
            log.info("Catalog sync finished with state {} ({} novels, {} volumes, {} files)",
                    run.state, run.novelsSeen, run.volumesSeen, run.filesSeen);
        } catch (Exception e) {
            run.state = NovelsCatalogSyncStatus.State.FAILED;
            run.lastError = e.getMessage();
            log.error("Catalog sync failed", e);
        } finally {
            run.finishedAt = java.time.Instant.now();
            run.currentPath = null;
        }
    }

    private void walk(SyncRun run, String remotePath, String category, int depth,
                      Set<String> visitedNovelPaths) {
        if (run.cancelled || depth > maxDepth) {
            return;
        }
        // Anti-ban (Tier 4): procesar los listados en lotes separados por una
        // pausa larga. La pausa se evalúa ANTES del siguiente listado (no tras
        // cerrar un lote), así el lote final no deja una pausa basura al final
        // del sync, y el primer lote arranca inmediatamente.
        if (syncBatchSize > 0 && run.listingsProcessed.get() >= syncBatchSize && !run.cancelled) {
            pauseBetweenBatches(run);
        }
        if (run.cancelled) {
            return;
        }
        run.currentPath = remotePath;
        List<ElscioneApiClient.ApiItem> contents;
        try {
            contents = apiClient.listContents(remotePath);
            run.listingsProcessed.incrementAndGet();
        } catch (Exception e) {
            run.lastError = remotePath + ": " + e.getMessage();
            log.warn("Could not list {}: {}", remotePath, e.getMessage());
            return;
        }

        List<ElscioneApiClient.ApiItem> files = new ArrayList<>();
        List<ElscioneApiClient.ApiItem> directories = new ArrayList<>();
        for (ElscioneApiClient.ApiItem item : contents) {
            String href = item.href();
            if (href == null || href.equals(remotePath) || !href.startsWith(remotePath)) {
                continue;
            }
            if (item.size() != null) {
                files.add(item);
            } else {
                directories.add(item);
            }
        }

        List<ElscioneApiClient.ApiItem> bookFiles = files.stream()
                .filter(item -> fileNameParser.formatOf(lastSegment(item.href()))
                        != NovelVolumeFile.Format.OTHER)
                .toList();

        if (!bookFiles.isEmpty()) {
            if (depth == 0) {
                upsertLooseFileNovels(run, bookFiles);
            } else {
                upsertNovel(run, remotePath, category, bookFiles);
                visitedNovelPaths.add(remotePath);
            }
        }

        for (ElscioneApiClient.ApiItem directory : directories) {
            if (run.cancelled) {
                return;
            }
            // depth 0 -> direct children are novels or categories without a
            // category of their own; from depth 1 onwards the current folder
            // name becomes the category of its subtree.
            String childCategory = depth == 0
                    ? null
                    : (category != null ? category : lastDecodedSegment(remotePath));
            walk(run, directory.href(), childCategory, depth + 1, visitedNovelPaths);
        }
    }

    /**
     * Pausa anti-ban entre lotes de listados: dormir {@code batchPauseMillis}
     * en ticks de 1s comprobando la cancelación, de modo que POST
     * /api/novels-manager/sync/cancel responda en menos de un segundo aunque
     * la pausa sea de 10 minutos. El estado permanece RUNNING (la cancelación
     * lo requiere) y {@code pauseUntil} se expone en el status para
     * observabilidad.
     */
    private void pauseBetweenBatches(SyncRun run) {
        run.pausesTaken.incrementAndGet();
        Instant until = Instant.now().plusMillis(batchPauseMillis);
        run.pauseUntil = until;
        log.info("Batch of {} listings complete ({} so far); pausing {} ms until {} to avoid server bans",
                syncBatchSize, run.listingsProcessed.get(), batchPauseMillis, until);
        try {
            long deadline = System.currentTimeMillis() + batchPauseMillis;
            while (!run.cancelled && System.currentTimeMillis() < deadline) {
                Thread.sleep(Math.min(1000, Math.max(1, deadline - System.currentTimeMillis())));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            run.pauseUntil = null;
            if (run.cancelled) {
                log.info("Batch pause interrupted by cancellation");
            } else {
                log.info("Batch pause finished; continuing with the next batch");
            }
        }
    }

    /** Archivos sueltos en la raíz: se agrupan en novelas virtuales por título parseado. */
    private void upsertLooseFileNovels(SyncRun run, List<ElscioneApiClient.ApiItem> looseFiles) {
        Map<String, List<ElscioneApiClient.ApiItem>> byTitle = new HashMap<>();
        for (ElscioneApiClient.ApiItem item : looseFiles) {
            String fileName = lastDecodedSegment(item.href());
            String title = fileNameParser.novelTitleFromLooseFile(fileName)
                    .orElseGet(() -> fileName);
            byTitle.computeIfAbsent(title, t -> new ArrayList<>()).add(item);
        }
        for (Map.Entry<String, List<ElscioneApiClient.ApiItem>> entry : byTitle.entrySet()) {
            Novel novel = novelRepository.findByTitleAndRemotePathIsNull(entry.getKey())
                    .orElseGet(() -> {
                        Novel created = new Novel();
                        created.setTitle(entry.getKey());
                        created.setCreatedAt(java.time.Instant.now());
                        run.newNovels.incrementAndGet();
                        return created;
                    });
            upsertNovelVolumes(run, novel, entry.getValue());
        }
    }

    /** Carpeta remota que contiene archivos de libro directamente = una obra. */
    private void upsertNovel(SyncRun run, String remotePath, String category,
                             List<ElscioneApiClient.ApiItem> bookFiles) {
        Novel novel = novelRepository.findByRemotePath(remotePath)
                .orElseGet(() -> {
                    Novel created = new Novel();
                    created.setRemotePath(remotePath);
                    created.setCreatedAt(java.time.Instant.now());
                    run.newNovels.incrementAndGet();
                    return created;
                });
        novel.setTitle(lastDecodedSegment(remotePath));
        novel.setCategory(category);
        novel.setLastSyncedAt(java.time.Instant.now());
        novel.setUpdatedAt(java.time.Instant.now());
        upsertNovelVolumes(run, novel, bookFiles);
    }

    private void upsertNovelVolumes(SyncRun run, Novel novel, List<ElscioneApiClient.ApiItem> bookFiles) {
        Instant now = java.time.Instant.now();
        run.novelsSeen.incrementAndGet();
        // Persist first: brand-new novels need an ID before volumes reference them.
        novelRepository.saveAndFlush(novel);
        List<NovelVolume> existingVolumes = volumeRepository.findByNovel(novel);
        Map<String, NovelVolume> volumesByKey = new HashMap<>();
        for (NovelVolume volume : existingVolumes) {
            volumesByKey.put(volume.getSourceKey(), volume);
        }

        Set<String> seenVolumeKeys = new HashSet<>();
        List<String> hrefs = bookFiles.stream().map(ElscioneApiClient.ApiItem::href).toList();
        Map<String, NovelVolumeFile> existingFiles = new HashMap<>();
        for (NovelVolumeFile file : fileRepository.findByRemoteHrefIn(hrefs)) {
            existingFiles.put(file.getRemoteHref(), file);
        }

        for (ElscioneApiClient.ApiItem item : bookFiles) {
            run.filesSeen.incrementAndGet();
            String fileName = lastDecodedSegment(item.href());
            NovelFileNameParser.ParsedVolumeFile parsed = fileNameParser.parse(fileName);
            String key = parsed.volumeNumber() != null
                    ? "vol:" + parsed.volumeNumber()
                    : "file:" + fileName;
            seenVolumeKeys.add(key);

            NovelVolume volume = volumesByKey.get(key);
            if (volume == null) {
                volume = new NovelVolume();
                volume.setNovel(novel);
                volume.setSourceKey(key);
                volume.setVolumeNumber(parsed.volumeNumber());
                volume.setRemoteDir(novel.getRemotePath());
                volume.setCreatedAt(now);
                run.newVolumes.incrementAndGet();
                // Register in the map so the remaining format variants of
                // the same volume reuse this row instead of duplicating it.
                volumesByKey.put(key, volume);
            }
            volume.setLabel(parsed.label());
            volume.setTranslatorGroup(parsed.translatorGroup());
            volume.setRemovedFromSource(false);
            volume.setUpdatedAt(now);
            run.volumesSeen.incrementAndGet();
            volumeRepository.saveAndFlush(volume);

            NovelVolumeFile file = existingFiles.get(item.href());
            if (file == null) {
                file = new NovelVolumeFile();
                file.setRemoteHref(item.href());
                file.setCreatedAt(now);
            }
            file.setVolume(volume);
            file.setFormat(parsed.format());
            file.setSizeBytes(item.size());
            file.setFileName(fileName);
            file.setUpdatedAt(now);
            fileRepository.saveAndFlush(file);
        }

        // Volúmenes existentes que ya no están en la fuente: se marcan; se
        // conservan porque pueden tener un Book del pipeline enlazado.
        for (NovelVolume volume : existingVolumes) {
            if (!seenVolumeKeys.contains(volume.getSourceKey()) && !volume.isRemovedFromSource()) {
                volume.setRemovedFromSource(true);
                volume.setUpdatedAt(now);
                volumeRepository.saveAndFlush(volume);
            }
        }

        novelRepository.save(novel);
    }

    /** Novelas (carpetas) que ya no existen en la fuente: sus volúmenes se marcan. */
    private void markUnvisitedNovelsRemoved(SyncRun run, Set<String> visitedNovelPaths) {
        int from = 0;
        Instant now = java.time.Instant.now();
        while (true) {
            List<Novel> page = novelRepository.findAll(PageRequest.of(from, 200)).getContent();
            if (page.isEmpty()) {
                break;
            }
            for (Novel novel : page) {
                if (novel.getRemotePath() != null && !visitedNovelPaths.contains(novel.getRemotePath())) {
                    for (NovelVolume volume : volumeRepository.findByNovel(novel)) {
                        if (!volume.isRemovedFromSource()) {
                            volume.setRemovedFromSource(true);
                            volume.setUpdatedAt(now);
                            volumeRepository.saveAndFlush(volume);
                            run.removedVolumes.incrementAndGet();
                        }
                    }
                }
            }
            from++;
        }
    }

    private String lastSegment(String href) {
        String withoutSlash = href.endsWith("/") ? href.substring(0, href.length() - 1) : href;
        int slash = withoutSlash.lastIndexOf('/');
        return slash >= 0 ? withoutSlash.substring(slash + 1) : withoutSlash;
    }

    private String lastDecodedSegment(String href) {
        String segment = lastSegment(href);
        String decoded = URLDecoder.decode(segment, StandardCharsets.UTF_8).trim();
        return decoded.isEmpty() ? segment : decoded;
    }

    private NovelsCatalogSyncStatus snapshot(SyncRun run) {
        return new NovelsCatalogSyncStatus(
                run.state,
                run.currentPath,
                run.novelsSeen.get(),
                run.volumesSeen.get(),
                run.filesSeen.get(),
                run.newNovels.get(),
                run.newVolumes.get(),
                run.removedVolumes.get(),
                run.startedAt,
                run.finishedAt,
                run.lastError,
                run.pauseUntil);
    }

    /** Estado mutable de la ejecución en curso. */
    static class SyncRun {
        volatile NovelsCatalogSyncStatus.State state = NovelsCatalogSyncStatus.State.RUNNING;
        volatile String currentPath;
        volatile String lastError;
        volatile boolean cancelled;
        /** Fin de la pausa entre lotes si hay una activa; null en caso contrario. */
        volatile Instant pauseUntil;
        volatile java.time.Instant finishedAt;
        final java.time.Instant startedAt = java.time.Instant.now();
        final AtomicInteger listingsProcessed = new AtomicInteger();
        final AtomicInteger pausesTaken = new AtomicInteger();
        final AtomicInteger novelsSeen = new AtomicInteger();
        final AtomicInteger volumesSeen = new AtomicInteger();
        final AtomicInteger filesSeen = new AtomicInteger();
        final AtomicInteger newNovels = new AtomicInteger();
        final AtomicInteger newVolumes = new AtomicInteger();
        final AtomicInteger removedVolumes = new AtomicInteger();
    }
}
