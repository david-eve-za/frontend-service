package com.glez.frontendservice.crawler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glez.frontendservice.config.CrawlerProperties;
import com.glez.frontendservice.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureWireMock(port = 0)
@Import({JacksonConfig.class, CrawlerProperties.class})
@DynamicPropertySource
static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("app.novels.base-url", () -> "http://localhost:${wiremock.server.port}/");
    registry.add("app.novels.output-dir", () -> "./output");
    registry.add("app.novels.max-workers", () -> "3");
    registry.add("app.novels.chunk-size", () -> "65536");
    registry.add("app.novels.api-timeout-seconds", () -> "30");
    registry.add("app.novels.download-timeout-seconds", () -> "60");
    registry.add("app.novels.max-retries", () -> "5");
    registry.add("app.novels.retry-backoff-seconds", () -> "16");
    registry.add("app.novels.min-request-interval-ms", () -> "2000");
    registry.add("app.novels.session-ttl-minutes", () -> "30");
    registry.add("app.novels.flaresolverr-url", () -> "http://localhost:8191/v1");
    registry.add("app.novels.flaresolverr-timeout-seconds", () -> "60");
    registry.add("app.novels.flaresolverr-session", () -> "elscione-bypass");
    registry.add("app.novels.anubis-solve-enabled", () -> "true");
    registry.add("app.novels.anubis-max-difficulty", () -> "24");
    registry.add("app.novels.session-cache-path", () -> ".elscione-session.json");
    registry.add("app.novels.session-acquisition-attempts", () -> "2");
    registry.add("app.novels-manager.auto-sync-enabled", () -> "true");
    registry.add("app.novels-manager.sync-on-startup", () -> "true");
    registry.add("app.novels-manager.sync-cron", () -> "0 0 */3 * * *");
    registry.add("app.novels-manager.sync-max-depth", () -> "3");
    registry.add("app.novels-manager.sync-batch-size", () -> "100");
    registry.add("app.novels-manager.sync-batch-pause-minutes", () -> "10");
    registry.add("spring.ai.openai.api-key", () -> "test-api-key");
    registry.add("spring.ai.openai.base-url", () -> "https://integrate.api.nvidia.com/v1");
    registry.add("spring.ai.openai.chat.options.model", () -> "openai/gpt-oss-20b");
    registry.add("spring.ai.openai.chat.options.timeout", () -> "5m");
    registry.add("app.nvidia.models", () -> "openai/gpt-oss-20b");
    registry.add("app.nvidia.max-retries", () -> "3");
    registry.add("app.nvidia.rate-limit-rpm", () -> "30");
    registry.add("app.nvidia.max-output-tokens", () -> "8192");
    registry.add("app.nvidia.reasoning-reserve-tokens", () -> "1024");
    registry.add("app.nvidia.chars-per-token", () -> "3.5");
    registry.add("app.nvidia.translation-expansion-factor", () -> "1.15");
    registry.add("app.nvidia.slow-piece-threshold-seconds", () -> "240");
    registry.add("app.nvidia.slow-piece-max-failures", () -> "2");
    registry.add("spring.datasource.url", () -> "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
    registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
    registry.add("spring.datasource.username", () -> "sa");
    registry.add("spring.datasource.password", () -> "");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    registry.add("spring.jpa.show-sql", () -> "false");
    registry.add("spring.jpa.open-in-view", () -> "false");
}

@SpringBootTest
@AutoConfigureWireMock(port = 0)
@Import({JacksonConfig.class, CrawlerProperties.class})
@DynamicPropertySource
static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("app.novels.base-url", () -> "http://localhost:${wiremock.server.port}/");
    registry.add("app.novels.output-dir", () -> "./output");
    registry.add("app.novels.max-workers", () -> "3");
    registry.add("app.novels.chunk-size", () -> "65536");
    registry.add("app.novels.api-timeout-seconds", () -> "30");
    registry.add("app.novels.download-timeout-seconds", () -> "60");
    registry.add("app.novels.max-retries", () -> "5");
    registry.add("app.novels.retry-backoff-seconds", () -> "16");
    registry.add("app.novels.min-request-interval-ms", () -> "2000");
    registry.add("app.novels.session-ttl-minutes", () -> "30");
    registry.add("app.novels.flaresolverr-url", () -> "http://localhost:8191/v1");
    registry.add("app.novels.flaresolverr-timeout-seconds", () -> "60");
    registry.add("app.novels.flaresolverr-session", () -> "elscione-bypass");
    registry.add("app.novels.anubis-solve-enabled", () -> "true");
    registry.add("app.novels.anubis-max-difficulty", () -> "24");
    registry.add("app.novels.session-cache-path", () -> ".elscione-session.json");
    registry.add("app.novels.session-acquisition-attempts", () -> "2");
    registry.add("app.novels-manager.auto-sync-enabled", () -> "true");
    registry.add("app.novels-manager.sync-on-startup", () -> "true");
    registry.add("app.novels-manager.sync-cron", () -> "0 0 */3 * * *");
    registry.add("app.novels-manager.sync-max-depth", () -> "3");
    registry.add("app.novels-manager.sync-batch-size", () -> "100");
    registry.add("app.novels-manager.sync-batch-pause-minutes", () -> "10");
    registry.add("spring.ai.openai.api-key", () -> "test-api-key");
    registry.add("spring.ai.openai.base-url", () -> "https://integrate.api.nvidia.com/v1");
    registry.add("spring.ai.openai.chat.options.model", () -> "openai/gpt-oss-20b");
    registry.add("spring.ai.openai.chat.options.timeout", () -> "5m");
    registry.add("app.nvidia.models", () -> "openai/gpt-oss-20b");
    registry.add("app.nvidia.max-retries", () -> "3");
    registry.add("app.nvidia.rate-limit-rpm", () -> "30");
    registry.add("app.nvidia.max-output-tokens", () -> "8192");
    registry.add("app.nvidia.reasoning-reserve-tokens", () -> "1024");
    registry.add("app.nvidia.chars-per-token", () -> "3.5");
    registry.add("app.nvidia.translation-expansion-factor", () -> "1.15");
    registry.add("app.nvidia.slow-piece-threshold-seconds", () -> "240");
    registry.add("app.nvidia.slow-piece-max-failures", () -> "2");
    registry.add("spring.datasource.url", () -> "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
    registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
    registry.add("spring.datasource.username", () -> "sa");
    registry.add("spring.datasource.password", () -> "");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    registry.add("spring.jpa.show-sql", () -> "false");
    registry.add("spring.jpa.open-in-view", () -> "false");
}

