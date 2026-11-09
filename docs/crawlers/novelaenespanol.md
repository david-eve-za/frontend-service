# Crawler Fiche: NovelaEnEspanolCrawler

**Source ID**: `novelaenespanol`  
**Display Name**: Novela en Español  
**Language**: `es`  
**Base URL**: `https://novelaenespanol.com/`  
**Status**: 🟢 Implemented  

---

## 1. Source Analysis

### 1.1 Origin (Python)

| Field | Value |
|-------|-------|
| **Python File** | `sources/es/novelaenespanol.py` |
| **Base Class** | `WpCategoryTemplate` |
| **Version** | lncrawl (analyzed 2026-11-06) |

### 1.2 Site Structure

| Aspect | Details |
|--------|---------|
| **Type** | WordPress category-based (novels = categories, chapters = posts) |
| **Search** | WP REST API: `/wp-json/wp/v2/categories?search={query}` |
| **Novel Page** | Category page + REST API for metadata |
| **Chapter List** | WP REST API: `/wp-json/wp/v2/posts?categories={id}` |
| **Chapter Content** | `.entry-content` |
| **Pagination** | API handles via `X-WP-TotalPages` header |
| **Anti-Bot** | None (standard WP REST API) |
| **Rate Limit** | 60 RPM (generous WP API) |

### 1.3 Python Dependencies

```python
from lncrawl.core import Novel, PageSoup
from lncrawl.templates.wpcategory import WpCategoryTemplate
```

---

## 2. Java Conversion

### 2.1 Implementation Class

```java
@Component
@Qualifier("novelaenespanol")
public class NovelaEnEspanolCrawler implements SourceCrawler {
    // Uses WebClient + Jackson for WP REST API
}
```

### 2.2 Key API Endpoints

| Operation | Endpoint | Parameters |
|-----------|----------|------------|
| **Search** | `GET /wp-json/wp/v2/categories` | `search={query}&per_page=20&_fields=id,name,slug,count,parent,link` |
| **Novel Info** | `GET /wp-json/wp/v2/categories` | `slug={slug}&_fields=id,name,slug,count,description` |
| **Chapters** | `GET /wp-json/wp/v2/posts` | `categories={id}&per_page=100&page={page}&orderby=date&order=asc&_fields=link,title` |
| **Chapter Content** | HTML page | `.entry-content` selector |

### 2.3 Java Dependencies

| Dependency | Purpose |
|------------|---------|
| `spring-boot-starter-webflux` | WebClient for REST API |
| `com.fasterxml.jackson` | JSON parsing |
| `org.jsoup:jsoup` | HTML parsing for chapter content |
| `org.apache.commons:commons-text` | HTML entity decoding |

### 2.4 Rate Limiting Config

```yaml
app:
  crawler:
    sources:
      novelaenespanol:
        base-url: "https://novelaenespanol.com/"
        rate-limit-rpm: 60
        cloudflare-bypass: false
        enabled: true
```

---

## 3. Testing

### 3.1 JSON Fixtures

```
src/test/resources/fixtures/novelaenespanol/
├── search-{query}.json          # Categories search response
├── category-{slug}.json         # Novel category info
├── posts-page-{n}.json          # Chapters (posts) paginated
└── chapter-{id}.html            # Chapter content page
```

### 3.2 Unit Tests

```java
@Test
void search_returnsResults() { ... }

@Test
void getNovelDetails_parsesCategoryApi() { ... }

@Test
void getChapters_fetchesAllPages() { ... }

@Test
void getChapterContent_cleansHtml() { ... }
```

### 3.3 Integration Tests (WireMock)

```java
@Test
void search_viaWireMock_returnsExpected() { ... }

@Test
void getChapters_paginatesCorrectly() { ... }
```

---

## 4. Differences from Python

| Python Behavior | Java Implementation | Reason |
|-----------------|---------------------|--------|
| `WpCategoryTemplate` base | Implements `SourceCrawler` + direct API calls | Unified contract |
| `_api()` helper method | Direct `WebClient` calls | Explicit HTTP |
| `html.unescape()` | `StringEscapeUtils.unescapeHtml4()` | Apache Commons Text |
| `X-WP-TotalPages` header | Read from response headers | Standard HTTP |
| Generator-based pagination | While loop with page counter | Imperative style |

---

## 5. Limitations & Known Issues

| Limitation | Impact | Workaround |
|------------|--------|------------|
| Landing page (novel info post) mixed in chapters | Medium | Skip URL matching novel slug |
| Chapter titles include novel name prefix | Low | Strip prefix in `cleanChapterTitle()` |
| No volume structure (flat chapter list) | Low | Single volume "Volume 1" |
| Relies on WP REST API availability | Medium | Graceful degradation |

---

## 6. Rollback Plan

Same as standard: disable via `app.crawler.sources.novelaenespanol.enabled=false`

---

## 7. Checklist for PR

- [x] `SourceCrawler` implementation complete
- [x] All 5 interface methods implemented
- [x] JSON fixtures saved in test resources
- [x] Unit tests ≥ 90% coverage
- [x] Integration tests with WireMock
- [x] Contract test passes
- [x] Rate limit configured (60 RPM)
- [x] CHANGELOG.md updated
- [x] Fiche saved as `/docs/crawlers/novelaenespanol.md`
- [x] Manual test: search, novel detail, chapter list, chapter content

---

*Generated: 2026-11-06*  
*Source: `sources/es/novelaenespanol.py` + `lncrawl/templates/wpcategory.py`*  
*Implementation: `src/main/java/com/glez/frontendservice/crawler/NovelaEnEspanolCrawler.java`*