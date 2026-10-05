package com.glez.frontendservice.logging;

import com.glez.frontendservice.dtos.LogEventDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("LogEventBroadcaster Tests")
class LogEventBroadcasterTest {

    @Mock
    private SseEmitter emitter;

    private LogEventBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        broadcaster = new LogEventBroadcaster();
    }

    private LogEventDto event(String message) {
        return new LogEventDto(UUID.randomUUID(), Instant.now(), "INFO",
                "com.glez.Test", "main", message, Map.of());
    }

    @Test
    @DisplayName("publish retiene el evento en el búfer y lo envía a los suscriptores")
    void publish_retainsAndSends() throws Exception {
        broadcaster.subscribe(emitter);

        broadcaster.publish(event("hola"));

        // El suscriptor recibió el evento (el propio subscribe reenvía el búfer vacío)
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        assertEquals(1, broadcaster.recent().size());
        assertEquals("hola", broadcaster.recent().get(0).message());
    }

    @Test
    @DisplayName("El búfer está acotado: los eventos más antiguos se descartan")
    void publish_boundsBuffer() {
        for (int i = 0; i < LogEventBroadcaster.MAX_HISTORY + 50; i++) {
            broadcaster.publish(event("m-" + i));
        }

        assertEquals(LogEventBroadcaster.MAX_HISTORY, broadcaster.recent().size());
        assertEquals("m-50", broadcaster.recent().get(0).message(),
                "El evento más antiguo retenido debe ser el primero tras el desborde");
        assertEquals("m-" + (LogEventBroadcaster.MAX_HISTORY + 49),
                broadcaster.recent().get(LogEventBroadcaster.MAX_HISTORY - 1).message());
    }

    @Test
    @DisplayName("Un suscriptor nuevo recibe primero la historia del búfer")
    void subscribe_receivesHistoryFirst() throws Exception {
        broadcaster.publish(event("viejo-1"));
        broadcaster.publish(event("viejo-2"));

        broadcaster.subscribe(emitter);

        // Dos eventos de historia + el onCompletion callback registrado
        verify(emitter, org.mockito.Mockito.times(2)).send(any(SseEmitter.SseEventBuilder.class));
        assertEquals(1, broadcaster.subscriberCount());
    }

    @Test
    @DisplayName("Un suscriptor cuya conexión falla se elimina del registro")
    void publish_removesBrokenSubscriber() throws Exception {
        broadcaster.subscribe(emitter);
        doThrow(new RuntimeException("broken pipe")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        broadcaster.publish(event("boom"));

        assertEquals(0, broadcaster.subscriberCount(),
                "El emisor roto no debe quedar registrado");
    }

    @Test
    @DisplayName("unsubscribe limpia el registro sin romper el búfer")
    void unsubscribe_cleansRegistry() {
        broadcaster.subscribe(emitter);

        broadcaster.unsubscribe(emitter);
        broadcaster.publish(event("sin-suscriptores"));

        assertEquals(0, broadcaster.subscriberCount());
        assertEquals(1, broadcaster.recent().size());
    }
}
