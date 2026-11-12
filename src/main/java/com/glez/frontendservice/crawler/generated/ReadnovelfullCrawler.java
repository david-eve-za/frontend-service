package com.glez.frontendservice.crawler.generated;

import com.glez.frontendservice.crawler.generated.AbstractGeneratedCrawler;
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
 * Readnovelfull crawler - Generado automáticamente desde Python.
 * 
 * Origen: sources/en/r/readnovelfull.py
 * Base Python class: NovelFullTemplate
 * Rate limit: 3.0 req/min
 * Search: False
 * Login: False
 * MTL: False
 * 
 * Selectores detectados:
 *   (ninguno detectado)
 */
@Slf4j
@Component
@Qualifier("readnovelfull")
public class ReadnovelfullCrawler extends AbstractGeneratedCrawler {

// ─── Configuración de selectores ───
    private static final String NOVEL_TITLE_SELECTOR = "h3.title";
    private static final String NOVEL_COVER_SELECTOR = ".book img";
    private static final String NOVEL_AUTHOR_SELECTOR = ".info a[href*='/a/'], .info a[href*='/au/'], .info a[href*='author']";
    private static final String NOVEL_TAGS_SELECTOR = ".info a[href*='/genres/']";
    private static final String NOVEL_SYNOPSIS_SELECTOR = ".desc-text";
    private static final String CHAPTER_LIST_SELECTOR = "ul.list-chapter > li > a[href], select > option[value]";
    private static final String CHAPTER_BODY_SELECTOR = "#chr-content, #chapter-content";
    private static final String SEARCH_ITEM_LIST_SELECTOR = "#list-page .row h3[class*='title'] > a";
    private static final String SEARCH_ITEM_TITLE_SELECTOR = "h3.title";
    private static final String SEARCH_ITEM_INFO_SELECTOR = "span.chapter";


    // ─── Constructor ───
    public ReadnovelfullCrawler(WebClient.Builder webClientBuilder,
                         CrawlerProperties properties,
                         RateLimiterConfig.RateLimiterProvider rateLimiterProvider) {
        super(
            webClientBuilder,
            properties,
            rateLimiterProvider,
            "readnovelfull",
            List.of("https://readnovelfull.com/"),
            3.0f,
            false,
            "en",
            "Readnovelfull",
            "pi pi-book"
        );
    }

    @Override
    protected void initializeCleaner() {
        super.initializeCleaner();
        // Sin reglas de limpieza específicas
    }

    // ─── Búsqueda ───

    @Override
    public List<NovelSearchResult> search(String query) {
        log.info("Iniciando búsqueda en readnovelfull para: '{}'", query);
        List<NovelSearchResult> results = super.search(query);
        log.info("Búsqueda en readnovelfull completada: {} resultados encontrados", results.size());
        return results;
    }

    // ─── Login ───


    // ─── Búsqueda ───

    @Override
    public String getSearchUrlTemplate() {
        return "https://readnovelfull.com/novel-list/search?keyword={}";
    }

    @Override
    protected String getSearchItemListSelector() {
        return SEARCH_ITEM_LIST_SELECTOR;
    }

    @Override
    protected String getSearchItemTitleSelector() {
        return SEARCH_ITEM_TITLE_SELECTOR;
    }

    @Override
    protected String getSearchItemInfoSelector() {
        return SEARCH_ITEM_INFO_SELECTOR;
    }

    @Override
    protected String getNovelTitleSelector() {
        return NOVEL_TITLE_SELECTOR;
    }

    @Override
    protected String getNovelCoverSelector() {
        return NOVEL_COVER_SELECTOR;
    }

    @Override
    protected String getNovelAuthorSelector() {
        return NOVEL_AUTHOR_SELECTOR;
    }

    @Override
    protected String getNovelTagsSelector() {
        return NOVEL_TAGS_SELECTOR;
    }

    @Override
    protected String getNovelSynopsisSelector() {
        return NOVEL_SYNOPSIS_SELECTOR;
    }

    @Override
    protected String getChapterListSelector() {
        return CHAPTER_LIST_SELECTOR;
    }

    @Override
    protected String getChapterContentSelector() {
        return CHAPTER_BODY_SELECTOR;
    }


    // ─── Métodos principales (delegados a clase base) ───
    // La implementación real está en AbstractGeneratedCrawler
    // que usa Jsoup + selectores configurados arriba.
    
    // Si necesitas lógica personalizada, sobrescribe:
    // - getNovelDetails(String novelUrl)
    // - getChapters(String novelUrl)
    // - getChapterContent(String chapterUrl)
    // - search(String query)

}
