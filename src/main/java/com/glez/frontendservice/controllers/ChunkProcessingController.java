package com.glez.frontendservice.controllers;

import gon.cue.services.BookProcessingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/chunks")
@Tag(name = "Chunk Processing", description = "Operations related to individual text chunks")
@RequiredArgsConstructor
public class ChunkProcessingController {

    private final BookProcessingService bookProcessingService;

    @Operation(summary = "Translate a specific chunk", description = "Translates a single text chunk by its ID. Allows re-translation.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Chunk translated successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = Map.class))),
            @ApiResponse(responseCode = "400", description = "Chunk has no original text or translation failed", content = @Content),
            @ApiResponse(responseCode = "404", description = "Chunk not found", content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PostMapping("/{chunkId}/translate")
    public ResponseEntity<Map<String, Object>> translateChunk(
            @Parameter(description = "ID of the chunk to translate", required = true)
            @PathVariable UUID chunkId) {
        Map<String, Object> response = new HashMap<>();
        try {
            bookProcessingService.translateChunk(chunkId);
            response.put("message", "Chunk " + chunkId + " translated successfully.");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            response.put("error", "Error translating chunk: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
