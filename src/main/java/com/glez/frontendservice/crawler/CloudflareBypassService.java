package com.glez.frontendservice.crawler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Abstract Cloudflare/Anubis bypass service that can be reused by crawlers.
 * Provides session management, challenge solving, and cookie persistence.
 */
@Slf4j
@Service
public abstract class CloudflareBypassService {

    @Value("${app.crawler.flaresolverr-url:}")
    protected String flaresolverrUrl;

    @Value("${app.crawler.flaresolverr-timeout-seconds:60}")
    protected int flaresolverrTimeoutSeconds;

    @Value("${app.crawler.anubis-solve-enabled:true}")
    protected boolean anubisSolveEnabled;

    @Value("${app.crawler.anubis-max-difficulty:24}")
    protected int anubisMaxDifficulty;

    @Value("${app.crawler.session-cache-path:}")
    protected String sessionCachePath;

    public record CloudflareSession(
            String userAgent,
            String cookies,
            long obtainedAt
    ) {}

    public abstract CloudflareSession getSession(String baseUrl);

    public abstract void invalidateSession(String baseUrl);

    public abstract boolean isHealthy(String baseUrl);

    public boolean isAvailable() {
        return flaresolverrUrl != null && !flaresolverrUrl.isBlank();
    }

    public boolean isAnubisSolvingAvailable() {
        return anubisSolveEnabled;
    }

    public int getAnubisMaxDifficulty() {
        return anubisMaxDifficulty;
    }

    public String getFlaresolverrUrl() {
        return flaresolverrUrl;
    }

    public int getFlaresolverrTimeoutSeconds() {
        return flaresolverrTimeoutSeconds;
    }

    protected boolean isSessionValid(CloudflareSession session) {
        if (session == null) {
            return false;
        }
        return session.obtainedAt() > (System.currentTimeMillis() - Duration.ofMinutes(30).toMillis());
    }

    protected abstract void persistSession(String baseUrl, CloudflareSession session);

    protected abstract Optional<CloudflareSession> loadSession(String baseUrl);
}