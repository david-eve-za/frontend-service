package com.glez.frontendservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Un archivo remoto (variante de formato) de un volumen: el mismo volumen
 * suele existir como .epub, .pdf y .zip simultáneamente.
 */
@Entity
public class NovelVolumeFile {

    /**
     * Formatos de archivo de volumen reconocidos por el gestor. OTHER cubre
     * archivos no procesables (scripts, imágenes, etc.).
     */
    public enum Format {
        EPUB,
        PDF,
        ZIP,
        TXT,
        OTHER
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "volume_id", nullable = false)
    @JsonIgnore
    private NovelVolume volume;

    /** href remoto percent-encoded del archivo (único en todo el catálogo). */
    @Column(name = "remote_href", unique = true, nullable = false, length = 1024)
    private String remoteHref;

    @Enumerated(EnumType.STRING)
    private Format format;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public NovelVolume getVolume() {
        return volume;
    }

    public void setVolume(NovelVolume volume) {
        this.volume = volume;
    }

    public String getRemoteHref() {
        return remoteHref;
    }

    public void setRemoteHref(String remoteHref) {
        this.remoteHref = remoteHref;
    }

    public Format getFormat() {
        return format;
    }

    public void setFormat(Format format) {
        this.format = format;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}