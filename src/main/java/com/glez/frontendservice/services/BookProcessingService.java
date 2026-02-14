package com.glez.frontendservice.services;

import gon.cue.components.SmartTextSplitter;
import gon.cue.model.*;
import gon.cue.repository.BookRepository;
import gon.cue.repository.ChunksRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor // Add this annotation
public class BookProcessingService {

    private final BookRepository bookRepository;
    private final TextExtractorService textExtractorService;
    private final TranslationAgent translationAgent;
    private final AudioGeneratorService audioGeneratorService;
    private final ChunksRepository chunksRepository;
    private final SmartTextSplitter smartTextSplitter;
    private final ApplicationContext applicationContext;

    private final ExecutorService executorService = Executors.newFixedThreadPool(4); // Initialize here

    // Map to keep track of running tasks for each book to allow cancellation
    private final Map<UUID, List<CompletableFuture<?>>> activeTasks = new ConcurrentHashMap<>();

    // Remove @Autowired and the constructor, Lombok's @RequiredArgsConstructor handles constructor injection
    // public BookProcessingService(BookRepository bookRepository, TextExtractorService textExtractorService, TranslationAgent translationAgent, AudioGeneratorService audioGeneratorService, ChunksRepository chunksRepository, SmartTextSplitter smartTextSplitter, ApplicationContext applicationContext) {
    //     this.bookRepository = bookRepository;
    //     this.textExtractorService = textExtractorService;
    //     this.translationAgent = translationAgent;
    //     this.audioGeneratorService = audioGeneratorService;
    //     this.chunksRepository = chunksRepository;
    //     this.smartTextSplitter = smartTextSplitter;
    //     this.applicationContext = applicationContext;

    //     this.executorService = Executors.newFixedThreadPool(4);
    // }

    public Book storeBook(InputStream fileInputStream, String originalFilename) {
        Book book = new Book();
        book.setName(originalFilename);
        book.setStatus(ProcessingStatus.UPLOADED);
        
        try {
            String extractedText = textExtractorService.extractAndCleanText(fileInputStream, originalFilename);
            book.setFullText(extractedText); // Store the full extracted text
            book = bookRepository.save(book); // Save the book with full text
        } catch (IOException e) {
            // If text extraction fails, delete the book record if it was saved without chunks
            if (book.getId() != null) {
                bookRepository.delete(book);
            }
            throw new RuntimeException("Failed to extract text from file: " + e.getMessage(), e);
        } catch (Exception e) {
            if (book.getId() != null) {
                bookRepository.delete(book);
            }
            throw new RuntimeException("Error during book storage or chunking: " + e.getMessage(), e);
        }

        return book;
    }

    @Transactional
    public void finalizeProcessing(UUID bookId) {
        Book finalBook = bookRepository.findById(bookId).orElseThrow(() -> new RuntimeException("Book disappeared during processing"));
        
        // If the book was stopped/paused, do not proceed to completion
        if (finalBook.getStatus() == ProcessingStatus.STOPPED) {
            return;
        }
        
        // Avoid reprocessing if it's already in a final state
        if (finalBook.getStatus() == ProcessingStatus.COMPLETED || finalBook.getStatus() == ProcessingStatus.FAILED) {
            return;
        }

        List<Chunks> finalChunks = chunksRepository.findByBook(finalBook);

        boolean allCompleted = !finalChunks.isEmpty() && finalChunks.stream().allMatch(c -> c.getStatus() == ChunkStatus.COMPLETED);

        if (allCompleted) {
            try {
                String fullTranslatedText = finalChunks.stream()
                        .sorted(Comparator.comparingInt(Chunks::getPosition))
                        .map(Chunks::getTranslatedText)
                        .collect(Collectors.joining("\n\n"));

                audioGeneratorService.processTextToAudio(fullTranslatedText, Path.of(finalBook.getName()));

                finalBook.setStatus(ProcessingStatus.COMPLETED);
            } catch (Exception e) {
                System.err.println("Failed during audio generation for book " + bookId);
                e.printStackTrace();
                finalBook.setStatus(ProcessingStatus.FAILED);
            }
        } else {
            // If not all are completed, and we are not stopped, it means some failed.
            // Check if any are still processing (shouldn't be, as futures are done)
            boolean anyFailed = finalChunks.stream().anyMatch(c -> c.getStatus() == ChunkStatus.FAILED);
            if (anyFailed) {
                finalBook.setStatus(ProcessingStatus.FAILED);
            }
            // If some are AWAITING (because of pause), we leave the book status as is (or set to STOPPED if not already)
        }
        bookRepository.save(finalBook);
    }

