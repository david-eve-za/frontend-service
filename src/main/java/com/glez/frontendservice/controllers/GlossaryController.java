package com.glez.frontendservice.controllers;

import com.glez.frontendservice.model.Book;
import com.glez.frontendservice.model.Novel;
import com.glez.frontendservice.model.TranslationTerm;
import com.glez.frontendservice.repository.BookRepository;
import com.glez.frontendservice.repository.NovelRepository;
import com.glez.frontendservice.repository.TranslationTermRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/glossary")
@RequiredArgsConstructor
@Tag(name = "Glossary", description = "Glosario de traducción: términos con su traducción establecida, "
        + "para mantener la consistencia entre los chunks de un libro y entre los volúmenes de una serie")
public class GlossaryController {

    private final TranslationTermRepository termRepository;
    private final NovelRepository novelRepository;
    private final BookRepository bookRepository;

    @Operation(summary = "Lista los términos del glosario",
            description = "Sin parámetros devuelve los términos globales; con novelId o bookId los de ese alcance.")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Términos del alcance"))
    @GetMapping
    public ResponseEntity<List<TranslationTerm>> list(
            @RequestParam(name = "novelId", required = false) UUID novelId,
            @RequestParam(name = "bookId", required = false) UUID bookId) {
        if (novelId != null) {
            return ResponseEntity.ok(termRepository.findByNovelIdOrderBySourceTermAsc(novelId));
        }
        if (bookId != null) {
            return ResponseEntity.ok(termRepository.findByBookIdOrderBySourceTermAsc(bookId));
        }
        return ResponseEntity.ok(termRepository.findByNovelIsNullAndBookIsNullOrderBySourceTermAsc());
    }

    @Operation(summary = "Crea o actualiza un término",
            description = "Payload: {novelId?, bookId?, sourceTerm, targetTerm}. Sin novelId ni bookId "
                    + "el término es global. Si ya existe el sourceTerm en el alcance, se actualiza su traducción.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Término guardado"),
            @ApiResponse(responseCode = "404", description = "Novel o libro desconocido")
    })
    @PostMapping
    public ResponseEntity<TranslationTerm> save(@RequestBody Map<String, String> body) {
        String sourceTerm = body.get("sourceTerm");
        String targetTerm = body.get("targetTerm");
        if (sourceTerm == null || sourceTerm.isBlank() || targetTerm == null || targetTerm.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        TranslationTerm term = new TranslationTerm();
        String novelId = body.get("novelId");
        String bookId = body.get("bookId");
        if (novelId != null && !novelId.isBlank()) {
            Novel novel = novelRepository.findById(UUID.fromString(novelId))
                    .orElse(null);
            if (novel == null) {
                return ResponseEntity.notFound().build();
            }
            term.setNovel(novel);
        } else if (bookId != null && !bookId.isBlank()) {
            Book book = bookRepository.findById(UUID.fromString(bookId))
                    .orElse(null);
            if (book == null) {
                return ResponseEntity.notFound().build();
            }
            term.setBook(book);
        }
        term.setSourceTerm(sourceTerm.trim());
        term.setTargetTerm(targetTerm.trim());
        term.setCreatedAt(Instant.now());
        term.setUpdatedAt(Instant.now());
        return ResponseEntity.ok(termRepository.save(term));
    }

    @Operation(summary = "Elimina un término del glosario")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Término eliminado"),
            @ApiResponse(responseCode = "404", description = "Término desconocido")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        if (!termRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        termRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
