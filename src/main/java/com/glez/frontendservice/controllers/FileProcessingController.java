package com.glez.frontendservice.controllers;


import com.glez.frontendservice.dtos.TraceEventDto;
import com.glez.frontendservice.model.Book;
import com.glez.frontendservice.model.ChunkDto;
import com.glez.frontendservice.model.PaginatedResponse;
import com.glez.frontendservice.repository.BookRepository;
import com.glez.frontendservice.services.BookProcessingService;
import com.glez.frontendservice.services.BookPipelineOrchestrator;
import com.glez.frontendservice.services.ProcessingTraceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/files")
@Tag(name = "File Processing", description = "Operations related to file processing and text extraction")
@RequiredArgsConstructor // Add this annotation
public class FileProcessingController {

    private final BookProcessingService bookProcessingService;
    private final BookRepository bookRepository;
    private final BookPipelineOrchestrator bookPipelineOrchestrator;
    private final ProcessingTraceService processingTraceService;

    // Remove @Autowired, Lombok's @RequiredArgsConstructor handles constructor injection
    // public FileProcessingController(BookProcessingService bookProcessingService, BookRepository bookRepository) {
    //     this.bookProcessingService = bookProcessingService;
    //     this.bookRepository = bookRepository;
    // }

    @Operation(summary = "Upload a file for processing", description = "Uploads a file, creates a Book entry, and starts asynchronous processing.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "File uploaded successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = Map.class))),
            @ApiResponse(responseCode = "400", description = "Invalid file input",
                    content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content)
    })
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadFile(
            @Parameter(description = "File to upload", required = true)
            @RequestParam("file") MultipartFile file) {
        Map<String, Object> response = new HashMap<>();
        if (file.isEmpty()) {
            response.put("error", "Please select a file to upload.");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            Book book = bookProcessingService.storeBook(file.getInputStream(), file.getOriginalFilename());
            response.put("message", "File uploaded successfully. Processing started.");
            response.put("bookId", book.getId());
            response.put("status", book.getStatus());
            return ResponseEntity.ok(response);
        } catch (IOException e) {
            response.put("error", "Failed to read file: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        } catch (Exception e) {
            response.put("error", "Error initiating processing: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @Operation(summary = "List all books", description = "Retrieves a list of all books with their IDs, status, current step and audio artifact.")
    @GetMapping("/list")
    public ResponseEntity<List<Map<String, Object>>> getAllBooks() {
        // Light projection: the full_text CLOB of every book used to ride
        // along on every listing (findAll of entities).
        List<Map<String, Object>> books = bookRepository.findBookListViews().stream()
                .map(book -> {
                    Map<String, Object> bookMap = new HashMap<>();
                    bookMap.put("id", book.getId());
                    bookMap.put("name", book.getName());
                    bookMap.put("status", book.getStatus());
                    bookMap.put("currentStep", book.getCurrentStep());
                    bookMap.put("lastTraceId", book.getLastTraceId());
                    bookMap.put("audioFilePath", book.getAudioFilePath());
                    return bookMap;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(books);
    }

    @Operation(summary = "Get book status", description = "Retrieves the processing status of a book by its ID.")
    @GetMapping("/{bookId}/status")
    public ResponseEntity<Map<String, Object>> getBookStatus(@PathVariable UUID bookId) {
        // Light projection (polled by the UI during processing): reading the
        // status must never transfer the full_text CLOB.
        return bookRepository.findStatusViews(List.of(bookId)).stream()
                .findFirst()
                .map(book -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("bookId", book.getId());
                    response.put("status", book.getStatus());
                    response.put("currentStep", book.getCurrentStep());
                    response.put("lastTraceId", book.getLastTraceId());
                    response.put("audioFilePath", book.getAudioFilePath());
                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Start or resume the processing pipeline",
            description = "Idempotent: derives the first pending step from the persisted state and resumes from there. "
                    + "Safe to call again after a failure to continue from the last checkpoint. "
                    + "The optional 'blocks' parameter restricts the split to the given semantic block ids "
                    + "(comma-separated): the chunks are regenerated from those blocks so only they are translated.")
    @PostMapping("/{bookId}/process")
    public ResponseEntity<Map<String, Object>> processBook(
            @Parameter(description = "ID of the book to process", required = true)
            @PathVariable UUID bookId,
            @Parameter(description = "Optional last pipeline step to run (EXTRACT, SPLIT, TRANSLATE, AUDIO, FINALIZE). "
                    + "Defaults to FINALIZE (full pipeline). Used by the novels manager to run "
                    + "translation and audio as independent stages.")
            @RequestParam(name = "to", required = false) com.glez.frontendservice.model.ProcessStep targetStep,
            @Parameter(description = "Optional comma-separated ids of the semantic blocks that define the "
                    + "translation scope (only those blocks are split and translated).")
            @RequestParam(name = "blocks", required = false) String blockIdsParam) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (bookPipelineOrchestrator.isRunning(bookId)) {
                response.put("error", "Book " + bookId + " is already being processed.");
                return ResponseEntity.status(409).body(response);
            }
            if (!bookRepository.existsById(bookId)) {
                response.put("error", "Book " + bookId + " not found.");
                return ResponseEntity.notFound().build();
            }

            List<UUID> blockIds = parseBlockIds(blockIdsParam);
            if (!blockIds.isEmpty()) {
                bookProcessingService.validateBlocksBelongToBook(bookId, blockIds);
            }

            // Un libro STOPPED solo despierta por intención EXPLÍCITA del
            // usuario (este endpoint): el flip previo lo deja en AWAITING
            // y el guard del orquestador ya no lo ignora. El auto-resume
            // del arranque sigue saltando los STOPPED (1-arg processBook).
            bookRepository.updateStatusWhen(bookId,
                    com.glez.frontendservice.model.ProcessingStatus.STOPPED,
                    com.glez.frontendservice.model.ProcessingStatus.AWAITING);

            com.glez.frontendservice.model.ProcessStep effectiveTarget =
                    targetStep != null ? targetStep : com.glez.frontendservice.model.ProcessStep.FINALIZE;
            bookPipelineOrchestrator.processBook(bookId, effectiveTarget,
                    blockIds.isEmpty() ? null : blockIds);
            response.put("message", "Processing started/resumed for book " + bookId
                    + (targetStep != null ? " up to " + targetStep : "")
                    + (!blockIds.isEmpty() ? " scoped to " + blockIds.size() + " block(s)" : ""));
            response.put("bookId", bookId);
            return ResponseEntity.accepted().body(response);
        } catch (IllegalArgumentException e) {
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            response.put("error", "Error starting processing: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /** Parses the comma-separated block ids; empty list when absent. */
    private List<UUID> parseBlockIds(String blockIdsParam) {
        if (blockIdsParam == null || blockIdsParam.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(blockIdsParam.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(id -> {
                    try {
                        return UUID.fromString(id);
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("Invalid block id: " + id);
                    }
                })
                .toList();
    }

    @Operation(summary = "Pause the running pipeline of a book",
            description = "Cooperative pause: the step currently in flight (e.g. a translation pass) runs to "
                    + "completion and the pipeline stops at the next checkpoint, leaving the book AWAITING. "
                    + "Nothing is cancelled and no retry budget is consumed. "
                    + "Returns 409 when the book has no active run in this instance.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "202", description = "Pause requested; it takes effect at the next checkpoint", content = @Content),
            @ApiResponse(responseCode = "404", description = "Book not found", content = @Content),
            @ApiResponse(responseCode = "409", description = "The book is not being processed right now", content = @Content)
    })
    @PostMapping("/{bookId}/pause")
    public ResponseEntity<Map<String, Object>> pauseBook(
            @Parameter(description = "ID of the book to pause", required = true)
            @PathVariable UUID bookId) {
        Map<String, Object> response = new HashMap<>();
        if (!bookRepository.existsById(bookId)) {
            response.put("error", "Book " + bookId + " not found.");
            return ResponseEntity.notFound().build();
        }
        if (!bookPipelineOrchestrator.requestPause(bookId)) {
            response.put("error", "Book " + bookId + " is not being processed right now.");
            return ResponseEntity.status(409).body(response);
        }
        response.put("message", "Pause requested for book " + bookId
                + ": the pipeline stops at the next checkpoint.");
        response.put("bookId", bookId);
        return ResponseEntity.accepted().body(response);
    }

    @Operation(summary = "Stop the pipeline of a book",
            description = "Immediate stop: in-flight chunk translations are cancelled, the step closes as "
                    + "CANCELLED in the trace (no retry budget consumed) and the book is left STOPPED. "
                    + "Works on idle/failed books too (they are simply marked STOPPED, out of the auto-resume "
                    + "rotation). A STOPPED book restarts via POST /{bookId}/process.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "202", description = "Book stopped", content = @Content),
            @ApiResponse(responseCode = "404", description = "Book not found", content = @Content),
            @ApiResponse(responseCode = "409", description = "The book is already COMPLETED (nothing to stop)", content = @Content)
    })
    @PostMapping("/{bookId}/stop")
    public ResponseEntity<Map<String, Object>> stopBook(
            @Parameter(description = "ID of the book to stop", required = true)
            @PathVariable UUID bookId) {
        Map<String, Object> response = new HashMap<>();
        if (!bookRepository.existsById(bookId)) {
            response.put("error", "Book " + bookId + " not found.");
            return ResponseEntity.notFound().build();
        }
        if (!bookPipelineOrchestrator.requestStop(bookId)) {
            response.put("error", "Book " + bookId + " is already completed; there is nothing to stop.");
            return ResponseEntity.status(409).body(response);
        }
        response.put("message", "Book " + bookId + " stopped.");
        response.put("bookId", bookId);
        return ResponseEntity.accepted().body(response);
    }

    @Operation(summary = "Get processing timeline",
            description = "Full trace of every pipeline step attempt of a book: step, status, attempts, durations and errors.")
    @GetMapping("/{bookId}/trace")
    public ResponseEntity<List<TraceEventDto>> getBookTrace(
            @Parameter(description = "ID of the book", required = true)
            @PathVariable UUID bookId) {
        try {
            return ResponseEntity.ok(processingTraceService.getTimeline(bookId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }



    @Operation(summary = "Delete a book", description = "Deletes a book and all its associated data.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Book deleted successfully", content = @Content),
            @ApiResponse(responseCode = "404", description = "Book not found", content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @DeleteMapping("/{bookId}")
    public ResponseEntity<Void> deleteBook(
            @Parameter(description = "ID of the book to delete", required = true)
            @PathVariable UUID bookId) {
        try {
            bookProcessingService.deleteBook(bookId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @Operation(summary = "Get full text of a book", description = "Retrieves the full extracted text content of a book by its ID.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved full text",
                    content = @Content(mediaType = "text/plain", schema = @Schema(type = "string"))),
            @ApiResponse(responseCode = "404", description = "Book not found", content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @GetMapping("/{bookId}/full-text")
    public ResponseEntity<String> getBookFullText(
            @Parameter(description = "ID of the book", required = true)
            @PathVariable UUID bookId) {
        try {
            String fullText = bookProcessingService.getFullText(bookId);
            return ResponseEntity.ok(fullText);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @Operation(summary = "Update full text of a book", description = "Updates the full text content of a book by its ID.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Full text updated successfully", content = @Content),
            @ApiResponse(responseCode = "404", description = "Book not found", content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PutMapping(value = "/{bookId}/full-text", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<Void> updateBookFullText(
            @Parameter(description = "ID of the book", required = true)
            @PathVariable UUID bookId,
            @Parameter(description = "New full text content", required = true)
            @RequestBody String newText) {
        try {
            bookProcessingService.updateFullText(bookId, newText);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @Operation(summary = "Split book into chunks", description = "Splits the full text of a book into smaller chunks for processing.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Book successfully split into chunks",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = Map.class))),
            @ApiResponse(responseCode = "400", description = "Book has no full text to chunk", content = @Content),
            @ApiResponse(responseCode = "404", description = "Book not found", content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PostMapping("/{bookId}/split")
    public ResponseEntity<Map<String, Object>> splitBookIntoChunks(
            @Parameter(description = "ID of the book to split", required = true)
            @PathVariable UUID bookId) {
        Map<String, Object> response = new HashMap<>();
        try {
            bookProcessingService.splitBookIntoChunks(bookId);
            response.put("message", "Book " + bookId + " successfully split into chunks.");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            response.put("error", "Error splitting book into chunks: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @Operation(summary = "Get all chunks for a book with pagination", description = "Retrieves a paginated list of text chunks "
            + "for a given book ID in reading order (block position, then chunk position), each with its original and translated "
            + "text and the metadata of the semantic block it belongs to.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved paginated chunks",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = PaginatedResponse.class))),
            @ApiResponse(responseCode = "404", description = "Book not found", content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @GetMapping("/{bookId}/chunks")
    public ResponseEntity<PaginatedResponse<ChunkDto>> getBookChunks(
            @Parameter(description = "ID of the book to retrieve chunks for", required = true)
            @PathVariable UUID bookId,
            @Parameter(description = "Optional comma-separated ids of semantic blocks: when present the view only "
                    + "includes the chunks of those blocks (translation scope view).")
            @RequestParam(name = "blocks", required = false) String blockIdsParam,
            @PageableDefault(page = 0, size = 10) Pageable pageable) {
        try {
            PaginatedResponse<ChunkDto> chunks =
                    bookProcessingService.getBookChunks(bookId, parseBlockIds(blockIdsParam), pageable);
            return ResponseEntity.ok(chunks);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @Operation(summary = "Translate a single chunk on demand",
            description = "Translates exactly ONE chunk without touching the rest of the book: reuses the book's glossary "
                    + "and the shared NVIDIA rate limit. A COMPLETED chunk can be re-translated (only its row changes). "
                    + "Returns 202 immediately: the chunk is marked PROCESSING and COMPLETED/FAILED asynchronously — "
                    + "poll GET /{bookId}/chunks to see the outcome.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "202", description = "Translation dispatched", content = @Content),
            @ApiResponse(responseCode = "400", description = "Chunk not found, not owned by the book or without text",
                    content = @Content),
            @ApiResponse(responseCode = "404", description = "Book not found", content = @Content),
            @ApiResponse(responseCode = "409", description = "The book is currently running a pipeline pass", content = @Content)
    })
    @PostMapping("/{bookId}/chunks/{chunkId}/translate")
    public ResponseEntity<Map<String, Object>> translateSingleChunk(
            @Parameter(description = "ID of the book the chunk belongs to", required = true)
            @PathVariable UUID bookId,
            @Parameter(description = "ID of the chunk to translate", required = true)
            @PathVariable UUID chunkId) {
        Map<String, Object> response = new HashMap<>();
        if (bookPipelineOrchestrator.isRunning(bookId)) {
            response.put("error", "Book " + bookId + " is already being processed.");
            return ResponseEntity.status(409).body(response);
        }
        if (!bookRepository.existsById(bookId)) {
            response.put("error", "Book " + bookId + " not found.");
            return ResponseEntity.notFound().build();
        }
        try {
            bookProcessingService.translateSingleChunk(bookId, chunkId);
            response.put("message", "Translation of chunk " + chunkId + " dispatched.");
            response.put("bookId", bookId);
            response.put("chunkId", chunkId);
            return ResponseEntity.accepted().body(response);
        } catch (IllegalArgumentException e) {
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            response.put("error", "Error starting chunk translation: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
