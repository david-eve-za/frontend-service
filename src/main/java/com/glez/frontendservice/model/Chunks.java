package com.glez.frontendservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Chunks {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "book_id")
    private Book book;

    @Column(name = "original_text", columnDefinition = "TEXT")
    private String originalText;

    @Column(name = "translated_text", columnDefinition = "TEXT")
    private String translatedText;

    @Enumerated(EnumType.STRING)
    private ChunkStatus status;

    @Column(nullable = false)
    private Integer position;

    @Column(nullable = false)
    private Integer attempts = 0;
}
