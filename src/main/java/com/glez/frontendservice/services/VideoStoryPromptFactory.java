package com.glez.frontendservice.services;

import com.glez.frontendservice.dtos.StoryPromptRequest;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fábrica central de los PROMPTS del flujo de Historias en Video: contiene
 * las plantillas de la timeline (1 STORY PROMPT, 2 CHARACTER DESIGN PROMPT,
 * 3 ASSETS DESIGN MASTER, 4 SCENE PROMPT GENERATOR y 5 VIDEO GENERATION
 * MASTER PROMPT) — todas VERBATIM del usuario — junto con sus ensambladores,
 * el parser de escenas del paso 4 y la validación común del formulario de
 * historia.
 *
 * <p>Las herramientas manuales (bibliotecas de prompts) y el Asistente
 * (generación encadenada con IA) comparten esta fábrica, de modo que la
 * plantilla vive en UN solo sitio: paso 1 produce la historia, el paso 2
 * incrusta esa historia y produce los diseños, el paso 3 incrusta historia +
 * diseños y produce la biblioteca de assets, el paso 4 produce las escenas,
 * y el paso 5 (opcional) transporta UNA escena (auto-seleccionada) a la
 * PLANTILLA MASTER de 15 secciones de generación de video con TODA la cadena
 * embebida.</p>
 */
public final class VideoStoryPromptFactory {

    private VideoStoryPromptFactory() {
    }

    // ═══════════════════════ PASO 1: STORY PROMPT ═══════════════════════

    /**
     * La PLANTILLA de historia completa, VERBATIM: cada [PLACEHOLDER] se
     * sustituye en {@link #buildStoryPrompt} con el campo correspondiente del
     * formulario. Los placeholders que aparecen más de una vez ([GENRE],
     * [TARGET AUDIENCE]) se sustituyen en todas sus ocurrencias; los tres
     * [X]% del ritmo se sustituyen por posición.
     */
    static final String STORY_TEMPLATE = """
            Write a short, emotionally engaging [GENRE] story for a [TARGET AUDIENCE] audience, designed specifically for a cinematic 3D animated video.

            The story should feel like [TONE / EMOTIONAL STYLE], with relatable characters, natural conversations, visual storytelling, emotional tension, and a strong [CLIFFHANGER / ENDING TYPE].

            Keep the writing simple, visual, cinematic, and easy to transform into animated scenes.

            STORY STYLE:

            - Genre: [GENRE]
            - Subgenre: [SUBGENRE]
            - Target audience: [TARGET AUDIENCE]
            - Country/cultural setting: [COUNTRY / CULTURE]
            - Preferred location: [CITY / TYPE OF LOCATION]
            - Family-friendly: [YES / NO]
            - Visual style: [CINEMATIC 3D ANIMATED FEATURE / OTHER]
            - Tone: [ROMANTIC / COMEDIC / DRAMATIC / SUSPENSEFUL / EMOTIONAL / OTHER]
            - Realism level: [REALISTIC / STYLIZED / FANTASY]
            - Overall feeling: [DESCRIPTION]

            The story should include realistic situations related to:
            [LIST OF THEMES, CONFLICTS, RELATIONSHIPS, SECRETS, AMBITION, JEALOUSY, MYSTERY, ETC.]

            Avoid:
            - Generic or predictable storytelling
            - Exaggerated melodrama
            - Unrealistic character behavior
            - Instant romantic relationships
            - Excessive exposition
            - Long descriptions that are difficult to visualize
            - Clichés unless creatively reinvented

            Make the characters behave like believable adults.

            Build relationships gradually through actions, dialogue, body language, misunderstandings, shared experiences, and emotional subtext.

            MAIN CHARACTERS:

            Create [NUMBER] important characters.

            For each character establish:

            Name:
            Age:
            Gender:
            Nationality:
            Job / Role:
            Personality:
            Appearance:
            Relationship to other characters:
            Secretly wants:
            Afraid of losing:
            Character flaw:
            Strength:
            Hidden secret:
            Character arc:

            MAIN CHARACTER REQUIREMENTS:

            [MAIN CHARACTER 1 REQUIREMENTS]

            [MAIN CHARACTER 2 REQUIREMENTS]

            [OTHER CHARACTER REQUIREMENTS]

            STORY STRUCTURE:

            1. HOOK

            Start with an intriguing situation in the first 1–2 sentences.

            The opening must immediately create curiosity and make the audience want to know what happens next.

            2. SETUP

            Introduce the protagonist naturally.

            Show:
            - Their normal life
            - Their environment
            - Their personality
            - Their immediate goal
            - Their emotional situation

            Do not spend too much time on exposition.

            3. FIRST CONNECTION

            Introduce the second important character through a natural interaction.

            Do not immediately state that they are attracted to each other.

            Show chemistry through:
            - Eye contact
            - Small gestures
            - Helpful actions
            - Teasing
            - Awkward moments
            - Shared humor
            - Unexpected conversations
            - Physical proximity
            - Subtle reactions

            4. DEVELOPING RELATIONSHIP

            Create several short interactions that gradually deepen the relationship.

            Each interaction should reveal something new about the characters.

            Other characters may notice the growing connection.

            5. COMPLICATION

            Introduce a meaningful obstacle.

            Possible complications include:

            - Workplace policy
            - Career competition
            - Promotion
            - Secret from the past
            - Misunderstanding
            - Jealousy
            - Hidden relationship
            - Family connection
            - Major career decision
            - Betrayal
            - One character leaving
            - A mysterious message
            - A personal secret
            - Conflict of interest
            - Unexpected discovery

            The complication must be connected to the characters and story rather than feeling random.

            6. EMOTIONAL TURN

            Reveal information that changes how the audience understands the relationship or previous events.

            The reveal should:
            - Make sense in retrospect
            - Increase emotional stakes
            - Change character motivations
            - Create new questions

            7. CLIMAX

            Bring the emotional and narrative tension to its highest point.

            The characters should be forced to confront:
            [MAIN EMOTIONAL CONFLICT]

            8. CLIFFHANGER

            Do not completely resolve the main conflict or relationship.

            End with:
            - A shocking revelation
            - Unexpected message
            - Confession
            - Discovery
            - Decision
            - Arrival of another character
            - Hidden identity
            - Secret revealed
            - Sudden betrayal
            - New piece of information

            The final moment should make the audience immediately want Part 2.

            DIALOGUE:

            Use natural [LANGUAGE] dialogue.

            Characters should sound like real people.

            Keep dialogue:
            - Short
            - Conversational
            - Emotionally natural
            - Appropriate for the characters' ages
            - Appropriate for the culture and setting

            Use:
            - Subtle flirting
            - Pauses
            - Awkwardness
            - Humor
            - Emotional reactions
            - Subtext
            - Interruptions when appropriate

            Avoid unnatural exposition.

            Never have characters explain information they would already know simply for the audience's benefit.

            NARRATION:

            Use concise narration only when necessary.

            Show emotions through:
            - Facial expressions
            - Body language
            - Eye movement
            - Gestures
            - Silence
            - Physical actions
            - Changes in posture

            Do not repeatedly explain what characters are feeling.

            VISUAL STORYTELLING:

            Every paragraph should be easy to visualize as a cinematic 3D animation.

            Include when relevant:
            - Environment
            - Architecture
            - Weather
            - Time of day
            - Lighting
            - Body language
            - Facial expressions
            - Small gestures
            - Eye contact
            - Character movement
            - Props
            - Vehicles
            - Background activity
            - Visually interesting locations

            PACING:

            Target length: a cinematic video of approximately [DURATION].

            Structure the pacing approximately as:

            - First [X]% — Character introduction and setup
            - Middle [X]% — Relationship development and conflict
            - Final [X]% — Emotional reveal, climax, and cliffhanger

            Introduce a meaningful new development every few paragraphs.

            Do not spend too much time describing locations.

            ENDING:

            Do not completely resolve:
            [MAIN UNRESOLVED ELEMENT]

            The final line should create a strong emotional reaction such as:

            "Wait... WHAT?!"

            OUTPUT FORMAT:

            TITLE — Create a highly clickable emotional title.
            HOOK — Provide the strongest 1–2 sentence opening.
            STORY — Write the complete story.
            CLIFFHANGER — Summarize the final shocking revelation in one sentence.
            CHARACTER LIST — Give a brief description of every important character.

            IMPORTANT:

            Create a completely original storyline with original characters, situations, relationships, conflicts, and dialogue.

            Do not copy or closely imitate existing movies, TV shows, books, viral stories, or other copyrighted works.

            Make the story emotionally engaging, visually cinematic, easy to animate, and suitable for [PLATFORM / FORMAT].
            """;

