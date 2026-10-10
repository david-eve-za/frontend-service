package com.glez.frontendservice.controllers;

import com.glez.frontendservice.dtos.ModelSpecs;
import com.glez.frontendservice.services.ModelInspectorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
@Tag(name = "Model Catalog", description = "Investigación de modelos: especificaciones reales (Ollama) y disponibilidad "
        + "online del pool remoto de NVIDIA, consultadas ANTES de usar un modelo")
public class ModelCatalogController {

    private final ModelInspectorService inspector;

    private final com.glez.frontendservice.services.NvidiaAiService nvidiaAiService;

    @Operation(summary = "Investiga todos los modelos: pool de NVIDIA + modelos locales de Ollama",
            description = "Para cada modelo del pool de rotación de NVIDIA: estado online del proveedor (sonda "
                    + "corta con caché) y verificación de que existe en el catálogo publicado. Para cada modelo "
                    + "local de Ollama: especificaciones REALES vía /api/show (longitud de contexto, número de "
                    + "parámetros, cuantización, familia y capacidades — un 'thinking' presente confirma que el "
                    + "modelo razona y consume parte del presupuesto de salida).")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Catálogo investigado"))
    @GetMapping
    public ResponseEntity<Map<String, Object>> catalog(
            @RequestParam(name = "ollamaModel", required = false, defaultValue = "gpt-oss:20b")
            String ollamaModel) {
        List<ModelSpecs> catalog = new ArrayList<>();

        // Pool remoto de NVIDIA: sonda de vida + verificación de catálogo.
        // La API no expone contexto/salida por modelo: el presupuesto se
        // dimensiona con las heurísticas documentadas (source=CONFIGURED).
        List<String> pool = nvidiaAiService.poolModelNames();
        boolean nvidiaOnline = inspector.isNvidiaOnline();
        List<String> catalogIds = nvidiaOnline ? inspector.nvidiaCatalogIds() : null;
        for (String model : pool) {
            catalog.add(new ModelSpecs(
                    model, "NVIDIA", nvidiaOnline, true, false,
                    catalogIds == null ? false : catalogIds.contains(model),
                    null, null, null, null, List.of(), "CONFIGURED"));
        }

        // Modelo local de Ollama: especificaciones REALES (100% disponible).
        if (ollamaModel != null && !ollamaModel.isBlank()) {
            catalog.add(inspector.inspectOllamaModel(ollamaModel));
        }

        return ResponseEntity.ok(Map.of(
                "catalog", catalog,
                "nvidiaOnline", nvidiaOnline,
                "ollamaOnline", inspector.isOllamaOnline()));
    }
}
