package com.glez.frontendservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Un término del glosario de traducción: la traducción establecida que
 * debe respetarse al traducir. Tres alcances: GLOBAL (novel y book null,
 * aplica a todas las traducciones), por SERIE (novel seteado, extraído del
 * primer volumen y reutilizado por los demás de la misma serie) y por
 * LIBRO (book seteado, para subidas sin serie).
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "translation_terms",
        uniqueConstraints = @UniqueConstraint(columnNames = {"novel_id", "book_id", "source_term"}))
public class TranslationTerm {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Serie a la que aplica el término; null = no acotado por serie. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "novel_id")
    @JsonIgnore
    private Novel novel;

    /** Libro al que aplica el término (subidas sin serie); null = no acotado. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "book_id")
    @JsonIgnore
    private Book book;

    @Column(name = "source_term", nullable = false, length = 300)
    private String sourceTerm;

    @Column(name = "target_term", nullable = false, length = 300)
    private String targetTerm;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