    /** El párrafo completo de temas: se elimina entero si no se aportan temas. */
    private static final String THEMES_PARAGRAPH =
            "The story should include realistic situations related to:\n"
            + "[LIST OF THEMES, CONFLICTS, RELATIONSHIPS, SECRETS, AMBITION, JEALOUSY, MYSTERY, ETC.]\n\n";

    /** El bloque completo de requisitos: se elimina entero si los tres van vacíos. */
    private static final String CHARACTER_REQUIREMENTS_BLOCK =
            "MAIN CHARACTER REQUIREMENTS:\n\n"
            + "[MAIN CHARACTER 1 REQUIREMENTS]\n\n"
            + "[MAIN CHARACTER 2 REQUIREMENTS]\n\n"
            + "[OTHER CHARACTER REQUIREMENTS]\n\n";

    /**
     * Rellena la PLANTILLA de historia completa con los campos del
     * formulario. Placeholders de una sola ocurrencia por valor; los
     * opcionales de una línea eliminan la línea si van vacíos; el párrafo de
     * temas y el bloque de requisitos desaparecen completos si van vacíos; y
     * los tres [X]% del ritmo se sustituyen POR POSICIÓN.
     */
    public static String buildStoryPrompt(StoryPromptRequest r) {
        String prompt = STORY_TEMPLATE;

        prompt = prompt
                .replace("[GENRE]", r.genre().trim())
                .replace("[TARGET AUDIENCE]", r.targetAudience().trim())
                .replace("[TONE / EMOTIONAL STYLE]", r.tone().trim())
                // El placeholder del tono en el STORY STYLE es DISTINTO al de
                // la primera línea (lista de estilos) pero lleva el mismo valor.
                .replace("[ROMANTIC / COMEDIC / DRAMATIC / SUSPENSEFUL / EMOTIONAL / OTHER]", r.tone().trim())
                .replace("[CLIFFHANGER / ENDING TYPE]", valueOrDefault(r.endingType(), "CLIFFHANGER"))
                .replace("[NUMBER]", String.valueOf(
                        r.characterCount() != null && r.characterCount() > 0 ? r.characterCount() : 2))
                .replace("[MAIN EMOTIONAL CONFLICT]", r.mainEmotionalConflict().trim())
                .replace("[MAIN UNRESOLVED ELEMENT]", r.unresolvedElement().trim())
                .replace("[LANGUAGE]", r.dialogueLanguage().trim())
                .replace("[DURATION]", formatDuration(r.videoDurationSeconds()))
                .replace("[PLATFORM / FORMAT]", r.platform().trim());

        prompt = prompt
                .replace("[YES / NO]", valueOrDefault(r.familyFriendly(), "YES"))
                .replace("[CINEMATIC 3D ANIMATED FEATURE / OTHER]",
                        valueOrDefault(r.visualStyle(), "CINEMATIC 3D ANIMATED FEATURE"))
                .replace("[REALISTIC / STYLIZED / FANTASY]", valueOrDefault(r.realismLevel(), "STYLIZED"));

        prompt = replaceOrDropLine(prompt, "[SUBGENRE]", r.subgenre());
        prompt = replaceOrDropLine(prompt, "[COUNTRY / CULTURE]", r.countryCulture());
        prompt = replaceOrDropLine(prompt, "[CITY / TYPE OF LOCATION]", r.location());
        prompt = replaceOrDropLine(prompt, "[DESCRIPTION]", r.overallFeeling());

        if (isBlank(r.themes())) {
            prompt = prompt.replace(THEMES_PARAGRAPH, "");
        } else {
            prompt = prompt.replace(
                    "[LIST OF THEMES, CONFLICTS, RELATIONSHIPS, SECRETS, AMBITION, JEALOUSY, MYSTERY, ETC.]",
                    r.themes().trim());
        }

        if (isBlank(r.character1Requirements()) && isBlank(r.character2Requirements())
                && isBlank(r.otherCharacterRequirements())) {
            prompt = prompt.replace(CHARACTER_REQUIREMENTS_BLOCK, "");
        } else {
            prompt = replaceOrDropLine(prompt, "[MAIN CHARACTER 1 REQUIREMENTS]", r.character1Requirements());
            prompt = replaceOrDropLine(prompt, "[MAIN CHARACTER 2 REQUIREMENTS]", r.character2Requirements());
            prompt = replaceOrDropLine(prompt, "[OTHER CHARACTER REQUIREMENTS]", r.otherCharacterRequirements());
        }

        prompt = prompt.replaceFirst(Pattern.quote("[X]%"),
                pacingPercent(r.pacingFirstPercent(), 30) + "%");
        prompt = prompt.replaceFirst(Pattern.quote("[X]%"),
                pacingPercent(r.pacingMiddlePercent(), 50) + "%");
        prompt = prompt.replaceFirst(Pattern.quote("[X]%"),
                pacingPercent(r.pacingFinalPercent(), 20) + "%");

        prompt = prompt.replaceAll("\n{3,}", "\n\n").stripTrailing();
        return prompt;
    }

    /**
     * Validación común del formulario de historia (paso 1): los campos que
     * la plantilla necesita sí o sí — incluida la DURACIÓN DEL VIDEO en
     * segundos, que dirige la extensión de la historia y de las escenas.
     * False si falta alguno.
     */
    public static boolean isValidStoryRequest(StoryPromptRequest r) {
        return r != null
                && !isBlank(r.title()) && !isBlank(r.genre())
                && !isBlank(r.targetAudience()) && !isBlank(r.tone())
                && !isBlank(r.mainEmotionalConflict()) && !isBlank(r.unresolvedElement())
                && !isBlank(r.dialogueLanguage())
                && r.videoDurationSeconds() != null && r.videoDurationSeconds() > 0
                && !isBlank(r.platform());
    }

    // ═══════════════ PASO 2: CHARACTER DESIGN PROMPT ═══════════════

    /**
     * La PLANTILLA de diseño de personajes, VERBATIM hasta el encabezado
     * OUTPUT: el único placeholder sustituible es [3D ANIMATION STYLE]; los
     * bloques de salida "Character N: [SINGLE COMPLETE PROMPT]" se generan
     * uno por personaje solicitado.
     */
    static final String DESIGN_TEMPLATE = """
            Based on the story above, create ONE SINGLE CHARACTER DESIGN PROMPT for EACH important character.

            The character designs must remain visually consistent throughout the entire animated project.

            CHARACTER STYLE:

            [3D ANIMATION STYLE]

            Create characters with:

            - Appealing cinematic proportions
            - Expressive eyes
            - Expressive facial animation
            - Stylized but believable anatomy
            - Detailed sculpted hair
            - High-quality clothing textures
            - Soft stylized skin shading
            - Premium animated-feature rendering
            - Cinematic lighting
            - Family-friendly appearance

            For EACH CHARACTER, include:

            - Name
            - Age
            - Nationality
            - Job / role
            - Personality
            - Face shape
            - Skin tone
            - Eye color
            - Hair color
            - Hair style
            - Facial features
            - Body type
            - Height impression
            - Clothing
            - Shoes
            - Accessories
            - Typical expression
            - Body language
            - Overall visual personality

            CHARACTER CONTINUITY:

            Maintain the exact same:

            - Face
            - Hair
            - Hair color
            - Eye color
            - Skin tone
            - Body proportions
            - Clothing design
            - Accessories
            - Age appearance
            - Color palette
            - Animation style

            throughout every scene.

            Do not redesign characters between scenes.

            OUTPUT:""";

    /**
     * Ensambla el prompt de diseño de personajes: incrusta la HISTORIA
     * GENERADA antes de la plantilla (así "Based on the story above" es
     * literal — el prompt es autocontenido), sustituye el estilo de
     * animación y genera los N bloques de salida, separados por una línea en
     * blanco y SIN salto final.
     */
    public static String buildCharacterDesignPrompt(String story, String animationStyle, int characterCount) {
        String base = DESIGN_TEMPLATE
                .replace("[3D ANIMATION STYLE]", animationStyle == null ? "" : animationStyle.trim())
                .stripTrailing();

        StringBuilder prompt = new StringBuilder()
                .append("Here is the story the character designs must be based on:\n\n")
                .append(story == null ? "" : story.strip())
                .append("\n\n---\n\n")
                .append(base)
                .append("\n\n");

        int n = characterCount > 0 ? characterCount : 4;
        for (int i = 1; i <= n; i++) {
            prompt.append("Character ").append(i).append(":\n[SINGLE COMPLETE PROMPT]");
            if (i < n) {
                prompt.append("\n\n");
            }
        }
        return prompt.toString();
    }

