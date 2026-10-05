package com.glez.frontendservice.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.glez.frontendservice.dtos.LogEventDto;

import java.time.Instant;
import java.util.Map;

/**
 * Appender de Logback que captura cada evento del logger raíz y lo publica
 * al {@link LogEventBroadcaster} para el visor de logs en tiempo real del
 * frontend. Se adjunta programáticamente (ver {@link LogStreamInitializer}),
 * así que la configuración de consola/archivo del application.yml no cambia.
 */
public class LogStreamAppender extends AppenderBase<ILoggingEvent> {

    private final LogEventBroadcaster broadcaster;

    public LogStreamAppender(LogEventBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @Override
    protected void append(ILoggingEvent event) {
        // Un appender jamás debe romper el logging: si el contexto no tiene
        // adaptador MDC (p.ej. contextos manuales en tests) se degrada a un
        // mapa vacío en lugar de fallar toda la cadena.
        Map<String, String> mdc;
        try {
            mdc = event.getMDCPropertyMap();
        } catch (Exception e) {
            mdc = Map.of();
        }
        broadcaster.publish(new LogEventDto(
                LogEventBroadcaster.newId(),
                Instant.ofEpochMilli(event.getTimeStamp()),
                event.getLevel().toString(),
                event.getLoggerName(),
                event.getThreadName(),
                event.getFormattedMessage(),
                mdc == null || mdc.isEmpty() ? Map.of() : mdc));
    }
}
