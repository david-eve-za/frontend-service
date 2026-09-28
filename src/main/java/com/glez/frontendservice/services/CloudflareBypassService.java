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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Provee una sesión válida contra el servidor de novelas, que apila dos capas
 * anti-bot sobre el mismo origen. Adquisición 100% dinámica (no hay sesión
 * estática manual): cada sesión se minta, verifica y conserva
 * automáticamente.
 *
 * 1. Cloudflare (managed challenge): FlareSolverr como sidecar navega con un
 *    navegador real y aporta cf_clearance + user agent. Con una sesión
 *    persistente y doble navegación se cosecha el jar completo: la primera
 *    navegación puede resolver el challenge DESPUÉS de que FlareSolverr lee
 *    las cookies, por lo que la segunda ya las devuelve todas. FlareSolverr
 *    no detecta el challenge ("Challenge not detected!") pero aun así deja
 *    el clearance en el contexto de sesión.
 * 2. Anubis (proof-of-work, within.website): FlareSolverr NO puede con esta
 *    capa: devuelve la página del challenge como si fuera contenido, antes
 *    de que el PoW del navegador termine, y por tanto nunca entrega la
 *    cookie de autorización. Se resuelve directamente en Java: el challenge
 *    viene embebido en el HTML del interstitial (id + randomData +
 *    difficulty), el PoW es SHA-256(randomData + nonce) con al menos
 *    "difficulty" nibbles hexadecimales en cero al inicio del digest, y la
 *    redención es un GET a /.within.website/x/cmd/anubis/api/pass-challenge
 *    que responde 302 + Set-Cookie con un JWT.
 *
 * Ruta de adquisición (session-cache-path / session-acquisition-attempts):
 * 1. Caché en memoria (TTL 30 min) — cero peticiones.
 * 2. Última sesión buena persistida en disco (solo si su edad < TTL): se
 *    SONDEA contra el origen; el probe es la única fuente de verdad — si
 *    pasa ambas capas se reutiliza sin tocar FlareSolverr, si no se descarta.
 * 3. Mint: FlareSolverr (sesión persistente + doble navegación) + PoW de
 *    Anubis + verificación final; solo las sesiones VERIFICADAS se
 *    persisten de vuelta al disco.
 * 4. Best-effort si todo falla: el reintento de ElscioneApiClient
 *    (challenge/403/429/503 con backoff exponencial) invalida y vuelve a
 *    entrar aquí.
 *
 * La persistencia es best-effort: cualquier fallo de I/O se registra y se
 * continúa sin romper la resolución. El archivo contiene cookies vivas y
 * por eso se mantiene fuera de git.
 */
@Slf4j
@Service
public class CloudflareBypassService {

