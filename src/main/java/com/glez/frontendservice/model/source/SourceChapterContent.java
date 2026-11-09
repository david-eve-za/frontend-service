package com.glez.frontendservice.model.source;

import com.glez.frontendservice.model.source.converter.ChapterImageInfoListToStringConverter;
import com.glez.frontendservice.model.source.converter.MapToStringConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Contenido HTML de un capítulo, almacenado comprimido en filesystem (GZIP).
 * La tabla solo guarda metadatos y path al archivo.
 */
@Entity
@Table(name = "source_chapter_content",
uniqueConstraints = @UniqueConstraint(name = "uq_source_chapter_content_chapter", columnNames = "chapter_id"))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SourceChapterContent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chapter_id", nullable = false)
    @MapsId
    @com.fasterxml.jackson.annotation.JsonIgnore
    private SourceChapter chapter;

    @Column(name = "content_file", nullable = false, length = 500)
    private String contentFile;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "compressed_size", nullable = false)
    private long compressedSize;

    @Column(name = "original_size", nullable = false)
    private long originalSize;

    @Column(name = "compression", nullable = false, length = 10)
    private String compression = "gzip";

    @Column(columnDefinition = "TEXT", nullable = false)
    @Convert(converter = ChapterImageInfoListToStringConverter.class)
    private List<ChapterImageInfo> images = new ArrayList<>();

    @Column(length = 50)
    private String extractor;

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

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChapterImageInfo {
        private String url;
        private String localPath;
        private Integer width;
        private Integer height;
    }

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