package com.glez.frontendservice.services;


import com.glez.frontendservice.components.SmartTextSplitter;
import com.glez.frontendservice.model.*;
import com.glez.frontendservice.repository.BlockRepository;
import com.glez.frontendservice.repository.BookRepository;
import com.glez.frontendservice.repository.ChunksRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BookProcessingService {

    private final BookRepository bookRepository;
    private final TextExtractorService textExtractorService;
    private final NvidiaAiService nvidiaAiService;
    private final AudioGeneratorService audioGeneratorService;
    private final ChunksRepository chunksRepository;
    private final BlockRepository blockRepository;
    private final SmartTextSplitter smartTextSplitter;
    private final ApplicationContext applicationContext;

    // Map to keep track of running tasks for each book to allow cancellation
    private final Map<UUID, List<CompletableFuture<?>>> activeTasks = new ConcurrentHashMap<>();

    public Book storeBook(InputStream fileInputStream, String originalFilename) {
        Book book = new Book();
        book.setName(originalFilename);
        book.setStatus(ProcessingStatus.UPLOADED);

        try {
            String extractedText = textExtractorService.extractAndCleanText(fileInputStream, originalFilename);
            book.setFullText(extractedText);
            book = bookRepository.save(book);
        } catch (IOException e) {
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

        if (finalBook.getStatus() == ProcessingStatus.STOPPED) {
            return;
        }

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
            boolean anyFailed = finalChunks.stream().anyMatch(c -> c.getStatus() == ChunkStatus.FAILED);
            if (anyFailed) {
                finalBook.setStatus(ProcessingStatus.FAILED);
            }
        }
        bookRepository.save(finalBook);
    }

    @Transactional
    public void deleteBook(UUID bookId) {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("Book with ID " + bookId + " not found."));

        List<CompletableFuture<?>> futures = activeTasks.remove(bookId);
        if (futures != null) {
            for (CompletableFuture<?> future : futures) {
                future.cancel(true);
            }
        }

        chunksRepository.deleteAllByBook(book);
        blockRepository.deleteAllByBook(book);
        bookRepository.delete(book);
    }

    public String getFullText(UUID bookId) {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("Book with ID " + bookId + " not found."));
        return book.getFullText();
    }

    @Transactional
    public int saveBlocks(UUID bookId, String rawText, List<BlockSaveRequest.BlockItem> blocks) {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("Book with ID " + bookId + " not found."));

        if (rawText != null) {
            book.setFullText(rawText);
        }

        blockRepository.deleteAllByBook(book);

        int position = 0;
        for (BlockSaveRequest.BlockItem item : blocks) {
            Block block = new Block();
            block.setBook(book);
            block.setBlockType(Block.BlockType.valueOf(item.type().toUpperCase(Locale.ROOT)));
            block.setBlockNumber(item.id());
            block.setContent(item.content());
            block.setPosition(position++);
            blockRepository.save(block);
        }

        bookRepository.save(book);
        return position;
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

        book.setStatus(ProcessingStatus.UPLOADED);
        bookRepository.save(book);
    }

    @Async("bookProcessingExecutor")
    @Transactional
    public CompletableFuture<Void> translateChunkAsync(UUID chunkId) {
        Chunks chunk = chunksRepository.findById(chunkId)
                .orElseThrow(() -> new IllegalArgumentException("Chunk with ID " + chunkId + " not found."));

        if (chunk.getOriginalText() == null || chunk.getOriginalText().isEmpty()) {
            throw new IllegalArgumentException("Chunk with ID " + chunkId + " has no original text to translate.");
        }

        chunk.setStatus(ChunkStatus.PROCESSING);
        chunksRepository.save(chunk);

        try {
            String translated = nvidiaAiService.translateChunk(chunk.getOriginalText(), "en", "es");
            chunk.setTranslatedText(translated);
            chunk.setStatus(ChunkStatus.COMPLETED);
        } catch (Exception e) {
            chunk.setStatus(ChunkStatus.FAILED);
            System.err.println("Error translating chunk " + chunkId + ": " + e.getMessage());
            throw new RuntimeException("Translation failed for chunk " + chunkId, e);
        } finally {
            chunksRepository.save(chunk);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Async("bookProcessingExecutor")
    @Transactional
    public CompletableFuture<Void> processBookChunksAsync(UUID bookId) {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("Book with ID " + bookId + " not found."));

        List<Chunks> chunks = chunksRepository.findByBook(book);
        List<CompletableFuture<?>> futures = chunks.stream()
                .filter(c -> c.getStatus() == ChunkStatus.AWAITING)
                .map(c -> translateChunkAsync(c.getId()))
                .collect(Collectors.toList());

        activeTasks.put(bookId, futures);
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
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
