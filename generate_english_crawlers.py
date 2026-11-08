#!/usr/bin/env python3
"""
Generador automático de crawlers Java desde Python (lightnovel-crawler).

Lee todos los crawlers en inglés de sources/_index.json y genera esqueletos Java
basados en patrones detectados en el código Python.
"""

import json
import re
import os
from pathlib import Path
from dataclasses import dataclass
from typing import List, Optional, Dict, Any

# ─── Configuración ──────────────────────────────────────────────────────

PYTHON_SOURCES_ROOT = Path("/Users/davidgonzalez/Develop/lightnovel-crawler/sources")
INDEX_JSON = PYTHON_SOURCES_ROOT / "_index.json"
JAVA_OUTPUT_DIR = Path("/Users/davidgonzalez/Develop/frontend-service/src/main/java/com/glez/frontendservice/crawler/generated")

JAVA_PACKAGE = "com.glez.frontendservice.crawler.generated"
JAVA_BASE_CLASS = "AbstractGeneratedCrawler"  # Clase base que crearemos

# ─── Modelos ────────────────────────────────────────────────────────────


@dataclass
class CrawlerMeta:
    source_id: str
    file_path: str
    language: str
    can_search: bool
    can_login: bool
    has_mtl: bool
    rate_limit: float
    base_urls: List[str]


@dataclass
class ParsedCrawler:
    meta: CrawlerMeta
    class_name: str
    base_class: str  # NovelFullTemplate, SoupTemplate, LegacyCrawler, etc.
    base_urls: List[str]
    selectors: Dict[str, str]  # CSS selectors
    search_url_template: Optional[str]
    custom_methods: List[str]  # Nombres de métodos personalizados
    cleaner_rules: Dict[str, List[str]]  # bad_css, bad_text, etc.
    requires_js: bool
    uses_api: bool


# ─── Utilidades ─────────────────────────────────────────────────────────


def to_java_class_name(source_id: str) -> str:
    """Convierte source_id (kebab-case) a PascalCase para clase Java."""
    # Sanitize: replace dots, prefix numbers with underscore
    sanitized = source_id.replace(".", "").replace("_", "-")
    parts = sanitized.split("-")
    # Prefix parts that start with digit
    parts = ["_" + p if p and p[0].isdigit() else p for p in parts]
    return "".join(p.capitalize() for p in parts) + "Crawler"


def to_java_variable(name: str) -> str:
    """Convierte a camelCase para variables Java."""
    parts = name.replace("_", "-").split("-")
    return parts[0].lower() + "".join(p.capitalize() for p in parts[1:])


def escape_java_string(s: str) -> str:
    # Escape for Java string literal
    s = s.replace("\\", "\\\\")  # backslash first
    s = s.replace('"', '\\"')       # double quote
    s = s.replace("\n", "\\n")     # newline
    s = s.replace("\r", "\\r")     # carriage return
    s = s.replace("\t", "\\t")     # tab
    return s


# ─── Parser de crawlers Python ─────────────────────────────────────────


