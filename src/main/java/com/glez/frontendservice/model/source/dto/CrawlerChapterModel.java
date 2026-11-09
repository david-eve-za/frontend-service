package com.glez.frontendservice.model.source.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Modelo de capítulo extraído por crawler.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrawlerChapterModel {

    /** Número de serie global del capítulo (1, 2, 3...) - CLAVE DE NEGOCIO */
    private int id;

    /** Título del capítulo */
    private String title;

    /** URL del capítulo en la fuente */
    private String url;

    /** Volumen al que pertenece (serial number, no UUID) */
    private Integer volume;

    /** Metadatos extra */
    private Map<String, Object> extras;

    public Map<String, Object> getExtras() {
        return extras != null ? extras : Map.of();
    }
}