package com.glez.frontendservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Un PROYECTO del Asistente de Historias en Video: la cadena completa de la
 * timeline (1 historia → 2 diseños de personajes → 3 escenas → 4 plantilla de
 * video opcional) generada automáticamente por la IA en cada paso, donde la
 * salida de cada paso alimenta el siguiente. La presencia de cada output
 * marca el estado del wizard: sin historia no hay diseños, sin diseños no
 * hay escenas, sin escenas no hay plantilla de video.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "video_story_projects")
public class VideoStoryProject {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Nombre interno con el que se identifica el proyecto. */
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** PASO 1 — Historia completa generada por la IA con la plantilla narrativa. */
    @Column(name = "story", length = 20000)
    private String story;

    /** PASO 1 — Duración objetivo del video en segundos: dirige la extensión de la historia y de las escenas. */
    @Column(name = "video_duration_seconds")
    private Integer videoDurationSeconds;

    /** PASO 2 — Estilo de animación con el que se generaron los diseños. */
    @Column(name = "design_style", length = 500)
    private String designStyle;

    /** PASO 2 — Nº de bloques de personaje solicitados. */
    @Column(name = "design_character_count")
    private Integer designCharacterCount;

    /** PASO 2 — Diseños de personajes generados por la IA (uno por bloque). */
    @Column(name = "designs", length = 20000)
    private String designs;

    /** PASO 3 — Relación de aspecto elegida para la biblioteca de assets (16:9/9:16/1:1). */
    @Column(name = "assets_aspect_ratio", length = 20)
    private String assetsAspectRatio;

    /**
     * PASO 3 — Biblioteca de assets generada por la IA (entornos, props,
     * tecnología, documentos, vehículos, motifs) con IDs estables.
     */
    @Column(name = "assets", length = 30000)
    private String assets;

    /** PASO 4 — Duración media por escena en segundos (el nº de escenas se DERIVA de la duración total). */
    @Column(name = "scene_avg_seconds")
    private Integer sceneAvgSeconds;

    /** PASO 3 — Personaje que habla primero en las escenas. */
    @Column(name = "scene_speaker1", length = 300)
    private String sceneSpeaker1;

    /** PASO 3 — Personaje que habla segundo en las escenas. */
    @Column(name = "scene_speaker2", length = 300)
    private String sceneSpeaker2;

    /** PASO 3 — Idioma de pronunciación del diálogo de las escenas. */
    @Column(name = "scene_language", length = 100)
    private String sceneLanguage;

    /** PASO 3 — Escenas cinematográficas generadas por la IA. */
    @Column(name = "scenes", length = 30000)
    private String scenes;

    /** PASO 5 (opcional) — Plantilla MASTER de generación de video (15 secciones) rellena por la IA desde una escena. */
    @Column(name = "video_prompt", length = 40000)
    private String videoPrompt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
