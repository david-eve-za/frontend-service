package com.glez.frontendservice.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.UUID;

/**
 * Chunk row for the parallel original/translation view. The block
 * metadata (blockId/blockType/blockNumber) lets the UI badge each chunk
 * with its owning semantic block and filter the view to the blocks
 * chosen as translation scope; it is null for legacy unbound chunks.
 */
@Getter
@Setter
@ToString
@EqualsAndHashCode
@NoArgsConstructor
public class ChunkDto {
    private UUID id;
    private String originalText;
    private String translatedText;
    private ChunkStatus status;
    private int position;
    private UUID blockId;
    private String blockType;
    private Integer blockNumber;

    /** Legacy shape without block metadata (kept for existing callers/tests). */
    public ChunkDto(UUID id, String originalText, String translatedText, ChunkStatus status, int position) {
        this.id = id;
        this.originalText = originalText;
        this.translatedText = translatedText;
        this.status = status;
        this.position = position;
    }

    /** Full shape with the owning block's metadata for the scope-aware view. */
    public ChunkDto(UUID id, String originalText, String translatedText, ChunkStatus status, int position,
                    UUID blockId, String blockType, Integer blockNumber) {
        this.id = id;
        this.originalText = originalText;
        this.translatedText = translatedText;
        this.status = status;
        this.position = position;
        this.blockId = blockId;
        this.blockType = blockType;
        this.blockNumber = blockNumber;
    }
}
