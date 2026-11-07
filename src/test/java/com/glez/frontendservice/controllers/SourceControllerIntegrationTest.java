package com.glez.frontendservice.controllers;

import com.glez.frontendservice.config.IntegrationTestConfig;
import com.glez.frontendservice.crawler.SourceMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IntegrationTestConfig.class)
class SourceControllerIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void getAllSources_returnsSources() {
        webTestClient.get().uri("/api/sources")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(SourceMetadata.class)
                .value(list -> {
                    assertThat(list).hasSizeGreaterThanOrEqualTo(2);
                    assertThat(list).extracting(SourceMetadata::id).contains("elscione");
                });
    }

    @Test
    void getSource_returnsSource() {
        webTestClient.get().uri("/api/sources/elscione")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("elscione")
                .jsonPath("$.displayName").isEqualTo("Elscione");
    }

    @Test
    void getSource_notFound_returns404() {
        webTestClient.get().uri("/api/sources/unknown")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void healthCheckAll_returnsStatus() {
        webTestClient.get().uri("/api/sources/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.elscione").isEqualTo(true);
    }

    @Test
    void search_returnsResults() {
        webTestClient.get().uri(uriBuilder -> uriBuilder
                .path("/api/sources/elscione/search")
                .queryParam("q", "test")
                .build())
                .exchange()
                .expectStatus().isOk();
    }
}