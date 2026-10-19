package com.glez.frontendservice.services;

import com.glez.frontendservice.dtos.StoryPromptRequest;
import com.glez.frontendservice.dtos.WizardAssetsStepRequest;
import com.glez.frontendservice.dtos.WizardDesignStepRequest;
import com.glez.frontendservice.dtos.WizardScenesStepRequest;
import com.glez.frontendservice.dtos.WizardVideoStepRequest;
import com.glez.frontendservice.model.VideoStoryProject;
import com.glez.frontendservice.model.VideoStoryStep;
import com.glez.frontendservice.model.VideoStoryTraceEvent;
import com.glez.frontendservice.repository.VideoStoryProjectRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * El ASISTENTE de Historias en Video: ejecuta la timeline completa
 * encadenada por la IA —
 * <ol>
 *   <li>PASO 1 (STORY PROMPT): la plantilla narrativa rellena con el
 *       formulario produce la HISTORIA (dimensionada por la DURACIÓN del
 *       video).</li>
 *   <li>PASO 2 (CHARACTER DESIGN PROMPT): la historia incrustada produce
 *       los DISEÑOS de personajes con consistencia visual.</li>
 *   <li>PASO 3 (ASSETS DESIGN): historia+diseños producen la biblioteca de
 *       assets con IDs estables.</li>
 *   <li>PASO 4 (SCENE PROMPT GENERATOR): historia+diseños producen las
 *       ESCENAS — el número y los tiempos se derivan de la duración.</li>
 *   <li>PASO 5, opcional (VIDEO GENERATION MASTER PROMPT): la escena
 *       elegida por índice (default: la primera, SIN interacción) se
 *       transporta a la plantilla MASTER de 15 secciones con TODA la cadena
 *       embebida y una referencia opcional (archivo | URL | web).</li>
 * </ol>
 *
 * <p><b>Observabilidad exhaustiva</b>: cada paso corre con MDC
 * {@code traceId=projectId} y {@code step=WIZARD_*}, de modo que TODOS sus
 * eventos (incluidas las líneas [llm] del NvidiaAiService, que heredan el
 * MDC del hilo) quedan correlacionados por proyecto en el Visor de Logs y
 * en el histórico de BD. Por paso se loggea: INICIO con parámetros resueltos
 * (INFO, persistido), preview del prompt (DEBUG, solo visor), respuesta con
 * tamaño y duración (INFO), y los FALLOS con su causa (ERROR): truncado de
 * la IA, respuesta vacía o error de la rotación. Los rechazos de orden de
 * pasos y validaciones se loggean como WARN.</p>
 */
@Service
@RequiredArgsConstructor
public class VideoStoryWizardService {

    private static final Logger log = LoggerFactory.getLogger(VideoStoryWizardService.class);

    /**
     * Tope de salida AMPLIADO para los pasos del wizard cuya salida desborda
     * el tope global de 8192 tokens (dimensionado para el pipeline de
     * traducción): la biblioteca de assets (documento MASTER de 8 partes)
     * y las escenas (N escenas con sus 10 secciones cada una) lo necesitan.
     * Sin esto, esos pasos truncan SIEMPRE en done_reason=length.
     */
    static final int LONG_STEP_OUTPUT_TOKENS = 16384;

    /** Tope estándar para los pasos de salida contenida (historia, diseños, video). */
    static final int DEFAULT_STEP_OUTPUT_TOKENS = 8192;

    /** Proyectos con una generación en curso: cerrojo anti doble-disparo. */
    private final java.util.Set<UUID> runningProjects = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final NvidiaAiService ai;
    private final VideoStoryProjectRepository projectRepository;
    /** Referencia opcional del paso 5 (archivo | URL | búsqueda web) — solo el wizard. */
    private final WizardReferenceService referenceService;
    /** Cronología persistida de cada intento de paso (tabla propia del wizard). */
    private final VideoStoryTraceService traceService;