    // ═══════════ PASO 3: ASSETS DESIGN (utensilios/objetos) ═══════════

    /**
     * La PLANTILLA MASTER de diseño de assets, VERBATIM: analiza la historia
     * y los diseños de personajes aprobados y produce la biblioteca
     * reutilizable de assets (entornos, props, tecnología, documentos,
     * vehículos, motifs) con IDs estables para las etapas de escenas y
     * video. Los 6 placeholders viven en la sección 1 (INPUT MATERIAL) y
     * se sustituyen IN-PLACE: la historia bajo "### A. Complete Story", los
     * diseños bajo "### B. Approved Character Designs", y los 4 campos de
     * estilo global en la sección C.
     */
    static final String ASSETS_TEMPLATE = """
            # MASTER PROMPT — ASSETS DESIGN

            ## ROLE

            Act as a senior 3D animation art director, environment designer, prop designer, and visual development artist specializing in high-quality cinematic animated films.

            Your task is to analyze an existing story and its approved character designs, then create a complete, organized collection of visual assets required to produce the story as a cinematic 3D animated video.

            The assets must be visually consistent with the established character designs, animation style, story atmosphere, locations, and narrative requirements.

            You are preparing assets for the next production stage:

            **Story Prompt → Character Design → Assets Design → Scene Generator → Video Generation**

            Your output must be practical for AI image generation, 3D asset creation, and subsequent scene-by-scene video generation.

            ---

            ## 1. INPUT MATERIAL

            You will receive the following information:

            ### A. Complete Story
            [INSERT THE COMPLETE STORY GENERATED IN THE STORY PROMPT STAGE]

            ### B. Approved Character Designs
            [INSERT THE CHARACTER DESIGN PROMPTS GENERATED IN THE CHARACTER DESIGN STAGE]

            ### C. Global Visual Style
            - Animation style: [INSERT ANIMATION STYLE]
            - Visual quality: High-quality cinematic 3D animated feature film.
            - Target audience: [INSERT TARGET AUDIENCE]
            - Aspect ratio: [16:9 / 9:16 / 1:1]
            - Color palette: [INSERT COLOR PALETTE OR DERIVE IT FROM THE STORY]
            - Lighting style: Cinematic, physically coherent, and appropriate to the mood of each environment.
            - Rendering style: Detailed materials, believable textures, appealing shapes, controlled depth of field, and polished cinematic composition.

            If a field is not provided, derive a suitable choice from the story and maintain it consistently.

            ---

            ## 2. PRIMARY OBJECTIVE

            Analyze the story and identify every visual asset needed to produce its important scenes.

            Create reusable designs for the following categories:

            1. Environments and locations.
            2. Interior spaces.
            3. Exterior spaces.
            4. Narrative props and important objects.
            5. Everyday props.
            6. Furniture and environmental objects.
            7. Vehicles and transportation, when relevant.
            8. Technology and electronic devices.
            9. Documents, photographs, letters, and other story-related materials.
            10. Recurring visual motifs and special environmental elements.

            Only create categories that are relevant to the story.

            Do not generate assets merely to fill the list. Every asset must serve a visual, narrative, or production purpose.

            **Important:** Do not redesign the characters. Their approved character designs are the source of truth for character appearance, proportions, wardrobe, and visual style.

            ---

            ## 3. ASSET EXTRACTION AND CLASSIFICATION

            Before writing the final prompts, analyze the story internally and identify the assets that appear in its scenes.

            For each asset, determine:

            - Asset ID.
            - Asset name.
            - Asset category.
            - Narrative purpose.
            - Scenes in which it appears.
            - Whether it is reused or appears only once.
            - Whether it is a primary, secondary, or background asset.
            - Its required visual details.
            - Whether its appearance changes during the story.
            - Whether it must maintain continuity across multiple scenes.

            Assign each asset a unique identifier.

            Use the following naming conventions:

            - ENV-001, ENV-002, ENV-003: Environments.
            - INT-001, INT-002, INT-003: Interior spaces.
            - EXT-001, EXT-002, EXT-003: Exterior spaces.
            - PROP-001, PROP-002, PROP-003: Props.
            - TECH-001, TECH-002, TECH-003: Technology.
            - VEH-001, VEH-002, VEH-003: Vehicles.
            - DOC-001, DOC-002, DOC-003: Documents and paper-based objects.
            - MOTIF-001, MOTIF-002, MOTIF-003: Recurring visual motifs.

            Keep the identifiers stable throughout the entire production workflow.

            If an object appears repeatedly, reuse the same asset ID instead of creating duplicate designs.

            If an asset changes during the story, distinguish between its base design and its specific story state.

            Example:

            - PROP-001: Smartphone — base design.
            - PROP-001-A: Smartphone with screen turned off.
            - PROP-001-B: Smartphone displaying an incoming call.

            Only create state variations when they are visually or narratively necessary.

            ---

            ## 4. ENVIRONMENT DESIGN

            For every important location, create a complete environment design prompt.

            The environment must be designed as a reusable location, not as a single scene.

            Include the following information:

            ### Environment Design Structure

            **Asset ID:**
            Unique environment identifier.

            **Environment Name:**
            A short, descriptive name.

            **Environment Type:**
            Office, apartment, restaurant, street, rooftop, conference room, parking garage, café, or another relevant location.

            **Narrative Function:**
            Explain why this location matters to the story.

            **Geographical Context:**
            Describe the city, neighborhood, architectural influence, and cultural details required by the story.

            **Spatial Layout:**
            Describe the overall floor plan, room dimensions, entrances, exits, windows, doors, hallways, furniture placement, and important landmarks.

            **Architectural Design:**
            Describe the architectural style, materials, structural elements, and decorative details.

            **Color Palette:**
            Define the dominant colors and supporting accent colors.

            **Materials and Textures:**
            Specify the materials used for walls, floors, ceilings, windows, metal, wood, glass, fabrics, and other visible surfaces.

            **Lighting Design:**
            Describe the default lighting conditions, practical light sources, natural light direction, shadows, and overall atmosphere.

            **Permanent Visual Landmarks:**
            Identify recognizable elements that must remain in the same positions across scenes.

            **Reusable Set Dressing:**
            List furniture, decorative objects, plants, lamps, signs, and other environmental assets that belong to this location.

            **Character Interaction Areas:**
            Identify areas where characters can sit, stand, walk, work, meet, argue, or interact with objects.

            **Continuity Rules:**
            Specify which structural elements and objects must remain unchanged.

            **Full Environment Generation Prompt:**
            Write one complete, detailed prompt ready for an AI image-generation system.

            The final prompt must describe the location clearly enough that another generation can reproduce the same environment.

            Do not include characters unless a human figure is strictly necessary to communicate scale. Prefer an empty environment for the primary environment asset.

            Do not include dialogue, subtitles, watermarks, or unnecessary text.

            ---

            ## 5. PROP AND OBJECT DESIGN

            Create a reusable asset design prompt for every important object that characters interact with or that contributes to the story.

            Prioritize objects that:

            - Reveal important information.
            - Create misunderstandings.
            - Carry emotional meaning.
            - Are exchanged between characters.
            - Appear in multiple scenes.
            - Change state during the story.
            - Are visually prominent in close-up shots.
            - Are essential to the story's climax or cliffhanger.

            For each object, use this structure:

            **Asset ID:**
            Unique identifier.

            **Asset Name:**
            Descriptive object name.

            **Category:**
            Narrative prop, everyday prop, technology, document, accessory, furniture, or another suitable category.

            **Narrative Importance:**
            Explain how the object contributes to the story.

            **Physical Description:**
            Describe the object's shape, dimensions, proportions, construction, and recognizable features.

            **Materials and Textures:**
            Describe the materials, surface finishes, reflections, wear, and small details.

            **Color Palette:**
            Specify the object's main colors and accent colors.

            **Distinctive Features:**
            Identify details that make this object recognizable in every scene.

            **Default State:**
            Describe the object's initial condition.

            **Possible Story States:**
            Describe any important changes, such as open or closed, damaged or intact, screen on or off, folded or unfolded.

            **Interaction Requirements:**
            Explain how characters hold, open, carry, use, or manipulate the object.

            **Scale and Proportions:**
            Provide approximate dimensions or realistic size relationships when useful.

            **Continuity Rules:**
            Specify the details that must remain identical across scenes.

            **Object Generation Prompt:**
            Write one complete prompt ready for AI image generation.

            **Optional Detail Views:**
            Specify additional views only when they are necessary, such as front, back, side, top, open state, or close-up.

            Generate a separate asset only when an object's design or state needs to be independently controlled.

            Do not create multiple assets for minor variations that can be described in the scene prompt.

            ---

            ## 6. TECHNOLOGY AND DOCUMENTS

            When the story includes phones, computers, monitors, tablets, emails, photographs, letters, contracts, reports, or other information-bearing objects, treat their visual appearance as part of continuity.

            For each relevant item, define:

            - Device or document type.
            - Physical design.
            - Screen or paper appearance.
            - Relevant visible information.
            - Text placement and orientation, when applicable.
            - Default state.
            - Narrative states.
            - Scenes in which the item appears.
            - Required close-up views.
            - Continuity requirements.

            ### Important rules for readable text

            If readable text is essential to the story, provide the exact text that must appear.

            Keep the text short and specific.

            Do not invent names, dates, messages, or document contents that contradict the story.

            Because image-generation systems may render text inaccurately, identify when the text should be added during post-production rather than generated directly in the image.

            For screens and documents that do not require readable content, use a plausible visual layout without introducing distracting or contradictory details.

            ---

            ## 7. ASSET VARIATIONS AND STORY STATES

            Some assets must evolve as the story progresses.

            Identify these cases and create explicit variants when necessary.

            Examples:

            - An office before and after a major event.
            - A conference room during the day and at night.
            - A desk before and after an important meeting.
            - A sealed envelope and the same envelope after being opened.
            - A smartphone showing different story-relevant screens.
            - A photograph in its original state and a torn or damaged version.
            - A location during normal business hours and after everyone has left.

            For each variation, specify:

            1. Base asset ID.
            2. Variation ID.
            3. Exact visual changes.
            4. Details that must remain unchanged.
            5. Narrative reason for the variation.
            6. Scenes that use the variation.

            **Do not treat changes in camera angle, framing, or lighting as new assets.**

            These belong to the Scene Generator stage unless the environment itself must physically change.

            ---

            ## 8. ASSET VISUALIZATION PROMPTS

            For each major asset, generate an image prompt suitable for establishing its canonical appearance.

            Choose the appropriate presentation based on the asset category.

            ### For environments

            Prefer an empty, wide establishing view that clearly communicates the location's architecture and spatial layout.

            Include enough visible detail to establish the position of permanent landmarks and furniture.

            ### For props

            Prefer a clean product-style presentation with the object isolated against a neutral background.

            Show the complete object without cropping important details.

            ### For complex objects

            Use a reference-sheet presentation with front, side, back, and detail views when necessary.

            Maintain consistent proportions and materials across all views.

            ### For recurring story objects

            Show the canonical design first. If the object changes during the story, create separate prompts for the necessary variants.

            ### Visual style requirements

            All assets must follow the global visual style and match the approved character designs.

            For cinematic 3D animation, use:

            - Appealing and intentional shapes.
            - Carefully controlled proportions.
            - Detailed but readable surfaces.
            - Physically coherent materials.
            - Cinematic lighting appropriate to the intended presentation.
            - A consistent level of detail.
            - A coherent color palette.
            - Professional feature-film-quality rendering.

            Avoid photorealistic live-action imagery if the established project style is stylized 3D animation.

            Do not introduce a different animation style for individual assets.

            ---

            ## 9. CONTINUITY AND REUSE RULES

            Consistency across all scenes is mandatory.

            For every asset, establish a canonical design that remains stable throughout the production.

            ### Environment continuity

            Maintain consistent:

            - Architecture.
            - Room dimensions and layout.
            - Door and window positions.
            - Furniture placement.
            - Permanent decorations.
            - Materials and surface finishes.
            - Distinctive environmental landmarks.

            Lighting and atmosphere may change according to the story, but the underlying location must remain recognizable.

            ### Prop continuity

            Maintain consistent:

            - Shape.
            - Size.
            - Proportions.
            - Color.
            - Materials.
            - Distinctive details.
            - Logos or labels, if explicitly required.
            - Physical condition, except when the story changes it.

            ### Cross-stage continuity

            The Scene Generator must reference assets by their IDs.

            For example:

            "Location: INT-001 — Executive Conference Room."

            "Props: PROP-003 — Black Smartphone; DOC-001 — Sealed Envelope."

            Do not rely solely on descriptive names. Include the IDs so the same canonical assets can be reused.

            If an asset is unavailable or not yet designed, flag it as missing instead of silently inventing a replacement.

            ---

            ## 10. ASSET PRIORITIZATION

            Organize assets into three priority levels.

            ### Priority A — Essential Assets

            Assets required to understand the story or produce its most important scenes.

            Examples:
            - Main locations.
            - Objects that reveal secrets.
            - Objects involved in the central conflict.
            - Important recurring props.
            - Environments used in the climax or cliffhanger.

            ### Priority B — Supporting Assets

            Assets that improve realism, establish character routines, or support meaningful interactions.

            Examples:
            - Desks.
            - Office chairs.
            - Coffee cups.
            - Laptops.
            - Desk lamps.
            - Bags.
            - Notebooks.

            ### Priority C — Background Assets

            Assets that enrich the environment but do not drive the story.

            Examples:
            - Background office equipment.
            - Decorative objects.
            - Distant street furniture.
            - Secondary plants.
            - Nonessential environmental details.

            Avoid generating detailed standalone prompts for every minor background object.

            Group minor objects into a reusable set-dressing collection when possible.

            ---

            ## 11. FINAL OUTPUT FORMAT

            Deliver the results in the following order.

            ### PART 1 — ASSET INVENTORY

            Create a table with these columns:

            | Asset ID | Asset Name | Category | Priority | Reusable? | Story Purpose | Scenes Used |
            |---|---|---|---|---|---|---|

            Use the same identifiers throughout the output.

            ### PART 2 — ENVIRONMENT ASSETS

            For each important environment, provide:

            1. Asset ID and name.
            2. Narrative function.
            3. Spatial and architectural description.
            4. Color palette and materials.
            5. Permanent visual landmarks.
            6. Continuity requirements.
            7. Complete environment generation prompt.

            ### PART 3 — PROP AND OBJECT ASSETS

            For each important object, provide:

            1. Asset ID and name.
            2. Narrative importance.
            3. Physical description.
            4. Materials and colors.
            5. Distinctive features.
            6. Default state and required variations.
            7. Continuity requirements.
            8. Complete object generation prompt.

            ### PART 4 — ASSET VARIATIONS

            Create a table:

            | Base Asset ID | Variation ID | Visual Change | Narrative Reason | Scenes Used |
            |---|---|---|---|---|

            If no variations are needed, explicitly state that.

            ### PART 5 — REUSABLE SET DRESSING

            Group secondary and background objects by location.

            For example:

            - Executive office set dressing.
            - Open-plan office set dressing.
            - Apartment set dressing.
            - Restaurant set dressing.
            - Street set dressing.

            Provide one concise generation prompt for each collection when useful.

            ### PART 6 — ASSET REFERENCE PROMPTS

            Provide the final, copy-ready image-generation prompts for the essential assets.

            Each prompt must be self-contained and must not require the image-generation system to read previous prompts.

            Include the global visual style and the asset's important identifying details in each prompt.

            ### PART 7 — CONTINUITY MANIFEST

            Finish with a concise list of rules that the Scene Generator must follow.

            Include:

            - Canonical asset IDs.
            - Location layouts that must remain stable.
            - Important props and their designs.
            - Required asset variations.
            - Cross-scene consistency rules.
            - Any missing information or unresolved design decisions.

            ### PART 8 — PRODUCTION READINESS CHECK

            Evaluate whether the assets are ready for the Scene Generator.

            Use these statuses:

            - READY: The asset is sufficiently defined for scene production.
            - NEEDS VARIATION: The base asset exists, but one or more story states must be designed.
            - NEEDS DETAIL: Important visual information is missing.
            - MISSING: The asset has not been defined.

            List only genuine issues that could affect scene generation.

            Do not claim that an image or 3D model has been generated if you have only written a prompt.

            ---

            ## 12. FINAL QUALITY CONTROL

            Before delivering the result, verify the following:

            - Every important story location has been identified.
            - Every plot-critical object has been identified.
            - Recurring assets have stable IDs.
            - Asset variations are explicitly documented.
            - Environment layouts are sufficiently clear for reuse.
            - Objects have recognizable, reproducible designs.
            - All assets match the established animation style.
            - Character designs are respected and not unnecessarily duplicated.
            - The prompts can be used independently in image-generation systems.
            - No new plot events or contradictory details have been introduced.
            - The asset inventory is organized for direct use by the Scene Generator.
            - Unnecessary assets and duplicate designs have been avoided.

            The objective is not to create the largest possible collection of assets.

            The objective is to create the **smallest complete, visually coherent, reusable asset library that can support every important scene in the story**.

            ---

            ## EXECUTION INSTRUCTION

            Analyze the supplied story and approved character designs, then execute all relevant sections of this prompt.

            Do not rewrite the story.

            Do not redesign the characters.

            Do not generate the final scene-by-scene prompts yet.

            Do not generate video-generation prompts yet.

            Focus exclusively on identifying, designing, organizing, and documenting the reusable visual assets required for the next stage.

            Return the final output in the specified order, using clear headings, concise technical descriptions, and copy-ready generation prompts.""";

