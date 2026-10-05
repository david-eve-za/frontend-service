package com.glez.frontendservice.logging;

import ch.qos.logback.classic.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * Adjunta el {@link LogStreamAppender} al logger raíz de Logback cuando la
 * aplicación está lista, y lo retira al apagarse. Así el visor de logs del
 * frontend recibe todos los eventos (incluido el contexto MDC del pipeline)
 * sin tocar la configuración de logging existente.
 */
@Component
public class LogStreamInitializer {

    private final LogEventBroadcaster broadcaster;

    private LogStreamAppender appender;

    public LogStreamInitializer(LogEventBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void attach() {
        ch.qos.logback.classic.LoggerContext context =
                (ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory();
        appender = new LogStreamAppender(broadcaster);
        appender.setContext(context);
        appender.setName("LOG_STREAM");
        appender.start();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(appender);
    }

    @PreDestroy
    public void detach() {
        if (appender == null) {
            return;
        }
        ch.qos.logback.classic.LoggerContext context =
                (ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory();
        context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(appender);
        appender.stop();
    }
}
