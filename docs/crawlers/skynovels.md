# Crawler Fiche: SkyNovelsCrawler

**Source ID**: `skynovels`  
**Display Name**: SkyNovels  
**Language**: `es`  
**Base URL**: `https://www.skynovels.net/`  
**Status**: 🟢 Implemented  

---

## 1. Source Analysis

### 1.1 Origin (Python)

| Field | Value |
|-------|-------|
| **Python File** | `sources/es/skynovels.py` |
| **Base Class** | `SoupTemplate` (API-based) |
| **Version** | lncrawl (analyzed 2026-11-06) |

### 1.2 Site Structure

| Aspect | Details |
|--------|---------|
| **Type** | API-based (Angular frontend + REST backend) |
| **API Base** | `https://api.skynovels.net/api` |
| **Image API** | `https://api.skynovels.net/api/get-image` |
| **Catalogue** | Single request for all novels (`/novels?limit=500`) |
| **Search** | Client-side filtering on cached catalogue |
| **Novel Details** | `/novel-chapters/{id}` |
| **Chapter Content** | `/chapters/{id}` |
| **Anti-Bot** | None (public API) |
| **Rate Limit** | 30 RPM |

### 1.3 Python Dependencies

```python
import unicodedata
from lncrawl.core import Chapter, Novel, PageSoup, SearchResult, SoupTemplate
```

---

## 2. Java Conversion

### 2.1 Implementation Class

```java
@Component
@Qualifier("skynovels")
public class SkyNovelsCrawler implements SourceCrawler {
    // WebClient + Jackson for JSON API
    // Catalogue cached in memory
}
```

### 2.2 Key API Endpoints

| Operation | Endpoint | Notes |
|-----------|----------|-------|
| **Catalogue** | `GET /api/novels?limit=500` | Cached on first search |
| **Search** | Client-side filter on catalogue | `fold()` for accent-insensitive |
| **Novel Details** | `GET /api/novel-chapters/{id}` | Returns novel + chapters |
| **Chapter Content** | `GET /api/chapters/{id}` | Returns JSON with `chp_content` |
| **Cover Image** | `GET /api/get-image/{image}/novels/false` | Dynamic image API |

### 2.3 Java Dependencies

| Dependency | Purpose |
|------------|---------|
| `spring-boot-starter-webflux` | WebClient for API |
| `com.fasterxml.jackson` | JSON parsing |
| `org.jsoup:jsoup` | HTML cleaning for synopsis/chapter content |

### 2.4 Rate Limiting Config

```yaml
app:
  crawler:
    sources:
      skynovels:
        base-url: "https://www.skynovels.net/"
        rate-limit-rpm: 30
        cloudflare-bypass: false
        enabled: true
```

---

## 3. Differences from Python

| Python Behavior | Java Implementation |
|-----------------|---------------------|
| `unicodedata.normalize` for search | `Normalizer.normalize` + `Character.NON_SPACING_MARK` |
| Catalogue cached in `self.catalogue` | `volatile List<JsonNode> catalogueCache` with sync |
| `PageSoup.create(html)` | `Jsoup.parse(html)` |
| Generator-based search | Stream filter on cached list |

---

## 4. Limitations

| Limitation | Impact |
|------------|--------|
| Catalogue cached indefinitely | Low (manual refresh on restart) |
| No real chapter titles (API only has numbers) | Low (UI shows "Capítulo N") |
| Volume grouping not reliable | Low (single volume) |
| Synopsis only in catalogue, not detail API | Handled by cross-referencing |

---

## 5. Checklist

- [x] Implementation complete
- [x] All interface methods
- [x] Tests passing
- [x] Config in application.yml
- [x] Fiche saved

---

*Generated: 2026-11-06*  
*Source: `sources/es/skynovels.py`*  
*Implementation: `SkyNovelsCrawler.java`*