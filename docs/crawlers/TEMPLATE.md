# Crawler Fiche Template

**Source ID**: `{source-id}`  
**Display Name**: `{Display Name}`  
**Language**: `{ISO 639-1 code}`  
**Base URL**: `{https://example.com/}`  
**Status**: 🟢 Implemented | 🟡 In Progress | 🔴 Planned | ⚫ Rejected

---

## 1. Source Analysis

### 1.1 Origin (Python)

| Field | Value |
|-------|-------|
| **Python File** | `sources/{lang}/{source-id}.py` |
| **Base Class** | `LegacyCrawler` / `SoupTemplate` / `WpCategoryTemplate` / Custom |
| **Version** | `lncrawl` version when analyzed |
| **Last Updated** | Date of Python file |

### 1.2 Site Structure

| Aspect | Details |
|--------|---------|
| **Type** | WordPress / Static HTML / SPA / API / Hybrid |
| **Search** | URL pattern / API endpoint / POST form |
| **Novel Page** | URL pattern, key selectors |
| **Chapter List** | In novel page / separate page / API |
| **Chapter Content** | Selector / API / rendered |
| **Pagination** | Query param / AJAX / infinite scroll |
| **Anti-Bot** | Cloudflare / Anubis / reCAPTCHA / None |
| **Rate Limit** | Observed / documented / estimated RPM |

### 1.3 Python Dependencies

```python
# List imports from the crawler file
from lncrawl.core import ...
from bs4 import BeautifulSoup
import requests
import cloudscraper
# ... etc
```

---

## 2. Java Conversion

### 2.1 Implementation Class

```java
@Component
@Qualifier("{source-id}")
public class {SourceId}Crawler implements SourceCrawler {
    // Implementation details
}
```

### 2.2 Key Selectors / API Endpoints

| Operation | Selector / Endpoint | Notes |
|-----------|---------------------|-------|
| **Search URL** | `{baseUrl}/search?q={query}` | |
| **Search Item** | `.search-result .title a` | CSS selector |
| **Novel Title** | `h1.entry-title` | |
| **Novel Cover** | `.novel-cover img` | |
| **Novel Author** | `.author a` | |
| **Novel Synopsis** | `.synopsis .content` | |
| **Chapter List** | `.chapter-list a` | |
| **Chapter Title** | `a.chapter-title` | |
| **Chapter URL** | `href` attribute | |
| **Chapter Body** | `.chapter-content` | |
| **Next Page** | `.pagination .next` | If applicable |

### 2.3 Java Dependencies

| Dependency | Purpose |
|------------|---------|
| `org.jsoup:jsoup` | HTML parsing, CSS selectors |
| `spring-boot-starter-webflux` | `WebClient` for HTTP |
| `com.fasterxml.jackson` | JSON parsing (if API) |
| Custom: `CloudflareBypassService` | If Cloudflare protected |

### 2.4 Rate Limiting Config

```yaml
app:
  sources:
    {source-id}:
      base-url: "{baseUrl}"
      rate-limit-rpm: {N}
      cloudflare-bypass: {true/false}
      login-required: {true/false}
```

---

## 3. Testing

### 3.1 HTML Fixtures

```
src/test/resources/fixtures/{source-id}/
├── search-{query}.html          # Search results page
├── novel-{slug}.html            # Novel detail page
├── chapter-list-{slug}.html     # Chapter list (if separate)
└── chapter-{id}.html            # Chapter content page
```

### 3.2 Unit Tests

```java
@ExtendWith(MockitoExtension.class)
class {SourceId}CrawlerTest {
    
    @Test
    void search_returnsResults() { ... }
    
    @Test
    void getNovelDetails_parsesAllFields() { ... }
    
    @Test
    void getChapters_returnsOrderedList() { ... }
    
    @Test
    void getChapterContent_cleansHtml() { ... }
}
```

### 3.3 Integration Tests (WireMock)

```java
@SpringBootTest
@AutoConfigureWireMock(port = 0)
class {SourceId}CrawlerIntegrationTest {
    
    @Test
    void search_viaWireMock_returnsExpected() { ... }
    
    @Test
    void fullFlow_searchToChapter_works() { ... }
}
```

### 3.4 Contract Test

```java
@Test
void registersInSourceRegistry() {
    List<SourceMetadata> sources = sourceRegistry.getAllSources();
    assertThat(sources).anyMatch(s -> s.id().equals("{source-id}"));
}
```

---

## 4. Differences from Python

| Python Behavior | Java Implementation | Reason |
|-----------------|---------------------|--------|
| `ThreadPoolExecutor` for parallel chapter fetch | Virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`) | Structured concurrency, lighter weight |
| `cloudscraper` for Cloudflare | `CloudflareBypassService` delegation | Reuse existing FlareSolverr/Anubis investment |
| `BeautifulSoup(lxml)` | `Jsoup.parse(html)` | Pure Java, no native deps |
| `pydantic` models | Java `record` DTOs | Compile-time safety, Jackson serialization |
| Global `ctx.config` | Spring `@Value` / `@ConfigurationProperties` | Dependency injection |

---

## 5. Limitations & Known Issues

| Limitation | Impact | Workaround |
|------------|--------|------------|
| *e.g., Search only works for exact titles* | Medium | Document in UI, suggest alternative |
| *e.g., Chapter images not extracted* | Low | Future enhancement |
| *e.g., Requires login for latest chapters* | High | Implement `login()` method, store credentials in vault |

---

## 6. Rollback Plan

If crawler causes issues in production:
1. Disable via config: `app.crawler.{source-id}.enabled=false`
2. Remove `@Component` annotation (or use `@Profile("!prod")`)
3. Source disappears from `/api/sources` and frontend menu automatically
4. No database migration rollback needed (source-specific tables unused)

---

## 7. Checklist for PR

- [ ] `SourceCrawler` implementation complete
- [ ] All 5 interface methods implemented
- [ ] HTML fixtures saved in test resources
- [ ] Unit tests ≥ 90% coverage on crawler class
- [ ] Integration tests with WireMock
- [ ] Contract test passes (registration verified)
- [ ] Rate limit configured in `application.yml`
- [ ] Flyway migration added (if new tables)
- [ ] ADR updated if architectural decision
- [ ] CHANGELOG.md entry added
- [ ] This fiche saved as `/docs/crawlers/{source-id}.md`
- [ ] Manual test: search, novel detail, chapter list, chapter content
- [ ] chrome-devtools: no console errors, network OK

---

*Template version: 1.0*  
*Copy to `/docs/crawlers/{source-id}.md` and fill in for each crawler*