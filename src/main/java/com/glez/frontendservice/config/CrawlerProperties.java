package com.glez.frontendservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Configuration properties for crawler system.
 * Bound to {@code app.crawler} in application.yml.
 */
@ConfigurationProperties(prefix = "app.crawler")
public class CrawlerProperties {

    /** Global default rate limit (requests per minute) */
    private int defaultRateLimitRpm = 30;

    /** Global default HTTP timeout (seconds) */
    private int defaultTimeoutSeconds = 30;

    /** Global default User-Agent */
    private String defaultUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

    /** FlareSolverr URL for Cloudflare bypass */
    private String flaresolverrUrl = "http://localhost:8191/v1";

    /** FlareSolverr timeout (seconds) */
    private int flaresolverrTimeoutSeconds = 60;

    /** Anubis solver enabled */
    private boolean anubisSolveEnabled = true;

    /** Anubis max difficulty */
    private int anubisMaxDifficulty = 24;

    /** Session cache file path */
    private String sessionCachePath = ".crawler-sessions.json";

    /** Per-source configuration overrides */
    private Map<String, SourceConfig> sources;

    public int getDefaultRateLimitRpm() { return defaultRateLimitRpm; }
    public void setDefaultRateLimitRpm(int defaultRateLimitRpm) { this.defaultRateLimitRpm = defaultRateLimitRpm; }

    public int getDefaultTimeoutSeconds() { return defaultTimeoutSeconds; }
    public void setDefaultTimeoutSeconds(int defaultTimeoutSeconds) { this.defaultTimeoutSeconds = defaultTimeoutSeconds; }

    public String getDefaultUserAgent() { return defaultUserAgent; }
    public void setDefaultUserAgent(String defaultUserAgent) { this.defaultUserAgent = defaultUserAgent; }

    public String getFlaresolverrUrl() { return flaresolverrUrl; }
    public void setFlaresolverrUrl(String flaresolverrUrl) { this.flaresolverrUrl = flaresolverrUrl; }

    public int getFlaresolverrTimeoutSeconds() { return flaresolverrTimeoutSeconds; }
    public void setFlaresolverrTimeoutSeconds(int flaresolverrTimeoutSeconds) { this.flaresolverrTimeoutSeconds = flaresolverrTimeoutSeconds; }

    public boolean isAnubisSolveEnabled() { return anubisSolveEnabled; }
    public void setAnubisSolveEnabled(boolean anubisSolveEnabled) { this.anubisSolveEnabled = anubisSolveEnabled; }

    public int getAnubisMaxDifficulty() { return anubisMaxDifficulty; }
    public void setAnubisMaxDifficulty(int anubisMaxDifficulty) { this.anubisMaxDifficulty = anubisMaxDifficulty; }

    public String getSessionCachePath() { return sessionCachePath; }
    public void setSessionCachePath(String sessionCachePath) { this.sessionCachePath = sessionCachePath; }

    public Map<String, SourceConfig> getSources() { return sources; }
    public void setSources(Map<String, SourceConfig> sources) { this.sources = sources; }

    /**
     * Per-source configuration.
     */
    public static class SourceConfig {
        private String baseUrl;
        private int rateLimitRpm;
        private boolean cloudflareBypass;
        private boolean enabled = true;
        private java.util.List<String> tags = java.util.List.of();

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public int getRateLimitRpm() { return rateLimitRpm; }
        public void setRateLimitRpm(int rateLimitRpm) { this.rateLimitRpm = rateLimitRpm; }

        public boolean isCloudflareBypass() { return cloudflareBypass; }
        public void setCloudflareBypass(boolean cloudflareBypass) { this.cloudflareBypass = cloudflareBypass; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public java.util.List<String> getTags() { return tags; }
        public void setTags(java.util.List<String> tags) { this.tags = tags; }

        @Override
        public String toString() {
            return "SourceConfig{" +
                    "baseUrl='" + baseUrl + '\'' +
                    ", rateLimitRpm=" + rateLimitRpm +
                    ", cloudflareBypass=" + cloudflareBypass +
                    ", enabled=" + enabled +
                    ", tags=" + tags +
                    '}';
        }
    }
}