package com.glez.frontendservice.model.source;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Novela principal por fuente (crawlers genéricos, no-Elscione).
 * Clave natural: (source_id, novel_url_hash) donde hash = SHA256(source_id + "|" + novel_url).
 */
@Entity
@Table(name = "source_novel", indexes = {
    @Index(name = "idx_source_novel_source_id", columnList = "source_id"),
    @Index(name = "idx_source_novel_title", columnList = "title"),
    @Index(name = "idx_source_novel_language", columnList = "language"),
    @Index(name = "idx_source_novel_popularity", columnList = "popularity"),
    @Index(name = "idx_source_novel_updated_at", columnList = "updated_at"),
    @Index(name = "idx_source_novel_last_crawled", columnList = "last_crawled_at")
},
uniqueConstraints = {
    @UniqueConstraint(name = "uq_source_novel_natural", columnNames = {"source_id", "novel_url_hash"}),
    @UniqueConstraint(name = "uq_source_novel_url", columnNames = {"source_id", "novel_url"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SourceNovel {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "source_id", nullable = false, length = 100)
    private String sourceId;

    @Column(name = "novel_url", nullable = false, length = 2048)
    private String novelUrl;

    @Column(name = "novel_url_hash", nullable = false, length = 64)
    private String novelUrlHash;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(length = 500)
    private String author;

    @Column(name = "cover_url", length = 2048)
    private String coverUrl;

    @Column(columnDefinition = "TEXT")
    private String synopsis;

    @Column(name = "language", length = 2)
    private String language;

    @Column(name = "is_mtl", nullable = false)
    private boolean mtl = false;

    @Column(name = "is_manga", nullable = false)
    private boolean manga = false;

    @Column(name = "is_rtl", nullable = false)
    private boolean rtl = false;

    @Column(length = 50)
    private String status;

    @Column(name = "last_update", length = 100)
    private String lastUpdate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<String> tags = new ArrayList<>();

    @Column(name = "volume_count", nullable = false)
    private int volumeCount = 0;

    @Column(name = "chapter_count", nullable = false)
    private int chapterCount = 0;

    @Column(name = "popularity", nullable = false)
    private long popularity = 0;

    @Column(name = "view_count", nullable = false)
    private long viewCount = 0;

    @Column(name = "crawler_version", length = 50)
    private String crawlerVersion;

    @Column(name = "crawler_tier", length = 20)
    private String crawlerTier;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> extra = new HashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_crawled_at")
    private Instant lastCrawledAt;

    @Column(name = "cover_file", length = 500)
    private String coverFile;

    @OneToMany(mappedBy = "novel", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("serial ASC")
    private List<SourceVolume> volumes = new ArrayList<>();

    @OneToMany(mappedBy = "novel", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SourceNovelTranslation> translations = new ArrayList<>();

    @OneToMany(mappedBy = "novel", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SourceNovelGlossary> glossaries = new ArrayList<>();

    @ManyToMany
    @JoinTable(
        name = "source_novel_tag",
        joinColumns = @JoinColumn(name = "novel_id"),
        inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    private List<SourceTag> tagEntities = new ArrayList<>();

    // Helpers
    public static String computeUrlHash(String sourceId, String novelUrl) {
        return com.google.common.hash.Hashing.sha256()
                .hashString(sourceId + "|" + novelUrl, java.nio.charset.StandardCharsets.UTF_8)
                .toString();
    }

    @PrePersist
    public void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        if (this.novelUrlHash == null && this.sourceId != null && this.novelUrl != null) {
            this.novelUrlHash = computeUrlHash(this.sourceId, this.novelUrl);
        }
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }
}