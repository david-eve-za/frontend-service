package com.glez.frontendservice.services;

import com.glez.frontendservice.dtos.WizardAssetsStepRequest;
import com.glez.frontendservice.dtos.WizardDesignStepRequest;
import com.glez.frontendservice.model.VideoStoryProject;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests del ASISTENTE de Historias en Video
 * ({@link VideoStoryWizardService}): el encadenado de pasos con la IA
 * mockeada — gating entre pasos, persistencia del output y traducción del
 * truncamiento a un error accionable.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Video Story Wizard service tests")
class VideoStoryWizardServiceTest {

    @Mock
    private NvidiaAiService ai;

    @Mock
    private VideoStoryProjectRepository projectRepository;

    private VideoStoryWizardService wizard;

    private final UUID projectId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        wizard = new VideoStoryWizardService(ai, projectRepository);
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
}
