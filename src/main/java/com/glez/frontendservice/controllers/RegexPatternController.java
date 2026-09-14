package com.glez.frontendservice.controllers;

import com.glez.frontendservice.model.RegexPattern;
import com.glez.frontendservice.services.RegexPatternService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/regex-patterns")
@Tag(name = "Regex Patterns", description = "Operations for managing regex patterns used in text cleaning")
@RequiredArgsConstructor
public class RegexPatternController {

    private final RegexPatternService regexPatternService;

    @Operation(summary = "Get all regex patterns", description = "Retrieves a paginated list of all regex patterns")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved patterns",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = Page.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @GetMapping
    public ResponseEntity<Page<RegexPattern>> getAllPatterns(
            @PageableDefault(page = 0, size = 20) Pageable pageable) {
        Page<RegexPattern> patterns = regexPatternService.getAllPatterns(pageable);
        return ResponseEntity.ok(patterns);
    }

    @Operation(summary = "Get all enabled regex patterns", description = "Retrieves all enabled regex patterns ordered by orderIndex")
    @GetMapping("/enabled")
    public ResponseEntity<List<RegexPattern>> getEnabledPatterns() {
        List<RegexPattern> patterns = regexPatternService.getEnabledPatterns();
        return ResponseEntity.ok(patterns);
    }

    @Operation(summary = "Get enabled patterns by type", description = "Retrieves enabled patterns filtered by type")
    @GetMapping("/enabled/type/{type}")
    public ResponseEntity<List<RegexPattern>> getEnabledPatternsByType(
            @PathVariable RegexPattern.PatternType type) {
        List<RegexPattern> patterns = regexPatternService.getEnabledPatternsByType(type);
        return ResponseEntity.ok(patterns);
    }

    @Operation(summary = "Get regex pattern by ID", description = "Retrieves a single regex pattern by its ID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved pattern",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = RegexPattern.class))),
            @ApiResponse(responseCode = "404", description = "Pattern not found", content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @GetMapping("/{id}")
    public ResponseEntity<RegexPattern> getPatternById(
            @Parameter(description = "ID of the pattern") @PathVariable UUID id) {
        return regexPatternService.getPatternById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Create a new regex pattern", description = "Creates a new regex pattern for text cleaning")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Pattern created successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = RegexPattern.class))),
            @ApiResponse(responseCode = "400", description = "Invalid pattern or name already exists", content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PostMapping
    public ResponseEntity<RegexPattern> createPattern(@RequestBody RegexPattern pattern) {
        RegexPattern created = regexPatternService.createPattern(pattern);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Update an existing regex pattern", description = "Updates an existing regex pattern")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Pattern updated successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = RegexPattern.class))),
            @ApiResponse(responseCode = "400", description = "Invalid pattern or name already exists", content = @Content),
            @ApiResponse(responseCode = "404", description = "Pattern not found", content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PutMapping("/{id}")
    public ResponseEntity<RegexPattern> updatePattern(
            @Parameter(description = "ID of the pattern to update") @PathVariable UUID id,
            @RequestBody RegexPattern pattern) {
        RegexPattern updated = regexPatternService.updatePattern(id, pattern);
        return ResponseEntity.ok(updated);
    }

    @Operation(summary = "Toggle pattern enabled/disabled", description = "Toggles the enabled status of a regex pattern")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Pattern toggled successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = RegexPattern.class))),
            @ApiResponse(responseCode = "404", description = "Pattern not found", content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PatchMapping("/{id}/toggle")
    public ResponseEntity<RegexPattern> togglePattern(
            @Parameter(description = "ID of the pattern to toggle") @PathVariable UUID id) {
        RegexPattern toggled = regexPatternService.togglePattern(id);
        return ResponseEntity.ok(toggled);
    }

    @Operation(summary = "Delete a regex pattern", description = "Deletes a regex pattern by its ID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Pattern deleted successfully", content = @Content),
            @ApiResponse(responseCode = "404", description = "Pattern not found", content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePattern(
            @Parameter(description = "ID of the pattern to delete") @PathVariable UUID id) {
        regexPatternService.deletePattern(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Validate regex pattern syntax", description = "Validates if a regex pattern is syntactically correct")
    @PostMapping("/validate")
    public ResponseEntity<Map<String, Object>> validatePattern(@RequestBody Map<String, String> request) {
        String pattern = request.get("pattern");
        Map<String, Object> response = new java.util.HashMap<>();
        try {
            java.util.regex.Pattern.compile(pattern);
            response.put("valid", true);
            response.put("message", "Pattern is valid");
        } catch (Exception e) {
            response.put("valid", false);
            response.put("message", "Invalid pattern: " + e.getMessage());
        }
        return ResponseEntity.ok(response);
    }
}