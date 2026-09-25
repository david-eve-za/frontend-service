package com.glez.frontendservice.controllers;

import com.glez.frontendservice.model.Block;
import com.glez.frontendservice.model.BlockSaveRequest;
import com.glez.frontendservice.services.BookProcessingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/blocks")
@Tag(name = "Semantic Blocks", description = "Operations for saving semantic blocks (Prologue / Chapter / Epilogue) of a document")
@RequiredArgsConstructor
public class BlockController {

    private final BookProcessingService bookProcessingService;

    @Operation(summary = "Save semantic blocks of a document", description = "Receives the raw text with blockML tags and the parsed semantic blocks, updates the document text and persists the blocks.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Blocks saved successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = Map.class))),
            @ApiResponse(responseCode = "400", description = "Invalid payload: missing documentId, invalid block type or malformed UUID", content = @Content),
            @ApiResponse(responseCode = "404", description = "Document not found", content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PostMapping
    public ResponseEntity<Map<String, Object>> saveBlocks(@RequestBody BlockSaveRequest request) {
        Map<String, Object> response = new HashMap<>();

        if (request.documentId() == null || request.documentId().isBlank()) {
            response.put("error", "documentId is required.");
            return ResponseEntity.badRequest().body(response);
        }

        final UUID bookId;
        try {
            bookId = UUID.fromString(request.documentId());
        } catch (IllegalArgumentException e) {
            response.put("error", "documentId must be a valid UUID.");
            return ResponseEntity.badRequest().body(response);
        }

        if (request.blocks() == null) {
            response.put("error", "blocks array is required.");
            return ResponseEntity.badRequest().body(response);
        }

        for (BlockSaveRequest.BlockItem item : request.blocks()) {
            if (item.type() == null) {
                response.put("error", "Each block must have a type.");
                return ResponseEntity.badRequest().body(response);
            }
            try {
                Block.BlockType.valueOf(item.type().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                response.put("error", "Invalid block type: " + item.type() + ". Allowed values: prologue, chapter, epilogue.");
                return ResponseEntity.badRequest().body(response);
            }
        }

        try {
            int saved = bookProcessingService.saveBlocks(bookId, request.rawText(), request.blocks());
            response.put("message", "Blocks saved successfully.");
            response.put("blocksSaved", saved);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("error", e.getMessage());
            return ResponseEntity.status(404).body(response);
        } catch (Exception e) {
            response.put("error", "Error saving blocks: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
