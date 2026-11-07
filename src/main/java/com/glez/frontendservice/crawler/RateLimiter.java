package com.glez.frontendservice.crawler;

import java.time.Duration;

/**
 * Rate limiter interface for controlling request rates per source.
 * Implementations should be thread-safe and support async/blocking acquisition.
 */
public interface RateLimiter {

    /**
     * Acquire a permit for making a request.
     * Blocks until a permit is available or timeout expires.
     * 
     * @param timeout maximum time to wait for a permit
     * @return true if permit acquired, false if timeout
     */
    boolean acquire(Duration timeout);

    /**
     * Acquire a permit asynchronously.
     * 
     * @return CompletableFuture that completes when permit is acquired
     */
    java.util.concurrent.CompletableFuture<Void> acquireAsync();

    /**
     * Try to acquire a permit without blocking.
     * 
     * @return true if permit acquired immediately, false otherwise
     */
    boolean tryAcquire();

    /**
     * Get the configured rate limit (requests per minute).
     * 
     * @return rate limit in RPM
     */
    int getRateLimitRpm();

    /**
     * Get the source ID this limiter is for.
     * 
     * @return source ID
     */
    String getSourceId();

    /**
     * Get current available permits (for monitoring).
     * 
     * @return available permits
     */
    int getAvailablePermits();

    /**
     * Reset the limiter (for testing or config reload).
     */
    void reset();
}