package com.glez.frontendservice.controllers;

import com.glez.frontendservice.dtos.NovelSummaryDto;
import com.glez.frontendservice.dtos.NovelsCatalogSyncStatus;
import com.glez.frontendservice.dtos.VolumeDto;
import com.glez.frontendservice.dtos.VolumePreparedDto;
import com.glez.frontendservice.services.NovelsCatalogQueryService;
import com.glez.frontendservice.services.NovelsCatalogSyncService;
import com.glez.frontendservice.services.NovelsVolumeTextService;
import com.glez.frontendservice.services.VolumeAudioDownloadService;
import com.glez.frontendservice.services.anilist.NovelAnilistEnrichmentService;
import com.glez.frontendservice.services.anilist.NovelAnilistEnrichmentService.AniListSearchPreview;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/novels-manager")
@RequiredArgsConstructor
@Tag(name = "Novels Manager", description = "Catálogo de novelas sincronizado desde elscione (solo metadatos) "
        + "y acciones de pipeline por volumen, con enriquecimiento AniList")
public class NovelsManagerController {

    private final NovelsCatalogQueryService catalogQueryService;
    private final NovelsCatalogSyncService catalogSyncService;
    private final NovelsVolumeTextService volumeTextService;
    private final VolumeAudioDownloadService audioDownloadService;
    private final NovelAnilistEnrichmentService anilistEnrichmentService;

    @Operation(summary = "Página de obras del catálogo (nivel 1 de la tabla anidada)")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Página de obras con número de volúmenes"))
    @GetMapping("/catalog")
    public ResponseEntity<PagedModel<NovelSummaryDto>> getCatalog(
            @Parameter(description = "Filtro por título (ignora mayúsculas)")
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        return ResponseEntity.ok(catalogQueryService.findNovels(search, page, size));
    }

    @Operation(summary = "Obtiene todas las novelas sin paginación (para enriquecimiento masivo)")
    @GetMapping("/catalog/all")
    public ResponseEntity<List<NovelSummaryDto>> getAllNovels() {
        return ResponseEntity.ok(catalogQueryService.findAllNovels());
    }

    @Operation(summary = "Obtiene todos los IDs de novelas (para enriquecimiento masivo)")
    @GetMapping("/novels/ids")
    public ResponseEntity<List<UUID>> getAllNovelIds() {
        return ResponseEntity.ok(catalogQueryService.findAllNovelIds());
    }

    @Operation(summary = "Obtiene una novela individual por ID (con campos AniList)")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Novela con campos de enriquecimiento AniList"))
    @GetMapping("/novels/{novelId}")
    public ResponseEntity<NovelSummaryDto> getNovel(@PathVariable UUID novelId) {
        return catalogQueryService.findByIdAsSummary(novelId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Volúmenes de una obra con estados derivados y botones habilitados (nivel 2)")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Lista de volúmenes de la obra"))
    @GetMapping("/novels/{novelId}/volumes")
    public ResponseEntity<List<VolumeDto>> getNovelVolumes(@PathVariable UUID novelId) {
        return ResponseEntity.ok(catalogQueryService.findVolumes(novelId));
    }

    @Operation(summary = "Enriquece una novela buscando en AniList por su título")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Resultado del enriquecimiento"),
            @ApiResponse(responseCode = "404", description = "Novela no encontrada")
    })
    @PostMapping("/novels/{novelId}/enrich-anilist")
    public ResponseEntity<NovelAnilistEnrichmentService.NovelEnrichmentResult> enrichNovelAnilist(@PathVariable UUID novelId) {
        var result = anilistEnrichmentService.enrichNovel(novelId.toString());
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Enriquecimiento en lote: busca y enriquece múltiples novelas en AniList")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Resultado del enriquecimiento en lote"))
    @PostMapping("/novels/enrich-batch-anilist")
    public ResponseEntity<NovelAnilistEnrichmentService.BatchEnrichmentResult> enrichBatchAnilist(@RequestBody List<String> novelIds) {
        var result = anilistEnrichmentService.enrichBatch(novelIds);
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Enriquece TODAS las novelas con AniList")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Resultado del enriquecimiento masivo"))
    @PostMapping("/novels/enrich-all-anilist")
    public ResponseEntity<NovelAnilistEnrichmentService.BatchEnrichmentResult> enrichAllAnilist() {
        var result = anilistEnrichmentService.enrichAll();
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Traduce todas las sinopsis/descripciones de AniList al español")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Lista de sinopsis traducidas"))
    @GetMapping("/novels/translate-synopses")
    public ResponseEntity<List<NovelAnilistEnrichmentService.TranslatedSynopsis>> translateAllSynopses() {
        var result = anilistEnrichmentService.translateAllSynopsesToSpanish();
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Previsualiza resultados de búsqueda en AniList sin guardar")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Lista de previews de AniList"))
    @GetMapping("/novels/{novelId}/anilist-preview")
    public ResponseEntity<List<NovelAnilistEnrichmentService.AniListSearchPreview>> previewAnilist(@PathVariable UUID novelId) {
        return catalogQueryService.findById(novelId)
                .map(novel -> ResponseEntity.ok(anilistEnrichmentService.previewSearch(novel.getTitle())))
                .orElse(ResponseEntity.notFound().build());
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

    @Operation(summary = "Descarga el audiolibro generado del volumen",
            description = "Un volumen con audio de bloques semánticos descarga un ZIP construido "
                    + "AL VUELO con los mp3 de sus bloques en orden de lectura (un volumen completo "
                    + "puede ocupar cientos de MB: se strema, nunca se materializa en memoria). "
                    + "El audio legado (texto completo) descarga el mp3 directo. El path NUNCA viene "
                    + "de la petición: se resuelve por el volumen → Book enlazado → puntero de la BD.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "El audiolibro (mp3 o ZIP) en streaming"),
            @ApiResponse(responseCode = "404", description = "Volumen/Book inexistentes o sin audio generado"),
            @ApiResponse(responseCode = "409", description = "El audio apuntado por la BD ya no existe en disco")
    })
    @GetMapping("/volumes/{volumeId}/audio/download")
    public ResponseEntity<StreamingResponseBody> downloadVolumeAudio(@PathVariable UUID volumeId) {
        try {
            VolumeAudioDownloadService.AudioDownload download = audioDownloadService.resolve(volumeId);
            MediaType contentType = download.zip() ? MediaType.parseMediaType("application/zip")
                    : MediaType.parseMediaType("audio/mpeg");
            // filename* con UTF-8: los nombres de libros llevan espacios y
            // corchetes que rompen el header plano.
            HttpHeaders headers = new HttpHeaders();
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename(download.filename(), StandardCharsets.UTF_8)
                    .build());
            return ResponseEntity.ok().headers(headers).contentType(contentType)
                    .body(out -> audioDownloadService.writeTo(download, out));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body(out -> out.write(("{\"error\":\"" + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body(out -> out.write(("{\"error\":\"" + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Operation(summary = "Ejecuta manualmente la limpieza de novelas contenedoras y duplicados sueltos")
    @PostMapping("/cleanup")
    public ResponseEntity<Map<String, Object>> runCleanup() {
        try {
            catalogSyncService.runManualCleanup();
            return ResponseEntity.ok(Map.of("success", true, "message", "Limpieza completada"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }
}