package com.glez.frontendservice.services;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Limitador compartido de solicitudes a la API de NVIDIA (cuota
 * configurada en RPM). Token-bucket: capacidad inicial completa (se
 * permite un arranque en ráfaga) y reposición de un permiso cada
 * 60/rpm segundos.
 *
 * <p>Pensado para virtual threads: acquire() BLOQUEA el hilo llamador
 * hasta que hay cupo, y un virtual thread aparca gratis durante la
 * espera — la concurrencia la gobierna esta cuota, no el pool de hilos.
 * Todos los puntos de consumo (traducción con reintentos/rotación de
 * modelos y extracción del glosario) pasan por aquí, de modo que el
 * pipeline completo respeta el límite de forma orquestada.
 */
@Component
public class NvidiaRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(NvidiaRateLimiter.class);

    private final int rpm;
    private final Semaphore permits;
    private final ScheduledExecutorService refiller;

    public NvidiaRateLimiter(@Value("${app.nvidia.rate-limit-rpm:30}") int rpm) {
        this.rpm = rpm;
        if (rpm > 0) {
            this.permits = new Semaphore(rpm, true);
            long periodMs = Math.max(1, 60_000L / rpm);
            this.refiller = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "nvidia-rate-refiller");
                thread.setDaemon(true);
                return thread;
            });
            this.refiller.scheduleAtFixedRate(this::refill, periodMs, periodMs, TimeUnit.MILLISECONDS);
            log.info("NVIDIA API rate limit enabled: {} requests per minute (refill every {} ms)", rpm, periodMs);
        } else {
            this.permits = null;
            this.refiller = null;
            log.info("NVIDIA API rate limit disabled (app.nvidia.rate-limit-rpm={})", rpm);
        }
    }

    /**
     * Bloquea hasta que haya cupo para una solicitud. Con el límite
     * deshabilitado (rpm <= 0) es un no-op.
     */
    public void acquire() {
        if (permits == null) {
            return;
        }
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for NVIDIA API quota", e);
        }
    }

    /** Repone un permiso (hasta la capacidad) en cada tick. */
    private void refill() {
        if (permits.availablePermits() < rpm) {
            permits.release();
        }
    }

    @PreDestroy
    void shutdown() {
        if (refiller != null) {
            refiller.shutdownNow();
        }
    }
}