    /**
     * PASO 1: valida el formulario, ensambla la plantilla narrativa, pide la
     * historia a la IA y crea el proyecto con el resultado.
     */
    public VideoStoryProject createProjectWithStory(StoryPromptRequest request) {
        if (!VideoStoryPromptFactory.isValidStoryRequest(request)) {
            log.warn("[wizard] Paso 1 (historia) rechazado: faltan campos obligatorios del formulario");
            throw new IllegalArgumentException("Faltan campos obligatorios del formulario de historia.");
        }

        // El proyecto nace DESPUÉS de la llamada: el paso 1 corre bajo una
        // traza temporal; el id del proyecto queda correlacionado en el log
        // de creación para poder seguirlo en el visor.
        wizardMdc(UUID.randomUUID(), "WIZARD_STORY");
        try {
            log.info("[wizard] Paso 1 (historia) iniciado: «{}» — género «{}», tono «{}», audiencia «{}», "
                            + "duración {}s, {} personajes, plataforma «{}»",
                    request.title(), request.genre(), request.tone(), request.targetAudience(),
                    request.videoDurationSeconds(), request.characterCount(), request.platform());

            long aiStart = System.currentTimeMillis();
            String prompt = VideoStoryPromptFactory.buildStoryPrompt(request);
            String story = generate("paso 1 (historia)", prompt, DEFAULT_STEP_OUTPUT_TOKENS);

            VideoStoryProject project = new VideoStoryProject();
            project.setTitle(request.title().trim());
            project.setStory(story);
            // La duración dirige la extensión de las escenas (paso 4).
            project.setVideoDurationSeconds(request.videoDurationSeconds());
            Instant now = Instant.now();
            project.setCreatedAt(now);
            project.setUpdatedAt(now);
            VideoStoryProject saved = projectRepository.save(project);

            // Traza POST-creación: la fila del proyecto tiene que existir
            // para colgarle el evento. La duración REAL de la IA viaja en
            // los detalles; el evento queda como evidencia del paso en la
            // cronología del proyecto (los fallos del paso 1 no dejan fila
            // que tracear y quedan solo en el visor de logs, por diseño:
            // no se crean proyectos vacíos).
            VideoStoryTraceEvent trace = traceService.startStep(saved.getId(), VideoStoryStep.STORY, null);
            traceService.completeStep(trace, "historia de " + story.length() + " caracteres en "
                    + (System.currentTimeMillis() - aiStart) + " ms de IA");

            log.info("[wizard] Proyecto CREADO: «{}» (id={}, historia de {} caracteres, duración objetivo {})",
                    saved.getTitle(), saved.getId(), story.length(),
                    VideoStoryPromptFactory.formatDuration(request.videoDurationSeconds()));
            return saved;
        } finally {
            MDC.clear();
        }
    }

    /**
     * PASO 2: genera los DISEÑOS de personajes incrustando la historia del
     * proyecto. Requiere que el paso 1 haya terminado.
     */
    public VideoStoryProject generateDesigns(UUID projectId, WizardDesignStepRequest request) {
        if (request == null || request.animationStyle() == null || request.animationStyle().isBlank()) {
            log.warn("[wizard] Paso 2 (diseños) rechazado: estilo de animación ausente");
            throw new IllegalArgumentException("El estilo de animación es obligatorio.");
        }
        acquireProject(projectId);
        wizardMdc(projectId, "WIZARD_DESIGNS");
        try {
            VideoStoryProject project = requireStep(projectId, "paso 2 (diseños)",
                    p -> p.getStory() != null,
                    "El proyecto aún no tiene historia: ejecuta el paso 1 primero.");

            int characterCount = request.characterCount() != null && request.characterCount() > 0
                    ? request.characterCount()
                    : 4;
            log.info("[wizard] Paso 2 (diseños) iniciado: «{}» — estilo «{}», {} personajes",
                    project.getTitle(), request.animationStyle().trim(), characterCount);

            VideoStoryTraceEvent trace = traceService.startStep(projectId, VideoStoryStep.DESIGNS,
                    "estilo «" + request.animationStyle().trim() + "», " + characterCount + " personajes");
            try {
                String prompt = VideoStoryPromptFactory.buildCharacterDesignPrompt(
                        project.getStory(), request.animationStyle(), characterCount);
                String designs = generate("paso 2 (diseños)", prompt, DEFAULT_STEP_OUTPUT_TOKENS);

                project.setDesignStyle(request.animationStyle().trim());
                project.setDesignCharacterCount(characterCount);
                project.setDesigns(designs);
                traceService.completeStep(trace, designs.length() + " caracteres de diseños");
                log.info("[wizard] Diseños generados para «{}»: {} caracteres",
                        project.getTitle(), designs.length());
                return saveUpdated(project);
            } catch (Exception e) {
                traceService.failStep(trace, e);
                throw e;
            }
        } finally {
            releaseProject(projectId);
            MDC.clear();
        }
    }

