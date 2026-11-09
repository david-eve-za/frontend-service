package com.glez.frontendservice.crawler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glez.frontendservice.config.CrawlerProperties;
import com.glez.frontendservice.config.IntegrationTestConfig;
import com.glez.frontendservice.config.JacksonConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import({JacksonConfig.class, CrawlerProperties.class, IntegrationTestConfig.class})
class NovelUpdatesCrawlerIntegrationTest {

    @Autowired
    private NovelUpdatesCrawler crawler;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void getAllNovelIds_returnsEmpty() {
        var ids = crawler.getAllNovelIds();
        assertThat(ids).isNotNull().isEmpty();
    }

    @Test
    void getChapters_returnsEmptyForDirectory() {
        var chapters = crawler.getChapters("https://www.novelupdates.com/series/test-novel-1/");
        assertThat(chapters).isEmpty();
    }

    @Test
    void getChapterContent_returnsFailureForDirectory() {
        var content = crawler.getChapterContent("https://www.novelupdates.com/series/test-novel-1/chapter-1");
        assertThat(content.success()).isFalse();
        assertThat(content.errorMessage()).contains("directory");
    }

    @Test
    void healthCheck_returnsTrue() {
        assertThat(crawler.isHealthy()).isTrue();
    }
}