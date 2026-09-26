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
        List<Map<String, Object>> books = bookRepository.findAll().stream()
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
        return bookRepository.findById(bookId)
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
                    + "Safe to call again after a failure to continue from the last checkpoint.")
    @PostMapping("/{bookId}/process")
    public ResponseEntity<Map<String, Object>> processBook(
            @Parameter(description = "ID of the book to process", required = true)
            @PathVariable UUID bookId) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (bookPipelineOrchestrator.isRunning(bookId)) {
                response.put("error", "Book " + bookId + " is already being processed.");
                return ResponseEntity.status(409).body(response);
            }
            bookPipelineOrchestrator.processBook(bookId);
            response.put("message", "Processing started/resumed for book " + bookId);
            response.put("bookId", bookId);
            return ResponseEntity.accepted().body(response);
        } catch (IllegalArgumentException e) {
            response.put("error", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            response.put("error", "Error starting processing: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
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

    @Operation(summary = "Get all chunks for a book with pagination", description = "Retrieves a paginated list of all text chunks for a given book ID.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved paginated chunks",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = PaginatedResponse.class))), // Updated schema
            @ApiResponse(responseCode = "404", description = "Book not found", content = @Content),
            @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @GetMapping("/{bookId}/chunks")
    public ResponseEntity<PaginatedResponse<ChunkDto>> getBookChunks(
            @Parameter(description = "ID of the book to retrieve chunks for", required = true)
            @PathVariable UUID bookId,
            @PageableDefault(page = 0, size = 10) Pageable pageable) { // Added Pageable parameter
        try {
            PaginatedResponse<ChunkDto> chunks = bookProcessingService.getBookChunks(bookId, pageable); // Pass pageable
            return ResponseEntity.ok(chunks);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
