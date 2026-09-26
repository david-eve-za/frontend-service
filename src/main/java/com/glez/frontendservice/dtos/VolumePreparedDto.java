package com.glez.frontendservice.dtos;

import java.util.UUID;

/**
 * Respuesta de la preparación del texto de un volumen: el volumen queda
 * enlazado a un Book del pipeline listo para el split en el wizard.
 */
public record VolumePreparedDto(
        UUID volumeId,
        UUID bookId,
        String bookName) {
}
