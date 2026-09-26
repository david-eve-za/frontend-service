package com.glez.frontendservice.dtos;

import java.util.List;
import java.util.UUID;

/**
 * Fila de nivel 2 (volumen) del catálogo con los estados DERIVADOS del
 * {@link com.glez.frontendservice.model.Book} enlazado y las acciones
 * habilitadas (fuente única de verdad para los botones de la UI):
 *
 * <ul>
 *   <li>canPrepareText: no hay Book aún y el archivo fuente sigue disponible.</li>
 *   <li>canTranslate: hay chunks (split hecho) sin traducción completa.</li>
 *   <li>canCreateAudio: traducción completa sin audio generado.</li>
 * </ul>
 */
public record VolumeDto(
        UUID id,
        Integer volumeNumber,
        String label,
        String translatorGroup,
        boolean removedFromSource,
        UUID bookId,
        String bookStatus,
        boolean splitDone,
        boolean translated,
        boolean audioCreated,
        boolean running,
        boolean sourceAvailable,
        boolean canPrepareText,
        boolean canTranslate,
        boolean canCreateAudio,
        List<VolumeFileDto> files) {
}
