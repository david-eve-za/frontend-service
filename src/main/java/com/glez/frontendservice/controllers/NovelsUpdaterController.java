package com.glez.frontendservice.controllers;

import com.glez.frontendservice.dtos.NovelsCrawlStatus;
import com.glez.frontendservice.services.NovelsUpdaterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/novels")
@Tag(name = "Novels Updater", description = "Synchronizes the light novels library from the remote Elscione server")
@RequiredArgsConstructor
public class NovelsUpdaterController {

    private final NovelsUpdaterService novelsUpdaterService;

    @Operation(summary = "Start a novels synchronization",
            description = "Launches an asynchronous recursive crawl that downloads new/updated novels, skipping existing files")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "202", description = "Synchronization started"),
            @ApiResponse(responseCode = "409", description = "A synchronization is already running"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @PostMapping("/sync")
    public ResponseEntity<Object> startSync() {
        try {
            return ResponseEntity.accepted().body(novelsUpdaterService.startSync());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
        }
    }

    @Operation(summary = "Get current synchronization status",
            description = "Returns progress counters of the last or currently running synchronization")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Current status"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @GetMapping("/status")
    public ResponseEntity<NovelsCrawlStatus> getStatus() {
        return ResponseEntity.ok(novelsUpdaterService.getStatus());
    }

    @Operation(summary = "Cancel the running synchronization",
            description = "Requests cooperative cancellation of the crawl in progress")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Cancellation requested"),
            @ApiResponse(responseCode = "409", description = "No synchronization is running"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @PostMapping("/cancel")
    public ResponseEntity<Object> cancel() {
        if (novelsUpdaterService.cancel()) {
            return ResponseEntity.ok(Map.of("cancelled", true));
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("cancelled", false, "message", "No synchronization is currently running"));
    }
}
