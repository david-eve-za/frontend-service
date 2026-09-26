package com.glez.frontendservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Una obra (novela) del catálogo sincronizado desde elscione. Se identifica
 * por la carpeta remota que la contiene; la misma obra presente en dos
 * carpetas distintas (p.ej. raíz y !Yaoi) genera dos entradas.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Novel {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Ruta remota (href percent-encoded del directorio en elscione). Nula
     * para novelas virtuales creadas a partir de archivos sueltos en la
     * raíz de la biblioteca remota.
     */
    @Column(name = "remote_path", unique = true)
    private String remotePath;

    /** Título de la obra (nombre del directorio remoto sanitizado). */
    private String title;

    /** Categoría de primer nivel en la que vive (p.ej. "!Yaoi"); nulo si está en la raíz. */
    private String category;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @OneToMany(mappedBy = "novel", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<NovelVolume> volumes = new ArrayList<>();
}
