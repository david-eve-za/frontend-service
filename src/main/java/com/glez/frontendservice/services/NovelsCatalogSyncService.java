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
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
 * <p><b>Pasadas incrementales por carpetas completas:</b> cada carpeta de obra
 * listada por completo en una pasada queda marcada en {@code Novel.listingComplete}
 * (+ {@code lastListedAt}); las corridas siguientes <i>saltan</i> esas carpetas
 * (un solo query precarga el conjunto completo por corrida — el ahorro real:
 * un listing HTTP + pausa anti-ban por carpeta evitada) y solo listan las
 * carpetas pendientes, las categorías (baratas, necesarias para descubrir
 * obras nuevas) y la raíz. Una corrida cancelada retoma donde se quedó. Las
 * carpetas saltadas se siguen considerando "visitadas": no hay que
 * re-verificar su presencia hasta el ciclo completo. Cuando el 100% de las
 * carpetas está marcado (catálogo local = catálogo del servidor), la siguiente
 * corrida reinicia todos los flags con un único bulk update y arranca un
 * <i>ciclo completo</i> de re-sincronización.</p>
 *
 * <p>Anti-ban por lotes: los listados se procesan en grupos de
 * {@code sync-batch-size} peticiones separados por pausas de
 * {@code sync-batch-pause-minutes} (default 100 listados / 10 minutos), de
 * modo que el patrón de tráfico sea de ráfagas cortas con recuperación larga
 * en lugar de una sesión sostenida de horas. La pausa es cancelable al
 * instante y se expone en el status como {@code pauseUntil}.</p>
 */
