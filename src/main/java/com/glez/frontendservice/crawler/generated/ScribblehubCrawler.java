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
 * Scribblehub crawler - Generado automáticamente desde Python.
 * 
 * Origen: sources/en/s/scribblehub.py
 * Base Python class: SoupTemplate
 * Rate limit: 3.0 req/min
 * Search: False
 * Login: False
 * MTL: False
 * 
 * Selectores detectados:
 *   novel_url_selector = .fictionposts-template-default
 *   novel_title_selector = .fic_title
 *   novel_cover_selector = .fic_image img
 *   novel_author_selector = .auth_name_fic
 *   novel_tags_selector = a.fic_genre
 *   novel_synopsis_selector = .wi_fic_desc
 *   chapter_list_selector = .toc_ol a.toc_a
 *   chapter_body_selector = #chp_raw
 */
@Slf4j
@Component
@Qualifier("scribblehub")
public class ScribblehubCrawler extends AbstractGeneratedCrawler {

    // ─── Configuración de selectores ───
    private static final String NOVEL_TITLE_SELECTOR = ".fic_title";
    private static final String NOVEL_COVER_SELECTOR = ".fic_image img";
    private static final String NOVEL_AUTHOR_SELECTOR = ".auth_name_fic";
    private static final String NOVEL_TAGS_SELECTOR = "a.fic_genre";
    private static final String NOVEL_SYNOPSIS_SELECTOR = ".wi_fic_desc";
    private static final String CHAPTER_LIST_SELECTOR = ".toc_ol a.toc_a";
    private static final String CHAPTER_BODY_SELECTOR = "#chp_raw";


    // ─── Constructor ───
    public ScribblehubCrawler(WebClient.Builder webClientBuilder,
                         CrawlerProperties properties,
                         RateLimiterConfig.RateLimiterProvider rateLimiterProvider) {
        super(
            webClientBuilder,
            properties,
            rateLimiterProvider,
            "scribblehub",
            List.of("https://www.scribblehub.com/"),
            3.0f,
            false,
            "en",
            "Scribblehub",
            "pi pi-book"
        );
    }

    @Override
    protected void initializeCleaner() {
        super.initializeCleaner();
        badCss.addAll(List.of(".p-avatar-wrap", ".sp-head", ".spdiv", ".chp_stats_feature", ".modern-footnotes-footnote", ".modern-footnotes-footnote__note", ".wi_authornotes"));
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