    /**
     * Ensambla el prompt de diseño de assets (paso 3): sustituye IN-PLACE
     * los 6 placeholders de la sección INPUT MATERIAL — la historia bajo
     * "### A. Complete Story", los diseños aprobados bajo
     * "### B. Approved Character Designs" (la plantilla trae sus propias
     * ranuras de entrada, a diferencia de los pasos 2 y 4) y los campos de
     * estilo global en la sección C. Los opcionales vacíos caen a la regla
     * de fallback que la propia plantilla define ("derive a suitable
     * choice from the story").
     */
    public static String buildAssetsPrompt(String story, String designs, String animationStyle,
            String targetAudience, String aspectRatio, String colorPalette) {
        return ASSETS_TEMPLATE
                .replace("[INSERT THE COMPLETE STORY GENERATED IN THE STORY PROMPT STAGE]",
                        story == null ? "" : story.strip())
                .replace("[INSERT THE CHARACTER DESIGN PROMPTS GENERATED IN THE CHARACTER DESIGN STAGE]",
                        designs == null ? "" : designs.strip())
                .replace("[INSERT ANIMATION STYLE]",
                        valueOrDefault(animationStyle,
                                "Not specified — derive it from the established character designs"))
                .replace("[INSERT TARGET AUDIENCE]",
                        valueOrDefault(targetAudience,
                                "Not specified — derive a suitable choice from the story"))
                .replace("[16:9 / 9:16 / 1:1]", valueOrDefault(aspectRatio, "16:9"))
                .replace("[INSERT COLOR PALETTE OR DERIVE IT FROM THE STORY]",
                        valueOrDefault(colorPalette,
                                "Derive it from the story and maintain it consistently"))
                .stripTrailing();
    }

