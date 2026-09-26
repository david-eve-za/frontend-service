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
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProcessTraceEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
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
