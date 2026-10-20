package com.glez.frontendservice.controllers;

import com.glez.frontendservice.dtos.LogEventDto;
import com.glez.frontendservice.logging.LogEventBroadcaster;
import com.glez.frontendservice.model.LogEvent;
import com.glez.frontendservice.model.PaginatedResponse;
import com.glez.frontendservice.repository.LogEventRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
@Tag(name = "Log Stream", description = "Visor de logs en tiempo real (SSE + búfer) e histórico persistido en BD "
        + "con retención acotada, para el análisis a posteriori de errores")
public class LogStreamController {

    /** Sin timeout: el stream vive mientras el cliente esté conectado. */
    private static final long NO_TIMEOUT = 0L;

    private static final int MAX_SEARCH_PAGE_SIZE = 200;

    private final LogEventBroadcaster broadcaster;
    private final LogEventRepository logEventRepository;

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

    @Operation(summary = "Búsqueda en el histórico de logs persistido en BD",
            description = "Consulta paginada del histórico (retención configurable, por defecto 7 días) "
                    + "por rango de tiempo, nivel, clase(s), libro del pipeline, traceId de traza "
                    + "(el id de proyecto del asistente de historias o el traceId de cualquier proceso) "
                    + "o texto en el mensaje. El contexto MDC (bookId, traceId, step) se reconstruye en "
                    + "cada evento. Orden descendente por fecha (lo más reciente primero).")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Página de eventos de log persistidos"))
    @GetMapping("/search")
    public ResponseEntity<PaginatedResponse<LogEventDto>> search(
            @Parameter(description = "Desde (ISO-8601), opcional")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Hasta (ISO-8601), opcional")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "Nivel exacto (ERROR, WARN, INFO...), opcional")
            @RequestParam(required = false) String level,
            @Parameter(description = "Clase(s) emisora(s), separadas por coma (selección múltiple), opcional")
            @RequestParam(required = false) String loggers,
            @Parameter(description = "bookId del pipeline (MDC), opcional")
            @RequestParam(required = false) String bookId,
            @Parameter(description = "traceId de traza MDC (p. ej. el id de un proyecto del asistente), opcional")
            @RequestParam(required = false) String traceId,
            @Parameter(description = "Texto contenido en el mensaje o en la clase, opcional")
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int pageSize = Math.min(Math.max(1, size), MAX_SEARCH_PAGE_SIZE);
        Pageable pageable = PageRequest.of(Math.max(0, page), pageSize, Sort.by(Sort.Direction.DESC, "loggedAt"));
        List<String> loggerNames = parseLoggers(loggers);
        Page<LogEventDto> result = logEventRepository
                .findAll(searchSpecification(from, to, level, loggerNames, bookId, traceId, q), pageable)
                .map(LogStreamController::toDto);
        return ResponseEntity.ok(new PaginatedResponse<>(result));
    }

    /** Lista de clases seleccionadas (selección múltiple), sin elementos vacíos. */
    private static List<String> parseLoggers(String loggers) {
        if (loggers == null || loggers.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(loggers.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .toList();
    }

    /**
     * Filtros opcionales del histórico: rango de tiempo, nivel exacto,
     * selección múltiple de clases (IN), libro del pipeline, traceId de
     * traza (proceso del asistente o cualquier traza MDC) y texto en
     * mensaje o clase. Los vacíos/nulos no filtran — una petición sin
     * parámetros devuelve la primera página del histórico reciente.
     *
     * <p>NOTA: construir con {@code cb.conjunction().getExpressions().add(...)}
     * NO funciona en Hibernate 6 (getExpressions() es una vista no mutable y
     * los predicados añadidos se ignoran silenciosamente) — se acumulan en
     * una lista y se combinan con {@code cb.and(...)}.</p>
     */
    private static Specification<LogEvent> searchSpecification(
            Instant from, Instant to, String level, List<String> loggers,
            String bookId, String traceId, String q) {
        return (root, query, cb) -> {
            List<Predicate> filters = new ArrayList<>();
            if (from != null) {
                filters.add(cb.greaterThanOrEqualTo(root.get("loggedAt"), from));
            }
            if (to != null) {
                filters.add(cb.lessThanOrEqualTo(root.get("loggedAt"), to));
            }
            if (level != null && !level.isBlank()) {
                filters.add(cb.equal(cb.upper(root.get("level")), level.trim().toUpperCase()));
            }
            if (!loggers.isEmpty()) {
                // Path TIPADO: con root.get(...) sin tipo (Path<Object>) el
                // bind de los parámetros del IN no compara correctamente.
                filters.add(root.<String>get("logger").in(loggers));
            }
            if (bookId != null && !bookId.isBlank()) {
                filters.add(cb.equal(root.get("bookId"), bookId.trim()));
            }
            if (traceId != null && !traceId.isBlank()) {
                filters.add(cb.equal(root.get("traceId"), traceId.trim()));
            }
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase() + "%";
                filters.add(cb.or(
                        cb.like(cb.lower(root.get("message")), like),
                        cb.like(cb.lower(root.get("logger")), like)));
            }
            return cb.and(filters.toArray(new Predicate[0]));
        };
    }

    /** Reconstruye el DTO del visor (mdc como mapa) desde la fila persistida. */
    private static LogEventDto toDto(LogEvent event) {
        Map<String, String> mdc = new HashMap<>();
        if (event.getBookId() != null) {
            mdc.put("bookId", event.getBookId());
        }
        if (event.getTraceId() != null) {
            mdc.put("traceId", event.getTraceId());
        }
        if (event.getStep() != null) {
            mdc.put("step", event.getStep());
        }
        return new LogEventDto(
                event.getId(),
                event.getLoggedAt(),
                event.getLevel(),
                event.getLogger(),
                event.getThread(),
                event.getMessage(),
                mdc);
    }
}
