package com.glez.frontendservice.dtos;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Un evento de log capturado en vivo para el visor del frontend: nivel,
 * clase que lo emitió, hilo, mensaje formateado y el contexto MDC de
 * correlación del pipeline (bookId, traceId, step) cuando existe.
 */
public record LogEventDto(
        UUID id,
        Instant timestamp,
        String level,
        String logger,
        String thread,
        String message,
        Map<String, String> mdc) {
}
