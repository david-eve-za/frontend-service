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
 * Mtlreader crawler - Generado automáticamente desde Python.
 * 
 * Origen: sources/en/m/mtlreader.py
 * Base Python class: LegacyCrawler
 * Rate limit: 3.0 req/min
 * Search: False
 * Login: False
 * MTL: True
 * 
 * Selectores detectados:
 *   (ninguno detectado)
 */
@Slf4j
@Component
@Qualifier("mtlreader")
public class MtlreaderCrawler extends AbstractGeneratedCrawler {

    // ─── Configuración de selectores ───
    // No se detectaron selectores CSS específicos


    // ─── Constructor ───
    public MtlreaderCrawler(WebClient.Builder webClientBuilder,
                         CrawlerProperties properties,
                         RateLimiterConfig.RateLimiterProvider rateLimiterProvider) {
        super(
            webClientBuilder,
            properties,
            rateLimiterProvider,
            "mtlreader",
            List.of("https://mtlreader.com/", "https://www.mtlreader.com/"),
            3.0f,
            false,
            "en",
            "Mtlreader",
            "pi pi-globe"
        );
    }

    @Override
    protected void initializeCleaner() {
        super.initializeCleaner();
        // Sin reglas de limpieza específicas
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

    /**
     * Health check con timeout más corto para evitar timeouts en mtlreader.com
     * que suele ser lento en responder al endpoint raíz.
     */
    @Override
    public boolean isHealthy() {
        try {
            acquirePermit();
            // Usar HEAD request con timeout corto (10s) para health check
            String html = webClient.head()
                    .uri("/")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();
            return html != null; // HEAD no devuelve body, pero 2xx status significa healthy
        } catch (Exception e) {
            log.warn("Health check failed for {}: {}", getSourceId(), e.getMessage());
            return false;
        }
    }
}
