package com.glez.frontendservice.model.source;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Traducción de título de volumen.
 */
@Entity
@Table(name = "source_volume_translation",
uniqueConstraints = @UniqueConstraint(name = "uq_source_volume_translation_unique", columnNames = {"novel_id", "volume_serial", "language"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SourceVolumeTranslation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "novel_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private SourceNovel novel;

    @Column(name = "volume_serial", nullable = false)
    private int volumeSerial;

    @Column(name = "language", nullable = false, length = 2)
    private String language;

    @Column(name = "volume_title", nullable = false, length = 500)
    private String volumeTitle;

    @Column(length = 50)
    private String engine;

    @Column(length = 100)
    private String model;

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