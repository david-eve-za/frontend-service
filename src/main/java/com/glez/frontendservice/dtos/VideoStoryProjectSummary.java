package com.glez.frontendservice.dtos;

import java.time.Instant;
import java.util.UUID;

/**
 * Resumen de un proyecto del asistente para la lista del frontend: sin los
 * outputs pesados (que viajan en el detalle).
 */
public record VideoStoryProjectSummary(
        UUID id,
        String title,
        boolean hasStory,
        boolean hasDesigns,
        boolean hasAssets,
        boolean hasScenes,
        boolean hasVideoPrompt,
        Instant createdAt) {
}
