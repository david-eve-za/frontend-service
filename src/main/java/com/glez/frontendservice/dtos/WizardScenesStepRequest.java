package com.glez.frontendservice.dtos;

/**
 * Paso 4 del asistente: parámetros para generar las ESCENAS sobre la
 * historia y los diseños del proyecto. El NÚMERO de escenas ya no se pide:
 * se deriva de la duración total del video (guardada en el proyecto) y de
 * la duración media por escena elegida aquí.
 *
 * @param averageSceneSeconds duración media por escena en segundos (null/<=0 → 10);
 *                            nº de escenas ≈ duración total / este valor
 * @param speakerFirst        nombre del personaje que habla primero (opcional: omite su línea)
 * @param speakerSecond       nombre del personaje que habla segundo (opcional: omite su línea)
 * @param language            idioma de pronunciación del diálogo (default Spanish)
 */
public record WizardScenesStepRequest(
        Integer averageSceneSeconds,
        String speakerFirst,
        String speakerSecond,
        String language) {
}
