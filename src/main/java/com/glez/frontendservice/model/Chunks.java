package com.glez.frontendservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Un fragmento de traducción de un libro. Cuando el libro está dividido en
 * bloques semánticos, cada chunk pertenece a un bloque concreto (los chunks
 * se generan por bloque, nunca sobre el texto completo); block es null
 * para libros sin bloques (ruta legada de texto completo).
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Chunks {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "book_id")
    @JsonIgnore
    private Book book;

    /**
     * Bloque semántico al que pertenece este chunk; null = chunk de texto
     * completo (libros sin bloques o chunks legados).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "block_id")
    @JsonIgnore
    private Block block;

    @Column(name = "original_text", columnDefinition = "TEXT")
    private String originalText;

    @Column(name = "translated_text", columnDefinition = "TEXT")
    private String translatedText;

    @Enumerated(EnumType.STRING)
    private ChunkStatus status;

    @Column(nullable = false)
    private Integer position;

    @Column(nullable = false)
    private Integer attempts = 0;
}
