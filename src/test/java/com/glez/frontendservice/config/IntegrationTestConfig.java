package com.glez.frontendservice.config;

import com.glez.frontendservice.services.NvidiaAiService;
import com.glez.frontendservice.services.DashboardStatsService;
import com.glez.frontendservice.controllers.DashboardController;
import com.glez.frontendservice.controllers.AiProviderController;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.ai.chat.client.ChatClient;

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
    public com.glez.frontendservice.services.NvidiaAiService nvidiaAiService() {
        return Mockito.mock(com.glez.frontendservice.services.NvidiaAiService.class);
    }

    @Bean
    @Primary
    public com.glez.frontendservice.services.DashboardStatsService dashboardStatsService() {
        return Mockito.mock(com.glez.frontendservice.services.DashboardStatsService.class);
    }

    @Bean
    @Primary
    public com.glez.frontendservice.controllers.DashboardController dashboardController() {
        return Mockito.mock(com.glez.frontendservice.controllers.DashboardController.class);
    }

    @Bean
    @Primary
    public com.glez.frontendservice.controllers.AiProviderController aiProviderController() {
        return Mockito.mock(com.glez.frontendservice.controllers.AiProviderController.class);
    }

    @Bean
    @Primary
    public org.springframework.ai.chat.client.ChatClient.Builder chatClientBuilder() {
        return Mockito.mock(org.springframework.ai.chat.client.ChatClient.Builder.class);
    }

    @Bean
    @Primary
    public com.glez.frontendservice.config.CrawlerProperties crawlerProperties() {
        com.glez.frontendservice.config.CrawlerProperties props = Mockito.mock(com.glez.frontendservice.config.CrawlerProperties.class);
        Mockito.when(props.getSources()).thenReturn(java.util.Map.of(
            "elscione", new com.glez.frontendservice.config.CrawlerProperties.SourceConfig() {{
                setBaseUrl("https://server.elscione.com/Officially%20Translated%20Light%20Novels/");
                setRateLimitRpm(20);
                setCloudflareBypass(true);
                setEnabled(true);
            }},
            "novelasligeras", new com.glez.frontendservice.config.CrawlerProperties.SourceConfig() {{
                setBaseUrl("https://novelasligeras.net/");
                setRateLimitRpm(30);
                setCloudflareBypass(false);
                setEnabled(true);
            }},
            "novelaenespanol", new com.glez.frontendservice.config.CrawlerProperties.SourceConfig() {{
                setBaseUrl("https://novelaenespanol.com/");
                setRateLimitRpm(60);
                setCloudflareBypass(false);
                setEnabled(true);
            }},
            "novelasligera_com", new com.glez.frontendservice.config.CrawlerProperties.SourceConfig() {{
                setBaseUrl("https://novelasligera.com/");
                setRateLimitRpm(30);
                setCloudflareBypass(false);
                setEnabled(true);
            }},
            "skynovels", new com.glez.frontendservice.config.CrawlerProperties.SourceConfig() {{
                setBaseUrl("https://www.skynovels.net/");
                setRateLimitRpm(30);
                setCloudflareBypass(false);
                setEnabled(true);
            }},
            "domentranslations", new com.glez.frontendservice.config.CrawlerProperties.SourceConfig() {{
                setBaseUrl("https://domentranslations.wordpress.com/");
                setRateLimitRpm(60);
                setCloudflareBypass(false);
                setEnabled(true);
            }},
            "novelupdates", new com.glez.frontendservice.config.CrawlerProperties.SourceConfig() {{
                setBaseUrl("https://www.novelupdates.com/");
                setRateLimitRpm(120);
                setCloudflareBypass(false);
                setEnabled(true);
            }},
            "foxaholic", new com.glez.frontendservice.config.CrawlerProperties.SourceConfig() {{
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

    @Bean
    @Primary
    public com.glez.frontendservice.services.NvidiaAiService nvidiaAiService() {
        return Mockito.mock(com.glez.frontendservice.services.NvidiaAiService.class);
    }

    @Bean
    @Primary
    public com.glez.frontendservice.services.DashboardStatsService dashboardStatsService() {
        return Mockito.mock(com.glez.frontendservice.services.DashboardStatsService.class);
    }

    @Bean
    @Primary
    public com.glez.frontendservice.controllers.DashboardController dashboardController() {
        return Mockito.mock(com.glez.frontendservice.controllers.DashboardController.class);
    }

    @Bean
    @Primary
    public com.glez.frontendservice.controllers.AiProviderController aiProviderController() {
        return Mockito.mock(com.glez.frontendservice.controllers.AiProviderController.class);
    }

    @Bean
    @Primary
    public org.springframework.ai.chat.client.ChatClient.Builder chatClientBuilder() {
        return Mockito.mock(org.springframework.ai.chat.client.ChatClient.Builder.class);
    }
}