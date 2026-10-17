package com.glez.frontendservice.services;

import com.glez.frontendservice.model.Book;
import com.glez.frontendservice.model.ChunkStatus;
import com.glez.frontendservice.model.Chunks;
import com.glez.frontendservice.repository.ChunksRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests de {@link ChunkTranslationService}: el estado por chunk y la CAUSA
 * del fallo que persiste en error_message — la base del popup en vivo del
 * Traducir Libro.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Chunk translation service tests")
class ChunkTranslationServiceTest {

    @Mock
    private ChunksRepository chunksRepository;

    @Mock
    private NvidiaAiService nvidiaAiService;

    private ChunkTranslationService service;

    private final UUID chunkId = UUID.randomUUID();

    private Chunks chunk;

    @BeforeEach
    void setUp() {
        service = new ChunkTranslationService(chunksRepository, nvidiaAiService);
        Book book = new Book();
        book.setId(UUID.randomUUID());
        chunk = new Chunks();
        chunk.setId(chunkId);
        chunk.setBook(book);
        chunk.setOriginalText("Some long text to translate into Spanish.");
        chunk.setPosition(1);
        when(chunksRepository.findById(chunkId)).thenReturn(Optional.of(chunk));
        // Lenient: el test de fail-fast (sin texto) aborta antes de guardar.
        org.mockito.Mockito.lenient()
                .when(chunksRepository.save(any(Chunks.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("Éxito: chunk COMPLETED y SIN error residual")
    void translateChunk_success_completesWithoutError() throws Exception {
        when(nvidiaAiService.translateChunk(anyString(), anyString(), anyString(), anyList()))
                .thenReturn("Un texto largo para traducir al español.");

        CompletableFuture<Void> future = service.translateChunk(chunkId, java.util.List.of());
        future.get();

        assertEquals(ChunkStatus.COMPLETED, chunk.getStatus());
        assertEquals("Un texto largo para traducir al español.", chunk.getTranslatedText());
        assertNull(chunk.getErrorMessage(), "Un chunk sano no lleva causa de error");
    }

    @Test
    @DisplayName("Fallo: chunk FAILED y la CAUSA persistida para el popup del FE")
    void translateChunk_failure_persistsConciseCause() {
        when(nvidiaAiService.translateChunk(anyString(), anyString(), anyString(), anyList()))
                .thenThrow(new RuntimeException("Translation failed for chunk x",
                        new IllegalStateException("All models exhausted after 21 attempts")));

        try {
            // Sin el proxy @Async de Spring, el fallo se lanza sincrónicamente
            // (en producción viaja envuelto en el CompletableFuture).
            service.translateChunk(chunkId, java.util.List.of());
        } catch (RuntimeException expected) {
            // Desenlace esperado: "Translation failed for chunk …".
        }

        ArgumentCaptor<Chunks> saved = ArgumentCaptor.forClass(Chunks.class);
        verify(chunksRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        Chunks persisted = saved.getValue();
        assertEquals(ChunkStatus.FAILED, persisted.getStatus());
        // La causa profunda (el error real de la rotación) queda en el chunk:
        // el popup en vivo del FE la muestra sin abrir el Visor de Logs.
        assertTrue(persisted.getErrorMessage().contains("All models exhausted"),
                "La causa profunda debe persistir: " + persisted.getErrorMessage());
        assertTrue(persisted.getErrorMessage().contains("Translation failed for chunk x"),
                "La cadena completa de causas: " + persisted.getErrorMessage());
    }

    @Test
    @DisplayName("Reintento: el PROCESSING limpia la causa del fallo anterior")
    void translateChunk_retryClearsPreviousError() throws Exception {
        chunk.setErrorMessage("fallo anterior");
        when(nvidiaAiService.translateChunk(anyString(), anyString(), anyString(), anyList()))
                .thenReturn("Traducción nueva.");

        service.translateChunk(chunkId, java.util.List.of()).get();

        assertNull(chunk.getErrorMessage(), "El reintento limpia la causa vieja");
        assertEquals(ChunkStatus.COMPLETED, chunk.getStatus());
    }

    @Test
    @DisplayName("A chunk without original text fails fast")
    void translateChunk_noText_throws() {
        chunk.setOriginalText("");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.translateChunk(chunkId, java.util.List.of()));
        assertTrue(e.getMessage().contains("no original text"), e.getMessage());
    }
}
