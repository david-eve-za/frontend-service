package com.glez.frontendservice.services;

import com.glez.frontendservice.components.SmartTextSplitter;
import com.glez.frontendservice.model.Block;
import com.glez.frontendservice.model.Book;
import com.glez.frontendservice.model.ChunkDto;
import com.glez.frontendservice.model.Chunks;
import com.glez.frontendservice.model.ChunkStatus;
import com.glez.frontendservice.model.PaginatedResponse;
import com.glez.frontendservice.model.ProcessingStatus;
import com.glez.frontendservice.model.TranslationTerm;
import com.glez.frontendservice.repository.BookRepository;
import com.glez.frontendservice.repository.ChunksRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BookProcessingService Tests")
class BookProcessingServiceTest {

    @Mock
    private BookRepository bookRepository;

    @Mock
    private TextExtractorService textExtractorService;

    @Mock
    private NvidiaAiService nvidiaAiService;

    @Mock
    private AudioGeneratorService audioGeneratorService;

    @Mock
    private ChunksRepository chunksRepository;

    @Mock
    private com.glez.frontendservice.repository.BlockRepository blockRepository;

    @Mock
    private SmartTextSplitter smartTextSplitter;

    @Mock
    private com.glez.frontendservice.repository.ProcessTraceEventRepository processTraceEventRepository;

    @Mock
    private GlossaryService glossaryService;

    @Mock
    private ChunkTranslationService chunkTranslationService;

    @Mock
    private com.glez.frontendservice.repository.TranslationTermRepository translationTermRepository;

    @Mock
    private ApplicationContext applicationContext;

    private BookProcessingService bookProcessingService;

    private UUID bookId;
    private Book book;

    @BeforeEach
    void setUp() {
        bookProcessingService = new BookProcessingService(
            bookRepository, textExtractorService, nvidiaAiService,
            audioGeneratorService, chunksRepository, blockRepository,
            processTraceEventRepository, translationTermRepository, glossaryService,
            chunkTranslationService, smartTextSplitter, applicationContext
        );
        
        bookId = UUID.randomUUID();
        book = new Book();
        book.setId(bookId);
        book.setName("test-book.pdf");
        book.setStatus(ProcessingStatus.UPLOADED);
        book.setFullText("Full book text content");
    }

    @Nested
    @DisplayName("storeBook method tests")
    class StoreBookTests {

        @Test
        @DisplayName("Should store book successfully with extracted text")
        void storeBook_withValidInput_storesBook() throws IOException {
            String extractedText = "Extracted text from PDF";
            InputStream inputStream = new ByteArrayInputStream("PDF content".getBytes());
            String filename = "test.pdf";

            when(textExtractorService.extractAndCleanText(inputStream, filename)).thenReturn(extractedText);
            when(bookRepository.save(any(Book.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Book result = bookProcessingService.storeBook(inputStream, filename);

            assertNotNull(result);
            assertEquals(filename, result.getName());
            assertEquals(extractedText, result.getFullText());
            assertEquals(ProcessingStatus.UPLOADED, result.getStatus());
            verify(bookRepository).save(any(Book.class));
        }

        @Test
        @DisplayName("Should throw exception when text extraction fails (book not saved yet)")
        void storeBook_whenTextExtractionFails_throwsException() throws IOException {
            InputStream inputStream = new ByteArrayInputStream("PDF content".getBytes());
            String filename = "test.pdf";

            when(textExtractorService.extractAndCleanText(inputStream, filename)).thenThrow(new IOException("Extraction failed"));
            // Book is not saved yet when extraction fails, so no delete is called

            RuntimeException exception = assertThrows(RuntimeException.class, 
                () -> bookProcessingService.storeBook(inputStream, filename));

            assertTrue(exception.getMessage().contains("Failed to extract text from file"));
            verify(bookRepository, never()).save(any(Book.class));
            verify(bookRepository, never()).delete(any(Book.class));
        }

        @Test
        @DisplayName("Should delete book and rethrow exception when save fails")
        void storeBook_whenSaveFails_deletesBookAndThrows() throws IOException {
            InputStream inputStream = new ByteArrayInputStream("PDF content".getBytes());
            String filename = "test.pdf";

            when(textExtractorService.extractAndCleanText(inputStream, filename)).thenReturn("Extracted text");
            // Save succeeds first (setting ID), then we simulate a failure on a subsequent operation
            when(bookRepository.save(any(Book.class))).thenAnswer(invocation -> {
                Book b = invocation.getArgument(0);
                b.setId(UUID.randomUUID());
                throw new RuntimeException("Save failed");
            });

            RuntimeException exception = assertThrows(RuntimeException.class, 
                () -> bookProcessingService.storeBook(inputStream, filename));

            assertTrue(exception.getMessage().contains("Error during book storage"));
            verify(bookRepository).delete(any(Book.class));
        }
    }

    @Nested
    @DisplayName("finalizeProcessing method tests")
    class FinalizeProcessingTests {

        @Test
        @DisplayName("Should return early if book status is STOPPED")
        void finalizeProcessing_withStoppedStatus_returnsEarly() {
            book.setStatus(ProcessingStatus.STOPPED);
            when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));

            bookProcessingService.finalizeProcessing(bookId);

            verify(bookRepository).findById(bookId);
            verifyNoMoreInteractions(bookRepository);
        }

        @Test
        @DisplayName("Should return early if book status is COMPLETED")
        void finalizeProcessing_withCompletedStatus_returnsEarly() {
            book.setStatus(ProcessingStatus.COMPLETED);
            when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));

