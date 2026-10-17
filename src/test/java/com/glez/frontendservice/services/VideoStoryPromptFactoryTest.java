package com.glez.frontendservice.services;

import com.glez.frontendservice.dtos.StoryPromptRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de las plantillas 3 (SCENE PROMPT GENERATOR) y 4 (VIDEO GENERATION
 * TEMPLATE) de la timeline — las del encadenado del Asistente.
 */
@DisplayName("Video story factory: scene & video templates")
class VideoStoryPromptFactoryTest {

    private static final String STORY = "TITLE — The Last Recipe\n\nEmiko hides a family secret in her Kyoto bakery…";
    private static final String DESIGNS =
            "Character 1:\nEMIKO, 28, shy pastry chef, round face, black bob haircut…\n\nCharacter 2:\nDANIEL, 32, food critic…";
    private static final String SCENE_01 =
            "SCENE 01\n1) SCENE TYPE: Feature-film quality 3D animated romantic drama. Bakery, evening…";

    // ═══════════════ PASO 3: SCENE PROMPT GENERATOR ═══════════════

    @Test
    @DisplayName("Paso 4: antepone historia y diseños, deriva por duración y sustituye hablantes por posición")
    void buildScenePrompt_chainsStoryAndDesigns() {
        String prompt = VideoStoryPromptFactory.buildScenePrompt(
                STORY, DESIGNS, 12, 120, "Emiko", "Daniel", "Spanish");

        // La cadena: historia y diseños ANTES de la plantilla ("above" es literal).
        assertTrue(prompt.startsWith("Here is the complete story:\n\n"));
        assertTrue(prompt.contains(STORY));
        assertTrue(prompt.contains("Here are the established character designs:\n\n"));
        assertTrue(prompt.contains(DESIGNS));
        assertTrue(prompt.contains(DESIGNS + "\n\n---\n\nBased on the complete story and established character designs above,"));
        assertTrue(prompt.indexOf(DESIGNS) < prompt.indexOf("Based on the complete story"));
        // Sustituciones propias: nº de escenas derivado, DURACIÓN TOTAL, hablantes POR POSICIÓN, idioma.
        assertTrue(prompt.contains("divide the story into 12 cinematic 3D animated scenes."));
        assertTrue(prompt.contains("The total video duration is 2 minutes: distribute it across the scenes"));
        assertTrue(prompt.contains("ONLY Emiko speaks first."));
        assertTrue(prompt.contains("ONLY Daniel speaks second."));
        assertTrue(prompt.contains("Natural Spanish pronunciation."));
        assertFalse(prompt.contains("[CHARACTER]"), "Los dos [CHARACTER] deben quedar sustituidos");
        assertFalse(prompt.contains("[NUMBER OF SCENES]"));
        assertFalse(prompt.contains("[TOTAL VIDEO DURATION]"));
        // Las marcas que rellena la IA permanecen (verbatim de la plantilla).
        assertTrue(prompt.contains("[Complete prompt]"));
        assertTrue(prompt.contains("[DIALOGUE]"));
        assertTrue(prompt.endsWith("Continue until the entire story has been converted into scenes."));
    }

    @Test
    @DisplayName("Paso 4: sin hablantes el bloque STRICT desaparece; sin duración (legacy) el párrafo se omite")
    void buildScenePrompt_withoutSpeakers_dropsStrictBlock() {
        String prompt = VideoStoryPromptFactory.buildScenePrompt(
                STORY, DESIGNS, 0, null, null, "", "English");

        // Default de escenas cuando el número es inválido.
        assertTrue(prompt.contains("divide the story into 5 cinematic 3D animated scenes."));
        // Proyecto legacy sin duración: el párrafo de duración desaparece completo.
        assertFalse(prompt.contains("The total video duration is"));
        assertFalse(prompt.contains("[TOTAL VIDEO DURATION]"));
        // Sin hablantes: ni el encabezado STRICT ni sus líneas quedan.
        assertFalse(prompt.contains("STRICT CHARACTER DIALOGUE ASSIGNMENT:"));
        assertFalse(prompt.contains("speaks first."));
        assertFalse(prompt.contains("speaks second."));
        // El resto de la sección de diálogo queda intacto.
        assertTrue(prompt.contains("Natural English pronunciation."));
        assertTrue(prompt.contains("7) DIALOGUE"));
    }

