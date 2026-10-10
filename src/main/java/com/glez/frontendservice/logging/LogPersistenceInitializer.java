package com.glez.frontendservice.logging;

import ch.qos.logback.classic.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * Adjunta el {@link LogEventDbAppender} al logger raíz de Logback cuando la
 * aplicación está lista, y lo retira al apagarse — espejo exacto del
 * {@link LogStreamInitializer} para la persistencia de logs en BD: sin
 * tocar la configuración de logging existente.
 */
@Component
public class LogPersistenceInitializer {

    private final com.glez.frontendservice.services.LogEventPersistenceService persistenceService;

    private LogEventDbAppender appender;

    public LogPersistenceInitializer(
            com.glez.frontendservice.services.LogEventPersistenceService persistenceService) {
        this.persistenceService = persistenceService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void attach() {
        ch.qos.logback.classic.LoggerContext context =
                (ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory();
        appender = new LogEventDbAppender(persistenceService);
        appender.setContext(context);
        appender.setName("LOG_DB");
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
