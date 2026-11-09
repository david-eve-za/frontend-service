package com.glez.frontendservice.crawler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TokenBucketRateLimiter.
 */
class TokenBucketRateLimiterTest {

    private TokenBucketRateLimiter limiter;
    private ScheduledExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newScheduledThreadPool(2);
        // 60 RPM = 1 request per second
        limiter = new TokenBucketRateLimiter("test-source", 60, executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void testTryAcquire() {
        // Should be able to acquire up to bucket capacity (60)
        int acquired = 0;
        for (int i = 0; i < 60; i++) {
            if (limiter.tryAcquire()) {
                acquired++;
            }
        }
        assertEquals(60, acquired);
        
        // Bucket should be empty now
        assertFalse(limiter.tryAcquire());
        assertEquals(0, limiter.getAvailablePermits());
    }

    @Test
    void testAcquireBlocking() throws InterruptedException {
        // Consume all tokens
        for (int i = 0; i < 60; i++) {
            assertTrue(limiter.tryAcquire());
        }
        
        // Next acquire should block until token refills (~1 second)
        long start = System.currentTimeMillis();
        boolean acquired = limiter.acquire(Duration.ofSeconds(2));
        long elapsed = System.currentTimeMillis() - start;
        
        assertTrue(acquired);
        // Should have waited at least ~1 second for token refill
        assertTrue(elapsed >= 900, "Should have waited for token refill, but only waited " + elapsed + "ms");
    }

    @Test
    void testAcquireTimeout() {
        // Consume all tokens
        for (int i = 0; i < 60; i++) {
            assertTrue(limiter.tryAcquire());
        }
        
        // Try to acquire with very short timeout - should fail
        boolean acquired = limiter.acquire(Duration.ofMillis(100));
        assertFalse(acquired);
    }

    @Test
    void testConcurrentAccess() throws InterruptedException {
        int threadCount = 10;
        int requestsPerThread = 20;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        
        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < requestsPerThread; i++) {
                        if (limiter.tryAcquire()) {
                            successCount.incrementAndGet();
                        } else {
                            failureCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        
        // Total requests = 200, bucket capacity = 60
        // So we expect at most 60 successes from tryAcquire (rest will fail)
        // But with concurrent access, some might succeed due to refill
        assertTrue(successCount.get() >= 60, "Should have at least 60 successes");
        assertEquals(threadCount * requestsPerThread, successCount.get() + failureCount.get());
    }

    @Test
    void testGetRateLimitRpm() {
        assertEquals(60, limiter.getRateLimitRpm());
    }

    @Test
    void testGetSourceId() {
        assertEquals("test-source", limiter.getSourceId());
    }

    @Test
    void testReset() {
        // Consume some tokens
        for (int i = 0; i < 30; i++) {
            assertTrue(limiter.tryAcquire());
        }
        assertEquals(30, limiter.getAvailablePermits());
        
        // Reset
        limiter.reset();
        assertEquals(60, limiter.getAvailablePermits());
    }

    @Test
    void testAsyncAcquire() throws Exception {
        // Consume all tokens
        for (int i = 0; i < 60; i++) {
            assertTrue(limiter.tryAcquire());
        }
        
        // Async acquire should complete when token refills
        var future = limiter.acquireAsync();
        assertFalse(future.isDone());
        
        // Wait for completion (should take ~1 second)
        future.get(3, TimeUnit.SECONDS);
        assertTrue(future.isDone());
    }
}