    // ═══════════════ PASO 4: VIDEO GENERATION TEMPLATE ═══════════════

    @Test
    @DisplayName("Paso 4: escena antes de la plantilla + instrucción de rellenar; solo el estilo es nuestro")
    void buildVideoGenerationPrompt_wrapsSceneAndTemplate() {
        String prompt = VideoStoryPromptFactory.buildVideoGenerationPrompt(
                SCENE_01, "Premium polished 3D animation, Pixar and DreamWorks style");

        // La escena va ANTES de la instrucción de rellenar.
        assertTrue(prompt.startsWith("Here is the scene from the animated story:\n\n"));
        assertTrue(prompt.contains(SCENE_01));
        assertTrue(prompt.indexOf(SCENE_01) < prompt.indexOf("Fill in the following video generation template"));
        // La instrucción de rellenar precede a la plantilla.
        assertTrue(prompt.contains(
                "Output ONLY the filled template:\n\nCreate a cinematic 3D animated video based on the following scene."));
        // Solo [3D ANIMATION STYLE] lo sustituye la app.
        assertTrue(prompt.contains("STYLE:\nPremium polished 3D animation, Pixar and DreamWorks style"));
        assertFalse(prompt.contains("[3D ANIMATION STYLE]"));
        // El resto de placeholders los rellena la IA: permanecen verbatim.
        assertTrue(prompt.contains("[SCENE DESCRIPTION]"));
        assertTrue(prompt.contains("[EXACT CHARACTER DESCRIPTIONS]"));
        assertTrue(prompt.contains("[ACTION SEQUENCE]"));
        assertTrue(prompt.contains("[EXACT DIALOGUE]"));
        assertTrue(prompt.contains("[TIMING SPLIT]"));
        assertTrue(prompt.contains("[CAMERA MOVEMENT]"));
        assertTrue(prompt.contains("[LIGHTING]"));
        assertTrue(prompt.contains("[SETTING]"));
        assertTrue(prompt.contains("[AUDIO DESCRIPTION]"));
        assertTrue(prompt.endsWith("no logos, no watermark."));
    }

    // ═══════════════ Validación común del paso 1 ═══════════════

    @Test
    @DisplayName("isValidStoryRequest: false con nulos/campos clave vacíos, true con el payload completo")
    void isValidStoryRequest_validatesRequiredFields() {
        StoryPromptRequest full = new StoryPromptRequest(
                "T", "Romance", null, "Young adults", null, null, null, null,
                "Romantic", null, null, null, null, null, null, null, null,
                "conflicto", "sin resolver", "Spanish", 90, null, null, null, "TikTok");
        assertTrue(VideoStoryPromptFactory.isValidStoryRequest(full));

        StoryPromptRequest missingConflict = new StoryPromptRequest(
                "T", "Romance", null, "Young adults", null, null, null, null,
                "Romantic", null, null, null, null, null, null, null, null,
                " ", "sin resolver", "Spanish", 90, null, null, null, "TikTok");
        assertFalse(VideoStoryPromptFactory.isValidStoryRequest(missingConflict));

        StoryPromptRequest missingDuration = new StoryPromptRequest(
                "T", "Romance", null, "Young adults", null, null, null, null,
                "Romantic", null, null, null, null, null, null, null, null,
                "conflicto", "sin resolver", "Spanish", null, null, null, null, "TikTok");
        assertFalse(VideoStoryPromptFactory.isValidStoryRequest(missingDuration));

        assertFalse(VideoStoryPromptFactory.isValidStoryRequest(null));
    }

    // ═══════════════ PASO 1: STORY PROMPT (cobertura portada) ═══════════════

    /** Payload con TODOS los campos del formulario de historia. */
    private StoryPromptRequest fullStoryRequest() {
        return new StoryPromptRequest(
                "La pastelería de la esquina", "Romantic drama", "Slow burn office romance",
                "Young adults", "Japan", "A small bakery in Kyoto",
                "YES", "CINEMATIC 3D ANIMATED FEATURE", "Bittersweet", "STYLIZED",
                "Warm, nostalgic, hopeful",
                "Ambition, family expectations, a secret recipe, quiet jealousy",
                3,
                "A shy pastry chef hiding a family secret; endearing, observant, self-doubting.",
                "A pragmatic food critic with a guarded heart; witty, skeptical, secretly kind.",
                "The bakery owner: comic relief, protective, keeps everyone's secrets.",
                "Shocking revelation",
                "Whether to reveal the secret that could destroy the bakery or lose the person she loves",
                "Whether she keeps the bakery or follows her heart",
                "Spanish", 90, 20, 60, 20, "TikTok");
    }

