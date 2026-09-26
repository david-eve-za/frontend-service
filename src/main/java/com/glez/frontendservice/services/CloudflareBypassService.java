package com.glez.frontendservice.services;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.glez.frontendservice.exception.NovelsApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Provee una sesión de Cloudflare (user-agent + cookies) para alcanzar servidores
 * protegidos por challenges. Equivale a lo que hace cloudscraper en el script
 * original NovelsUpdater.py, ya que no existe un equivalente Java maduro.
 *
 * Orden de resolución:
 * 1. Sesión estática desde configuración (cf_clearance copiada del navegador).
 * 2. FlareSolverr como sidecar (https://github.com/FlareSolverr/FlareSolverr).
 * 3. Peticiones directas con user-agent de navegador (mejor esfuerzo).
 */
@Slf4j
@Service
public class CloudflareBypassService {

    public static final String DEFAULT_FIREFOX_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:139.0) Gecko/20100101 Firefox/139.0";

    public record CloudflareSession(String userAgent, String cookieHeader, Instant obtainedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FlareSolverrResponse(String status, String message, Solution solution) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Solution(String url, Integer status, String userAgent, List<FlareSolverrCookie> cookies) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FlareSolverrCookie(String name, String value) {
    }

    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String flaresolverrUrl;
    private final int flaresolverrTimeoutSeconds;
    private final String staticUserAgent;
    private final String staticClearanceCookie;
    private final long sessionTtlMillis;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private volatile CloudflareSession cachedSession;

    public CloudflareBypassService(ObjectMapper objectMapper,
                                   @Value("${app.novels.base-url}") String baseUrl,
                                   @Value("${app.novels.flaresolverr-url:}") String flaresolverrUrl,
                                   @Value("${app.novels.flaresolverr-timeout-seconds:60}") int flaresolverrTimeoutSeconds,
                                   @Value("${app.novels.static-cf-user-agent:}") String staticUserAgent,
                                   @Value("${app.novels.static-cf-clearance:}") String staticClearanceCookie,
                                   @Value("${app.novels.session-ttl-minutes:30}") long sessionTtlMinutes) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.flaresolverrUrl = flaresolverrUrl;
        this.flaresolverrTimeoutSeconds = flaresolverrTimeoutSeconds;
        this.staticUserAgent = staticUserAgent;
        this.staticClearanceCookie = staticClearanceCookie;
        this.sessionTtlMillis = Duration.ofMinutes(sessionTtlMinutes).toMillis();
    }

    public synchronized CloudflareSession getSession() {
        CloudflareSession current = cachedSession;
        if (isFresh(current)) {
            return current;
        }
        cachedSession = resolveSession();
        return cachedSession;
    }

    public void invalidate() {
        cachedSession = null;
    }

    private boolean isFresh(CloudflareSession session) {
        return session != null
                && Duration.between(session.obtainedAt(), Instant.now()).toMillis() < sessionTtlMillis;
    }

    private CloudflareSession resolveSession() {
        if (isConfigured(staticUserAgent) && isConfigured(staticClearanceCookie)) {
            log.debug("Using static Cloudflare session from configuration");
            // Accept either a bare cf_clearance value or a full Cookie header
            // (required when the site stacks additional challenges such as
            // Anubis, where cf_clearance alone is not enough).
            String cookieHeader = staticClearanceCookie.contains("=")
                    ? staticClearanceCookie
                    : "cf_clearance=" + staticClearanceCookie;
            return new CloudflareSession(staticUserAgent, cookieHeader, Instant.now());
        }
        if (isConfigured(flaresolverrUrl)) {
            try {
                CloudflareSession session = fetchFromFlareSolverr();
                log.info("Cloudflare session obtained through FlareSolverr");
                return session;
            } catch (Exception e) {
                log.warn("FlareSolverr call failed ({}), falling back to direct requests", e.getMessage());
            }
        } else {
            log.warn("No FlareSolverr URL and no static cf_clearance configured: direct requests will likely be blocked by Cloudflare");
        }
        return new CloudflareSession(DEFAULT_FIREFOX_USER_AGENT, null, Instant.now());
    }

    private boolean isConfigured(String value) {
        return value != null && !value.isBlank();
    }

    CloudflareSession fetchFromFlareSolverr() {
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "cmd", "request.get",
                    "url", baseUrl,
                    "maxTimeout", flaresolverrTimeoutSeconds * 1000));
            HttpRequest request = HttpRequest.newBuilder(URI.create(flaresolverrUrl))
                    .timeout(Duration.ofSeconds(flaresolverrTimeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new NovelsApiException("FlareSolverr returned HTTP " + response.statusCode());
            }
            return parseFlareSolverrResponse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NovelsApiException("FlareSolverr request interrupted", e);
        } catch (IOException e) {
            throw new NovelsApiException("FlareSolverr request failed: " + e.getMessage(), e);
        }
    }

    CloudflareSession parseFlareSolverrResponse(String body) {
        try {
            FlareSolverrResponse parsed = objectMapper.readValue(body, FlareSolverrResponse.class);
            if (!"ok".equals(parsed.status()) || parsed.solution() == null) {
                throw new NovelsApiException("FlareSolverr could not solve the challenge: " + parsed.message());
            }
            Solution solution = parsed.solution();
            if (solution.userAgent() == null || solution.userAgent().isBlank()) {
                throw new NovelsApiException("FlareSolverr response missing user agent");
            }
            String cookieHeader = solution.cookies() == null ? null : solution.cookies().stream()
                    .map(cookie -> cookie.name() + "=" + cookie.value())
                    .collect(Collectors.joining("; "));
            return new CloudflareSession(solution.userAgent(), cookieHeader, Instant.now());
        } catch (JacksonException e) {
            throw new NovelsApiException("Invalid FlareSolverr response: " + e.getMessage(), e);
        }
    }
}
