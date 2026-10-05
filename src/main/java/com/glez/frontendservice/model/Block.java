package com.glez.frontendservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "blocks")
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

    @ManyToOne
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
