package com.glez.frontendservice.services;

import com.glez.frontendservice.dtos.StoryPromptRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de las plantillas del Asistente de Historias en Video: SCENE
 * PROMPT GENERATOR (paso 4), VIDEO GENERATION MASTER PROMPT (paso 5, 15
 * secciones) y el parser de escenas que alimenta la auto-selección.
 */
@DisplayName("Video story factory: scene, master video templates & scene parser")
class VideoStoryPromptFactoryTest {

    private static final String STORY = "TITLE — The Last Recipe\n\nEmiko hides a family secret in her Kyoto bakery…";
    private static final String DESIGNS =
            "Character 1:\nEMIKO, 28, shy pastry chef, round face, black bob haircut…\n\nCharacter 2:\nDANIEL, 32, food critic…";
    private static final String ASSETS = "PART 1 — ASSET INVENTORY\n\n| Asset ID | Asset Name | …\nENV-001 — Bakery interior…";

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

    // ═════ PASO 5: VIDEO GENERATION MASTER PROMPT (15 secciones) ═════

    @Test
    @DisplayName("Paso 5: plantilla MASTER verbatim — slots de la app sustituidos, slots de la IA intactos")
    void buildVideoGenerationPrompt_fillsAppSlots_keepsAiSlotsVerbatim() {
        String prompt = VideoStoryPromptFactory.buildVideoGenerationPrompt(
                "La pastelería de la esquina", STORY, DESIGNS, ASSETS,
                "SCENE 01\nBakery, evening…\n\nSCENE 02\nRooftop, night…", "SCENE 02",
                "Soft stylized 3D animation", "Spanish", "9:16", 12, null);

        // El esqueleto de la plantilla viaja COMPLETO y verbatim: las 15
        // secciones, la EXECUTION INSTRUCTION y la instrucción de rellenado.
        assertTrue(prompt.contains("Fill in the following MASTER PROMPT template using ONLY the materials"));
        assertTrue(prompt.contains("# MASTER PROMPT — CINEMATIC 3D ANIMATED VIDEO GENERATION"));
        for (int section = 1; section <= 15; section++) {
            assertTrue(prompt.contains("## " + section + "."), "Falta la sección " + section);
        }
        assertTrue(prompt.contains("## 1. PROJECT INFORMATION"));
        assertTrue(prompt.contains("## 7. TIMING BREAKDOWN"));
        assertTrue(prompt.contains("## 11. NEGATIVE PROMPT"));
        assertTrue(prompt.contains("## 15. FINAL VALIDATION"));
        assertTrue(prompt.contains("## EXECUTION INSTRUCTION"));
        // Slots que la APP posee: sustituidos con los valores del proyecto.
        assertTrue(prompt.contains("- **Story Title:** `La pastelería de la esquina`"));
        assertTrue(prompt.contains("- **Visual Style:** `Soft stylized 3D animation`"));
        assertTrue(prompt.contains("- **Dialogue Language:** `Spanish`"));
        assertTrue(prompt.contains("- **Aspect Ratio:** `9:16`"));
        assertTrue(prompt.contains("- **Default Clip Duration:** `12 seconds`"));
        assertTrue(prompt.contains("- **Audio Generation:** `Yes`"));
        // Los CUATRO materiales aprobados caen IN-PLACE en la EXECUTION
        // INSTRUCTION (patrón de la plantilla MASTER de assets).
        assertTrue(prompt.contains("Using `" + STORY + "`"));
        assertTrue(prompt.contains("`" + DESIGNS + "`"));
        assertTrue(prompt.contains("`" + ASSETS + "`"));
        assertTrue(prompt.contains("and `SCENE 01\nBakery, evening…\n\nSCENE 02\nRooftop, night…`"));
        assertTrue(prompt.contains("generate the video-generation prompt for `SCENE 02`."));
        // Slots que rellena la IA: permanecen verbatim para que el output
        // replique la estructura EXACTA de la plantilla.
        assertTrue(prompt.contains("`{{GENRE}}`"));
        assertTrue(prompt.contains("`{{STORY_SUMMARY}}`"));
        assertTrue(prompt.contains("`{{TARGET_AUDIENCE}}`"));
        assertTrue(prompt.contains("`{{TARGET_VIDEO_PLATFORM}}`"));
        assertTrue(prompt.contains("`{{GENERATION_MODE}}`"));
        assertTrue(prompt.contains("`{{SCENE_VISUAL_STYLE}}`"));
        assertTrue(prompt.contains("`{{SEGMENT_1_START}}`"));
        assertTrue(prompt.contains("`{{DIALOGUE_ID}}`"));
        assertTrue(prompt.contains("`{{CUSTOM_NEGATIVE_PROMPT}}`"));
        assertTrue(prompt.contains("`{{DELIVERABLE_VIDEO_PROMPT}}`"));
        assertTrue(prompt.contains("`{{VALIDATE_STORY_CONTENT}}`"));
        assertTrue(prompt.contains("`{{CHARACTER_ID}}`"));
        assertTrue(prompt.contains("`{{ASSET_ID}}`"));
        // La meta-instrucción de repetición de bloques acompaña a la plantilla.
        assertTrue(prompt.contains("Repeat the CHARACTER and ASSET reference blocks"));
        assertTrue(prompt.contains("do NOT include the EXECUTION"));
        // Sin referencia: el bloque de material de referencia NO aparece.
        assertFalse(prompt.contains("REFERENCE MATERIAL"));
    }

