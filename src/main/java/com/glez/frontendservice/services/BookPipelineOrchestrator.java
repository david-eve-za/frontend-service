package com.glez.frontendservice.services;

import com.glez.frontendservice.model.ChunkStatus;
import com.glez.frontendservice.model.ProcessStep;
import com.glez.frontendservice.model.ProcessTraceEvent;
import com.glez.frontendservice.model.ProcessingStatus;
import com.glez.frontendservice.repository.BlockRepository;
import com.glez.frontendservice.repository.BookRepository;
import com.glez.frontendservice.repository.ChunksRepository;
import com.glez.frontendservice.repository.ChunksRepository.ChunkStatusView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Drives the complete book processing pipeline (EXTRACT → SPLIT →
 * TRANSLATE → AUDIO → FINALIZE), recording a persisted trace event per
 * step attempt. The entry point {@link #processBook(UUID)} is idempotent:
 * it derives the first pending step from the persisted state and skips
 * everything that is already done, which makes it both the "start" and
 * the "resume from last checkpoint" operation.
 */
@Service
public class BookPipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(BookPipelineOrchestrator.class);

    private final BookRepository bookRepository;
    private final ChunksRepository chunksRepository;
    private final BlockRepository blockRepository;
    private final BookProcessingService bookProcessingService;
    private final AudioGeneratorService audioGeneratorService;
    private final ProcessingTraceService traceService;

    @Value("${app.processing.max-attempts:3}")
    private int maxAttempts;

    /**
     * In-memory re-entrancy guard: while a book is being processed by this
     * instance, a new processBook call for the same book is rejected.
     */
    private final Set<UUID> runningBooks = ConcurrentHashMap.newKeySet();

    /**
     * PAUSAS solicitadas desde el panel: la corrida en marcha termina el
     * paso actual por completo y se detiene en el siguiente checkpoint,
     * dejando el libro en AWAITING. Peticiones huérfanas (carrera contra
     * una corrida que ya terminó) se limpian en el finally de processBook.
     */
    private final Set<UUID> pauseRequests = ConcurrentHashMap.newKeySet();

    /**
     * STOPS solicitados desde el panel: la corrida en marcha se cancela
     * (chunks en vuelo interrumpidos), el paso en curso queda CANCELLED en
     * la traza y el libro queda STOPPED. Prevalece sobre una pausa.
     */
    private final Set<UUID> stopRequests = ConcurrentHashMap.newKeySet();

    public BookPipelineOrchestrator(BookRepository bookRepository,
                                    ChunksRepository chunksRepository,
                                    BlockRepository blockRepository,
                                    BookProcessingService bookProcessingService,
                                    AudioGeneratorService audioGeneratorService,
                                    ProcessingTraceService traceService) {
        this.bookRepository = bookRepository;
        this.chunksRepository = chunksRepository;
        this.blockRepository = blockRepository;
        this.bookProcessingService = bookProcessingService;
        this.audioGeneratorService = audioGeneratorService;
        this.traceService = traceService;
    }

    /**
     * Starts or resumes the pipeline of a book from its last checkpoint,
     * running every remaining step (up to FINALIZE). Asynchronous: returns
     * immediately after validation.
     *
     * @throws IllegalArgumentException if the book does not exist
     * @throws IllegalStateException if the book is already being processed
     */
    @Async("bookProcessingExecutor")
    public CompletableFuture<Void> processBook(UUID bookId) {
        return processBook(bookId, ProcessStep.FINALIZE, null, false);
    }

    /**
     * Same as {@link #processBook(UUID)} but stops after the target step
     * completes (checkpoint semantics). Used by the novels manager to run
     * TRANSLATE and AUDIO as independent, user-triggered stages.
     *
     * @param targetStep last pipeline step to execute
     * @throws IllegalArgumentException if the book does not exist
     * @throws IllegalStateException if the book is already being processed
     */
    @Async("bookProcessingExecutor")
    public CompletableFuture<Void> processBook(UUID bookId, ProcessStep targetStep) {
        return processBook(bookId, targetStep, null, true);
    }

    /**
     * Same as {@link #processBook(UUID, ProcessStep)} but restricts the
     * SPLIT step to the selected semantic blocks (Prólogo/Capítulo/Epílogo):
     * the chunks are always regenerated from the blocks' content, so the
     * translation only covers those blocks. A null/empty selection keeps
     * the regular behavior (all pending chunks of the book).
     *
     * @param targetStep last pipeline step to execute
     * @param blockIds   blocks that define the translation scope; null or
     *                   empty means the whole book
     * @throws IllegalArgumentException if the book does not exist
     * @throws IllegalStateException if the book is already being processed
     */
    @Async("bookProcessingExecutor")
    public CompletableFuture<Void> processBook(UUID bookId, ProcessStep targetStep, List<UUID> blockIds) {
        return processBook(bookId, targetStep, blockIds, true);
    }

    /**
     * Full entry point. The retry budgets exist to stop automatic-resume
     * loops of a failing book, not to veto explicit user intent: manual
     * runs (UI buttons, novels manager) proceed past the guards with a
     * warning, while automatic runs (startup recovery) are aborted.
     *
     * @param targetStep last pipeline step to execute
     * @param blockIds   blocks that define the translation scope; null or
     *                   empty means the whole book
     * @param manual     true for user-triggered runs (guards warn and
     *                   proceed), false for automatic resume (guards abort)
     * @throws IllegalArgumentException if the book does not exist
     * @throws IllegalStateException if the book is already being processed
     */
    @Async("bookProcessingExecutor")
    public CompletableFuture<Void> processBook(UUID bookId, ProcessStep targetStep, List<UUID> blockIds, boolean manual) {
        if (!runningBooks.add(bookId)) {
            // Rejected before the try block: the finally clause must not
            // release the lock held by the other run.
            log.warn("Book {} is already being processed; ignoring this request", bookId);
            throw new IllegalStateException("Book " + bookId + " is already being processed");
        }
        try {
            // Light projection (no full_text CLOB) for the entry guards;
            // the PROCESSING transition is a bulk update.
            BookRepository.BookStatusView state = bookRepository.findStatusViews(List.of(bookId)).stream()
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Book " + bookId + " not found"));

            if (state.getStatus() == ProcessingStatus.COMPLETED) {
                // Excepción deliberada: la RE-GENERACIÓN manual del audiolibro
                // de un libro completado es una acción legítima (nueva voz,
                // audios borrados). Solo la etapa AUDIO explícita pasa; el
                // auto-resume y cualquier otro objetivo siguen sin tocar el
                // libro completado.
                boolean audioRegeneration = manual && targetStep == ProcessStep.AUDIO;
                if (!audioRegeneration) {
                    log.info("Book {} is already completed; nothing to do", bookId);
                    return CompletableFuture.completedFuture(null);
                }
                log.info("Book {} is completed: re-generating its audiobook (manual AUDIO stage)", bookId);
            }
            if (state.getStatus() == ProcessingStatus.STOPPED) {
                log.info("Book {} is stopped; resume ignored", bookId);
                return CompletableFuture.completedFuture(null);
            }

            boolean wasCompleted = state.getStatus() == ProcessingStatus.COMPLETED;
            String traceId = UUID.randomUUID().toString().replace("-", "");
            bookRepository.updateStatusAndTrace(bookId, ProcessingStatus.PROCESSING, traceId);

            runPipeline(bookId, traceId, targetStep, blockIds, manual);
            if (pauseRequests.contains(bookId)) {
                // La corrida pausó a mitad del paso AUDIO (la petición no la
                // consumió el paso): el libro queda AWAITING — visible en el
                // panel con Arrancar para reanudar — incluso si antes estaba
                // COMPLETED (re-generación de audiolibro pausada). Los mp3 ya
                // generados se conservan y se saltan al reanudar.
                pauseRequests.remove(bookId);
                bookRepository.updateStatusWhen(bookId, ProcessingStatus.PROCESSING,
                        ProcessingStatus.AWAITING);
            } else if (wasCompleted) {
                // Un libro completado que re-generó su audio vuelve a quedar
                // COMPLETED (update guardado: la corrida lo puso PROCESSING).
                bookRepository.updateStatusWhen(bookId, ProcessingStatus.PROCESSING, ProcessingStatus.COMPLETED);
            } else {
                markAwaitingIfStoppedEarly(bookId, targetStep);
            }
            return CompletableFuture.completedFuture(null);
        } catch (PipelineStoppedException e) {
            // Stop explícito del usuario: el estado STOPPED ya lo persistió
            // requestStop — ni FAILED ni AWAITING deben sobreescribirlo.
            log.info("{}", e.getMessage());
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException e) {
            log.error("Pipeline run aborted for book {}", bookId, e);
            throw e;
        } finally {
            runningBooks.remove(bookId);
            pauseRequests.remove(bookId);
            stopRequests.remove(bookId);
            ProcessingTraceService.clearMdc();
        }
    }

    public boolean isRunning(UUID bookId) {
        return runningBooks.contains(bookId);
    }

    /** Snapshot de los libros que esta instancia está procesando ahora. */
    public Set<UUID> getRunningBookIds() {
        return Set.copyOf(runningBooks);
    }

    /**
     * PAUSA (panel): la corrida en marcha termina el paso actual por
     * completo — los chunks en vuelo siguen hasta el final — y se detiene
     * en el siguiente checkpoint, dejando el libro en AWAITING. Solo tiene
     * sentido con una corrida activa de esta instancia.
     *
     * @return true si la petición quedó registrada; false si el libro no
     *         se está procesando ahora (el endpoint responde 409).
     */
    public boolean requestPause(UUID bookId) {
        if (!runningBooks.contains(bookId)) {
            return false;
        }
        pauseRequests.add(bookId);
        log.info("Pausa solicitada para el libro {}: se detendrá al terminar el paso en curso", bookId);
        return true;
    }

    /**
     * STOP (panel): cancela el trabajo en vuelo del libro YA. Si hay una
     * corrida activa: interrumpe las traducciones de chunks en marcha
     * (cancelActiveTasks), marca el libro STOPPED con un update en bloque
     * (feedback inmediato en la UI) y la corrida colapsa de forma limpia
     * — el paso en curso queda CANCELLED en la traza, sin marcar el libro
     * FAILED ni consumir intentos. Si NO hay corrida (AWAITING/UPLOADED/
     * FAILED), simplemente lo deja en STOPPED para sacarlo de la rotación
     * del auto-resume.
     *
     * @return true si el libro quedó (o ya estaba siendo) detenido; false
     *         si ya estaba COMPLETED (no hay nada que detener).
     */
    public boolean requestStop(UUID bookId) {
        if (runningBooks.contains(bookId)) {
            stopRequests.add(bookId);
            int cancelledTasks = bookProcessingService.cancelActiveTasks(bookId);
            bookRepository.updateStatusWhen(bookId, ProcessingStatus.PROCESSING, ProcessingStatus.STOPPED);
            log.info("Stop solicitado para el libro {} en marcha: {} tarea(s) de traducción canceladas",
                    bookId, cancelledTasks);
            return true;
        }
        boolean stopped = bookRepository.updateStatusIfNot(bookId, ProcessingStatus.COMPLETED,
                ProcessingStatus.STOPPED) > 0;
        if (stopped) {
            log.info("Libro {} (sin corrida activa) marcado como STOPPED desde el panel", bookId);
        }
        return stopped;
    }

    /**
     * Control-flow: la corrida fue interrumpida por un stop explícito del
     * usuario. NO es un fallo — processBook la reconoce y termina en
     * silencio, conservando el estado STOPPED que el propio stop persistió.
     */
    private static final class PipelineStoppedException extends RuntimeException {
        private PipelineStoppedException(UUID bookId) {
            super("Pipeline run of book " + bookId + " stopped by user request");
        }
    }

    private void runPipeline(UUID bookId, String traceId, ProcessStep targetStep, List<UUID> blockIds, boolean manual) {
        runExtractStep(bookId, traceId);
        if (userStopRequested(bookId)) {
            throw new PipelineStoppedException(bookId);
        }
        if (userPauseRequested(bookId)) {
            return;
        }
        runSplitStep(bookId, traceId, blockIds, manual);
        if (targetStep == ProcessStep.SPLIT) {
            return;
        }
        if (userStopRequested(bookId)) {
            throw new PipelineStoppedException(bookId);
        }
        if (userPauseRequested(bookId)) {
            return;
        }
        runTranslateStep(bookId, traceId, manual);
        if (targetStep == ProcessStep.TRANSLATE) {
            return;
        }
        if (userStopRequested(bookId)) {
            throw new PipelineStoppedException(bookId);
        }
        if (userPauseRequested(bookId)) {
            return;
        }
        runAudioStep(bookId, traceId, manual);
        if (targetStep == ProcessStep.AUDIO) {
            return;
        }
        if (userStopRequested(bookId)) {
            throw new PipelineStoppedException(bookId);
        }
        if (userPauseRequested(bookId)) {
            return;
        }
        runFinalizeStep(bookId, traceId, manual);
    }

    /**
     * Stop pendiente del usuario en un checkpoint: la corrida se corta
     * aquí; el estado STOPPED ya lo persistió requestStop.
     */
    private boolean userStopRequested(UUID bookId) {
        return stopRequests.contains(bookId);
    }

    /**
     * PAUSA pendiente del usuario en un checkpoint: el paso en curso ya
     * terminó por completo, así que el libro pasa a AWAITING (update
     * guardado: si la corrida no estaba en PROCESSING es un no-op) y la
     * corrida termina de forma limpia — sin FAILED, sin intentos gastados.
     */
    private boolean userPauseRequested(UUID bookId) {
        if (!pauseRequests.contains(bookId)) {
            return false;
        }
        pauseRequests.remove(bookId);
        bookRepository.updateStatusWhen(bookId, ProcessingStatus.PROCESSING, ProcessingStatus.AWAITING);
        log.info("Corrida del libro {} pausada en el checkpoint: el libro queda AWAITING", bookId);
        return true;
    }

    /**
     * When the run stopped at a checkpoint (targetStep before FINALIZE), the
     * book must not stay PROCESSING: mark it AWAITING so the UI reflects that
     * it is waiting for the user to trigger the next stage. Bulk update:
     * the book entity (and its CLOB) is not loaded for the transition.
     */
    private void markAwaitingIfStoppedEarly(UUID bookId, ProcessStep targetStep) {
        if (targetStep == ProcessStep.FINALIZE) {
            return;
        }
        bookRepository.updateStatusWhen(bookId, ProcessingStatus.PROCESSING, ProcessingStatus.AWAITING);
    }

    /**
     * EXTRACT cannot be re-executed (the uploaded file is not persisted),
     * so it only validates that the extracted text exists. The check runs
     * in SQL (LENGTH(TRIM(full_text))) so the multi-megabyte CLOB is
     * never loaded just to test for blankness.
     */
    private void runExtractStep(UUID bookId, String traceId) {
        if (!bookRepository.hasExtractedText(bookId)) {
            ProcessTraceEvent event = traceService.startStep(bookId, ProcessStep.EXTRACT, traceId, null);
            failAndAbort(event, new IllegalStateException(
                    "The book has no extracted text. Upload the file again: the original file is not persisted."));
        }
        traceService.skipStep(bookId, ProcessStep.EXTRACT, traceId, "Text already extracted at upload");
    }

    private void runSplitStep(UUID bookId, String traceId, List<UUID> blockIds, boolean manual) {
        // Block-scoped split: the chunks are ALWAYS regenerated from the
        // selected blocks' content, replacing any previous chunks so the
        // translation covers only the selected scope.
        if (blockIds != null && !blockIds.isEmpty()) {
            ProcessTraceEvent event = traceService.startStep(bookId, ProcessStep.SPLIT, traceId,
                    blockIds.size() + " selected block(s)");
            checkStepAttempts(event, manual);
            try {
                bookProcessingService.splitBookIntoChunks(bookId, blockIds);
                long count = chunksRepository.countByBookId(bookId);
                traceService.completeStep(event, count + " chunks created from " + blockIds.size() + " block(s)");
            } catch (RuntimeException e) {
                failAndAbort(event, e);
            }
            return;
        }

        if (chunksRepository.countByBookId(bookId) > 0) {
            traceService.skipStep(bookId, ProcessStep.SPLIT, traceId, "Chunks already exist");
            return;
        }
        ProcessTraceEvent event = traceService.startStep(bookId, ProcessStep.SPLIT, traceId, null);
        checkStepAttempts(event, manual);
        try {
            // Sin selección explícita: si el libro tiene bloques se trocea
            // por bloque (todos); sin bloques, texto completo (legado).
            bookProcessingService.splitBookIntoChunks(bookId, null);
            long count = chunksRepository.countByBookId(bookId);
            traceService.completeStep(event, count + " chunks created");
        } catch (RuntimeException e) {
            failAndAbort(event, e);
        }
    }

    /**
     * Translates every chunk that still needs work: AWAITING, FAILED, or
     * PROCESSING left over from a crashed run (stale). While this instance
     * holds the per-book run lock, a PROCESSING chunk can only be stale.
     *
     * <p>Attempt accounting counts only GENUINE failures: a run interrupted
     * by a crash or restart does not consume the retry budget, and a
     * chunk's attempts only grow when its translation actually fails.
     *
     * <p>The step works on a light (id, status, attempts) projection and
     * bulk updates: no chunk text is loaded unless it is dispatched for
     * translation (the translation service re-reads each chunk by id).</p>
     */
    private void runTranslateStep(UUID bookId, String traceId, boolean manual) {
        List<ChunkStatusView> chunks = chunksRepository.findStatusViewsByBookId(bookId);

        if (chunks.isEmpty()) {
            ProcessTraceEvent event = traceService.startStep(bookId, ProcessStep.TRANSLATE, traceId, null);
            failAndAbort(event, new IllegalStateException("No chunks found for book " + bookId
                    + "; the SPLIT step did not produce any chunk."));
        }

        List<ChunkStatusView> pending = chunks.stream()
                .filter(c -> c.getStatus() == ChunkStatus.AWAITING
                        || c.getStatus() == ChunkStatus.FAILED
                        || c.getStatus() == ChunkStatus.PROCESSING)
                .toList();

        if (pending.isEmpty()) {
            traceService.skipStep(bookId, ProcessStep.TRANSLATE, traceId,
                    "All " + chunks.size() + " chunks already translated");
            return;
        }

        int genuineFailures = traceService.countGenuineAttempts(bookId, ProcessStep.TRANSLATE);

        ProcessTraceEvent event = traceService.startStep(bookId, ProcessStep.TRANSLATE, traceId,
                pending.size() + "/" + chunks.size() + " chunks pending");
        checkStepAttempts(event, manual);

        // Heal inflated legacy counters (only ever reduces) via one bulk
        // update; the effective attempts are then min(attempts, genuine).
        chunksRepository.healAttempts(bookId, PENDING_STATUSES, genuineFailures);
        List<ChunkStatusView> exhausted = pending.stream()
                .filter(c -> effectiveAttempts(c, genuineFailures) >= maxAttempts)
                .toList();
        if (!exhausted.isEmpty()) {
            if (manual) {
                log.warn("{} chunk(s) exhausted the maximum of {} translation attempts (e.g. chunk {}); "
                        + "manual run proceeds anyway", exhausted.size(), maxAttempts, exhausted.get(0).getId());
            } else {
                failAndAbort(event, new IllegalStateException(exhausted.size()
                        + " chunk(s) exhausted the maximum of " + maxAttempts + " translation attempts (e.g. chunk "
                        + exhausted.get(0).getId() + ")"));
            }
        }

        // Re-queue stale statuses for the async pass in one bulk update;
        // attempts are NOT consumed upfront anymore (genuine failures only).
        chunksRepository.requeueByStatus(bookId,
                List.of(ChunkStatus.FAILED, ChunkStatus.PROCESSING), ChunkStatus.AWAITING);

        try {
            bookProcessingService.processBookChunksAsync(bookId).join();
        } catch (RuntimeException e) {
            if (stopRequests.contains(bookId)) {
                // Stop explícito del panel: los chunks en vuelo fueron
                // interrumpidos por requestStop — NO son fallos genuinos,
                // no consumen intentos y el libro ya quedó STOPPED. El
                // paso cierra como CANCELLED en la traza y la corrida
                // termina en silencio.
                traceService.cancelStep(event,
                        "Pipeline detenido desde el panel durante la traducción "
                                + "(chunks en vuelo cancelados; se re-encolan en la próxima corrida)");
                throw new PipelineStoppedException(bookId);
            }
            // Chunks are translated OUTSIDE any transaction (each save is
            // its own short transaction), so every chunk that is not
            // COMPLETED genuinely failed in this run: record one attempt
            // for each of them (single bulk update + id scan for the
            // trace detail).
            int failedCount = chunksRepository.incrementFailedAttempts(bookId, ChunkStatus.COMPLETED);
            String detail = failedCount > 0
                    ? failedCount + " chunks failed to translate (e.g. chunk "
                            + chunksRepository.findIdsByBookIdAndStatusNot(bookId, ChunkStatus.COMPLETED)
                            .stream().findFirst().orElse(null) + ")"
                            + (rootCauseMessage(e).map(cause -> ": " + cause).orElse(""))
                    : e.getMessage();
            failAndAbort(event, new IllegalStateException(detail != null ? detail : e.toString(), e));
        }

        traceService.completeStep(event, "All chunks translated");
    }

    private static final List<ChunkStatus> PENDING_STATUSES =
            List.of(ChunkStatus.AWAITING, ChunkStatus.FAILED, ChunkStatus.PROCESSING);

    /** Post-heal attempt count: the heal bulk update clamps to genuineFailures. */
    private static int effectiveAttempts(ChunkStatusView chunk, int genuineFailures) {
        return Math.min(chunk.getAttempts() != null ? chunk.getAttempts() : 0, genuineFailures);
    }

    /** Deepest cause message of an exception chain, for the trace detail. */
    private static java.util.Optional<String> rootCauseMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? java.util.Optional.empty() : java.util.Optional.of(message);
    }

    /**
     * AUDIO genera un archivo por bloque semántico (Prólogo, Capítulo N,
     * Epílogo), no un único audio del libro: cada bloque se arma con las
     * traducciones de SUS chunks (enlazados al bloque en el split) y
     * produce su propio mp3 dentro del directorio de audiolibro del libro.
     * Cuando todos los bloques tienen audio, el libro apunta al directorio.
     *
     * <p>Los libros sin bloques mantienen la ruta legada: un único mp3 con
     * todo el texto traducido.</p>
     *
     * <p>Rendimiento: el paso opera sobre vistas ligeras — el libro se lee
     * SIN su full_text CLOB (multi-MB), los bloques SIN su content CLOB y
     * los chunks SIN su original_text — y la ruta de audio se persiste con
     * un update en bloque, sin re-flushear la entidad gestionada.</p>
     */
    private void runAudioStep(UUID bookId, String traceId, boolean manual) {
        BookRepository.BookAudioView book = bookRepository.findAudioViewById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("Book " + bookId + " not found"));

        List<BlockRepository.BlockAudioView> blocks = blockRepository.findAudioViewsByBookId(bookId);

        if (!blocks.isEmpty()) {
            runPerBlockAudioStep(bookId, book.getName(), blocks, traceId, manual);
            return;
        }

        if (book.getAudioFilePath() != null && Files.exists(Path.of(book.getAudioFilePath()))) {
            traceService.skipStep(bookId, ProcessStep.AUDIO, traceId,
                    "Audio already generated at " + book.getAudioFilePath());
            return;
        }

        ProcessTraceEvent event = traceService.startStep(bookId, ProcessStep.AUDIO, traceId, null);
        checkStepAttempts(event, manual);

        try {
            String fullTranslatedText = chunksRepository.findTranslatedTextByBookId(bookId).stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("\n\n"));

            Path outputPath = audioPathFor(book.getName());
            boolean ok = audioGeneratorService.processTextToAudio(fullTranslatedText, outputPath);
            if (!ok) {
                throw new IllegalStateException(
                        "Audio generation failed for book " + bookId);
            }
            bookRepository.updateAudioPathInBulk(bookId, outputPath.toString());
            traceService.completeStep(event, "Audio generated at " + outputPath);
        } catch (RuntimeException e) {
            failAndAbort(event, e);
        }
    }

    /**
     * Audio por bloque: un mp3 por cada bloque con chunks traducidos, en
     * orden de lectura. Los bloques con audio ya presente en disco se
     * saltan; el libro se considera con audio completo cuando todos sus
     * bloques tienen archivo. Los chunks de TODOS los bloques se leen en
     * una sola consulta proyectada (solo translated_text, sin entidades
     * ni original_text), y las rutas de audio se persisten con updates
     * en bloque (nunca re-flush de la entidad con su CLOB).
     */
    private void runPerBlockAudioStep(UUID bookId, String bookName,
                                      List<BlockRepository.BlockAudioView> blocks,
                                      String traceId, boolean manual) {
        Map<UUID, List<ChunksRepository.BlockChunkAudioView>> chunksByBlock =
                chunksRepository.findBlockAudioViewsByBookId(bookId).stream()
                        .collect(Collectors.groupingBy(ChunksRepository.BlockChunkAudioView::getBlockId));

        long pending = blocks.stream()
                .filter(block -> !blockHasAudioOnDisk(block))
                .filter(block -> !chunksByBlock.getOrDefault(block.getId(), List.of()).isEmpty())
                .count();
        long allPresent = blocks.stream().filter(this::blockHasAudioOnDisk).count();
        if (pending == 0 && allPresent == blocks.size()) {
            traceService.skipStep(bookId, ProcessStep.AUDIO, traceId,
                    "All " + blocks.size() + " block audios already generated");
            return;
        }

        ProcessTraceEvent event = traceService.startStep(bookId, ProcessStep.AUDIO, traceId,
                blocks.size() + " blocks, " + (blocks.size() - allPresent) + " pending audio");
        checkStepAttempts(event, manual);

        try {
            Path audioDir = blockAudioDirectory(bookName);
            try {
                Files.createDirectories(audioDir);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            int generated = 0;
            // Bloques generados en ESTA corrida: las vistas proyectadas son
            // inmutables (no reflejan los updates en bloque), así que el
            // "todos con audio" del cierre se calcula con este conjunto.
            Set<UUID> generatedIds = new java.util.HashSet<>();
            List<String> skipped = new ArrayList<>();
            for (int i = 0; i < blocks.size(); i++) {
                // Granularidad de BLOQUE para los controles del panel: una
                // pausa o un stop durante el audiolibro toman efecto al
                // terminar el bloque en curso (no al terminar TODO el
                // paso — un libro completo puede tardar la hora).
                if (stopRequests.contains(bookId)) {
                    traceService.cancelStep(event,
                            "Pipeline detenido desde el panel durante el audio (bloques ya generados: "
                                    + generatedIds.size() + "; los mp3 en disco se conservan)");
                    throw new PipelineStoppedException(bookId);
                }
                if (pauseRequests.contains(bookId)) {
                    // La petición NO se consume aquí: la consume el cierre de
                    // processBook (marcando AWAITING — incluso si el libro
                    // estaba COMPLETED por una re-generación pausada) o el
                    // checkpoint de runPipeline en las corridas completas.
                    // Reanudar salta los bloques que ya tienen su mp3.
                    traceService.cancelStep(event,
                            "Pausado desde el panel durante el audio (bloques ya generados: "
                                    + generatedIds.size() + "; los mp3 en disco se conservan)");
                    return;
                }
                BlockRepository.BlockAudioView block = blocks.get(i);
                List<ChunksRepository.BlockChunkAudioView> blockChunks =
                        chunksByBlock.getOrDefault(block.getId(), List.of());
                if (blockChunks.isEmpty()) {
                    skipped.add(blockLabel(block) + " (sin chunks)");
                    continue;
                }
                if (blockHasAudioOnDisk(block)) {
                    skipped.add(blockLabel(block) + " (ya generado)");
                    continue;
                }
                List<ChunksRepository.BlockChunkAudioView> untranslated = blockChunks.stream()
                        .filter(chunk -> chunk.getStatus() != ChunkStatus.COMPLETED)
                        .toList();
                if (!untranslated.isEmpty()) {
                    throw new IllegalStateException("Block " + blockLabel(block)
                            + " has " + untranslated.size() + " untranslated chunk(s); translate before generating audio");
                }

                String translatedText = blockChunks.stream()
                        .map(ChunksRepository.BlockChunkAudioView::getTranslatedText)
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining("\n\n"));

                Path outputPath = audioDir.resolve(blockAudioFileName(i + 1, block));
                boolean ok = audioGeneratorService.processTextToAudio(translatedText, outputPath);
                if (!ok) {
                    throw new IllegalStateException("Audio generation failed for block " + blockLabel(block));
                }
                blockRepository.updateAudioPathInBulk(block.getId(), outputPath.toString());
                generatedIds.add(block.getId());
                generated++;
            }

            boolean allDone = blocks.stream()
                    .allMatch(block -> blockHasAudioOnDisk(block) || generatedIds.contains(block.getId()));
            if (allDone) {
                bookRepository.updateAudioPathInBulk(bookId, audioDir.toString());
            }
            traceService.completeStep(event, generated + " block audio(s) generated in " + audioDir
                    + (skipped.isEmpty() ? "" : "; skipped: " + String.join(", ", skipped)));
        } catch (PipelineStoppedException e) {
            // Stop explícito del panel a mitad del audio: el estado STOPPED ya
            // lo persistió requestStop — el catch de processBook termina la
            // corrida en silencio; NUNCA debe caer en failAndAbort.
            throw e;
        } catch (RuntimeException e) {
            failAndAbort(event, e);
        }
    }

    private boolean blockHasAudioOnDisk(BlockRepository.BlockAudioView block) {
        return block.getAudioFilePath() != null && Files.exists(Path.of(block.getAudioFilePath()));
    }

    /** Etiqueta legible en español del bloque para archivos de audio. */
    private String blockLabel(BlockRepository.BlockAudioView block) {
        return switch (block.getBlockType()) {
            case PROLOGUE -> "Prologo";
            case CHAPTER -> "Capitulo " + String.format("%02d", block.getBlockNumber() != null
                    ? block.getBlockNumber() : block.getPosition());
            case EPILOGUE -> "Epilogo";
        };
    }

    /** Directorio del audiolibro del libro: "<base> - audiolibro". */
    static Path blockAudioDirectory(String bookName) {
        return Path.of(bookBaseName(bookName) + " - audiolibro");
    }

    /** Archivo de audio de un bloque: "<orden> - <etiqueta>.mp3". */
    private String blockAudioFileName(int order, BlockRepository.BlockAudioView block) {
        return String.format("%02d - %s.mp3", order, blockLabel(block));
    }

    /** Nombre base del libro sin extensión. */
    static String bookBaseName(String name) {
        String resolved = name != null ? name : "book";
        int dot = resolved.lastIndexOf('.');
        int slash = Math.max(resolved.lastIndexOf('/'), resolved.lastIndexOf('\\'));
        return (dot > slash) ? resolved.substring(0, dot) : resolved;
    }

    private void runFinalizeStep(UUID bookId, String traceId, boolean manual) {
        ProcessTraceEvent event = traceService.startStep(bookId, ProcessStep.FINALIZE, traceId, null);
        checkStepAttempts(event, manual);
        try {
            // Bulk status transition: the book entity (and its CLOB) is
            // not loaded to flip the status. The run set PROCESSING at
            // entry, so the guarded update only skips truly concurrent
            // status changes.
            bookRepository.updateStatusWhen(bookId, ProcessingStatus.PROCESSING, ProcessingStatus.COMPLETED);
            traceService.completeStep(event, "Book processing completed");
        } catch (RuntimeException e) {
            failAndAbort(event, e);
        }
    }

    /**
     * Aborts the run when a step has exceeded the configured maximum number
     * of attempts (guards against infinite resume loops). Manual runs are
     * explicit user intent: they proceed past the budget with a warning —
     * their events carry attempt > max, which never counts toward the
     * budget, so a manual failure stays non-counting while automatic
     * resume remains protected.
     */
    private void checkStepAttempts(ProcessTraceEvent event, boolean manual) {
        if (event.getAttempt() > maxAttempts) {
            if (manual) {
                log.warn("Step {} budget exceeded ({} of {} attempts); manual run proceeds anyway",
                        event.getStep(), event.getAttempt() - 1, maxAttempts);
                return;
            }
            failAndAbort(event, new IllegalStateException("Step " + event.getStep()
                    + " exceeded the maximum of " + maxAttempts + " attempts"));
        }
    }

    /**
     * Marks the current step event as FAILED, marks the book as FAILED
     * keeping its checkpoint (currentStep) intact, and stops the run.
     */
    private void failAndAbort(ProcessTraceEvent event, RuntimeException cause) {
        traceService.failStep(event, cause);
        markBookFailed(event.getBook().getId(), event.getTraceId(), cause);
        throw cause;
    }

    private void failAndAbortNoEvent(RuntimeException cause) {
        throw cause;
    }

    private void markBookFailed(UUID bookId, String traceId, Exception cause) {
        try {
            // Bulk update: no book entity (and no CLOB) is loaded for the
            // failure marking.
            bookRepository.updateStatusAndTrace(bookId, ProcessingStatus.FAILED, traceId);
        } catch (Exception e) {
            log.error("Could not persist FAILED status for book {}", bookId, e);
        }
    }

    /**
     * Derives the audio output path from the book name, forcing a .mp3
     * extension so ffmpeg can pick the output muxer.
     */
    static Path audioPathFor(String bookName) {
        return Path.of(bookBaseName(bookName) + ".mp3");
    }
}