    @Transactional
    public void deleteBook(UUID bookId) {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("Book with ID " + bookId + " not found."));

        // Cancel any active processing tasks for this book
        List<CompletableFuture<?>> futures = activeTasks.remove(bookId);
        if (futures != null) {
            for (CompletableFuture<?> future : futures) {
                future.cancel(true); // Attempt to interrupt running tasks
            }
        }

        // Delete all associated chunks first
        chunksRepository.deleteAllByBook(book);

        // Then delete the book
        bookRepository.delete(book);
    }

    public String getFullText(UUID bookId) {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("Book with ID " + bookId + " not found."));
        return book.getFullText();
    }

    @Transactional
    public void updateFullText(UUID bookId, String newText) {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("Book with ID " + bookId + " not found."));
        book.setFullText(newText);
        bookRepository.save(book);
    }

    @Transactional
    public void splitBookIntoChunks(UUID bookId) {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("Book with ID " + bookId + " not found."));
        
        if (book.getFullText() == null || book.getFullText().isEmpty()) {
            throw new IllegalArgumentException("Book with ID " + bookId + " has no full text to chunk.");
        }

        // Clear existing chunks for this book
        chunksRepository.deleteAllByBook(book);

        List<String> splitText = smartTextSplitter.split(book.getFullText());

        for (int i = 0; i < splitText.size(); i++) {
            String text = splitText.get(i);
            Chunks chunk = new Chunks();
            chunk.setBook(book);
            chunk.setOriginalText(text);
            chunk.setStatus(ChunkStatus.AWAITING);
            chunk.setPosition(i);
            chunksRepository.save(chunk);
        }
        
        // Update book status to UPLOADED or AWAITING_PROCESSING after re-chunking
        book.setStatus(ProcessingStatus.UPLOADED); 
        bookRepository.save(book);
    }

    @Transactional
    public void translateChunk(UUID chunkId) {
        Chunks chunk = chunksRepository.findById(chunkId)
                .orElseThrow(() -> new IllegalArgumentException("Chunk with ID " + chunkId + " not found."));

        // Ensure original text exists before attempting translation
        if (chunk.getOriginalText() == null || chunk.getOriginalText().isEmpty()) {
            throw new IllegalArgumentException("Chunk with ID " + chunkId + " has no original text to translate.");
        }
        
        // Set chunk status to PROCESSING
        chunk.setStatus(ChunkStatus.PROCESSING);
        chunksRepository.save(chunk);

        try {
            // Assuming "en" to "es" translation for now, this can be made configurable later
            String translated = translationAgent.translateChunk(chunk.getOriginalText(), "en", "es");
            chunk.setTranslatedText(translated);
            chunk.setStatus(ChunkStatus.COMPLETED);
        } catch (Exception e) {
            chunk.setStatus(ChunkStatus.FAILED);
            System.err.println("Error translating chunk " + chunkId + ": " + e.getMessage());
            throw new RuntimeException("Translation failed for chunk " + chunkId, e);
        } finally {
            chunksRepository.save(chunk);
        }
    }

    public PaginatedResponse<ChunkDto> getBookChunks(UUID bookId, Pageable pageable) {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("Book with ID " + bookId + " not found."));
        
        Page<Chunks> chunksPage = chunksRepository.findByBook(book, pageable);
        
        Page<ChunkDto> chunkDtoPage = chunksPage.map(chunk -> new ChunkDto(
                chunk.getId(),
                chunk.getOriginalText(),
                chunk.getTranslatedText(),
                chunk.getStatus(),
                chunk.getPosition()
        ));
        
        return new PaginatedResponse<>(chunkDtoPage);
    }
}
