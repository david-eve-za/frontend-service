package com.glez.frontendservice.config;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiter configuration for crawlers.
 * Provides per-source rate limiting using Resilience4j.
 */
@Configuration
public class RateLimiterConfig {

    private final Map<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();

    /**
     * Gets or creates a rate limiter for the given source.
     *
     * @param sourceId the source identifier
     * @param rateLimitRpm requests per minute limit (0 = unlimited)
     * @return the rate limiter for the source
     */
    public RateLimiter getRateLimiter(String sourceId, int rateLimitRpm) {
        return rateLimiters.computeIfAbsent(sourceId, id -> {
            if (rateLimitRpm <= 0) {
                return RateLimiter.of(id, RateLimiterConfig.custom()
                        .limitForPeriod(Integer.MAX_VALUE)
                        .limitRefreshPeriod(Duration.ofMinutes(1))
                        .build());
            }
            return RateLimiter.of(id, RateLimiterConfig.custom()
                    .limitForPeriod(rateLimitRpm)
                    .limitRefreshPeriod(Duration.ofMinutes(1))
                    .timeoutDuration(Duration.ofSeconds(30))
                    .build());
        });
    }

    /**
     * Executes a task with rate limiting for the given source.
     *
     * @param sourceId the source identifier
     * @param rateLimitRpm requests per minute limit
     * @param task the task to execute
     * @param <T> the return type
     * @return the result of the task
     * @throws Exception if rate limit is exceeded or task fails
     */
    public <T> T executeWithRateLimit(String sourceId, int rateLimitRpm, java.util.function.Supplier<T> task) throws Exception {
        RateLimiter rateLimiter = getRateLimiter(sourceId, rateLimitRpm);
        return RateLimiter.decorateSupplier(rateLimiter, task).get();
    }

    /**
     * Executes a runnable with rate limiting for the given source.
     *
     * @param sourceId the source identifier
     * @param rateLimitRpm requests per minute limit
     * @param task the task to execute
     * @throws Exception if rate limit is exceeded or task fails
     */
    public void executeWithRateLimit(String sourceId, int rateLimitRpm, Runnable task) throws Exception {
        RateLimiter rateLimiter = getRateLimiter(sourceId, rateLimitRpm);
        RateLimiter.decorateRunnable(rateLimiter, task).run();
    }
}