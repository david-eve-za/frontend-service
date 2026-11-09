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
 * Novelight crawler - Generado automáticamente desde Python.
 * 
 * Origen: sources/en/n/novelight.py
 * Base Python class: SoupTemplate
 * Rate limit: 3.0 req/min
 * Search: False
 * Login: False
 * MTL: False
 * 
 * Selectores detectados:
 *   search_item_list_selector = .manga-grid-list a.item
 *   novel_title_selector = header.header-manga h1
 *   novel_cover_selector = div.second-information div.poster img
 *   novel_synopsis_selector = div#information section.text-info
 *   novel_tags_selector = div#information section.tags a[href^=
 *   novel_author_selector = .mini-info a[href^=
 *   chapter_list_selector = select#select-pagination-chapter > option
 *   chapter_title_selector = .title
 *   chapter_body_selector = .chapter-text
 */
@Slf4j
@Component
@Qualifier("novelight")
public class NovelightCrawler extends AbstractGeneratedCrawler {

    // ─── Configuración de selectores ───
    private static final String NOVEL_TITLE_SELECTOR = "header.header-manga h1";
    private static final String NOVEL_COVER_SELECTOR = "div.second-information div.poster img";
    private static final String NOVEL_AUTHOR_SELECTOR = ".mini-info a[href^=";
    private static final String NOVEL_TAGS_SELECTOR = "div#information section.tags a[href^=";
    private static final String NOVEL_SYNOPSIS_SELECTOR = "div#information section.text-info";
    private static final String CHAPTER_LIST_SELECTOR = "select#select-pagination-chapter > option";
    private static final String CHAPTER_BODY_SELECTOR = ".chapter-text";
    private static final String SEARCH_ITEM_LIST_SELECTOR = ".manga-grid-list a.item";


    // ─── Constructor ───
    public NovelightCrawler(WebClient.Builder webClientBuilder,
                         CrawlerProperties properties,
                         RateLimiterConfig.RateLimiterProvider rateLimiterProvider) {
        super(
            webClientBuilder,
            properties,
            rateLimiterProvider,
            "novelight",
            List.of("https://novelight.net/"),
            3.0f,
            false,
            "en",
            "Novelight",
            "pi pi-book"
        );
    }

    @Override
    protected void initializeCleaner() {
        super.initializeCleaner();
        badCss.addAll(List.of("div.advertisment"));
    }

    // ─── Búsqueda ───


    // ─── Login ───


    // ─── Métodos principales (delegados a clase base) ───
    // La implementación real está en AbstractGeneratedCrawler
    // que usa Jsoup + selectores configurados arriba.
    
    // Si necesitas lógica personalizada, sobrescribe:
    // - getNovelDetails(String novelUrl)
    // - getChapters(String novelUrl)
    // - getChapterContent(String chapterUrl)
    // - search(String query)

    @Override
    protected String getChapterContentSelector() {
        return CHAPTER_BODY_SELECTOR;
    }
    @Override
    protected String getChapterListSelector() {
        return CHAPTER_LIST_SELECTOR;
    }
}
