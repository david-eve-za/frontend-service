package com.glez.frontendservice.services;

import com.glez.frontendservice.components.NovelCatalogPaths;
import com.glez.frontendservice.dtos.NovelSummaryDto;
import com.glez.frontendservice.dtos.VolumeDto;
import com.glez.frontendservice.dtos.VolumeFileDto;
import com.glez.frontendservice.model.ChunkStatus;
import com.glez.frontendservice.model.Novel;
import com.glez.frontendservice.model.NovelVolume;
import com.glez.frontendservice.model.NovelVolumeFile;
import com.glez.frontendservice.model.ChunkStatus;
import com.glez.frontendservice.repository.BookRepository;
import com.glez.frontendservice.repository.BookRepository.BookStatusView;
import com.glez.frontendservice.repository.ChunksRepository;
import com.glez.frontendservice.repository.ChunksRepository.ChunkCountView;
import com.glez.frontendservice.repository.NovelRepository;
import com.glez.frontendservice.repository.NovelVolumeFileRepository;
import com.glez.frontendservice.repository.NovelVolumeRepository;
import com.glez.frontendservice.repository.NovelVolumeRepository.NovelVolumeCount;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Ensambla los DTOs del gestor de novelas (lectura). Los estados de
 * procesamiento de cada volumen (split/traducido/audio/en ejecución) se
 * DERIVAN del Book enlazado — nunca se duplican en el catálogo.
 *
 * <p>Todas las consultas son de conjunto (batch): ninguna vista dispara
 * N+1 ni carga entidades Book (y su CLOB full_text) por volumen.</p>
 */
@Service
@RequiredArgsConstructor
public class NovelsCatalogQueryService {

    private final NovelRepository novelRepository;
    private final NovelVolumeRepository volumeRepository;
    private final NovelVolumeFileRepository fileRepository;
    private final ChunksRepository chunksRepository;
    private final BookRepository bookRepository;
    private final NovelCatalogPaths catalogPaths;
    private final BookPipelineOrchestrator pipelineOrchestrator;

