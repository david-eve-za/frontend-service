package com.glez.frontendservice.controllers;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Generic paginated response wrapper.
 * Used for consistent pagination across all paginated endpoints.
 */
public record PageResponse<T>(
    @Schema(description = "Page content")
    List<T> content,
    
    @Schema(description = "Current page number (0-based)")
    int page,
    
    @Schema(description = "Page size")
    int size,
    
    @Schema(description = "Total number of elements")
    long totalElements,
    
    @Schema(description = "Total number of pages")
    int totalPages
) {}