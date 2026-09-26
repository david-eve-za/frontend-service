package com.glez.frontendservice.services;

import com.glez.frontendservice.model.Book;
import com.glez.frontendservice.model.Chunks;
import com.glez.frontendservice.model.ChunkStatus;
import com.glez.frontendservice.model.ProcessStep;
import com.glez.frontendservice.model.ProcessTraceEvent;
import com.glez.frontendservice.model.ProcessingStatus;
import com.glez.frontendservice.repository.BookRepository;
import com.glez.frontendservice.repository.ChunksRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BookPipelineOrchestrator Tests")
class BookPipelineOrchestratorTest {

    @Mock
    private BookRepository bookRepository;

    @Mock
    private ChunksRepository chunksRepository;

    @Mock
    private BookProcessingService bookProcessingService;

    @Mock
    private AudioGeneratorService audioGeneratorService;

    @Mock
    private ProcessingTraceService traceService;

    private BookPipelineOrchestrator orchestrator;

    private UUID bookId;
    private Book book;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        orchestrator = new BookPipelineOrchestrator(
                bookRepository, chunksRepository, bookProcessingService, audioGeneratorService, traceService);
        setField(orchestrator, "maxAttempts", 3);

        bookId = UUID.randomUUID();
        book = new Book();
        book.setId(bookId);
        book.setName("mi-libro.pdf");
        book.setStatus(ProcessingStatus.UPLOADED);
        book.setFullText("Texto completo del libro");
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private ProcessTraceEvent traceEvent(ProcessStep step, int attempt) {
        ProcessTraceEvent event = new ProcessTraceEvent();
        event.setId(UUID.randomUUID());
        event.setBook(book);
        event.setTraceId("trace-1");
        event.setStep(step);
        event.setAttempt(attempt);
        return event;
    }

    private void stubTraceEvents() {
        lenient().when(traceService.startStep(eq(bookId), any(), anyString(), any()))
                .thenAnswer(inv -> traceEvent(inv.getArgument(1), 1));
    }

    private Chunks chunk(ChunkStatus status, int position, int attempts, String translated) {
        Chunks chunk = new Chunks();
        chunk.setId(UUID.randomUUID());
        chunk.setBook(book);
        chunk.setOriginalText("original " + position);
        chunk.setTranslatedText(translated);
        chunk.setStatus(status);
        chunk.setPosition(position);
        chunk.setAttempts(attempts);
        return chunk;
    }

    @Test
    @DisplayName("Guards: completed and stopped books are ignored; unknown books throw")
    void processBook_withGuards() {
        book.setStatus(ProcessingStatus.COMPLETED);
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
        orchestrator.processBook(bookId).join();
        verify(traceService, never()).startStep(any(), any(), any(), any());

        book.setStatus(ProcessingStatus.STOPPED);
        orchestrator.processBook(bookId).join();
        verify(traceService, never()).startStep(any(), any(), any(), any());

        when(bookRepository.findById(bookId)).thenReturn(Optional.empty());
        assertThrows(RuntimeException.class, () -> orchestrator.processBook(bookId));
    }