    public static final String DEFAULT_FIREFOX_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:139.0) Gecko/20100101 Firefox/139.0";

    private static final Duration ANUBIS_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** Ruta del endpoint de redención de Anubis (relativa al base prefix). */
    private static final String ANUBIS_PASS_CHALLENGE_PATH = "/.within.website/x/cmd/anubis/api/pass-challenge";

    /** Script tag del interstitial de Anubis con el challenge embebido como JSON. */
    private static final Pattern ANUBIS_CHALLENGE_TAG = Pattern.compile(
            "<script id=\"anubis_challenge\" type=\"application/json\">(.*?)</script>", Pattern.DOTALL);

    private static final Pattern ANUBIS_BASE_PREFIX_TAG = Pattern.compile(
            "<script id=\"anubis_base_prefix\" type=\"application/json\">\"(.*?)\"\\s*</script>", Pattern.DOTALL);

    private static final Pattern ANUBIS_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ANUBIS_RANDOM_DATA = Pattern.compile("\"randomData\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ANUBIS_DIFFICULTY = Pattern.compile("\"difficulty\"\\s*:\\s*(\\d+)");

    public record CloudflareSession(String userAgent, String cookieHeader, Instant obtainedAt) {
    }

    /** Challenge de Anubis extraído del interstitial HTML. */
    record AnubisChallenge(String id, String randomData, int difficulty, String basePrefix) {
    }

    /** Resultado de la etapa de Anubis: sesión con cookies fusionadas y verificación del probe. */
    private record StageResult(CloudflareSession session, boolean verified) {
    }

    /** Formato en disco de la última sesión buena (epochMillis evita serializar Instant). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PersistedSession(String userAgent, String cookieHeader, long obtainedAtEpochMillis) {
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
    private final String flaresolverrSession;
    private final boolean anubisSolveEnabled;
    private final int anubisMaxDifficulty;
    private final Path sessionCachePath;
    private final int sessionAcquisitionAttempts;
    private final long sessionTtlMillis;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * Cliente para la etapa de Anubis: sin seguir redirects, para poder
     * capturar el Set-Cookie del 302 de pass-challenge antes de que el
     * cliente lo consuma.
     */
    private final HttpClient challengeHttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private volatile CloudflareSession cachedSession;

    public CloudflareBypassService(ObjectMapper objectMapper,
                                   @Value("${app.novels.base-url}") String baseUrl,
                                   @Value("${app.novels.flaresolverr-url:}") String flaresolverrUrl,
                                   @Value("${app.novels.flaresolverr-timeout-seconds:60}") int flaresolverrTimeoutSeconds,
                                   @Value("${app.novels.flaresolverr-session:}") String flaresolverrSession,
                                   @Value("${app.novels.anubis-solve-enabled:false}") boolean anubisSolveEnabled,
                                   @Value("${app.novels.anubis-max-difficulty:24}") int anubisMaxDifficulty,
                                   @Value("${app.novels.session-cache-path:}") String sessionCachePath,
                                   @Value("${app.novels.session-acquisition-attempts:2}") int sessionAcquisitionAttempts,
                                   @Value("${app.novels.session-ttl-minutes:30}") long sessionTtlMinutes) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.flaresolverrUrl = flaresolverrUrl;
        this.flaresolverrTimeoutSeconds = flaresolverrTimeoutSeconds;
        this.flaresolverrSession = flaresolverrSession;
        this.anubisSolveEnabled = anubisSolveEnabled;
        this.anubisMaxDifficulty = anubisMaxDifficulty;
        this.sessionCachePath = isConfigured(sessionCachePath) ? Path.of(sessionCachePath) : null;
        this.sessionAcquisitionAttempts = sessionAcquisitionAttempts;
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

    // ------------------------------------------------------------------
    // Ruta de adquisición dinámica
    // ------------------------------------------------------------------

    private CloudflareSession resolveSession() {
        // Paso 2 de la jerarquía: reusar la última sesión buena persistida,
        // validándola con el probe (nunca confiar en una sesión no sondeada).
        if (anubisSolveEnabled) {
            CloudflareSession persisted = loadPersistedSession();
            if (persisted != null) {
                StageResult probed = solveAnubisChallengeIfNeeded(persisted);
                if (probed.verified()) {
                    log.info("Persisted session verified by probe; reusing without re-minting");
                    return probed.session();
                }
                log.warn("Persisted session failed the probe; re-minting through FlareSolverr");
            }
        }
        // Pasos 3-4: mint con intentos acotados. El reintento externo de
        // ElscioneApiClient (backoff exponencial) re-entra aquí si sale una
        // sesión best-effort, así que el bucle se mantiene pequeño y sin sleeps.
        CloudflareSession bestEffort = null;
        int attempts = Math.max(1, sessionAcquisitionAttempts);
        for (int attempt = 0; attempt < attempts; attempt++) {
            CloudflareSession base = harvestBaseSession();
            if (!anubisSolveEnabled) {
                // Sin probe no hay verificación posible ni persistencia
                // aplicable: devolver la sesión de FlareSolverr tal cual.
                return base;
            }
            StageResult stage = solveAnubisChallengeIfNeeded(base);
            bestEffort = stage.session();
            if (stage.verified()) {
                persistSession(stage.session());
                return stage.session();
            }
            log.warn("Session acquisition attempt {}/{} not verified", attempt + 1, attempts);
        }
        return bestEffort;
    }

    /** Cosecha la capa Cloudflare vía FlareSolverr, o degrada a UA-only si falla. */
    private CloudflareSession harvestBaseSession() {
        if (isConfigured(flaresolverrUrl)) {
            try {
                CloudflareSession session = fetchFromFlareSolverr();
                log.info("Cloudflare session obtained through FlareSolverr");
                return session;
            } catch (Exception e) {
                log.warn("FlareSolverr call failed ({}), falling back to direct requests", e.getMessage());
            }
        } else {
            log.warn("No FlareSolverr URL configured: direct requests will likely be blocked by Cloudflare");
        }
        return new CloudflareSession(DEFAULT_FIREFOX_USER_AGENT, null, Instant.now());
    }

    private boolean isConfigured(String value) {
        return value != null && !value.isBlank();
    }

    // ------------------------------------------------------------------
    // Cache de última sesión buena (best-effort, fuera de git)
    // ------------------------------------------------------------------

    /** Carga la sesión persistida; null si no existe, está corrupta o superó el TTL. */
    private CloudflareSession loadPersistedSession() {
        if (sessionCachePath == null) {
            return null;
        }
        try {
            if (!Files.isRegularFile(sessionCachePath)) {
                return null;
            }
            PersistedSession persisted = objectMapper.readValue(Files.readString(sessionCachePath), PersistedSession.class);
            if (persisted == null || !isConfigured(persisted.userAgent()) || !isConfigured(persisted.cookieHeader())) {
                return null;
            }
            Instant obtainedAt = Instant.ofEpochMilli(persisted.obtainedAtEpochMillis());
            if (Duration.between(obtainedAt, Instant.now()).toMillis() >= sessionTtlMillis) {
                log.debug("Persisted session is older than TTL ({} ms); ignoring", sessionTtlMillis);
                return null;
            }
            log.debug("Persisted session candidate loaded from {}", sessionCachePath);
            return new CloudflareSession(persisted.userAgent(), persisted.cookieHeader(), obtainedAt);
        } catch (Exception e) {
            log.debug("Could not load persisted session cache {}: {}", sessionCachePath, e.getMessage());
            return null;
        }
    }

    /** Persiste una sesión VERIFICADA; fallos de I/O solo se registran. */
    private void persistSession(CloudflareSession session) {
        if (sessionCachePath == null || session.cookieHeader() == null || session.cookieHeader().isBlank()) {
            return;
        }
        try {
            PersistedSession persisted = new PersistedSession(session.userAgent(), session.cookieHeader(),
                    session.obtainedAt().toEpochMilli());
            Path temp = sessionCachePath.resolveSibling(sessionCachePath.getFileName() + ".tmp");
            Files.writeString(temp, objectMapper.writeValueAsString(persisted));
            Files.move(temp, sessionCachePath, StandardCopyOption.REPLACE_EXISTING);
            log.debug("Verified session persisted to {}", sessionCachePath);
        } catch (Exception e) {
            log.warn("Could not persist session cache {}: {}", sessionCachePath, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // FlareSolverr: capa Cloudflare
    // ------------------------------------------------------------------

    /**
     * Resuelve la capa Cloudflare vía FlareSolverr. Con sesión persistente
     * configurada, navega DOS veces con la misma sesión y fusiona las
     * cookies: la primera navegación puede completar el challenge después
     * de que FlareSolverr lea las cookies, dejando el cf_clearance en el
     * contexto que la segunda navegación ya devuelve.
     */
    CloudflareSession fetchFromFlareSolverr() {
        if (isConfigured(flaresolverrSession)) {
            createFlareSolverrSessionQuietly();
        }
        int navigations = isConfigured(flaresolverrSession) ? 2 : 1;
        CloudflareSession merged = null;
        NovelsApiException lastFailure = null;
        for (int nav = 0; nav < navigations; nav++) {
            try {
                CloudflareSession current = fetchFromFlareSolverrOnce();
                merged = mergeCookieSessions(merged, current);
            } catch (NovelsApiException e) {
                lastFailure = e;
                log.warn("FlareSolverr navigation {}/{} failed: {}", nav + 1, navigations, e.getMessage());
            }
        }
        if (merged == null) {
            throw lastFailure != null ? lastFailure : new NovelsApiException("FlareSolverr returned no session");
        }
        return merged;
    }

    /** Crea la sesión persistente en FlareSolverr; ignora "ya existe". */
    private void createFlareSolverrSessionQuietly() {
        try {
            String payload = objectMapper.writeValueAsString(Map.of("cmd", "sessions.create", "session", flaresolverrSession));
            HttpRequest request = HttpRequest.newBuilder(URI.create(flaresolverrUrl))
                    .timeout(Duration.ofSeconds(flaresolverrTimeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.debug("FlareSolverr session '{}' ready", flaresolverrSession);
        } catch (Exception e) {
            log.debug("FlareSolverr sessions.create for '{}' returned: {}", flaresolverrSession, e.getMessage());
        }
    }

    private CloudflareSession fetchFromFlareSolverrOnce() {
        try {
            Map<String, Object> payloadMap = new LinkedHashMap<>();
            payloadMap.put("cmd", "request.get");
            payloadMap.put("url", baseUrl);
            if (isConfigured(flaresolverrSession)) {
                payloadMap.put("session", flaresolverrSession);
            }
            payloadMap.put("maxTimeout", flaresolverrTimeoutSeconds * 1000);
            HttpRequest request = HttpRequest.newBuilder(URI.create(flaresolverrUrl))
                    .timeout(Duration.ofSeconds(flaresolverrTimeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payloadMap)))
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

    /** Fusiona dos sesiones: el user agent de la más reciente gana; las cookies se mezclan por nombre. */
    private CloudflareSession mergeCookieSessions(CloudflareSession older, CloudflareSession newer) {
        if (older == null) {
            return newer;
        }
        Map<String, String> jar = cookieJar(older.cookieHeader());
        jar.putAll(cookieJar(newer.cookieHeader()));
        return new CloudflareSession(newer.userAgent(), cookieHeader(jar), newer.obtainedAt());
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

    // ------------------------------------------------------------------
    // Anubis: proof-of-work resuelto en Java + probe de verificación
    // ------------------------------------------------------------------

    /**
     * Sondea el origen con la sesión base y, si responde con el interstitial
     * de Anubis, resuelve el PoW y redime el challenge. El resultado incluye
     * la verificación: true solo cuando el origen respondió con contenido
     * real (no un challenge de Anubis ni un 403 de Cloudflare), que es la
     * condición para persistir/reusar la sesión. Cualquier fallo degrada a
     * una sesión no verificada: el reintento de ElscioneApiClient invalida
     * y vuelve a resolver.
     */
    private StageResult solveAnubisChallengeIfNeeded(CloudflareSession base) {
        Map<String, String> jar = cookieJar(base.cookieHeader());
        try {
            HttpResponse<String> probe = anubisGet(baseUrl, base.userAgent(), jar);
            captureCookies(probe, jar);
            AnubisChallenge challenge = parseAnubisChallenge(probe.body());
            if (challenge == null) {
                if (isCloudflareChallenge(probe.statusCode(), probe.body())) {
                    log.warn("Anubis probe blocked by Cloudflare (HTTP {}): cf_clearance missing or expired", probe.statusCode());
                    return new StageResult(withCookies(base, jar), false);
                }
                log.debug("Origin reachable without Anubis challenge (HTTP {})", probe.statusCode());
                return new StageResult(withCookies(base, jar), true);
            }
            log.info("Anubis challenge detected (difficulty {}), solving proof of work", challenge.difficulty());

            long solveStart = System.currentTimeMillis();
            String[] solution = solveProofOfWork(challenge.randomData(), challenge.difficulty());
            long solveElapsed = System.currentTimeMillis() - solveStart;
            log.info("Anubis PoW solved in {}ms (nonce {})", solveElapsed, solution[1]);
            String redemptionUrl = passChallengeUrl(challenge, solution, solveElapsed, baseUrl);
            HttpResponse<String> redemption = anubisGet(redemptionUrl, base.userAgent(), jar);
            captureCookies(redemption, jar);
            if (redemption.statusCode() / 100 != 2 && redemption.statusCode() / 100 != 3) {
                log.warn("Anubis pass-challenge failed with HTTP {}", redemption.statusCode());
                return new StageResult(withCookies(base, jar), false);
            }
            log.info("Anubis challenge redeemed (HTTP {}), verification request follows", redemption.statusCode());

            HttpResponse<String> verify = anubisGet(baseUrl, base.userAgent(), jar);
            captureCookies(verify, jar);
            boolean verified = parseAnubisChallenge(verify.body()) == null
                    && !isCloudflareChallenge(verify.statusCode(), verify.body());
            if (verified) {
                log.info("Anubis session verified, cookie jar: {}", jar.keySet());
            } else {
                log.warn("Anubis verification still challenged (HTTP {})", verify.statusCode());
            }
            return new StageResult(withCookies(base, jar), verified);
        } catch (Exception e) {
            log.warn("Anubis solve stage failed: {} — returning base session", e.getMessage());
            return new StageResult(base, false);
        }
    }

    /** Distingue un challenge de Cloudflare (403 + "Just a moment") de una respuesta del origen. */
    private static boolean isCloudflareChallenge(int statusCode, String body) {
        return statusCode == 403
                || body.contains("challenges.cloudflare.com")
                || body.contains("Just a moment");
    }

    /**
     * PoW de Anubis ("fast"): buscar nonce tal que SHA-256(randomData + nonce)
     * (nonce en decimal, UTF-8) tenga al menos "difficulty" nibbles en cero
     * al inicio del digest. Con difficulty 4 son ~16^4 = 65k intentos (~50ms).
     */
    String[] solveProofOfWork(String randomData, int difficulty) {
        if (difficulty < 1 || difficulty > anubisMaxDifficulty) {
            throw new NovelsApiException("Anubis difficulty " + difficulty + " out of configured bounds (1.." + anubisMaxDifficulty + ")");
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            long nonce = 0;
            while (true) {
                sha.reset();
                byte[] digest = sha.digest((randomData + nonce).getBytes(StandardCharsets.UTF_8));
                if (leadingZeroNibbles(digest) >= difficulty) {
                    return new String[]{toHex(digest), Long.toString(nonce)};
                }
                nonce++;
            }
        } catch (NoSuchAlgorithmException e) {
            throw new NovelsApiException("SHA-256 unavailable", e);
        }
    }

    /** Cuenta los nibbles hexadecimales en cero a la izquierda del digest. */
    static int leadingZeroNibbles(byte[] digest) {
        int count = 0;
        for (byte b : digest) {
            int high = (b >> 4) & 0xF;
            int low = b & 0xF;
            if (high == 0) {
                count++;
                if (low == 0) {
                    count++;
                    continue;
                }
            }
            break;
        }
        return count;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * Extrae el challenge de Anubis del interstitial HTML. Devuelve null si
     * la página no es un interstitial (contenido real, error o challenge de
     * otra capa). El JSON del challenge NO se parsea con Jackson porque los
     * valores viven dentro de un script tag con HTML escapado.
     */
    static AnubisChallenge parseAnubisChallenge(String html) {
        if (html == null) {
            return null;
        }
        Matcher tag = ANUBIS_CHALLENGE_TAG.matcher(html);
        if (!tag.find()) {
            return null;
        }
        String json = tag.group(1);
        if (json.isBlank() || json.trim().equals("null")) {
            return null;
        }
        Matcher id = ANUBIS_ID.matcher(json);
        Matcher randomData = ANUBIS_RANDOM_DATA.matcher(json);
        Matcher difficulty = ANUBIS_DIFFICULTY.matcher(json);
        if (!id.find() || !randomData.find() || !difficulty.find()) {
            return null;
        }
        String basePrefix = "";
        Matcher prefix = ANUBIS_BASE_PREFIX_TAG.matcher(html);
        if (prefix.find()) {
            basePrefix = prefix.group(1);
        }
        return new AnubisChallenge(id.group(1), randomData.group(1),
                Integer.parseInt(difficulty.group(1)), basePrefix);
    }

    /**
     * URL de redención tal como la construye el JS de Anubis:
     * {origin}{basePrefix}/.within.website/x/cmd/anubis/api/pass-challenge
     * con id, response (hash hex), nonce, redir (URL completa original) y
     * elapsedTime en milisegundos. La URL va URL-encoded igual que
     * URL.searchParams.set del navegador.
     */
    private String passChallengeUrl(AnubisChallenge challenge, String[] solution, long solveElapsed, String originalUrl) {
        URI base = URI.create(baseUrl);
        String origin = base.getScheme() + "://" + base.getAuthority();
        return origin + challenge.basePrefix() + ANUBIS_PASS_CHALLENGE_PATH
                + "?id=" + URLEncoder.encode(challenge.id(), StandardCharsets.UTF_8)
                + "&response=" + solution[0]
                + "&nonce=" + solution[1]
                + "&redir=" + URLEncoder.encode(originalUrl, StandardCharsets.UTF_8)
                + "&elapsedTime=" + solveElapsed;
    }

    private HttpResponse<String> anubisGet(String url, String userAgent, Map<String, String> jar) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(ANUBIS_REQUEST_TIMEOUT)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .header("Referer", referer())
                .header("Upgrade-Insecure-Requests", "1")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-User", "?1")
                .GET();
        String cookies = cookieHeader(jar);
        if (!cookies.isBlank()) {
            builder.header("Cookie", cookies);
        }
        return challengeHttpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String referer() {
        URI base = URI.create(baseUrl);
        return base.getScheme() + "://" + base.getAuthority() + "/";
    }

    // ------------------------------------------------------------------
    // Cookie jar helpers
    // ------------------------------------------------------------------

    /** Parsea un Cookie header "a=1; b=2" en un jar ordenado. */
    static Map<String, String> cookieJar(String cookieHeader) {
        Map<String, String> jar = new LinkedHashMap<>();
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return jar;
        }
        for (String pair : cookieHeader.split(";")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                jar.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return jar;
    }

    private static String cookieHeader(Map<String, String> jar) {
        return jar.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("; "));
    }

    /**
     * Captura los Set-Cookie de una respuesta en el jar. Un valor vacío
     * elimina la cookie (Anubis limpia así su cookie de autorización al
     * fallar una redención).
     */
    static void captureCookies(HttpResponse<?> response, Map<String, String> jar) {
        for (String setCookie : response.headers().allValues("set-cookie")) {
            String pair = setCookie.split(";", 2)[0].trim();
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (value.isBlank()) {
                jar.remove(name);
            } else {
                jar.put(name, value);
            }
        }
    }

    private static CloudflareSession withCookies(CloudflareSession base, Map<String, String> jar) {
        return new CloudflareSession(base.userAgent(), cookieHeader(jar), base.obtainedAt());
    }
}
