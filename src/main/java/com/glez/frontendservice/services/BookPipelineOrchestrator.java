package com.glez.frontendservice.services;

import com.glez.frontendservice.model.Book;
import com.glez.frontendservice.model.Chunks;
import com.glez.frontendservice.model.ChunkStatus;
import com.glez.frontendservice.model.ProcessStep;
import com.glez.frontendservice.model.ProcessTraceEvent;
import com.glez.frontendservice.model.ProcessingStatus;
import com.glez.frontendservice.repository.BookRepository;
import com.glez.frontendservice.repository.ChunksRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
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

    public BookPipelineOrchestrator(BookRepository bookRepository,
                                    ChunksRepository chunksRepository,
                                    BookProcessingService bookProcessingService,
                                    AudioGeneratorService audioGeneratorService,
                                    ProcessingTraceService traceService) {
        this.bookRepository = bookRepository;
        this.chunksRepository = chunksRepository;
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
        return processBook(bookId, ProcessStep.FINALIZE);
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
        if (!runningBooks.add(bookId)) {
            // Rejected before the try block: the finally clause must not
            // release the lock held by the other run.
            log.warn("Book {} is already being processed; ignoring this request", bookId);
            throw new IllegalStateException("Book " + bookId + " is already being processed");
        }
        try {
            Book book = bookRepository.findById(bookId)
                    .orElseThrow(() -> new IllegalArgumentException("Book " + bookId + " not found"));

            if (book.getStatus() == ProcessingStatus.COMPLETED) {
                log.info("Book {} is already completed; nothing to do", bookId);
                return CompletableFuture.completedFuture(null);
            }
            if (book.getStatus() == ProcessingStatus.STOPPED) {
                log.info("Book {} is stopped; resume ignored", bookId);
                return CompletableFuture.completedFuture(null);
            }

            String traceId = UUID.randomUUID().toString().replace("-", "");
            book.setStatus(ProcessingStatus.PROCESSING);
            book.setLastTraceId(traceId);
            bookRepository.save(book);

            runPipeline(bookId, traceId, targetStep);
            markAwaitingIfStoppedEarly(bookId, targetStep);
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException e) {
            log.error("Pipeline run aborted for book {}", bookId, e);
            throw e;
        } finally {
            runningBooks.remove(bookId);
            ProcessingTraceService.clearMdc();
        }
    }

    public boolean isRunning(UUID bookId) {
        return runningBooks.contains(bookId);
    }

    private void runPipeline(UUID bookId, String traceId, ProcessStep targetStep) {
        runExtractStep(bookId, traceId);
        runSplitStep(bookId, traceId);
        if (targetStep == ProcessStep.SPLIT) {
            return;
        }
        runTranslateStep(bookId, traceId);
        if (targetStep == ProcessStep.TRANSLATE) {
            return;
        }
        runAudioStep(bookId, traceId);
        if (targetStep == ProcessStep.AUDIO) {
            return;
        }
        runFinalizeStep(bookId, traceId);
    }

    /**
     * When the run stopped at a checkpoint (targetStep before FINALIZE), the
     * book must not stay PROCESSING: mark it AWAITING so the UI reflects that
     * it is waiting for the user to trigger the next stage.
     */
    private void markAwaitingIfStoppedEarly(UUID bookId, ProcessStep targetStep) {
        if (targetStep == ProcessStep.FINALIZE) {
            return;
        }
        bookRepository.findById(bookId).ifPresent(book -> {
            if (book.getStatus() == ProcessingStatus.PROCESSING) {
                book.setStatus(ProcessingStatus.AWAITING);
                bookRepository.save(book);
            }
        });
    }

    /**
     * EXTRACT cannot be re-executed (the uploaded file is not persisted),
     * so it only validates that the extracted text exists.
     */
    private void runExtractStep(UUID bookId, String traceId) {
        Book book = bookRepository.findById(bookId).orElseThrow();
        if (book.getFullText() == null || book.getFullText().isBlank()) {
            ProcessTraceEvent event = traceService.startStep(bookId, ProcessStep.EXTRACT, traceId, null);
            failAndAbort(event, new IllegalStateException(
                    "The book has no extracted text. Upload the file again: the original file is not persisted."));
        }
        traceService.skipStep(bookId, ProcessStep.EXTRACT, traceId, "Text already extracted at upload");
    }

    private void runSplitStep(UUID bookId, String traceId) {
        if (!chunksRepository.findByBook(loadBook(bookId)).isEmpty()) {
            traceService.skipStep(bookId, ProcessStep.SPLIT, traceId, "Chunks already exist");
            return;
        }
        ProcessTraceEvent event = traceService.startStep(bookId, ProcessStep.SPLIT, traceId, null);
        checkStepAttempts(event);
        try {
            bookProcessingService.splitBookIntoChunks(bookId);
            int count = chunksRepository.findByBook(loadBook(bookId)).size();
            traceService.completeStep(event, count + " chunks created");
        } catch (RuntimeException e) {
            failAndAbort(event, e);
        }
    }

    /**
     * Translates every chunk that still needs work: AWAITING, FAILED, or
     * PROCESSING left over from a crashed run (stale). While this instance
     * holds the per-book run lock, a PROCESSING chunk can only be stale.
     */
    private void runTranslateStep(UUID bookId, String traceId) {
        Book book = loadBook(bookId);
        List<Chunks> chunks = chunksRepository.findByBook(book);

        if (chunks.isEmpty()) {
            ProcessTraceEvent event = traceService.startStep(bookId, ProcessStep.TRANSLATE, traceId, null);
            failAndAbort(event, new IllegalStateException("No chunks found for book " + bookId
                    + "; the SPLIT step did not produce any chunk."));
        }

        List<Chunks> pending = chunks.stream()
                .filter(c -> c.getStatus() == ChunkStatus.AWAITING
                        || c.getStatus() == ChunkStatus.FAILED
                        || c.getStatus() == ChunkStatus.PROCESSING)
                .toList();

        if (pending.isEmpty()) {
            traceService.skipStep(bookId, ProcessStep.TRANSLATE, traceId,
                    "All " + chunks.size() + " chunks already translated");
            return;
        }

        ProcessTraceEvent event = traceService.startStep(bookId, ProcessStep.TRANSLATE, traceId,
                pending.size() + "/" + chunks.size() + " chunks pending");
        checkStepAttempts(event);

        for (Chunks chunk : pending) {
            if (chunk.getAttempts() >= maxAttempts) {
                failAndAbort(event, new IllegalStateException("Chunk " + chunk.getId()
                        + " exceeded the maximum of " + maxAttempts + " attempts"));
            }
            chunk.setAttempts(chunk.getAttempts() + 1);
            chunk.setStatus(ChunkStatus.AWAITING);
            chunksRepository.save(chunk);
        }

        try {
            bookProcessingService.processBookChunksAsync(bookId).join();
        } catch (RuntimeException e) {
            // The failure detail is enriched with the chunks persisted as FAILED.
            List<Chunks> failedAfterJoin = chunksRepository.findByBook(loadBook(bookId)).stream()
                    .filter(c -> c.getStatus() == ChunkStatus.FAILED)
                    .toList();
            String detail = e.getMessage();
            if (!failedAfterJoin.isEmpty()) {
                detail = failedAfterJoin.size() + " chunks failed to translate (e.g. chunk "
                        + failedAfterJoin.get(0).getId() + ")";
            }
            failAndAbort(event, new IllegalStateException(detail != null ? detail : e.toString(), e));
        }

        List<Chunks> failed = chunksRepository.findByBook(loadBook(bookId)).stream()
                .filter(c -> c.getStatus() == ChunkStatus.FAILED)
                .toList();
        if (!failed.isEmpty()) {
            failAndAbort(event, new IllegalStateException(failed.size()
                    + " chunks failed to translate (e.g. chunk " + failed.get(0).getId() + ")"));
        }
        traceService.completeStep(event, "All chunks translated");
    }

    /**
     * AUDIO regenerates the audio file only when it has not been produced
     * yet (no persisted path or the file disappeared from disk).
     */
    private void runAudioStep(UUID bookId, String traceId) {
        Book book = loadBook(bookId);
        if (book.getAudioFilePath() != null && Files.exists(Path.of(book.getAudioFilePath()))) {
            traceService.skipStep(bookId, ProcessStep.AUDIO, traceId,
                    "Audio already generated at " + book.getAudioFilePath());
            return;
        }

        ProcessTraceEvent event = traceService.startStep(bookId, ProcessStep.AUDIO, traceId, null);
        checkStepAttempts(event);

        try {
            String fullTranslatedText = chunksRepository.findByBook(book).stream()
                    .sorted(Comparator.comparingInt(Chunks::getPosition))
                    .map(Chunks::getTranslatedText)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("\n\n"));

            Path outputPath = audioPathFor(book);
            boolean ok = audioGeneratorService.processTextToAudio(fullTranslatedText, outputPath);
            if (!ok) {
                throw new IllegalStateException(
                        "Audio generation failed for book " + bookId);
            }
            book.setAudioFilePath(outputPath.toString());
            bookRepository.save(book);
            traceService.completeStep(event, "Audio generated at " + outputPath);
        } catch (RuntimeException e) {
            failAndAbort(event, e);
        }
    }

    private void runFinalizeStep(UUID bookId, String traceId) {
        ProcessTraceEvent event = traceService.startStep(bookId, ProcessStep.FINALIZE, traceId, null);
        checkStepAttempts(event);
        try {
            Book book = loadBook(bookId);
            book.setStatus(ProcessingStatus.COMPLETED);
            bookRepository.save(book);
            traceService.completeStep(event, "Book processing completed");
        } catch (RuntimeException e) {
            failAndAbort(event, e);
        }
    }

    /**
     * Aborts the run when a step has exceeded the configured maximum
     * number of attempts (guards against infinite resume loops).
     */
    private void checkStepAttempts(ProcessTraceEvent event) {
        if (event.getAttempt() > maxAttempts) {
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
            Book book = bookRepository.findById(bookId).orElse(null);
            if (book == null) {
                return;
            }
            book.setStatus(ProcessingStatus.FAILED);
            book.setLastTraceId(traceId);
            bookRepository.save(book);
        } catch (Exception e) {
            log.error("Could not persist FAILED status for book {}", bookId, e);
        }
    }

    /**
     * Derives the audio output path from the book name, forcing a .mp3
     * extension so ffmpeg can pick the output muxer.
     */
    static Path audioPathFor(Book book) {
        String name = book.getName() != null ? book.getName() : "book";
        int dot = name.lastIndexOf('.');
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String base = (dot > slash) ? name.substring(0, dot) : name;
        return Path.of(base + ".mp3");
    }

    private Book loadBook(UUID bookId) {
        return bookRepository.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("Book " + bookId + " not found"));
    }
}
