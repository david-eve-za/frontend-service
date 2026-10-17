package com.glez.frontendservice.controllers;

import com.glez.frontendservice.dtos.StoryPromptRequest;
import com.glez.frontendservice.dtos.VideoStoryProjectSummary;
import com.glez.frontendservice.dtos.WizardAssetsStepRequest;
import com.glez.frontendservice.dtos.WizardDesignStepRequest;
import com.glez.frontendservice.dtos.WizardScenesStepRequest;
import com.glez.frontendservice.dtos.WizardVideoStepRequest;
import com.glez.frontendservice.model.VideoStoryProject;
import com.glez.frontendservice.repository.VideoStoryProjectRepository;
import com.glez.frontendservice.services.VideoStoryWizardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/video-story-projects")
@RequiredArgsConstructor
@Tag(name = "Video Story Wizard", description = "Historias en video, ASISTENTE: la timeline completa encadenada "
        + "por la IA — paso 1 historia, paso 2 diseños de personajes, paso 3 biblioteca de assets, paso 4 escenas "
        + "y paso 5 (opcional) plantilla de generación de video. La salida de cada paso alimenta al siguiente.")
public class VideoStoryWizardController {

    private static final Logger log = LoggerFactory.getLogger(VideoStoryWizardController.class);

    private final VideoStoryWizardService wizard;
    private final VideoStoryProjectRepository projectRepository;

    @Operation(summary = "Lista los proyectos del asistente", description = "Resúmenes (sin outputs pesados), más recientes primero.")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Proyectos del asistente"))
    @GetMapping
    public ResponseEntity<List<VideoStoryProjectSummary>> list() {
        return ResponseEntity.ok(projectRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(p -> new VideoStoryProjectSummary(
                        p.getId(), p.getTitle(),
                        p.getStory() != null, p.getDesigns() != null,
                        p.getAssets() != null, p.getScenes() != null,
                        p.getVideoPrompt() != null,
                        p.getCreatedAt()))
                .toList());
    }

