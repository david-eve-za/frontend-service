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
 * Bato crawler - Generado automáticamente desde Python.
 * 
 * Origen: sources/en/b/bato.py
 * Base Python class: SoupTemplate
 * Rate limit: 3.0 req/min
 * Search: True
 * Login: False
 * MTL: False
 * 
 * Selectores detectados:
 *   novel_title_selector = h3.item-title
 *   novel_cover_selector = .attr-cover img
 *   chapter_list_selector = .main a.chapt
 */
@Slf4j
@Component
@Qualifier("bato")
public class BatoCrawler extends AbstractGeneratedCrawler {

    // ─── Configuración de selectores ───
    private static final String NOVEL_TITLE_SELECTOR = "h3.item-title";
    private static final String NOVEL_COVER_SELECTOR = ".attr-cover img";
    private static final String CHAPTER_LIST_SELECTOR = ".main a.chapt";


    // ─── Constructor ───
    public BatoCrawler(WebClient.Builder webClientBuilder,
                         CrawlerProperties properties,
                         RateLimiterConfig.RateLimiterProvider rateLimiterProvider) {
        super(
            webClientBuilder,
            properties,
            rateLimiterProvider,
            "bato",
            List.of("https://bato.to/", "https://battwo.com/", "https://mto.to/"),
            3.0f,
            false,
            "en",
            "Bato",
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
    public String getSearchUrlTemplate() {
        return "https://wto.to/search?word=%s";
    }

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
    protected String getChapterListSelector() {
        return CHAPTER_LIST_SELECTOR;
    }
}
