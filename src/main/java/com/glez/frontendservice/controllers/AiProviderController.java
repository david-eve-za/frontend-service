package com.glez.frontendservice.controllers;

import com.glez.frontendservice.model.AiProviderConfig;
import com.glez.frontendservice.services.AiProviderSettingsService;
import com.glez.frontendservice.services.NvidiaAiService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/ai-providers")
@RequiredArgsConstructor
@Tag(name = "AI Providers", description = "Selección del proveedor de IA por caso de uso: traducción de libros "
        + "y Asistente de Historias pueden usar el pool de NVIDIA (cloud con fallback local) o el pool 100% local "
        + "de Ollama. Cambiable en caliente desde la página de Configuración; persiste entre reinicios.")
public class AiProviderController {

    private final AiProviderSettingsService settings;
    private final NvidiaAiService ai;

    @Operation(summary = "Selección actual de proveedores",
            description = "Devuelve el proveedor activo por caso de uso y los modelos de cada pool "
                    + "(nvidia: cloud con fallbacks; ollama: local).")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Estado de la selección"))
    @GetMapping
    public ResponseEntity<Map<String, Object>> get() {
        return ResponseEntity.ok(Map.of(
                "translationProvider", settings.translationProvider(),
                "storyProvider", settings.storyProvider(),
                "nvidiaModels", ai.poolModelNames(),
                "ollamaModels", ai.ollamaPoolModelNames()));
    }

    @Operation(summary = "Actualiza la selección de proveedores",
            description = "Payload parcial {translationProvider?, storyProvider?} con valores nvidia|ollama. "
                    + "Aplica a la siguiente llamada sin reiniciar; null mantiene el valor actual.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Selección actualizada y persistida"),
            @ApiResponse(responseCode = "400", description = "Proveedor inválido (solo nvidia|ollama)")
    })
    @PutMapping
    public ResponseEntity<?> update(@RequestBody Map<String, String> body) {
        try {
            AiProviderConfig updated = settings.update(
                    body == null ? null : body.get("translationProvider"),
                    body == null ? null : body.get("storyProvider"));
            return ResponseEntity.ok(Map.of(
                    "translationProvider", updated.getTranslationProvider(),
                    "storyProvider", updated.getStoryProvider(),
                    "nvidiaModels", ai.poolModelNames(),
                    "ollamaModels", ai.ollamaPoolModelNames()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
