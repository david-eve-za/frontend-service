package com.glez.frontendservice.model;

import jakarta.persistence.*;

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
@Table(name = "video_story_projects")
public class VideoStoryProject {

    /**
     * Id del proyecto: lo ASIGNA el servicio (no @GeneratedValue) para que
     * el PASO 1 pueda conocerlo desde ANTES de la llamada a la IA — el
     * MDC traceId del paso 1 ES el id futuro del proyecto, así TODAS sus
     * líneas (llamada incluida, minutos) quedan rastreables en el visor
     * bajo el mismo id que la cronología. La columna H2 es la misma
     * ('uuid not null') y el único escritor es el propio asistente.
     */
    @Id
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

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getStory() {
        return story;
    }

    public void setStory(String story) {
        this.story = story;
    }

    public Integer getVideoDurationSeconds() {
        return videoDurationSeconds;
    }

    public void setVideoDurationSeconds(Integer videoDurationSeconds) {
        this.videoDurationSeconds = videoDurationSeconds;
    }

    public String getDesignStyle() {
        return designStyle;
    }

    public void setDesignStyle(String designStyle) {
        this.designStyle = designStyle;
    }

    public Integer getDesignCharacterCount() {
        return designCharacterCount;
    }

    public void setDesignCharacterCount(Integer designCharacterCount) {
        this.designCharacterCount = designCharacterCount;
    }

    public String getDesigns() {
        return designs;
    }

    public void setDesigns(String designs) {
        this.designs = designs;
    }

    public String getAssetsAspectRatio() {
        return assetsAspectRatio;
    }

    public void setAssetsAspectRatio(String assetsAspectRatio) {
        this.assetsAspectRatio = assetsAspectRatio;
    }

    public String getAssets() {
        return assets;
    }

    public void setAssets(String assets) {
        this.assets = assets;
    }

    public Integer getSceneAvgSeconds() {
        return sceneAvgSeconds;
    }

    public void setSceneAvgSeconds(Integer sceneAvgSeconds) {
        this.sceneAvgSeconds = sceneAvgSeconds;
    }

    public String getSceneSpeaker1() {
        return sceneSpeaker1;
    }

    public void setSceneSpeaker1(String sceneSpeaker1) {
        this.sceneSpeaker1 = sceneSpeaker1;
    }

    public String getSceneSpeaker2() {
        return sceneSpeaker2;
    }

    public void setSceneSpeaker2(String sceneSpeaker2) {
        this.sceneSpeaker2 = sceneSpeaker2;
    }

    public String getSceneLanguage() {
        return sceneLanguage;
    }

    public void setSceneLanguage(String sceneLanguage) {
        this.sceneLanguage = sceneLanguage;
    }

    public String getScenes() {
        return scenes;
    }

    public void setScenes(String scenes) {
        this.scenes = scenes;
    }

    public String getVideoPrompt() {
        return videoPrompt;
    }

    public void setVideoPrompt(String videoPrompt) {
        this.videoPrompt = videoPrompt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}