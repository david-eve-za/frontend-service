package com.glez.frontendservice.dtos;

/**
 * Paso 2 del asistente: parámetros para generar los DISEÑOS DE PERSONAJES
 * sobre la historia del proyecto.
 *
 * @param animationStyle  [3D ANIMATION STYLE], valor EN (obligatorio)
 * @param characterCount  nº de bloques "Character N:" (null/<=0 → 4)
 */
public record WizardDesignStepRequest(
        String animationStyle,
        Integer characterCount) {
}
