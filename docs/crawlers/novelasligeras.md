# Crawler Fiche: NovelasLigerasCrawler

**Source ID**: `novelasligeras`  
**Display Name**: Novelas Ligeras  
**Language**: `es`  
**Base URL**: `https://novelasligeras.net/`  
**Status**: 🟢 Implemented  

---

## 1. Source Analysis

### 1.1 Origin (Python)

| Field | Value |
|-------|-------|
| **Python File** | `sources/es/novelasligeras.py` |
| **Base Class** | `LegacyCrawler` |
| **Version** | lncrawl (analyzed 2026-11-06) |

### 1.2 Site Structure

| Aspect | Details |
|--------|---------|
| **Type** | WooCommerce product catalog |
| **Search** | `https://novelasligeras.net/?post_type=product&s={query}` |
| **Novel Page** | Product page with h1.product_title |
| **Chapter List** | Links in `.wpb_wrapper a` with date pattern |
| **Chapter Content** | `.wpb_text_column > div:nth-child(1)` |
| **Pagination** | N/A (all chapters on novel page) |
| **Anti-Bot** | None detected |
| **Rate Limit** | 30 RPM (configured) |

### 1.3 Python Dependencies

```python
from lncrawl.core import Chapter, LegacyCrawler, SearchResult, Volume
import logging
import re
from urllib.parse import urlparse
```

---

## 2. Java Conversion

### 2.1 Implementation Class

```java
@Component
@Qualifier("novelasligeras")
public class NovelasLigerasCrawler implements SourceCrawler {
    // Implementation in com.glez.frontendservice.crawler
}
```

### 2.2 Key Selectors / URL Patterns

| Operation | Selector / Pattern | Notes |
|-----------|-------------------|-------|
| **Search URL** | `https://novelasligeras.net/?post_type=product&s={query}` | WooCommerce product search |
| **Search Item** | `.wf-cell[data-post-id]` | Product grid items |
| **Title** | `h1.product_title` | Product title |
| **Cover** | `meta[property="og:image"]` | Open Graph image |
| **Author** | `tr.woocommerce-product-attributes-item--attribute_pa_escritor a[rel=tag]` | WooCommerce attribute |
| **Synopsis** | `.woocommerce-product-details__short-description` | Product short description |
| **Chapter List** | `.wpb_wrapper a:not([id],[title],[href$='suscripciones/'],[href*='patreon'],[href*='paypal'])` | Filtered links |
| **Volume Pattern** | `-volumen-(\d+)-` | Regex from chapter URL |
| **Chapter Body** | `.wpb_text_column > div:nth-child(1)` | First div in content wrapper |
| **Chapter Title Fix** | `Capitulo` → `Capítulo` | Spanish normalization |

### 2.3 Java Dependencies

| Dependency | Purpose |
|------------|---------|
| `org.jsoup:jsoup` | HTML parsing, CSS selectors |
| `spring-boot-starter-webflux` | WebClient for HTTP |
| `com.fasterxml.jackson` | JSON (for DTOs) |

### 2.4 Rate Limiting Config

```yaml
app:
  crawler:
    sources:
      novelasligeras:
        base-url: "https://novelasligeras.net/"
        rate-limit-rpm: 30
        cloudflare-bypass: false
        enabled: true
```

---

## 3. Testing

### 3.1 HTML Fixtures

```
src/test/resources/fixtures/novelasligeras/
├── search-{query}.html          # Search results page
├── novel-{slug}.html            # Novel detail page
└── chapter-{id}.html            # Chapter content page
```

### 3.2 Unit Tests

```java
@ExtendWith(MockitoExtension.class)
class NovelasLigerasCrawlerTest {
    
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
class NovelasLigerasCrawlerIntegrationTest {
    
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
    assertThat(sources).anyMatch(s -> s.id().equals("novelasligeras"));
}
```

---

## 4. Differences from Python

| Python Behavior | Java Implementation | Reason |
|-----------------|---------------------|--------|
| `LegacyCrawler` base class | Implements `SourceCrawler` interface | Unified contract |
| `ThreadPoolExecutor` for parallel | Virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`) | Structured concurrency |
| `cloudscraper` for Cloudflare | Not needed (no Cloudflare) | Simpler |
| `BeautifulSoup(lxml)` | `Jsoup.parse(html)` | Pure Java |
| `pydantic` models | Java `record` DTOs | Compile-time safety |
| Global `ctx.config` | Spring `@Value` / `@ConfigurationProperties` | Dependency injection |

---

## 5. Limitations & Known Issues

| Limitation | Impact | Workaround |
|------------|--------|------------|
| Search uses WooCommerce product search, may return non-novel products | Medium | Filter by post_type=product in search URL |
| Volume detection via URL regex `-volumen-(\d+)-` | Low | Works for current site structure |
| No chapter pagination (all chapters on one page) | Low | Works for current novels |
| Author/genre in WooCommerce attributes | Low | Standard WP e-commerce pattern |

---

## 6. Rollback Plan

If crawler causes issues in production:
1. Disable via config: `app.crawler.sources.novelasligeras.enabled=false`
2. Remove `@Component` annotation (or use `@Profile("!prod")`)
3. Source disappears from `/api/sources` and frontend menu automatically
4. No database migration rollback needed (source-specific tables unused)

---

## 7. Checklist for PR

- [x] `SourceCrawler` implementation complete
- [x] All 5 interface methods implemented
- [x] HTML fixtures saved in test resources
- [x] Unit tests ≥ 90% coverage on crawler class
- [x] Integration tests with WireMock
- [x] Contract test passes (registration verified)
- [x] Rate limit configured in `application.yml`
- [x] Flyway migration added (shared schema)
- [x] ADR updated if architectural decision
- [x] CHANGELOG.md updated
- [x] This fiche saved as `/docs/crawlers/novelasligeras.md`
- [x] Manual test: search, novel detail, chapter list, chapter content
- [x] chrome-devtools: no console errors, network OK

---

*Generated: 2026-11-06*  
*Source: `sources/es/novelasligeras.py`*  
*Implementation: `src/main/java/com/glez/frontendservice/crawler/NovelasLigerasCrawler.java`*