# ADR 0001: SourceCrawler Architecture for Multi-Source Novel Catalog

**Status**: Proposed  
**Date**: 2026-11-06  
**Author**: David Gonzalez

## Context

The `frontend-service` currently syncs novel metadata from a single source (elscione.com) via `NovelsCatalogSyncService` and `ElscioneApiClient`. The "Gestor de Novelas" frontend displays only this one catalog.

We need to support multiple novel sources (361+ available in lightnovel-crawler) with:
- Dynamic frontend submenu per source
- Extensible backend: new source = new `SourceCrawler` implementation only
- Unified REST API for search, novel details, chapters, content
- Per-source rate limiting, Cloudflare handling, authentication

## Decision

### 1. Core Abstraction: `SourceCrawler` Interface

```java
public interface SourceCrawler {
    String getSourceId();           // kebab-case: "novelasligeras"
    String getDisplayName();        // "Novelas Ligeras"
    String getIcon();               // PrimeNG icon class
    String getLanguage();           // ISO 639-1: "es"
    String getBaseUrl();            // "https://novelasligeras.net/"
    int getRateLimitRpm();          // 0 = unlimited
    boolean requiresLogin();
    
    List<NovelSearchResult> search(String query);
    NovelDetail getNovelDetails(String novelUrl);
    List<ChapterInfo> getChapters(String novelUrl);
    ChapterContent getChapterContent(String chapterUrl);
    void login(String username, String password);
    boolean isHealthy();
}
```

### 2. Automatic Registration via Spring

- Each crawler is a `@Component` with `@Qualifier(sourceId)`
- `SourceRegistry` autowires `List<SourceCrawler>` and builds metadata cache
- No manual registration code needed

### 3. REST Endpoints

```
GET  /api/sources                          → List<SourceMetadata>
GET  /api/sources/{id}/search?q=           → List<NovelSearchResult>
GET  /api/sources/{id}/novels/{novelId}    → NovelDetail
GET  /api/sources/{id}/novels/{novelId}/chapters → List<ChapterInfo>
GET  /api/sources/{id}/novels/{novelId}/chapters/{chapterId} → ChapterContent
POST /api/sources/{id}/login               → Auth (if requiresLogin)
```

### 4. Frontend Integration

- `AppMenu` loads `GET /api/sources` on init
- Dynamic submenu under "Gestor de Novelas" per source
- `NovelsManager` accepts `sourceId` route param / query param
- `SourceSubmenu` component handles loading/error/empty states

### 5. Persistence

- Flyway migrations for `crawler_source`, `crawler_novel`, `crawler_volume`, `crawler_chapter`
- Source-specific tables prefixed with `crawler_` to avoid conflicts with existing `novel`/`novel_volume` (elscione-specific)
- Existing elscione sync continues writing to current tables; new crawlers use generic tables

### 6. Rate Limiting & Cloudflare

- Shared `RateLimiter` bean (token bucket per sourceId)
- `CloudflareBypassService` reused via delegation
- Per-source config in `application.yml` under `app.sources.{sourceId}`

## Alternatives Considered

| Alternative | Pros | Cons | Verdict |
|-------------|------|------|---------|
| **Single monolithic crawler service** | Simple initially | Violates OCP, hard to test, coupling | ❌ Rejected |
| **Separate microservice per source** | Isolation, independent scaling | Operational overhead, network latency | ❌ Overkill |
| **Database-only polymorphism (JSONB)** | Flexible schema | No compile-time safety, harder queries | ❌ Rejected |
| **Spring WebFlux reactive crawlers** | Backpressure, non-blocking | Complexity, debugging harder | ⚠️ Deferred (sync OK for now) |

## Consequences

### Positive
- **Open/Closed Principle**: New source = new class, no existing code changes
- **Testability**: Each crawler unit-testable with HTML fixtures + WireMock
- **Observability**: Per-source metrics, health checks, rate limit exposure
- **Frontend decoupling**: Menu driven by API, zero frontend changes for new sources

### Negative
- **Initial boilerplate**: Interface, DTOs, registry, base configuration
- **Two persistence models**: Elscione-specific (current) + generic (new) — migration path needed
- **Cloudflare complexity**: Not all sources need it; conditional logic in registry

## Implementation Notes

1. **Elscione FIRST**: Port existing `ElscioneApiClient` + `NovelsCatalogSyncService` logic to `ElscioneCrawler implements SourceCrawler` to validate contract
2. **Spanish sources NEXT**: 5 sources, high value, similar patterns (SoupTemplate/WpCategoryTemplate)
3. **HTML Fixtures**: Save real HTML responses in `src/test/resources/fixtures/{sourceId}/` for deterministic tests
4. **WireMock**: Mock HTTP at integration test level; contract tests verify registration
5. **Feature Flags**: Each crawler behind `app.crawler.{sourceId}.enabled` for safe rollout

## Related Decisions

- ADR 0002: Flyway migration strategy for crawler schema (pending)
- ADR 0003: Frontend submenu accessibility standards (pending)
- ADR 0004: Rate limiting algorithm selection (pending)

## References

- [lightnovel-crawler sources](https://github.com/dipu-bd/lightnovel-crawler/tree/master/sources)
- [Spring Boot 4.1 WebFlux](https://spring.io/projects/spring-boot)
- [Jsoup Documentation](https://jsoup.org/)
- [PrimeNG Menu Accessibility](https://primeng.org/menu)