@Slf4j
@Service
public class NovelsCatalogSyncService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(NovelsCatalogSyncService.class);

    private final ElscioneApiClient apiClient;
    private final NovelRepository novelRepository;
    private final NovelVolumeRepository volumeRepository;
    private final NovelVolumeFileRepository fileRepository;
    private final NovelFileNameParser fileNameParser;
    /** Dashboard catalog counts are cached; cleared whenever a sync run ends. */
    private final CacheManager cacheManager;
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
    private final TransactionTemplate transactionTemplate;

    public NovelsCatalogSyncService(ElscioneApiClient apiClient,
                                     NovelRepository novelRepository,
                                     NovelVolumeRepository volumeRepository,
                                     NovelVolumeFileRepository fileRepository,
                                     NovelFileNameParser fileNameParser,
                                     CacheManager cacheManager,
                                     TransactionTemplate transactionTemplate,
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
        this.cacheManager = cacheManager;
        this.transactionTemplate = transactionTemplate;
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
        return run != null ? snapshot(run) : idleProgress();
    }

    /**
     * Estado de reposo con el progreso real del ciclo: aunque no haya corrida
     * activa, el UI puede mostrar cuántas carpetas de obra están completas y
     * cuántas faltan para el 100% que dispara el ciclo completo.
     */
    private NovelsCatalogSyncStatus idleProgress() {
        long total = novelRepository.countByRemotePathIsNotNull();
        long pending = novelRepository.countByRemotePathIsNotNullAndListingCompleteFalse();
        return new NovelsCatalogSyncStatus(NovelsCatalogSyncStatus.State.IDLE, null, 0, 0, 0, 0, 0, 0,
                null, null, null, null, false, total - pending, pending, 0);
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

    /**
     * Ejecuta manualmente la limpieza de novelas contenedoras y duplicados sueltos.
     * Útil para limpiar datos existentes sin esperar a una sincronización completa.
     */
    public void runManualCleanup() {
        transactionTemplate.executeWithoutResult(status -> {
            log.info("[sync-catálogo] Iniciando limpieza manual de novelas contenedoras y duplicados sueltos");
            
            // 1. Limpiar novelas contenedoras (remotePath != null con subdirectorios que tienen volúmenes)
            List<Novel> allNovels = novelRepository.findAll().stream()
                    .filter(n -> n.getRemotePath() != null)
                    .toList();
            int deletedContainerCount = 0;
            for (Novel novel : allNovels) {
                String remotePath = novel.getRemotePath();
                if (remotePath == null) {
                    continue;
                }
                if (hasSubdirectoryNovelsWithVolumes(remotePath)) {
                    log.info("[sync-catálogo] Eliminando novela contenedora: '{}' ({})", novel.getTitle(), remotePath);
                    volumeRepository.markRemovedFromSource(List.of(novel.getId()), java.time.Instant.now());
                    novelRepository.delete(novel);
                    deletedContainerCount++;
                }
            }
            if (deletedContainerCount > 0) {
                log.info("[sync-catálogo] Limpieza de contenedoras completada: {} eliminadas", deletedContainerCount);
            }
            
            // 2. Eliminar TODAS las novelas sueltas (remotePath == null) con títulos que empiezan por "!"
            // (marcadores de categoría como "!Side Stories", "!Balamiere", etc.)
            int deletedCategoryCount = novelRepository.deleteLooseCategoryNovels();
            if (deletedCategoryCount > 0) {
                log.info("[sync-catálogo] Limpieza de categorías sueltas completada: {} eliminadas (bulk delete)", deletedCategoryCount);
            }
            
            // 3. Limpiar duplicados de novelas sueltas restantes (remotePath == null con mismo título)
            cleanupDuplicateLooseFileNovels();
        });
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

    /**
     * Corre una pasada de sincronización sobre el executor propio.
     *
     * <p>Modo de la pasada (decidido al arrancar): si todas las carpetas de
     * obra están marcadas como completas (catálogo 100% sincronizado), se
     * reinician los flags y la corrida es un <b>ciclo completo</b>; si no,
     * es una <b>pasada incremental</b> que solo listan las carpetas
     * pendientes (ver {@link #walk}). El modo, el progreso del ciclo y las
     * carpetas saltadas quedan en el log y en el status expuesto al UI.</p>
     */
    void execute(SyncRun run) {
        try {
            long totalFolders = novelRepository.countByRemotePathIsNotNull();
            long pendingFolders = novelRepository.countByRemotePathIsNotNullAndListingCompleteFalse();
            run.foldersTotal = totalFolders;
            if (totalFolders > 0 && pendingFolders == 0) {
                // Ciclo completo: el catálogo local ya espeja el 100% del
                // servidor; reiniciamos todos los flags y re-sincronizamos
                // desde cero (regla de reinicio del ciclo).
                int reset = novelRepository.resetListingComplete();
                pendingFolders = totalFolders;
                run.fullCycle = true;
                log.info("[sync-catálogo] Ciclo completo: {}/{} carpetas sincronizadas al 100%; "
                                + "reiniciados {} flags, iniciando pasada completa desde cero",
                        totalFolders, totalFolders, reset);
            } else {
                run.fullCycle = false;
                log.info("[sync-catálogo] Pasada incremental: {}/{} carpetas completas, faltan {} por listar",
                        totalFolders - pendingFolders, totalFolders, pendingFolders);
            }
            // Un solo query por corrida precarga las carpetas completas: el
            // walk() consulta el Set en memoria (O(1) por carpeta) en lugar
            // de ir a la BD por cada directorio hijo.
            run.completeFolders.addAll(novelRepository.findRemotePathsByListingCompleteTrue());
            Set<String> visitedNovelPaths = new HashSet<>();
            walk(run, startPath, null, 0, visitedNovelPaths, null);
            // Cleanup: eliminar novelas contenedoras que tienen subdirectorios con archivos de libro
            // (novelas "contenedoras" como "!Side Stories" que solo agrupan otras novelas)
            cleanupContainerNovels(run, visitedNovelPaths);
            markUnvisitedNovelsRemoved(run, visitedNovelPaths);
            if (run.cancelled) {
                run.state = NovelsCatalogSyncStatus.State.CANCELLED;
            } else {
                run.state = NovelsCatalogSyncStatus.State.COMPLETED;
            }
            log.info("[sync-catálogo] Pasada terminada con estado {} ({} novelas listadas, {} volúmenes, "
                            + "{} archivos, {} carpetas completas saltadas)",
                    run.state, run.novelsSeen.get(), run.volumesSeen.get(), run.filesSeen.get(),
                    run.skippedFolders.get());
        } catch (Exception e) {
            run.state = NovelsCatalogSyncStatus.State.FAILED;
            run.lastError = e.getMessage();
            log.error("[sync-catálogo] Pasada fallida tras {} novelas, {} carpetas saltadas",
                    run.novelsSeen.get(), run.skippedFolders.get(), e);
        } finally {
            run.finishedAt = java.time.Instant.now();
            run.currentPath = null;
            evictCatalogCounts();
        }
    }

    /**
     * Invalida el conteo cacheado del dashboard tras cada corrida: los
     * COUNT(*) de novelas/volúmenes son full scans y el panel los consulta
     * en cada poll, así que sólo se recalculan cuando el catálogo cambia
     * (o expira el TTL corto de la caché).
     */
    private void evictCatalogCounts() {
        if (cacheManager == null) {
            return;
        }
        Cache cache = cacheManager.getCache("catalogCounts");
        if (cache != null) {
            cache.clear();
            log.debug("catalogCounts cache evicted after sync run");
        }
    }

    /**
     * Recorre el árbol remoto en profundidad listando cada carpeta: las
     * carpetas con archivos de libro se upsertean como obras (y quedan
     * marcadas como completas en la pasada), las demás (categorías) se
     * recorren para descubrir obras nuevas.
     *
     * <p>Las carpetas de obra marcadas como completas en una pasada
     * anterior se saltan por completo (ver {@code SyncRun.completeFolders},
     * precargado al inicio de la corrida): no se consumen listings ni
     * pausas anti-ban por ellas, pero sí se registran como visitadas para
     * que {@link #markUnvisitedNovelsRemoved} no las marque como
     * desaparecidas. El listado de una carpeta puede fallar (red, ban,
     * challenge): la carpeta queda pendiente y la siguiente pasada la
     * reintenta — una corrida cancelada retoma exactamente donde se quedó.</p>
     */
    private void walk(SyncRun run, String remotePath, String category, int depth,
                      Set<String> visitedNovelPaths, List<ElscioneApiClient.ApiItem> cachedContents) {
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
        if (cachedContents != null) {
            contents = cachedContents;
            run.listingsProcessed.incrementAndGet();
        } else {
            try {
                contents = apiClient.listContents(remotePath);
                run.listingsProcessed.incrementAndGet();
            } catch (Exception e) {
                run.lastError = remotePath + ": " + e.getMessage();
                log.warn("Could not list {}: {}", remotePath, e.getMessage());
                return;
            }
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

        // Determinar si esta carpeta debe crear una novela:
        // Solo crea novela si TIENE archivos de libro Y (no tiene subdirectorios O
        // ninguno de sus subdirectorios tiene archivos de libro).
        // Esto evita crear "novelas contenedor" como "!Side Stories" que solo
        // agrupan otras novelas reales en subdirectorios.
        boolean hasSubdirsWithBookFiles = false;
        // Cache de contenidos de subdirectorios para evitar listar dos veces
        Map<String, List<ElscioneApiClient.ApiItem>> subDirContentsCache = new HashMap<>();
        if (!directories.isEmpty()) {
            for (ElscioneApiClient.ApiItem dir : directories) {
                // Saltar la comprobación de archivos de libro para subdirectorios ya completados
                // (pasadas incrementales): ya sabemos si tienen libros o no.
                if (run.completeFolders.contains(dir.href())) {
                    continue;
                }
                try {
                    List<ElscioneApiClient.ApiItem> subContents = apiClient.listContents(dir.href());
                    subDirContentsCache.put(dir.href(), subContents);
                    boolean hasBookFiles = subContents.stream()
                            .filter(item -> item.size() != null)
                            .anyMatch(item -> fileNameParser.formatOf(lastSegment(item.href()))
                                    != NovelVolumeFile.Format.OTHER);
                    if (hasBookFiles) {
                        hasSubdirsWithBookFiles = true;
                        break;
                    }
                } catch (Exception e) {
                    log.debug("No se pudo listar subdirectorio {} para verificar archivos: {}", dir.href(), e.getMessage());
                }
            }
        }

        boolean shouldCreateNovel = !bookFiles.isEmpty() && depth > 0 && !hasSubdirsWithBookFiles;

        if (shouldCreateNovel) {
            upsertNovel(run, remotePath, category, bookFiles);
            visitedNovelPaths.add(remotePath);
        } else if (!bookFiles.isEmpty() && depth == 0) {
            // Archivos sueltos en la raíz
            upsertLooseFileNovels(run, bookFiles);
        }

        for (ElscioneApiClient.ApiItem directory : directories) {
            if (run.cancelled) {
                return;
            }
            // Carpeta de obra ya completada en una pasada anterior: se
            // SALTA el listing (el ahorro real de la pasada incremental)
            // pero se marca como visitada — sigue presente en el servidor
            // a efectos de markUnvisitedNovelsRemoved, que no debe marcar
            // sus volúmenes como eliminados por no haberla re-listado.
            if (run.completeFolders.contains(directory.href())) {
                visitedNovelPaths.add(directory.href());
                run.skippedFolders.incrementAndGet();
                log.debug("[sync-catálogo] Carpeta completa saltada: {}", lastDecodedSegment(directory.href()));
                continue;
            }
            // depth 0 -> direct children are novels or categories without a
            // category of their own; from depth 1 onwards the current folder
            // name becomes the category of its subtree.
            String childCategory = depth == 0
                    ? null
                    : (category != null ? category : lastDecodedSegment(remotePath));
            // Pasar los contenidos ya obtenidos para evitar listar dos veces
            List<ElscioneApiClient.ApiItem> subDirCachedContents = subDirContentsCache.get(directory.href());
            walk(run, directory.href(), childCategory, depth + 1, visitedNovelPaths, subDirCachedContents);
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
            // Filtrar títulos que empiezan con "!" (marcadores de categoría como "!Side Stories", "!Balamiere", etc.)
            // Estos no son novelas reales, sino contenedores de categorías que no deben crear novelas sueltas.
            if (title != null && title.startsWith("!")) {
                log.info("[sync-catálogo] Ignorando archivo suelto con título de categoría: {}", title);
                continue;
            }
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

    /**
     * Carpeta remota que contiene archivos de libro directamente = una obra.
     * El upsert queda marcado como {@code listingComplete}: su listado se
     * procesó por completo (volúmenes y archivos persistidos) y las pasadas
     * incrementales siguientes la saltarán hasta el próximo ciclo completo.
     */
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
        novel.setListingComplete(true);
        novel.setLastListedAt(java.time.Instant.now());
        upsertNovelVolumes(run, novel, bookFiles);
    }

    /**
     * Upsert de los volúmenes/archivos de una obra a partir del listado de
     * su carpeta: los existentes se refrescan (label, grupo traductor,
     * removedFromSource reseteado), los nuevos se crean, y los volúmenes que
     * ya no están en la fuente se marcan — todo en lotes (un saveAll por
     * tipo). Al terminar deja un DEBUG con el detalle por obra para el
     * seguimiento de la pasada.
     */
    private void upsertNovelVolumes(SyncRun run, Novel novel, List<ElscioneApiClient.ApiItem> bookFiles) {
        Instant now = java.time.Instant.now();
        run.novelsSeen.incrementAndGet();
        int newVolumesBefore = run.newVolumes.get();
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

        // Batched persistence: new + updated volumes/files accumulate and
        // flush in ONE saveAll per kind (single transaction + JDBC-batched
        // statements) instead of one saveAndFlush round-trip per row.
        List<NovelVolume> volumesToSave = new ArrayList<>();
        List<NovelVolumeFile> filesToSave = new ArrayList<>(bookFiles.size());

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
            // Every touched volume is persisted (new AND existing): the
            // metadata refresh of an existing row must not rely on dirty
            // checking, which a surrounding test transaction could discard
            // (and which never runs for detached rows in production).
            volumesToSave.add(volume);
            volume.setLabel(parsed.label());
            volume.setTranslatorGroup(parsed.translatorGroup());
            volume.setRemovedFromSource(false);
            volume.setUpdatedAt(now);
            run.volumesSeen.incrementAndGet();

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
            filesToSave.add(file);
        }

        // Flush volumes BEFORE files: brand-new volumes get their
        // client-generated ids assigned at persist, so the files' FK rows
        // are complete when their own batch runs. The AND-FLUSH variant
        // replaces the per-row saveAndFlush of the previous code: the
        // whole batch is written in one go and no dirty state survives
        // the method exit unflushed.
        volumeRepository.saveAllAndFlush(volumesToSave);
        fileRepository.saveAllAndFlush(filesToSave);

        // Volúmenes existentes que ya no están en la fuente: se marcan; se
        // conservan porque pueden tener un Book del pipeline enlazado.
        Instant markTime = java.time.Instant.now();
        List<NovelVolume> unvisited = new ArrayList<>();
        for (NovelVolume volume : existingVolumes) {
            if (!seenVolumeKeys.contains(volume.getSourceKey()) && !volume.isRemovedFromSource()) {
                volume.setRemovedFromSource(true);
                volume.setUpdatedAt(markTime);
                unvisited.add(volume);
            }
        }
        volumeRepository.saveAllAndFlush(unvisited);

        novelRepository.save(novel);
        log.debug("[sync-catálogo] Obra '{}' upsertada: {} volúmenes, {} archivos ({} nuevos, {} marcados como eliminados de la fuente)",
                novel.getTitle(), seenVolumeKeys.size(), bookFiles.size(),
                run.newVolumes.get() - newVolumesBefore, unvisited.size());
    }

    /**
     * Novelas (carpetas) que ya no existen en la fuente: sus volúmenes se
     * marcan con UN solo bulk update (antes: paging de todas las novelas
     * + una consulta de volúmenes y un save por volumen).
     */
    private void markUnvisitedNovelsRemoved(SyncRun run, Set<String> visitedNovelPaths) {
        List<UUID> unvisitedIds = novelRepository.findIdAndRemotePath().stream()
                .filter(view -> !visitedNovelPaths.contains(view.getRemotePath()))
                .map(NovelRepository.RemotePathView::getId)
                .toList();
        if (unvisitedIds.isEmpty()) {
            return;
        }
        run.removedVolumes.addAndGet(
                volumeRepository.markRemovedFromSource(unvisitedIds, java.time.Instant.now()));
    }

    /**
     * Limpia novelas contenedoras: elimina novelas que tienen subdirectorios con archivos de libro.
     * Estas son "novelas contenedoras" (ej. "!Side Stories") que solo agrupan otras novelas reales
     * en subdirectorios, pero que fueron creadas incorrectamente como novelas durante el sync.
     * <p>
     * Verifica localmente en la BD si la novela tiene "hijas" (novelas cuyo remotePath
     * empieza con el remotePath de esta novela + "/") que tengan volúmenes.
     * También elimina duplicados de novelas de archivos sueltos (remotePath == null) con el mismo título.
     */
    private void cleanupContainerNovels(SyncRun run, Set<String> visitedNovelPaths) {
        transactionTemplate.executeWithoutResult(status -> {
            log.info("[sync-catálogo] Iniciando limpieza de novelas contenedoras y duplicados sueltos");
            List<Novel> allNovels = novelRepository.findAll().stream()
                    .filter(n -> n.getRemotePath() != null)
                    .toList();
            int deletedCount = 0;
            for (Novel novel : allNovels) {
                String remotePath = novel.getRemotePath();
                if (remotePath == null) {
                    continue;
                }
                
                // Verificar si esta novela tiene subdirectorios que son novelas con volúmenes
                // comprobando en la BD local si hay novelas hijas con volúmenes
                if (hasSubdirectoryNovelsWithVolumes(remotePath)) {
                    log.info("[sync-catálogo] Eliminando novela contenedora: '{}' ({})", novel.getTitle(), remotePath);
                    // Marcar volúmenes como eliminados de la fuente
                    volumeRepository.markRemovedFromSource(List.of(novel.getId()), java.time.Instant.now());
                    // Eliminar la novela
                    novelRepository.delete(novel);
                    deletedCount++;
                }
            }
            if (deletedCount > 0) {
                log.info("[sync-catálogo] Limpieza completada: {} novelas contenedoras eliminadas", deletedCount);
            }
    
            // También limpiar duplicados de novelas de archivos sueltos (remotePath == null)
            cleanupDuplicateLooseFileNovels();
        });
    }

    /**
     * Elimina duplicados de novelas de archivos sueltos (remotePath == null) que tengan el mismo título.
     * Mantiene solo la primera (la más antigua por createdAt).
     * Debe llamarse dentro de una transacción (usar transactionTemplate en el caller).
     */
    private void cleanupDuplicateLooseFileNovels() {
            log.info("[sync-catálogo] Iniciando limpieza de duplicados de novelas sueltas");
            List<Novel> looseNovels = novelRepository.findByRemotePathIsNull();
            log.info("[sync-catálogo] Encontradas {} novelas sueltas", looseNovels.size());
            if (looseNovels.isEmpty()) {
                return;
            }
            
            // Agrupar por título
            Map<String, List<Novel>> byTitle = new HashMap<>();
            for (Novel novel : looseNovels) {
                byTitle.computeIfAbsent(novel.getTitle(), k -> new ArrayList<>()).add(novel);
            }
            
            int deletedCount = 0;
            for (Map.Entry<String, List<Novel>> entry : byTitle.entrySet()) {
                List<Novel> duplicates = entry.getValue();
                if (duplicates.size() > 1) {
                    // Ordenar por createdAt (mantener la más antigua), nulls first
                    duplicates.sort(Comparator.comparing(Novel::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                            .thenComparing(Novel::getId));
                    Novel toKeep = duplicates.get(0);
                    for (int i = 1; i < duplicates.size(); i++) {
                        Novel duplicate = duplicates.get(i);
                        log.info("[sync-catálogo] Eliminando novela suelta duplicada: '{}' (id: {})", duplicate.getTitle(), duplicate.getId());
                        volumeRepository.markRemovedFromSource(List.of(duplicate.getId()), java.time.Instant.now());
                        novelRepository.delete(duplicate);
                        deletedCount++;
                    }
                }
            }
            if (deletedCount > 0) {
                log.info("[sync-catálogo] Limpieza de duplicados sueltos completada: {} eliminados", deletedCount);
            }
    }

    /**
     * Verifica si una carpeta remota tiene subdirectorios que son novelas con volúmenes.
     * Comprueba en la BD local si hay novelas cuyo remotePath empieza con
     * el remotePath de esta novela + "/".
     */
    private boolean hasSubdirectoryNovelsWithVolumes(String remotePath) {
        String prefix = remotePath + "/";
        List<Novel> subNovels = novelRepository.findByRemotePathStartingWith(prefix);
        for (Novel subNovel : subNovels) {
            if (volumeRepository.countByNovel(subNovel) > 0) {
                return true;
            }
        }
        return false;
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
        long total = novelRepository.countByRemotePathIsNotNull();
        long pending = novelRepository.countByRemotePathIsNotNullAndListingCompleteFalse();
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
                run.pauseUntil,
                run.fullCycle,
                total - pending,
                pending,
                run.skippedFolders.get());
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
        /** Carpetas completas saltadas en esta corrida (listings ahorrados). */
        final AtomicInteger skippedFolders = new AtomicInteger();
        /** Carpetas de obra completas precargadas al iniciar la corrida. */
        final Set<String> completeFolders = new HashSet<>();
        /** Total de carpetas de obra del catálogo al iniciar la corrida. */
        volatile long foldersTotal;
        /** true si esta corrida es un ciclo completo (flags reiniciados). */
        volatile boolean fullCycle;
    }
}