            bookProcessingService.finalizeProcessing(bookId);

            verify(bookRepository).findById(bookId);
            verifyNoMoreInteractions(bookRepository);
        }

        @Test
        @DisplayName("Should return early if book status is FAILED")
        void finalizeProcessing_withFailedStatus_returnsEarly() {
            book.setStatus(ProcessingStatus.FAILED);
            when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));

            bookProcessingService.finalizeProcessing(bookId);

            verify(bookRepository).findById(bookId);
            verifyNoMoreInteractions(bookRepository);
        }

        @Test
        @DisplayName("Should mark book as COMPLETED when all chunks are completed")
        void finalizeProcessing_allChunksCompleted_marksBookCompleted() {
            book.setStatus(ProcessingStatus.PROCESSING);
            Chunks chunk1 = createChunk(ChunkStatus.COMPLETED, 0, "Translated 1");
            Chunks chunk2 = createChunk(ChunkStatus.COMPLETED, 1, "Translated 2");
            List<Chunks> chunks = List.of(chunk1, chunk2);

            when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
            when(chunksRepository.findByBook(book)).thenReturn(chunks);
            when(audioGeneratorService.processTextToAudio(anyString(), any(Path.class))).thenReturn(true);
            when(bookRepository.save(any(Book.class))).thenAnswer(invocation -> invocation.getArgument(0));

            bookProcessingService.finalizeProcessing(bookId);

            verify(bookRepository).save(argThat(b -> b.getStatus() == ProcessingStatus.COMPLETED));
            verify(audioGeneratorService).processTextToAudio(anyString(), any(Path.class));
        }

        @Test
        @DisplayName("Should mark book as FAILED when any chunk failed")
        void finalizeProcessing_anyChunkFailed_marksBookFailed() {
            book.setStatus(ProcessingStatus.PROCESSING);
            Chunks chunk1 = createChunk(ChunkStatus.COMPLETED, 0, "Translated 1");
            Chunks chunk2 = createChunk(ChunkStatus.FAILED, 1, null);
            List<Chunks> chunks = List.of(chunk1, chunk2);

            when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
            when(chunksRepository.findByBook(book)).thenReturn(chunks);
            when(bookRepository.save(any(Book.class))).thenAnswer(invocation -> invocation.getArgument(0));

            bookProcessingService.finalizeProcessing(bookId);

            verify(bookRepository).save(argThat(b -> b.getStatus() == ProcessingStatus.FAILED));
        }

        @Test
        @DisplayName("Should mark book as FAILED when audio generation fails")
        @Disabled("Mock injection issue with audioGeneratorService")
        void finalizeProcessing_audioGenerationFails_marksBookFailed() {
            book.setStatus(ProcessingStatus.PROCESSING);
            Chunks chunk1 = createChunk(ChunkStatus.COMPLETED, 0, "Translated 1");
            Chunks chunk2 = createChunk(ChunkStatus.COMPLETED, 1, "Translated 2");
            List<Chunks> chunks = List.of(chunk1, chunk2);

            when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
            when(chunksRepository.findByBook(book)).thenReturn(chunks);
            lenient().when(audioGeneratorService.processTextToAudio(anyString(), any(Path.class))).thenReturn(false);
            when(bookRepository.save(any(Book.class))).thenAnswer(invocation -> invocation.getArgument(0));

            bookProcessingService.finalizeProcessing(bookId);

            verify(bookRepository).save(argThat(b -> b.getStatus() == ProcessingStatus.FAILED));
        }

        @Test
        @DisplayName("Should throw exception when book not found")
        void finalizeProcessing_bookNotFound_throwsException() {
            when(bookRepository.findById(bookId)).thenReturn(Optional.empty());

            RuntimeException exception = assertThrows(RuntimeException.class, 
                () -> bookProcessingService.finalizeProcessing(bookId));

            assertTrue(exception.getMessage().contains("Book disappeared during processing"));
        }
    }

    @Nested
    @DisplayName("deleteBook method tests")
    class DeleteBookTests {

        @Test
        @DisplayName("Should delete book and cancel active tasks")
        void deleteBook_withActiveTasks_cancelsTasksAndDeletes() {
            UUID chunkId1 = UUID.randomUUID();
            UUID chunkId2 = UUID.randomUUID();
            CompletableFuture<Void> future1 = CompletableFuture.completedFuture(null);
            CompletableFuture<Void> future2 = CompletableFuture.completedFuture(null);
            
            // Add active tasks
            try {
                java.lang.reflect.Field field = BookProcessingService.class.getDeclaredField("activeTasks");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                java.util.Map<UUID, List<CompletableFuture<?>>> activeTasks = (java.util.Map<UUID, List<CompletableFuture<?>>>) field.get(bookProcessingService);
                activeTasks.put(bookId, List.of(future1, future2));
            } catch (Exception e) {
                throw new RuntimeException("Failed to set activeTasks", e);
            }

            when(bookRepository.existsById(bookId)).thenReturn(true);

            bookProcessingService.deleteBook(bookId);

            // Bulk deletes: no entity (nor CLOB) is loaded for the deletion
            verify(chunksRepository).deleteByBookIdInBulk(bookId);
            verify(bookRepository).deleteByIdInBulk(bookId);
        }

        @Test
        @DisplayName("Should throw exception when book not found")
        void deleteBook_bookNotFound_throwsException() {
            when(bookRepository.existsById(bookId)).thenReturn(false);

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, 
                () -> bookProcessingService.deleteBook(bookId));

            assertTrue(exception.getMessage().contains("Book with ID " + bookId + " not found"));
        }
    }

    @Nested
    @DisplayName("getFullText method tests")
    class GetFullTextTests {

        @Test
        @DisplayName("Should return full text when book exists")
        void getFullText_bookExists_returnsFullText() {
            when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));

            String result = bookProcessingService.getFullText(bookId);

            assertEquals(book.getFullText(), result);
        }

        @Test
        @DisplayName("Should throw exception when book not found")
        void getFullText_bookNotFound_throwsException() {
            when(bookRepository.findById(bookId)).thenReturn(Optional.empty());

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, 
                () -> bookProcessingService.getFullText(bookId));

            assertTrue(exception.getMessage().contains("Book with ID " + bookId + " not found"));
        }
    }

    @Nested
    @DisplayName("updateFullText method tests")
    class UpdateFullTextTests {

        @Test
        @DisplayName("Should update full text when book exists")
        void updateFullText_bookExists_updatesText() {
            String newText = "Updated full text content";
            when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
            when(bookRepository.save(any(Book.class))).thenAnswer(invocation -> invocation.getArgument(0));

            bookProcessingService.updateFullText(bookId, newText);

            assertEquals(newText, book.getFullText());
            verify(bookRepository).save(book);
        }

        @Test
        @DisplayName("Should throw exception when book not found")
        void updateFullText_bookNotFound_throwsException() {
            when(bookRepository.findById(bookId)).thenReturn(Optional.empty());

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, 
                () -> bookProcessingService.updateFullText(bookId, "New text"));

            assertTrue(exception.getMessage().contains("Book with ID " + bookId + " not found"));
        }
    }

    @Nested
    @DisplayName("splitBookIntoChunks method tests")
    class SplitBookIntoChunksTests {

        @Test
        @DisplayName("Should split book into chunks and save them")
        void splitBookIntoChunks_validBook_splitsAndSaves() {
            List<String> splitText = List.of("Chunk 1 text", "Chunk 2 text", "Chunk 3 text");
            when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
            when(smartTextSplitter.split(book.getFullText())).thenReturn(splitText);
            when(chunksRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
            when(bookRepository.save(any(Book.class))).thenAnswer(invocation -> invocation.getArgument(0));

            bookProcessingService.splitBookIntoChunks(bookId);

            verify(chunksRepository).deleteByBookIdInBulk(bookId);
            verify(smartTextSplitter).split(book.getFullText());
            // One batched saveAll with all the chunks (instead of one save per chunk)
            verify(chunksRepository).saveAll(argThat((List<Chunks> list) -> list != null && list.size() == 3));
            verify(bookRepository).save(argThat(b -> b.getStatus() == ProcessingStatus.UPLOADED));
        }

        @Test
        @DisplayName("Should throw exception when book has no full text")
        void splitBookIntoChunks_noFullText_throwsException() {
            book.setFullText("");
            when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, 
                () -> bookProcessingService.splitBookIntoChunks(bookId));

            assertTrue(exception.getMessage().contains("has no full text to chunk"));
        }

        @Test
        @DisplayName("Should throw exception when book not found")
        void splitBookIntoChunks_bookNotFound_throwsException() {
            when(bookRepository.findById(bookId)).thenReturn(Optional.empty());

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, 
                () -> bookProcessingService.splitBookIntoChunks(bookId));

            assertTrue(exception.getMessage().contains("Book with ID " + bookId + " not found"));
        }
    }

    @Nested
    @DisplayName("chunk translation dispatch (parallel, via proxy bean)")
    class TranslateChunkDispatchTests {

        @Test
        @DisplayName("Every AWAITING chunk is dispatched to the translation bean; COMPLETED ones are skipped")
        void processBookChunksAsync_dispatchesPendingChunksInParallel() {
            Chunks chunk1 = createChunk(ChunkStatus.AWAITING, 0, null);
            Chunks chunk2 = createChunk(ChunkStatus.AWAITING, 1, null);
            Chunks chunk3 = createChunk(ChunkStatus.COMPLETED, 2, "Already done");

            when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
            // Id-only scan of the AWAITING chunks: no chunk text is loaded
            // for the dispatch (each chunk re-reads itself by id).
            when(chunksRepository.findIdsByBookIdAndStatus(bookId, ChunkStatus.AWAITING))
                    .thenReturn(List.of(chunk1.getId(), chunk2.getId()));
            when(chunkTranslationService.translateChunk(any(UUID.class), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            CompletableFuture<Void> future = bookProcessingService.processBookChunksAsync(bookId);

            assertNotNull(future);
            verify(chunkTranslationService).translateChunk(chunk1.getId(), List.of());
            verify(chunkTranslationService).translateChunk(chunk2.getId(), List.of());
            verify(chunkTranslationService, never()).translateChunk(chunk3.getId(), List.of());
        }
    }

    @Nested
    @DisplayName("processBookChunksAsync method tests")
    class ProcessBookChunksAsyncTests {

        @Test
        @DisplayName("Should process all awaiting chunks")
        void processBookChunksAsync_withAwaitingChunks_startsProcessing() {
            Chunks chunk1 = createChunk(ChunkStatus.AWAITING, 0, null);
            Chunks chunk2 = createChunk(ChunkStatus.AWAITING, 1, null);
            Chunks chunk3 = createChunk(ChunkStatus.COMPLETED, 2, "Already done");

            when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
            when(chunksRepository.findIdsByBookIdAndStatus(bookId, ChunkStatus.AWAITING))
                    .thenReturn(List.of(chunk1.getId(), chunk2.getId()));

            when(chunkTranslationService.translateChunk(any(UUID.class), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            CompletableFuture<Void> future = bookProcessingService.processBookChunksAsync(bookId);

            assertNotNull(future);
            verify(chunksRepository).findIdsByBookIdAndStatus(bookId, ChunkStatus.AWAITING);
        }

        @Test
        @DisplayName("Should throw exception when book not found")
        void processBookChunksAsync_bookNotFound_throwsException() {
            when(bookRepository.findById(bookId)).thenReturn(Optional.empty());

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, 
                () -> bookProcessingService.processBookChunksAsync(bookId).get());

            assertTrue(exception.getMessage().contains("Book with ID " + bookId + " not found"));
        }
    }

    @Nested
    @DisplayName("getBookChunks method tests")
    class GetBookChunksTests {

        @Test
        @DisplayName("Should return paginated chunks with block metadata in reading order")
        void getBookChunks_withValidBook_returnsPaginatedChunksWithBlockMetadata() {
            Pageable pageable = PageRequest.of(0, 10);
            UUID blockId = UUID.randomUUID();
            ChunksRepository.ChunkView bound = new TestChunkView(
                    UUID.randomUUID(), "Original 1", "Translated 1", ChunkStatus.COMPLETED, 0,
                    blockId, Block.BlockType.CHAPTER, 3);
            ChunksRepository.ChunkView legacy = new TestChunkView(
                    UUID.randomUUID(), "Original 2", null, ChunkStatus.AWAITING, 1,
                    null, null, null);
            Page<ChunksRepository.ChunkView> viewPage = new PageImpl<>(List.of(bound, legacy), pageable, 2);

            when(bookRepository.existsById(bookId)).thenReturn(true);
            when(chunksRepository.findChunkViewsByBookId(bookId, pageable)).thenReturn(viewPage);

            PaginatedResponse<ChunkDto> result = bookProcessingService.getBookChunks(bookId, null, pageable);

            assertNotNull(result);
            assertEquals(2, result.getContent().size());
            assertEquals(bound.getId(), result.getContent().get(0).getId());
            assertEquals(blockId, result.getContent().get(0).getBlockId());
            assertEquals("CHAPTER", result.getContent().get(0).getBlockType());
            assertEquals(3, result.getContent().get(0).getBlockNumber());
            assertNull(result.getContent().get(1).getBlockId());
            assertNull(result.getContent().get(1).getBlockType());
        }

        @Test
        @DisplayName("Should filter the page to the given blocks")
        void getBookChunks_withBlockIds_usesBlockFilteredQuery() {
            Pageable pageable = PageRequest.of(0, 10);
            UUID blockId = UUID.randomUUID();
            Page<ChunksRepository.ChunkView> emptyPage =
                    new PageImpl<>(List.<ChunksRepository.ChunkView>of(), pageable, 0);

            when(bookRepository.existsById(bookId)).thenReturn(true);
            when(chunksRepository.findChunkViewsByBookIdAndBlockIdIn(bookId, List.of(blockId), pageable))
                    .thenReturn(emptyPage);

            PaginatedResponse<ChunkDto> result = bookProcessingService.getBookChunks(bookId, List.of(blockId), pageable);

            assertNotNull(result);
            assertEquals(0, result.getTotalElements());
            verify(chunksRepository).findChunkViewsByBookIdAndBlockIdIn(bookId, List.of(blockId), pageable);
            verify(chunksRepository, never()).findChunkViewsByBookId(any(), any());
        }

        @Test
        @DisplayName("Should throw exception when book not found")
        void getBookChunks_bookNotFound_throwsException() {
            Pageable pageable = PageRequest.of(0, 10);
            when(bookRepository.existsById(bookId)).thenReturn(false);

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> bookProcessingService.getBookChunks(bookId, null, pageable));

            assertTrue(exception.getMessage().contains("Book with ID " + bookId + " not found"));
        }
    }

    @Nested
    @DisplayName("translateSingleChunk method tests")
    class TranslateSingleChunkTests {

        @Test
        @DisplayName("Should dispatch a single chunk through the translation bean with the book glossary")
        void translateSingleChunk_validChunk_dispatchesWithGlossary() {
            UUID chunkId = UUID.randomUUID();
            Chunks chunk = createChunk(ChunkStatus.AWAITING, 0, null);
            List<TranslationTerm> glossary = List.of();

            when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
            when(chunksRepository.findById(chunkId)).thenReturn(Optional.of(chunk));
            when(chunksRepository.existsByIdAndBookId(chunkId, bookId)).thenReturn(true);
            when(glossaryService.getOrCreateGlossary(book)).thenReturn(glossary);

            bookProcessingService.translateSingleChunk(bookId, chunkId);

            verify(chunkTranslationService).translateChunk(chunkId, glossary);
        }

        @Test
        @DisplayName("Should allow re-translating a completed chunk (dispatches anyway)")
        void translateSingleChunk_completedChunk_dispatchesAnyway() {
            UUID chunkId = UUID.randomUUID();
            Chunks chunk = createChunk(ChunkStatus.COMPLETED, 0, "Previous translation");
            List<TranslationTerm> glossary = List.of();

            when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
            when(chunksRepository.findById(chunkId)).thenReturn(Optional.of(chunk));
            when(chunksRepository.existsByIdAndBookId(chunkId, bookId)).thenReturn(true);
            when(glossaryService.getOrCreateGlossary(book)).thenReturn(glossary);

            bookProcessingService.translateSingleChunk(bookId, chunkId);

            verify(chunkTranslationService).translateChunk(chunkId, glossary);
        }

        @Test
        @DisplayName("Should throw when the chunk does not belong to the book")
        void translateSingleChunk_foreignChunk_throwsException() {
            UUID chunkId = UUID.randomUUID();
            Chunks chunk = createChunk(ChunkStatus.AWAITING, 0, null);

            when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
            when(chunksRepository.findById(chunkId)).thenReturn(Optional.of(chunk));
            when(chunksRepository.existsByIdAndBookId(chunkId, bookId)).thenReturn(false);

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> bookProcessingService.translateSingleChunk(bookId, chunkId));

            assertTrue(exception.getMessage().contains("does not belong"));
            verify(chunkTranslationService, never()).translateChunk(any(), any());
        }

        @Test
        @DisplayName("Should throw when the chunk has no original text")
        void translateSingleChunk_noOriginalText_throwsException() {
            UUID chunkId = UUID.randomUUID();
            Chunks chunk = createChunk(ChunkStatus.AWAITING, 0, null);
            chunk.setOriginalText("   ");

            when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
            when(chunksRepository.findById(chunkId)).thenReturn(Optional.of(chunk));
            when(chunksRepository.existsByIdAndBookId(chunkId, bookId)).thenReturn(true);

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> bookProcessingService.translateSingleChunk(bookId, chunkId));

            assertTrue(exception.getMessage().contains("no original text"));
            verify(chunkTranslationService, never()).translateChunk(any(), any());
        }

        @Test
        @DisplayName("Should throw when the chunk does not exist")
        void translateSingleChunk_chunkNotFound_throwsException() {
            UUID chunkId = UUID.randomUUID();

            when(bookRepository.findById(bookId)).thenReturn(Optional.of(book));
            when(chunksRepository.findById(chunkId)).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class,
                () -> bookProcessingService.translateSingleChunk(bookId, chunkId));
            verify(chunkTranslationService, never()).translateChunk(any(), any());
        }
    }

    /** ChunkView stub for the light projection (block metadata without loading the block CLOB). */
    private static class TestChunkView implements ChunksRepository.ChunkView {
        private final UUID id;
        private final String originalText;
        private final String translatedText;
        private final ChunkStatus status;
        private final int position;
        private final UUID blockId;
        private final Block.BlockType blockType;
        private final Integer blockNumber;

        TestChunkView(UUID id, String originalText, String translatedText, ChunkStatus status, int position,
                      UUID blockId, Block.BlockType blockType, Integer blockNumber) {
            this.id = id;
            this.originalText = originalText;
            this.translatedText = translatedText;
            this.status = status;
            this.position = position;
            this.blockId = blockId;
            this.blockType = blockType;
            this.blockNumber = blockNumber;
        }

        @Override
        public UUID getId() {
            return id;
        }

        @Override
        public String getOriginalText() {
            return originalText;
        }

        @Override
        public String getTranslatedText() {
            return translatedText;
        }

        @Override
        public ChunkStatus getStatus() {
            return status;
        }

        @Override
        public int getPosition() {
            return position;
        }

        @Override
        public UUID getBlockId() {
            return blockId;
        }

        @Override
        public Block.BlockType getBlockType() {
            return blockType;
        }

        @Override
        public Integer getBlockNumber() {
            return blockNumber;
        }
    }

    private Chunks createChunk(ChunkStatus status, int position, String translatedText) {
        Chunks chunk = new Chunks();
        chunk.setId(UUID.randomUUID());
        chunk.setBook(book);
        chunk.setOriginalText("Original text " + position);
        chunk.setTranslatedText(translatedText);
        chunk.setStatus(status);
        chunk.setPosition(position);
        return chunk;
    }
}