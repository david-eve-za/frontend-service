package com.glez.frontendservice.dtos;

/**
 * Paso 3 del asistente: parámetros para generar la BIBLIOTECA DE ASSETS
 * (entornos, props, tecnología, documentos, vehículos, motifs) sobre la
 * historia y los diseños del proyecto. Todos los campos son opcionales:
 * el estilo hereda por defecto el del paso 2 (consistencia visual) y el
 * resto cae a las reglas de derivación que la propia plantilla define.
 *
 * @param animationStyle estilo para [INSERT ANIMATION STYLE]; vacío usa el del paso 2
 * @param targetAudience audiencia para [INSERT TARGET AUDIENCE]; vacío la IA la deriva de la historia
 * @param aspectRatio   16:9 / 9:16 / 1:1; vacío usa 16:9
 * @param colorPalette  paleta de colores; vacío la IA la deriva de la historia
 */
public record WizardAssetsStepRequest(
        String animationStyle,
        String targetAudience,
        String aspectRatio,
        String colorPalette) {
}