    // ═══════════════ PASO 4: SCENE PROMPT GENERATOR ═══════════════

    /**
     * La PLANTILLA generadora de escenas, VERBATIM. Placeholders nuestros:
     * [NUMBER OF SCENES] (1x) y [CHARACTER] (2x — POR POSICIÓN: primero el
     * que habla primero, luego el segundo) y [LANGUAGE] (1x). El resto de
     * marcas ([Action], [DIALOGUE], [Complete prompt]) los rellena la IA.
     */
    static final String SCENE_TEMPLATE = """
            Based on the complete story and established character designs above, divide the story into [NUMBER OF SCENES] cinematic 3D animated scenes.

            The total video duration is [TOTAL VIDEO DURATION]: distribute it across the scenes and make each scene's TIMING SPLIT reflect its exact length, so that all scenes together cover the full duration.

            Each scene must be directly connected to the previous scene and preserve perfect character and visual continuity.

            IMPORTANT:

            Use the EXACT established character designs.

            Do not change:
            - Character face
            - Hair
            - Hair color
            - Eye color
            - Skin tone
            - Body proportions
            - Age
            - Clothing
            - Accessories
            - Animation style

            Every scene must be easy to generate as a cinematic video shot.

            For EACH SCENE use exactly this structure:

            1) SCENE TYPE

            Describe the cinematic purpose of the scene.

            Example:
            Feature-film quality 3D animated romantic drama. Emotional workplace scene. Evening.

            2) SUBJECT

            Describe:
            - Who is present
            - What they are doing
            - Their relationship
            - Important objects
            - Main visual focus

            3) APPEARANCE

            Describe the characters using their EXACT established character designs.

            Do not create new character descriptions that contradict the original designs.

            Include:
            - Clothing
            - Hair
            - Facial expressions
            - Body language
            - Props

            4) SETTING AND LIGHTING

            Describe:
            - Location
            - Time of day
            - Weather
            - Architecture
            - Background elements
            - Lighting
            - Atmosphere
            - Color mood
            - Environmental details

            5) ACTION SEQUENCE

            Describe the action chronologically.

            Use clear visual actions:

            - Character enters
            - Character looks
            - Character walks
            - Character sits
            - Character reaches
            - Character turns
            - Character reacts
            - Character pauses
            - Character approaches
            - Character leaves

            Focus on what can actually be seen on screen.

            6) TIMING SPLIT

            Break the scene into precise time segments.

            Example:

            0.0–2.0 sec:
            [Action]

            2.0–4.0 sec:
            [Action]

            4.0–7.0 sec:
            [Action]

            7.0–10.0 sec:
            [Action]

            Adapt the timing to the actual scene length.

            7) DIALOGUE

            Use only dialogue that belongs in the scene.

            STRICT CHARACTER DIALOGUE ASSIGNMENT:

            ONLY [CHARACTER] speaks first.

            ONLY [CHARACTER] speaks second.

            No dialogue overlap unless specifically requested.

            Natural [LANGUAGE] pronunciation.

            Conversational delivery.

            Accurate lip synchronization.

            Character A:
            "[DIALOGUE]"

            Character B:
            "[DIALOGUE]"

            8) SHOT TYPE AND CAMERA

            Describe:

            - Establishing shot
            - Wide shot
            - Medium shot
            - Close-up
            - Extreme close-up
            - Over-the-shoulder
            - Two-shot
            - Tracking shot
            - Dolly movement
            - Crane movement
            - Push-in
            - Pull-out
            - Camera angle
            - Depth of field

            Use cinematic feature-film camera language.

            9) AUDIO

            Describe:

            - Background music
            - Ambient sound
            - Environmental sound
            - Character movement
            - Footsteps
            - Objects
            - Weather
            - Room tone
            - Dialogue clarity

            Music must remain below dialogue.

            Dialogue must be crisp and synchronized with lip movement.

            10) NEGATIVES AND CONTINUITY

            No:
            - Live action
            - Photorealistic humans
            - Anime style
            - Unwanted art styles
            - Low-poly models
            - Uncanny faces
            - Broken anatomy
            - Extra fingers
            - Missing fingers
            - Duplicated characters
            - Floating objects
            - Visual glitches
            - Character deformation
            - Inconsistent faces
            - Inconsistent hairstyles
            - Outfit changes without story justification
            - Incorrect accessories
            - Lip-sync errors
            - Dialogue overlap
            - Camera shake
            - Random camera movement
            - On-screen text
            - Subtitles
            - Captions
            - Logos
            - Watermarks
            - UI elements
            - Readable unwanted branding

            Maintain exact continuity of:

            - Character identity
            - Face
            - Hair
            - Clothing
            - Accessories
            - Body proportions
            - Environment
            - Lighting
            - Color palette
            - Animation style
            - Camera language
            - Story timeline

            throughout the entire series.

            Generate scenes that feel like consecutive shots from the SAME cinematic animated movie.

            OUTPUT:

            SCENE 01
            [Complete prompt]

            SCENE 02
            [Complete prompt]

            SCENE 03
            [Complete prompt]

            Continue until the entire story has been converted into scenes.""";

