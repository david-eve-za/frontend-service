package com.glez.frontendservice.dtos;

import com.glez.frontendservice.model.ProcessStep;
import com.glez.frontendservice.model.TraceEventStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only view of a pipeline trace event, returned by the trace endpoint.
 */
public record TraceEventDto(
        UUID id,
        String traceId,
        ProcessStep step,
        TraceEventStatus status,
        Integer attempt,
        Instant startedAt,
        Instant finishedAt,
        String errorMessage,
        String details
) {
}
