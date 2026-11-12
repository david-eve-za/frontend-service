package com.glez.frontendservice.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One persisted trace event of a book processing pipeline step attempt.
 * The full list of events for a book forms its processing timeline and is
 * the basis for resuming the pipeline from the last valid checkpoint.
 */
@Entity
// Composite covering index for countGenuineFailedAttempts (book + step
// + started_at ordering without loading the events).
@Table(indexes = @Index(name = "idx_trace_book_step_started", columnList = "book_id, step, started_at"))
public class ProcessTraceEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * LAZY: trace timelines hold dozens of events per book; eagerly
     * dragging the Book (and its multi-megabyte full_text CLOB) with
     * every event row would multiply the read cost for nothing. Only
     * the FK id is ever read from this association.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    @Column(name = "trace_id", nullable = false)
    private String traceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProcessStep step;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TraceEventStatus status;

    @Column(nullable = false)
    private Integer attempt;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(length = 2000)
    private String details;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Book getBook() {
        return book;
    }

    public void setBook(Book book) {
        this.book = book;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public ProcessStep getStep() {
        return step;
    }

    public void setStep(ProcessStep step) {
        this.step = step;
    }

    public TraceEventStatus getStatus() {
        return status;
    }

    public void setStatus(TraceEventStatus status) {
        this.status = status;
    }

    public Integer getAttempt() {
        return attempt;
    }

    public void setAttempt(Integer attempt) {
        this.attempt = attempt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }
}