    @Test
    @DisplayName("Happy path: extract skipped, split, translate, audio and finalize complete")
    void processBook_happyPath_completesBook() {
        stubTraceEvents();
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));

        // After split: 2 chunks awaiting translation
        List<Chunks> awaiting = List.of(
                chunk(ChunkStatus.AWAITING, 0, 0, null),
                chunk(ChunkStatus.AWAITING, 1, 0, null));
        // After translation: both completed
        List<Chunks> completed = List.of(
                chunk(ChunkStatus.COMPLETED, 0, 1, "traducido 0"),
                chunk(ChunkStatus.COMPLETED, 1, 1, "traducido 1"));

        when(chunksRepository.findByBook(book))
                .thenReturn(List.of())   // split check: empty
                .thenReturn(awaiting)   // split count
                .thenReturn(awaiting)   // translate pending evaluation
                .thenReturn(completed)   // translate failed-check
                .thenReturn(completed); // audio join

        when(bookProcessingService.processBookChunksAsync(bookId))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(audioGeneratorService.processTextToAudio(anyString(), any(Path.class)))
                .thenReturn(true);

        orchestrator.processBook(bookId).join();

        // EXTRACT skipped because the text already exists
        verify(traceService).skipStep(eq(bookId), eq(ProcessStep.EXTRACT), anyString(), anyString());

        // SPLIT executed
        verify(bookProcessingService).splitBookIntoChunks(bookId);

        // TRANSLATE: chunks re-queued with attempts incremented
        verify(chunksRepository, times(2)).save(any(Chunks.class));
        verify(bookProcessingService).processBookChunksAsync(bookId);

        // AUDIO generated with .mp3 path derived from the book name
        ArgumentCaptor<Path> audioPath = ArgumentCaptor.forClass(Path.class);
        verify(audioGeneratorService).processTextToAudio(eq("traducido 0\n\ntraducido 1"), audioPath.capture());
        assertEquals(Path.of("mi-libro.mp3"), audioPath.getValue());

        // FINALIZE: book completed with artifact persisted
        assertEquals(ProcessingStatus.COMPLETED, book.getStatus());
        assertEquals("mi-libro.mp3", book.getAudioFilePath());

        verify(traceService, atLeastOnce()).completeStep(any(), any());
        verify(traceService, never()).failStep(any(), any());
    }

    @Test
    @DisplayName("Resume: chunks left in PROCESSING by a crash are re-queued")
    void processBook_withStaleProcessingChunk_requeuesIt() {
        stubTraceEvents();
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));

        Chunks stale = chunk(ChunkStatus.PROCESSING, 0, 1, null);
        Chunks done = chunk(ChunkStatus.COMPLETED, 1, 1, "ya traducido");
        List<Chunks> mixed = List.of(stale, done);
        List<Chunks> completed = List.of(
                chunk(ChunkStatus.COMPLETED, 0, 2, "recuperado"),
                done);

        when(chunksRepository.findByBook(book))
                .thenReturn(mixed)     // split check: not empty -> skip
                .thenReturn(mixed)     // translate pending evaluation
                .thenReturn(completed) // translate failed-check
                .thenReturn(completed);// audio join

        when(bookProcessingService.processBookChunksAsync(bookId))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(audioGeneratorService.processTextToAudio(anyString(), any(Path.class)))
                .thenReturn(true);

        orchestrator.processBook(bookId).join();

        // SPLIT was skipped, the stale chunk was re-queued
        verify(bookProcessingService, never()).splitBookIntoChunks(bookId);
        assertEquals(ChunkStatus.AWAITING, stale.getStatus());
        assertEquals(2, stale.getAttempts());
        verify(bookProcessingService).processBookChunksAsync(bookId);
        assertEquals(ProcessingStatus.COMPLETED, book.getStatus());
    }

    @Test
    @DisplayName("Translation failure marks the book FAILED and persists the step error")
    void processBook_whenTranslationFails_marksBookFailed() {
        stubTraceEvents();
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));

        Chunks pendingChunk = chunk(ChunkStatus.FAILED, 0, 1, null);
        // After the (mocked) translation pass the chunk remains FAILED.
        Chunks failedAfter = chunk(ChunkStatus.FAILED, 0, 2, null);
        List<Chunks> pending = List.of(pendingChunk);
        List<Chunks> afterFailure = List.of(failedAfter);

        when(chunksRepository.findByBook(book))
                .thenReturn(pending)    // split check: not empty
                .thenReturn(pending)    // translate pending
                .thenReturn(afterFailure); // translate failed-check

        when(bookProcessingService.processBookChunksAsync(bookId))
                .thenReturn(CompletableFuture.completedFuture(null));

        assertThrows(RuntimeException.class, () -> orchestrator.processBook(bookId).join());

        // Step event marked FAILED with the error and the book marked FAILED
        verify(traceService, times(1)).failStep(any(), any());
        assertEquals(ProcessingStatus.FAILED, book.getStatus());
        // No audio attempted after a failed translation
        verify(audioGeneratorService, never()).processTextToAudio(anyString(), any());
        verify(bookProcessingService, never()).splitBookIntoChunks(bookId);
    }

    @Test
    @DisplayName("A chunk that exhausted its attempts aborts the run")
    void processBook_whenChunkExceedsMaxAttempts_aborts() {
        stubTraceEvents();
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));

        Chunks exhausted = chunk(ChunkStatus.FAILED, 0, 3, null); // attempts == maxAttempts
        List<Chunks> pending = List.of(exhausted);
        when(chunksRepository.findByBook(book))
                .thenReturn(pending)  // split check
                .thenReturn(pending); // translate pending

        assertThrows(RuntimeException.class, () -> orchestrator.processBook(bookId).join());

        verify(traceService).failStep(any(), any());
        assertEquals(ProcessingStatus.FAILED, book.getStatus());
        // The exhausted chunk is not re-queued
        verify(bookProcessingService, never()).processBookChunksAsync(bookId);
    }

    @Test
    @DisplayName("AUDIO is skipped when the artifact already exists on disk")
    void processBook_withExistingAudio_skipsAudioStep() throws Exception {
        stubTraceEvents();
        Path existingAudio = tempDir.resolve("mi-libro.mp3");
        Files.writeString(existingAudio, "fake mp3");
        book.setAudioFilePath(existingAudio.toString());
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));

        List<Chunks> completed = List.of(chunk(ChunkStatus.COMPLETED, 0, 1, "traducido"));
        when(chunksRepository.findByBook(book))
                .thenReturn(completed) // split check
                .thenReturn(completed) // translate pending (empty)
                .thenReturn(completed); // not reached for audio join

        orchestrator.processBook(bookId).join();

        verify(traceService).skipStep(eq(bookId), eq(ProcessStep.AUDIO), anyString(), anyString());
        verify(audioGeneratorService, never()).processTextToAudio(anyString(), any());
        assertEquals(ProcessingStatus.COMPLETED, book.getStatus());
    }

    @Test
    @DisplayName("A book without extracted text fails the pipeline with a clear error")
    void processBook_withoutFullText_failsWithClearError() {
        stubTraceEvents();
        book.setFullText(null);
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));

        assertThrows(RuntimeException.class, () -> orchestrator.processBook(bookId).join());

        verify(traceService).failStep(any(), any());
        assertEquals(ProcessingStatus.FAILED, book.getStatus());
        verify(bookProcessingService, never()).splitBookIntoChunks(bookId);
    }

    @Test
    @DisplayName("audioPathFor forces the .mp3 extension")
    void audioPathFor_variants() {
        assertEquals(Path.of("mi-libro.mp3"), BookPipelineOrchestrator.audioPathFor(book));
        book.setName("libro");
        assertEquals(Path.of("libro.mp3"), BookPipelineOrchestrator.audioPathFor(book));
        book.setName("carpeta/libro.epub");
        assertEquals(Path.of("carpeta/libro.mp3"), BookPipelineOrchestrator.audioPathFor(book));
        book.setName(null);
        assertEquals(Path.of("book.mp3"), BookPipelineOrchestrator.audioPathFor(book));
    }

    @Test
    @DisplayName("A second concurrent run of the same book is rejected")
    void processBook_rejectsConcurrentRuns() throws Exception {
        stubTraceEvents();
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));

        // A chunk awaiting translation: the first run will block inside the
        // TRANSLATE phase waiting for the (never-completing) translation future.
        Chunks awaiting = chunk(ChunkStatus.AWAITING, 0, 0, null);
        Chunks done = chunk(ChunkStatus.COMPLETED, 0, 1, "traducido");
        when(chunksRepository.findByBook(book))
                .thenReturn(List.of(awaiting)) // split check: skip
                .thenReturn(List.of(awaiting)) // translate pending
                .thenReturn(List.of(done))    // translate failed-check after unblock
                .thenReturn(List.of(done));   // audio join

        CompletableFuture<Void> blocked = new CompletableFuture<>();
        when(bookProcessingService.processBookChunksAsync(bookId)).thenReturn(blocked);
        when(audioGeneratorService.processTextToAudio(anyString(), any(Path.class))).thenReturn(true);

        // Direct calls are synchronous in unit tests, so run the first one
        // in a background thread.
        CompletableFuture<Void> first = CompletableFuture.runAsync(
                () -> orchestrator.processBook(bookId).join());

        // Wait until the first run holds the lock (inside TRANSLATE).
        long deadline = System.currentTimeMillis() + 5000;
        while (!orchestrator.isRunning(bookId) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(orchestrator.isRunning(bookId), "First run should hold the lock");

        // The second run is rejected synchronously.
        assertThrows(IllegalStateException.class, () -> orchestrator.processBook(bookId));

        // The first run keeps the lock and completes normally after unblocking.
        blocked.complete(null);
        first.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(ProcessingStatus.COMPLETED, book.getStatus());
    }
}
