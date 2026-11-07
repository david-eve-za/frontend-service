package com.glez.frontendservice.crawler;

import com.glez.frontendservice.config.CrawlerProperties;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * NovelasLigera.com crawler implementation.
 * Port of Python lncrawl NovelasLigeraCrawler (SoupTemplate).
 * 
 * Source: https://novelasligera.com/
 * Pattern: Elementor-based WordPress site with custom chapter listing.
 * Uses Jsoup for HTML parsing, WebClient for HTTP.
 */
@Slf4j
@Component
@Qualifier("novelasligera_com")
public class NovelasLigeraComCrawler implements SourceCrawler {

    private static final String SEARCH_URL_TEMPLATE = 
        "https://novelasligera.com/?s=%s&post_type=product";
    
    // Selectors from Python implementation
    private static final String TITLE_SELECTOR = "h1.entry-title";
    private static final String COVER_SELECTOR = ".elementor-widget-container img";
    private static final String CHAPTER_LIST_SELECTOR = 
        "div.elementor-tab-content ul.lcp_catlist li a[href], " +
        "div.elementor-accordion ul.lcp_catlist li a[href], " +
        "ul.lcp_catlist li a[href]";
    private static final String CHAPTER_BODY_SELECTOR = "div.entry-content";
    private static final String AUTHOR_SELECTOR = ".entry-content p, .novel-author p, .author-info p";

    private final WebClient webClient;
    private final CrawlerProperties.SourceConfig config;
    private final int rateLimitRpm;

    public NovelasLigeraComCrawler(WebClient.Builder webClientBuilder, CrawlerProperties properties) {
        this.config = properties.getSources().get("novelasligera_com");
        this.rateLimitRpm = config != null ? config.getRateLimitRpm() : 30;
        
        this.webClient = webClientBuilder
                .baseUrl("https://novelasligera.com")
                .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build();
    }

    @Override
    public String getSourceId() {
        return "novelasligera_com";
    }

    @Override
    public String getDisplayName() {
        return "Novelas Ligera";
    }

    @Override
    public String getIcon() {
        return "pi pi-book";
    }

    @Override
    public String getLanguage() {
        return "es";
    }

    @Override
    public String getBaseUrl() {
        return "https://novelasligera.com/";
    }

    @Override
    public int getRateLimitRpm() {
        return rateLimitRpm;
    }

    @Override
    public boolean requiresLogin() {
        return false;
    }

