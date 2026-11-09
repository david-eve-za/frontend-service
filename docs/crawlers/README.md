# Crawler Module Documentation

## Overview

This module implements a multi-source novel catalog system for the frontend-service. It provides a unified interface (`SourceCrawler`) for searching, browsing, and retrieving content from multiple novel sources, with automatic frontend menu integration.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      SourceCrawler Interface                 │
├─────────────────────────────────────────────────────────────┤
│  getSourceId()    getDisplayName()   getIcon()               │
│  getLanguage()    getBaseUrl()       getRateLimitRpm()       │
│  requiresLogin()  isHealthy()        getAllNovelIds()        │
│  search()         getNovelDetails()  getChapters()           │
│  getChapterContent() login()        getMetadata()            │
└─────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┼───────────────┐
              ▼               ▼               ▼
┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐
│  SoupTemplate    │ │  WpCategoryTemplate│ │  API-based      │
│  (HTML parsing)  │ │  (WP REST API)    │ │  (JSON API)     │
└──────────────────┘ └──────────────────┘ └──────────────────┘
        │                    │                    │
        ▼                    ▼                    ▼
┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐
│ NovelasLigeras   │ │ NovelaEnEspanol  │ │ SkyNovels        │
│ NovelasLigeraCom │ │ Domentranslations│ │ NovelUpdates     │
│ Foxaholic        │ │                  │ │                  │
└──────────────────┘ └──────────────────┘ └──────────────────┘
        │                    │                    │
        └────────────────────┼────────────────────┘
                             ▼
                    ┌──────────────────┐
                    │  SourceRegistry  │
                    │  (Auto-discovery)│
                    └──────────────────┘
                             │
                             ▼
                    ┌──────────────────┐
                    │  SourceController│
                    │  REST Endpoints  │
                    └──────────────────┘
```

## Implemented Crawlers

| Source ID | Display Name | Pattern | Base URL | Rate Limit |
|-----------|--------------|---------|----------|------------|
| `elscione` | Elscione | Custom (Cloudflare/Anubis) | server.elscione.com | 20 RPM |
| `novelasligeras` | Novelas Ligeras | SoupTemplate (WooCommerce) | novelasligeras.net | 30 RPM |
| `novelaenespanol` | Novela en Español | WpCategoryTemplate (WP REST) | novelaenespanol.com | 60 RPM |
| `novelasligera_com` | Novelas Ligera | SoupTemplate (Elementor) | novelasligera.com | 30 RPM |
| `skynovels` | SkyNovels | API-based (JSON) | skynovels.net | 30 RPM |
| `domentranslations` | Domentranslations | LegacyCrawler (WP.com) | domentranslations.wordpress.com | 60 RPM |
| `novelupdates` | NovelUpdates | API Directory | novelupdates.com | 120 RPM |
| `foxaholic` | Foxaholic | Multi-subdomain + AJAX | foxaholic.com | 30 RPM |

## Quick Start

### 1. Add a New Crawler

```java
@Component
@Qualifier("my-new-source")
public class MyNewSourceCrawler implements SourceCrawler {
    
    private final WebClient webClient;
    
    public MyNewSourceCrawler(WebClient.Builder builder, CrawlerProperties props) {
        this.webClient = builder
            .baseUrl("https://example.com")
            .defaultHeader("User-Agent", "Mozilla/5.0...")
            .build();
    }
    
    @Override public String getSourceId() { return "my-new-source"; }
    @Override public String getDisplayName() { return "My New Source"; }
    @Override public String getIcon() { return "pi pi-book"; }
    @Override public String getLanguage() { return "en"; }
    @Override public String getBaseUrl() { return "https://example.com/"; }
    @Override public int getRateLimitRpm() { return 30; }
    @Override public boolean requiresLogin() { return false; }
    
    @Override
    public List<NovelSearchResult> search(String query) {
        // Implement search using WebClient + Jsoup
    }
    
    @Override
    public NovelDetail getNovelDetails(String novelUrl) {
        // Implement detail extraction
    }
    
    @Override
    public List<ChapterInfo> getChapters(String novelUrl) {
        // Implement chapter list extraction
    }
    
    @Override
    public ChapterContent getChapterContent(String chapterUrl) {
        // Implement content extraction
    }
    
