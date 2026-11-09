package com.glez.frontendservice.config;

import com.glez.frontendservice.services.NvidiaAiService;
import com.glez.frontendservice.services.DashboardStatsService;
import com.glez.frontendservice.services.NovelsCatalogQueryService;
import com.glez.frontendservice.services.ElscioneApiClient;
import com.glez.frontendservice.controllers.DashboardController;
import com.glez.frontendservice.controllers.AiProviderController;
import com.glez.frontendservice.repository.NovelRepository;
import com.glez.frontendservice.repository.NovelVolumeRepository;
import com.glez.frontendservice.crawler.SourceRegistry;
import com.glez.frontendservice.config.RateLimiterConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.Builder;

import org.mockito.Mockito;

/**
 * Test configuration for integration tests.
 * Provides minimal required beans and mocks problematic beans
 * to allow integration tests to run without full application configuration.
 */
@TestConfiguration
public class IntegrationTestConfig {

    @Bean
    @Primary
    public NvidiaAiService nvidiaAiService() {
        return Mockito.mock(NvidiaAiService.class);
    }

    @Bean
    @Primary
    public DashboardStatsService dashboardStatsService() {
        return Mockito.mock(DashboardStatsService.class);
    }

    @Bean
    @Primary
    public DashboardController dashboardController() {
        return Mockito.mock(DashboardController.class);
    }

    @Bean
    @Primary
    public AiProviderController aiProviderController() {
        return Mockito.mock(AiProviderController.class);
    }

    @Bean
    @Primary
    public Builder chatClientBuilder() {
        return Mockito.mock(Builder.class);
    }

    @Bean
    @Primary
    public NovelsCatalogQueryService novelsCatalogQueryService() {
        return Mockito.mock(NovelsCatalogQueryService.class);
    }

    @Bean
    @Primary
    public ElscioneApiClient elscioneApiClient() {
        return Mockito.mock(ElscioneApiClient.class);
    }

    @Bean
    @Primary
    public NovelRepository novelRepository() {
        return Mockito.mock(NovelRepository.class);
    }

    @Bean
    @Primary
    public NovelVolumeRepository novelVolumeRepository() {
        return Mockito.mock(NovelVolumeRepository.class);
    }

    @Bean
    @Primary
    public SourceRegistry sourceRegistry() {
        SourceRegistry registry = Mockito.mock(SourceRegistry.class);
        // Mock the enabled sources list
        Mockito.when(registry.getEnabledSources()).thenReturn(java.util.List.of(
            new com.glez.frontendservice.crawler.SourceMetadata(
                "elscione", "Elscione", "pi pi-book", "es", 
                "https://server.elscione.com/Officially%20Translated%20Light%20Novels/", 20, false, true, java.util.List.of()),
            new com.glez.frontendservice.crawler.SourceMetadata(
                "novelasligeras", "Novelas Ligeras", "pi pi-book", "es", 
                "https://novelasligeras.net/", 30, false, true, java.util.List.of())
        ));
        // Mock health check
        Mockito.when(registry.healthCheckAll()).thenReturn(java.util.Map.of(
            "elscione", true,
            "novelasligeras", true
        ));
        // Mock getSource
        Mockito.when(registry.getSource(Mockito.anyString())).thenReturn(java.util.Optional.empty());
        Mockito.when(registry.getSource(Mockito.eq("elscione"))).thenReturn(java.util.Optional.of(
            Mockito.mock(com.glez.frontendservice.crawler.SourceCrawler.class)
        ));
        Mockito.when(registry.getSource(Mockito.eq("novelasligeras"))).thenReturn(java.util.Optional.of(
            Mockito.mock(com.glez.frontendservice.crawler.SourceCrawler.class)
        ));
        // Mock requireSource
        Mockito.when(registry.requireSource(Mockito.anyString())).thenReturn(
            Mockito.mock(com.glez.frontendservice.crawler.SourceCrawler.class)
        );
        return registry;
    }

    @Bean
    @Primary
    public CrawlerProperties crawlerProperties() {
        CrawlerProperties props = Mockito.mock(CrawlerProperties.class);
        Mockito.when(props.getSources()).thenReturn(java.util.Map.of(
            "elscione", new CrawlerProperties.SourceConfig() {{
                setBaseUrl("https://server.elscione.com/Officially%20Translated%20Light%20Novels/");
                setRateLimitRpm(20);
                setCloudflareBypass(true);
                setEnabled(true);
            }},
            "novelasligeras", new CrawlerProperties.SourceConfig() {{
                setBaseUrl("https://novelasligeras.net/");
                setRateLimitRpm(30);
                setCloudflareBypass(false);
                setEnabled(true);
            }},
            "novelaenespanol", new CrawlerProperties.SourceConfig() {{
                setBaseUrl("https://novelaenespanol.com/");
                setRateLimitRpm(60);
                setCloudflareBypass(false);
                setEnabled(true);
            }},
            "novelasligera_com", new CrawlerProperties.SourceConfig() {{
                setBaseUrl("https://novelasligera.com/");
                setRateLimitRpm(30);
                setCloudflareBypass(false);
                setEnabled(true);
            }},
            "skynovels", new CrawlerProperties.SourceConfig() {{
                setBaseUrl("https://www.skynovels.net/");
                setRateLimitRpm(30);
                setCloudflareBypass(false);
                setEnabled(true);
            }},
            "domentranslations", new CrawlerProperties.SourceConfig() {{
                setBaseUrl("https://domentranslations.wordpress.com/");
                setRateLimitRpm(60);
                setCloudflareBypass(false);
                setEnabled(true);
            }},
            "novelupdates", new CrawlerProperties.SourceConfig() {{
                setBaseUrl("https://www.novelupdates.com/");
                setRateLimitRpm(120);
                setCloudflareBypass(false);
                setEnabled(true);
            }},
            "foxaholic", new CrawlerProperties.SourceConfig() {{
                setBaseUrl("https://www.foxaholic.com/");
                setRateLimitRpm(30);
                setCloudflareBypass(false);
                setEnabled(true);
            }}
        ));
        Mockito.when(props.getDefaultRateLimitRpm()).thenReturn(30);
        Mockito.when(props.getDefaultTimeoutSeconds()).thenReturn(30);
        Mockito.when(props.getDefaultUserAgent()).thenReturn("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        Mockito.when(props.getFlaresolverrUrl()).thenReturn("http://localhost:8191/v1");
        Mockito.when(props.getFlaresolverrTimeoutSeconds()).thenReturn(60);
        Mockito.when(props.isAnubisSolveEnabled()).thenReturn(true);
        Mockito.when(props.getAnubisMaxDifficulty()).thenReturn(24);
        Mockito.when(props.getSessionCachePath()).thenReturn(".crawler-sessions.json");
        return props;
    }
}