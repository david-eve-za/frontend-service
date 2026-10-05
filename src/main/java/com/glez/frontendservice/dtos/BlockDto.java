package com.glez.frontendservice.dtos;

import java.util.UUID;

/**
 * Bloque semántico (Prólogo / Capítulo / Epílogo) de un libro, para la
 * selección de alcance de la traducción. El contenido completo no viaja:
 * solo un extracto y la longitud para identificar el bloque.
 */
public record BlockDto(
        UUID id,
        String blockType,
        Integer blockNumber,
        int position,
        String preview,
        int contentLength) {
}
