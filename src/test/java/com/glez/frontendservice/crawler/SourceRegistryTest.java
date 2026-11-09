package com.glez.frontendservice.crawler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class SourceRegistryTest {

    @Test
    void registersCrawlersFromSpringContext() {
        // Given - create concrete test implementations
        SourceCrawler crawler1 = new TestSourceCrawler("test-source-1", "Test Source 1", "es", "https://test1.com/");
        SourceCrawler crawler2 = new TestSourceCrawler("test-source-2", "Test Source 2", "en", "https://test2.com/", 60, true);

        // Create registry
        SourceRegistry registry = new SourceRegistry(mock(ApplicationContext.class));
        
        // Manually register since we're not using Spring's @Autowired
        registry.register(crawler1);
        registry.register(crawler2);

        // Then
        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.getAllSourceIds()).containsExactlyInAnyOrder("test-source-1", "test-source-2");
        
        List<SourceMetadata> sources = registry.getAllSources();
        assertThat(sources).hasSize(2);
        assertThat(sources).extracting(SourceMetadata::id).containsExactlyInAnyOrder("test-source-1", "test-source-2");
        assertThat(sources).extracting(SourceMetadata::displayName).containsExactlyInAnyOrder("Test Source 1", "Test Source 2");
    }

    @Test
    void getSourceReturnsOptional() {
        SourceRegistry registry = new SourceRegistry(mock(ApplicationContext.class));
        
        SourceCrawler crawler = new TestSourceCrawler("source-a", "Source A", "es", "https://source-a.com/");
        registry.register(crawler);

        assertThat(registry.getSource("source-a")).isPresent();
        assertThat(registry.getSource("source-a").get()).isSameAs(crawler);
        assertThat(registry.getSource("unknown")).isEmpty();
    }

    @Test
    void requireSourceThrowsWhenNotFound() {
        SourceRegistry registry = new SourceRegistry(mock(ApplicationContext.class));
        
        assertThatThrownBy(() -> registry.requireSource("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown source: unknown");
    }

    @Test
    void unregisterRemovesCrawler() {
        SourceRegistry registry = new SourceRegistry(mock(ApplicationContext.class));
        
        SourceCrawler crawler = new TestSourceCrawler("to-remove", "To Remove", "es", "https://remove.com/");
        registry.register(crawler);

        assertThat(registry.unregister("to-remove")).isTrue();
        assertThat(registry.size()).isZero();
        assertThat(registry.getSource("to-remove")).isEmpty();
    }

    @Test
    void duplicateRegistrationThrows() {
        SourceRegistry registry = new SourceRegistry(mock(ApplicationContext.class));
        
        SourceCrawler crawler1 = new TestSourceCrawler("duplicate", "Duplicate 1", "es", "https://dup1.com/");
        SourceCrawler crawler2 = new TestSourceCrawler("duplicate", "Duplicate 2", "es", "https://dup2.com/");
        registry.register(crawler1);

        assertThatThrownBy(() -> registry.register(crawler2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    void getSourcesByLanguageFiltersCorrectly() {
        SourceRegistry registry = new SourceRegistry(mock(ApplicationContext.class));
        
        SourceCrawler esCrawler = new TestSourceCrawler("es-source", "ES Source", "es", "https://es.com/");
        SourceCrawler enCrawler = new TestSourceCrawler("en-source", "EN Source", "en", "https://en.com/");

        registry.register(esCrawler);
        registry.register(enCrawler);

        assertThat(registry.getSourcesByLanguage("es")).hasSize(1);
        assertThat(registry.getSourcesByLanguage("es").get(0).id()).isEqualTo("es-source");
        assertThat(registry.getSourcesByLanguage("en")).hasSize(1);
        assertThat(registry.getSourcesByLanguage("fr")).isEmpty();
    }

    @Test
    void metadataCacheSortedByDisplayName() {
        SourceRegistry registry = new SourceRegistry(mock(ApplicationContext.class));
        
        SourceCrawler zCrawler = new TestSourceCrawler("z-source", "Zebra Source", "es", "https://z.com/");
        SourceCrawler aCrawler = new TestSourceCrawler("a-source", "Alpha Source", "es", "https://a.com/");

        registry.register(zCrawler);
        registry.register(aCrawler);

        List<String> names = registry.getAllSources().stream()
                .map(SourceMetadata::displayName)
                .toList();
        
        assertThat(names).containsExactly("Alpha Source", "Zebra Source");
    }

    @Test
    void disabledSourcesExcludedFromCache() {
        SourceRegistry registry = new SourceRegistry(mock(ApplicationContext.class));
        
        SourceCrawler enabledCrawler = new TestSourceCrawler("enabled", "Enabled Source", "es", "https://enabled.com/");
        // Create disabled crawler by overriding metadata
        SourceCrawler disabledCrawler = new TestSourceCrawler("disabled", "Disabled Source", "es", "https://disabled.com/") {
            @Override
            public SourceMetadata getMetadata() {
                return new SourceMetadata(
                        "disabled", "Disabled Source", "pi pi-book", "es", "https://disabled.com/", 30, false, false, List.of());
            }
        };

        registry.register(enabledCrawler);
        registry.register(disabledCrawler);

        assertThat(registry.getAllSources()).hasSize(1);
        assertThat(registry.getAllSources().get(0).id()).isEqualTo("enabled");
        assertThat(registry.size()).isEqualTo(2); // Still tracked internally
    }

    // Simple test implementation of SourceCrawler
    static class TestSourceCrawler implements SourceCrawler {
        private final String sourceId;
        private final String displayName;
        private final String language;
        private final String baseUrl;
        private final int rateLimitRpm;
        private final boolean requiresLogin;

        public TestSourceCrawler(String sourceId, String displayName, String language, String baseUrl) {
            this(sourceId, displayName, language, baseUrl, 30, false);
        }

        public TestSourceCrawler(String sourceId, String displayName, String language, String baseUrl, int rateLimitRpm, boolean requiresLogin) {
            this.sourceId = sourceId;
            this.displayName = displayName;
            this.language = language;
            this.baseUrl = baseUrl;
            this.rateLimitRpm = rateLimitRpm;
            this.requiresLogin = requiresLogin;
        }

        @Override public String getSourceId() { return sourceId; }
        @Override public String getDisplayName() { return displayName; }
        @Override public String getIcon() { return "pi pi-book"; }
        @Override public String getLanguage() { return language; }
        @Override public String getBaseUrl() { return baseUrl; }
        @Override public int getRateLimitRpm() { return rateLimitRpm; }
        @Override public boolean requiresLogin() { return requiresLogin; }
        @Override public List<NovelSearchResult> search(String query) { return List.of(); }
        @Override public NovelDetail getNovelDetails(String novelUrl) { return null; }
        @Override public List<ChapterInfo> getChapters(String novelUrl) { return List.of(); }
        @Override public ChapterContent getChapterContent(String chapterUrl) { return null; }
        @Override public void login(String username, String password) { }
        @Override public boolean isHealthy() { return true; }
    }
}