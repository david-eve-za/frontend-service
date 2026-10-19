package com.glez.frontendservice.dtos;

/**
 * Paso 5 del asistente: la escena objetivo que la IA debe transportar a la
 * PLANTILLA MASTER de generación de video (15 secciones), más la referencia
 * opcional (archivo | URL | término de búsqueda web).
 *
 * <p>Todos los campos son opcionales: sin nada, se usa la PRIMERA escena
 * del paso 4 y ninguna referencia — el paso es 100% automático.</p>
 *
 * @param sceneIndex          índice 1-based de la escena del paso 4 a transportar; vacío usa la primera
 * @param animationStyle       estilo visual; vacío usa el del paso 2 del proyecto
 * @param referenceFileContent contenido de texto de un archivo subido (leído en el navegador), usado como referencia
 * @param referenceUrl         URL http/https cuyo contenido se recupera en el servidor como referencia
 * @param referenceSearch      término que se busca en la web (DuckDuckGo) como referencia
 */
public record WizardVideoStepRequest(
        Integer sceneIndex,
        String animationStyle,
        String referenceFileContent,
        String referenceUrl,
        String referenceSearch) {
}