    @Operation(summary = "Detalle completo de un proyecto", description = "El proyecto con todos los outputs de la cadena.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Proyecto completo"),
            @ApiResponse(responseCode = "404", description = "Proyecto desconocido")
    })
    @GetMapping("/{id}")
    public ResponseEntity<VideoStoryProject> get(@PathVariable UUID id) {
        return projectRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "PASO 1 — crea el proyecto y genera la HISTORIA con la IA",
            description = "Rellena la plantilla narrativa con el formulario, llama a la IA (rotación NVIDIA con "
                    + "fallback a Ollama; puede tardar varios minutos) y crea el proyecto con la historia generada.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Proyecto creado con la historia generada"),
            @ApiResponse(responseCode = "400", description = "Faltan campos obligatorios del formulario"),
            @ApiResponse(responseCode = "502", description = "La IA falló (todos los modelos caídos, truncado o respuesta vacía)")
    })
    @PostMapping
    public ResponseEntity<?> create(@RequestBody StoryPromptRequest request) {
        if (!com.glez.frontendservice.services.VideoStoryPromptFactory.isValidStoryRequest(request)) {
            log.warn("[wizard] HTTP 400 en paso 1 (historia): faltan campos obligatorios del formulario");
            return ResponseEntity.badRequest().body(Map.of("message",
                    "Faltan campos obligatorios: título, género, audiencia, tono, conflicto, "
                            + "elemento sin resolver, idioma, duración del video y plataforma."));
        }
        try {
            return ResponseEntity.ok(wizard.createProjectWithStory(request));
        } catch (IllegalStateException e) {
            return aiFailure(e);
        }
    }

    @Operation(summary = "PASO 2 — genera los DISEÑOS DE PERSONAJES sobre la historia del proyecto",
            description = "Incrusta la historia en la plantilla de diseño y llama a la IA. Requiere el paso 1.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Diseños generados y persistidos"),
            @ApiResponse(responseCode = "404", description = "Proyecto desconocido"),
            @ApiResponse(responseCode = "409", description = "El paso 1 (historia) aún no se ha ejecutado"),
            @ApiResponse(responseCode = "502", description = "La IA falló")
    })
    @PostMapping("/{id}/designs")
    public ResponseEntity<?> generateDesigns(@PathVariable UUID id,
            @RequestBody WizardDesignStepRequest request) {
        return runStep(() -> wizard.generateDesigns(id, request));
    }

    @Operation(summary = "PASO 3 — genera la BIBLIOTECA DE ASSETS (entornos, props, tecnología, documentos…)",
            description = "Rellena la plantilla MASTER de assets con la historia y los diseños del proyecto "
                    + "(in-place) y llama a la IA. Requiere el paso 2. Todos los parámetros son opcionales: "
                    + "el estilo hereda el del paso 2 y el resto se deriva de la historia según la propia plantilla.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Biblioteca de assets generada y persistida"),
            @ApiResponse(responseCode = "404", description = "Proyecto desconocido"),
            @ApiResponse(responseCode = "409", description = "El paso 2 (diseños) aún no se ha ejecutado"),
            @ApiResponse(responseCode = "502", description = "La IA falló (incluye truncado por el tope de salida — reintenta o usa menos detalle)")
    })
    @PostMapping("/{id}/assets")
    public ResponseEntity<?> generateAssets(@PathVariable UUID id,
            @RequestBody WizardAssetsStepRequest request) {
        return runStep(() -> wizard.generateAssets(id, request));
    }

    @Operation(summary = "PASO 4 — genera las ESCENAS sobre historia y diseños del proyecto",
            description = "Incrusta historia y diseños en la plantilla de escenas y llama a la IA. Requiere el paso 2.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Escenas generadas y persistidas"),
            @ApiResponse(responseCode = "404", description = "Proyecto desconocido"),
            @ApiResponse(responseCode = "409", description = "El paso 2 (diseños) aún no se ha ejecutado"),
            @ApiResponse(responseCode = "502", description = "La IA falló")
    })
    @PostMapping("/{id}/scenes")
    public ResponseEntity<?> generateScenes(@PathVariable UUID id,
            @RequestBody WizardScenesStepRequest request) {
        return runStep(() -> wizard.generateScenes(id, request));
    }

    @Operation(summary = "PASO 4 (opcional) — transporta una escena a la PLANTILLA DE GENERACIÓN DE VIDEO",
            description = "Pide a la IA rellenar la plantilla de video con la escena pegada (usa por defecto el "
                    + "estilo del paso 2). Requiere el paso 3. Se puede repetir para otra escena: sobrescribe.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Plantilla de video generada y persistida"),
            @ApiResponse(responseCode = "404", description = "Proyecto desconocido"),
            @ApiResponse(responseCode = "409", description = "El paso 3 (escenas) aún no se ha ejecutado"),
            @ApiResponse(responseCode = "502", description = "La IA falló")
    })
    @PostMapping("/{id}/video")
    public ResponseEntity<?> generateVideo(@PathVariable UUID id,
            @RequestBody WizardVideoStepRequest request) {
        return runStep(() -> wizard.generateVideoPrompt(id, request));
    }

    @Operation(summary = "Descarga el STORYBOARD completo en un solo documento",
            description = "Ensambla historia, diseños, assets, escenas y plantilla de video en un único "
                    + "markdown listo para descargar (solo las secciones con contenido).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Documento markdown del storyboard"),
            @ApiResponse(responseCode = "404", description = "Proyecto desconocido")
    })
    @GetMapping("/{id}/storyboard")
    public ResponseEntity<byte[]> storyboard(@PathVariable UUID id) {
        if (!projectRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        String document = wizard.buildStoryboardDocument(id);
        // byte[] UTF-8 explícito: el StringHttpMessageConverter usa
        // ISO-8859-1 por defecto y corrompería el español del storyboard.
        byte[] body = document.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String slug = "storyboard-"
                + slugOf(projectRepository.findById(id).map(p -> p.getTitle()).orElse("proyecto"));
        return ResponseEntity.ok()
                .header("Content-Type", "text/markdown; charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=\"" + slug + ".md\"")
                .body(body);
    }

    @Operation(summary = "Elimina un proyecto del asistente")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Proyecto eliminado"),
            @ApiResponse(responseCode = "404", description = "Proyecto desconocido")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        if (!projectRepository.existsById(id)) {
            log.warn("[wizard] HTTP 404 al eliminar: proyecto {} no encontrado", id);
            return ResponseEntity.notFound().build();
        }
        projectRepository.deleteById(id);
        log.info("[wizard] Proyecto {} eliminado de la biblioteca", id);
        return ResponseEntity.noContent().build();
    }

    /** Slug seguro para el nombre del archivo del storyboard. */
    private static String slugOf(String title) {
        return title.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    // ═══════════════════════ Helpers ═══════════════════════

    /** Ejecuta un paso del wizard traduciendo los fallos a códigos HTTP claros. */
    private ResponseEntity<?> runStep(java.util.function.Supplier<VideoStoryProject> step) {
        try {
            return ResponseEntity.ok(step.get());
        } catch (IllegalArgumentException e) {
            // "Proyecto no encontrado" es 404; el resto de validaciones, 400.
            if (e.getMessage() != null && e.getMessage().contains("no encontrado")) {
                log.warn("[wizard] HTTP 404 en paso: {}", e.getMessage());
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
            }
            log.warn("[wizard] HTTP 400 en paso: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            // Falta un paso previo o ya hay una generación en curso para el
            // proyecto (doble disparo) → conflicto de estado del wizard. El
            // fallo de la IA ya quedó loggeado como ERROR en el servicio (con
            // MDC del proyecto); aquí solo se registra el resultado HTTP.
            if (e.getMessage() != null
                    && (e.getMessage().contains("ejecuta el paso")
                            || e.getMessage().contains("generación en curso"))) {
                log.warn("[wizard] HTTP 409 en paso: {}", e.getMessage());
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
            }
            log.warn("[wizard] HTTP 502 en paso: {}", e.getMessage());
            return aiFailure(e);
        }
    }

    private ResponseEntity<Map<String, String>> aiFailure(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("message", e.getMessage() == null ? "La IA falló sin mensaje." : e.getMessage()));
    }
}
