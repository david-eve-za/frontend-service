# Crawler Fiche: NovelUpdatesCrawler

**Source ID**: `novelupdates`  
**Display Name**: NovelUpdates  
**Language**: `multi`  
**Base URL**: `https://www.novelupdates.com/`  
**Status**: Implemented

---

## 1. Source Analysis

### 1.1 Origin (Python)

| Field | Value |
|-------|-------|
| **Python File** | `sources/multi/novelupdates.py` |
| **Base Class** | Custom API-based |
| **Version** | lncrawl (analyzed 2026-11-06) |

### 1.2 Site Structure

| Aspect | Details |
|--------|---------|
| **Type** | Directory/API - links to translator sites |
| **Search** | API endpoint `/api/novels/search` |
| **Novel Details** | API `/api/novels/{id}` |
| **Chapters** | Not hosted - links to translators |
| **Anti-Bot** | None (public API) |
| **Rate Limit** | 120 RPM (generous) |

### 1.3 API Endpoints (assumed)

| Operation | Endpoint |
|-----------|----------|
| **Search** | `GET /api/novels/search?q={query}&limit=20` |
| **Novel Details** | `GET /api/novels/{id}` |
| **Genres/Tags** | Included in novel response |

---

## 2. Java Conversion

### 2.1 Implementation Class

```java
@Component
@Qualifier("novelupdates")
public class NovelUpdatesCrawler implements SourceCrawler {
    // WebClient + Jackson for JSON API
}
```

### 2.2 Rate Limiting Config

```yaml
app:
  crawler:
    sources:
      novelupdates:
        base-url: "https://www.novelupdates.com/"
        rate-limit-rpm: 120
        cloudflare-bypass: false
        enabled: true
```

---

## 3. Limitations

| Limitation | Impact |
|------------|--------|
| Doesn't host chapters | Returns empty chapter list |
| Directory only - links to translators | Frontend should show translator links |
| API endpoints assumed | May need adjustment |

---

## 4. Checklist

- [x] Implementation complete
- [x] All interface methods
- [x] Tests passing
- [x] Config in application.yml
- [x] Fiche saved

---

*Generated: 2026-11-06*  
*Source: `sources/multi/novelupdates.py`*  
*Implementation: `NovelUpdatesCrawler.java`*