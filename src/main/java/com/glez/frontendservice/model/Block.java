package com.glez.frontendservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(name = "blocks",
        // Composite index so findByBookIdOrderByPositionAsc is an ordered
        // index scan instead of FK lookup + in-memory sort.
        indexes = @Index(name = "idx_blocks_book_position", columnList = "book_id, position"))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Block {

    public enum BlockType {
        PROLOGUE,
        CHAPTER,
        EPILOGUE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * LAZY: loading the blocks of a book must never drag the Book row
     * (and its multi-megabyte full_text CLOB) along; every access to the
     * owning book in the pipeline goes through explicit book loads.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "book_id")
    private Book book;

    @Enumerated(EnumType.STRING)
    @Column(name = "block_type", nullable = false)
    private BlockType blockType;

    @Column(name = "block_number")
    private Integer blockNumber;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private Integer position;

    /**
     * Archivo de audio generado para este bloque (p. ej.
     * "libro - audiolibro/02 - Capitulo 01.mp3"); null hasta que se genere.
     */
    @Column(name = "audio_file_path")
    private String audioFilePath;
}
