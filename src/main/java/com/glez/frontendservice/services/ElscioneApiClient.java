package com.glez.frontendservice.services;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.glez.frontendservice.exception.NovelsApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.LongConsumer;

/**
 * Cliente HTTP del servidor Elscione: replica el protocolo del script Python
 * NovelsUpdater.py (POST JSON {"action":"get","items":{...}} para listar y
 * GET streaming para descargar), incluyendo reintentos con backoff
 * exponencial y refresco de sesión de Cloudflare ante challenges (403/429/503).
 */
@Slf4j
@Service
public class ElscioneApiClient {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApiItem(String href, Long size) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ListRequest(String action, ItemsQuery items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ItemsQuery(String href, int what) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ListResponse(List<ApiItem> items) {
    }

    /**
     * Statuses that suggest a Cloudflare/Anubis challenge and must refresh
     * the session before retrying. 503 doubles as a retryable server error.
     */
    private static final Set<Integer> CHALLENGE_STATUSES = Set.of(403, 429, 503);

    /** Techo del sleep combinando backoff + Retry-After (5 min). */
    private static final long MAX_RETRY_SLEEP_MILLIS = Duration.ofMinutes(5).toMillis();

    private final CloudflareBypassService cloudflareBypass;
    private final ObjectMapper objectMapper;
    private final URI apiUri;
    private final String referer;
    private final int maxRetries;
    private final long retryBackoffMillis;
    private final int apiTimeoutSeconds;
    private final int downloadTimeoutSeconds;
    private final int chunkSize;

    /**
     * Espaciado mínimo entre CUALQUIER par de peticiones a elscione (listings
     * del sync, descargas del crawler y on-demand), compartido entre todos los
     * hilos. Anti-bloqueo de Cloudflare: elimina bursts y agrega jitter para
     * que el patrón de tráfico no sea metronómico. 0 lo desactiva.
     */
    private final long minRequestIntervalMillis;

    /** Siguiente instante permitido para una petición (guard por paceLock). */
    private long nextAllowedRequestAt;
    private final Object paceLock = new Object();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public ElscioneApiClient(CloudflareBypassService cloudflareBypass,
                             ObjectMapper objectMapper,
                             @Value("${app.novels.base-url}") String baseUrl,
                             @Value("${app.novels.max-retries:5}") int maxRetries,
                             @Value("${app.novels.retry-backoff-seconds:16}") long retryBackoffSeconds,
                             @Value("${app.novels.api-timeout-seconds:30}") int apiTimeoutSeconds,
                             @Value("${app.novels.download-timeout-seconds:60}") int downloadTimeoutSeconds,
                             @Value("${app.novels.chunk-size:65536}") int chunkSize,
                             @Value("${app.novels.min-request-interval-ms:2000}") long minRequestIntervalMillis) {
        this.cloudflareBypass = cloudflareBypass;
        this.objectMapper = objectMapper;
        URI base = URI.create(baseUrl);
        String origin = base.getScheme() + "://" + base.getAuthority();
        this.apiUri = URI.create(origin);
        this.referer = origin + "/";
        this.maxRetries = maxRetries;
        this.retryBackoffMillis = Duration.ofSeconds(retryBackoffSeconds).toMillis();
        this.apiTimeoutSeconds = apiTimeoutSeconds;
        this.downloadTimeoutSeconds = downloadTimeoutSeconds;
        this.chunkSize = chunkSize;
        this.minRequestIntervalMillis = minRequestIntervalMillis;
    }

    public String getFullUrl(String remotePath) {
        return apiUri + remotePath;
    }

    public List<ApiItem> listContents(String remotePath) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(new ListRequest("get", new ItemsQuery(remotePath, 1)));
        } catch (JacksonException e) {
            throw new NovelsApiException("Could not serialize listing request for " + remotePath, e);
        }
        String requestBody = payload;
        for (int sessionAttempts = 0; ; sessionAttempts++) {
            HttpResponse<String> response = executeWithRetries(
                    session -> HttpRequest.newBuilder(apiUri)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(requestBody)),
                    HttpResponse.BodyHandlers.ofString(), apiTimeoutSeconds);
            try {
                ListResponse parsed = objectMapper.readValue(response.body(), ListResponse.class);
                return parsed.items() == null ? List.of() : parsed.items();
            } catch (JacksonException e) {
                // A 2xx body that is not JSON is an HTML challenge page (e.g.
                // Anubis serves HTTP 200), invisible to the status-based
                // invalidation inside executeWithRetries: refresh the session
                // once and retry before giving up.
                if (sessionAttempts == 0) {
                    log.info("Non-JSON response for {} (challenge page?), refreshing session and retrying", remotePath);
                    cloudflareBypass.invalidate();
                    continue;
                }
                throw new NovelsApiException("Invalid JSON response for " + remotePath
                        + " (body starts: " + snippet(response.body()) + ")", e);
            }
        }
    }

    private String snippet(String body) {
        if (body == null) {
            return "<empty>";
        }
        String flattened = body.replaceAll("\\s+", " ").trim();
        return flattened.substring(0, Math.min(flattened.length(), 120));
    }

    public void downloadToFile(String url, Path target, LongConsumer progressConsumer) {
        HttpResponse<InputStream> response = executeWithRetries(
                session -> HttpRequest.newBuilder(URI.create(url)).GET(),
                HttpResponse.BodyHandlers.ofInputStream(), downloadTimeoutSeconds);
        Path partialFile = target.resolveSibling(target.getFileName() + ".part");
        try (InputStream body = response.body();
             OutputStream output = Files.newOutputStream(partialFile,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[chunkSize];
            int read;
            while ((read = body.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                if (progressConsumer != null) {
                    progressConsumer.accept(read);
                }
            }
        } catch (IOException e) {
            deleteQuietly(partialFile);
            throw new NovelsApiException("Download failed for " + url + ": " + e.getMessage(), e);
        }
        try {
            Files.move(partialFile, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            deleteQuietly(partialFile);
            throw new NovelsApiException("Could not finalize download " + target, e);
        }
    }

    @FunctionalInterface
    private interface SessionRequestFactory {
        HttpRequest.Builder create(CloudflareBypassService.CloudflareSession session);
    }

    private <T> HttpResponse<T> executeWithRetries(SessionRequestFactory requestFactory,
                                                   HttpResponse.BodyHandler<T> bodyHandler,
                                                   int timeoutSeconds) {
        int retries = 0;
        while (true) {
            paceRequest();
            CloudflareBypassService.CloudflareSession session = cloudflareBypass.getSession();
            HttpRequest request = applyBrowserHeaders(requestFactory.create(session), session)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .build();
            try {
                HttpResponse<T> response = httpClient.send(request, bodyHandler);
                int statusCode = response.statusCode();
                if (statusCode >= 200 && statusCode < 300) {
                    return response;
                }
                closeErrorBody(response);
                if (CHALLENGE_STATUSES.contains(statusCode)) {
                    log.info("Cloudflare challenge suspected (HTTP {}) for {}, refreshing session", statusCode, request.uri());
                    cloudflareBypass.invalidate();
                }
                // Every 5xx is a server-side failure worth retrying with
                // backoff: the classic 500-504 plus the Cloudflare origin
                // errors (520 unknown error, 521 web server down, 522/524
                // timeouts, 523 unreachable, 525/526 SSL, 530 error 1XXX).
                // 4xx are client errors and never retry (except challenges).
                if (statusCode >= 500 || CHALLENGE_STATUSES.contains(statusCode)) {
                    if (++retries > maxRetries) {
                        throw new NovelsApiException("HTTP " + statusCode + " after "
                                + maxRetries + " retries for " + request.uri());
                    }
                    log.debug("HTTP {} for {} (retry {}/{}), backing off", statusCode, request.uri(), retries, maxRetries);
                    sleepBackoff(retrySleepMillis(retries, response));
                    continue;
                }
                throw new NovelsApiException("Unexpected HTTP status " + statusCode + " for " + request.uri());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NovelsApiException("Request interrupted: " + request.uri(), e);
            } catch (IOException e) {
                if (++retries > maxRetries) {
                    throw new NovelsApiException("Request failed after " + maxRetries
                            + " retries for " + request.uri() + ": " + e.getMessage(), e);
                }
                log.debug("I/O error for {} (retry {}/{}): {}", request.uri(), retries, maxRetries, e.getMessage());
                sleepBackoff(Math.min(retryBackoffMillis * (1L << Math.min(retries - 1, 16)), MAX_RETRY_SLEEP_MILLIS));
            }
        }
    }

    private HttpRequest.Builder applyBrowserHeaders(HttpRequest.Builder builder,
                                                    CloudflareBypassService.CloudflareSession session) {
        builder.header("User-Agent", session.userAgent())
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .header("Referer", referer)
                .header("Upgrade-Insecure-Requests", "1")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-User", "?1");
        if (session.cookieHeader() != null && !session.cookieHeader().isBlank()) {
            builder.header("Cookie", session.cookieHeader());
        }
        return builder;
    }

    /**
     * Pacer anti-bloqueo compartido por todos los hilos: ninguna petición sale
     * antes de que transcurra el intervalo mínimo (con jitter aleatorio del
     * ±25%) desde la petición anterior. Sin él, el pool de descargas dispara
     * ráfagas de ~10 req/s que disparan las heurísticas de rate de Cloudflare.
     * El sleep es interrumpible para no bloquear cancelaciones.
     */
    void paceRequest() {
        if (minRequestIntervalMillis <= 0) {
            return;
        }
        long jitter = Math.round((Math.random() - 0.5) * 0.5 * minRequestIntervalMillis);
        long interval = minRequestIntervalMillis + jitter;
        long sleepMillis;
        synchronized (paceLock) {
            long now = System.currentTimeMillis();
            sleepMillis = Math.max(0, nextAllowedRequestAt - now);
            nextAllowedRequestAt = Math.max(nextAllowedRequestAt, now) + interval;
        }
        if (sleepMillis > 0) {
            log.debug("Pacing request: waiting {}ms (min interval {}ms)", sleepMillis, interval);
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NovelsApiException("Pacer wait interrupted", e);
            }
        }
    }

    /**
     * Backoff del reintento n (exponencial: base * 2^(n-1)), ampliado al valor
     * del header Retry-After cuando el servidor lo envía (429/503) y con un
     * techo total de 5 minutos.
     */
    private long retrySleepMillis(int retryNumber, HttpResponse<?> response) {
        long backoff = retryBackoffMillis * (1L << Math.min(retryNumber - 1, 16));
        long retryAfter = retryAfterMillis(response);
        return Math.min(Math.max(backoff, retryAfter), MAX_RETRY_SLEEP_MILLIS);
    }

    /** Lee Retry-After en segundos; ignora el formato HTTP-date. */
    long retryAfterMillis(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After")
                .flatMap(value -> {
                    try {
                        return java.util.Optional.of(Long.parseLong(value.trim()) * 1000L);
                    } catch (NumberFormatException e) {
                        return java.util.Optional.<Long>empty();
                    }
                })
                .orElse(0L);
    }

    private void sleepBackoff(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NovelsApiException("Retry wait interrupted", e);
        }
    }

    private void closeErrorBody(HttpResponse<?> response) {
        if (response.body() instanceof InputStream stream) {
            try {
                stream.close();
            } catch (IOException e) {
                log.debug("Could not close error stream: {}", e.getMessage());
            }
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete partial file {}: {}", path, e.getMessage());
        }
    }
}
