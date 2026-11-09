package com.glez.frontendservice.model.source;

import com.glez.frontendservice.model.source.converter.MapToStringConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Capítulo de una novela de crawler genérico.
 * Clave de negocio: serial global dentro de la novela.
 */
@Entity
@Table(name = "source_chapter", indexes = {
    @Index(name = "idx_source_chapter_novel_id", columnList = "novel_id"),
    @Index(name = "idx_source_chapter_volume_id", columnList = "volume_id"),
    @Index(name = "idx_source_chapter_is_done", columnList = "is_done")
},
uniqueConstraints = @UniqueConstraint(name = "uq_source_chapter_serial", columnNames = {"novel_id", "serial"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SourceChapter {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "novel_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private SourceNovel novel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "volume_id")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private SourceVolume volume;

    @Column(nullable = false)
    private int serial;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(name = "is_done", nullable = false)
    private boolean done = false;

    private Integer wordCount;

    @Column(name = "crawler_version", length = 50)
    private String crawlerVersion;

    @Column(name = "crawler_tier", length = 20)
    private String crawlerTier;

    @Column(columnDefinition = "TEXT", nullable = false)
    @Convert(converter = MapToStringConverter.class)
    private Map<String, Object> extra = new HashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_fetched_at")
    private Instant lastFetchedAt;

    @OneToOne(mappedBy = "chapter", cascade = CascadeType.ALL, orphanRemoval = true)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private SourceChapterContent content;

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