    @Test
    @DisplayName("Paso 1: rellena todos los placeholders (los 2 tonos, géneros dobles, ritmo posicional)")
    void buildStoryPrompt_fullFields_fillsEveryPlaceholder() {
        String prompt = VideoStoryPromptFactory.buildStoryPrompt(fullStoryRequest());

        assertTrue(prompt.startsWith(
                "Write a short, emotionally engaging Romantic drama story for a Young adults audience"));
        assertFalse(prompt.contains("["), "Ningún placeholder debe quedar sin sustituir");
        // [GENRE] y [TARGET AUDIENCE] aparecen dos veces; el tono tiene DOS
        // placeholders distintos (primera línea + STORY STYLE).
        assertEquals(2, prompt.lines().filter(l -> l.contains("Romantic drama")).count());
        assertTrue(prompt.contains("- Tone: Bittersweet"));
        assertTrue(prompt.contains("- Family-friendly: YES"));
        assertTrue(prompt.contains("- Realism level: STYLIZED"));
        assertTrue(prompt.contains("- Subgenre: Slow burn office romance"));
        assertTrue(prompt.contains("Target length: a cinematic video of approximately 90 seconds."));
        assertTrue(prompt.contains("Use natural Spanish dialogue."));
        assertTrue(prompt.contains("- First 20% — Character introduction and setup"));
        assertTrue(prompt.contains("- Middle 60% — Relationship development and conflict"));
        assertTrue(prompt.contains("- Final 20% — Emotional reveal, climax, and cliffhanger"));
        assertTrue(prompt.contains("Create 3 important characters."));
        assertTrue(prompt.contains("A shy pastry chef hiding a family secret"));
        assertTrue(prompt.endsWith("suitable for TikTok."));
    }

    @Test
    @DisplayName("Paso 1: payload mínimo usa defaults y omite secciones opcionales completas")
    void buildStoryPrompt_minimalRequest_defaultsAndDrops() {
        StoryPromptRequest minimal = new StoryPromptRequest(
                "Historia mínima", "Comedy", null, "All ages", null, null,
                null, null, "Comedic", null, null, null, null,
                null, null, null, null,
                "Whether to admit they like each other", "The confession",
                "English", 60, null, null, null, "YouTube Shorts");
        String prompt = VideoStoryPromptFactory.buildStoryPrompt(minimal);

        assertFalse(prompt.contains("["));
        assertTrue(prompt.contains("Create 2 important characters."));
        assertTrue(prompt.contains("a strong CLIFFHANGER."));
        assertTrue(prompt.contains("- First 30% — Character introduction and setup"));
        assertFalse(prompt.contains("Subgenre"));
        assertFalse(prompt.contains("The story should include realistic situations related to:"));
        assertFalse(prompt.contains("MAIN CHARACTER REQUIREMENTS"));
        assertTrue(prompt.contains("STORY STRUCTURE:"));
        assertTrue(prompt.endsWith("suitable for YouTube Shorts."));
    }

    // ═══════════ PASO 2: CHARACTER DESIGN (cobertura portada) ═══════════

    @Test
    @DisplayName("Paso 2: incrusta la historia, sustituye el estilo y genera N bloques")
    void buildCharacterDesignPrompt_fullChain() {
        String prompt = VideoStoryPromptFactory.buildCharacterDesignPrompt(
                STORY, "Premium polished 3D animation, Pixar and DreamWorks style", 3);

        assertTrue(prompt.startsWith("Here is the story the character designs must be based on:\n\n"));
        assertTrue(prompt.contains(STORY + "\n\n---\n\nBased on the story above,"));
        assertFalse(prompt.contains("[3D ANIMATION STYLE]"));
        assertTrue(prompt.contains(
                "OUTPUT:\n\nCharacter 1:\n[SINGLE COMPLETE PROMPT]\n\n"
                        + "Character 2:\n[SINGLE COMPLETE PROMPT]\n\n"
                        + "Character 3:\n[SINGLE COMPLETE PROMPT]"));
        assertTrue(prompt.endsWith("[SINGLE COMPLETE PROMPT]"));
    }