    /** Página de obras (nivel 1) para la tabla principal del gestor. */
    @Transactional(readOnly = true)
    public Page<NovelSummaryDto> findNovels(String search, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, 200),
                Sort.by(Sort.Order.asc("title")));
        Page<Novel> novels = (search == null || search.isBlank())
                ? novelRepository.findAll(pageRequest)
                : novelRepository.findByTitleContainingIgnoreCase(search.trim(), pageRequest);

        // Conteo de volúmenes de TODA la página en una sola consulta
        // agrupada (antes: un COUNT por novela en la página).
        Map<UUID, Long> volumeCounts = volumeRepository
                .countByNovelIds(pageIds(novels)).stream()
                .collect(Collectors.toMap(NovelVolumeCount::getNovelId,
                        NovelVolumeCount::getVolumeCount));

        // Forzar evaluación inmediata dentro de la transacción para evitar
        // problemas de lazy loading (anilistGenres, anilistTags)
        List<NovelSummaryDto> content = novels.getContent().stream()
                .map(novel -> toSummary(novel, volumeCounts.getOrDefault(novel.getId(), 0L)))
                .collect(Collectors.toList());

        return new PageImpl<>(content, novels.getPageable(), novels.getTotalElements());
    }

    /** Busca una novela por ID (para endpoint de detalle). */
    @Transactional(readOnly = true)
    public Optional<Novel> findById(UUID novelId) {
        return novelRepository.findById(novelId);
    }

    /** Volúmenes (nivel 2) de una obra con estados derivados y acciones habilitadas. */
    @Transactional(readOnly = true)
    public List<VolumeDto> findVolumes(UUID novelId) {
        Novel novel = novelRepository.findById(novelId)
                .orElseThrow(() -> new IllegalArgumentException("Novel " + novelId + " not found"));
        List<NovelVolume> volumes = volumeRepository.findByNovel(novel).stream()
                .sorted(Comparator.comparing(NovelVolume::getVolumeNumber,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(NovelVolume::getLabel,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        // Una consulta por tipo de dato (antes: hasta 4 por volumen, con
        // carga lazy de cada Book y su CLOB full_text).
        Map<UUID, BookStatusView> books = bookStatusViews(volumes);
        Map<UUID, ChunkCountView> chunkCounts = chunkCounts(volumes);
        Map<UUID, List<NovelVolumeFile>> filesByVolume = filesByVolume(volumes);

        return volumes.stream()
                .map(volume -> toVolumeDto(volume, books, chunkCounts, filesByVolume))
                .toList();
    }

    private static List<UUID> pageIds(Page<Novel> novels) {
        return novels.getContent().stream().map(Novel::getId).toList();
    }

    private Map<UUID, BookStatusView> bookStatusViews(List<NovelVolume> volumes) {
        Set<UUID> bookIds = volumes.stream()
                .map(NovelVolume::getBookIdOrNull)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (bookIds.isEmpty()) {
            return Map.of();
        }
        return bookRepository.findStatusViews(bookIds).stream()
                .collect(Collectors.toMap(BookStatusView::getId, view -> view));
    }

    private Map<UUID, ChunkCountView> chunkCounts(List<NovelVolume> volumes) {
        Set<UUID> bookIds = volumes.stream()
                .map(NovelVolume::getBookIdOrNull)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (bookIds.isEmpty()) {
            return Map.of();
        }
        return chunksRepository.countByBookIds(bookIds, ChunkStatus.COMPLETED).stream()
                .collect(Collectors.toMap(ChunkCountView::getBookId, view -> view));
    }

    private Map<UUID, List<NovelVolumeFile>> filesByVolume(List<NovelVolume> volumes) {
        List<UUID> volumeIds = volumes.stream().map(NovelVolume::getId).toList();
        if (volumeIds.isEmpty()) {
            return Map.of();
        }
        return fileRepository.findByVolumeIdIn(volumeIds).stream()
                .collect(Collectors.groupingBy(file -> file.getVolume().getId()));
    }

    public NovelSummaryDto toSummary(Novel novel, long volumeCount) {
        // Forzar inicialización de colecciones lazy dentro de la transacción
        // para evitar LazyInitializationException durante la serialización JSON
        List<String> genres = novel.getAnilistGenres() != null
                ? new ArrayList<>(novel.getAnilistGenres())
                : null;
        List<String> tags = novel.getAnilistTags() != null
                ? new ArrayList<>(novel.getAnilistTags())
                : null;

        return new NovelSummaryDto(
                novel.getId(),
                novel.getTitle(),
                novel.getCategory(),
                volumeCount,
                novel.getLastSyncedAt(),
                novel.getAnilistId(),
                novel.getAnilistCoverUrl(),
                novel.getAnilistDescription(),
                genres,
                tags,
                novel.getAnilistStatus(),
                novel.getAnilistAverageScore(),
                novel.getAnilistFormat(),
                novel.getAnilistSource(),
                novel.getAnilistCountryOfOrigin(),
                novel.getAnilistVolumes(),
                novel.getAnilistChapters());
    }

    private VolumeDto toVolumeDto(NovelVolume volume,
                                  Map<UUID, BookStatusView> books,
                                  Map<UUID, ChunkCountView> chunkCounts,
                                  Map<UUID, List<NovelVolumeFile>> filesByVolume) {
        // Nunca se toca volume.getBook(): esa carga lazy arrastraría el
        // CLOB full_text entero por cada volumen.
        UUID bookId = volume.getBookIdOrNull();
        BookStatusView book = bookId != null ? books.get(bookId) : null;
        boolean running = bookId != null && pipelineOrchestrator.isRunning(bookId);

        boolean splitDone = false;
        boolean translated = false;
        if (book != null) {
            ChunkCountView counts = chunkCounts.get(bookId);
            long total = counts != null ? counts.getTotal() : 0;
            long completed = counts != null ? counts.getCompleted() : 0;
            splitDone = total > 0;
            translated = total > 0 && total == completed;
        }
        boolean audioCreated = book != null && book.getAudioFilePath() != null;

        List<VolumeFileDto> files = filesByVolume
                .getOrDefault(volume.getId(), List.of()).stream()
                .map(file -> VolumeFileDto.of(file, catalogPaths.isPresentLocally(file.getRemoteHref())))
                .toList();
        boolean sourceAvailable = !files.isEmpty() && !volume.isRemovedFromSource();

        boolean canPrepareText = book == null && sourceAvailable;
        boolean canTranslate = book != null && splitDone && !translated && !running;
        boolean canCreateAudio = book != null && translated && !audioCreated && !running;

        return new VolumeDto(
                volume.getId(),
                volume.getVolumeNumber(),
                volume.getLabel(),
                volume.getTranslatorGroup(),
                volume.isRemovedFromSource(),
                bookId,
                book != null && book.getStatus() != null ? book.getStatus().name() : null,
                splitDone,
                translated,
                audioCreated,
                running,
                sourceAvailable,
                canPrepareText,
                canTranslate,
                canCreateAudio,
                files);
    }
}
