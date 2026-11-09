package com.glez.frontendservice.model.source;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Catálogo de etiquetas/géneros normalizados.
 * Permite matching case-insensitive y sin acentos via normalized_name.
 */
@Entity
@Table(name = "source_tag", indexes = {
    @Index(name = "idx_source_tag_category", columnList = "category"),
    @Index(name = "idx_source_tag_usage", columnList = "usage_count DESC")
},
uniqueConstraints = @UniqueConstraint(name = "uq_source_tag_name_source", columnNames = {"normalized_name", "source_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SourceTag {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "normalized_name", nullable = false, length = 100)
    private String normalizedName;

    @Column(length = 50)
    private String category;

    @Column(name = "source_id", length = 100)
    private String sourceId;

    @Column(name = "usage_count", nullable = false)
    private long usageCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void onCreate() {
        this.createdAt = Instant.now();
        if (this.normalizedName == null && this.name != null) {
            this.normalizedName = normalize(this.name);
        }
    }

    public static String normalize(String input) {
        return java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{Mn}", "")
                .toLowerCase()
                .trim();
    }
}