    /**
     * Ensambla el prompt del generador de escenas (paso 3): ANTEPONE la
     * historia y los diseños establecidos (así "Based on the complete story
     * and established character designs above" es literal), sustituye el
     * número de escenas, los DOS [CHARACTER] por posición (primero el que
     * habla primero, luego el segundo — las líneas vacías se omiten) y el
     * idioma de pronunciación.
     */
    public static String buildScenePrompt(String story, String designs, int sceneCount,
            Integer totalVideoDurationSeconds,
            String speakerFirst, String speakerSecond, String language) {
        String base = SCENE_TEMPLATE
                .replace("[NUMBER OF SCENES]", String.valueOf(sceneCount > 0 ? sceneCount : 5))
                .replace("[LANGUAGE]", valueOrDefault(language, "Spanish").trim());

        // La duración total dirige el reparto de tiempos; los proyectos legacy
        // sin duración la omiten (comportamiento anterior por nº de escenas).
        if (totalVideoDurationSeconds != null && totalVideoDurationSeconds > 0) {
            base = base.replace("[TOTAL VIDEO DURATION]", formatDuration(totalVideoDurationSeconds));
        } else {
            base = base.replaceFirst(
                    "(?m)^.*" + Pattern.quote("The total video duration is [TOTAL VIDEO DURATION]")
                            + ".*\\R?", "");
        }

        // Los dos [CHARACTER] son idénticos: se sustituyen POR POSICIÓN. Si
        // un hablante va vacío, su línea desaparece completa.
        if (isBlank(speakerFirst) && isBlank(speakerSecond)) {
            base = base.replace("STRICT CHARACTER DIALOGUE ASSIGNMENT:\n\n", "");
            base = base.replaceAll("(?m)^.*" + Pattern.quote("[CHARACTER]") + ".*\\R?", "");
        } else {
            base = substitutePositional(base, "[CHARACTER]", speakerFirst);
            base = substitutePositional(base, "[CHARACTER]", speakerSecond);
        }

        String prefix = "Here is the complete story:\n\n"
                + (story == null ? "" : story.strip())
                + "\n\n---\n\n"
                + "Here are the established character designs:\n\n"
                + (designs == null ? "" : designs.strip())
                + "\n\n---\n\n";

        return prefix + base.stripTrailing();
    }

    // ═══════ PASO 5 (OPCIONAL): VIDEO GENERATION MASTER PROMPT ═══════