def parse_python_crawler(py_path: Path) -> ParsedCrawler:
    """Extrae información estructural de un crawler Python."""
    content = py_path.read_text(encoding="utf-8")

    # Detectar clase base
    base_class = "SoupTemplate"  # default
    if "NovelFullTemplate" in content:
        base_class = "NovelFullTemplate"
    elif "LegacyCrawler" in content:
        base_class = "LegacyCrawler"
    elif "SoupTemplate" in content:
        base_class = "SoupTemplate"
    elif "MadaraTemplate" in content:
        base_class = "MadaraTemplate"
    elif "WordpressTemplate" in content:
        base_class = "WordpressTemplate"
    elif "FreeWebnovelTemplate" in content:
        base_class = "FreeWebnovelTemplate"
    elif "BloggerTemplate" in content:
        base_class = "BloggerTemplate"
    elif "NovelPubTemplate" in content:
        base_class = "NovelPubTemplate"
    elif "MangaStreamTemplate" in content:
        base_class = "MangaStreamTemplate"
    elif "NovelMTLTemplate" in content:
        base_class = "NovelMTLTemplate"

    # Extraer base_url
    base_urls = []
    # Patrón: base_url = [...] o base_url = "..."
    for match in re.finditer(r'base_url\s*=\s*(\[.*?\]|".*?")', content, re.DOTALL):
        val = match.group(1).strip()
        if val.startswith("["):
            # Lista
            urls = re.findall(r'"([^"]+)"', val)
            base_urls.extend(urls)
        else:
            base_urls.append(val.strip('"'))

    # Si no hay base_url, buscar en class definition
    if not base_urls:
        for match in re.finditer(r'class\s+\w+\s*\(.*?\)\s*:', content, re.DOTALL):
            class_block = content[match.end():match.end()+500]
            for m in re.finditer(r'base_url\s*=\s*(\[.*?\]|".*?")', class_block, re.DOTALL):
                val = m.group(1).strip()
                if val.startswith("["):
                    urls = re.findall(r'"([^"]+)"', val)
                    base_urls.extend(urls)
                else:
                    base_urls.append(val.strip('"'))

    # Extraer selectores (patrón *_selector = "...")
    selectors = {}
    selector_pattern = r'(\w+_selector)\s*=\s*["\']([^"\']+)["\']'
    for match in re.finditer(selector_pattern, content):
        selectors[match.group(1)] = match.group(2)

    # Detectar search_url template
    search_url = None
    if "build_search_url" in content:
        search_url = "build_search_url (método personalizado)"
    elif "search_url" in content:
        for match in re.finditer(r'search_url\s*=\s*["\']([^"\']+)["\']', content):
            search_url = match.group(1)
            break

    # Detectar métodos personalizados
    custom_methods = []
    for match in re.finditer(r'def\s+(\w+)\s*\(', content):
        method = match.group(1)
        if method not in ["__init__", "initialize", "search", "read_novel", "download_chapter",
                          "parse_chapter_body", "select_chapter_tags", "parse_novel_info",
                          "parse_chapter_list", "get_chapters", "get_novel_details"]:
            custom_methods.append(method)

    # Detectar reglas de limpieza
    cleaner_rules = {"bad_css": [], "bad_text": [], "bad_regex": []}
    if "cleaner.bad_css.update" in content:
        for match in re.finditer(r'cleaner\.bad_css\.update\s*\(\s*\[(.*?)\]', content, re.DOTALL):
            items = re.findall(r'["\']([^"\']+)["\']', match.group(1))
            cleaner_rules["bad_css"].extend(items)
    if "bad_text" in content and "re_cleaner" in content:
        for match in re.finditer(r'bad_text\s*=\s*\[(.*?)\]', content, re.DOTALL):
            items = re.findall(r'["\']([^"\']+)["\']', match.group(1))
            cleaner_rules["bad_text"].extend(items)

    # Detectar si usa JS rendering
    requires_js = "render_soup" in content or "browser" in content or "wait_for" in content

    # Detectar si usa API JSON
    uses_api = "get_json" in content or "scraper.get_json" in content

    # Obtener meta del índice
    with INDEX_JSON.open() as f:
        index = json.load(f)

    crawlers_index = index.get("crawlers", {})
    meta = None
    for cid, info in crawlers_index.items():
        if info["file_path"] == str(py_path.relative_to(PYTHON_SOURCES_ROOT)):
            meta = CrawlerMeta(
                source_id=cid,
                file_path=info["file_path"],
                language=info["language"],
                can_search=info["can_search"],
                can_login=info["can_login"],
                has_mtl=info["has_mtl"],
                rate_limit=info["request_rate_limit"],
                base_urls=info.get("base_urls", base_urls),
            )
            break

    if not meta:
        # Fallback
        source_id = py_path.stem
        meta = CrawlerMeta(
            source_id=source_id,
            file_path=str(py_path.relative_to(PYTHON_SOURCES_ROOT)),
            language="en",
            can_search=False,
            can_login=False,
            has_mtl=False,
            rate_limit=3.0,
            base_urls=base_urls,
        )

    class_name = to_java_class_name(meta.source_id)

    return ParsedCrawler(
        meta=meta,
        class_name=class_name,
        base_class=base_class,
        base_urls=base_urls or meta.base_urls,
        selectors=selectors,
        search_url_template=search_url,
        custom_methods=custom_methods,
        cleaner_rules=cleaner_rules,
        requires_js=requires_js,
        uses_api=uses_api,
    )