    @Override
    public List<NovelSearchResult> search(String query) {
        log.debug("Searching NovelasLigera.com for: {}", query);
        
        try {
            String encodedQuery = java.net.URLEncoder.encode(query, "UTF-8");
            String searchUrl = String.format(SEARCH_URL_TEMPLATE, encodedQuery);
            
            String html = webClient.get()
                    .uri(searchUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            
            Document doc = Jsoup.parse(html);
            
            // Search results on NovelasLigera - need to find the correct selector
            // Based on WooCommerce product search results
            Elements resultElements = doc.select(".product, .wf-cell, .search-result, .type-product");
            
            List<NovelSearchResult> results = new ArrayList<>();
            for (Element el : resultElements) {
                Element titleEl = el.selectFirst("h2, h3, .woocommerce-loop-product__title, .product-title");
                Element linkEl = el.selectFirst("a[href]");
                Element imgEl = el.selectFirst("img[src]");
                
                if (titleEl == null || linkEl == null) continue;
                
                String title = titleEl.text().strip();
                String url = linkEl.absUrl("href");
                String coverUrl = imgEl != null ? imgEl.absUrl("src") : "";
                
                if (!title.isEmpty() && !url.isEmpty()) {
                    results.add(new NovelSearchResult(
                            title,
                            url,
                            "",
                            coverUrl,
                            ""
                    ));
                }
            }
            
            // Fallback: try generic search result selectors
            if (results.isEmpty()) {
                Elements genericResults = doc.select(".search-results .post, .search-result, article");
                for (Element el : genericResults) {
                    Element titleEl = el.selectFirst("h1 a, h2 a, h3 a, .entry-title a");
                    Element linkEl = el.selectFirst("a[href]");
                    
                    if (titleEl == null || linkEl == null) continue;
                    
                    String title = titleEl.text().strip();
                    String url = linkEl.absUrl("href");
                    
                    if (!title.isEmpty() && !url.isEmpty()) {
                        results.add(new NovelSearchResult(
                                title,
                                url,
                                "",
                                "",
                                ""
                        ));
                    }
                }
            }
            
            log.debug("Found {} results for query '{}'", results.size(), query);
            return results;
            
        } catch (Exception e) {
            log.error("Search failed for query '{}': {}", query, e.getMessage());
            throw new CrawlerException(getSourceId(), "search", "Search failed: " + e.getMessage(), e);
        }
    }

    @Override
    public NovelDetail getNovelDetails(String novelUrl) {
        log.debug("Getting novel details from NovelasLigera.com: {}", novelUrl);
        
        try {
            String html = webClient.get()
                    .uri(novelUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            
            Document doc = Jsoup.parse(html);
            
            // Title
            Element titleEl = doc.selectFirst(TITLE_SELECTOR);
            if (titleEl == null) {
                throw new CrawlerException(getSourceId(), "getNovelDetails", "Title not found: " + novelUrl);
            }
            String title = titleEl.text().strip();
            
            // Cover
            String coverUrl = "";
            Element coverEl = doc.selectFirst(COVER_SELECTOR);
            if (coverEl != null && coverEl.hasAttr("src")) {
                coverUrl = toAbsoluteUrl(coverEl.attr("src"));
            } else if (coverEl != null && coverEl.hasAttr("data-src")) {
                coverUrl = toAbsoluteUrl(coverEl.attr("data-src"));
            } else if (coverEl != null && coverEl.hasAttr("data-lazy-src")) {
                coverUrl = toAbsoluteUrl(coverEl.attr("data-lazy-src"));
            }
            
            // Synopsis - try multiple selectors
            String synopsis = "";
            Element synopsisEl = doc.selectFirst(".entry-content > p:first-of-type, .novel-summary, .description, .synopsis");
            if (synopsisEl != null) {
                synopsis = synopsisEl.text();
            }
            
            // Author and Genres from paragraphs containing "Autor:" and "Género:"
            String author = "";
            List<String> tags = new ArrayList<>();
            
            Elements paragraphs = doc.select(AUTHOR_SELECTOR);
            for (Element p : paragraphs) {
                String text = p.text().strip();
                if (text.startsWith("Autor:") || text.contains("Autor:")) {
                    int idx = text.indexOf("Autor:");
                    if (idx >= 0) {
                        author = text.substring(idx + 6).strip();
                    }
                } else if (text.startsWith("Género:") || text.contains("Género:")) {
                    int idx = text.indexOf("Género:");
                    if (idx >= 0) {
                        String genresText = text.substring(idx + 7).strip().replaceAll("\\.$", "");
                        for (String genre : genresText.split(",")) {
                            String g = genre.strip();
                            if (!g.isEmpty()) tags.add(g);
                        }
                    }
                }
            }
            
            // Chapters
            List<ChapterInfo> chapters = extractChapters(doc, novelUrl);
            List<VolumeInfo> volumes = groupChaptersIntoVolumes(chapters);
            
            return new NovelDetail(
                    title,
                    author,
                    coverUrl,
                    synopsis,
                    tags,
                    volumes,
                    "es",
                    false,
                    false,
                    novelUrl,
                    "Ongoing",
                    ""
            );
            
        } catch (CrawlerException e) {
            throw e;
        } catch (Exception e) {
            log.error("Get novel details failed for {}: {}", novelUrl, e.getMessage());
            throw new CrawlerException(getSourceId(), "getNovelDetails", "Failed to get novel details: " + e.getMessage(), e);
        }
    }

    @Override
    public List<ChapterInfo> getChapters(String novelUrl) {
        log.debug("Getting chapters for novel: {}", novelUrl);
        
        try {
            String html = webClient.get()
                    .uri(novelUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            
            Document doc = Jsoup.parse(html);
            return extractChapters(doc, novelUrl);
            
        } catch (Exception e) {
            log.error("Get chapters failed for {}: {}", novelUrl, e.getMessage());
            throw new CrawlerException(getSourceId(), "getChapters", "Failed to get chapters: " + e.getMessage(), e);
        }
    }

    @Override
    public ChapterContent getChapterContent(String chapterUrl) {
        log.debug("Getting chapter content: {}", chapterUrl);
        
        try {
            String html = webClient.get()
                    .uri(chapterUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            
            Document doc = Jsoup.parse(html);
            
            // Chapter body selector
            Element contentEl = doc.selectFirst(CHAPTER_BODY_SELECTOR);
            if (contentEl == null) {
                return ChapterContent.failure("Chapter content element not found");
            }
            
            // Clean bad tags and CSS from Python implementation
            cleanChapterContent(contentEl);
            
            // Extract images
            List<String> images = new ArrayList<>();
            Elements imgElements = contentEl.select("img[src]");
            for (Element img : imgElements) {
                String src = img.absUrl("src");
                if (!src.isEmpty()) {
                    images.add(src);
                }
            }
            
            String cleanHtml = contentEl.html();
            
            return ChapterContent.success(cleanHtml, images);
            
        } catch (Exception e) {
            log.error("Get chapter content failed for {}: {}", chapterUrl, e.getMessage());
            return ChapterContent.failure("Failed to get chapter content: " + e.getMessage());
        }
    }

    @Override
    public void login(String username, String password) {
        throw new UnsupportedOperationException("Login not required for catalog access");
    }

    @Override
    public boolean isHealthy() {
        try {
            String html = webClient.get()
                    .uri("/")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return html != null && html.contains("novelasligera");
        } catch (Exception e) {
            log.warn("NovelasLigera.com health check failed: {}", e.getMessage());
            return false;
        }
    }

    // ==================== Private Helper Methods ====================

    private List<ChapterInfo> extractChapters(Document doc, String novelUrl) {
        List<ChapterInfo> chapters = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        
        Elements links = doc.select(CHAPTER_LIST_SELECTOR);
        int chapterId = 0;
        
        for (Element a : links) {
            String title = a.text().strip();
            String href = a.attr("abs:href");
            
            if (href.isEmpty() || title.isEmpty() || seenUrls.contains(href)) {
                continue;
            }
            
            seenUrls.add(href);
            chapterId++;
            
            chapters.add(new ChapterInfo(
                    chapterId,
                    title,
                    href,
                    1 // Single volume for this template
            ));
        }
        
        return chapters;
    }

    private List<VolumeInfo> groupChaptersIntoVolumes(List<ChapterInfo> chapters) {
        if (chapters.isEmpty()) {
            return List.of();
        }
        return List.of(new VolumeInfo(1, "Volume 1", chapters));
    }

    private void cleanChapterContent(Element contentEl) {
        // Bad tags from Python initialize()
        contentEl.select("script, style, noscript, iframe").remove();
        
        // Bad CSS from Python initialize()
        String[] badCss = {
            ".osny-nightmode", ".code-block", ".zeno_font_resizer_container",
            ".sharedaddy", ".jp-relatedposts", ".saboxplugin-wrap",
            ".post-ratings", ".post-tags", ".entry-meta",
            ".author-box", ".yarpp-related"
        };
        for (String selector : badCss) {
            contentEl.select(selector).remove();
        }
    }

    private String toAbsoluteUrl(String url) {
        if (url == null || url.isEmpty()) return "";
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        if (url.startsWith("//")) return "https:" + url;
        if (url.startsWith("/")) return "https://novelasligera.com" + url;
        return "https://novelasligera.com/" + url;
    }
}