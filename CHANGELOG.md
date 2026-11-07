# CHANGELOG

## [1.0.0] - 2026-11-06

### Added

#### Core Crawler Framework
- **SourceCrawler Interface** - Unified contract for all novel sources with 10 methods:
  - `getSourceId()`, `getDisplayName()`, `getIcon()`, `getLanguage()`, `getBaseUrl()`
  - `getRateLimitRpm()`, `requiresLogin()`
  - `search()`, `getNovelDetails()`, `getChapters()`, `getChapterContent()`
  - `login()`, `isHealthy()`, `getAllNovelIds()`
- **SourceRegistry** - Auto-registration via Spring `@Component` with `@Qualifier`
- **DTOs** - `SourceMetadata`, `NovelSearchResult`, `NovelDetail`, `VolumeInfo`, `ChapterInfo`, `ChapterContent`
- **CrawlerException** - Structured exception with source context and retryability

#### Crawler Implementations (8 sources)
1. **ElscioneCrawler** - Reference implementation porting existing Cloudflare/Anubis bypass logic
2. **NovelasLigerasCrawler** - SoupTemplate for novelasligeras.net (WooCommerce)
3. **NovelaEnEspanolCrawler** - WpCategoryTemplate for novelaenespanol.com (WP REST API)
4. **NovelasLigeraComCrawler** - SoupTemplate for novelasligera.com (Elementor)
5. **SkyNovelsCrawler** - API-based for skynovels.net (Angular + REST)
6. **DomentranslationsCrawler** - LegacyCrawler for domentranslations.wordpress.com
7. **NovelUpdatesCrawler** - API directory for novelupdates.com
8. **FoxaholicCrawler** - Multi-subdomain with AJAX chapter loading for foxaholic.com

#### REST API Endpoints
- `GET /api/sources` - List all registered sources (for dynamic menu)
- `GET /api/sources/{sourceId}` - Source metadata
- `GET /api/sources/{sourceId}/search?q=` - Search novels
- `GET /api/sources/{sourceId}/novels/{novelId}` - Novel details
- `GET /api/sources/{sourceId}/novels/{novelId}/chapters` - Chapter list
- `GET /api/sources/{sourceId}/novels/{novelId}/chapters/{chapterId}` - Chapter content
- `GET /api/sources/{sourceId}/novels/ids` - All novel IDs (for batch ops)
- `GET /api/sources/health` - Health check all sources
- `GET /api/sources/{sourceId}/health` - Health check specific source

#### Database Schema (Flyway V1)
- `crawler_source` - Source registry table
- `crawler_novel` - Novels per source
- `crawler_volume` - Volumes per novel
- `crawler_chapter` - Chapters per volume
- `crawler_chapter_content` - Cached chapter content
- `crawler_sync_status` - Sync monitoring

#### Frontend Integration
- **Dynamic AppMenu** - Loads sources from `/api/sources` on init
- **Source Submenu** - Under "Gestor de Novelas" with all 8 sources
- **Source Selector** - In NovelsManager toolbar with search/filter
- **Multi-Source Catalog** - NovelsManager accepts `sourceId` parameter
- **Loading/Error/Empty States** - Skeleton, toast, empty messages
- **URL Persistence** - Source selection in query params (`?source=novelasligeras`)

#### Configuration
- **CrawlerProperties** - Type-safe config binding to `app.crawler`
- **application.yml** - 8 pre-configured sources with rate limits
- **JacksonConfig** - Primary ObjectMapper bean with JavaTimeModule

#### Testing
- **SourceRegistryTest** - 7 tests (registration, lookup, filtering, sorting)
- **SourceMetadataTest** - 9 tests (validation, factories, edge cases)
- **HTML Fixtures** - Test resources structure for crawler unit tests
- **WireMock Integration** - Ready for crawler integration tests

#### Documentation
- **ADR-0001** - SourceCrawler Architecture Decision
- **Crawler Fiches** - 8 fiches in `/docs/crawlers/`
- **TEMPLATE.md** - Standardized fiche template
- **Phase 0 Discovery** - Complete analysis report

### Changed
- Updated `NovelsManagerService` to accept optional `sourceId` parameter (backward compatible)
- Updated `SourceController` with new endpoints and health checks
- Extended `SourceCrawler` interface with default `getAllNovelIds()` and `getMetadata()`
- Modified `ElscioneCrawler` to implement `getAllNovelIds()`
- Added Jackson dependencies and `JacksonConfig`

### Dependencies Added
- `org.jsoup:jsoup:1.20.1` - HTML parsing
- `org.springframework.boot:spring-boot-starter-webflux` - WebClient
- `org.flywaydb:flyway-core` - Database migrations
- `org.wiremock:wiremock:3.13.0` - HTTP mocking (test)
- `org.testcontainers:testcontainers:1.20.0` - Integration tests (test)
- `org.apache.commons:commons-text:1.13.0` - HTML entity decoding
- `com.fasterxml.jackson.core:jackson-databind:2.18.3` - JSON processing
- `com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.3` - Java time support

### Configuration
- Added `app.crawler` section in `application.yml` with 8 sources
- Rate limits: 20-120 RPM per source
- Flyway migration path: `src/main/resources/db/migration`
- JaCoCo threshold: 80% (enforced)

### Known Issues
- Overall JaCoCo coverage: 57% (target: 80%)
  - Crawler package: 3% (needs integration tests)
  - Controllers: 25% (needs @SpringBootTest)
- Checkstyle: 6898 violations (pre-existing)
- 2 pre-existing test failures in `TextExtractorServiceFormatsTest`

### Next Steps
- [ ] Add integration tests for all 8 crawlers (WireMock + HTML fixtures)
- [ ] Add @SpringBootTest for SourceController (improve controller coverage)
- [ ] Implement rate limiting with Resilience4j or Bucket4j
- [ ] Add Cloudflare/Anubis abstraction reuse across crawlers
- [ ] Fix Checkstyle violations
- [ ] Add Playwright E2E tests for frontend submenu
- [ ] Achieve 80% JaCoCo coverage