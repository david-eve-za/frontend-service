package com.glez.frontendservice.services;

import com.glez.frontendservice.exception.NovelsApiException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CloudflareBypassService Tests")
class CloudflareBypassServiceTest {

    private static final String FLARESOLVERR_OK_RESPONSE = """
            {
              "status": "ok",
              "message": "",
              "solution": {
                "url": "https://example.com/base/",
                "status": 200,
                "cookies": [
                  {"name": "cf_clearance", "value": "abc123", "domain": ".example.com"},
                  {"name": "other", "value": "z", "domain": ".example.com"}
                ],
                "userAgent": "Mozilla/5.0 FlareSolverrUA"
              },
              "startTimestamp": 1234
            }
            """;

    /** Respuesta de FlareSolverr que provee la sesión base para los tests de Anubis. */
    private static final String FLARESOLVERR_SEEDED_RESPONSE = """
            {"status":"ok","message":"Challenge not detected!","solution":{"url":"stub","status":200,
            "cookies":[{"name":"cf_clearance","value":"seeded"}],"userAgent":"TestUA"}}
            """;

    /** Interstitial de Anubis v1.27 reducido, con la estructura real de script tags. */
    private static final String ANUBIS_INTERSTITIAL = """
            <html lang="en"><head><title>Making sure you're not a bot!</title>
            <script id="anubis_version" type="application/json">"v1.27.0" </script>
            <script id="anubis_challenge" type="application/json">{"rules":{"algorithm":"fast","difficulty":2},"challenge":{"issuedAt":"2026-09-27T21:44:42.209637745Z","metadata":{"User-Agent":"TestUA","X-Real-Ip":"76.187.78.85"},"id":"01a0e4d3-d1e1-799f-bb7e-9e2b175a9eeb","method":"fast","randomData":"RANDOM_DATA","policyRuleHash":"ac980f49c4d35fab","difficulty":2,"spent":false}}
            </script>
            <script id="anubis_base_prefix" type="application/json">"" </script>
            <script id="anubis_public_url" type="application/json">"" </script></head>
            <body><main><h1 id="title">Making sure you're not a bot!</h1><p id="status">Loading...</p></main></body></html>
            """;

    /** RandomData completo del interstitial de prueba (debe coincidir con ANUBIS_INTERSTITIAL). */
    private static final String TEST_RANDOM_DATA = "c6595e38534b9e323290a42b3a6c910e59cf75d1d8f61df497b171ea130418b7b352d54d8e2b60a26a5781efa7bacfedd81b2545025f305a308bf18988502013";