    /**
     * La PLANTILLA MASTER de generación de video, VERBATIM del usuario: 15
     * secciones (información del proyecto, ajustes globales, referencias de
     * personajes/assets, información de la escena, prompt de video,
     * desglose de tiempos, hoja de diálogo, cámara, audio, negativos,
     * referencias, continuidad, salida y validación) más la EXECUTION
     * INSTRUCTION que porta los materiales aprobados.
     *
     * <p><b>Reparto de slots</b>: la APP sustituye IN-PLACE solo los que
     * posee de verdad — {@code {{STORY_TITLE}}}, {@code {{VISUAL_STYLE}}},
     * {@code {{DIALOGUE_LANGUAGE}}}, {@code {{ASPECT_RATIO}}} y
     * {@code {{OUTPUT_ASPECT_RATIO}}}, {@code {{DEFAULT_CLIP_DURATION}}},
     * {@code {{AUDIO_ENABLED}}}, {@code {{TARGET_SCENE_ID}}} y los CUATRO
     * materiales aprobados de la EXECUTION INSTRUCTION ({@code {{APPROVED_*}}}).
     * El RESTO de {@code {{PLACEHOLDER}}} los RELLENA LA IA a partir de
     * esos materiales — es el diseño de la propia plantilla («Return the
     * completed video-generation prompt using the structure defined in this
     * template»).</p>
     */
    static final String MASTER_VIDEO_TEMPLATE = """
            # MASTER PROMPT — CINEMATIC 3D ANIMATED VIDEO GENERATION

            ## 1. PROJECT INFORMATION

            - **Story Title:** `{{STORY_TITLE}}`
            - **Genre:** `{{GENRE}}`
            - **Target Audience:** `{{TARGET_AUDIENCE}}`
            - **Story Summary:** `{{STORY_SUMMARY}}`
            - **Visual Style:** `{{VISUAL_STYLE}}`
            - **Emotional Tone:** `{{EMOTIONAL_TONE}}`
            - **Target Video Platform:** `{{TARGET_VIDEO_PLATFORM}}`
            - **Generation Mode:** `{{GENERATION_MODE}}`

            ---

            ## 2. GLOBAL VIDEO SETTINGS

            - **Aspect Ratio:** `{{ASPECT_RATIO}}`
            - **Resolution:** `{{RESOLUTION}}`
            - **Frame Rate:** `{{FRAME_RATE}}`
            - **Default Clip Duration:** `{{DEFAULT_CLIP_DURATION}}`
            - **Audio Generation:** `{{AUDIO_ENABLED}}`
            - **Dialogue Language:** `{{DIALOGUE_LANGUAGE}}`
            - **Rendering Style:** `{{RENDERING_STYLE}}`
            - **Camera Style:** `{{CAMERA_STYLE}}`
            - **Motion Style:** `{{MOTION_STYLE}}`
            - **Lighting Style:** `{{LIGHTING_STYLE}}`
            - **Color Palette:** `{{COLOR_PALETTE}}`

            ---

            ## 3. CHARACTER REFERENCES

            ### CHARACTER `{{CHARACTER_ID}}`

            - **Name:** `{{CHARACTER_NAME}}`
            - **Approved Design:** `{{CHARACTER_DESIGN}}`
            - **Reference Image:** `{{CHARACTER_REFERENCE_IMAGE}}`
            - **Physical Appearance:** `{{CHARACTER_APPEARANCE}}`
            - **Clothing:** `{{CHARACTER_CLOTHING}}`
            - **Accessories:** `{{CHARACTER_ACCESSORIES}}`
            - **Personality:** `{{CHARACTER_PERSONALITY}}`
            - **Voice Characteristics:** `{{CHARACTER_VOICE}}`
            - **Emotional State:** `{{CHARACTER_EMOTIONAL_STATE}}`
            - **Scene-Specific Actions:** `{{CHARACTER_ACTIONS}}`

            ---

            ## 4. ASSET REFERENCES

            ### ASSET `{{ASSET_ID}}`

            - **Asset Name:** `{{ASSET_NAME}}`
            - **Asset Category:** `{{ASSET_CATEGORY}}`
            - **Approved Design:** `{{ASSET_DESIGN}}`
            - **Reference Image:** `{{ASSET_REFERENCE_IMAGE}}`
            - **Initial State:** `{{ASSET_INITIAL_STATE}}`
            - **Current State:** `{{ASSET_CURRENT_STATE}}`
            - **Position:** `{{ASSET_POSITION}}`
            - **Interaction Requirements:** `{{ASSET_INTERACTIONS}}`
            - **Continuity Requirements:** `{{ASSET_CONTINUITY}}`

            ---

            ## 5. SCENE INFORMATION

            - **Scene ID:** `{{SCENE_ID}}`
            - **Scene Name:** `{{SCENE_NAME}}`
            - **Scene Type:** `{{SCENE_TYPE}}`
            - **Narrative Purpose:** `{{SCENE_PURPOSE}}`
            - **Emotional Objective:** `{{EMOTIONAL_OBJECTIVE}}`
            - **Previous Scene ID:** `{{PREVIOUS_SCENE_ID}}`
            - **Next Scene ID:** `{{NEXT_SCENE_ID}}`
            - **Location Asset ID:** `{{LOCATION_ASSET_ID}}`
            - **Time of Day:** `{{TIME_OF_DAY}}`
            - **Weather:** `{{WEATHER}}`
            - **Scene Duration:** `{{SCENE_DURATION}}`
            - **Character IDs:** `{{SCENE_CHARACTER_IDS}}`
            - **Asset IDs:** `{{SCENE_ASSET_IDS}}`

            ---

            ## 6. VIDEO GENERATION PROMPT

            ### Visual Style

            `{{SCENE_VISUAL_STYLE}}`

            ### Subject and Characters

            `{{SCENE_SUBJECTS_AND_CHARACTERS}}`

            ### Environment and Assets

            `{{SCENE_ENVIRONMENT_AND_ASSETS}}`

            ### Initial Composition

            `{{INITIAL_COMPOSITION}}`

            ### Action Sequence

            `{{ACTION_SEQUENCE}}`

            ### Facial Expressions and Body Language

            `{{FACIAL_EXPRESSIONS_AND_BODY_LANGUAGE}}`

            ### Dialogue and Lip Synchronization

            `{{DIALOGUE_AND_LIP_SYNC}}`

            ### Camera and Cinematography

            `{{CAMERA_AND_CINEMATOGRAPHY}}`

            ### Lighting and Atmosphere

            `{{LIGHTING_AND_ATMOSPHERE}}`

            ### Motion and Animation

            `{{MOTION_AND_ANIMATION}}`

            ### Ending Composition

            `{{ENDING_COMPOSITION}}`

            ### Audio

            `{{SCENE_AUDIO}}`

            ### Visual Continuity

            `{{VISUAL_CONTINUITY}}`

            ---

            ## 7. TIMING BREAKDOWN

            | Start Time | End Time | Character ID | Action | Facial Expression / Body Language | Camera | Dialogue / Audio |
            |---|---|---|---|---|---|---|
            | `{{SEGMENT_1_START}}` | `{{SEGMENT_1_END}}` | `{{SEGMENT_1_CHARACTER_ID}}` | `{{SEGMENT_1_ACTION}}` | `{{SEGMENT_1_PERFORMANCE}}` | `{{SEGMENT_1_CAMERA}}` | `{{SEGMENT_1_AUDIO}}` |
            | `{{SEGMENT_2_START}}` | `{{SEGMENT_2_END}}` | `{{SEGMENT_2_CHARACTER_ID}}` | `{{SEGMENT_2_ACTION}}` | `{{SEGMENT_2_PERFORMANCE}}` | `{{SEGMENT_2_CAMERA}}` | `{{SEGMENT_2_AUDIO}}` |
            | `{{SEGMENT_3_START}}` | `{{SEGMENT_3_END}}` | `{{SEGMENT_3_CHARACTER_ID}}` | `{{SEGMENT_3_ACTION}}` | `{{SEGMENT_3_PERFORMANCE}}` | `{{SEGMENT_3_CAMERA}}` | `{{SEGMENT_3_AUDIO}}` |

            ---

            ## 8. DIALOGUE SHEET

            | Dialogue ID | Speaker Character ID | Character Name | Exact Dialogue | Emotional Delivery | Start Time | End Time |
            |---|---|---|---|---|---|---|
            | `{{DIALOGUE_ID}}` | `{{SPEAKER_CHARACTER_ID}}` | `{{SPEAKER_NAME}}` | `{{EXACT_DIALOGUE}}` | `{{DIALOGUE_DELIVERY}}` | `{{DIALOGUE_START_TIME}}` | `{{DIALOGUE_END_TIME}}` |

            - **Nonverbal Vocalizations:** `{{NONVERBAL_VOCALIZATIONS}}`
            - **Dialogue Timing:** `{{DIALOGUE_TIMING}}`
            - **Lip Synchronization:** `{{LIP_SYNC_REQUIREMENTS}}`
            - **Silent Character Behavior:** `{{SILENT_CHARACTER_BEHAVIOR}}`

            ---

            ## 9. CAMERA SPECIFICATIONS

            - **Shot Type:** `{{SHOT_TYPE}}`
            - **Camera Angle:** `{{CAMERA_ANGLE}}`
            - **Camera Position:** `{{CAMERA_POSITION}}`
            - **Camera Movement:** `{{CAMERA_MOVEMENT}}`
            - **Lens Characteristics:** `{{LENS_CHARACTERISTICS}}`
            - **Composition:** `{{CAMERA_COMPOSITION}}`
            - **Focus Subject:** `{{FOCUS_SUBJECT}}`
            - **Depth of Field:** `{{DEPTH_OF_FIELD}}`
            - **Screen Direction:** `{{SCREEN_DIRECTION}}`
            - **Camera Transition:** `{{CAMERA_TRANSITION}}`

            ---

            ## 10. AUDIO SPECIFICATIONS

            - **Audio Mode:** `{{AUDIO_MODE}}`
            - **Dialogue:** `{{AUDIO_DIALOGUE}}`
            - **Voice Consistency:** `{{VOICE_CONSISTENCY}}`
            - **Ambient Sound:** `{{AMBIENT_SOUND}}`
            - **Sound Effects:** `{{SOUND_EFFECTS}}`
            - **Background Music:** `{{BACKGROUND_MUSIC}}`
            - **Audio Timing:** `{{AUDIO_TIMING}}`
            - **Audio Continuity:** `{{AUDIO_CONTINUITY}}`

            ---

            ## 11. NEGATIVE PROMPT

            `{{CUSTOM_NEGATIVE_PROMPT}}`

            ### Global Negative Constraints

            - Character identity changes.
            - Inconsistent facial features.
            - Unapproved hairstyle or clothing changes.
            - Inconsistent body proportions.
            - Duplicate characters.
            - Extra limbs or fingers.
            - Distorted anatomy or faces.
            - Unnatural facial expressions or movements.
            - Unintended mouth movement.
            - Incorrect dialogue assignment.
            - Inconsistent environment design.
            - Objects changing shape, color, or material.
            - Unintended object duplication or disappearance.
            - Flickering lighting.
            - Unwanted camera movement.
            - Visual artifacts or glitches.
            - Inconsistent animation style.
            - Unintended text or subtitles.
            - Unapproved logos or watermarks.

            ---

            ## 12. REFERENCE INPUTS

            - **Character Reference Images:** `{{CHARACTER_REFERENCE_IMAGES}}`
            - **Environment Reference Images:** `{{ENVIRONMENT_REFERENCE_IMAGES}}`
            - **Prop Reference Images:** `{{PROP_REFERENCE_IMAGES}}`
            - **Opening Frame Reference:** `{{OPENING_FRAME_REFERENCE}}`
            - **Previous Clip Reference:** `{{PREVIOUS_CLIP_REFERENCE}}`
            - **Ending Frame Reference:** `{{ENDING_FRAME_REFERENCE}}`
            - **Reference Priority:** `{{REFERENCE_PRIORITY}}`

            ---

            ## 13. CONTINUITY REQUIREMENTS

            - **Character Appearance:** `{{CHARACTER_APPEARANCE_CONTINUITY}}`
            - **Character Position:** `{{CHARACTER_POSITION_CONTINUITY}}`
            - **Character Movement:** `{{CHARACTER_MOVEMENT_CONTINUITY}}`
            - **Emotional State:** `{{EMOTIONAL_STATE_CONTINUITY}}`
            - **Environment Layout:** `{{ENVIRONMENT_CONTINUITY}}`
            - **Prop Position and State:** `{{PROP_CONTINUITY}}`
            - **Lighting and Time of Day:** `{{LIGHTING_CONTINUITY}}`
            - **Screen Direction:** `{{SCREEN_DIRECTION_CONTINUITY}}`
            - **Previous Clip Connection:** `{{PREVIOUS_CLIP_CONNECTION}}`
            - **Next Clip Connection:** `{{NEXT_CLIP_CONNECTION}}`
            - **Final Frame Requirements:** `{{FINAL_FRAME_REQUIREMENTS}}`

            ---

            ## 14. OUTPUT REQUIREMENTS

            - **Output Type:** `{{OUTPUT_TYPE}}`
            - **Scene ID:** `{{OUTPUT_SCENE_ID}}`
            - **Output Duration:** `{{OUTPUT_DURATION}}`
            - **Aspect Ratio:** `{{OUTPUT_ASPECT_RATIO}}`
            - **Resolution:** `{{OUTPUT_RESOLUTION}}`
            - **Frame Rate:** `{{OUTPUT_FRAME_RATE}}`
            - **Audio:** `{{OUTPUT_AUDIO}}`
            - **Number of Variations:** `{{NUMBER_OF_VARIATIONS}}`

            ### Required Deliverables

            1. `{{DELIVERABLE_VIDEO_PROMPT}}`
            2. `{{DELIVERABLE_TIMING_BREAKDOWN}}`
            3. `{{DELIVERABLE_DIALOGUE_SHEET}}`
            4. `{{DELIVERABLE_NEGATIVE_PROMPT}}`
            5. `{{DELIVERABLE_REFERENCE_INPUTS}}`
            6. `{{DELIVERABLE_CONTINUITY_REQUIREMENTS}}`
            7. `{{DELIVERABLE_GENERATION_NOTES}}`

            ---

            ## 15. FINAL VALIDATION

            - `{{VALIDATE_STORY_CONTENT}}`
            - `{{VALIDATE_CHARACTER_DESIGNS}}`
            - `{{VALIDATE_ASSET_DESIGNS}}`
            - `{{VALIDATE_ENVIRONMENT_CONTINUITY}}`
            - `{{VALIDATE_ACTION_SEQUENCE}}`
            - `{{VALIDATE_DIALOGUE}}`
            - `{{VALIDATE_SPEAKER_ASSIGNMENTS}}`
            - `{{VALIDATE_TIMING}}`
            - `{{VALIDATE_CAMERA_INSTRUCTIONS}}`
            - `{{VALIDATE_LIGHTING}}`
            - `{{VALIDATE_AUDIO}}`
            - `{{VALIDATE_NEGATIVE_CONSTRAINTS}}`
            - `{{VALIDATE_REFERENCE_INPUTS}}`
            - `{{VALIDATE_CLIP_CONTINUITY}}`
            - `{{VALIDATE_PLATFORM_REQUIREMENTS}}`
            - `{{IDENTIFY_MISSING_INPUTS}}`

            ---

            ## EXECUTION INSTRUCTION

            Using `{{APPROVED_STORY}}`, `{{APPROVED_CHARACTER_DESIGNS}}`, `{{APPROVED_ASSET_LIBRARY}}`, and `{{APPROVED_SCENE_BREAKDOWN}}`, generate the video-generation prompt for `{{TARGET_SCENE_ID}}`.

            Preserve the approved narrative, character identities, asset designs, actions, dialogue, timing, visual style, and continuity.

            Adapt the output to `{{VIDEO_PLATFORM}}` and `{{GENERATION_MODE}}`.

            Return the completed video-generation prompt using the structure defined in this template.

            Do not invent missing story details or claim that a video has been generated when only a prompt has been created.""";