    /**
     * PASO 3: genera la BIBLIOTECA DE ASSETS (entornos, props, tecnología,
     * documentos, vehículos, motifs) analizando la historia y los diseños
     * aprobados con la plantilla MASTER. Requiere el paso 2.
     */
    public VideoStoryProject generateAssets(UUID projectId, WizardAssetsStepRequest request) {
        acquireProject(projectId);
        wizardMdc(projectId, "WIZARD_ASSETS");
        try {
            VideoStoryProject project = requireStep(projectId, "paso 3 (assets)",
                    p -> p.getDesigns() != null,
                    "El proyecto aún no tiene diseños de personajes: ejecuta el paso 2 primero.");

            // El estilo por defecto hereda el del paso 2: la plantilla exige
            // consistencia visual con los diseños aprobados.
            String animationStyle = request != null && request.animationStyle() != null
                    && !request.animationStyle().isBlank()
                            ? request.animationStyle().trim()
                            : project.getDesignStyle();
            String aspectRatio = request != null && request.aspectRatio() != null
                    && !request.aspectRatio().isBlank()
                            ? request.aspectRatio().trim()
                            : "16:9";
            String targetAudience = request == null ? null : request.targetAudience();
            String colorPalette = request == null ? null : request.colorPalette();

            log.info("[wizard] Paso 3 (assets) iniciado: «{}» — estilo «{}», aspecto «{}», audiencia «{}», paleta «{}»",
                    project.getTitle(), animationStyle, aspectRatio,
                    targetAudience == null ? "(derivada de la historia)" : targetAudience,
                    colorPalette == null ? "(derivada de la historia)" : colorPalette);

            VideoStoryTraceEvent trace = traceService.startStep(projectId, VideoStoryStep.ASSETS,
                    "aspecto «" + aspectRatio + "»");
            try {
                String prompt = VideoStoryPromptFactory.buildAssetsPrompt(
                        project.getStory(), project.getDesigns(), animationStyle,
                        targetAudience, aspectRatio, colorPalette);
                String assets = generate("paso 3 (assets)", prompt, LONG_STEP_OUTPUT_TOKENS);

                project.setAssetsAspectRatio(aspectRatio);
                project.setAssets(assets);
                traceService.completeStep(trace, assets.length() + " caracteres de biblioteca");
                log.info("[wizard] Assets generados para «{}»: {} caracteres (aspecto {})",
                        project.getTitle(), assets.length(), aspectRatio);
                return saveUpdated(project);
            } catch (Exception e) {
                traceService.failStep(trace, e);
                throw e;
            }
        } finally {
            releaseProject(projectId);
            MDC.clear();
        }
    }

