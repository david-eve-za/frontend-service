package com.glez.frontendservice.config;

import com.glez.frontendservice.services.NvidiaAiService;
import com.glez.frontendservice.services.DashboardStatsService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

/**
 * Test configuration that provides minimal required beans and mocks problematic beans
 * to allow integration tests to run without full application configuration.
 */
@TestConfiguration
public class TestConfig {

    @Bean
    @Primary
    public NvidiaAiService nvidiaAiService() {
        return org.mockito.Mockito.mock(NvidiaAiService.class);
    }

    @Bean
    @Primary
    public DashboardStatsService dashboardStatsService() {
        return org.mockito.Mockito.mock(DashboardStatsService.class);
    }

    @Bean
    @Primary
    public ChatClient.Builder chatClientBuilder() {
        return org.mockito.Mockito.mock(ChatClient.Builder.class);
    }
}