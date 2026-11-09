package com.glez.frontendservice.model.source;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Traducción de metadatos de novela (título, autor, sinopsis).
 */
@Entity
@Table(name = "source_novel_translation",
uniqueConstraints = @UniqueConstraint(name = "uq_source_novel_translation_lang", columnNames = {"novel_id", "language"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SourceNovelTranslation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "novel_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private SourceNovel novel;

    @Column(name = "language", nullable = false, length = 2)
    private String language;

    @Column(length = 500)
    private String title;

    @Column(length = 500)
    private String author;

    @Column(columnDefinition = "TEXT")
    private String synopsis;

    @Column(length = 50)
    private String engine;

    @Column(length = 100)
    private String model;

    @Column(name = "glossary_applied", nullable = false)
    private boolean glossaryApplied = false;

    @Column(name = "glossary_version")
    private Integer glossaryVersion;

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