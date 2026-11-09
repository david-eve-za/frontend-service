package com.glez.frontendservice.model.source;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Glosario de términos para traducción asistida.
 * JSON: término_original -> traducción_elegida.
 */
@Entity
@Table(name = "source_novel_glossary",
uniqueConstraints = @UniqueConstraint(name = "uq_source_novel_glossary_lang", columnNames = {"novel_id", "language"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SourceNovelGlossary {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "novel_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private SourceNovel novel;

    @Column(name = "language", nullable = false, length = 2)
    private String language;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, String> terms = new HashMap<>();

    @Column(nullable = false)
    private int version = 1;

    @Column(name = "auto_extracted", nullable = false)
    private boolean autoExtracted = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }
}