    /**
     * PASO 4: genera las ESCENAS cinematográficas incrustando historia +
     * diseños del proyecto. El NÚMERO de escenas se DERIVA de la duración
     * total del video (duración media por escena elegida en el formulario):
     * p. ej. 120 s con escenas de 10 s → 12 escenas. Requiere el paso 2.
     */
    public VideoStoryProject generateScenes(UUID projectId, WizardScenesStepRequest request) {
        acquireProject(projectId);
        wizardMdc(projectId, "WIZARD_SCENES");
        try {
            VideoStoryProject project = requireStep(projectId, "paso 4 (escenas)",
                    p -> p.getDesigns() != null,
                    "El proyecto aún no tiene diseños de personajes: ejecuta el paso 2 primero.");

            String speakerFirst = request == null ? null : request.speakerFirst();
            String speakerSecond = request == null ? null : request.speakerSecond();
            String language = request == null || request.language() == null || request.language().isBlank()
                    ? "Spanish"
                    : request.language().trim();
            int avgSeconds = request != null && request.averageSceneSeconds() != null
                    && request.averageSceneSeconds() > 0
                            ? request.averageSceneSeconds()
                            : 10;

            // Nº de escenas derivado de la duración; los proyectos legacy sin
            // duración caen al default de 5 (comportamiento anterior).
            Integer total = project.getVideoDurationSeconds();
            int sceneCount = total != null && total > 0
                    ? Math.max(2, Math.min(20, Math.round((float) total / avgSeconds)))
                    : 5;

            log.info("[wizard] Paso 4 (escenas) iniciado: «{}» — duración total {}, media {}s/escena → {} escenas, "
                            + "hablantes «{}»/«{}», idioma «{}»",
                    project.getTitle(),
                    total != null ? VideoStoryPromptFactory.formatDuration(total) : "(desconocida, legacy)",
                    avgSeconds, sceneCount,
                    speakerFirst == null ? "-" : speakerFirst,
                    speakerSecond == null ? "-" : speakerSecond,
                    language);

            VideoStoryTraceEvent trace = traceService.startStep(projectId, VideoStoryStep.SCENES,
                    sceneCount + " escenas ≈ " + avgSeconds + "s, idioma «" + language + "»");
            try {
                String prompt = VideoStoryPromptFactory.buildScenePrompt(
                        project.getStory(), project.getDesigns(), sceneCount, total,
                        speakerFirst, speakerSecond, language);
                String scenes = generate("paso 4 (escenas)", prompt, LONG_STEP_OUTPUT_TOKENS);

                project.setSceneAvgSeconds(avgSeconds);
                project.setSceneSpeaker1(speakerFirst);
                project.setSceneSpeaker2(speakerSecond);
                project.setSceneLanguage(language);
                project.setScenes(scenes);
                traceService.completeStep(trace, scenes.length() + " caracteres (" + sceneCount + " escenas)");
                log.info("[wizard] Escenas generadas para «{}»: {} caracteres ({} escenas ≈ {}s para {})",
                        project.getTitle(), scenes.length(), sceneCount, avgSeconds,
                        total != null ? VideoStoryPromptFactory.formatDuration(total) : "duración desconocida");
                return saveUpdated(project);
            } catch (Exception e) {
                traceService.failStep(trace, e);
                throw e;
            }
        } finally {
            releaseProject(projectId);
            MDC.clear();
        }
    }

    /**
     * PASO 5 (opcional): transporta UNA escena del paso 4 — AUTO-SELECCIONADA
     * por índice 1-based (sin input: la primera) — a la PLANTILLA MASTER de
     * 15 secciones de generación de video, embebiendo TODA la cadena
     * aprobada (historia, diseños, assets y escenas) en los slots de la
     * EXECUTION INSTRUCTION. La REFERENCIA OPCIONAL (contenido de archivo,
     * URL o término de búsqueda web) se resuelve aquí y viaja al prompt como
     * material de inspiración. Se puede repetir: cada ejecución sobrescribe
     * la plantilla anterior. CERO interacción humana obligatoria.
     */
    public VideoStoryProject generateVideoPrompt(UUID projectId, WizardVideoStepRequest request) {
        acquireProject(projectId);
        wizardMdc(projectId, "WIZARD_VIDEO");
        try {
            VideoStoryProject project = requireStep(projectId, "paso 5 (video)",
                    p -> p.getScenes() != null,
                    "El proyecto aún no tiene escenas: ejecuta el paso 4 primero.");

            // El estilo del paso 5 por defecto es el del paso 2 (misma línea visual).
            String animationStyle = request != null && request.animationStyle() != null
                    && !request.animationStyle().isBlank()
                            ? request.animationStyle().trim()
                            : project.getDesignStyle();

            // Escena objetivo AUTO-SELECCIONADA de las escenas generadas: sin
            // input, la primera; con índice 1-based, la elegida.
            List<VideoStoryPromptFactory.SceneBlock> sceneBlocks =
                    VideoStoryPromptFactory.parseScenes(project.getScenes());
            if (sceneBlocks.isEmpty()) {
                log.warn("[wizard] Paso 5 (video) rechazado: escenas sin cabeceras «SCENE NN» reconocibles");
                throw new IllegalArgumentException(
                        "No se pudo identificar ninguna escena «SCENE NN» en las escenas del proyecto: "
                                + "regenera el paso 4 y vuelve a intentarlo.");
            }
            int sceneIndex = request != null && request.sceneIndex() != null
                    && request.sceneIndex() > 0 ? request.sceneIndex() : 1;
            if (sceneIndex > sceneBlocks.size()) {
                log.warn("[wizard] Paso 5 (video) rechazado: índice {} sobre {} escenas",
                        sceneIndex, sceneBlocks.size());
                throw new IllegalArgumentException("El proyecto solo tiene " + sceneBlocks.size()
                        + " escena(s): elige un índice entre 1 y " + sceneBlocks.size() + ".");
            }
            VideoStoryPromptFactory.SceneBlock target = sceneBlocks.get(sceneIndex - 1);

            // Referencia opcional (archivo | URL | búsqueda web): si el
            // usuario la aportó y no se puede resolver, el paso falla con
            // un mensaje accionable — nunca se descarta en silencio.
            String reference = referenceService.resolveReference(
                    request == null ? null : request.referenceFileContent(),
                    request == null ? null : request.referenceUrl(),
                    request == null ? null : request.referenceSearch());

            log.info("[wizard] Paso 5 (video) iniciado: «{}» — escena {} de {} «{}», estilo «{}», "
                            + "referencia «{}»",
                    project.getTitle(), sceneIndex, sceneBlocks.size(), target.id(),
                    animationStyle == null ? "(del proyecto)" : animationStyle,
                    reference == null ? "ninguna" : "sí (" + reference.length() + " caracteres)");

            VideoStoryTraceEvent trace = traceService.startStep(projectId, VideoStoryStep.VIDEO,
                    "escena «" + target.id() + "» de " + sceneBlocks.size());
            try {
                String prompt = VideoStoryPromptFactory.buildVideoGenerationPrompt(
                        project.getTitle(), project.getStory(), project.getDesigns(),
                        project.getAssets(), project.getScenes(), target.id(), animationStyle,
                        project.getSceneLanguage(), project.getAssetsAspectRatio(),
                        project.getSceneAvgSeconds(), reference);
                String videoPrompt = generate("paso 5 (video)", prompt, LONG_STEP_OUTPUT_TOKENS);

                project.setVideoPrompt(videoPrompt);
                traceService.completeStep(trace,
                        videoPrompt.length() + " caracteres de plantilla MASTER (escena «" + target.id() + "»)");
                log.info("[wizard] Plantilla MASTER de video generada para «{}»: {} caracteres "
                                + "(escena «{}» de {})",
                        project.getTitle(), videoPrompt.length(), target.id(), sceneBlocks.size());
                return saveUpdated(project);
            } catch (Exception e) {
                traceService.failStep(trace, e);
                throw e;
            }
        } finally {
            releaseProject(projectId);
            MDC.clear();
        }
    }

