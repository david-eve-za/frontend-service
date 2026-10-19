package com.glez.frontendservice.services;

import com.glez.frontendservice.dtos.WizardAssetsStepRequest;
import com.glez.frontendservice.dtos.WizardDesignStepRequest;
import com.glez.frontendservice.dtos.WizardVideoStepRequest;
import com.glez.frontendservice.model.VideoStoryProject;
import com.glez.frontendservice.model.VideoStoryStep;
import com.glez.frontendservice.repository.VideoStoryProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests del ASISTENTE de Historias en Video
 * ({@link VideoStoryWizardService}): el encadenado de pasos con la IA
 * mockeada — gating entre pasos, auto-selección de escena y referencia
 * opcional del paso 5, persistencia del output y traducción del
 * truncamiento a un error accionable.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Video Story Wizard service tests")
class VideoStoryWizardServiceTest {

    @Mock
    private NvidiaAiService ai;

    @Mock
    private VideoStoryProjectRepository projectRepository;

    @Mock
    private WizardReferenceService referenceService;

    @Mock
    private VideoStoryTraceService traceService;

    private VideoStoryWizardService wizard;

    private final UUID projectId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        wizard = new VideoStoryWizardService(ai, projectRepository, referenceService, traceService);
    }

    /** Proyecto con historia y diseños completos (listo para el paso 3). */
    private VideoStoryProject projectWithDesigns() {
        VideoStoryProject project = new VideoStoryProject();
        project.setId(projectId);
        project.setTitle("Viaje hacia el futuro");
        project.setStory("La historia completa generada en el paso 1…");
        project.setDesignStyle("Realistic cinematic 3D animation");
        project.setDesignCharacterCount(5);
        project.setDesigns("Character 1: MAYA… Character 5: ROJAS…");
        return project;
    }

    @Test
    @DisplayName("Paso 3 (assets): genera con la IA sobre historia+diseños y persiste el output")
    void generateAssets_happyPath_generatesAndPersists() {
        VideoStoryProject project = projectWithDesigns();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(ai.callModelWithRotation(anyString(), anyInt())).thenReturn("PART 1 — ASSET INVENTORY…");
        when(projectRepository.save(any(VideoStoryProject.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        VideoStoryProject result = wizard.generateAssets(projectId, new WizardAssetsStepRequest(
                null, "Young adults", "9:16", null));

        // La IA recibió la plantilla con historia, diseños y estilo heredado del paso 2.
        verify(ai).callModelWithRotation(org.mockito.ArgumentMatchers.argThat(prompt ->
                        prompt.contains("# MASTER PROMPT — ASSETS DESIGN")
                                && prompt.contains("### A. Complete Story\n" + project.getStory())
                                && prompt.contains("### B. Approved Character Designs\n" + project.getDesigns())
                                && prompt.contains("- Animation style: Realistic cinematic 3D animation")
                                && prompt.contains("- Aspect ratio: 9:16")),
                org.mockito.ArgumentMatchers.eq(VideoStoryWizardService.LONG_STEP_OUTPUT_TOKENS));
        // El output quedó persistido junto con el aspecto elegido.
        assertEquals("PART 1 — ASSET INVENTORY…", result.getAssets());
        assertEquals("9:16", result.getAssetsAspectRatio());
        verify(projectRepository).save(project);
    }

    @Test
    @DisplayName("Paso 3 (assets): sin diseños previos falla con 409 accionable (ejecuta el paso 2)")
    void generateAssets_withoutDesigns_requiresStep2() {
        VideoStoryProject project = projectWithDesigns();
        project.setDesigns(null);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> wizard.generateAssets(projectId, new WizardAssetsStepRequest(
                        null, null, null, null)));
        assertTrue(e.getMessage().contains("ejecuta el paso 2"),
                "El mensaje debe guiar al usuario al paso previo: " + e.getMessage());
    }

    @Test
    @DisplayName("Un truncamiento de la IA se traduce a guía accionable, nunca se persiste output roto")
    void generate_truncation_translatesToActionableError() {
        VideoStoryProject project = projectWithDesigns();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(ai.callModelWithRotation(anyString(), anyInt()))
                .thenThrow(new NvidiaAiService.TranslationTruncatedException(
                        "Response truncated by the model output token limit"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> wizard.generateAssets(projectId, new WizardAssetsStepRequest(
                        null, null, null, null)));
        assertTrue(e.getMessage().contains("se truncó"), e.getMessage());
        assertTrue(e.getMessage().contains("reduciendo"), e.getMessage());
        assertEquals(null, project.getAssets(),
                "El proyecto NO queda con un output truncado persistido");
    }

    @Test
    @DisplayName("Una respuesta vacía de la IA también falla el paso sin persistir nada")
    void generate_emptyResponse_failsStep() {
        VideoStoryProject project = projectWithDesigns();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(ai.callModelWithRotation(anyString(), anyInt())).thenReturn("   ");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> wizard.generateDesigns(projectId, new WizardDesignStepRequest(
                        "Realistic cinematic 3D animation", 4)));
        assertTrue(e.getMessage().contains("vacía"), e.getMessage());
    }

    @Test
    @DisplayName("Paso 4 (escenas): el nº de escenas se DERIVA de la duración total del video")
    void generateScenes_derivesSceneCountFromDuration() {
        VideoStoryProject project = projectWithDesigns();
        project.setVideoDurationSeconds(120);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(ai.callModelWithRotation(anyString(), anyInt())).thenReturn("SCENE 01…");
        when(projectRepository.save(any(VideoStoryProject.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        VideoStoryProject result = wizard.generateScenes(projectId,
                new com.glez.frontendservice.dtos.WizardScenesStepRequest(
                        10, "Maya", "Leo", "Spanish"));

        // 120 s / 10 s por escena = 12 escenas; el prompt lleva el nº derivado
        // y la duración total que deben cubrir los TIMING SPLIT.
        verify(ai).callModelWithRotation(org.mockito.ArgumentMatchers.argThat(prompt ->
                        prompt.contains("divide the story into 12 cinematic 3D animated scenes.")
                                && prompt.contains("The total video duration is 2 minutes")),
                org.mockito.ArgumentMatchers.eq(VideoStoryWizardService.LONG_STEP_OUTPUT_TOKENS));
        assertEquals(10, result.getSceneAvgSeconds());
        assertEquals("SCENE 01…", result.getScenes());
    }

    @Test
    @DisplayName("Paso 4 (escenas): proyecto legacy sin duración cae a 5 escenas sin párrafo de duración")
    void generateScenes_legacyProjectWithoutDuration_fallsBackToFive() {
        VideoStoryProject project = projectWithDesigns();
        // Sin videoDurationSeconds: creado antes del cambio a duración.
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(ai.callModelWithRotation(anyString(), anyInt())).thenReturn("SCENE 01…");
        when(projectRepository.save(any(VideoStoryProject.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        wizard.generateScenes(projectId,
                new com.glez.frontendservice.dtos.WizardScenesStepRequest(
                        10, null, null, null));

        verify(ai).callModelWithRotation(org.mockito.ArgumentMatchers.argThat(prompt ->
                        prompt.contains("divide the story into 5 cinematic 3D animated scenes.")
                                && !prompt.contains("The total video duration is")),
                org.mockito.ArgumentMatchers.eq(VideoStoryWizardService.LONG_STEP_OUTPUT_TOKENS));
    }

    @Test
    @DisplayName("Storyboard: ensambla TODOS los outputs en un único markdown con secciones")
    void buildStoryboardDocument_assemblesAllOutputs() {
        VideoStoryProject project = projectWithDesigns();
        project.setVideoDurationSeconds(90);
        project.setAssets("PART 1 — ASSET INVENTORY…");
        project.setScenes("SCENE 01… SCENE 12…");
        project.setVideoPrompt("Create a cinematic 3D animated video…");
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        String doc = wizard.buildStoryboardDocument(projectId);

        assertTrue(doc.startsWith("# Historia en Video — Viaje hacia el futuro"));
        assertTrue(doc.contains("Duración objetivo del video: 90 seconds"));
        assertTrue(doc.contains("## 1. Historia"));
        assertTrue(doc.contains("La historia completa generada en el paso 1…"));
        assertTrue(doc.contains("## 2. Diseño de Personajes"));
        assertTrue(doc.contains("Character 1: MAYA"));
        assertTrue(doc.contains("## 3. Biblioteca de Assets"));
        assertTrue(doc.contains("## 4. Escenas (Storyboard)"));
        assertTrue(doc.contains("## 5. Plantilla de Video (opcional)"));
    }

    @Test
    @DisplayName("Storyboard: solo incluye las secciones con contenido (proyecto parcial)")
    void buildStoryboardDocument_partialProject_skipsEmptySections() {
        VideoStoryProject project = projectWithDesigns();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        String doc = wizard.buildStoryboardDocument(projectId);

        assertTrue(doc.contains("## 1. Historia"));
        assertTrue(doc.contains("## 2. Diseño de Personajes"));
        assertFalse(doc.contains("## 3. Biblioteca de Assets"));
        assertFalse(doc.contains("## 4. Escenas"));
        assertFalse(doc.contains("## 5. Plantilla de Video"));
    }

    @Test
    @DisplayName("Cerrojo: una segunda generación para el MISMO proyecto mientras corre otra falla con 409")
    void generateAssets_secondConcurrentCallForSameProject_isRejected() {
        VideoStoryProject project = projectWithDesigns();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(projectRepository.save(any(VideoStoryProject.class))).thenAnswer(inv -> inv.getArgument(0));
        // Mientras la primera generación está "en curso" (dentro del mock),
        // una segunda llamada anidada al mismo proyecto debe ser rechazada.
        when(ai.callModelWithRotation(anyString(), anyInt())).thenAnswer(inv -> {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> wizard.generateAssets(projectId,
                            new WizardAssetsStepRequest(null, null, null, null)));
            assertTrue(e.getMessage().contains("generación en curso"), e.getMessage());
            return "PART 1 — ASSET INVENTORY…";
        });

        VideoStoryProject result = wizard.generateAssets(projectId,
                new WizardAssetsStepRequest(null, null, null, null));

        assertEquals("PART 1 — ASSET INVENTORY…", result.getAssets());
    }

    @Test
    @DisplayName("Storyboard: proyecto inexistente falla con error claro")
    void buildStoryboardDocument_unknownProject_fails() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> wizard.buildStoryboardDocument(projectId));
        assertTrue(e.getMessage().contains("no encontrado"));
    }

    // ═══════════════ PASO 5: PLANTILLA MASTER DE VIDEO ═══════════════

    /** Proyecto con la cadena COMPLETA: historia, diseños, assets y escenas. */
    private VideoStoryProject projectWithScenes() {
        VideoStoryProject project = projectWithDesigns();
        project.setAssets("PART 1 — ASSET INVENTORY… ENV-001 — Bakery interior…");
        project.setAssetsAspectRatio("9:16");
        project.setSceneAvgSeconds(12);
        project.setSceneLanguage("Spanish");
        project.setScenes("SCENE 01\nBakery, evening. Emiko kneads dough.\n\n"
                + "SCENE 02\nRooftop, night. Daniel arrives.");
        return project;
    }

    /** Petición del paso 5 SIN ningún campo: la interacción humana cero. */
    private WizardVideoStepRequest emptyVideoRequest() {
        return new WizardVideoStepRequest(null, null, null, null, null);
    }

    @Test
    @DisplayName("Paso 5 (video): SIN input se auto-selecciona la PRIMERA escena y se genera la plantilla MASTER")
    void generateVideoPrompt_zeroInput_autoSelectsFirstScene() {
        VideoStoryProject project = projectWithScenes();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(referenceService.resolveReference(isNull(), isNull(), isNull())).thenReturn(null);
        when(ai.callModelWithRotation(anyString(), anyInt()))
                .thenReturn("# MASTER PROMPT — CINEMATIC 3D ANIMATED VIDEO GENERATION…");
        when(projectRepository.save(any(VideoStoryProject.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        VideoStoryProject result = wizard.generateVideoPrompt(projectId, emptyVideoRequest());

        // La IA recibe la plantilla MASTER con TODA la cadena embebida y la
        // escena objetivo AUTO-SELECCIONADA (la primera), con el tope ampliado.
        verify(ai).callModelWithRotation(org.mockito.ArgumentMatchers.argThat(prompt ->
                        prompt.contains("# MASTER PROMPT — CINEMATIC 3D ANIMATED VIDEO GENERATION")
                                && prompt.contains("Using `" + project.getStory() + "`")
                                && prompt.contains("`" + project.getAssets() + "`")
                                && prompt.contains("and `" + project.getScenes() + "`")
                                && prompt.contains("generate the video-generation prompt for `SCENE 01`.")
                                && prompt.contains("- **Story Title:** `Viaje hacia el futuro`")
                                && prompt.contains("- **Aspect Ratio:** `9:16`")
                                && prompt.contains("- **Default Clip Duration:** `12 seconds`")
                                && prompt.contains("- **Dialogue Language:** `Spanish`")
                                && !prompt.contains("REFERENCE MATERIAL")),
                eq(VideoStoryWizardService.LONG_STEP_OUTPUT_TOKENS));
        verify(referenceService).resolveReference(isNull(), isNull(), isNull());
        assertEquals("# MASTER PROMPT — CINEMATIC 3D ANIMATED VIDEO GENERATION…",
                result.getVideoPrompt());
        verify(projectRepository).save(project);
    }

    @Test
    @DisplayName("Paso 5 (video): el índice 1-based elige la escena objetivo")
    void generateVideoPrompt_sceneIndex_selectsTargetScene() {
        VideoStoryProject project = projectWithScenes();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(referenceService.resolveReference(isNull(), isNull(), isNull())).thenReturn(null);
        when(ai.callModelWithRotation(anyString(), anyInt())).thenReturn("Plantilla…");
        when(projectRepository.save(any(VideoStoryProject.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        wizard.generateVideoPrompt(projectId,
                new WizardVideoStepRequest(2, null, null, null, null));

        verify(ai).callModelWithRotation(org.mockito.ArgumentMatchers.argThat(prompt ->
                        prompt.contains("generate the video-generation prompt for `SCENE 02`.")),
                anyInt());
    }

    @Test
    @DisplayName("Paso 5 (video): índice fuera de rango falla con mensaje accionable, sin llamar a la IA")
    void generateVideoPrompt_outOfRange_failsWithoutAiCall() {
        VideoStoryProject project = projectWithScenes();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> wizard.generateVideoPrompt(projectId,
                        new WizardVideoStepRequest(3, null, null, null, null)));
        assertTrue(e.getMessage().contains("solo tiene 2 escena"), e.getMessage());
        assertTrue(e.getMessage().contains("entre 1 y 2"), e.getMessage());
        verify(ai, org.mockito.Mockito.never()).callModelWithRotation(anyString(), anyInt());
    }

    @Test
    @DisplayName("Paso 5 (video): escenas sin cabeceras «SCENE NN» falla guiando al paso 4")
    void generateVideoPrompt_unparseableScenes_failsWithGuide() {
        VideoStoryProject project = projectWithDesigns();
        project.setScenes("Una sola escena sin cabeceras reconocibles.");
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> wizard.generateVideoPrompt(projectId, emptyVideoRequest()));
        assertTrue(e.getMessage().contains("SCENE NN"), e.getMessage());
        assertTrue(e.getMessage().contains("regenera el paso 4"), e.getMessage());
        verify(ai, org.mockito.Mockito.never()).callModelWithRotation(anyString(), anyInt());
    }

    @Test
    @DisplayName("Paso 5 (video): la referencia resuelta viaja al prompt como material de inspiración")
    void generateVideoPrompt_withReference_embedsReferenceBlock() {
        VideoStoryProject project = projectWithScenes();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(referenceService.resolveReference(isNull(), isNull(),
                eq("panaderías tradicionales de Kioto")))
                .thenReturn("Resultados de búsqueda: panaderías de Kioto…");
        when(ai.callModelWithRotation(anyString(), anyInt())).thenReturn("Plantilla…");
        when(projectRepository.save(any(VideoStoryProject.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        wizard.generateVideoPrompt(projectId, new WizardVideoStepRequest(
                null, null, null, null, "panaderías tradicionales de Kioto"));

        verify(ai).callModelWithRotation(org.mockito.ArgumentMatchers.argThat(prompt ->
                        prompt.startsWith("Here is the optional REFERENCE MATERIAL provided by the user.")
                                && prompt.contains("Resultados de búsqueda: panaderías de Kioto…")),
                anyInt());
    }

    @Test
    @DisplayName("Paso 5 (video): sin escenas previas falla con 409 accionable (ejecuta el paso 4)")
    void generateVideoPrompt_withoutScenes_requiresStep4() {
        VideoStoryProject project = projectWithDesigns();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> wizard.generateVideoPrompt(projectId, emptyVideoRequest()));
        assertTrue(e.getMessage().contains("ejecuta el paso 4"), e.getMessage());
    }

    @Test
    @DisplayName("Paso 5 (video): el estilo vacío hereda el del paso 2 y el output queda persistido")
    void generateVideoPrompt_blankStyle_inheritsDesignStyle() {
        VideoStoryProject project = projectWithScenes();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(referenceService.resolveReference(isNull(), isNull(), isNull())).thenReturn(null);
        when(ai.callModelWithRotation(anyString(), anyInt())).thenReturn("Plantilla MASTER completa…");
        when(projectRepository.save(any(VideoStoryProject.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        VideoStoryProject result = wizard.generateVideoPrompt(projectId, emptyVideoRequest());

        verify(ai).callModelWithRotation(org.mockito.ArgumentMatchers.argThat(prompt ->
                        prompt.contains("- **Visual Style:** `Realistic cinematic 3D animation`")),
                anyInt());
        assertEquals("Plantilla MASTER completa…", result.getVideoPrompt());
        assertFalse(result.getScenes().isBlank(), "Las escenas siguen presentes tras el paso 5");
    }

    @Test
    @DisplayName("Paso 5 (video): el intento queda trazado en la cronología (STARTED → COMPLETED)")
    void generateVideoPrompt_recordsTrace_startedThenCompleted() {
        VideoStoryProject project = projectWithScenes();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(referenceService.resolveReference(isNull(), isNull(), isNull())).thenReturn(null);
        when(ai.callModelWithRotation(anyString(), anyInt())).thenReturn("Plantilla MASTER…");
        when(projectRepository.save(any(VideoStoryProject.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        wizard.generateVideoPrompt(projectId, emptyVideoRequest());

        // El intento se abre con el paso correcto y detalles de la escena,
        // y cierra COMPLETED con el tamaño del output.
        verify(traceService).startStep(eq(projectId), eq(VideoStoryStep.VIDEO), contains("SCENE 01"));
        verify(traceService).completeStep(any(), contains("caracteres de plantilla MASTER"));
        verify(traceService, org.mockito.Mockito.never()).failStep(any(), any());
    }

    @Test
    @DisplayName("Paso 5 (video): un fallo de la IA queda trazado como FAILED y se propaga")
    void generateVideoPrompt_aiFailure_recordsTraceFailed() {
        VideoStoryProject project = projectWithScenes();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(referenceService.resolveReference(isNull(), isNull(), isNull())).thenReturn(null);
        when(ai.callModelWithRotation(anyString(), anyInt()))
                .thenThrow(new IllegalStateException("La generación con la IA falló en el paso 5 (video)"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> wizard.generateVideoPrompt(projectId, emptyVideoRequest()));

        verify(traceService).startStep(eq(projectId), eq(VideoStoryStep.VIDEO), anyString());
        verify(traceService).failStep(any(), eq(e));
        verify(traceService, org.mockito.Mockito.never()).completeStep(any(), anyString());
        assertFalse(e.getMessage().isEmpty());
    }
}
