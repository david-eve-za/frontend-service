package com.glez.frontendservice.config;

import com.glez.frontendservice.crawler.RateLimiter;
import com.glez.frontendservice.crawler.TokenBucketRateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Configuration for shared rate limiters.
 * Creates one TokenBucketRateLimiter per configured source.
 * All crawlers for the same source share the same limiter instance.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(CrawlerProperties.class)
public class RateLimiterConfig {

    private final CrawlerProperties properties;
    private final Map<String, RateLimiter> limiters = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    public RateLimiterConfig(CrawlerProperties properties) {
        this.properties = properties;
        this.scheduler = Executors.newScheduledThreadPool(
                2,
                new ThreadFactory() {
                    private final AtomicInteger counter = new AtomicInteger(0);
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "crawler-rate-limiter-" + counter.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    }
                }
        );
    }

    /**
     * Get or create a rate limiter for the given source ID.
     * Uses per-source config if available, falls back to global default.
     */
    public RateLimiter getLimiter(String sourceId) {
        return limiters.computeIfAbsent(sourceId, id -> {
            int rpm = getRateLimitForSource(id);
            log.info("Creating rate limiter for source '{}': {} RPM", id, rpm);
            return new TokenBucketRateLimiter(id, rpm, scheduler);
        });
    }

    /**
     * Get rate limit RPM for a source.
     * Priority: per-source config > global default > hardcoded 30
     */
    private int getRateLimitForSource(String sourceId) {
        // Check per-source config
        if (properties.getSources() != null) {
            CrawlerProperties.SourceConfig config = properties.getSources().get(sourceId);
            if (config != null && config.getRateLimitRpm() > 0) {
                return config.getRateLimitRpm();
            }
        }
        // Fall back to global default
        return properties.getDefaultRateLimitRpm();
    }

    /**
     * Primary bean for the global default rate limiter.
     * Used by crawlers that don't have a specific source ID.
     */
    @Bean
    @Primary
    public RateLimiter defaultRateLimiter() {
        return getLimiter("default");
    }

    /**
     * Bean for getting a limiter by source ID.
     * Crawlers can inject this and call getLimiter(sourceId).
     */
    @Bean
    public RateLimiterProvider rateLimiterProvider() {
        return this::getLimiter;
    }

    /**
     * Functional interface for getting rate limiters by source ID.
     */
    @FunctionalInterface
    public interface RateLimiterProvider {
        RateLimiter getLimiter(String sourceId);
    }

    /**
     * Shutdown scheduler on context close.
     */
    @Bean
    public RateLimiterShutdownHook shutdownHook() {
        return new RateLimiterShutdownHook(scheduler);
    }

    public static class RateLimiterShutdownHook {
        private final ScheduledExecutorService scheduler;
        public RateLimiterShutdownHook(ScheduledExecutorService scheduler) {
            this.scheduler = scheduler;
        }
        public void shutdown() {
            log.debug("Shutting down rate limiter scheduler");
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}