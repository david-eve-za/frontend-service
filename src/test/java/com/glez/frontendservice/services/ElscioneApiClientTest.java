package com.glez.frontendservice.services;

import com.glez.frontendservice.exception.NovelsApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ElscioneApiClient Tests")
class ElscioneApiClientTest {

    private static final String LIST_BODY = """
            {"items":[
              {"href":"/Officially%20Novels/a.epub","size":123},
              {"href":"/Officially%20Novels/Dir/"}
            ]}
            """;
    private static final String DOWNLOAD_BODY = "hello novels";

    private final JsonMapper objectMapper = new JsonMapper();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final Queue<Integer> plannedStatuses = new ConcurrentLinkedQueue<>();
    private final Queue<String> plannedBodies = new ConcurrentLinkedQueue<>();
    private volatile String successBody = LIST_BODY;
    private volatile int lastPlannedStatus = 200;

    @TempDir
    Path tempDir;

    private HttpServer server;
    private CloudflareBypassService bypass;
    private ElscioneApiClient client;

    @BeforeEach
    void setUp() throws IOException {
        bypass = new CloudflareBypassService(objectMapper,
                "http://127.0.0.1/base/", "", 5, "", false, 24, "", 1, 30);
        requestCount.set(0);
        plannedStatuses.clear();
        lastPlannedStatus = 200;
        successBody = LIST_BODY;
        server = startHttpServer();
        client = buildClient(5);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("listContents retries transient 5xx errors and parses the items")
    void listContents_retriesTransientErrorsAndParsesItems() {
        plannedStatuses.add(500);
        plannedStatuses.add(503);
        plannedStatuses.add(200);

        List<ElscioneApiClient.ApiItem> items = client.listContents("/Officially%20Novels/");

        assertEquals(3, requestCount.get());
        assertEquals(2, items.size());
        assertEquals("/Officially%20Novels/a.epub", items.get(0).href());
        assertEquals(123L, items.get(0).size());
        assertEquals("/Officially%20Novels/Dir/", items.get(1).href());
        assertNull(items.get(1).size());
    }

    @Test
    @DisplayName("listContents retries Cloudflare origin errors (520-526, 530) before giving up")
    void listContents_retriesCloudflareOriginErrors() {
        plannedStatuses.add(520);
        plannedStatuses.add(522);
        plannedStatuses.add(200);

        List<ElscioneApiClient.ApiItem> items = client.listContents("/Officially%20Novels/");

        assertEquals(3, requestCount.get());
        assertEquals(2, items.size());
    }

    @Test
    @DisplayName("listContents throws after exhausting the retry budget on a Cloudflare 52x")
    void listContents_throwsWhen52xRetriesExhausted() {
        client = buildClient(2);
        plannedStatuses.add(522);

        NovelsApiException exception = assertThrows(NovelsApiException.class,
                () -> client.listContents("/Officially%20Novels/"));

        assertTrue(exception.getMessage().contains("HTTP 522 after 2 retries"));
        assertEquals(3, requestCount.get());
    }

    @Test
    @DisplayName("retryAfterMillis parses Retry-After in seconds and ignores HTTP-dates")
    void retryAfterMillis_parsesHeader() {
        assertEquals(2000L, client.retryAfterMillis(responseWithHeader("Retry-After", "2")));
        assertEquals(0L, client.retryAfterMillis(responseWithHeader("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT")));
        assertEquals(0L, client.retryAfterMillis(responseWithHeader("X-Other", "5")));
    }

    @Test
    @DisplayName("pacer spaces requests apart when a minimum interval is configured")
    void pacer_spacesRequests() {
        client = new ElscioneApiClient(bypass, objectMapper,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/Officially%20Novels/",
                5, 0, 2, 2, 8192, 100);
        long start = System.nanoTime();
        client.listContents("/Officially%20Novels/");
        client.listContents("/Officially%20Novels/");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // Intervalo con jitter -25% -> el segundo request espera >= 75ms.
        assertTrue(elapsedMs >= 60,
                "Two paced requests should be spaced by the minimum interval (jittered), took " + elapsedMs + "ms");
        assertEquals(2, requestCount.get());
    }

    private java.net.http.HttpResponse<Void> responseWithHeader(String name, String value) {
        return new java.net.http.HttpResponse<>() {
            @Override
            public int statusCode() {
                return 429;
            }

            @Override
            public java.net.http.HttpHeaders headers() {
                return java.net.http.HttpHeaders.of(java.util.Map.of(name, List.of(value)), (a, b) -> true);
            }

            @Override
            public Void body() {
                return null;
            }

            @Override
            public Optional<java.net.http.HttpResponse<Void>> previousResponse() {
                return Optional.empty();
            }

            @Override
            public java.net.http.HttpRequest request() {
                return java.net.http.HttpRequest.newBuilder(URI.create("http://localhost/")).build();
            }

            @Override
            public URI uri() {
                return URI.create("http://localhost/");
            }

            @Override
            public Optional<javax.net.ssl.SSLSession> sslSession() {
                return Optional.empty();
            }

            @Override
            public java.net.http.HttpClient.Version version() {
                return java.net.http.HttpClient.Version.HTTP_1_1;
            }
        };
    }

    @Test
    @DisplayName("listContents throws after exhausting the retry budget")
    void listContents_throwsWhenRetriesExhausted() {
        plannedStatuses.add(500);

        NovelsApiException exception = assertThrows(NovelsApiException.class,
                () -> client.listContents("/Officially%20Novels/"));

        assertTrue(exception.getMessage().contains("after 5 retries"));
        assertEquals(6, requestCount.get());
    }

    @Test
    @DisplayName("listContents invalidates the Cloudflare session on 403 challenges")
    void listContents_invalidatesCloudflareSessionOnChallenge() {
        CloudflareBypassService bypassMock = mock(CloudflareBypassService.class);
        when(bypassMock.getSession()).thenReturn(
                new CloudflareBypassService.CloudflareSession("UA", "cf_clearance=x", Instant.now()));
        client = buildClientWith(bypassMock, 2);
        plannedStatuses.add(403);

        assertThrows(NovelsApiException.class, () -> client.listContents("/Officially%20Novels/"));

        verify(bypassMock, atLeastOnce()).invalidate();
    }

    @Test
    @DisplayName("listContents refreshes the session and retries when a 200 response is an HTML challenge page")
    void listContents_refreshesSessionWhen200ResponseIsHtmlChallenge() {
        CloudflareBypassService bypassMock = mock(CloudflareBypassService.class);
        when(bypassMock.getSession()).thenReturn(
                new CloudflareBypassService.CloudflareSession("UA", "cf_clearance=x", Instant.now()));
        client = buildClientWith(bypassMock, 2);
        plannedBodies.add("<html>challenge page</html>");
        plannedBodies.add(LIST_BODY);

        List<ElscioneApiClient.ApiItem> items = client.listContents("/Officially%20Novels/");

        assertEquals(2, requestCount.get());
        assertEquals(2, items.size());
        verify(bypassMock, atLeastOnce()).invalidate();
    }

    @Test
    @DisplayName("listContents throws on non-retryable client errors")
    void listContents_throwsOnNonRetryableClientError() {
        plannedStatuses.add(404);

        NovelsApiException exception = assertThrows(NovelsApiException.class,
                () -> client.listContents("/Officially%20Novels/"));

        assertTrue(exception.getMessage().contains("Unexpected HTTP status 404"));
        assertEquals(1, requestCount.get());
    }

    @Test
    @DisplayName("downloadToFile streams content to the target file and reports progress")
    void downloadToFile_writesContentAndReportsProgress() throws IOException {
        successBody = DOWNLOAD_BODY;
        Path target = tempDir.resolve("book.epub");
        AtomicLong progress = new AtomicLong();
        LongConsumer progressConsumer = progress::addAndGet;

        client.downloadToFile(client.getFullUrl("/Officially%20Novels/a.epub"), target, progressConsumer);

        assertEquals(DOWNLOAD_BODY, Files.readString(target));
        assertEquals(DOWNLOAD_BODY.length(), progress.get());
        assertFalse(Files.exists(target.resolveSibling("book.epub.part")));
    }

    @Test
    @DisplayName("downloadToFile retries a 502 before downloading successfully")
    void downloadToFile_retriesBeforeWriting() throws IOException {
        successBody = DOWNLOAD_BODY;
        plannedStatuses.add(502);
        plannedStatuses.add(200);
        Path target = tempDir.resolve("book.epub");

        client.downloadToFile(client.getFullUrl("/Officially%20Novels/a.epub"), target, null);

        assertEquals(2, requestCount.get());
        assertEquals(DOWNLOAD_BODY, Files.readString(target));
    }

    @Test
    @DisplayName("downloadToFile does not leave partial files when the download fails")
    void downloadToFile_throwsWithoutLeavingPartialFiles() {
        plannedStatuses.add(500);
        Path target = tempDir.resolve("book.epub");

        assertThrows(NovelsApiException.class,
                () -> client.downloadToFile(client.getFullUrl("/Officially%20Novels/a.epub"), target, null));

        assertFalse(Files.exists(target));
        assertFalse(Files.exists(target.resolveSibling("book.epub.part")));
    }

    @Test
    @DisplayName("getFullUrl builds absolute URLs against the configured origin")
    void getFullUrl_buildsAbsoluteUrls() {
        assertEquals("http://127.0.0.1:" + server.getAddress().getPort() + "/Officially%20Novels/a.epub",
                client.getFullUrl("/Officially%20Novels/a.epub"));
    }

    private ElscioneApiClient buildClient(int maxRetries) {
        return buildClientWith(bypass, maxRetries);
    }

    private ElscioneApiClient buildClientWith(CloudflareBypassService bypassService, int maxRetries) {
        return new ElscioneApiClient(bypassService, objectMapper,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/Officially%20Novels/",
                maxRetries, 0, 2, 2, 8192, 0);
    }

    private HttpServer startHttpServer() throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            exchange.getRequestBody().close();
            Integer planned = plannedStatuses.poll();
            if (planned != null) {
                lastPlannedStatus = planned;
            }
            int status = lastPlannedStatus;
            if (status == 200) {
                String body = plannedBodies.isEmpty() ? successBody : plannedBodies.poll();
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type",
                        body.startsWith("{") ? "application/json" : "application/octet-stream");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            } else {
                exchange.sendResponseHeaders(status, 0);
                exchange.close();
            }
        });
        httpServer.start();
        return httpServer;
    }
}
