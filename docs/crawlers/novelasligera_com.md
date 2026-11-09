# Crawler Fiche: NovelasLigeraComCrawler

**Source ID**: `novelasligera_com`  
**Display Name**: Novelas Ligera  
**Language**: `es`  
**Base URL**: `https://novelasligera.com/`  
**Status**: 🟢 Implemented  

---

## 1. Source Analysis

### 1.1 Origin (Python)

| Field | Value |
|-------|-------|
| **Python File** | `sources/es/novelasligera_com.py` |
| **Base Class** | `SoupTemplate` |
| **Version** | lncrawl (analyzed 2026-11-06) |

### 1.2 Site Structure

| Aspect | Details |
|--------|---------|
| **Type** | Elementor-based WordPress |
| **Search** | `https://novelasligera.com/?s={query}&post_type=product` |
| **Novel Page** | Elementor layout with standard WP selectors |
| **Chapter List** | Multiple Elementor tab/accordion selectors |
| **Chapter Content** | `div.entry-content` |
| **Anti-Bot** | None |
| **Rate Limit** | 30 RPM |

### 1.3 Key Selectors

| Element | Selector |
|---------|----------|
| **Title** | `h1.entry-title` |
| **Cover** | `.elementor-widget-container img` |
| **Chapter List** | `div.elementor-tab-content ul.lcp_catlist li a[href], div.elementor-accordion ul.lcp_catlist li a[href], ul.lcp_catlist li a[href]` |
| **Chapter Body** | `div.entry-content` |
| **Author** | Paragraphs containing "Autor:" |
| **Genres** | Paragraphs containing "Género:" |

---

## 2. Java Conversion

### 2.1 Implementation Class

```java
@Component
@Qualifier("novelasligera_com")
public class NovelasLigeraComCrawler implements SourceCrawler {
    // Jsoup + WebClient
}
```

### 2.2 Rate Limiting Config

```yaml
app:
  crawler:
    sources:
      novelasligera_com:
        base-url: "https://novelasligera.com/"
        rate-limit-rpm: 30
        cloudflare-bypass: false
        enabled: true
```

---

## 3. Differences from Python

| Python Behavior | Java Implementation |
|-----------------|---------------------|
| `SoupTemplate` base | Implements `SourceCrawler` |
| `cleaner.bad_tags` / `bad_css` | Manual `Element.select().remove()` |
| Multiple chapter list selectors | Combined CSS selector with commas |
| WooCommerce product search | Custom search URL with `post_type=product` |

---

## 4. Limitations

| Limitation | Impact |
|------------|--------|
| Elementor selectors may change | Medium |
| WooCommerce search may return products | Low (filtered by post_type) |
| No volume structure | Low (single volume) |

---

## 5. Checklist

- [x] Implementation complete
- [x] All interface methods
- [x] Tests passing
- [x] Config in application.yml
- [x] Fiche saved

---

*Generated: 2026-11-06*  
*Source: `sources/es/novelasligera_com.py`*  
*Implementation: `NovelasLigeraComCrawler.java`*