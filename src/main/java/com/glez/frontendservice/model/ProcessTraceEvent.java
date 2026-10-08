package com.glez.frontendservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
@Data
@NoArgsConstructor
@AllArgsConstructor
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
}