    /**
     * STORYBOARD COMPLETO EN UN SOLO DOCUMENTO: ensambla todos los outputs
     * de la cadena (historia, diseños, assets, escenas y plantilla de
     * video) en un único markdown con secciones — solo las que tienen
     * contenido — listo para descargar.
     */
    public String buildStoryboardDocument(UUID projectId) {
        wizardMdc(projectId, "WIZARD_STORYBOARD");
        try {
            VideoStoryProject project = projectRepository.findById(projectId)
                    .orElseThrow(() -> {
                        log.warn("[wizard] Storyboard: proyecto {} no encontrado", projectId);
                        return new IllegalArgumentException("Proyecto " + projectId + " no encontrado.");
                    });

            StringBuilder doc = new StringBuilder();
            doc.append("# Historia en Video — ").append(project.getTitle()).append('\n');
            if (project.getVideoDurationSeconds() != null && project.getVideoDurationSeconds() > 0) {
                doc.append("\nDuración objetivo del video: ")
                        .append(VideoStoryPromptFactory.formatDuration(project.getVideoDurationSeconds()))
                        .append('\n');
            }
            int sections = appendSection(doc, "1. Historia", project.getStory());
            sections += appendSection(doc, "2. Diseño de Personajes", project.getDesigns());
            sections += appendSection(doc, "3. Biblioteca de Assets", project.getAssets());
            sections += appendSection(doc, "4. Escenas (Storyboard)", project.getScenes());
            sections += appendSection(doc, "5. Plantilla de Video (opcional)", project.getVideoPrompt());

            log.info("[wizard] Storyboard ensamblado para «{}»: {} caracteres, {} secciones con contenido",
                    project.getTitle(), doc.length(), sections);
            return doc.toString();
        } finally {
            MDC.clear();
        }
    }

    // ═══════════════════════ Internos ═══════════════════════

    /**
     * Toma el cerrojo del proyecto: si ya hay una generación en curso
     * (doble clic, recarga de página con la petición en vuelo…), falla
     * rápido en vez de quemar cuota con dos llamadas simultáneas.
     */
    private void acquireProject(UUID projectId) {
        if (!runningProjects.add(projectId)) {
            log.warn("[wizard] Generación rechazada: ya hay un paso en curso para el proyecto {}", projectId);
            throw new IllegalStateException(
                    "Ya hay una generación en curso para este proyecto: espera a que termine antes de lanzar otra.");
        }
    }

