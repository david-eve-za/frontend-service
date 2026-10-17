package com.glez.frontendservice.services;

import com.glez.frontendservice.dtos.TraceEventDto;
import com.glez.frontendservice.model.Book;
import com.glez.frontendservice.model.ProcessStep;
import com.glez.frontendservice.model.ProcessTraceEvent;
import com.glez.frontendservice.model.TraceEventStatus;
import com.glez.frontendservice.repository.BookRepository;
import com.glez.frontendservice.repository.ProcessTraceEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Persists one trace event per pipeline step attempt and keeps the MDC
 * logging context (bookId, traceId, step) in sync, so every log line
 * produced while processing a book carries its correlation context.
 *
 * <p>All mutations run in independent transactions (REQUIRES_NEW) so the
 * trace survives rollbacks and crashes of the business transactions.
 */
@Service
public class ProcessingTraceService {

    public static final String MDC_BOOK_ID = "bookId";
    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_STEP = "step";

    private static final Logger log = LoggerFactory.getLogger(ProcessingTraceService.class);

    private final ProcessTraceEventRepository traceEventRepository;
    private final BookRepository bookRepository;

    @Value("${app.processing.max-attempts:3}")
    private int maxAttempts;

    public ProcessingTraceService(ProcessTraceEventRepository traceEventRepository,
                                  BookRepository bookRepository) {
        this.traceEventRepository = traceEventRepository;
        this.bookRepository = bookRepository;
    }

    /**
     * Records the beginning of a step attempt and points the book's
     * checkpoint (currentStep) at it. Neither the book entity nor its
     * full_text CLOB is loaded: the checkpoint moves with a bulk update
     * and the event keeps an uninitialized FK reference.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProcessTraceEvent startStep(UUID bookId, ProcessStep step, String traceId, String details) {
        bookRepository.updateCheckpoint(bookId, step, traceId);

        Book book = bookRepository.getReferenceById(bookId);

        ProcessTraceEvent event = new ProcessTraceEvent();
        event.setBook(book);
        event.setTraceId(traceId);
        event.setStep(step);
        event.setStatus(TraceEventStatus.STARTED);
        event.setAttempt((int) countFailedAttempts(bookId, step) + 1);
        event.setStartedAt(Instant.now());
        event.setDetails(details);
        event = traceEventRepository.save(event);

        putMdc(bookId, traceId, step);
        log.info("Step {} started (attempt {})", step, event.getAttempt());
        return event;
    }

    /**
     * Marks a step attempt as successfully completed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeStep(ProcessTraceEvent event, String details) {
        event.setStatus(TraceEventStatus.COMPLETED);
        event.setFinishedAt(Instant.now());
        if (details != null) {
            event.setDetails(details);
        }
        traceEventRepository.save(event);
        log.info("Step {} completed in {} ms", event.getStep(),
                event.getFinishedAt().toEpochMilli() - event.getStartedAt().toEpochMilli());
    }

    /**
     * Marks a step attempt as failed, persisting the error message.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failStep(ProcessTraceEvent event, Throwable error) {
        event.setStatus(TraceEventStatus.FAILED);
        event.setFinishedAt(Instant.now());
        event.setErrorMessage(truncate(error));
        traceEventRepository.save(event);
        log.error("Step {} failed: {}", event.getStep(), error.getMessage(), error);
    }

    /**
     * Marks a step attempt as interrupted by an explicit user stop: the
     * event closes as CANCELLED (not FAILED) so the attempt never counts
     * toward the genuine-failure retry budget and the timeline shows the
     * interruption with its reason.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancelStep(ProcessTraceEvent event, String reason) {
        event.setStatus(TraceEventStatus.CANCELLED);
        event.setFinishedAt(Instant.now());
        event.setDetails(reason);
        traceEventRepository.save(event);
        log.info("Step {} cancelled: {}", event.getStep(), reason);
    }

    /**
     * Records that a step was skipped because it was already satisfied by
     * the persisted state (idempotent re-entry).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void skipStep(UUID bookId, ProcessStep step, String traceId, String reason) {
        Book book = bookRepository.getReferenceById(bookId);

        ProcessTraceEvent event = new ProcessTraceEvent();
        event.setBook(book);
        event.setTraceId(traceId);
        event.setStep(step);
        event.setStatus(TraceEventStatus.SKIPPED);
        event.setAttempt((int) countFailedAttempts(bookId, step) + 1);
        event.setStartedAt(Instant.now());
        event.setFinishedAt(Instant.now());
        event.setDetails(reason);
        traceEventRepository.save(event);

        putMdc(bookId, traceId, step);
        log.info("Step {} skipped: {}", step, reason);
    }

    /**
     * Full processing timeline of a book, oldest first. The book is only
     * referenced by id (FK proxy): the CLOB is never loaded.
     */
    @Transactional(readOnly = true)
    public List<TraceEventDto> getTimeline(UUID bookId) {
        if (!bookRepository.existsById(bookId)) {
            throw new IllegalArgumentException("Book " + bookId + " not found");
        }
        Book book = bookRepository.getReferenceById(bookId);
        return traceEventRepository.findByBookOrderByStartedAtAscIdAsc(book).stream()
                .map(e -> new TraceEventDto(e.getId(), e.getTraceId(), e.getStep(), e.getStatus(),
                        e.getAttempt(), e.getStartedAt(), e.getFinishedAt(),
                        e.getErrorMessage(), e.getDetails()))
                .toList();
    }

    /**
     * Populates the MDC logging context for the current thread.
     */
    public static void putMdc(UUID bookId, String traceId, ProcessStep step) {
        MDC.put(MDC_BOOK_ID, bookId != null ? bookId.toString() : null);
        MDC.put(MDC_TRACE_ID, traceId);
        MDC.put(MDC_STEP, step != null ? step.name() : null);
    }

    /**
     * Clears the MDC keys managed by this service.
     */
    public static void clearMdc() {
        MDC.remove(MDC_BOOK_ID);
        MDC.remove(MDC_TRACE_ID);
        MDC.remove(MDC_STEP);
    }

    /**
     * Number of genuine failures recorded for a step: concluded runs that
     * FAILED. Interrupted runs, successful runs and blocked pseudo-attempts
     * never count, so restarts or crashes cannot exhaust the retry budget.
     * Single COUNT query with the covering (book, step, started_at) index.
     */
    public int countGenuineAttempts(UUID bookId, ProcessStep step) {
        return (int) traceEventRepository.countFailedAttempts(
                bookId, step, TraceEventStatus.FAILED, maxAttempts);
    }

    /**
     * Counts only genuine step failures: concluded runs that FAILED.
     * Interrupted runs, successful runs and blocked pseudo-attempts never
     * count, so restarts, crashes — or intentional re-runs of a step that
     * already worked (e.g. block-scoped splits) — cannot exhaust the retry
     * budget. Events with attempt > max are the pseudo-failures recorded
     * when the budget guard aborts a run before it starts; a genuine
     * attempt can never exceed the budget, so excluding them is exact and
     * also self-heals legacy blocked events.
     */
    private long countFailedAttempts(UUID bookId, ProcessStep step) {
        return traceEventRepository.countFailedAttempts(
                bookId, step, TraceEventStatus.FAILED, maxAttempts);
    }

    private static String truncate(Throwable error) {
        String message = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }
}
