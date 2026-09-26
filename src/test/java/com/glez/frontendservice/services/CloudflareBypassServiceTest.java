package com.glez.frontendservice.services;

import com.glez.frontendservice.exception.NovelsApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

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

    private final JsonMapper objectMapper = new JsonMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Static configuration takes precedence and the session is cached")
    void staticConfiguration_takesPrecedenceAndIsCached() {
        CloudflareBypassService service = new CloudflareBypassService(objectMapper,
                "https://example.com/base/", "", 5, "StaticUA", "static-cookie-value", 30);

        CloudflareBypassService.CloudflareSession session = service.getSession();

        assertEquals("StaticUA", session.userAgent());
        assertEquals("cf_clearance=static-cookie-value", session.cookieHeader());
        assertSame(session, service.getSession());
    }

    @Test
    @DisplayName("invalidate forces a new session resolution")
    void invalidate_forcesSessionResolution() {
        CloudflareBypassService service = new CloudflareBypassService(objectMapper,
                "https://example.com/base/", "", 5, "StaticUA", "static-cookie-value", 30);

        CloudflareBypassService.CloudflareSession first = service.getSession();
        service.invalidate();
        CloudflareBypassService.CloudflareSession second = service.getSession();

        assertNotSame(first, second);
        assertEquals(first.userAgent(), second.userAgent());
    }

    @Test
    @DisplayName("FlareSolverr response is parsed into a session with cookies and user agent")
    void flaresolverr_responseIsParsedIntoSession() throws IOException {
        server = startHttpServer(FLARESOLVERR_OK_RESPONSE);
        CloudflareBypassService service = new CloudflareBypassService(objectMapper,
                "https://example.com/base/",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                5, "", "", 30);

        CloudflareBypassService.CloudflareSession session = service.getSession();

        assertEquals("Mozilla/5.0 FlareSolverrUA", session.userAgent());
        assertEquals("cf_clearance=abc123; other=z", session.cookieHeader());
    }

    @Test
    @DisplayName("FlareSolverr failure falls back to a direct session without cookies")
    void flaresolverrUnavailable_fallsBackToDirectSession() {
        CloudflareBypassService service = new CloudflareBypassService(objectMapper,
                "https://example.com/base/", "http://127.0.0.1:1/v1", 1, "", "", 30);

        CloudflareBypassService.CloudflareSession session = service.getSession();

        assertEquals(CloudflareBypassService.DEFAULT_FIREFOX_USER_AGENT, session.userAgent());
        assertNull(session.cookieHeader());
    }

    @Test
    @DisplayName("parseFlareSolverrResponse throws when FlareSolverr reports an error")
    void parseFlareSolverrResponse_throwsWhenStatusNotOk() {
        CloudflareBypassService service = new CloudflareBypassService(objectMapper,
                "https://example.com/base/", "", 5, "", "", 30);

        assertThrows(NovelsApiException.class, () ->
                service.parseFlareSolverrResponse("{\"status\":\"error\",\"message\":\"challenge failed\"}"));
    }

    @Test
    @DisplayName("parseFlareSolverrResponse throws when the user agent is missing")
    void parseFlareSolverrResponse_throwsWhenUserAgentMissing() {
        CloudflareBypassService service = new CloudflareBypassService(objectMapper,
                "https://example.com/base/", "", 5, "", "", 30);
        String body = "{\"status\":\"ok\",\"solution\":{\"status\":200,\"cookies\":[],\"userAgent\":\"\"}}";

        assertThrows(NovelsApiException.class, () -> service.parseFlareSolverrResponse(body));
    }

    private HttpServer startHttpServer(String responseBody) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/", exchange -> {
            exchange.getRequestBody().close();
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        httpServer.start();
        return httpServer;
    }
}
