package com.glez.frontendservice.controllers;

import com.glez.frontendservice.crawler.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST endpoints for multi-source novel catalog.
 * Provides dynamic source discovery and unified search/browse API.
 */
@Slf4j
@RestController
@RequestMapping("/api/sources")
@RequiredArgsConstructor
@Tag(name = "Sources", description = "Multi-source novel catalog - dynamic source registration and unified search")
public class SourceController {

    private final SourceRegistry sourceRegistry;

    /**
     * Get all registered sources for frontend menu.
     * Used by AppMenu to build dynamic submenu under "Gestor de Novelas".
     */
    @Operation(summary = "List all registered novel sources",
            description = "Returns metadata for all enabled sources. Used by frontend to build dynamic source submenu.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of sources",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = SourceMetadata.class)))
    })
    @GetMapping
    public ResponseEntity<List<SourceMetadata>> getAllSources() {
        return ResponseEntity.ok(sourceRegistry.getEnabledSources());
    }

    /**
     * Get metadata for a specific source.
     */
    @Operation(summary = "Get source metadata by ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Source metadata"),
            @ApiResponse(responseCode = "404", description = "Source not found")
    })
    @GetMapping("/{sourceId}")
    public ResponseEntity<SourceMetadata> getSource(@PathVariable String sourceId) {
        return sourceRegistry.getSource(sourceId)
                .map(SourceCrawler::getMetadata)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Search novels in a specific source with pagination.
     */
    @Operation(summary = "Search novels in a source",
            description = "Search for novels by query string in the specified source with pagination support.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Search results",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = NovelSearchResult.class))),
            @ApiResponse(responseCode = "404", description = "Source not found"),
            @ApiResponse(responseCode = "500", description = "Search failed")
    })
    @GetMapping("/{sourceId}/search")
    public ResponseEntity<PageResponse<NovelSearchResult>> search(
            @Parameter(description = "Source identifier") @PathVariable String sourceId,
            @Parameter(description = "Search query") @RequestParam String q,
            @Parameter(description = "Page number (0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "50") int size) {
        
        SourceCrawler crawler = sourceRegistry.requireSource(sourceId);
        try {
            List<NovelSearchResult> allResults = crawler.search(q);
            
            // Apply pagination
            int start = page * size;
            int end = Math.min(start + size, allResults.size());
            
            if (start >= allResults.size()) {
                return ResponseEntity.ok(new PageResponse<>(List.of(), page, size, allResults.size(), 0));
            }
            
            List<NovelSearchResult> pagedResults = allResults.subList(start, end);
            int totalPages = (int) Math.ceil((double) allResults.size() / size);
            
            return ResponseEntity.ok(new PageResponse<>(pagedResults, page, size, allResults.size(), totalPages));
        } catch (CrawlerException e) {
            log.error("Search failed for source {}: {}", sourceId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get novel details from a specific source.
     */
    @Operation(summary = "Get novel details from a source",
            description = "Retrieve full novel metadata including volumes and chapters.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Novel details",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = NovelDetail.class))),
            @ApiResponse(responseCode = "404", description = "Source or novel not found"),
            @ApiResponse(responseCode = "500", description = "Retrieval failed")
    })
    @GetMapping("/{sourceId}/novels/{novelId}")
    public ResponseEntity<NovelDetail> getNovel(
            @Parameter(description = "Source identifier") @PathVariable String sourceId,
            @Parameter(description = "Novel URL or source-specific ID") @PathVariable String novelId) {
        
        SourceCrawler crawler = sourceRegistry.requireSource(sourceId);
        try {
            // The novelId might be a full URL or just an ID - pass as-is to crawler
            String novelUrl = novelId.startsWith("http") ? novelId : crawler.getBaseUrl() + novelId;
            NovelDetail detail = crawler.getNovelDetails(novelUrl);
            return ResponseEntity.ok(detail);
        } catch (CrawlerException e) {
            if (e.getMessage().contains("not found") || e.getMessage().contains("404")) {
                return ResponseEntity.notFound().build();
            }
            log.error("Get novel failed for source {}: {}", sourceId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get chapter list for a novel from a specific source.
     */
    @Operation(summary = "Get chapters for a novel from a source")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Chapter list",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ChapterInfo.class))),
            @ApiResponse(responseCode = "404", description = "Source or novel not found"),
            @ApiResponse(responseCode = "500", description = "Retrieval failed")
    })
    @GetMapping("/{sourceId}/novels/{novelId}/chapters")
    public ResponseEntity<List<ChapterInfo>> getChapters(
            @Parameter(description = "Source identifier") @PathVariable String sourceId,
            @Parameter(description = "Novel URL or source-specific ID") @PathVariable String novelId) {
        
        SourceCrawler crawler = sourceRegistry.requireSource(sourceId);
        try {
            String novelUrl = novelId.startsWith("http") ? novelId : crawler.getBaseUrl() + novelId;
            List<ChapterInfo> chapters = crawler.getChapters(novelUrl);
            return ResponseEntity.ok(chapters);
        } catch (CrawlerException e) {
            if (e.getMessage().contains("not found") || e.getMessage().contains("404")) {
                return ResponseEntity.notFound().build();
            }
            log.error("Get chapters failed for source {}: {}", sourceId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get chapter content from a specific source.
     */
    @Operation(summary = "Get chapter content from a source",
            description = "Retrieve full chapter HTML content and images.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Chapter content",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ChapterContent.class))),
            @ApiResponse(responseCode = "404", description = "Source or chapter not found"),
            @ApiResponse(responseCode = "500", description = "Retrieval failed")
    })
    @GetMapping("/{sourceId}/novels/{novelId}/chapters/{chapterId}")
    public ResponseEntity<ChapterContent> getChapter(
            @Parameter(description = "Source identifier") @PathVariable String sourceId,
            @Parameter(description = "Novel URL or source-specific ID") @PathVariable String novelId,
            @Parameter(description = "Chapter URL or source-specific ID") @PathVariable String chapterId) {
        
        SourceCrawler crawler = sourceRegistry.requireSource(sourceId);
        try {
            String chapterUrl = chapterId.startsWith("http") ? chapterId : crawler.getBaseUrl() + chapterId;
            ChapterContent content = crawler.getChapterContent(chapterUrl);
            return ResponseEntity.ok(content);
        } catch (CrawlerException e) {
            if (e.getMessage().contains("not found") || e.getMessage().contains("404")) {
                return ResponseEntity.notFound().build();
            }
            log.error("Get chapter content failed for source {}: {}", sourceId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get chapter content from a specific source (query param version to avoid path matching issues).
     */
    @Operation(summary = "Get chapter content from a source (query param)",
            description = "Retrieve full chapter HTML content and images using query parameter for chapter URL.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Chapter content",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ChapterContent.class))),
            @ApiResponse(responseCode = "404", description = "Source or chapter not found"),
            @ApiResponse(responseCode = "500", description = "Retrieval failed")
    })
    @GetMapping("/{sourceId}/chapter-content")
    public ResponseEntity<ChapterContent> getChapterContent(
            @Parameter(description = "Source identifier") @PathVariable String sourceId,
            @Parameter(description = "Chapter URL") @RequestParam String chapterUrl) {

        SourceCrawler crawler = sourceRegistry.requireSource(sourceId);
        try {
            ChapterContent content = crawler.getChapterContent(chapterUrl);
            return ResponseEntity.ok(content);
        } catch (CrawlerException e) {
            if (e.getMessage().contains("not found") || e.getMessage().contains("404")) {
                return ResponseEntity.notFound().build();
            }
            log.error("Get chapter content failed for source {}: {}", sourceId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Health check all sources.
     */
    @Operation(summary = "Health check all sources",
            description = "Check connectivity and basic functionality of all registered sources.")
    @GetMapping("/health")
    public ResponseEntity<Map<String, Boolean>> healthCheckAll() {
        return ResponseEntity.ok(sourceRegistry.healthCheckAll());
    }

    /**
     * Get all novel IDs from a specific source.
     */
    @Operation(summary = "Get all novel IDs from a source")
    @GetMapping("/{sourceId}/novels/ids")
    public ResponseEntity<List<String>> getAllNovelIds(
            @PathVariable String sourceId) {
        SourceCrawler crawler = sourceRegistry.requireSource(sourceId);
        try {
            // This would need to be implemented in the crawler interface
            // For now, return empty list - crawler needs getAllNovelIds method
            return ResponseEntity.ok(List.of());
        } catch (Exception e) {
            log.error("Get all novel IDs failed for source {}: {}", sourceId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Health check a specific source.
     */
    @Operation(summary = "Health check a specific source")
    @GetMapping("/{sourceId}/health")
    public ResponseEntity<Map<String, Boolean>> healthCheck(
            @PathVariable String sourceId) {
        SourceCrawler crawler = sourceRegistry.requireSource(sourceId);
        boolean healthy;
        try {
            healthy = crawler.isHealthy();
        } catch (Exception e) {
            healthy = false;
        }
        return ResponseEntity.ok(Map.of(sourceId, healthy));
    }
}