package com.glez.frontendservice.services;

import com.glez.frontendservice.components.NovelCatalogPaths;
import com.glez.frontendservice.dtos.VolumePreparedDto;
import com.glez.frontendservice.model.Book;
import com.glez.frontendservice.model.NovelVolume;
import com.glez.frontendservice.model.NovelVolumeFile;
import com.glez.frontendservice.repository.NovelVolumeFileRepository;
import com.glez.frontendservice.repository.NovelVolumeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Prepara el texto de un volumen del catálogo para el pipeline (paso previo
 * al "Hacer Split" del wizard). Fuente híbrida: reutiliza el archivo ya
 * descargado por el crawler en el directorio de salida y, si no existe,
 * lo descarga on-demand desde elscione (quedando también disponible para
 * futuras ejecuciones).
 */
@Slf4j
@Service
public class NovelsVolumeTextService {

    /** Preferencia de formato para extraer texto (el parser Tika soporta todos). */
    private static final List<NovelVolumeFile.Format> FORMAT_PREFERENCE = List.of(
            NovelVolumeFile.Format.EPUB,
            NovelVolumeFile.Format.PDF,
            NovelVolumeFile.Format.ZIP,
            NovelVolumeFile.Format.TXT);

    private final NovelVolumeRepository volumeRepository;
    private final NovelVolumeFileRepository fileRepository;
    private final ElscioneApiClient apiClient;
    private final NovelCatalogPaths catalogPaths;
    private final BookProcessingService bookProcessingService;

    public NovelsVolumeTextService(NovelVolumeRepository volumeRepository,
                                   NovelVolumeFileRepository fileRepository,
                                   ElscioneApiClient apiClient,
                                   NovelCatalogPaths catalogPaths,
                                   BookProcessingService bookProcessingService) {
        this.volumeRepository = volumeRepository;
        this.fileRepository = fileRepository;
        this.apiClient = apiClient;
        this.catalogPaths = catalogPaths;
        this.bookProcessingService = bookProcessingService;
    }

    /**
     * Obtiene el texto del volumen (local o descarga on-demand), crea el
     * Book del pipeline y lo enlaza al volumen.
     *
     * @throws IllegalArgumentException si el volumen no existe o no tiene archivo fuente
     * @throws IllegalStateException si el volumen ya tiene un Book enlazado
     */
    @Transactional
    public VolumePreparedDto prepareText(java.util.UUID volumeId) {
        NovelVolume volume = volumeRepository.findById(volumeId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Volume " + volumeId + " not found"));
        if (volume.getBook() != null) {
            throw new IllegalStateException(
                    "Volume " + volumeId + " is already linked to book " + volume.getBook().getId());
        }

        NovelVolumeFile source = selectSourceFile(volume);
        if (source == null) {
            throw new IllegalArgumentException(
                    "Volume " + volumeId + " has no downloadable source file");
        }

        Path localPath = catalogPaths.localPathOf(source.getRemoteHref());
        if (!Files.exists(localPath)) {
            log.info("Volume file {} not present locally; downloading on demand", source.getRemoteHref());
            Path parent = localPath.getParent();
            if (parent != null) {
                parent.toFile().mkdirs();
            }
            apiClient.downloadToFile(apiClient.getFullUrl(source.getRemoteHref()), localPath, null);
        }

        try (InputStream inputStream = Files.newInputStream(localPath)) {
            Book book = bookProcessingService.storeBook(inputStream, source.getFileName());
            volume.setBook(book);
            volumeRepository.save(volume);
            log.info("Volume {} linked to new book {}", volume.getId(), book.getId());
            return new VolumePreparedDto(volume.getId(), book.getId(), book.getName());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not read volume file " + localPath + ": " + e.getMessage(), e);
        }
    }

    /** Mejor archivo del volumen: preferencia de formato y, entre iguales, el ya presente localmente. */
    NovelVolumeFile selectSourceFile(NovelVolume volume) {
        List<NovelVolumeFile> files = fileRepository.findByVolumeId(volume.getId());
        return files.stream()
                .filter(file -> file.getFormat() != NovelVolumeFile.Format.OTHER)
                .sorted(Comparator
                        .comparing((NovelVolumeFile file) -> FORMAT_PREFERENCE.indexOf(file.getFormat()))
                        .thenComparing(file -> !catalogPaths.isPresentLocally(file.getRemoteHref())))
                .findFirst()
                .orElse(null);
    }
}
