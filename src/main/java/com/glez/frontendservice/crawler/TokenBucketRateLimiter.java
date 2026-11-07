package com.glez.frontendservice.crawler;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Token bucket rate limiter implementation.
 * 
 * Tokens are added at a fixed rate (rateLimitRpm / 60 per second).
 * Each request consumes 1 token.
 * Burst capacity equals the rate limit (allows 1 minute of burst at max rate).
 */
@Slf4j
public class TokenBucketRateLimiter implements RateLimiter {

    private final String sourceId;
    private final int rateLimitRpm;
    private final double tokensPerSecond;
    private final long refillIntervalNanos;
    
    // Atomic state for lock-free operation
    private final AtomicLong tokens;
    private final AtomicLong lastRefillTime;
    
    // For async waiting
    private final ScheduledExecutorService scheduler;

    public TokenBucketRateLimiter(String sourceId, int rateLimitRpm, ScheduledExecutorService scheduler) {
        this.sourceId = sourceId;
        this.rateLimitRpm = rateLimitRpm;
        this.tokensPerSecond = rateLimitRpm / 60.0;
        this.refillIntervalNanos = (long) (1_000_000_000L / tokensPerSecond);
        this.tokens = new AtomicLong(rateLimitRpm); // Start with full bucket (burst capacity)
        this.lastRefillTime = new AtomicLong(System.nanoTime());
        this.scheduler = scheduler;
        
        log.debug("Created TokenBucketRateLimiter for {}: {} RPM ({} tokens/sec, refill every {} ns)", 
                sourceId, rateLimitRpm, tokensPerSecond, refillIntervalNanos);
    }

    @Override
    public boolean acquire(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        
        while (true) {
            refill();
            
            long currentTokens = tokens.get();
            if (currentTokens >= 1) {
                // Try to consume a token atomically
                if (tokens.compareAndSet(currentTokens, currentTokens - 1)) {
                    return true;
                }
                // CAS failed, retry
                continue;
            }
            
            // No tokens available, check timeout
            long now = System.nanoTime();
            if (now >= deadline) {
                return false;
            }
            
            // Calculate time until next token
            long nextTokenNanos = lastRefillTime.get() + refillIntervalNanos;
            long waitNanos = Math.min(nextTokenNanos - now, deadline - now);
            
            if (waitNanos > 0) {
                LockSupport.parkNanos(this, waitNanos);
            }
        }
    }

    @Override
    public CompletableFuture<Void> acquireAsync() {
        return CompletableFuture.supplyAsync(() -> {
            acquire(Duration.ofMinutes(5)); // 5 min max wait
            return null;
        }, runnable -> scheduler.execute(runnable));
    }

    @Override
    public boolean tryAcquire() {
        refill();
        long currentTokens = tokens.get();
        if (currentTokens >= 1) {
            return tokens.compareAndSet(currentTokens, currentTokens - 1);
        }
        return false;
    }

    @Override
    public int getRateLimitRpm() {
        return rateLimitRpm;
    }

    @Override
    public String getSourceId() {
        return sourceId;
    }

    @Override
    public int getAvailablePermits() {
        refill();
        return Math.min((int) tokens.get(), rateLimitRpm);
    }

    @Override
    public void reset() {
        tokens.set(rateLimitRpm);
        lastRefillTime.set(System.nanoTime());
    }

    /**
     * Refill tokens based on elapsed time.
     * Uses atomic operations for thread safety.
     */
    private void refill() {
        long now = System.nanoTime();
        long lastRefill = lastRefillTime.get();
        
        // Calculate elapsed refill intervals
        long elapsedIntervals = (now - lastRefill) / refillIntervalNanos;
        if (elapsedIntervals <= 0) {
            return;
        }
        
        // Try to update lastRefillTime atomically
        if (!lastRefillTime.compareAndSet(lastRefill, lastRefill + elapsedIntervals * refillIntervalNanos)) {
            // Another thread already refilled, retry
            refill();
            return;
        }
        
        // Add tokens (capped at bucket capacity = rateLimitRpm)
        long currentTokens = tokens.get();
        long newTokens = Math.min(currentTokens + elapsedIntervals, rateLimitRpm);
        tokens.set(newTokens);
    }
}