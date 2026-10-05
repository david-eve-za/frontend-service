package com.glez.frontendservice.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.glez.frontendservice.dtos.LogEventDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("LogStreamAppender Tests")
class LogStreamAppenderTest {

    private static final String LOGGER_NAME = "com.glez.frontendservice.services.Test";

    @Mock
    private LogEventBroadcaster broadcaster;

    private LoggerContext context;
    private Logger logger;

    @BeforeEach
    void setUp() {
        context = new LoggerContext();
        // Un contexto manual no configura el adaptador MDC; se inyecta para
        // que el append recupere el contexto real (como hace Spring Boot).
        context.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
        LogStreamAppender appender = new LogStreamAppender(broadcaster);
        appender.setContext(context);
        appender.setName("LOG_STREAM_TEST");
        appender.start();

        logger = context.getLogger(LOGGER_NAME);
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        logger.setAdditive(false);

        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        context.stop();
    }

    @Test
    @DisplayName("El evento publicado lleva nivel, clase, hilo y mensaje formateado")
    void append_publishesStructuredDto() {
        logger.warn("mensaje {}", "formateado");

        ArgumentCaptor<LogEventDto> captor = ArgumentCaptor.forClass(LogEventDto.class);
        verify(broadcaster).publish(captor.capture());
        LogEventDto dto = captor.getValue();

        assertEquals("WARN", dto.level());
        assertEquals(LOGGER_NAME, dto.logger());
        assertEquals(Thread.currentThread().getName(), dto.thread());
        assertEquals("mensaje formateado", dto.message());
        assertNotNull(dto.timestamp());
        assertNotNull(dto.id());
    }

    @Test
    @DisplayName("El evento incluye el contexto MDC del pipeline (bookId/traceId/step)")
    void append_carriesMdcContext() {
        // Se escribe en el adaptador del propio contexto (en producción
        // org.slf4j.MDC apunta exactamente a este mismo adaptador).
        context.getMDCAdapter().put("bookId", "book-1");
        context.getMDCAdapter().put("traceId", "trace-9");
        context.getMDCAdapter().put("step", "TRANSLATE");

        logger.error("fallo del pipeline");

        ArgumentCaptor<LogEventDto> captor = ArgumentCaptor.forClass(LogEventDto.class);
        verify(broadcaster).publish(captor.capture());

        Map<String, String> mdc = captor.getValue().mdc();
        assertEquals("book-1", mdc.get("bookId"));
        assertEquals("trace-9", mdc.get("traceId"));
        assertEquals("TRANSLATE", mdc.get("step"));

        context.getMDCAdapter().clear();
    }

    @Test
    @DisplayName("Sin MDC el evento publica un mapa vacío")
    void append_withoutMdc_publishesEmptyMap() {
        logger.debug("sin contexto");

        ArgumentCaptor<LogEventDto> captor = ArgumentCaptor.forClass(LogEventDto.class);
        verify(broadcaster).publish(captor.capture());

        assertTrue(captor.getValue().mdc().isEmpty());
    }
}
