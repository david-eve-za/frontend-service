package com.glez.frontendservice.services;

import com.glez.frontendservice.config.MdcTaskDecorator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MdcTaskDecorator Tests")
class MdcTaskDecoratorTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("MDC context propagates to the executor thread")
    void decorate_propagatesMdcToWorkerThread() throws Exception {
        MDC.put("bookId", "book-1");
        MDC.put("traceId", "trace-1");
        MDC.put("step", "TRANSLATE");
        MdcTaskDecorator decorator = new MdcTaskDecorator();

        AtomicReference<Map<String, String>> workerContext = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Thread worker = new Thread(decorator.decorate(() -> {
            workerContext.set(MDC.getCopyOfContextMap());
            latch.countDown();
        }));
        worker.start();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Map<String, String> context = workerContext.get();
        assertNotNull(context);
        assertEquals("book-1", context.get("bookId"));
        assertEquals("trace-1", context.get("traceId"));
        assertEquals("TRANSLATE", context.get("step"));

        // The submitting thread keeps its own context untouched
        assertEquals("book-1", MDC.get("bookId"));
    }

    @Test
    @DisplayName("Worker thread context is cleaned after the task completes")
    void decorate_restoresPreviousContextAfterTask() throws Exception {
        MDC.put("bookId", "book-2");
        MdcTaskDecorator decorator = new MdcTaskDecorator();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Map<String, String>> afterRun = new AtomicReference<>();

        Thread worker = new Thread(() -> {
            MDC.put("workerOwn", "value");
            decorator.decorate(() -> MDC.put("inside", "task")).run();
            afterRun.set(MDC.getCopyOfContextMap());
            latch.countDown();
        });
        worker.start();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Map<String, String> context = afterRun.get();
        assertNotNull(context);
        // The keys set inside the decorated task are gone...
        assertNull(context.get("inside"));
        // ...and the worker's previous context is restored.
        assertEquals("value", context.get("workerOwn"));
    }
}
