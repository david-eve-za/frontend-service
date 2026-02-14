package com.glez.frontendservice.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChunkDto {
    private UUID id;
    private String originalText;
    private String translatedText;
    private ChunkStatus status;
    private int position;
}
