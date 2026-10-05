package com.glez.frontendservice.logging;

import com.glez.frontendservice.dtos.LogEventDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Núcleo del visor de logs en vivo: mantiene un búfer en memoria con los
 * últimos eventos (historia para quien se conecta tarde) y reenvía cada
 * evento nuevo a todos los suscriptores SSE en tiempo real.
 *
 * <p>Todo es en proceso: el {@link LogStreamAppender} de Logback publica
 * aquí y {@code LogStreamController} expone los suscriptores; no hay
 * aplicaciones externas ni brokers intermedios.
 */
@Component
public class LogEventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(LogEventBroadcaster.class);

    /** Historia máxima retenida en memoria (eventos por estado, no bytes). */
    static final int MAX_HISTORY = 1000;

    private final Deque<LogEventDto> buffer = new ArrayDeque<>();
    private final List<SseEmitter> subscribers = new ArrayList<>();

    /**
     * Registra un suscriptor SSE: recibe primero la historia del búfer y a
     * partir de ahí los eventos en vivo (sin hueco entre historia y stream).
     */
    public synchronized void subscribe(SseEmitter emitter) {
        subscribers.add(emitter);
        emitter.onCompletion(() -> unsubscribe(emitter));
        emitter.onTimeout(() -> unsubscribe(emitter));
        emitter.onError(e -> unsubscribe(emitter));
        for (LogEventDto event : buffer) {
            send(emitter, event);
        }
        log.debug("Log stream subscriber added ({} active)", subscribers.size());
    }

    public synchronized void unsubscribe(SseEmitter emitter) {
        subscribers.remove(emitter);
    }

    /** Publica un evento: se retiene en el búfer y se envía a los suscriptores. */
    public synchronized void publish(LogEventDto event) {
        buffer.addLast(event);
        while (buffer.size() > MAX_HISTORY) {
            buffer.removeFirst();
        }
        for (SseEmitter emitter : new ArrayList<>(subscribers)) {
            send(emitter, event);
        }
    }

    /** Copia de la historia actual, la más antigua primero. */
    public synchronized List<LogEventDto> recent() {
        return new ArrayList<>(buffer);
    }

    public synchronized int subscriberCount() {
        return subscribers.size();
    }

    private void send(SseEmitter emitter, LogEventDto event) {
        try {
            emitter.send(SseEmitter.event().name("log").data(event));
        } catch (Exception e) {
            // Cliente desconectado o stream roto: se elimina en el callback
            // de error/onCompletion, pero nos aseguramos aquí también.
            subscribers.remove(emitter);
        }
    }

    /** Id corto para los eventos (uso interno del broadcaster/appender). */
    public static UUID newId() {
        return UUID.randomUUID();
    }
}
