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
// Index for the catalog listing (findNovels sorts by title on every page):
// turns the full table scan + sort into an ordered index scan.
@Table(indexes = @Index(name = "idx_novel_title", columnList = "title"))
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
    @Column(name = "remote_path", unique = true, length = 1024)
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

    /**
     * La carpeta remota de esta obra fue listada POR COMPLETO en la pasada
     * de sincronización actual (todos sus volúmenes/archivos upsertados sin
     * error). Las corridas incrementales saltan estas carpetas — un solo
     * listing por carpeta hasta completar el 100% del catálogo — y al
     * completarse el ciclo se reinician todos los flags para arrancar una
     * pasada completa de nuevo. Nulo/irrelevante en las novelas de archivos
     * sueltos (remotePath nulo), que se refrescan con el listado de la
     * raíz en cada corrida.
     */
    @Column(name = "listing_complete", nullable = false)
    private boolean listingComplete = false;

    /** Momento del último listado completo de la carpeta remota. */
    @Column(name = "last_listed_at")
    private Instant lastListedAt;

    @OneToMany(mappedBy = "novel", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<NovelVolume> volumes = new ArrayList<>();

    // --- Campos de enriquecimiento AniList ---
    @Column(name = "anilist_id")
    private Integer anilistId;

    @Column(name = "anilist_cover_url", length = 512)
    private String anilistCoverUrl;

    @Column(name = "anilist_description", columnDefinition = "TEXT")
    private String anilistDescription;

    @ElementCollection
    @CollectionTable(name = "novel_anilist_genres", joinColumns = @JoinColumn(name = "novel_id"))
    @Column(name = "genre")
    private List<String> anilistGenres = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "novel_anilist_tags", joinColumns = @JoinColumn(name = "novel_id"))
    @Column(name = "tag")
    private List<String> anilistTags = new ArrayList<>();

    @Column(name = "anilist_status")
    private String anilistStatus;

    @Column(name = "anilist_average_score")
    private Integer anilistAverageScore;

    @Column(name = "anilist_format")
    private String anilistFormat;

    @Column(name = "anilist_source")
    private String anilistSource;

    @Column(name = "anilist_country_of_origin")
    private String anilistCountryOfOrigin;

    @Column(name = "anilist_volumes")
    private Integer anilistVolumes;

    @Column(name = "anilist_chapters")
    private Integer anilistChapters;
}