    @Override
    public void login(String username, String password) {
        throw new UnsupportedOperationException();
    }
    
    @Override
    public boolean isHealthy() {
        // Implement health check
        return true;
    }
}
```

### 2. Add Configuration

```yaml
app:
  crawler:
    sources:
      my-new-source:
        base-url: "https://example.com/"
        rate-limit-rpm: 30
        cloudflare-bypass: false
        enabled: true
        tags: ["tag1", "tag2"]
```

### 3. Add Tests

```
src/test/resources/fixtures/my-new-source/
├── search-{query}.html
├── novel-{slug}.html
└── chapter-{id}.html
```

```java
@SpringBootTest
@AutoConfigureWireMock(port = 0)
class MyNewSourceCrawlerIntegrationTest {
    
    @Autowired
    private MyNewSourceCrawler crawler;
    
    @Test
    void search_returnsResults() { ... }
    
    @Test
    void getNovelDetails_parsesAllFields() { ... }
}
```

## REST API

### Base URL: `/api/sources`

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/sources` | List all sources |
| GET | `/api/sources/{sourceId}` | Get source metadata |
| GET | `/api/sources/{sourceId}/search?q=` | Search novels |
| GET | `/api/sources/{sourceId}/novels/{novelId}` | Novel details |
| GET | `/api/sources/{sourceId}/novels/{novelId}/chapters` | Chapter list |
| GET | `/api/sources/{sourceId}/novels/{novelId}/chapters/{chapterId}` | Chapter content |
| GET | `/api/sources/{sourceId}/novels/ids` | All novel IDs |
| GET | `/api/sources/health` | Health check all |
| GET | `/api/sources/{sourceId}/health` | Health check one |

## Frontend Integration

The `AppMenu` component automatically loads sources from `/api/sources` on initialization and builds a dynamic submenu under "Gestor de Novelas".

The `NovelsManager` component accepts a `sourceId` query parameter and displays the catalog for the selected source.

## Configuration

### application.yml

```yaml
app:
  crawler:
    default-rate-limit-rpm: 30
    default-timeout-seconds: 30
    flaresolverr-url: http://localhost:8191/v1
    anubis-solve-enabled: true
    session-cache-path: .crawler-sessions.json
    
    sources:
      elscione:
        base-url: "https://server.elscione.com/..."
        rate-limit-rpm: 20
        cloudflare-bypass: true
        enabled: true
      # ... other sources
```

### Rate Limiting

Each crawler respects its configured `rateLimitRpm`. The implementation should use a token bucket or similar algorithm. Currently a default implementation is provided in the interface.

## Testing

### Unit Tests

```bash
mvn test -Dtest=SourceRegistryTest,SourceMetadataTest
```

### Integration Tests (WireMock)

```bash
mvn test -Dtest=*CrawlerIntegrationTest
```

### Crawler-Specific Tests

Each crawler should have:
- HTML fixtures in `src/test/resources/fixtures/{sourceId}/`
- Unit tests with mocked HTML
- Integration tests with WireMock
- Contract test verifying SourceRegistry registration

## Deployment

### Database Migration

Flyway migration `V1__create_crawler_schema.sql` creates:
- `crawler_source`
- `crawler_novel`
- `crawler_volume`
- `crawler_chapter`
- `crawler_chapter_content`
- `crawler_sync_status`

Run migrations:
```bash
mvn flyway:migrate
```

### Health Checks

```bash
curl http://localhost:8080/api/sources/health
curl http://localhost:8080/api/sources/elscione/health
```

## Troubleshooting

### Crawler Not Appearing in Menu
1. Verify `@Component` and `@Qualifier` annotations
2. Check `app.crawler.sources.{id}.enabled=true`
3. Check `/api/sources` endpoint

### Search Returns Empty
1. Check HTML fixtures match current site structure
2. Verify CSS selectors in crawler implementation
3. Check rate limiting (429 responses)

### 500 Errors on Search
1. Check crawler logs for exceptions
2. Verify `ObjectMapper` bean is available
3. Check network connectivity to source

## Contributing

1. Follow the `TEMPLATE.md` for new crawler fiches
2. Add integration tests with WireMock
3. Update `CHANGELOG.md`
4. Run `mvn test jacoco:check` before PR