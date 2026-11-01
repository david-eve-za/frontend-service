package com.glez.frontendservice.services.anilist;

import com.glez.frontendservice.dtos.NovelSummaryDto;
import com.glez.frontendservice.dtos.anilist.AniListMedia;
import com.glez.frontendservice.model.Novel;
import com.glez.frontendservice.repository.NovelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NovelAnilistEnrichmentService {

    private final AniListGraphQLClient anilistClient;
    private final NovelRepository novelRepository;

    /**
     * Enriquece una novela buscando en AniList por su título.
     * Si encuentra coincidencia, actualiza la novela con datos de AniList.
     */
    @Transactional
    public NovelEnrichmentResult enrichNovel(String novelId) {
        UUID uuid = UUID.fromString(novelId);
        Novel novel = novelRepository.findById(uuid)
                .orElseThrow(() -> new IllegalArgumentException("Novela no encontrada: " + novelId));

        // Si ya tiene anilistId, usar ese directamente
        if (novel.getAnilistId() != null) {
            return enrichByAnilistId(novel, novel.getAnilistId());
        }

        // Buscar por título en AniList
        List<AniListMedia> results = anilistClient.searchManga(novel.getTitle());

        if (results.isEmpty()) {
            return NovelEnrichmentResult.notFound("No se encontró coincidencia en AniList para: " + novel.getTitle());
        }

        // Tomar el primer resultado (el más relevante)
        AniListMedia bestMatch = results.get(0);
        return applyAnilistData(novel, bestMatch);
    }

    /**
     * Enriquece usando el ID conocido de AniList.
     */
    @Transactional
    public NovelEnrichmentResult enrichByAnilistId(Novel novel, Integer anilistId) {
        // TODO: implementar getMediaById cuando el cliente lo soporte
        // Por ahora, buscar por título usando el título de la novela
        List<AniListMedia> results = anilistClient.searchManga(novel.getTitle());

        if (results.isEmpty()) {
            return NovelEnrichmentResult.notFound("No se encontró en AniList con ID: " + anilistId);
        }

        // Buscar el que coincida con el ID
        Optional<AniListMedia> match = results.stream()
                .filter(m -> anilistId.equals(m.getId()))
                .findFirst();

        if (match.isEmpty()) {
            return NovelEnrichmentResult.notFound("ID de AniList " + anilistId + " no está en los resultados de búsqueda");
        }

        return applyAnilistData(novel, match.get());
    }

    /**
     * Enriquecimiento en lote: busca y enriquece múltiples novelas.
     * Útil para botón "Enriquecer todo".
     */
    @Transactional
    public BatchEnrichmentResult enrichBatch(List<String> novelIds) {
        int enriched = 0;
        int notFound = 0;
        int errors = 0;
        List<String> errorDetails = new java.util.ArrayList<>();

        for (String novelId : novelIds) {
            try {
                NovelEnrichmentResult result = enrichNovel(novelId);
                if (result.success()) {
                    enriched++;
                } else {
                    notFound++;
                    errorDetails.add(novelId + ": " + result.message());
                }
            } catch (Exception e) {
                errors++;
                errorDetails.add(novelId + ": " + e.getMessage());
                log.warn("Error enriqueciendo novela {}", novelId, e);
            }
        }

        return new BatchEnrichmentResult(enriched, notFound, errors, errorDetails);
    }

    /**
     * Busca en AniList sin guardar, solo para preview.
     */
    public List<AniListSearchPreview> previewSearch(String title) {
        List<AniListMedia> results = anilistClient.searchManga(title, 1, 5);
        return results.stream()
                .map(m -> new AniListSearchPreview(
                        m.getId(),
                        m.getTitle().getRomaji(),
                        m.getTitle().getEnglish(),
                        m.getCoverImage() != null ? m.getCoverImage().getLarge() : null,
                        m.getDescription(),
                        m.getGenres(),
                        m.getAverageScore(),
                        m.getStatus()))
                .collect(Collectors.toList());
    }

    private NovelEnrichmentResult applyAnilistData(Novel novel, AniListMedia media) {
        String description = media.getDescription();
        if (description != null) {
            // Limpiar HTML básico de la descripción de AniList
            description = description.replaceAll("<[^>]+>", "").replaceAll("&nbsp;", " ").trim();
        }

        novel.setAnilistId(media.getId());
        novel.setAnilistCoverUrl(media.getCoverImage() != null ? media.getCoverImage().getLarge() : null);
        novel.setAnilistDescription(description);
        novel.setAnilistGenres(media.getGenres());
        novel.setAnilistTags(media.getTags() != null
                ? media.getTags().stream().map(t -> t.getName()).collect(Collectors.toList())
                : List.of());
        novel.setAnilistStatus(media.getStatus());
        novel.setAnilistAverageScore(media.getAverageScore());
        novel.setAnilistFormat(media.getFormat());
        novel.setAnilistSource(media.getSource());
        novel.setAnilistCountryOfOrigin(media.getCountryOfOrigin());
        novel.setAnilistVolumes(media.getVolumes());
        novel.setAnilistChapters(media.getChapters());

        novelRepository.save(novel);

        log.info("Novela '{}' enriquecida con datos de AniList (ID: {})", novel.getTitle(), media.getId());
        return NovelEnrichmentResult.success("Enriquecida con datos de AniList (ID: " + media.getId() + ")");
    }

    // --- DTOs de resultado ---

    public record NovelEnrichmentResult(boolean success, String message) {
        public static NovelEnrichmentResult success(String message) {
            return new NovelEnrichmentResult(true, message);
        }
        public static NovelEnrichmentResult notFound(String message) {
            return new NovelEnrichmentResult(false, message);
        }
        public static NovelEnrichmentResult error(String message) {
            return new NovelEnrichmentResult(false, message);
        }
    }

    public record BatchEnrichmentResult(
            int enriched,
            int notFound,
            int errors,
            List<String> errorDetails
    ) {}

    public record AniListSearchPreview(
            Integer id,
            String titleRomaji,
            String titleEnglish,
            String coverUrl,
            String description,
            List<String> genres,
            Integer averageScore,
            String status
    ) {}
}