# ─── Generador de código Java ───────────────────────────────────────────


def generate_java_crawler(parsed: ParsedCrawler) -> str:
    """Genera el código Java para un crawler."""

    meta = parsed.meta
    class_name = parsed.class_name
    source_id = meta.source_id
    display_name = source_id.replace("_", " ").replace("-", " ").title()

    # Determinar icono por defecto
    icon = "pi pi-book"
    if "manga" in source_id or "scan" in source_id:
        icon = "pi pi-images"
    elif "mtl" in source_id or "translate" in source_id:
        icon = "pi pi-globe"
    elif "novel" in source_id:
        icon = "pi pi-book"

    # Base URLs para Java
    base_urls_java = ", ".join('"' + u + '"' for u in parsed.base_urls[:3])
    if not base_urls_java:
        base_urls_java = '"https://' + source_id.replace("_", ".").replace("-", ".") + '.com/"'

    # Selectores como constantes
    selector_fields = []
    for key, value in parsed.selectors.items():
        java_key = to_java_variable(key).upper()
        selector_fields.append('    private static final String ' + java_key + ' = "' + escape_java_string(value) + '";')

    # Cleaner rules
    cleaner_init = []
    if parsed.cleaner_rules["bad_css"]:
        items = ", ".join('"' + escape_java_string(x) + '"' for x in parsed.cleaner_rules["bad_css"])
        cleaner_init.append('        badCss.addAll(List.of(' + items + '));')
    if parsed.cleaner_rules["bad_text"]:
        items = ", ".join('"' + escape_java_string(x) + '"' for x in parsed.cleaner_rules["bad_text"])
        cleaner_init.append('        badTextPatterns.addAll(List.of(' + items + '));')

    # Search URL
    search_method = ""
    if meta.can_search:
        if parsed.search_url_template and not parsed.search_url_template.startswith("build_search_url"):
            search_method = '''
    @Override
    public String getSearchUrlTemplate() {
        return "''' + escape_java_string(parsed.search_url_template) + '''";
    }'''
        else:
            search_method = '''
    @Override
    public List<NovelSearchResult> search(String query) {
        // TODO: Implementar búsqueda para ''' + source_id + '''
        // Python original usa: ''' + (parsed.search_url_template or "método personalizado") + '''
        log.warn("Search no implementado para {}", getSourceId());
        return List.of();
    }'''

    # Login
    login_method = ""
    if meta.can_login:
        login_method = '''
    @Override
    public void login(String username, String password) {
        throw new UnsupportedOperationException("Login no implementado para " + getSourceId());
    }'''

    # Constantes de selectores específicos
    selector_constants = []
    selector_map = {
        "novel_title_selector": "NOVEL_TITLE_SELECTOR",
        "novel_cover_selector": "NOVEL_COVER_SELECTOR",
        "novel_author_selector": "NOVEL_AUTHOR_SELECTOR",
        "novel_tags_selector": "NOVEL_TAGS_SELECTOR",
        "novel_synopsis_selector": "NOVEL_SYNOPSIS_SELECTOR",
        "chapter_list_selector": "CHAPTER_LIST_SELECTOR",
        "chapter_body_selector": "CHAPTER_BODY_SELECTOR",
        "search_item_list_selector": "SEARCH_ITEM_LIST_SELECTOR",
        "search_item_title_selector": "SEARCH_ITEM_TITLE_SELECTOR",
        "search_item_info_selector": "SEARCH_ITEM_INFO_SELECTOR",
    }
    for py_key, java_const in selector_map.items():
        if py_key in parsed.selectors:
            selector_constants.append('    private static final String ' + java_const + ' = "' + escape_java_string(parsed.selectors[py_key]) + '";')

    # Java template
    template = '''package ''' + JAVA_PACKAGE + ''';

import ''' + JAVA_PACKAGE + '''.AbstractGeneratedCrawler;
import com.glez.frontendservice.crawler.ChapterContent;
import com.glez.frontendservice.crawler.ChapterInfo;
import com.glez.frontendservice.crawler.NovelDetail;
import com.glez.frontendservice.crawler.NovelSearchResult;
import com.glez.frontendservice.crawler.SourceCrawler;
import com.glez.frontendservice.crawler.VolumeInfo;
import com.glez.frontendservice.config.CrawlerProperties;
import com.glez.frontendservice.config.RateLimiterConfig;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ''' + display_name + ''' crawler - Generado automáticamente desde Python.
 * 
 * Origen: ''' + meta.file_path + '''
 * Base Python class: ''' + parsed.base_class + '''
 * Rate limit: ''' + str(meta.rate_limit) + ''' req/min
 * Search: ''' + str(meta.can_search) + '''
 * Login: ''' + str(meta.can_login) + '''
 * MTL: ''' + str(meta.has_mtl) + '''
 * 
 * Selectores detectados:
'''

    if parsed.selectors:
        for k, v in parsed.selectors.items():
            template += ' *   ' + k + ' = ' + v + '\n'
    else:
        template += ' *   (ninguno detectado)\n'

    template += ''' */
@Slf4j
@Component
@Qualifier("''' + source_id + '''")
public class ''' + class_name + ''' extends AbstractGeneratedCrawler {{

    // ─── Configuración de selectores ───
'''

    if selector_constants:
        template += "\n".join(selector_constants) + "\n"
    else:
        template += "    // No se detectaron selectores CSS específicos\n"

    template += '''

    // ─── Constructor ───
    public ''' + class_name + '''(WebClient.Builder webClientBuilder,
                         CrawlerProperties properties,
                         RateLimiterConfig.RateLimiterProvider rateLimiterProvider) {{
        super(
            webClientBuilder,
            properties,
            rateLimiterProvider,
            "''' + source_id + '''",
            List.of(''' + base_urls_java + '''),
            ''' + str(meta.rate_limit) + '''f,
            ''' + str(meta.can_login).lower() + ''',
            "''' + meta.language + '''",
            "''' + display_name + '''",
            "''' + icon + '''"
        );
    }}

    @Override
    protected void initializeCleaner() {{
        super.initializeCleaner();
'''

    if cleaner_init:
        template += "\n".join(cleaner_init) + "\n"
    else:
        template += "        // Sin reglas de limpieza específicas\n"

    template += '''    }}

    // ─── Búsqueda ───
''' + search_method + '''

    // ─── Login ───
''' + login_method + '''

    // ─── Métodos principales (delegados a clase base) ───
    // La implementación real está en AbstractGeneratedCrawler
    // que usa Jsoup + selectores configurados arriba.
    
    // Si necesitas lógica personalizada, sobrescribe:
    // - getNovelDetails(String novelUrl)
    // - getChapters(String novelUrl)
    // - getChapterContent(String chapterUrl)
    // - search(String query)

    @Override
    protected String getChapterContentSelector() {{
        return CHAPTER_BODY_SELECTOR;
    }

    @Override
    protected String getChapterListSelector() {{
        return CHAPTER_LIST_SELECTOR;
    }
}}
'''

    return template


