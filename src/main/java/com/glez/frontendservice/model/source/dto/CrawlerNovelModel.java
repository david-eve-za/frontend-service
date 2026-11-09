package com.glez.frontendservice.model.source.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Modelo de novela extraída por crawler (para persistencia).
 * Equivalente a CrawlerNovel en Python lightnovel-crawler.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrawlerNovelModel {

    /** URL canónica de la novela en la fuente */
    private String url;

    /** Título de la novela */
    private String title;

    /** Autor(es) */
    private String author;

    /** URL de portada original */
    private String coverUrl;

    /** Sinopsis/descripción */
    private String synopsis;

    /** Código de idioma ISO 639-1 */
    private String language;

    /** Si es traducción automática (MTL) */
    private boolean mtl;

    /** Si es manga/manhwa/manhua */
    private boolean manga;

    /** Si es right-to-left */
    private boolean rtl;

    /** Estado: Ongoing, Completed, Hiatus, etc. */
    private String status;

    /** Última actualización en la fuente (string) */
    private String lastUpdate;

    /** Tags/géneros */
    private List<String> tags;

    /** Volúmenes con sus capítulos */
    private List<CrawlerVolumeModel> volumes;

    /** Metadatos extra del crawler (selectores, timestamps, etc.) */
    private Map<String, Object> extras;

    public Map<String, Object> getExtras() {
        return extras != null ? extras : Map.of();
    }
}