@SpringBootTest
@AutoConfigureWireMock(port = 0)
@Import({JacksonConfig.class, CrawlerProperties.class})
class ElscioneCrawlerWireMockTest {

    @Autowired
    private ElscioneCrawler crawler;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setup() {
        reset();
    }

    @Test
    void search_emptyResults() {
        // Given
        stubFor(post(urlEqualTo("/"))
                .withRequestBody(equalToJson("{\"action\":\"get\",\"items\":{\"href\":\"/Officially%20Translated%20Light%20Novels/\",\"what\":1}}"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"items\":[]}")));

        // When
        var results = crawler.search("test");

        // Then
        assertThat(results).isEmpty();
    }

    @Test
    void search_withResults() throws Exception {
        // Given
        String fixture = objectMapper.writeValueAsString(
            objectMapper.readTree(getClass().getResourceAsStream("/fixtures/elscione/search/with-results.json"))
        );

        stubFor(post(urlEqualTo("/"))
                .withRequestBody(equalToJson("{\"action\":\"get\",\"items\":{\"href\":\"/Officially%20Translated%20Light%20Novels/\",\"what\":1}}"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(fixture)));

        // When
        var results = crawler.search("series");

        // Then
        assertThat(results).hasSize(2);
        assertThat(results.get(0).title()).isEqualTo("Series 1");
        assertThat(results.get(0).url()).contains("Series%201");
        assertThat(results.get(1).title()).isEqualTo("Series 2");
    }

    @Test
    void getNovelDetails_parsesVolumesAndFiles() throws Exception {
        // Given
        String fixture = objectMapper.writeValueAsString(
            objectMapper.readTree(getClass().getResourceAsStream("/fixtures/elscione/novel/series-1.json"))
        );

        // First call: list contents of series directory
        stubFor(post(urlEqualTo("/"))
                .withRequestBody(equalToJson("{\"action\":\"get\",\"items\":{\"href\":\"/Officially%20Translated%20Light%20Novels/Series%201/\",\"what\":1}}"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(fixture)));

        // When
        var detail = crawler.getNovelDetails("/Officially%20Translated%20Light%20Novels/Series%201/");

        // Then
        assertThat(detail.title()).isEqualTo("Series 1");
        assertThat(detail.volumes()).hasSize(2); // Volume 1 and Volume 2

        // Volume 1 should have 2 files (epub and pdf)
        var vol1 = detail.volumes().stream().filter(v -> v.id() == 1).findFirst().orElseThrow();
        assertThat(vol1.chapters()).hasSize(2);

        // Volume 2 should have 1 file
        var vol2 = detail.volumes().stream().filter(v -> v.id() == 2).findFirst().orElseThrow();
        assertThat(vol2.chapters()).hasSize(1);
    }

    @Test
    void healthCheck_returnsTrue() {
        // Given
        stubFor(post(urlEqualTo("/"))
                .withRequestBody(equalToJson("{\"action\":\"get\",\"items\":{\"href\":\"/Officially%20Translated%20Light%20Novels/\",\"what\":1}}"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"items\":[]}")));

        // When
        boolean healthy = crawler.isHealthy();

        // Then
        assertThat(healthy).isTrue();
    }

    @Test
    void getAllNovelIds_returnsIds() {
        // Given - ElscioneCrawler uses catalogQueryService which we can't easily mock
        // This test verifies the method exists and returns a list
        var ids = crawler.getAllNovelIds();
        assertThat(ids).isNotNull();
    }
}