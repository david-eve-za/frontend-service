package com.glez.frontendservice.services;

import com.glez.frontendservice.components.NovelCatalogPaths;
import com.glez.frontendservice.dtos.NovelSummaryDto;
import com.glez.frontendservice.dtos.VolumeDto;
import com.glez.frontendservice.dtos.VolumeFileDto;
import com.glez.frontendservice.model.Book;
import com.glez.frontendservice.model.ChunkStatus;
import com.glez.frontendservice.model.Novel;
import com.glez.frontendservice.model.NovelVolume;
import com.glez.frontendservice.model.NovelVolumeFile;
import com.glez.frontendservice.model.ProcessingStatus;
import com.glez.frontendservice.repository.ChunksRepository;
import com.glez.frontendservice.repository.NovelRepository;
import com.glez.frontendservice.repository.NovelVolumeFileRepository;
import com.glez.frontendservice.repository.NovelVolumeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Ensambla los DTOs del gestor de novelas (lectura). Los estados de
 * procesamiento de cada volumen (split/traducido/audio/en ejecución) se
 * DERIVAN del Book enlazado — nunca se duplican en el catálogo.
 */
@Service
@RequiredArgsConstructor
public class NovelsCatalogQueryService {

    private final NovelRepository novelRepository;
    private final NovelVolumeRepository volumeRepository;
    private final NovelVolumeFileRepository fileRepository;
    private final ChunksRepository chunksRepository;
    private final NovelCatalogPaths catalogPaths;
    private final BookPipelineOrchestrator pipelineOrchestrator;

    /** Página de obras (nivel 1) para la tabla principal del gestor. */
    public Page<NovelSummaryDto> findNovels(String search, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, 200),
                Sort.by(Sort.Order.asc("title")));
        Page<Novel> novels = (search == null || search.isBlank())
                ? novelRepository.findAll(pageRequest)
                : novelRepository.findByTitleContainingIgnoreCase(search.trim(), pageRequest);
        return novels.map(this::toSummary);
    }

    /** Volúmenes (nivel 2) de una obra con estados derivados y acciones habilitadas. */
    public List<VolumeDto> findVolumes(UUID novelId) {
        Novel novel = novelRepository.findById(novelId)
                .orElseThrow(() -> new IllegalArgumentException("Novel " + novelId + " not found"));
        return volumeRepository.findByNovel(novel).stream()
                .sorted(Comparator.comparing(NovelVolume::getVolumeNumber,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(NovelVolume::getLabel,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::toVolumeDto)
                .toList();
    }

    private NovelSummaryDto toSummary(Novel novel) {
        return new NovelSummaryDto(
                novel.getId(),
                novel.getTitle(),
                novel.getCategory(),
                volumeRepository.countByNovel(novel),
                novel.getLastSyncedAt());
    }

    private VolumeDto toVolumeDto(NovelVolume volume) {
        Book book = volume.getBook();
        boolean running = book != null && pipelineOrchestrator.isRunning(book.getId());

        boolean splitDone = false;
        boolean translated = false;
        if (book != null) {
            long total = chunksRepository.countByBook(book);
            long completed = chunksRepository.countByBookAndStatus(book, ChunkStatus.COMPLETED);
            splitDone = total > 0;
            translated = total > 0 && total == completed;
        }
        boolean audioCreated = book != null && book.getAudioFilePath() != null;

        List<VolumeFileDto> files = fileRepository.findByVolumeId(volume.getId()).stream()
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
                book != null ? book.getId() : null,
                book != null ? book.getStatus().name() : null,
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
