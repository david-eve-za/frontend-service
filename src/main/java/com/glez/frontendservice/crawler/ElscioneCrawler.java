package com.glez.frontendservice.crawler;

import com.glez.frontendservice.services.ElscioneApiClient;
import com.glez.frontendservice.services.NovelsCatalogQueryService;
import com.glez.frontendservice.dtos.NovelSummaryDto;
import com.glez.frontendservice.dtos.VolumeDto;
import com.glez.frontendservice.model.Novel;
import com.glez.frontendservice.model.NovelVolume;
import com.glez.frontendservice.repository.NovelRepository;
import com.glez.frontendservice.repository.NovelVolumeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Elscione source crawler implementation.
 * Ports the existing NovelsCatalogSyncService + ElscioneApiClient logic to the SourceCrawler interface.
 * 
 * This is the reference implementation for the SourceCrawler pattern.
 */
@Slf4j
@Component
@Qualifier("elscione")
public class ElscioneCrawler implements SourceCrawler {

    private final ElscioneApiClient apiClient;
    private final NovelsCatalogQueryService catalogQueryService;
    private final NovelRepository novelRepository;
    private final NovelVolumeRepository volumeRepository;

    public ElscioneCrawler(ElscioneApiClient apiClient,
                           NovelsCatalogQueryService catalogQueryService,
                           NovelRepository novelRepository,
                           NovelVolumeRepository volumeRepository) {
        this.apiClient = apiClient;
        this.catalogQueryService = catalogQueryService;
        this.novelRepository = novelRepository;
        this.volumeRepository = volumeRepository;
    }

    @Override
    public String getSourceId() {
        return "elscione";
    }

    @Override
    public String getDisplayName() {
        return "Elscione";
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
        return "https://server.elscione.com/Officially%20Translated%20Light%20Novels/";
    }

    @Override
    public int getRateLimitRpm() {
        return 20;
    }

    @Override
    public boolean requiresLogin() {
        return false;
    }

    @Override
    public List<NovelSearchResult> search(String query) {
        log.debug("Searching Elscione for: {}", query);
        try {
            List<NovelSummaryDto> results = catalogQueryService.findNovels(query, 0, 50).getContent();
            return results.stream()
                    .map(this::toSearchResult)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Search failed for query '{}': {}", query, e.getMessage());
            throw new CrawlerException(getSourceId(), "search", "Search failed: " + e.getMessage(), e);
        }
    }

    @Override
    public NovelDetail getNovelDetails(String novelUrl) {
        log.debug("Getting novel details from Elscione: {}", novelUrl);
        try {
            // Extract UUID from URL or use as-is if it's already a UUID
            UUID novelId = extractNovelId(novelUrl);
            Optional<NovelSummaryDto> novelOpt = catalogQueryService.findByIdAsSummary(novelId);
            
            if (novelOpt.isEmpty()) {
                throw new CrawlerException(getSourceId(), "getNovelDetails", "Novel not found: " + novelUrl);
            }
            
            NovelSummaryDto dto = novelOpt.get();
            List<VolumeDto> volumes = catalogQueryService.findVolumes(dto.id());
            
            return toNovelDetail(dto, volumes);
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
            UUID novelId = extractNovelId(novelUrl);
            List<VolumeDto> volumes = catalogQueryService.findVolumes(novelId);
            
            // Note: VolumeDto doesn't include chapters - they would need a separate endpoint call
            // For now, return empty list. Full chapter list would require calling /novels/{id}/volumes/{volumeId}/chapters
            return volumes.stream()
                    .flatMap(volume -> {
                        // Would need to call catalogQueryService.findVolumes(novelId) with chapter details
                        // This is a limitation of the current DTO structure
                        return List.<ChapterInfo>of().stream();
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Get chapters failed for {}: {}", novelUrl, e.getMessage());
            throw new CrawlerException(getSourceId(), "getChapters", "Failed to get chapters: " + e.getMessage(), e);
        }
    }

    @Override
    public ChapterContent getChapterContent(String chapterUrl) {
        log.debug("Getting chapter content: {}", chapterUrl);
        // Elscione doesn't serve chapter content directly via HTML - it serves EPUB/PDF files
        // This would require downloading and parsing the volume file
        // For now, return a placeholder indicating this needs file download
        return ChapterContent.failure("Elscione serves volumes as EPUB/PDF files. Use prepareVolumeText to download and process.");
    }

    @Override
    public void login(String username, String password) {
        // Elscione doesn't require login for catalog access
        throw new UnsupportedOperationException("Elscione does not require login");
    }

    @Override
    public boolean isHealthy() {
        try {
            // Try a simple API call to check connectivity
            apiClient.listContents("/Officially%20Translated%20Light%20Novels/");
            return true;
        } catch (Exception e) {
            log.warn("Elscione health check failed: {}", e.getMessage());
            return false;
        }
    }

    private NovelSearchResult toSearchResult(NovelSummaryDto dto) {
        String coverUrl = dto.anilistCoverUrl();
        if (coverUrl == null || coverUrl.isBlank()) {
            coverUrl = "";
        }
        String info = String.format("%d volumes | %s", 
                dto.volumeCount(), 
                dto.anilistStatus() != null ? dto.anilistStatus() : "Unknown");
        return new NovelSearchResult(dto.title(), dto.id().toString(), info, coverUrl, dto.id().toString());
    }

    private NovelDetail toNovelDetail(NovelSummaryDto dto, List<VolumeDto> volumes) {
        List<VolumeInfo> volumeInfos = volumes.stream()
                .map(v -> new VolumeInfo(
                        v.volumeNumber() != null ? v.volumeNumber() : 0,
                        v.label() != null ? v.label() : "Volume " + (v.volumeNumber() != null ? v.volumeNumber() : 0),
                        List.of() // Chapters not included in VolumeDto - would need separate call
                ))
                .collect(Collectors.toList());

        return new NovelDetail(
                dto.title(),
                "", // author not in summary
                dto.anilistCoverUrl() != null ? dto.anilistCoverUrl() : "",
                dto.anilistDescription() != null ? dto.anilistDescription() : "",
                dto.anilistGenres() != null ? dto.anilistGenres() : List.of(),
                volumeInfos,
                "es", // language default
                false, // isMtl default
                false, // isManga
                dto.id().toString(),
                dto.anilistStatus() != null ? dto.anilistStatus() : "Unknown",
                dto.lastSyncedAt() != null ? dto.lastSyncedAt().toString() : ""
        );
    }

    private UUID extractNovelId(String novelUrl) {
        try {
            // If it's already a UUID string
            return UUID.fromString(novelUrl);
        } catch (IllegalArgumentException e) {
            // If it's a URL, extract the UUID from the path
            // Expected format: .../novels/{uuid} or similar
            String[] parts = novelUrl.split("/");
            for (String part : parts) {
                try {
                    return UUID.fromString(part);
                } catch (IllegalArgumentException ignored) {
                }
            }
            throw new CrawlerException(getSourceId(), "extractNovelId", "Could not extract novel UUID from: " + novelUrl);
        }
    }

    @Override
    public List<String> getAllNovelIds() {
        try {
            // Get all novel IDs from the catalog query service
            return catalogQueryService.findAllNovelIds()
                    .stream()
                    .map(UUID::toString)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Get all novel IDs failed: {}", e.getMessage());
            return List.of();
        }
    }
}