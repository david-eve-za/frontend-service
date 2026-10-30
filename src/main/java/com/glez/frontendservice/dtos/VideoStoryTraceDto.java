package com.glez.frontendservice.dtos;

import java.time.Instant;
import java.util.UUID;

/**
 * Un evento de la cronología de un proyecto del Asistente de Historias:
 * el equivalente wizard del TraceEventDto del pipeline de libros, con la
 * duración del intento ya calculada.
 */
public record VideoStoryTraceDto(
        UUID id,
        String step,
        String status,
        int attempt,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs,
        String errorMessage,
        String details) {
}
