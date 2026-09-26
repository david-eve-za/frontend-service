package com.glez.frontendservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Un archivo remoto (variante de formato) de un volumen: el mismo volumen
 * suele existir como .epub, .pdf y .zip simultáneamente.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
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
}
