package com.glez.frontendservice.services;

import com.glez.frontendservice.model.ChunkStatus;
import com.glez.frontendservice.model.Chunks;
import com.glez.frontendservice.repository.ChunksRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChunkTranslationService Tests")
class ChunkTranslationServiceTest {

    @Mock
    private ChunksRepository chunksRepository;

    @Mock
    private NvidiaAiService nvidiaAiService;

    private ChunkTranslationService service;

    private UUID chunkId;
    private Chunks chunk;

    @BeforeEach
    void setUp() {
        service = new ChunkTranslationService(chunksRepository, nvidiaAiService);
        chunkId = UUID.randomUUID();
        chunk = new Chunks();
        chunk.setId(chunkId);
        chunk.setOriginalText("Original text to translate");
        chunk.setStatus(ChunkStatus.AWAITING);
        chunk.setPosition(0);
    }

    @Test
    @DisplayName("Translates the chunk with the glossary and persists COMPLETED")
    void translateChunk_success_persistsCompleted() throws Exception {
        when(chunksRepository.findById(chunkId)).thenReturn(Optional.of(chunk));
        when(chunksRepository.save(any(Chunks.class))).thenAnswer(inv -> inv.getArgument(0));
        when(nvidiaAiService.translateChunk(anyString(), anyString(), anyString(), any()))
                .thenReturn("Translated text");

        // Sin proxy de Spring la llamada es síncrona: ejecuta directo
        service.translateChunk(chunkId, List.of());

        verify(nvidiaAiService).translateChunk(eq("Original text to translate"), eq("en"), eq("es"), any());
        assertEquals(ChunkStatus.COMPLETED, chunk.getStatus());
        assertEquals("Translated text", chunk.getTranslatedText());
        verify(chunksRepository, atLeastOnce()).save(any(Chunks.class));
    }

    @Test
    @DisplayName("A failed translation marks the chunk FAILED and propagates the error")
    void translateChunk_failure_marksFailed() {
        when(chunksRepository.findById(chunkId)).thenReturn(Optional.of(chunk));
        when(chunksRepository.save(any(Chunks.class))).thenAnswer(inv -> inv.getArgument(0));
        when(nvidiaAiService.translateChunk(anyString(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("NVIDIA down"));

        assertThrows(RuntimeException.class, () -> service.translateChunk(chunkId, List.of()));
        assertEquals(ChunkStatus.FAILED, chunk.getStatus());
    }

    @Test
    @DisplayName("Unknown chunk id throws IllegalArgumentException")
    void translateChunk_unknownChunk_throws() {
        when(chunksRepository.findById(chunkId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.translateChunk(chunkId, List.of()));
    }

    @Test
    @DisplayName("A chunk without original text fails fast")
    void translateChunk_noText_throws() {
        chunk.setOriginalText("");
        when(chunksRepository.findById(chunkId)).thenReturn(Optional.of(chunk));

        assertThrows(IllegalArgumentException.class, () -> service.translateChunk(chunkId, List.of()));
    }
}