    @Test
    @DisplayName("Paso 2: count inválido cae a 4 bloques (default de plantilla)")
    void buildCharacterDesignPrompt_invalidCount_defaultsToFour() {
        String prompt = VideoStoryPromptFactory.buildCharacterDesignPrompt(STORY, "Realistic cinematic 3D animation", 0);
        assertEquals(4, prompt.split("\\[SINGLE COMPLETE PROMPT\\]", -1).length - 1);
    }

    // ═══════════════ Helpers compartidos: duración ═══════════════

    @Test
    @DisplayName("formatDuration: segundos y minutos legibles para las plantillas")
    void formatDuration_minutesAndSeconds() {
        assertEquals("45 seconds", VideoStoryPromptFactory.formatDuration(45));
        assertEquals("90 seconds", VideoStoryPromptFactory.formatDuration(90));
        assertEquals("1 minute", VideoStoryPromptFactory.formatDuration(60));
        assertEquals("2 minutes", VideoStoryPromptFactory.formatDuration(120));
        assertEquals("5 minutes", VideoStoryPromptFactory.formatDuration(300));
    }

    // ═══════════ PASO 3: ASSETS DESIGN (plantilla MASTER) ═══════════

    @Test
    @DisplayName("Paso 3: sustituye IN-PLACE historia y diseños en las ranuras A/B de INPUT MATERIAL")
    void buildAssetsPrompt_fillsInputSlotsInPlace() {
        String prompt = VideoStoryPromptFactory.buildAssetsPrompt(
                STORY, DESIGNS, "Realistic cinematic 3D animation",
                "Young adults", "9:16", "Teal and warm amber");

        // La historia cae EXACTAMENTE bajo su ranura de la sección A.
        assertTrue(prompt.contains("### A. Complete Story\n" + STORY));
        // Los diseños aprobados bajo su ranura de la sección B.
        assertTrue(prompt.contains("### B. Approved Character Designs\n" + DESIGNS));
        // La sección C con los cuatro campos sustituidos.
        assertTrue(prompt.contains("- Animation style: Realistic cinematic 3D animation"));
        assertTrue(prompt.contains("- Target audience: Young adults"));
        assertTrue(prompt.contains("- Aspect ratio: 9:16"));
        assertTrue(prompt.contains("- Color palette: Teal and warm amber"));
        // Ningún marcador [INSERT…] sobrevive.
        assertFalse(prompt.contains("[INSERT"));
        assertFalse(prompt.contains("[16:9 / 9:16 / 1:1]"));
    }

    @Test
    @DisplayName("Paso 3: campos vacíos caen a la regla de derivación que la propia plantilla define")
    void buildAssetsPrompt_blankOptionals_useTemplateFallbacks() {
        String prompt = VideoStoryPromptFactory.buildAssetsPrompt(
                STORY, DESIGNS, null, null, null, null);

        assertTrue(prompt.contains(
                "- Animation style: Not specified — derive it from the established character designs"));
        assertTrue(prompt.contains(
                "- Target audience: Not specified — derive a suitable choice from the story"));
        assertTrue(prompt.contains("- Aspect ratio: 16:9"));
        assertTrue(prompt.contains(
                "- Color palette: Derive it from the story and maintain it consistently"));
        assertFalse(prompt.contains("[INSERT"));
    }

    @Test
    @DisplayName("Paso 3: la plantilla MASTER conserva su estructura verbatim")
    void buildAssetsPrompt_keepsMasterTemplateVerbatim() {
        String prompt = VideoStoryPromptFactory.buildAssetsPrompt(
                STORY, DESIGNS, "Soft stylized 3D animation", null, "16:9", null);

        assertTrue(prompt.startsWith("# MASTER PROMPT — ASSETS DESIGN"));
        assertTrue(prompt.contains("## 1. INPUT MATERIAL"));
        assertTrue(prompt.contains("## 12. FINAL QUALITY CONTROL"));
        assertTrue(prompt.contains("## EXECUTION INSTRUCTION"));
        assertTrue(prompt.contains("**Story Prompt → Character Design → Assets Design → Scene Generator → Video Generation**"));
        // La meta-instrucción del usuario (sequential-thinking) NO es parte
        // de la plantilla y no debe colarse en el prompt.
        assertFalse(prompt.toLowerCase().contains("sequential-thinking"));
        assertTrue(prompt.endsWith("copy-ready generation prompts."));
    }
}
