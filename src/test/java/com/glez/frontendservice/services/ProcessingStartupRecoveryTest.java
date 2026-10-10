package com.glez.frontendservice.services;

import com.glez.frontendservice.model.ChunkStatus;
import com.glez.frontendservice.repository.BookRepository;
import com.glez.frontendservice.repository.ChunksRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Prueba la recuperación de arranque: la cura de chunks huérfanos en
 * PROCESSING (estados "Traduciendo" congelados cuyos hilos murieron con la
 * instancia anterior) corre SIEMPRE — también con auto-resume apagado — y
 * ANTES de reanudar los libros interrumpidos.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProcessingStartupRecovery Tests")
class ProcessingStartupRecoveryTest {

    @Mock
    private BookRepository bookRepository;

    @Mock
    private BookPipelineOrchestrator orchestrator;

    @Mock
    private ChunksRepository chunksRepository;

    private ProcessingStartupRecovery recovery;

    @BeforeEach
    void setUp() {
        recovery = new ProcessingStartupRecovery(bookRepository, orchestrator, chunksRepository);
    }

    @Test
    @DisplayName("Heals orphan PROCESSING chunks even with auto-resume disabled")
    void heal_runsEvenWithoutAutoResume() {
        when(chunksRepository.requeueAllStaleProcessing(ChunkStatus.PROCESSING, ChunkStatus.AWAITING))
                .thenReturn(3);

        // autoResume es un @Value de campo: en un test plano queda en false,
        // que es justo el caso que debe seguir curando (higiene de estado).
        recovery.recoverInterruptedBooks();

        verify(chunksRepository).requeueAllStaleProcessing(ChunkStatus.PROCESSING, ChunkStatus.AWAITING);
        verifyNoInteractions(orchestrator, bookRepository);
    }

    @Test
    @DisplayName("The heal runs BEFORE resuming interrupted books")
    void heal_runsBeforeBookResume() throws Exception {
        setAutoResume(true);
        when(chunksRepository.requeueAllStaleProcessing(any(), any())).thenReturn(0);
        when(bookRepository.findIdsByStatusIn(any())).thenReturn(List.of(UUID.randomUUID()));

        recovery.recoverInterruptedBooks();

        InOrder order = inOrder(chunksRepository, orchestrator);
        order.verify(chunksRepository).requeueAllStaleProcessing(any(), any());
        order.verify(orchestrator).processBook(any());
    }

    /** El @Value de campo no pasa por el constructor: se inyecta por reflexión. */
    private void setAutoResume(boolean value) throws Exception {
        java.lang.reflect.Field field = ProcessingStartupRecovery.class.getDeclaredField("autoResume");
        field.setAccessible(true);
        field.set(recovery, value);
    }
}
