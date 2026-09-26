package com.glez.frontendservice.dtos;

import com.glez.frontendservice.model.NovelVolumeFile;
import java.util.UUID;

/**
 * Variante de formato (nivel 3) de un volumen del catálogo.
 */
public record VolumeFileDto(
        UUID id,
        NovelVolumeFile.Format format,
        Long sizeBytes,
        String fileName,
        boolean presentLocally) {

    public static VolumeFileDto of(NovelVolumeFile file, boolean presentLocally) {
        return new VolumeFileDto(file.getId(), file.getFormat(), file.getSizeBytes(),
                file.getFileName(), presentLocally);
    }
}
