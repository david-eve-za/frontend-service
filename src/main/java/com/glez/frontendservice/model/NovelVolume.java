package com.glez.frontendservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Un volumen de una obra del catálogo. Los estados de procesamiento
 * (split hecho, traducido, audio) NO se almacenan aquí: se derivan del
 * {@link Book} enlazado al construir el DTO del gestor.
 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"novel_id", "source_key"}))
public class NovelVolume {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "novel_id", nullable = false)
    @JsonIgnore
    private Novel novel;

    /** Número de volumen parseado del nombre de archivo; nulo si no se pudo parsear. */
    @Column(name = "volume_number")
    private Integer volumeNumber;

    /** Etiqueta legible del volumen (p.ej. "Volume 01"). */
    private String label;

    /** Grupo traductor extraído de los corchetes del nombre, si existe. */
    @Column(name = "translator_group")
    private String translatorGroup;

    /**
     * Carpeta remota (href percent-encoded) que contiene los archivos de
     * este volumen. Coincide con novel.remotePath salvo en novelas
     * virtuales armadas desde archivos sueltos.
     */
    @Column(name = "remote_dir", length = 1024)
    private String remoteDir;

    /**
     * Clave determinista de sincronización dentro de la obra: "vol:N" para
     * volúmenes numerados o "file:<nombre de archivo>" para archivos sin
     * número. Permite el upsert idempotente entre ejecuciones.
     */
    @Column(name = "source_key", nullable = false, length = 1024)
    private String sourceKey;

    /** El volumen desapareció de la fuente en el último sync (estado local se conserva). */
    @Column(name = "removed_from_source", nullable = false)
    private boolean removedFromSource = false;

    /** Libro del pipeline enlazado a este volumen; nulo hasta que el usuario prepara el texto. */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "book_id")
    @JsonIgnore
    private Book book;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @OneToMany(mappedBy = "volume", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<NovelVolumeFile> files = new ArrayList<>();

    /**
     * Id del Book enlazado SIN inicializar el proxy lazy: Hibernate expone
     * el identificador sin tocar la base, así el gestor de novelas puede
     * agrupar por book_id sin cargar cada Book (y su CLOB full_text).
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public UUID getBookIdOrNull() {
        return book != null ? book.getId() : null;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Novel getNovel() {
        return novel;
    }

    public void setNovel(Novel novel) {
        this.novel = novel;
    }

    public Integer getVolumeNumber() {
        return volumeNumber;
    }

    public void setVolumeNumber(Integer volumeNumber) {
        this.volumeNumber = volumeNumber;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getTranslatorGroup() {
        return translatorGroup;
    }

    public void setTranslatorGroup(String translatorGroup) {
        this.translatorGroup = translatorGroup;
    }

    public String getRemoteDir() {
        return remoteDir;
    }

    public void setRemoteDir(String remoteDir) {
        this.remoteDir = remoteDir;
    }

    public String getSourceKey() {
        return sourceKey;
    }

    public void setSourceKey(String sourceKey) {
        this.sourceKey = sourceKey;
    }

    public boolean isRemovedFromSource() {
        return removedFromSource;
    }

    public void setRemovedFromSource(boolean removedFromSource) {
        this.removedFromSource = removedFromSource;
    }

    public Book getBook() {
        return book;
    }

    public void setBook(Book book) {
        this.book = book;
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

    public List<NovelVolumeFile> getFiles() {
        return files;
    }

    public void setFiles(List<NovelVolumeFile> files) {
        this.files = files;
    }
}