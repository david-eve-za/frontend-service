# Crawler Fiche: DomentranslationsCrawler

**Source ID**: `domentranslations`  
**Display Name**: Domentranslations  
**Language**: `es`  
**Base URL**: `https://domentranslations.wordpress.com/`  
**Status**: Implemented

---

## 1. Source Analysis

### 1.1 Origin (Python)

| Field | Value |
|-------|-------|
| **Python File** | `sources/es/domentranslations.py` (class `CclawTranslations`) |
| **Base Class** | `LegacyCrawler` |
| **Version** | lncrawl (analyzed 2026-11-06) |
| **Multi-domain** | Also handles `cclawtranslations.home.blog` |

### 1.2 Site Structure

| Aspect | Details |
|--------|---------|
| **Type** | WordPress.com blog |
| **Search** | `https://domentranslations.wordpress.com/?s={query}` |
| **Novel Page** | Standard WP post/page |
| **Chapter List** | Links in `div.entry-content a` with date pattern `hostname/YYYY/MM/` |
| **Chapter Content** | `div.entry-content` |
| **Volume Logic** | 100 chapters per volume |
| **Anti-Bot** | None (WP.com) |
| **Rate Limit** | 60 RPM |

### 1.3 Key Selectors

| Element | Selector |
|---------|----------|
| **Title** | `meta[property="og:title"]` -> fallback `h1.entry-title` |
| **Cover** | `meta[property="og:image"]` |
| **Author** | `meta[property="og:site_name"]` |
| **Chapter List** | `div.entry-content a` filtered by `hostname/YYYY/MM/` regex |
| **Chapter Body** | `div.entry-content` |
| **Cleanup** | Remove `.sharedaddy, .inline-ad-slot, .code-block, script, hr, .adsbygoogle` |

---

## 2. Java Conversion

### 2.1 Implementation Class

```java
@Component
@Qualifier("domentranslations")
public class DomentranslationsCrawler implements SourceCrawler {
    // Jsoup + WebClient
}
```

### 2.2 Rate Limiting Config

```yaml
app:
  crawler:
    sources:
      domentranslations:
        base-url: "https://domentranslations.wordpress.com/"
        rate-limit-rpm: 60
        cloudflare-bypass: false
        enabled: true
```

---

## 3. Differences from Python

| Python Behavior | Java Implementation |
|-----------------|---------------------|
| `LegacyCrawler` base | Implements `SourceCrawler` |
| `soup.select_one()` | `doc.selectFirst()` |
| `notoc.extract()` | `element.select().remove()` |
| Volume: 100 chapters each | Same logic |

---

## 4. Limitations

| Limitation | Impact |
|------------|--------|
| Two domains (wordpress.com + home.blog) | Handled by base URL selection |
| Date-based chapter URL pattern | May break if URL structure changes |
| No real volume titles | Generic "Volumen N" |

---

## 5. Checklist

- [x] Implementation complete
- [x] All interface methods
- [x] Tests passing
- [x] Config in application.yml
- [x] Fiche saved

---

*Generated: 2026-11-06*  
*Source: `sources/es/domentranslations.py`*  
*Implementation: `DomentranslationsCrawler.java`*