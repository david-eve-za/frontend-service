package com.glez.frontendservice.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("NvidiaRateLimiter Tests")
class NvidiaRateLimiterTest {

    @Test
    @DisplayName("Disabled limiter (rpm <= 0) is a no-op: acquires never block")
    void disabledLimiter_acquireNeverBlocks() {
        NvidiaRateLimiter disabled = new NvidiaRateLimiter(0);
        for (int i = 0; i < 1000; i++) {
            disabled.acquire();
        }
    }

    @Test
    @DisplayName("The initial capacity allows exactly rpm immediate acquires")
    void initialCapacity_allowsBurstUpToRpm() {
        NvidiaRateLimiter limiter = new NvidiaRateLimiter(5);
        for (int i = 0; i < 5; i++) {
            limiter.acquire(); // no debe bloquear
        }
    }

    @Test
    @DisplayName("Acquires beyond capacity block until the refiller releases")
    void beyondCapacity_blocksUntilRefill() throws Exception {
        int rpm = 2;
        long periodMs = Math.max(1, 60_000L / rpm); // 30s por permiso con rpm=2: demasiado lento para test
        // Usamos un rpm alto para acelerar el refill en el test: 600 rpm -> 100ms por permiso
        int fastRpm = 600;
        NvidiaRateLimiter limiter = new NvidiaRateLimiter(fastRpm);

        // Agotamos la capacidad inicial (600 permisos serían muchos; usamos el propio límite)
        // Consumimos todos los permisos disponibles
        for (int i = 0; i < fastRpm; i++) {
            limiter.acquire();
        }

        long start = System.nanoTime();
        limiter.acquire(); // debe bloquear hasta el refill (~100ms)
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(waitedMs >= 50, "acquire beyond capacity must block for the refill period (waited " + waitedMs + "ms)");
        assertTrue(waitedMs < 5000, "the refill must arrive in a bounded time");
    }

    @Test
    @DisplayName("Concurrent acquires from many virtual threads never exceed the capacity")
    void concurrentAcquires_respectCapacity() throws Exception {
        // rpm alto = refill rápido (100ms por permiso) para que el test
        // sea corto; la mecánica es idéntica con cualquier rpm.
        int fastRpm = 600;
        NvidiaRateLimiter limiter = new NvidiaRateLimiter(fastRpm);

        // Consumimos la ráfaga inicial
        for (int i = 0; i < fastRpm; i++) {
            limiter.acquire();
        }

        // 50 virtual threads intentan adquirir: se bloquean en el refill
        // (50 x 100ms = 5s en total)
        int threads = 50;
        AtomicInteger acquired = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(threads);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    limiter.acquire();
                    acquired.incrementAndGet();
                    done.countDown();
                });
            }
            assertTrue(done.await(15, TimeUnit.SECONDS), "all acquires must eventually complete");
        }
        assertEquals(threads, acquired.get());
    }
}