def generate_abstract_base_class() -> str:
    """Genera la clase base abstracta que usan todos los crawlers generados."""
    return '''package ''' + JAVA_PACKAGE + ''';

import com.glez.frontendservice.crawler.ChapterContent;
import com.glez.frontendservice.crawler.ChapterInfo;
import com.glez.frontendservice.crawler.CrawlerException;
import com.glez.frontendservice.crawler.NovelDetail;
import com.glez.frontendservice.crawler.NovelSearchResult;
import com.glez.frontendservice.crawler.RateLimiter;
import com.glez.frontendservice.crawler.SourceCrawler;
import com.glez.frontendservice.crawler.VolumeInfo;
import com.glez.frontendservice.config.CrawlerProperties;
import com.glez.frontendservice.config.RateLimiterConfig;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Clase base abstracta para crawlers generados automáticamente.
 * 
 * Implementa la lógica común usando Jsoup y selectores CSS configurados
 * en las subclases. Cada crawler hijo solo necesita definir sus selectores
 * y URLs base.
 */
@Slf4j
public abstract class AbstractGeneratedCrawler implements SourceCrawler {

    protected final WebClient webClient;
    protected final RateLimiter rateLimiter;
    protected final String sourceId;
    protected final List<String> baseUrls;
    protected final float rateLimitRpm;
    protected final boolean requiresLogin;
    protected final String language;
    protected final String displayName;
    protected final String icon;

    // Reglas de limpieza personalizables
    protected final Set<String> badCss = ConcurrentHashMap.newKeySet();
    protected final List<String> badTextPatterns = new ArrayList<>();

    protected AbstractGeneratedCrawler(WebClient.Builder webClientBuilder,
                                       CrawlerProperties properties,
                                       RateLimiterConfig.RateLimiterProvider rateLimiterProvider,
                                       String sourceId,
                                       List<String> baseUrls,
                                       float rateLimitRpm,
                                       boolean requiresLogin,
                                       String language,
                                       String displayName,
                                       String icon) {{
        this.sourceId = sourceId;
        this.baseUrls = baseUrls != null && !baseUrls.isEmpty() ? baseUrls : List.of("https://example.com/");
        this.rateLimitRpm = rateLimitRpm;
        this.requiresLogin = requiresLogin;
        this.language = language;
        this.displayName = displayName;
        this.icon = icon;

        // WebClient con base URL primaria
        this.webClient = webClientBuilder
                .baseUrl(this.baseUrls.get(0))
                .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build();

        // Rate limiter
        this.rateLimiter = rateLimiterProvider.getLimiter(sourceId);

        // Inicializar limpiador
        initializeCleaner();
    }}

    protected void initializeCleaner() {{
        // Sobrescribir en subclase para añadir reglas específicas
    }}

    // ─── SourceCrawler interface ───

    @Override
    public String getSourceId() {{
        return sourceId;
    }}

    @Override
    public String getDisplayName() {{
        return displayName;
    }}

    @Override
    public String getIcon() {{
        return icon;
    }}

    @Override
    public String getLanguage() {{
        return language;
    }}

    @Override
    public String getBaseUrl() {{
        return baseUrls.isEmpty() ? "" : baseUrls.get(0);
    }}

    @Override
    public int getRateLimitRpm() {{
        return Math.round(rateLimitRpm);
    }}

    @Override
    public boolean requiresLogin() {{
        return requiresLogin;
    }}

    @Override
    public List<NovelSearchResult> search(String query) {{
        log.debug("Searching {{}} for: {{}}", getSourceId(), query);
        
        String searchUrl = buildSearchUrl(query);
        if (searchUrl == null) {{
            log.warn("No search URL template for {{}}", getSourceId());
            return List.of();
        }}

        try {{
            acquirePermit();
            String html = webClient.get()
                    .uri(searchUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (html == null || html.isEmpty()) {{
                return List.of();
            }}

            Document doc = Jsoup.parse(html);
            return parseSearchResults(doc, query);

        }} catch (Exception e) {{
            log.error("Search failed for query '{{}}': {{}}", query, e.getMessage());
            throw new CrawlerException(getSourceId(), "search", "Search failed: " + e.getMessage(), e);
        }
    }

    protected String buildSearchUrl(String query) {{
        String template = getSearchUrlTemplate();
        if (template == null) return null;
        String encodedQuery = query.toLowerCase().replace(" ", "+");
        return String.format(template, encodedQuery);
    }}

    protected String getSearchUrlTemplate() {{
        return null; // Sobrescribir en subclase si hay template
    }}

    protected List<NovelSearchResult> parseSearchResults(Document doc, String query) {{
        String listSelector = getSearchItemListSelector();
        if (listSelector == null || listSelector.isEmpty()) {{
            log.warn("No search item list selector for {{}}", getSourceId());
            return List.of();
        }}

        Elements results = doc.select(listSelector);
        List<NovelSearchResult> searchResults = new ArrayList<>();

        for (Element item : results) {{
            String title = extractTitle(item);
            String url = extractUrl(item);
            String info = extractInfo(item);
            String cover = extractCover(item);

            if (url != null && !url.isEmpty()) {{
                searchResults.add(new NovelSearchResult(
                        title != null ? title.strip() : "Unknown",
                        toAbsoluteUrl(url),
                        info != null ? info : "",
                        cover != null ? cover : "",
                        url // sourceNovelId
                ));
            }
        }

        log.debug("Found {{}} results for query '{{}}'", searchResults.size(), query);
        return searchResults;
    }}

    protected String getSearchItemListSelector() {{ return null; }}
    protected String getSearchItemTitleSelector() {{ return null; }}
    protected String getSearchItemInfoSelector() {{ return null; }}

    protected String extractTitle(Element item) {{
        String titleSel = getSearchItemTitleSelector();
        if (titleSel != null) {{
            Element el = item.selectFirst(titleSel);
            if (el != null) return el.attr("title").isEmpty() ? el.text() : el.attr("title");
        }}
        return item.attr("title").isEmpty() ? item.text() : item.attr("title");
    }}

    protected String extractUrl(Element item) {{
        Element link = item.selectFirst("a[href]");
        return link != null ? link.attr("href") : "";
    }}

    protected String extractInfo(Element item) {{
        String infoSel = getSearchItemInfoSelector();
        if (infoSel != null) {{
            Element el = item.selectFirst(infoSel);
            if (el != null) return el.text();
        }}
        return "";
    }}

    protected String extractCover(Element item) {{
        Element img = item.selectFirst("img[src]");
        return img != null ? toAbsoluteUrl(img.attr("src")) : "";
    }}

    @Override
    public NovelDetail getNovelDetails(String novelUrl) {{
        log.debug("Getting novel details from {{}}: {{}}", getSourceId(), novelUrl);

        try {{
            acquirePermit();
            String html = webClient.get()
                    .uri(novelUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            Document doc = Jsoup.parse(html);
            return parseNovelDetails(doc, novelUrl);

        }} catch (Exception e) {{
            log.error("Get novel details failed for {{}}: {{}}", novelUrl, e.getMessage());
            throw new CrawlerException(getSourceId(), "getNovelDetails", "Failed: " + e.getMessage(), e);
        }
    }

    protected NovelDetail parseNovelDetails(Document doc, String novelUrl) {{
        String title = extractNovelTitle(doc);
        String author = extractNovelAuthor(doc);
        String cover = extractNovelCover(doc);
        String synopsis = extractNovelSynopsis(doc);
        List<String> tags = extractNovelTags(doc);
        List<VolumeInfo> volumes = extractVolumesAndChapters(doc, novelUrl);

        return new NovelDetail(
                title,
                author,
                cover,
                synopsis,
                tags,
                volumes,
                language,
                false, // isMtl
                false, // isManga
                novelUrl,
                "Ongoing",
                ""
        );
    }}

    protected String extractNovelTitle(Document doc) {{
        String sel = getNovelTitleSelector();
        if (sel != null) {{
            Element el = doc.selectFirst(sel);
            if (el != null) return el.text().strip();
        }}
        // Fallback: primer h1
        Element h1 = doc.selectFirst("h1");
        return h1 != null ? h1.text().strip() : "Unknown Title";
    }}

    protected String getNovelTitleSelector() {{ return null; }}

    protected String extractNovelAuthor(Document doc) {{
        String sel = getNovelAuthorSelector();
        if (sel != null) {{
            Element el = doc.selectFirst(sel);
            if (el != null) return el.text().strip();
        }}
        return "";
    }}

    protected String getNovelAuthorSelector() {{ return null; }}

    protected String extractNovelCover(Document doc) {{
        String sel = getNovelCoverSelector();
        if (sel != null) {{
            Element el = doc.selectFirst(sel);
            if (el != null && el.hasAttr("src")) return toAbsoluteUrl(el.attr("src"));
            if (el != null && el.hasAttr("content")) return toAbsoluteUrl(el.attr("content"));
        }}
        // Fallback: og:image
        Element og = doc.selectFirst("meta[property=og:image]");
        if (og != null && og.hasAttr("content")) return toAbsoluteUrl(og.attr("content"));
        return "";
    }}

    protected String getNovelCoverSelector() {{ return null; }}

    protected String extractNovelSynopsis(Document doc) {{
        String sel = getNovelSynopsisSelector();
        if (sel != null) {{
            Element el = doc.selectFirst(sel);
            if (el != null) return el.text();
        }}
        // Fallback: og:description
        Element og = doc.selectFirst("meta[property=og:description]");
        if (og != null && og.hasAttr("content")) return og.attr("content");
        return "";
    }}

    protected String getNovelSynopsisSelector() {{ return null; }}

    protected List<String> extractNovelTags(Document doc) {{
        String sel = getNovelTagsSelector();
        if (sel != null) {{
            Elements els = doc.select(sel);
            return els.stream().map(Element::text).map(String::strip).collect(Collectors.toList());
        }}
        return List.of();
    }}

    protected String getNovelTagsSelector() {{ return null; }}

    @Override
    public List<ChapterInfo> getChapters(String novelUrl) {{
        log.debug("Getting chapters for novel: {{}}", novelUrl);

        try {{
            acquirePermit();
            String html = webClient.get()
                    .uri(novelUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            Document doc = Jsoup.parse(html);
            return extractChapters(doc, novelUrl);

        }} catch (Exception e) {{
            log.error("Get chapters failed for {{}}: {{}}", novelUrl, e.getMessage());
            throw new CrawlerException(getSourceId(), "getChapters", "Failed: " + e.getMessage(), e);
        }
    }

    protected List<ChapterInfo> extractChapters(Document doc, String novelUrl) {{
        String listSel = getChapterListSelector();
        if (listSel == null || listSel.isEmpty()) {{
            log.warn("No chapter list selector for {{}}", getSourceId());
            return List.of();
        }}

        Elements links = doc.select(listSel);
        List<ChapterInfo> chapters = new ArrayList<>();

        int chapterCount = 0;
        for (Element a : links) {{
            String href = a.attr("href");
            if (href == null || href.isEmpty()) continue;

            chapterCount++;
            String title = a.text().strip();
            if (title.isEmpty()) title = "Chapter " + chapterCount;

            chapters.add(new ChapterInfo(
                    chapterCount,
                    title,
                    toAbsoluteUrl(href),
                    1 // volumeId por defecto
            ));
        }}

        return chapters;
    }}

    protected String getChapterListSelector() {{ return null; }}

    @Override
    public ChapterContent getChapterContent(String chapterUrl) {{
        log.debug("Getting chapter content: {{}}", chapterUrl);

        try {{
            acquirePermit();
            String html = webClient.get()
                    .uri(chapterUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            Document doc = Jsoup.parse(html);
            return parseChapterContent(doc, chapterUrl);

        }} catch (Exception e) {{
            log.error("Get chapter content failed for {{}}: {{}}", chapterUrl, e.getMessage());
            return ChapterContent.failure("Failed: " + e.getMessage());
        }
    }

    protected ChapterContent parseChapterContent(Document doc, String chapterUrl) {{
        String contentSel = getChapterContentSelector();
        Element contentEl = contentSel != null ? doc.selectFirst(contentSel) : null;

        if (contentEl == null) {{
            // Fallback: buscar contenido denso
            contentEl = findDenseContent(doc);
        }}

        if (contentEl == null) {{
            return ChapterContent.failure("Chapter content element not found");
        }}

        // Limpiar
        cleanChapterContent(contentEl);

        // Extraer imágenes
        List<String> images = new ArrayList<>();
        Elements imgElements = contentEl.select("img[src]");
        for (Element img : imgElements) {{
            String src = img.attr("src");
            if (!src.isEmpty()) {{
                images.add(toAbsoluteUrl(src));
            }
        }

        return ChapterContent.success(contentEl.html(), images);
    }}

    protected String getChapterContentSelector() {{ return null; }}

    protected Element findDenseContent(Document doc) {{
        // Heurística: elemento con más párrafos
        Element best = null;
        int maxParagraphs = 0;

        for (Element el : doc.select("div, article, section, main, td")) {{
            int paragraphs = el.select("p").size();
            if (paragraphs > maxParagraphs && paragraphs >= 3) {{
                maxParagraphs = paragraphs;
                best = el;
            }
        }
        return best;
    }}

    protected void cleanChapterContent(Element contentEl) {{
        // Eliminar selectores malos
        for (String badCss : badCss) {{
            contentEl.select(badCss).remove();
        }}

        // Eliminar scripts, styles, etc.
        contentEl.select("script, style, noscript, iframe").remove();

        // Patrones de texto malos
        for (String pattern : badTextPatterns) {{
            contentEl.select(":matchesOwn(" + Pattern.quote(pattern) + ")").remove();
        }
    }

    @Override
    public void login(String username, String password) {{
        throw new UnsupportedOperationException("Login no implementado para " + getSourceId());
    }}

    @Override
    public boolean isHealthy() {{
        try {{
            acquirePermit();
            String html = webClient.get()
                    .uri("/")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return html != null && !html.isEmpty();
        }} catch (Exception e) {{
            log.warn("Health check failed for {{}}: {{}}", getSourceId(), e.getMessage());
            return false;
        }
    }

    // ─── Helpers ───

    protected void acquirePermit() {{
        if (!rateLimiter.acquire(Duration.ofMinutes(5))) {{
            log.warn("Rate limiter timeout for {{}}", getSourceId());
        }
    }

    protected String toAbsoluteUrl(String url) {{
        if (url == null || url.isEmpty()) return "";
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        if (url.startsWith("//")) return "https:" + url;
        if (url.startsWith("/")) return baseUrls.get(0).replaceAll("/+$", "") + url;
        return baseUrls.get(0).replaceAll("/+$", "") + "/" + url;
    }}

    protected List<VolumeInfo> extractVolumesAndChapters(Document doc, String novelUrl) {{
        List<ChapterInfo> allChapters = extractChapters(doc, novelUrl);
        
        // Agrupar por volumen simple (cada 50 caps = volumen)
        List<VolumeInfo> volumes = new ArrayList<>();
        for (int i = 0; i < allChapters.size(); i += 50) {{
            int volNum = (i / 50) + 1;
            int end = Math.min(i + 50, allChapters.size());
            List<ChapterInfo> volChapters = allChapters.subList(i, end);
            volumes.add(new VolumeInfo(volNum, "Volume " + volNum, volChapters));
        }}

        if (volumes.isEmpty() && !allChapters.isEmpty()) {{
            volumes.add(new VolumeInfo(1, "Volume 1", allChapters));
        }}

        return volumes;
    }
}}
'''


