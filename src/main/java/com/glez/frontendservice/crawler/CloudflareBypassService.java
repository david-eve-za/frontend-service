package com.glez.frontendservice.crawler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Abstract Cloudflare/Anubis bypass service that can be reused by crawlers.
 * Provides session management, challenge solving, and cookie persistence.
 * 
 * Supports both:
 * - Multi-source API (with baseUrl parameter) - preferred for SourceCrawler framework
 * - Legacy single-source API (no-arg methods) - for backward compatibility with ElscioneApiClient
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

    // Legacy config (for ElscioneApiClient backward compat)
    @Value("${app.novels.flaresolverr-url:}")
    protected String legacyFlaresolverrUrl;

    @Value("${app.novels.flaresolverr-session:}")
    protected String legacyFlaresolverrSession;

    @Value("${app.novels.flaresolverr-timeout-seconds:60}")
    protected int legacyFlaresolverrTimeoutSeconds;

    @Value("${app.novels.anubis-solve-enabled:true}")
    protected boolean legacyAnubisSolveEnabled;

    @Value("${app.novels.anubis-max-difficulty:24}")
    protected int legacyAnubisMaxDifficulty;

    @Value("${app.novels.session-cache-path:}")
    protected String legacySessionCachePath;

    @Value("${app.novels.base-url:}")
    protected String legacyBaseUrl;

    public record CloudflareSession(
            String userAgent,
            String cookies,
            long obtainedAt
    ) {
        // Legacy compatibility: cookieHeader field expected by ElscioneApiClient
        public String cookieHeader() {
            return cookies;
        }
    }

    // ==================== Multi-source API (preferred) ====================

    public abstract CloudflareSession getSession(String baseUrl);

    public abstract void invalidateSession(String baseUrl);

    public abstract boolean isHealthy(String baseUrl);

    // ==================== Legacy single-source API (backward compat) ====================

    /**
     * @deprecated Use {@link #getSession(String)} with explicit baseUrl
     */
    @Deprecated
    public CloudflareSession getSession() {
        String baseUrl = getLegacyBaseUrl();
        return getSession(baseUrl);
    }

    /**
     * @deprecated Use {@link #invalidateSession(String)} with explicit baseUrl
     */
    @Deprecated
    public void invalidate() {
        String baseUrl = getLegacyBaseUrl();
        invalidateSession(baseUrl);
    }

    /**
     * @deprecated Use {@link #isHealthy(String)} with explicit baseUrl
     */
    @Deprecated
    public boolean isHealthy() {
        String baseUrl = getLegacyBaseUrl();
        return isHealthy(baseUrl);
    }

    private String getLegacyBaseUrl() {
        if (legacyBaseUrl != null && !legacyBaseUrl.isBlank()) {
            return legacyBaseUrl;
        }
        // Fallback to first configured source or default
        return "https://server.elscione.com/Officially%20Translated%20Light%20Novels/";
    }

    // ==================== Common properties ====================

    public boolean isAvailable() {
        String url = flaresolverrUrl != null && !flaresolverrUrl.isBlank() ? flaresolverrUrl : legacyFlaresolverrUrl;
        return url != null && !url.isBlank();
    }

    public boolean isAnubisSolvingAvailable() {
        return anubisSolveEnabled || legacyAnubisSolveEnabled;
    }

    public int getAnubisMaxDifficulty() {
        return anubisMaxDifficulty > 0 ? anubisMaxDifficulty : legacyAnubisMaxDifficulty;
    }

    public String getFlaresolverrUrl() {
        return flaresolverrUrl != null && !flaresolverrUrl.isBlank() ? flaresolverrUrl : legacyFlaresolverrUrl;
    }

    public int getFlaresolverrTimeoutSeconds() {
        return flaresolverrTimeoutSeconds > 0 ? flaresolverrTimeoutSeconds : legacyFlaresolverrTimeoutSeconds;
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