# Crawler Fiche: FoxaholicCrawler

**Source ID**: `foxaholic`  
**Display Name**: Foxaholic  
**Language**: `multi`  
**Base URL**: `https://www.foxaholic.com/`  
**Status**: Implemented

---

## 1. Source Analysis

### 1.1 Origin (Python)

| Field | Value |
|-------|-------|
| **Python File** | `sources/multi/foxaholic.py` |
| **Base Class** | `LegacyCrawler` |
| **Version** | lncrawl (analyzed 2026-11-06) |
| **Multi-subdomain** | foxaholic.com, www.foxaholic.com, 18.foxaholic.com, global.foxaholic.com |

### 1.2 Site Structure

| Aspect | Details |
|--------|---------|
| **Type** | WordPress manga theme (WP-Manga) |
| **Search** | `https://www.foxaholic.com/?s={query}&post_type=wp-manga` |
| **Novel Page** | WP-Manga layout with meta tags |
| **Chapter List** | `.wp-manga-chapter a` (reversed) |
| **AJAX Chapters** | 18.foxaholic.com & global.foxaholic.com use `/wp-admin/admin-ajax.php` |
| **Chapter Content** | `.entry-content_wrap` |
| **Blocked Chapters** | Some redirect via `.text-left` link |
| **Anti-Bot** | None |
| **Rate Limit** | 30 RPM |

### 1.3 Key Selectors

| Element | Selector |
|---------|----------|
| **Search Results** | `.c-tabs-item__content` |
| **Title** | `.post-title h3 a` |
| **Latest Chapter** | `.latest-chap .chapter a` |
| **Status** | `.mg_release .summary-content a` |
| **Novel Title** | `meta[property="og:title"]` |
| **Cover** | `.summary_image a img[data-src]` |
| **Author** | `.author-content a[href*=novel-author]` |
| **Chapter List** | `.wp-manga-chapter a` (reversed) |
| **AJAX Holder** | `#manga-chapters-holder[data-id]` |
| **Chapter Body** | `.entry-content_wrap` |
| **Blocked Redirect** | `.text-left a:contains(Chapter)` |

---

## 2. Java Conversion

### 2.1 Implementation Class

```java
@Component
@Qualifier("foxaholic")
public class FoxaholicCrawler implements SourceCrawler {
    // Jsoup + WebClient with AJAX support
}
```

### 2.2 Key Logic

| Feature | Implementation |
|---------|----------------|
| **Subdomain detection** | Extract base URL from novel URL |
| **AJAX chapters** | POST to `/wp-admin/admin-ajax.php` with `action=manga_get_chapters&manga={id}` |
| **Chapter reversal** | Reverse list for chronological order |
| **Volume grouping** | 100 chapters per volume |
| **Blocked chapter bypass** | Follow redirect link in `.text-left` |

### 2.2 Rate Limiting Config

```yaml
app:
  crawler:
    sources:
      foxaholic:
        base-url: "https://www.foxaholic.com/"
        rate-limit-rpm: 30
        cloudflare-bypass: false
        enabled: true
```

---

## 3. Differences from Python

| Python Behavior | Java Implementation |
|-----------------|---------------------|
| `submit_form()` for AJAX | WebClient POST with form data |
| Multiple base URLs | Detect from novel URL |
| `reversed()` on list | `Collections.reverse()` |
| Lambda for blocked chapter | Explicit selector |

---

## 4. Limitations

| Limitation | Impact |
|------------|--------|
| 4 subdomains with different behavior | Handled by URL detection |
| AJAX chapter loading on 2 subdomains | Implemented with fallback |
| Blocked chapter pattern may change | Fallback to HTML parsing |
| WP-Manga theme updates | Selectors may need updates |

---

## 5. Checklist

- [x] Implementation complete
- [x] All interface methods
- [x] Tests passing
- [x] Config in application.yml
- [x] Fiche saved

---

*Generated: 2026-11-06*  
*Source: `sources/multi/foxaholic.py`*  
*Implementation: `FoxaholicCrawler.java`*