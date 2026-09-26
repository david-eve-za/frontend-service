package com.glez.frontendservice.controllers;

import com.glez.frontendservice.dtos.NovelSummaryDto;
import com.glez.frontendservice.dtos.NovelsCatalogSyncStatus;
import com.glez.frontendservice.dtos.VolumeDto;
import com.glez.frontendservice.dtos.VolumePreparedDto;
import com.glez.frontendservice.services.NovelsCatalogQueryService;
import com.glez.frontendservice.services.NovelsCatalogSyncService;
import com.glez.frontendservice.services.NovelsVolumeTextService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/novels-manager")
@RequiredArgsConstructor
@Tag(name = "Novels Manager", description = "Catálogo de novelas sincronizado desde elscione (solo metadatos) "
        + "y acciones de pipeline por volumen")
public class NovelsManagerController {

    private final NovelsCatalogQueryService catalogQueryService;
    private final NovelsCatalogSyncService catalogSyncService;
    private final NovelsVolumeTextService volumeTextService;

    @Operation(summary = "Página de obras del catálogo (nivel 1 de la tabla anidada)")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Página de obras con número de volúmenes"))
    @GetMapping("/catalog")
    public ResponseEntity<Page<NovelSummaryDto>> getCatalog(
            @Parameter(description = "Filtro por título (ignora mayúsculas)")
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        return ResponseEntity.ok(catalogQueryService.findNovels(search, page, size));
    }

    @Operation(summary = "Volúmenes de una obra con estados derivados y botones habilitados (nivel 2)")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Lista de volúmenes de la obra"))
    @GetMapping("/novels/{novelId}/volumes")
    public ResponseEntity<List<VolumeDto>> getNovelVolumes(@PathVariable UUID novelId) {
        return ResponseEntity.ok(catalogQueryService.findVolumes(novelId));
    }

    @Operation(summary = "Dispara manualmente la sincronización de metadatos del catálogo")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Sincronización iniciada"),
            @ApiResponse(responseCode = "409", description = "Ya hay una sincronización en curso")
    })
    @PostMapping("/sync")
    public ResponseEntity<NovelsCatalogSyncStatus> startSync() {
        try {
            return ResponseEntity.accepted().body(catalogSyncService.startSync());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(catalogSyncService.getStatus());
        }
    }

    @Operation(summary = "Estado de la última sincronización de metadatos")
    @GetMapping("/sync/status")
    public ResponseEntity<NovelsCatalogSyncStatus> getSyncStatus() {
        return ResponseEntity.ok(catalogSyncService.getStatus());
    }

    @Operation(summary = "Cancela la sincronización de metadatos en curso")
    @PostMapping("/sync/cancel")
    public ResponseEntity<Map<String, Object>> cancelSync() {
        boolean cancelled = catalogSyncService.cancel();
        return ResponseEntity.ok(Map.of("cancelled", cancelled));
    }

    @Operation(summary = "Prepara el texto de un volumen (archivo local o descarga on-demand), "
            + "crea el Book del pipeline y lo enlaza; el split se hace luego en el wizard")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Volumen preparado y enlazado a un Book"),
            @ApiResponse(responseCode = "404", description = "El volumen no existe"),
            @ApiResponse(responseCode = "409", description = "El volumen ya tiene un Book enlazado")
    })
    @PostMapping("/volumes/{volumeId}/prepare-text")
    public ResponseEntity<Object> prepareVolumeText(@PathVariable UUID volumeId) {
        try {
            return ResponseEntity.ok(volumeTextService.prepareText(volumeId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(e.getMessage() != null && e.getMessage().contains("not found")
                    ? 404 : 400).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
