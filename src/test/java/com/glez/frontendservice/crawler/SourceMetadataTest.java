package com.glez.frontendservice.crawler;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class SourceMetadataTest {

    @Test
    void createsValidMetadata() {
        SourceMetadata metadata = new SourceMetadata(
                "test-source",
                "Test Source",
                "pi pi-book",
                "es",
                "https://test.com/",
                30,
                false,
                true,
                List.of("tag1", "tag2")
        );

        assertThat(metadata.id()).isEqualTo("test-source");
        assertThat(metadata.displayName()).isEqualTo("Test Source");
        assertThat(metadata.icon()).isEqualTo("pi pi-book");
        assertThat(metadata.language()).isEqualTo("es");
        assertThat(metadata.baseUrl()).isEqualTo("https://test.com/");
        assertThat(metadata.rateLimitRpm()).isEqualTo(30);
        assertThat(metadata.requiresLogin()).isFalse();
        assertThat(metadata.enabled()).isTrue();
        assertThat(metadata.tags()).containsExactly("tag1", "tag2");
    }

    @Test
    void minimalFactoryCreatesValidMetadata() {
        SourceMetadata metadata = SourceMetadata.minimal(
                "minimal",
                "Minimal Source",
                "es",
                "https://minimal.com/"
        );

        assertThat(metadata.id()).isEqualTo("minimal");
        assertThat(metadata.displayName()).isEqualTo("Minimal Source");
        assertThat(metadata.language()).isEqualTo("es");
        assertThat(metadata.baseUrl()).isEqualTo("https://minimal.com/");
        assertThat(metadata.icon()).isEqualTo("pi pi-book");
        assertThat(metadata.rateLimitRpm()).isEqualTo(30);
        assertThat(metadata.requiresLogin()).isFalse();
        assertThat(metadata.enabled()).isTrue();
        assertThat(metadata.tags()).isEmpty();
    }

    @Test
    void nullIdThrowsException() {
        assertThatThrownBy(() -> new SourceMetadata(
                null, "Name", "icon", "es", "https://test.com/", 30, false, true, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id cannot be null or blank");
    }

    @Test
    void blankIdThrowsException() {
        assertThatThrownBy(() -> new SourceMetadata(
                "  ", "Name", "icon", "es", "https://test.com/", 30, false, true, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id cannot be null or blank");
    }

    @Test
    void nullDisplayNameThrowsException() {
        assertThatThrownBy(() -> new SourceMetadata(
                "id", null, "icon", "es", "https://test.com/", 30, false, true, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Display name cannot be null or blank");
    }

    @Test
    void nullLanguageThrowsException() {
        assertThatThrownBy(() -> new SourceMetadata(
                "id", "Name", "icon", null, "https://test.com/", 30, false, true, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Language cannot be null or blank");
    }

    @Test
    void nullBaseUrlThrowsException() {
        assertThatThrownBy(() -> new SourceMetadata(
                "id", "Name", "icon", "es", null, 30, false, true, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Base URL cannot be null or blank");
    }

    @Test
    void negativeRateLimitThrowsException() {
        assertThatThrownBy(() -> new SourceMetadata(
                "id", "Name", "icon", "es", "https://test.com/", -1, false, true, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Rate limit RPM cannot be negative");
    }

    @Test
    void nullTagsDefaultsToEmptyList() {
        SourceMetadata metadata = new SourceMetadata(
                "id", "Name", "icon", "es", "https://test.com/", 30, false, true, null);

        assertThat(metadata.tags()).isEmpty();
    }
}