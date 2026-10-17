package com.glez.frontendservice.dtos;

/**
 * Payload de creación de un prompt de historia completa (paso 1 del
 * asistente): los campos del formulario que rellenan los placeholders de la
 * plantilla narrativa. El backend ensambla el prompt final sustituyendo
 * cada placeholder (los opcionales vacíos omiten su línea/párrafo o caen a
 * un default). La extensión se rige por la DURACIÓN DEL VIDEO, no por un
 * conteo de palabras.
 */
public record StoryPromptRequest(
        String title,
        String genre,
        String subgenre,
        String targetAudience,
        String countryCulture,
        String location,
        String familyFriendly,
        String visualStyle,
        String tone,
        String realismLevel,
        String overallFeeling,
        String themes,
        Integer characterCount,
        String character1Requirements,
        String character2Requirements,
        String otherCharacterRequirements,
        String endingType,
        String mainEmotionalConflict,
        String unresolvedElement,
        String dialogueLanguage,
        Integer videoDurationSeconds,
        Integer pacingFirstPercent,
        Integer pacingMiddlePercent,
        Integer pacingFinalPercent,
        String platform) {
}
