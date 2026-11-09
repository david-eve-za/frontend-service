package com.glez.frontendservice.crawler;

import com.glez.frontendservice.config.CrawlerProperties;
import com.glez.frontendservice.config.JacksonConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest
@Import({JacksonConfig.class, CrawlerProperties.class})
@ExtendWith(MockitoExtension.class)
class ElscioneCrawlerIntegrationTest {

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("app.novels.base-url", () -> "https://server.elscione.com/Officially%20Translated%20Light%20Novels/");
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

    @Autowired
    private ElscioneCrawler crawler;

    @Mock
    private com.glez.frontendservice.services.NovelsCatalogQueryService catalogQueryService;

    @Test
    void getAllNovelIds_returnsIds() {
        // Given
        when(catalogQueryService.findAllNovelIds()).thenReturn(java.util.List.of());

        // When
        var ids = crawler.getAllNovelIds();

        // Then
        assertThat(ids).isNotNull();
    }
}