    @Test
    @DisplayName("Paso 5: la referencia opcional se antepone como inspiración, antes de la plantilla")
    void buildVideoGenerationPrompt_withReference_prependsReferenceBlock() {
        String prompt = VideoStoryPromptFactory.buildVideoGenerationPrompt(
                "T", STORY, DESIGNS, ASSETS, "SCENE 01\n…", "SCENE 01",
                null, null, null, null, "Página web de referencia: panaderías tradicionales de Kioto…");

        assertTrue(prompt.startsWith("Here is the optional REFERENCE MATERIAL provided by the user."));
        assertTrue(prompt.contains("never contradict the approved story"));
        assertTrue(prompt.contains("never copy copyrighted text verbatim"));
        assertTrue(prompt.contains("Página web de referencia: panaderías tradicionales de Kioto…"));
        assertTrue(prompt.indexOf("Página web de referencia")
                < prompt.indexOf("# MASTER PROMPT — CINEMATIC 3D ANIMATED VIDEO GENERATION"));
    }

    @Test
    @DisplayName("Paso 5: sin biblioteca de assets el slot recibe la nota que guía a la IA")
    void buildVideoGenerationPrompt_withoutAssets_fallsBackToNote() {
        String prompt = VideoStoryPromptFactory.buildVideoGenerationPrompt(
                "T", STORY, DESIGNS, null, "SCENE 01\n…", "SCENE 01",
                null, null, null, null, null);

        assertTrue(prompt.contains("No approved asset library was generated"));
        assertFalse(prompt.contains("{{APPROVED_ASSET_LIBRARY}}"));
    }

    @Test
    @DisplayName("Paso 5: campos vacíos caen a los defaults del wizard (16:9, español, 10 s, SCENE 01)")
    void buildVideoGenerationPrompt_blankOptionals_useWizardDefaults() {
        String prompt = VideoStoryPromptFactory.buildVideoGenerationPrompt(
                null, STORY, DESIGNS, ASSETS, "SCENE 01\n…", null,
                null, null, null, null, null);

        assertTrue(prompt.contains("- **Story Title:** `Untitled story`"));
        assertTrue(prompt.contains(
                "- **Visual Style:** `Not specified — derive it from the approved character designs`"));
        assertTrue(prompt.contains("- **Dialogue Language:** `Spanish`"));
        assertTrue(prompt.contains("- **Aspect Ratio:** `16:9`"));
        assertTrue(prompt.contains("- **Default Clip Duration:** `10 seconds`"));
        assertTrue(prompt.contains("for `SCENE 01`."));
        assertFalse(prompt.contains("{{STORY_TITLE}}"));
        assertFalse(prompt.contains("{{VISUAL_STYLE}}"));
        assertFalse(prompt.contains("{{DIALOGUE_LANGUAGE}}"));
        assertFalse(prompt.contains("{{ASPECT_RATIO}}"));
        assertFalse(prompt.contains("{{OUTPUT_ASPECT_RATIO}}"));
        assertFalse(prompt.contains("{{DEFAULT_CLIP_DURATION}}"));
        assertFalse(prompt.contains("{{TARGET_SCENE_ID}}"));
        assertFalse(prompt.contains("{{APPROVED_STORY}}"));
        assertFalse(prompt.contains("{{APPROVED_SCENE_BREAKDOWN}}"));
    }

    // ═══════════════ Parser de escenas (paso 4 → paso 5) ═══════════════

    @Test
    @DisplayName("parseScenes: trocea el output del paso 4 por cabeceras «SCENE NN»")
    void parseScenes_splitsBySceneHeaders() {
        String scenes = "SCENE 01\nBakery, evening. Emiko kneads dough.\n\nSCENE 02\nRooftop, night. "
                + "Daniel arrives.\n\nSCENE 03\nCliffhanger on the rooftop.";

        java.util.List<VideoStoryPromptFactory.SceneBlock> blocks =
                VideoStoryPromptFactory.parseScenes(scenes);

        assertEquals(3, blocks.size());
        assertEquals("SCENE 01", blocks.get(0).id());
        assertTrue(blocks.get(0).text().startsWith("SCENE 01\nBakery"));
        assertTrue(blocks.get(0).text().contains("Emiko kneads"));
        assertFalse(blocks.get(0).text().contains("SCENE 02"));
        assertEquals("SCENE 02", blocks.get(1).id());
        assertTrue(blocks.get(1).text().contains("Rooftop, night"));
        assertFalse(blocks.get(1).text().contains("Cliffhanger"));
        assertEquals("SCENE 03", blocks.get(2).id());
        assertTrue(blocks.get(2).text().endsWith("Cliffhanger on the rooftop."));
    }

    @Test
    @DisplayName("parseScenes: cabeceras con negritas caen al fallback; sin cabeceras, lista vacía")
    void parseScenes_looseHeaders_andEmptyCases() {
        // El fallback tolera «**SCENE 01**» (negritas del modelo).
        java.util.List<VideoStoryPromptFactory.SceneBlock> loose =
                VideoStoryPromptFactory.parseScenes("**SCENE 01**\nThe bakery at dawn.");
        assertEquals(1, loose.size());
        assertEquals("SCENE 01", loose.get(0).id());
        assertTrue(loose.get(0).text().contains("The bakery at dawn."));

        assertTrue(VideoStoryPromptFactory.parseScenes("Una escena sin cabeceras reconocibles…").isEmpty());
        assertTrue(VideoStoryPromptFactory.parseScenes("   ").isEmpty());
        assertTrue(VideoStoryPromptFactory.parseScenes(null).isEmpty());
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