# ─── Main ───────────────────────────────────────────────────────────────


def main():
    print("Cargando índice de crawlers...")
    with INDEX_JSON.open() as f:
        index = json.load(f)

    crawlers = index.get("crawlers", {})

    # Filtrar solo inglés
    en_crawlers = []
    for cid, info in crawlers.items():
        if info["language"] == "en":
            file_path = info["file_path"]
            if file_path.startswith("sources/"):
                file_path = file_path[8:]
            py_path = PYTHON_SOURCES_ROOT / file_path
            if py_path.exists():
                en_crawlers.append((cid, info, py_path))

    print(f"Encontrados {len(en_crawlers)} crawlers en inglés")

    # Crear directorio de salida
    JAVA_OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    # Generar clase base
    print("Generando clase base AbstractGeneratedCrawler...")
    base_class_code = generate_abstract_base_class()
    (JAVA_OUTPUT_DIR / "AbstractGeneratedCrawler.java").write_text(base_class_code, encoding="utf-8")

    # Generar cada crawler
    print("Generando crawlers individuales...")
    generated = 0
    errors = 0

    for cid, info, py_path in en_crawlers:
        try:
            parsed = parse_python_crawler(py_path)
            java_code = generate_java_crawler(parsed)
            class_name = parsed.class_name
            output_file = JAVA_OUTPUT_DIR / (class_name + ".java")
            output_file.write_text(java_code, encoding="utf-8")
            generated += 1
            if generated % 50 == 0:
                print(f"   Generados {generated}/{len(en_crawlers)}...")
        except Exception as e:
            print(f"   Error en {cid}: {e}")
            errors += 1

    print(f"\nCompletado: {generated} generados, {errors} errores")
    print(f"Archivos en: {JAVA_OUTPUT_DIR}")


if __name__ == "__main__":
    main()