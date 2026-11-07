package com.glez.frontendservice.crawler;

import com.glez.frontendservice.config.CrawlerProperties;
import com.glez.frontendservice.config.IntegrationTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import({CrawlerProperties.class, IntegrationTestConfig.class})
class NovelasLigerasCrawlerIntegrationTest {

    @Autowired
    private NovelasLigerasCrawler crawler;

    @Test
    void getAllNovelIds_returnsEmpty() {
        var ids = crawler.getAllNovelIds();
        assertThat(ids).isNotNull().isEmpty();
    }

    @Test
    void healthCheck_returnsTrue() {
        assertThat(crawler.isHealthy()).isTrue();
    }
}