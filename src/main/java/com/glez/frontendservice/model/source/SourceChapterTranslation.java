package com.glez.frontendservice.model.source;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Traducción de capítulo (título + contenido).
 */
@Entity
@Table(name = "source_chapter_translation",
uniqueConstraints = @UniqueConstraint(name = "uq_source_chapter_translation_unique", columnNames = {"novel_id", "chapter_serial", "language"}),
indexes = @Index(name = "idx_source_chapter_translation_novel_serial", columnList = "novel_id, chapter_serial"))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SourceChapterTranslation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "novel_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private SourceNovel novel;

    @Column(name = "chapter_serial", nullable = false)
    private int chapterSerial;

    @Column(name = "language", nullable = false, length = 2)
    private String language;

    @Column(name = "chapter_title", length = 500)
    private String chapterTitle;

    @Column(name = "content_file", length = 500)
    private String contentFile;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "compressed_size")
    private Long compressedSize;

    @Column(name = "compression", nullable = false, length = 10)
    private String compression = "gzip";

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
        if (this.compression == null) {
            this.compression = "gzip";
        }
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }
}