package com.glez.frontendservice.services;

import ch.qos.logback.classic.Level;
import com.glez.frontendservice.model.LogEvent;
import com.glez.frontendservice.repository.LogEventRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Persistencia de logs en BD con retención acotada: los eventos de Logback
 * (a partir del nivel configurado) se acumulan en una cola en memoria y un
 * hilo propio los vuelca POR LOTES (un saveAll por tanda), de modo que el
 * logging de la aplicación nunca bloquea ni depende de la salud de la BD.
 *
 * <p>Retención: una purga diaria (y otra al arrancar) borra en bloque los
 * eventos más antiguos que {@code app.log-persistence.retention-days}
 * (por defecto 7 días). El contexto MDC del pipeline (bookId/traceId/step)
 * se guarda como columnas de primera clase para el análisis por libro.</p>
 *
 * <p>Anti-recursión: los eventos generados por este propio servicio (y por
 * su appender) se excluyen de la captura — de lo contrario cada volcado
 * generaría logs que generarían volcados.</p>
 */
@Component
public class LogEventPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(LogEventPersistenceService.class);

    /** Cola en memoria: acota el impacto de una BD lenta o caída. */
    private static final int QUEUE_CAPACITY = 20_000;

    private static final long FLUSH_INTERVAL_MS = 5_000;

    private static final long PURGE_INTERVAL_HOURS = 24;

    /**
     * Logger propio y del appender: excluidos de la captura para evitar la
     * retroalimentación (sus mensajes van a consola/archivo/visor, no a BD).
     */
    private static final String SELF_LOGGER_PREFIX =
            "com.glez.frontendservice.services.LogEventPersistenceService";

    private final LogEventRepository repository;

    private final boolean enabled;

    private volatile Level minThreshold = Level.INFO;

    private final int retentionDays;

    private final BlockingQueue<LogEvent> pending = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    private final AtomicLong dropped = new AtomicLong();

    private ScheduledExecutorService scheduler;

    private long lastDropWarningAt;

    public LogEventPersistenceService(
            LogEventRepository repository,
            @Value("${app.log-persistence.enabled:true}") boolean enabled,
            @Value("${app.log-persistence.min-level:INFO}") String minLevelName,
            @Value("${app.log-persistence.retention-days:7}") int retentionDays) {
        this.repository = repository;
        this.enabled = enabled;
        this.minThreshold = parseLevel(minLevelName);
        this.retentionDays = Math.max(1, retentionDays);
        if (!enabled) {
            log.info("Persistencia de logs en BD DESACTIVADA (app.log-persistence.enabled=false)");
        } else {
            log.info("Persistencia de logs en BD activada: nivel mínimo {}, retención {} día(s)",
                    minThreshold, this.retentionDays);
        }
    }

    /** Arranca el volcador y el purgador cuando la aplicación (y su BD) están listos. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("log-persist", 1).factory());
        scheduler.scheduleAtFixedRate(this::flush, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        // Purga inmediata (limpia restos de corridas previas con más de la
        // retención) y luego una vez al día.
        scheduler.scheduleAtFixedRate(this::purge, 10, PURGE_INTERVAL_HOURS, TimeUnit.HOURS);
    }

    /** Último volcado y parada del planificador al apagar la aplicación. */
    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (enabled) {
            flush();
        }
    }

    /** Whether the appender should hand this event over (level + config). */
    public boolean captures(Level level) {
        return enabled && level != null && level.isGreaterOrEqual(minThreshold);
    }

    /**
     * Captura un evento para persistencia: nunca bloquea ni lanza — la cola
     * acota el consumo de memoria y los excesos se descartan con contador.
     */
    public void capture(String level, String logger, String thread, String formattedMessage,
                         Map<String, String> mdc) {
        if (logger != null && (logger.startsWith(SELF_LOGGER_PREFIX)
                || logger.equals("com.glez.frontendservice.logging.LogEventDbAppender"))) {
            return;
        }
        LogEvent event = new LogEvent();
        // El id NO se asigna aquí: @GeneratedValue(UUID) de Hibernate lo emite
        // al persistir (un id pre-asignado haría que saveAll lo tratara como
        // un UPDATE de una fila inexistente).
        event.setLoggedAt(Instant.now());
        event.setLevel(level);
        event.setLogger(truncate(logger, 256));
        event.setThread(truncate(thread, 128));
        event.setMessage(truncate(formattedMessage, LogEvent.MAX_MESSAGE_LENGTH));
        if (mdc != null) {
            event.setBookId(truncate(mdc.get("bookId"), 64));
            event.setTraceId(truncate(mdc.get("traceId"), 64));
            event.setStep(truncate(mdc.get("step"), 32));
        }
        if (!pending.offer(event)) {
            long total = dropped.incrementAndGet();
            // Aviso limitado: no tiene sentido loggear cada descarte (y menos
            // a un ritmo que alimentaría la propia cola).
            if (System.currentTimeMillis() - lastDropWarningAt > 60_000) {
                lastDropWarningAt = System.currentTimeMillis();
                log.warn("Cola de persistencia de logs llena: {} eventos descartados hasta ahora", total);
            }
        }
    }

    /**
     * Vuelca la cola a BD en un lote (saveAll); si la BD falla (p. ej. un
     * reinicio de devtools), el lote vuelve a la cola y se reintenta en el
     * próximo tick — saveAll es transaccional, así que no hay duplicados.
     */
    void flush() {
        List<LogEvent> batch = new ArrayList<>();
        pending.drainTo(batch, 1_000);
        if (batch.isEmpty()) {
            return;
        }
        try {
            repository.saveAll(batch);
            log.debug("Persistidos {} eventos de log en BD", batch.size());
        } catch (Exception e) {
            long returned = 0;
            for (LogEvent event : batch) {
                // DESINTOXICAR EL REINTENTO: saveAll ya asignó el UUID al
                // persistir (pre-commit), y como la transacción hizo
                // rollback la fila jamás existió. Sin limpiar el id, el
                // próximo saveAll trataría el evento como EXISTENTE →
                // UPDATE de una fila inexistente → "Row was already
                // updated or deleted by another transaction" → el lote
                // rebotaría PARA SIEMPRE (bug real: 403 avisos en 2 h con
                // el mismo lote de 18). Con el id a null, el reintento es
                // un INSERT limpio con un UUID fresco.
                event.setId(null);
                if (pending.offer(event)) {
                    returned++;
                } else {
                    dropped.incrementAndGet();
                }
            }
            // La BD puede estar reiniciándose (devtools) o saturada: el
            // propio error no se captura (exclusión de auto-recursión).
            log.warn("No se pudo volcar el lote de logs a BD ({} reencolados de {}, {} pendientes): {}",
                    returned, batch.size(), pending.size(), e.getMessage());
        }
    }

    /** Purga de retención: borra en bloque lo anterior al corte configurado. */
    void purge() {
        try {
            Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
            int deleted = repository.deleteByLoggedAtBefore(cutoff);
            if (deleted > 0) {
                log.info("Retención de logs: {} eventos eliminados (anteriores a {})", deleted, cutoff);
            }
        } catch (Exception e) {
            log.warn("No se pudo ejecutar la purga de retención de logs: {}", e.getMessage());
        }
    }

    long pendingCount() {
        return pending.size();
    }

    long droppedCount() {
        return dropped.get();
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max - 1) + "…";
    }

    private static Level parseLevel(String name) {
        if (name == null) {
            return Level.INFO;
        }
        return switch (name.trim().toUpperCase()) {
            case "ERROR" -> Level.ERROR;
            case "WARN" -> Level.WARN;
            case "DEBUG" -> Level.DEBUG;
            case "TRACE" -> Level.TRACE;
            default -> Level.INFO;
        };
    }
}
