package com.glez.frontendservice.model.source;

import com.glez.frontendservice.model.source.converter.MapToStringConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Volumen de una novela de crawler genérico.
 * Clave de negocio: serial dentro de la novela (1, 2, 3...).
 */
@Entity
@Table(name = "source_volume", indexes = {
    @Index(name = "idx_source_volume_novel_id", columnList = "novel_id")
},
uniqueConstraints = @UniqueConstraint(name = "uq_source_volume_serial", columnNames = {"novel_id", "serial"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SourceVolume {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "novel_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private SourceNovel novel;

    @Column(nullable = false)
    private int serial;

    @Column(length = 500, nullable = false)
    private String title = "";

    @Column(name = "chapter_count", nullable = false)
    private int chapterCount = 0;

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

    @OneToMany(mappedBy = "volume", cascade = {CascadeType.PERSIST, CascadeType.MERGE}, orphanRemoval = false)
    @OrderBy("serial ASC")
    @BatchSize(size = 100)
    private List<SourceChapter> chapters = new ArrayList<>();

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