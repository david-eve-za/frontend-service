package com.glez.frontendservice.dtos;

/**
 * Paso 4 (opcional) del asistente: la escena elegida que la IA debe
 * transportar a la plantilla de generación de video.
 *
 * @param scene          texto completo de la escena (obligatorio)
 * @param animationStyle estilo para [3D ANIMATION STYLE]; vacío usa el del paso 2 del proyecto
 */
public record WizardVideoStepRequest(
        String scene,
        String animationStyle) {
}
