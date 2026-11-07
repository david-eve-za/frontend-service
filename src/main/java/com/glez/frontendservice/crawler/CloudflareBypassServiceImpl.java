package com.glez.frontendservice.crawler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glez.frontendservice.config.CrawlerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Concrete implementation of Cloudflare/Anubis bypass service.
 * Shared across all crawlers that need Cloudflare protection bypass.
 * 
 * Features:
 * - FlareSolverr integration for Cloudflare challenge solving
 * - Anubis proof-of-work solver (Java-native)
 * - Persistent session cache (cookies + user agent)
 * - Automatic session validation and renewal
 */
@Slf4j
@Service
@EnableConfigurationProperties(CrawlerProperties.class)
public class CloudflareBypassServiceImpl extends CloudflareBypassService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final Map<String, CloudflareSession> sessionCache = new ConcurrentHashMap<>();
    private final Path sessionCachePath;

    public CloudflareBypassServiceImpl(CrawlerProperties properties, WebClient.Builder webClientBuilder) {
        super();
        
        // Initialize from properties (parent class fields are protected)
        this.flaresolverrUrl = properties.getFlaresolverrUrl();
        this.flaresolverrTimeoutSeconds = properties.getFlaresolverrTimeoutSeconds();
        this.anubisSolveEnabled = properties.isAnubisSolveEnabled();
        this.anubisMaxDifficulty = properties.getAnubisMaxDifficulty();
        this.sessionCachePath = properties.getSessionCachePath() != null 
                ? Path.of(properties.getSessionCachePath()) 
                : null;
        
        this.webClient = webClientBuilder
                .baseUrl(flaresolverrUrl)
                .defaultHeader("Content-Type", "application/json")
                .build();
        
        this.objectMapper = new ObjectMapper();
        
        // Load persisted sessions
        loadPersistedSessions();
        
        log.info("CloudflareBypassService initialized: flaresolverr={}, anubis={}, cache={}", 
                flaresolverrUrl, anubisSolveEnabled, sessionCachePath);
    }

    @Override
    public CloudflareSession getSession(String baseUrl) {
        // 1. Check memory cache
        CloudflareSession cached = sessionCache.get(baseUrl);
        if (cached != null && isSessionValid(cached)) {
            log.debug("Using cached session for {}", baseUrl);
            return cached;
        }
        
        // 2. Try to load from disk cache
        Optional<CloudflareSession> diskSession = loadSession(baseUrl);
        if (diskSession.isPresent() && isSessionValid(diskSession.get())) {
            sessionCache.put(baseUrl, diskSession.get());
            log.debug("Loaded session from disk for {}", baseUrl);
            return diskSession.get();
        }
        
        // 3. Need to create new session via FlareSolverr
        log.info("Creating new FlareSolverr session for {}", baseUrl);
        CloudflareSession newSession = createSessionViaFlaresolverr(baseUrl);
        if (newSession != null) {
            sessionCache.put(baseUrl, newSession);
            persistSession(baseUrl, newSession);
            return newSession;
        }
        
        return null;
    }

    @Override
    public void invalidateSession(String baseUrl) {
        log.debug("Invalidating session for {}", baseUrl);
        sessionCache.remove(baseUrl);
        if (sessionCachePath != null) {
            Path sessionFile = sessionCachePath.resolve(baseUrl.replaceAll("[^a-zA-Z0-9.-]", "_") + ".json");
            try {
                Files.deleteIfExists(sessionFile);
            } catch (IOException e) {
                log.warn("Failed to delete session file for {}: {}", baseUrl, e.getMessage());
            }
        }
    }

    @Override
    public boolean isHealthy(String baseUrl) {
        try {
            CloudflareSession session = getSession(baseUrl);
            if (session == null) {
                return false;
            }
            
            // Probe the target with the session
            WebClient probeClient = WebClient.builder()
                    .baseUrl(baseUrl)
                    .defaultHeader("User-Agent", session.userAgent())
                    .defaultCookie("cf_clearance", extractClearanceCookie(session.cookies()))
                    .build();
            
            String response = probeClient.get()
                    .uri("/")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(10));
            
            boolean healthy = response != null && !response.contains("challenge") && !response.contains("Checking your browser");
            if (!healthy) {
                log.warn("Health check failed for {}: got challenge page", baseUrl);
                invalidateSession(baseUrl);
            }
            return healthy;
            
        } catch (Exception e) {
            log.warn("Health check error for {}: {}", baseUrl, e.getMessage());
            invalidateSession(baseUrl);
            return false;
        }
    }

    /**
     * Create a new session via FlareSolverr.
     * Navigates twice to ensure cf_clearance cookie is captured.
     */
    private CloudflareSession createSessionViaFlaresolverr(String baseUrl) {
        if (!isAvailable()) {
            log.warn("FlareSolverr not available (no URL configured)");
            return null;
        }
        
        try {
            // First navigation - solves challenge
            Map<String, Object> request1 = Map.of(
                    "cmd", "request.get",
                    "url", baseUrl,
                    "maxTimeout", flaresolverrTimeoutSeconds * 1000
            );
            
            JsonNode response1 = webClient.post()
                    .uri("/v1")
                    .body(BodyInserters.fromValue(request1))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(flaresolverrTimeoutSeconds));
            
            if (response1 == null || !response1.has("solution")) {
                log.error("FlareSolverr first request failed: {}", response1);
                return null;
            }
            
            JsonNode solution1 = response1.get("solution");
            String userAgent = solution1.get("userAgent").asText();
            String cookies = solution1.get("cookies").asText();
            
            // Second navigation - gets complete cookie jar with cf_clearance
            Map<String, Object> request2 = Map.of(
                    "cmd", "request.get",
                    "url", baseUrl,
                    "maxTimeout", flaresolverrTimeoutSeconds * 1000,
                    "session", "bypass-session"
            );
            
            JsonNode response2 = webClient.post()
                    .uri("/v1")
                    .body(BodyInserters.fromValue(request2))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(flaresolverrTimeoutSeconds));
            
            if (response2 == null || !response2.has("solution")) {
                log.error("FlareSolverr second request failed: {}", response2);
                return null;
            }
            
            JsonNode solution2 = response2.get("solution");
            String finalCookies = solution2.get("cookies").asText();
            
            // Extract cf_clearance cookie
            String clearanceCookie = extractClearanceCookie(finalCookies);
            if (clearanceCookie == null) {
                log.warn("No cf_clearance cookie in FlareSolverr response for {}", baseUrl);
            }
            
            log.info("FlareSolverr session created for {} (clearance: {})", 
                    baseUrl, clearanceCookie != null ? "yes" : "no");
            
            return new CloudflareSession(userAgent, finalCookies, System.currentTimeMillis());
            
        } catch (WebClientResponseException e) {
            log.error("FlareSolverr HTTP error for {}: {} {}", baseUrl, e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("FlareSolverr error for {}: {}", baseUrl, e.getMessage());
        }
        
        return null;
    }

    /**
     * Extract cf_clearance cookie from cookie string.
     */
    private String extractClearanceCookie(String cookies) {
        if (cookies == null) return null;
        for (String cookie : cookies.split(";")) {
            cookie = cookie.trim();
            if (cookie.startsWith("cf_clearance=")) {
                return cookie.substring("cf_clearance=".length());
            }
        }
        return null;
    }

    @Override
    protected void persistSession(String baseUrl, CloudflareSession session) {
        if (sessionCachePath == null) return;
        
        try {
            Files.createDirectories(sessionCachePath);
            Path sessionFile = sessionCachePath.resolve(baseUrl.replaceAll("[^a-zA-Z0-9.-]", "_") + ".json");
            
            Map<String, Object> data = Map.of(
                    "userAgent", session.userAgent(),
                    "cookies", session.cookies(),
                    "obtainedAt", session.obtainedAt()
            );
            
            String json = objectMapper.writeValueAsString(data);
            Files.writeString(sessionFile, json);
            log.debug("Persisted session for {} to {}", baseUrl, sessionFile);
            
        } catch (IOException e) {
            log.warn("Failed to persist session for {}: {}", baseUrl, e.getMessage());
        }
    }

    @Override
    protected Optional<CloudflareSession> loadSession(String baseUrl) {
        if (sessionCachePath == null) return Optional.empty();
        
        Path sessionFile = sessionCachePath.resolve(baseUrl.replaceAll("[^a-zA-Z0-9.-]", "_") + ".json");
        if (!Files.exists(sessionFile)) return Optional.empty();
        
        try {
            String json = Files.readString(sessionFile);
            JsonNode node = objectMapper.readTree(json);
            
            String userAgent = node.get("userAgent").asText();
            String cookies = node.get("cookies").asText();
            long obtainedAt = node.get("obtainedAt").asLong();
            
            return Optional.of(new CloudflareSession(userAgent, cookies, obtainedAt));
            
        } catch (IOException e) {
            log.warn("Failed to load session for {}: {}", baseUrl, e.getMessage());
            return Optional.empty();
        }
    }

    private void loadPersistedSessions() {
        if (sessionCachePath == null || !Files.exists(sessionCachePath)) return;
        
        try {
            Files.list(sessionCachePath)
                    .filter(p -> p.toString().endsWith(".json"))
                    .forEach(p -> {
                        try {
                            String json = Files.readString(p);
                            JsonNode node = objectMapper.readTree(json);
                            String baseUrl = p.getFileName().toString().replace(".json", "");
                            // Reverse the sanitization (approximate)
                            // We can't perfectly reverse, so we just log and let getSession handle it
                            log.debug("Found persisted session file: {}", p);
                        } catch (IOException e) {
                            log.debug("Could not read session file {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to list session cache: {}", e.getMessage());
        }
    }
}