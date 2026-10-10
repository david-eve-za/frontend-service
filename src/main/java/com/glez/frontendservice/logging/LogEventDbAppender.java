package com.glez.frontendservice.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.glez.frontendservice.services.LogEventPersistenceService;

import java.util.Map;

/**
 * Appender de Logback que entrega cada evento del logger raíz al
 * {@link LogEventPersistenceService} para su persistencia en BD por lotes.
 *
 * <p>Fallo abierto y sin bloqueo: si el servicio está desactivado o el
 * evento queda por debajo del nivel mínimo, se ignora; si el contexto no
 * tiene mapa MDC (contextos manuales en tests) se degrada a mapa vacío.
 * Como {@link LogStreamAppender}, se adjunta programáticamente (ver
 * {@link LogPersistenceInitializer}) sin tocar la configuración de
 * consola/archivo de application.yml.</p>
 */
public class LogEventDbAppender extends AppenderBase<ILoggingEvent> {

    private final LogEventPersistenceService persistenceService;

    public LogEventDbAppender(LogEventPersistenceService persistenceService) {
        this.persistenceService = persistenceService;
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!persistenceService.captures(event.getLevel())) {
            return;
        }
        Map<String, String> mdc;
        try {
            mdc = event.getMDCPropertyMap();
        } catch (Exception e) {
            mdc = Map.of();
        }
        // Nunca lanza ni bloquea: la captura acota con la cola en memoria.
        persistenceService.capture(
                event.getLevel() != null ? event.getLevel().toString() : null,
                event.getLoggerName(),
                event.getThreadName(),
                event.getFormattedMessage(),
                mdc == null ? Map.of() : mdc);
    }
}