    /** Una escena parseada del output del paso 4: cabecera «SCENE NN» + su texto completo. */
    public record SceneBlock(String id, String text) {
    }

    /** Cabecera de escena: una línea que SOLO contiene «SCENE NN» (formato del paso 4). */
    private static final Pattern SCENE_HEADER_STRICT =
            Pattern.compile("(?m)^\\s*SCENE\\s+(\\d+)\\s*$");

    /** Fallback tolerante: cabeceras con negritas o texto adicional en la línea. */
    private static final Pattern SCENE_HEADER_LOOSE =
            Pattern.compile("(?m)^\\s*\\**\\s*SCENE\\s+(\\d+)");

    /**
     * Parsea el output del paso 4 (escenas) en bloques «SCENE NN»: cada
     * bloque empieza en su cabecera y llega hasta la siguiente. Devuelve la
     * lista VACÍA si el texto no contiene cabeceras reconocibles.
     */
    public static java.util.List<SceneBlock> parseScenes(String scenes) {
        if (scenes == null || scenes.isBlank()) {
            return java.util.List.of();
        }
        java.util.List<SceneBlock> blocks = parseScenesWith(SCENE_HEADER_STRICT, scenes);
        if (!blocks.isEmpty()) {
            return blocks;
        }
        return parseScenesWith(SCENE_HEADER_LOOSE, scenes);
    }

    private static java.util.List<SceneBlock> parseScenesWith(Pattern header, String scenes) {
        java.util.List<int[]> spans = new java.util.ArrayList<>();
        java.util.List<String> ids = new java.util.ArrayList<>();
        Matcher matcher = header.matcher(scenes);
        while (matcher.find()) {
            ids.add("SCENE " + matcher.group(1));
            spans.add(new int[]{matcher.start(), -1});
        }
        java.util.List<SceneBlock> blocks = new java.util.ArrayList<>(spans.size());
        for (int i = 0; i < spans.size(); i++) {
            int end = i + 1 < spans.size() ? spans.get(i + 1)[0] : scenes.length();
            blocks.add(new SceneBlock(ids.get(i), scenes.substring(spans.get(i)[0], end).strip()));
        }
        return blocks;
    }

    /**
     * Ensambla el prompt del paso 5: sustituye IN-PLACE los slots de la APP
     * (título, estilo, idioma, aspecto, duración por defecto, audio y la
     * escena objetivo) y los CUATRO materiales aprobados en la EXECUTION
     * INSTRUCTION — así «Using {{APPROVED_STORY}}…» es literal y el prompt
     * es autocontenido. Antepone el bloque de REFERENCIA OPCIONAL cuando
     * existe y la instrucción de rellenado: el RESTO de placeholders los
     * llena la IA y permanecen verbatim en la plantilla. Devuelve el prompt
     * para el LLM, no la plantilla final.
     */
    public static String buildVideoGenerationPrompt(String title, String story, String designs,
            String assets, String scenes, String targetSceneId, String animationStyle,
            String dialogueLanguage, String aspectRatio, Integer defaultClipSeconds,
            String referenceMaterial) {
        String template = MASTER_VIDEO_TEMPLATE
                .replace("{{STORY_TITLE}}", valueOrDefault(title, "Untitled story"))
                .replace("{{VISUAL_STYLE}}", valueOrDefault(animationStyle,
                        "Not specified — derive it from the approved character designs"))
                .replace("{{DIALOGUE_LANGUAGE}}", valueOrDefault(dialogueLanguage, "Spanish"))
                .replace("{{ASPECT_RATIO}}", valueOrDefault(aspectRatio, "16:9"))
                .replace("{{OUTPUT_ASPECT_RATIO}}", valueOrDefault(aspectRatio, "16:9"))
                .replace("{{DEFAULT_CLIP_DURATION}}", defaultClipSeconds != null
                        && defaultClipSeconds > 0 ? formatDuration(defaultClipSeconds) : "10 seconds")
                .replace("{{AUDIO_ENABLED}}", "Yes")
                .replace("{{TARGET_SCENE_ID}}", valueOrDefault(targetSceneId, "SCENE 01"))
                .replace("{{APPROVED_STORY}}", materialsOrNote(story,
                        "No approved story was provided"))
                .replace("{{APPROVED_CHARACTER_DESIGNS}}", materialsOrNote(designs,
                        "No approved character designs were provided"))
                .replace("{{APPROVED_ASSET_LIBRARY}}", materialsOrNote(assets,
                        "(No approved asset library was generated — derive environment and prop "
                                + "references from the approved scene breakdown and story, and flag "
                                + "any missing asset in the Final Validation)"))
                .replace("{{APPROVED_SCENE_BREAKDOWN}}", materialsOrNote(scenes,
                        "No approved scene breakdown was provided"));

        StringBuilder prompt = new StringBuilder();
        if (!isBlank(referenceMaterial)) {
            prompt.append("Here is the optional REFERENCE MATERIAL provided by the user. ")
                    .append("Use it as inspiration and context where it fits the approved materials; ")
                    .append("never contradict the approved story, character designs, asset library ")
                    .append("or scene breakdown, and never copy copyrighted text verbatim:\n\n")
                    .append(referenceMaterial.strip())
                    .append("\n\n---\n\n");
        }
        prompt.append("Fill in the following MASTER PROMPT template using ONLY the materials ")
                .append("embedded in it. Replace every remaining {{PLACEHOLDER}} with its exact ")
                .append("content derived from those materials. Repeat the CHARACTER and ASSET ")
                .append("reference blocks once for each character and asset that appears in the ")
                .append("target scene, and add as many TIMING BREAKDOWN and DIALOGUE SHEET rows ")
                .append("as the scene needs. Output ONLY the completed template (sections 1 to 15 ")
                .append("fully filled, exactly as structured); do NOT include the EXECUTION ")
                .append("INSTRUCTION section in your output:\n\n")
                .append(template.stripTrailing());
        return prompt.toString();
    }

    /** Material embebido o, si falta, la nota que guía a la IA sin romper la plantilla. */
    private static String materialsOrNote(String material, String note) {
        return isBlank(material) ? note : material.strip();
    }

    // ═══════════════════════ Helpers comunes ═══════════════════════

    /**
     * Formatea una duración en segundos para las plantillas: 60 → "1 minute",
     * 120 → "2 minutes", 90 → "90 seconds", 45 → "45 seconds".
     */
    public static String formatDuration(int seconds) {
        if (seconds >= 60 && seconds % 60 == 0) {
            long minutes = seconds / 60;
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        return seconds + " seconds";
    }

    /** Sustituye el placeholder por el valor; si va vacío, elimina la LÍNEA completa que lo contiene. */
    private static String replaceOrDropLine(String template, String placeholder, String value) {
        if (!isBlank(value)) {
            return template.replace(placeholder, value.trim());
        }
        return template.replaceAll("(?m)^.*" + Pattern.quote(placeholder) + ".*\\R?", "");
    }

    /** Sustituye SOLO la primera ocurrencia del placeholder (placeholders posicionales). */
    private static String substitutePositional(String template, String placeholder, String value) {
        if (isBlank(value)) {
            return template.replaceAll("(?m)^.*" + Pattern.quote(placeholder) + ".*\\R?", "");
        }
        return template.replaceFirst(Pattern.quote(placeholder), value.trim());
    }

    private static String pacingPercent(Integer value, int fallback) {
        return String.valueOf(value != null && value >= 0 && value <= 100 ? value : fallback);
    }

    private static String valueOrDefault(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
