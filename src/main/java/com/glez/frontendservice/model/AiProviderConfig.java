package com.glez.frontendservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Selección de proveedor de IA por caso de uso (fila ÚNICA con id fijo 1):
 * traducción de libros y Asistente de Historias pueden usar el pool de
 * NVIDIA (cloud con fallback local) o el pool 100% local de Ollama. Los
 * valores se cambian en caliente desde la página de Configuración y
 * sobreviven a los reinicios; los defaults vienen de application.yml
 * (app.ai.translation-provider / app.ai.story-provider).
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "ai_provider_config")
public class AiProviderConfig {

    /** Fila única: id fijo asignado (sin generador). */
    @Id
    private Long id;

    /** Proveedor del pipeline de TRADUCCIÓN: nvidia | ollama. */
    @Column(name = "translation_provider", nullable = false, length = 20)
    private String translationProvider;

    /** Proveedor del ASISTENTE de historias: nvidia | ollama. */
    @Column(name = "story_provider", nullable = false, length = 20)
    private String storyProvider;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