    private final JsonMapper objectMapper = new JsonMapper();
    private HttpServer server;
    private HttpServer originServer;

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (originServer != null) {
            originServer.stop(0);
        }
    }

    // ------------------------------------------------------------------
    // FlareSolverr
    // ------------------------------------------------------------------

    @Test
    @DisplayName("invalidate forces a new session resolution through FlareSolverr")
    void invalidate_forcesSessionResolution() throws IOException {
        server = startFlareSolverrStub(FLARESOLVERR_OK_RESPONSE, FLARESOLVERR_OK_RESPONSE, new AtomicInteger(0), new StringBuilder());
        CloudflareBypassService service = newService("https://example.com/base/",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "", false, "", 1);

        CloudflareBypassService.CloudflareSession first = service.getSession();
        service.invalidate();
        CloudflareBypassService.CloudflareSession second = service.getSession();

        assertNotSame(first, second);
        assertEquals(first.userAgent(), second.userAgent());
        assertEquals("cf_clearance=abc123; other=z", second.cookieHeader());
    }

    @Test
    @DisplayName("FlareSolverr response is parsed into a session with cookies and user agent")
    void flaresolverr_responseIsParsedIntoSession() throws IOException {
        server = startFlareSolverrStub(FLARESOLVERR_OK_RESPONSE, FLARESOLVERR_OK_RESPONSE, new AtomicInteger(0), new StringBuilder());
        CloudflareBypassService service = newService("https://example.com/base/",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "", false, "", 1);

        CloudflareBypassService.CloudflareSession session = service.getSession();

        assertEquals("Mozilla/5.0 FlareSolverrUA", session.userAgent());
        assertEquals("cf_clearance=abc123; other=z", session.cookieHeader());
    }

    @Test
    @DisplayName("FlareSolverr persistent session navigates twice and merges the cookie jars")
    void flaresolverrPersistentSession_navigatesTwiceAndMergesCookies() throws IOException {
        AtomicInteger navigations = new AtomicInteger();
        // Primera navegación sin cf_clearance, segunda con él (el challenge se
        // resuelve en el contexto después de que FlareSolverr lee las cookies).
        String first = """
                {"status":"ok","message":"Challenge not detected!","solution":{"url":"stub","status":200,
                "cookies":[{"name":"verification","value":"v1"}],"userAgent":"Mozilla/5.0 FSUA"}}
                """;
        String second = """
                {"status":"ok","message":"Challenge not detected!","solution":{"url":"stub","status":200,
                "cookies":[{"name":"verification","value":"v2"},{"name":"cf_clearance","value":"clear-xyz"}],"userAgent":"Mozilla/5.0 FSUA"}}
                """;
        StringBuilder payloads = new StringBuilder();
        server = startFlareSolverrStubWithRecorder(first, second, navigations, payloads);
        CloudflareBypassService service = newService("https://example.com/base/",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "elscione-bypass", false, "", 1);

        CloudflareBypassService.CloudflareSession session = service.getSession();

        assertEquals(3, navigations.get()); // sessions.create + 2 request.get
        assertEquals("Mozilla/5.0 FSUA", session.userAgent());
        assertEquals("verification=v2; cf_clearance=clear-xyz", session.cookieHeader());
        String allPayloads = payloads.toString();
        assertTrue(allPayloads.contains("\"session\":\"elscione-bypass\""));
        assertTrue(allPayloads.contains("sessions.create"));
    }

    @Test
    @DisplayName("FlareSolverr failure falls back to a direct session without cookies")
    void flaresolverrUnavailable_fallsBackToDirectSession() {
        CloudflareBypassService service = newService("https://example.com/base/", "http://127.0.0.1:1/v1", "", false, "", 1);

        CloudflareBypassService.CloudflareSession session = service.getSession();

        assertEquals(CloudflareBypassService.DEFAULT_FIREFOX_USER_AGENT, session.userAgent());
        assertNull(session.cookieHeader());
    }

    @Test
    @DisplayName("parseFlareSolverrResponse throws when FlareSolverr reports an error")
    void parseFlareSolverrResponse_throwsWhenStatusNotOk() {
        CloudflareBypassService service = newService("https://example.com/base/", "", "", false, "", 1);

        assertThrows(NovelsApiException.class, () ->
                service.parseFlareSolverrResponse("{\"status\":\"error\",\"message\":\"challenge failed\"}"));
    }

    @Test
    @DisplayName("parseFlareSolverrResponse throws when the user agent is missing")
    void parseFlareSolverrResponse_throwsWhenUserAgentMissing() {
        CloudflareBypassService service = newService("https://example.com/base/", "", "", false, "", 1);
        String body = "{\"status\":\"ok\",\"solution\":{\"status\":200,\"cookies\":[],\"userAgent\":\"\"}}";

        assertThrows(NovelsApiException.class, () -> service.parseFlareSolverrResponse(body));
    }

    // ------------------------------------------------------------------
    // Anubis solver
    // ------------------------------------------------------------------

    @Test
    @DisplayName("solveProofOfWork finds a nonce whose SHA-256 digest has the required leading zeros")
    void solveProofOfWork_producesValidHash() {
        CloudflareBypassService service = newService("https://example.com/base/", "", "", false, "", 1);
        String randomData = "c6595e38534b9e323290a42b3a6c910e59cf75d1d8f61df497b171ea130418b7";

        String[] solution = service.solveProofOfWork(randomData, 4);

        String expectedHash = sha256Hex(randomData + solution[1]);
        assertEquals(expectedHash, solution[0]);
        assertTrue(solution[0].startsWith("0000"));
        assertDoesNotThrow(() -> Long.parseLong(solution[1]));
    }

    @Test
    @DisplayName("solveProofOfWork rejects difficulties outside the configured bound")
    void solveProofOfWork_rejectsUnboundedDifficulty() {
        CloudflareBypassService service = newService("https://example.com/base/", "", "", false, "", 1);

        assertThrows(NovelsApiException.class, () -> service.solveProofOfWork("data", 32));
        assertThrows(NovelsApiException.class, () -> service.solveProofOfWork("data", 0));
    }

    @Test
    @DisplayName("parseAnubisChallenge extracts id, randomData, difficulty and base prefix from the real interstitial")
    void parseAnubisChallenge_extractsFieldsFromRealHtml() {
        CloudflareBypassService.AnubisChallenge challenge =
                CloudflareBypassService.parseAnubisChallenge(interstitial());

        assertNotNull(challenge);
        assertEquals("01a0e4d3-d1e1-799f-bb7e-9e2b175a9eeb", challenge.id());
        assertEquals(TEST_RANDOM_DATA, challenge.randomData());
        assertEquals(2, challenge.difficulty());
        assertEquals("", challenge.basePrefix());
    }

    @Test
    @DisplayName("parseAnubisChallenge returns null for non-challenge pages")
    void parseAnubisChallenge_returnsNullForOtherPages() {
        assertNull(CloudflareBypassService.parseAnubisChallenge("<html><title>index</title></html>"));
        assertNull(CloudflareBypassService.parseAnubisChallenge(
                "<script id=\"anubis_challenge\" type=\"application/json\">null </script>"));
        assertNull(CloudflareBypassService.parseAnubisChallenge(null));
    }

    @Test
    @DisplayName("Anubis flow against a stub origin: probe, solve, redeem and verify merge the auth cookie")
    void anubisFlow_solvesRedeemsAndMergesCookies() throws IOException {
        originServer = startAnubisStubOrigin(new AtomicInteger());
        server = startFlareSolverrStub(FLARESOLVERR_SEEDED_RESPONSE, FLARESOLVERR_SEEDED_RESPONSE, new AtomicInteger(0), new StringBuilder());
        String base = "http://127.0.0.1:" + originServer.getAddress().getPort() + "/base/";
        CloudflareBypassService service = newService(base,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "", true, "", 1);

        CloudflareBypassService.CloudflareSession session = service.getSession();

        String cookies = session.cookieHeader();
        assertTrue(cookies.contains("cf_clearance=seeded"), cookies);
        assertTrue(cookies.contains("anubis-auth="), cookies);
        assertTrue(cookies.contains("verification=v1"), cookies);
        assertTrue(cookies.contains("PHPSESSID=origin-session"), cookies);
        assertFalse(cookies.contains("cleared="), "las cookies con valor vacío deben eliminarse del jar");
    }

    @Test
    @DisplayName("Anubis solve disabled returns the FlareSolverr session untouched without probing")
    void anubisDisabled_returnsFlareSolverrSessionUntouched() throws IOException {
        server = startFlareSolverrStub(FLARESOLVERR_OK_RESPONSE, FLARESOLVERR_OK_RESPONSE, new AtomicInteger(0), new StringBuilder());
        CloudflareBypassService service = newService("https://example.com/base/",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "", false, "", 1);

        CloudflareBypassService.CloudflareSession session = service.getSession();

        assertEquals("cf_clearance=abc123; other=z", session.cookieHeader());
    }

    // ------------------------------------------------------------------
    // Cache de sesión persistida (sustituye a las antiguas vars estáticas)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Persisted session verified by the probe is reused without FlareSolverr")
    void persistedSession_verifiedByProbe_isReusedWithoutFlareSolverr() throws IOException {
        AtomicInteger passChallenges = new AtomicInteger();
        originServer = startAnubisStubOrigin(passChallenges);
        Path cache = tempDir.resolve("session.json");
        writeCacheFile(cache, "TestUA", "cf_clearance=persisted; anubis-auth=jwt-old-token", Instant.now());
        String base = "http://127.0.0.1:" + originServer.getAddress().getPort() + "/base/";
        // Sin FlareSolverr configurado: si la persistida no sirviera, todo fallaría.
        CloudflareBypassService service = newService(base, "", "", true, cache.toString(), 1);

        CloudflareBypassService.CloudflareSession session = service.getSession();

        String cookies = session.cookieHeader();
        assertTrue(cookies.contains("cf_clearance=persisted"), cookies);
        assertTrue(cookies.contains("anubis-auth=jwt-old-token"), cookies);
        assertTrue(cookies.contains("PHPSESSID=origin-session"), "el probe debe añadir las cookies del origen");
        assertEquals(0, passChallenges.get(), "una sesión persistida válida no debe disparar el PoW");
    }

    @Test
    @DisplayName("Stale persisted session (older than TTL) is ignored and a fresh one is minted")
    void persistedSession_stale_isIgnoredAndMintedFresh() throws IOException {
        AtomicInteger passChallenges = new AtomicInteger();
        originServer = startAnubisStubOrigin(passChallenges);
        server = startFlareSolverrStub(FLARESOLVERR_SEEDED_RESPONSE, FLARESOLVERR_SEEDED_RESPONSE, new AtomicInteger(0), new StringBuilder());
        Path cache = tempDir.resolve("session.json");
        writeCacheFile(cache, "OldUA", "cf_clearance=expired", Instant.now().minusSeconds(3600));
        String base = "http://127.0.0.1:" + originServer.getAddress().getPort() + "/base/";
        CloudflareBypassService service = newService(base,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "", true, cache.toString(), 1);

        CloudflareBypassService.CloudflareSession session = service.getSession();

        assertTrue(session.cookieHeader().contains("anubis-auth="), "debe mintear una sesión nueva");
        assertEquals(1, passChallenges.get(), "la sesión vencida se ignora y se resuelve el PoW una vez");
        String persisted = Files.readString(cache);
        assertTrue(persisted.contains("anubis-auth"), "la sesión verificada se persiste de vuelta");
        assertTrue(persisted.contains("TestUA"), persisted);
    }

    @Test
    @DisplayName("Verified mint persists the session cache for future restarts")
    void verifiedMint_persistsSessionCache() throws IOException {
        originServer = startAnubisStubOrigin(new AtomicInteger());
        server = startFlareSolverrStub(FLARESOLVERR_SEEDED_RESPONSE, FLARESOLVERR_SEEDED_RESPONSE, new AtomicInteger(0), new StringBuilder());
        Path cache = tempDir.resolve("session.json");
        assertFalse(Files.exists(cache));
        String base = "http://127.0.0.1:" + originServer.getAddress().getPort() + "/base/";
        CloudflareBypassService service = newService(base,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "", true, cache.toString(), 1);

        CloudflareBypassService.CloudflareSession session = service.getSession();

        assertTrue(session.cookieHeader().contains("anubis-auth="));
        assertTrue(Files.exists(cache), "el mint verificado debe escribir el cache");
        String persisted = Files.readString(cache);
        assertTrue(persisted.contains("TestUA"));
        assertTrue(persisted.contains("cf_clearance=seeded"));
        assertFalse(Files.exists(tempDir.resolve("session.json.tmp")), "el temporal se limpia con el move");
    }

    @Test
    @DisplayName("Unverified sessions are never persisted (cache is not poisoned)")
    void unverifiedSession_neverPersisted() throws IOException {
        // El origen SIEMPRE sirve el interstitial: la verificación nunca pasa.
        originServer = startHttpServer(response -> ANUBIS_INTERSTITIAL.getBytes(StandardCharsets.UTF_8));
        Path cache = tempDir.resolve("session.json");
        String base = "http://127.0.0.1:" + originServer.getAddress().getPort() + "/base/";
        CloudflareBypassService service = newService(base, "", "", true, cache.toString(), 1);

        service.getSession();

        assertFalse(Files.exists(cache), "una sesión no verificada no debe persistirse");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private CloudflareBypassService newService(String baseUrl, String flaresolverrUrl, String flaresolverrSession,
                                               boolean anubisSolveEnabled, String sessionCachePath, int acquisitionAttempts) {
        return new CloudflareBypassService(objectMapper, baseUrl, flaresolverrUrl, 5,
                flaresolverrSession, anubisSolveEnabled, 24, sessionCachePath, acquisitionAttempts, 30);
    }

    private static String interstitial() {
        return ANUBIS_INTERSTITIAL.replace("RANDOM_DATA", TEST_RANDOM_DATA);
    }

    private void writeCacheFile(Path cache, String userAgent, String cookieHeader, Instant obtainedAt) throws IOException {
        String json = "{\"userAgent\":" + quote(userAgent)
                + ",\"cookieHeader\":" + quote(cookieHeader)
                + ",\"obtainedAtEpochMillis\":" + obtainedAt.toEpochMilli() + "}";
        Files.writeString(cache, json);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpServer startFlareSolverrStub(String firstResponse, String secondResponse,
                                             AtomicInteger counter, StringBuilder payloads) throws IOException {
        return startFlareSolverrStubWithRecorder(firstResponse, secondResponse, counter, payloads);
    }

    private HttpServer startFlareSolverrStubWithRecorder(String firstResponse, String secondResponse,
                                                          AtomicInteger counter, StringBuilder payloads) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            payloads.append(body).append('\n');
            int request = counter.incrementAndGet();
            String response = body.contains("sessions.create") ? "{\"status\":\"ok\",\"message\":\"created\"}"
                    : (request <= 2 ? firstResponse : secondResponse);
            respond(exchange, 200, "application/json", response);
        });
        httpServer.start();
        return httpServer;
    }

    /**
     * Stub del origen con la capa Anubis: sirve el interstitial, valida la
     * redención (id + hash SHA-256(randomData+nonce) con ceros iniciales) y
     * responde 302 + Set-Cookie; con la cookie de auth sirve contenido real.
     */
    private HttpServer startAnubisStubOrigin(AtomicInteger passChallenges) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
            if (path.endsWith("/.within.website/x/cmd/anubis/api/pass-challenge")) {
                passChallenges.incrementAndGet();
                String query = exchange.getRequestURI().getQuery();
                String id = queryParam(query, "id");
                String response = queryParam(query, "response");
                String nonce = queryParam(query, "nonce");
                String expected = sha256Hex(TEST_RANDOM_DATA + nonce);
                if (!"01a0e4d3-d1e1-799f-bb7e-9e2b175a9eeb".equals(id) || !expected.equals(response)
                        || !response.startsWith("00")) {
                    respond(exchange, 403, "text/html", "<html>rejected</html>");
                    return;
                }
                // Cookie con valor vacío: el jar debe eliminarla (patrón de Anubis al limpiar).
                exchange.getResponseHeaders().add("Set-Cookie", "cleared=; Path=/");
                exchange.getResponseHeaders().add("Set-Cookie", "anubis-auth=jwt-eddsa-token; Path=/");
                exchange.getResponseHeaders().set("Location", queryParam(query, "redir"));
                respond(exchange, 302, "text/html", "");
                return;
            }
            if (cookieHeader != null && cookieHeader.contains("anubis-auth=")) {
                exchange.getResponseHeaders().add("Set-Cookie", "PHPSESSID=origin-session; Path=/");
                respond(exchange, 200, "text/html", "<html><title>index</title></html>");
                return;
            }
            exchange.getResponseHeaders().add("Set-Cookie", "verification=v1; Path=/");
            respond(exchange, 200, "text/html", interstitial());
        });
        httpServer.start();
        return httpServer;
    }

    /** Servidor trivial con cuerpo fijo para el caso "nunca verifica". */
    private HttpServer startHttpServer(java.util.function.UnaryOperator<byte[]> bodySupplier) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/", exchange -> {
            exchange.getRequestBody().close();
            byte[] bytes = bodySupplier.apply(null);
            respond(exchange, 200, "text/html", new String(bytes, StandardCharsets.UTF_8));
        });
        httpServer.start();
        return httpServer;
    }

    private static String queryParam(String query, String name) {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && name.equals(pair.substring(0, eq))) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            if (bytes.length > 0) {
                output.write(bytes);
            }
        }
    }
}