    private void releaseProject(UUID projectId) {
        runningProjects.remove(projectId);
    }

    /** Ids de proyectos con un paso en curso — el panel de procesos los marca Running. */
    public Set<UUID> getRunningProjectIds() {
        return Set.copyOf(runningProjects);
    }

    /**
     * Marca el MDC del wizard: traceId=projectId y step por fase, para que
     * TODO el evento (incluidas las líneas [llm] del NvidiaAiService en el
     * mismo hilo) quede correlacionado por proyecto en el visor/histórico.
     */
    private static void wizardMdc(UUID projectId, String step) {
        MDC.put("traceId", projectId.toString());
        MDC.put("step", step);
    }

    /**
     * Una llamada al LLM con logging exhaustivo y errores traducidos: el
     * truncamiento (finish_reason=length) significa que la respuesta no
     * cupo en el tope de salida — el paso falla con una guía accionable en
     * vez de guardar un output roto; el resto de fallos de la rotación se
     * envuelven para llegar al frontend como 502 con mensaje.
     */
    private String generate(String step, String prompt, int outputTokenLimit) {
        long start = System.currentTimeMillis();
        log.info("[wizard] {} — llamada a la IA iniciada (prompt de {} caracteres, tope de salida {} tokens)",
                step, prompt.length(), outputTokenLimit);
        log.debug("[wizard] {} — preview del prompt: {}", step, preview(prompt));

        String output;
        try {
            output = ai.callModelWithRotation(prompt, outputTokenLimit);
        } catch (NvidiaAiService.TranslationTruncatedException e) {
            log.error("[wizard] {} — la IA TRUNCÓ la respuesta tras {} ms: {}", step, elapsed(start), e.getMessage());
            throw new IllegalStateException(
                    "La respuesta de la IA se truncó por el límite de salida ("
                            + e.getMessage() + "). Reintenta (la IA puede ser más concisa en el segundo intento) "
                            + "o genera de nuevo reduciendo la duración del video o el detalle "
                            + "(menos escenas o más cortas).", e);
        } catch (Exception e) {
            log.error("[wizard] {} — la llamada a la IA FALLÓ tras {} ms: {}", step, elapsed(start),
                    e.getMessage(), e);
            throw new IllegalStateException("La generación con la IA falló en el " + step + ": "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
        }
        if (output == null || output.isBlank()) {
            log.error("[wizard] {} — la IA devolvió una respuesta VACÍA tras {} ms", step, elapsed(start));
            throw new IllegalStateException("La IA devolvió una respuesta vacía en el " + step
                    + ". Vuelve a intentarlo o revisa el estado del proveedor en el catálogo de modelos.");
        }
        log.info("[wizard] {} — respuesta de la IA: {} caracteres en {} ms", step, output.length(), elapsed(start));
        return output;
    }

    private VideoStoryProject requireStep(UUID projectId, String step,
            Predicate<VideoStoryProject> ready, String message) {
        VideoStoryProject project = projectRepository.findById(projectId)
                .orElseThrow(() -> {
                    log.warn("[wizard] {} — proyecto {} no encontrado", step, projectId);
                    return new IllegalArgumentException("Proyecto " + projectId + " no encontrado.");
                });
        if (!ready.test(project)) {
            log.warn("[wizard] {} — bloqueado por orden de la cadena: {}", step, message);
            throw new IllegalStateException(message);
        }
        return project;
    }

    private VideoStoryProject saveUpdated(VideoStoryProject project) {
        project.setUpdatedAt(Instant.now());
        return projectRepository.save(project);
    }

    /** Añade una sección al storyboard solo si tiene contenido; devuelve 1 si se añadió, 0 si no. */
    private static int appendSection(StringBuilder doc, String header, String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        doc.append("\n---\n\n## ").append(header).append("\n\n").append(content.strip()).append('\n');
        return 1;
    }

    private static long elapsed(long startMillis) {
        return System.currentTimeMillis() - startMillis;
    }

    /** Preview compacto de una línea (DEBUG): los prompts son enormes. */
    private static String preview(String text) {
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() <= 160 ? compact : compact.substring(0, 160) + "…";
    }
}
