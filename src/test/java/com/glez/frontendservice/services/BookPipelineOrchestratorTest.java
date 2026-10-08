package com.glez.frontendservice.services;

import com.glez.frontendservice.model.Book;
import com.glez.frontendservice.model.Block;
import com.glez.frontendservice.model.Chunks;
import com.glez.frontendservice.model.ChunkStatus;
import com.glez.frontendservice.model.ProcessStep;
import com.glez.frontendservice.model.ProcessTraceEvent;
import com.glez.frontendservice.model.ProcessingStatus;
import com.glez.frontendservice.repository.BookRepository;
import com.glez.frontendservice.repository.BookRepository.BookStatusView;
import com.glez.frontendservice.repository.ChunksRepository;
import com.glez.frontendservice.repository.ChunksRepository.ChunkStatusView;
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
    private com.glez.frontendservice.repository.BlockRepository blockRepository;

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
                bookRepository, chunksRepository, blockRepository, bookProcessingService,
                audioGeneratorService, traceService);
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

    /** Common read-path stubs: extracted text exists and the book has chunks. */
    private void stubExtractedText() {
        lenient().when(bookRepository.hasExtractedText(bookId)).thenReturn(true);
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

    private Chunks chunk(Block block, ChunkStatus status, int position, int attempts, String translated) {
        Chunks chunk = chunk(status, position, attempts, translated);
        chunk.setBlock(block);
        return chunk;
    }

    /** Light (id, status, attempts) projection row used by the TRANSLATE step. */
    private record ChunkView(UUID id, ChunkStatus status, Integer attempts) implements ChunkStatusView {
        @Override
        public UUID getId() {
            return id;
        }

        @Override
        public ChunkStatus getStatus() {
            return status;
        }

        @Override
        public Integer getAttempts() {
            return attempts;
        }
    }

    private ChunkView view(Chunks chunk) {
        return new ChunkView(chunk.getId(), chunk.getStatus(), chunk.getAttempts());
    }

    private void stubChunkViews(List<Chunks> chunks) {
        // Explicit List<ChunkStatusView>: List<ChunkView> is not assignable
        // to it (generics invariance would fail the mock stub).
        java.util.List<ChunkStatusView> views = new java.util.ArrayList<>(chunks.size());
        for (Chunks chunk : chunks) {
            views.add(view(chunk));
        }
        when(chunksRepository.findStatusViewsByBookId(bookId)).thenReturn(views);
    }

    /** Light entry-state projection row returned by findStatusViews. */
    private record BookView(UUID id, ProcessingStatus status, ProcessStep currentStep,
                            String lastTraceId, String audioFilePath) implements BookStatusView {
        @Override
        public UUID getId() {
            return id;
        }

        @Override
        public ProcessingStatus getStatus() {
            return status;
        }

        @Override
        public ProcessStep getCurrentStep() {
            return currentStep;
        }

        @Override
        public String getLastTraceId() {
            return lastTraceId;
        }

        @Override
        public String getAudioFilePath() {
            return audioFilePath;
        }
    }

    /** Entry guard stub: the pipeline reads the book status as a light projection. */
    private void stubBookState(ProcessingStatus status) {
        lenient().when(bookRepository.findStatusViews(List.of(bookId)))
                .thenReturn(List.<BookStatusView>of(
                        new BookView(bookId, status, null, null, book.getAudioFilePath())));
    }

    @Test
    @DisplayName("Guards: completed and stopped books are ignored; unknown books throw")
    void processBook_withGuards() {
        when(bookRepository.findStatusViews(List.of(bookId)))
                .thenReturn(List.<BookStatusView>of(
                        new BookView(bookId, ProcessingStatus.COMPLETED, null, null, null)))
                .thenReturn(List.<BookStatusView>of(
                        new BookView(bookId, ProcessingStatus.STOPPED, null, null, null)))
                .thenReturn(List.of());
        orchestrator.processBook(bookId).join();
        verify(traceService, never()).startStep(any(), any(), any(), any());

        orchestrator.processBook(bookId).join();
        verify(traceService, never()).startStep(any(), any(), any(), any());

        assertThrows(RuntimeException.class, () -> orchestrator.processBook(bookId));
    }

    @Test
    @DisplayName("targetStep=TRANSLATE: runs up to translate and marks the book AWAITING without audio")
    void processBook_upToTranslate_skipsAudioAndMarksAwaiting() {
        stubTraceEvents();
        stubExtractedText();
        stubBookState(ProcessingStatus.UPLOADED);
        Chunks done = chunk(ChunkStatus.COMPLETED, 0, 1, "texto traducido");
        lenient().when(chunksRepository.countByBookId(bookId)).thenReturn(1L);
        stubChunkViews(List.of(done));

        orchestrator.processBook(bookId, ProcessStep.TRANSLATE).join();

        verify(audioGeneratorService, never()).processTextToAudio(any(), any());
        // The AWAITING transition is a guarded bulk update (PROCESSING -> AWAITING)
        verify(bookRepository).updateStatusWhen(bookId, ProcessingStatus.PROCESSING, ProcessingStatus.AWAITING);
        assertNull(book.getAudioFilePath());
    }

    @Test
    @DisplayName("targetStep=AUDIO: generates the audio file but does not finalize")
    void processBook_upToAudio_generatesAudioWithoutFinalize() {
        stubTraceEvents();
        stubExtractedText();
        stubBookState(ProcessingStatus.UPLOADED);
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book)); // audio step loads the book
        lenient().when(bookRepository.save(any(Book.class))).thenReturn(book);
        Chunks done = chunk(ChunkStatus.COMPLETED, 0, 1, "texto traducido");
        lenient().when(chunksRepository.countByBookId(bookId)).thenReturn(1L);
        stubChunkViews(List.of(done));
        when(chunksRepository.findByBook(book)).thenReturn(List.of(done));
        when(audioGeneratorService.processTextToAudio(any(), any())).thenReturn(true);

        orchestrator.processBook(bookId, ProcessStep.AUDIO).join();

        verify(audioGeneratorService, times(1)).processTextToAudio(any(), any());
        verify(bookRepository).updateStatusWhen(bookId, ProcessingStatus.PROCESSING, ProcessingStatus.AWAITING);
        assertNotNull(book.getAudioFilePath());
    }

    @Test
    @DisplayName("Happy path: extract skipped, split, translate, audio and finalize complete")
    void processBook_happyPath_completesBook() {
        stubTraceEvents();
        stubExtractedText();
        stubBookState(ProcessingStatus.UPLOADED);
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book)); // audio step loads the book

        // After split: 2 chunks awaiting translation
        List<Chunks> awaiting = List.of(
                chunk(ChunkStatus.AWAITING, 0, 0, null),
                chunk(ChunkStatus.AWAITING, 1, 0, null));
        // After translation: both completed
        List<Chunks> completed = List.of(
                chunk(ChunkStatus.COMPLETED, 0, 1, "traducido 0"),
                chunk(ChunkStatus.COMPLETED, 1, 1, "traducido 1"));

        when(chunksRepository.countByBookId(bookId))
                .thenReturn(0L)  // split check: no chunks yet
                .thenReturn(2L); // count after the split
        stubChunkViews(awaiting);
        when(chunksRepository.findByBook(book)).thenReturn(completed); // audio join

        when(bookProcessingService.processBookChunksAsync(bookId))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(audioGeneratorService.processTextToAudio(anyString(), any(Path.class)))
                .thenReturn(true);

        orchestrator.processBook(bookId).join();

        // EXTRACT skipped because the text already exists
        verify(traceService).skipStep(eq(bookId), eq(ProcessStep.EXTRACT), anyString(), anyString());

        // SPLIT executed
        // Full-book split goes through the block-aware 2-arg call (null =
        // all blocks when they exist, whole text otherwise)
        verify(bookProcessingService).splitBookIntoChunks(bookId, null);

        // TRANSLATE: attempts are not consumed upfront anymore; a successful
        // run never touches the counters
        verify(chunksRepository, never()).save(any(Chunks.class));
        verify(chunksRepository, never()).incrementFailedAttempts(any(), any());
        verify(bookProcessingService).processBookChunksAsync(bookId);

        // AUDIO generated with .mp3 path derived from the book name
        ArgumentCaptor<Path> audioPath = ArgumentCaptor.forClass(Path.class);
        verify(audioGeneratorService).processTextToAudio(eq("traducido 0\n\ntraducido 1"), audioPath.capture());
        assertEquals(Path.of("mi-libro.mp3"), audioPath.getValue());

        // FINALIZE: book completed via the guarded bulk status update
        verify(bookRepository).updateStatusWhen(bookId, ProcessingStatus.PROCESSING,
                ProcessingStatus.COMPLETED);
        assertEquals("mi-libro.mp3", book.getAudioFilePath());

        verify(traceService, atLeastOnce()).completeStep(any(), any());
        verify(traceService, never()).failStep(any(), any());
    }

    @Test
    @DisplayName("Resume: chunks left in PROCESSING by a crash are re-queued")
    void processBook_withStaleProcessingChunk_requeuesIt() {
        stubTraceEvents();
        stubExtractedText();
        stubBookState(ProcessingStatus.UPLOADED);
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book)); // audio step loads the book

        Chunks stale = chunk(ChunkStatus.PROCESSING, 0, 1, null);
        Chunks done = chunk(ChunkStatus.COMPLETED, 1, 1, "ya traducido");
        List<Chunks> mixed = List.of(stale, done);
        List<Chunks> completed = List.of(
                chunk(ChunkStatus.COMPLETED, 0, 0, "recuperado"),
                done);

        when(chunksRepository.countByBookId(bookId)).thenReturn(2L); // split check: chunks exist -> skip
        stubChunkViews(mixed);
        when(chunksRepository.findByBook(book)).thenReturn(completed); // audio join

        when(bookProcessingService.processBookChunksAsync(bookId))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(audioGeneratorService.processTextToAudio(anyString(), any(Path.class)))
                .thenReturn(true);

        orchestrator.processBook(bookId).join();

        // SPLIT was skipped, the stale chunk was re-queued by the bulk update
        verify(bookProcessingService, never()).splitBookIntoChunks(bookId);
        verify(chunksRepository).requeueByStatus(eq(bookId),
                eq(List.of(ChunkStatus.FAILED, ChunkStatus.PROCESSING)), eq(ChunkStatus.AWAITING));
        // An interrupted run must not consume chunk attempts
        verify(chunksRepository, never()).incrementFailedAttempts(any(), any());
        verify(bookProcessingService).processBookChunksAsync(bookId);
        verify(bookRepository).updateStatusWhen(bookId, ProcessingStatus.PROCESSING,
                ProcessingStatus.COMPLETED);
    }

    @Test
    @DisplayName("A genuine translation failure marks the book FAILED and consumes one chunk attempt")
    void processBook_whenTranslationFails_marksBookFailed() {
        stubTraceEvents();
        stubExtractedText();
        stubBookState(ProcessingStatus.UPLOADED);

        Chunks pendingChunk = chunk(ChunkStatus.FAILED, 0, 1, null);
        UUID failedChunkId = pendingChunk.getId();

        when(chunksRepository.countByBookId(bookId)).thenReturn(1L); // split check: not empty
        stubChunkViews(List.of(pendingChunk));
        when(chunksRepository.incrementFailedAttempts(bookId, ChunkStatus.COMPLETED)).thenReturn(1);
        when(chunksRepository.findIdsByBookIdAndStatusNot(bookId, ChunkStatus.COMPLETED))
                .thenReturn(List.of(failedChunkId));

        when(bookProcessingService.processBookChunksAsync(bookId))
                .thenReturn(CompletableFuture.failedFuture(
                        new RuntimeException("Translation failed for chunk " + failedChunkId)));

        assertThrows(RuntimeException.class, () -> orchestrator.processBook(bookId).join());

        // Step event marked FAILED with the error and the book marked FAILED
        verify(traceService, times(1)).failStep(any(), any());
        verify(bookRepository).updateStatusAndTrace(eq(bookId), eq(ProcessingStatus.FAILED), anyString());
        // The genuinely failed chunk consumed exactly one attempt (bulk update)
        verify(chunksRepository).incrementFailedAttempts(bookId, ChunkStatus.COMPLETED);
        // No audio attempted after a failed translation
        verify(audioGeneratorService, never()).processTextToAudio(anyString(), any());
        verify(bookProcessingService, never()).splitBookIntoChunks(bookId);
    }

    @Test
    @DisplayName("A chunk that exhausted its attempts aborts the run")
    void processBook_whenChunkExceedsMaxAttempts_aborts() {
        stubTraceEvents();
        stubExtractedText();
        stubBookState(ProcessingStatus.UPLOADED);
        // Three genuine TRANSLATE failures back the exhausted counter
        when(traceService.countGenuineAttempts(eq(bookId), eq(ProcessStep.TRANSLATE)))
                .thenReturn(3);

        Chunks exhausted = chunk(ChunkStatus.FAILED, 0, 3, null); // attempts == maxAttempts
        when(chunksRepository.countByBookId(bookId)).thenReturn(1L); // split check
        stubChunkViews(List.of(exhausted));

        assertThrows(RuntimeException.class, () -> orchestrator.processBook(bookId).join());

        verify(traceService).failStep(any(), any());
        verify(bookRepository).updateStatusAndTrace(eq(bookId), eq(ProcessingStatus.FAILED), anyString());
        // The exhausted chunk is not re-queued
        verify(bookProcessingService, never()).processBookChunksAsync(bookId);
    }

    @Test
    @DisplayName("Block-scoped run regenerates chunks from the selected blocks even when chunks exist")
    void processBook_withBlockScope_resplitsFromSelectedBlocks() {
        stubTraceEvents();
        stubExtractedText();
        stubBookState(ProcessingStatus.UPLOADED);

        UUID blockId = UUID.randomUUID();
        // Existing chunks: the full-book path would SKIP the split; the
        // block-scoped path must regenerate them anyway.
        Chunks regenerated = chunk(ChunkStatus.AWAITING, 0, 0, null);
        when(chunksRepository.countByBookId(bookId))
                .thenReturn(1L)  // (existing chunks: irrelevant for the block path)
                .thenReturn(1L); // count after the block-scoped split
        stubChunkViews(List.of(regenerated));

        when(bookProcessingService.processBookChunksAsync(bookId))
                .thenReturn(CompletableFuture.completedFuture(null));

        orchestrator.processBook(bookId, ProcessStep.TRANSLATE, List.of(blockId)).join();

        // The block-scoped split ran (not skipped) with the block ids
        verify(bookProcessingService).splitBookIntoChunks(bookId, List.of(blockId));
        verify(bookProcessingService, never()).splitBookIntoChunks(bookId);
        // Translation proceeded over the regenerated chunks
        verify(bookProcessingService).processBookChunksAsync(bookId);
        verify(bookRepository).updateStatusWhen(bookId, ProcessingStatus.PROCESSING, ProcessingStatus.AWAITING);
    }

    @Test
    @DisplayName("A manual run proceeds past an exhausted step budget with a warning")
    void processBook_manualRun_bypassesStepBudget() {
        stubExtractedText();
        // startStep reports attempt 4: the budget (3) is exhausted
        when(traceService.startStep(eq(bookId), any(), anyString(), any()))
                .thenAnswer(inv -> traceEvent(inv.getArgument(1), 4));
        stubBookState(ProcessingStatus.UPLOADED);

        Chunks awaiting = chunk(ChunkStatus.AWAITING, 0, 0, null);
        lenient().when(chunksRepository.countByBookId(bookId)).thenReturn(1L); // split check: chunks exist -> skip
        stubChunkViews(List.of(awaiting));

        when(bookProcessingService.processBookChunksAsync(bookId))
                .thenReturn(CompletableFuture.completedFuture(null));

        // 2-arg overload = manual (user-triggered): must NOT abort
        orchestrator.processBook(bookId, ProcessStep.TRANSLATE).join();

        verify(bookProcessingService).processBookChunksAsync(bookId);
        verify(traceService, never()).failStep(any(), any());
        verify(traceService).completeStep(any(), eq("All chunks translated"));
    }

    @Test
    @DisplayName("An automatic run still aborts when the step budget is exhausted")
    void processBook_automaticRun_abortsOnStepBudget() {
        stubExtractedText();
        when(traceService.startStep(eq(bookId), any(), anyString(), any()))
                .thenAnswer(inv -> traceEvent(inv.getArgument(1), 4));
        stubBookState(ProcessingStatus.UPLOADED);

        Chunks awaiting = chunk(ChunkStatus.AWAITING, 0, 0, null);
        when(chunksRepository.countByBookId(bookId)).thenReturn(1L); // split check -> skip
        stubChunkViews(List.of(awaiting));

        // 1-arg overload = automatic (startup recovery): must abort
        assertThrows(RuntimeException.class, () -> orchestrator.processBook(bookId).join());

        verify(traceService).failStep(any(), any());
        verify(bookRepository).updateStatusAndTrace(eq(bookId), eq(ProcessingStatus.FAILED), anyString());
        verify(bookProcessingService, never()).processBookChunksAsync(bookId);
    }

    @Test
    @DisplayName("A manual run retries chunks that exhausted their attempts")
    void processBook_manualRun_bypassesExhaustedChunks() {
        stubTraceEvents();
        stubExtractedText();
        stubBookState(ProcessingStatus.UPLOADED);
        // Three genuine failures back the exhausted counters
        when(traceService.countGenuineAttempts(eq(bookId), eq(ProcessStep.TRANSLATE)))
                .thenReturn(3);

        Chunks exhausted = chunk(ChunkStatus.FAILED, 0, 3, null);
        when(chunksRepository.countByBookId(bookId)).thenReturn(1L); // split check -> skip
        stubChunkViews(List.of(exhausted));

        when(bookProcessingService.processBookChunksAsync(bookId))
                .thenReturn(CompletableFuture.completedFuture(null));

        orchestrator.processBook(bookId, ProcessStep.TRANSLATE).join();

        verify(bookProcessingService).processBookChunksAsync(bookId);
        verify(traceService, never()).failStep(any(), any());
    }

    @Test
    @DisplayName("Block books generate one audio file per block inside the audiolibro directory")
    void processBook_blockBook_generatesAudioPerBlock() throws Exception {
        stubTraceEvents();
        stubExtractedText();
        stubBookState(ProcessingStatus.UPLOADED);
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book)); // audio step loads the book
        book.setStatus(ProcessingStatus.AWAITING);
        // Audio files go inside the JUnit temp dir so the test never
        // writes into the repository working directory.
        book.setName(tempDir.resolve("mi-libro").toString());

        // Two blocks: prologue + chapter 1
        Block prologue = new Block();
        prologue.setId(UUID.randomUUID());
        prologue.setBook(book);
        prologue.setBlockType(Block.BlockType.PROLOGUE);
        prologue.setBlockNumber(1);
        prologue.setPosition(0);
        Block chapter = new Block();
        chapter.setId(UUID.randomUUID());
        chapter.setBook(book);
        chapter.setBlockType(Block.BlockType.CHAPTER);
        chapter.setBlockNumber(1);
        chapter.setPosition(1);
        when(blockRepository.findByBookIdOrderByPositionAsc(bookId)).thenReturn(List.of(prologue, chapter));

        // Translated chunks per block (and for the translate step's evaluation)
        Chunks prologueChunk = chunk(prologue, ChunkStatus.COMPLETED, 0, 1, "prólogo traducido");
        Chunks chapterChunk = chunk(chapter, ChunkStatus.COMPLETED, 0, 1, "capítulo traducido");
        lenient().when(chunksRepository.countByBookId(bookId)).thenReturn(2L); // split check
        stubChunkViews(List.of(prologueChunk, chapterChunk));
        when(chunksRepository.findBlockChunksByBookId(bookId))
                .thenReturn(List.of(prologueChunk, chapterChunk));

        when(audioGeneratorService.processTextToAudio(anyString(), any(Path.class))).thenAnswer(inv -> {
            Path output = inv.getArgument(1);
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
            Files.writeString(output, "fake mp3");
            return true;
        });
        when(bookRepository.save(any(Book.class))).thenReturn(book);

        orchestrator.processBook(bookId).join();

        // One audio call per block, with per-block files inside the audiolibro directory
        org.mockito.ArgumentCaptor<Path> paths = org.mockito.ArgumentCaptor.forClass(Path.class);
        verify(audioGeneratorService, times(2)).processTextToAudio(anyString(), paths.capture());
        List<Path> captured = paths.getAllValues();
        org.junit.jupiter.api.Assertions.assertTrue(captured.get(0).toString()
                .endsWith("01 - Prologo.mp3"), captured.get(0).toString());
        org.junit.jupiter.api.Assertions.assertTrue(captured.get(1).toString()
                .endsWith("02 - Capitulo 01.mp3"), captured.get(1).toString());
        org.junit.jupiter.api.Assertions.assertTrue(captured.get(0).getParent().toString()
                .endsWith("mi-libro - audiolibro"), captured.get(0).toString());

        // Each block persists its audio path and the book points to the directory
        org.junit.jupiter.api.Assertions.assertNotNull(prologue.getAudioFilePath());
        org.junit.jupiter.api.Assertions.assertNotNull(chapter.getAudioFilePath());
        org.junit.jupiter.api.Assertions.assertTrue(book.getAudioFilePath().endsWith("mi-libro - audiolibro"));
        verify(traceService).completeStep(any(), org.mockito.ArgumentMatchers.argThat(
                (String details) -> details != null && details.contains("2 block audio(s) generated")));
    }

    @Test
    @DisplayName("Legacy attempt inflation from interrupted runs is healed before the retry")
    void processBook_withInflatedLegacyAttempts_healsAndRetries() {
        stubTraceEvents();
        stubExtractedText();
        stubBookState(ProcessingStatus.UPLOADED);
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book)); // audio step loads the book
        // The book lived through 2 interrupted runs + 1 genuine failure
        when(traceService.countGenuineAttempts(eq(bookId), eq(ProcessStep.TRANSLATE)))
                .thenReturn(1);

        // All chunks carry attempts=3 inflated by the interrupted runs
        Chunks inflated = chunk(ChunkStatus.AWAITING, 0, 3, null);
        List<Chunks> pending = List.of(inflated);
        List<Chunks> completed = List.of(chunk(ChunkStatus.COMPLETED, 0, 1, "traducido"));

        when(chunksRepository.countByBookId(bookId)).thenReturn(1L); // split check: not empty -> skip
        stubChunkViews(pending);
        when(chunksRepository.findByBook(book)).thenReturn(completed); // audio join

        when(bookProcessingService.processBookChunksAsync(bookId))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(audioGeneratorService.processTextToAudio(anyString(), any(Path.class)))
                .thenReturn(true);

        orchestrator.processBook(bookId).join();

        // The counter was clamped to the genuine failure count by the bulk
        // heal, the guard passed and the translation retried instead of
        // blocking the book
        verify(chunksRepository).healAttempts(eq(bookId), any(), eq(1));
        verify(bookProcessingService).processBookChunksAsync(bookId);
        // FINALIZE completes the book via the guarded bulk status update
        verify(bookRepository).updateStatusWhen(bookId, ProcessingStatus.PROCESSING,
                ProcessingStatus.COMPLETED);
    }

    @Test
    @DisplayName("AUDIO is skipped when the artifact already exists on disk")
    void processBook_withExistingAudio_skipsAudioStep() throws Exception {
        stubTraceEvents();
        stubExtractedText();
        stubBookState(ProcessingStatus.UPLOADED);
        Path existingAudio = tempDir.resolve("mi-libro.mp3");
        Files.writeString(existingAudio, "fake mp3");
        book.setAudioFilePath(existingAudio.toString());
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book)); // audio step loads the book

        List<Chunks> completed = List.of(chunk(ChunkStatus.COMPLETED, 0, 1, "traducido"));
        when(chunksRepository.countByBookId(bookId)).thenReturn(1L); // split check
        stubChunkViews(completed); // translate pending (empty)

        orchestrator.processBook(bookId).join();

        verify(traceService).skipStep(eq(bookId), eq(ProcessStep.AUDIO), anyString(), anyString());
        verify(audioGeneratorService, never()).processTextToAudio(anyString(), any());
        // FINALIZE completes the book via the guarded bulk status update
        verify(bookRepository).updateStatusWhen(bookId, ProcessingStatus.PROCESSING,
                ProcessingStatus.COMPLETED);
    }

    @Test
    @DisplayName("A book without extracted text fails the pipeline with a clear error")
    void processBook_withoutFullText_failsWithClearError() {
        stubTraceEvents();
        book.setFullText(null);
        stubBookState(ProcessingStatus.UPLOADED);
        when(bookRepository.hasExtractedText(bookId)).thenReturn(false);

        assertThrows(RuntimeException.class, () -> orchestrator.processBook(bookId).join());

        verify(traceService).failStep(any(), any());
        verify(bookRepository).updateStatusAndTrace(eq(bookId), eq(ProcessingStatus.FAILED), anyString());
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
        stubExtractedText();
        stubBookState(ProcessingStatus.UPLOADED);
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book)); // audio step loads the book

        // A chunk awaiting translation: the first run will block inside the
        // TRANSLATE phase waiting for the (never-completing) translation future.
        Chunks awaiting = chunk(ChunkStatus.AWAITING, 0, 0, null);
        Chunks done = chunk(ChunkStatus.COMPLETED, 0, 1, "traducido");
        when(chunksRepository.countByBookId(bookId)).thenReturn(1L); // split check: skip
        stubChunkViews(List.of(awaiting));
        when(chunksRepository.findByBook(book)).thenReturn(List.of(done)); // audio join after unblock

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
        // FINALIZE completes the book via the guarded bulk status update
        verify(bookRepository).updateStatusWhen(bookId, ProcessingStatus.PROCESSING,
                ProcessingStatus.COMPLETED);
    }
}
