package com.glez.frontendservice.model.source;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Metadatos de versionado del crawler por fuente.
 * Permite invalidar caché cuando cambia versión/configuración del crawler.
 */
@Entity
@Table(name = "source_crawler_meta")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SourceCrawlerMeta {

    @Id
    @Column(name = "source_id", nullable = false, length = 100)
    private String sourceId;

    @Column(name = "crawler_class", nullable = false, length = 200)
    private String crawlerClass;

    @Column(name = "version", nullable = false, length = 50)
    private String version;

    @Column(name = "tier", length = 20)
    private String tier;

    @Column(name = "last_schema_change")
    private Instant lastSchemaChange;

    @Column(name = "config_hash", length = 64)
    private String configHash;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    public void onSave() {
        this.updatedAt = Instant.now();
    }
}