package com.glez.frontendservice.model.source.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Modelo de volumen extraído por crawler.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrawlerVolumeModel {

    /** Número de serie del volumen (1, 2, 3...) - CLAVE DE NEGOCIO */
    private int id;

    /** Título del volumen */
    private String title;

    /** Capítulos de este volumen */
    private List<CrawlerChapterModel> chapters;

    /** Metadatos extra */
    private Map<String, Object> extras;

    public Map<String, Object> getExtras() {
        return extras != null ? extras : Map.of();
    }
}