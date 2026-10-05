package com.glez.frontendservice.controllers;

import com.glez.frontendservice.dtos.LogEventDto;
import com.glez.frontendservice.logging.LogEventBroadcaster;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
@Tag(name = "Log Stream", description = "Visor de logs en tiempo real: los eventos de Logback se transmiten "
        + "vía Server-Sent Events, con la historia reciente para quien se conecta tarde")
public class LogStreamController {

    /** Sin timeout: el stream vive mientras el cliente esté conectado. */
    private static final long NO_TIMEOUT = 0L;

    private final LogEventBroadcaster broadcaster;

    @Operation(summary = "Stream SSE de logs en tiempo real",
            description = "Devuelve un SseEmitter que entrega primero los últimos eventos retenidos "
                    + "y a partir de ahí cada nuevo evento de log ('log') en cuanto ocurre, "
                    + "incluyendo el contexto MDC (bookId, traceId, step) del pipeline.")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Stream de eventos de log"))
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(NO_TIMEOUT);
        broadcaster.subscribe(emitter);
        return emitter;
    }

    @Operation(summary = "Historia reciente de logs",
            description = "Instantánea del búfer en memoria (últimos 1000 eventos, la más antigua primero).")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Eventos recientes"))
    @GetMapping("/recent")
    public ResponseEntity<List<LogEventDto>> recent() {
        return ResponseEntity.ok(broadcaster.recent());
    }
}
