package com.glez.frontendservice.services;


import com.glez.frontendservice.components.SmartTextSplitter;
import com.glez.frontendservice.dtos.BlockDto;
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
import java.util.Objects;
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
    private final com.glez.frontendservice.repository.ProcessTraceEventRepository processTraceEventRepository;
    private final GlossaryService glossaryService;
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
        processTraceEventRepository.deleteAllByBook(book);
        bookRepository.delete(book);
    }

    public String getFullText(UUID bookId) {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("Book with ID " + bookId + " not found."));
        return book.getFullText();
    }

    /**
     * Guarda los bloques semánticos del libro. Las etiquetas blockML son
     * delimitadores puramente editoriales: NO se almacenan ni se traducen.
     * El fullText del libro se reconstruye limpio (solo el contenido de
     * los bloques, sin tags); el texto etiquetado se deriva bajo demanda
     * para el editor con {@link #buildTaggedText(UUID)}.
     */
    @Transactional
    public int saveBlocks(UUID bookId, String rawText, List<BlockSaveRequest.BlockItem> blocks) {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("Book with ID " + bookId + " not found."));

        blockRepository.deleteAllByBook(book);

        int position = 0;
        StringBuilder cleanText = new StringBuilder();
        for (BlockSaveRequest.BlockItem item : blocks) {
            Block block = new Block();
            block.setBook(book);
            block.setBlockType(Block.BlockType.valueOf(item.type().toUpperCase(Locale.ROOT)));
            block.setBlockNumber(item.id());
            block.setContent(item.content());
            block.setPosition(position++);
            blockRepository.save(block);

            if (item.content() != null && !item.content().isBlank()) {
                if (cleanText.length() > 0) {
                    cleanText.append("\n\n");
                }
                cleanText.append(item.content());
            }
        }

        // Texto limpio: los tags jamás se persisten ni llegan al traductor.
        book.setFullText(cleanText.toString());
        bookRepository.save(book);
        return position;
    }

    /**
     * Reconstruye el texto etiquetado (blockML) a partir de los bloques
     * persistidos, para que el editor de Dividir Texto pueda re-editar
     * las marcas. Es una vista derivada: el texto almacenado sigue
     * estando limpio.
     */
    @Transactional(readOnly = true)
    public String buildTaggedText(UUID bookId) {
        if (!bookRepository.existsById(bookId)) {
            throw new IllegalArgumentException("Book with ID " + bookId + " not found.");
        }
        StringBuilder tagged = new StringBuilder();
        for (Block block : blockRepository.findByBookIdOrderByPositionAsc(bookId)) {
            if (tagged.length() > 0) {
                tagged.append("\n\n");
            }
            switch (block.getBlockType()) {
                case PROLOGUE -> tagged.append("<<<PROLOGUE>>>");
                case CHAPTER -> tagged.append("<<<CHAPTER id=\"").append(block.getBlockNumber()).append("\">>>");
                case EPILOGUE -> tagged.append("<<<EPILOGUE>>>");
            }
            tagged.append(block.getContent() != null ? block.getContent() : "");
            switch (block.getBlockType()) {
                case PROLOGUE -> tagged.append("<<</PROLOGUE>>>");
                case CHAPTER -> tagged.append("<<</CHAPTER>>>");
                case EPILOGUE -> tagged.append("<<</EPILOGUE>>>");
            }
        }
        return tagged.toString();
    }

    @Transactional
    public void updateFullText(UUID bookId, String newText) {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("Book with ID " + bookId + " not found."));
        book.setFullText(newText);
        bookRepository.save(book);
    }

    /**
     * Bloques semánticos del libro (Prólogo/Capítulo/Epílogo) en orden de
     * lectura, con un extracto y la longitud para identificarlos en la UI
     * de selección de alcance de la traducción.
     */
    @Transactional(readOnly = true)
    public List<BlockDto> getBlocksForBook(UUID bookId) {
        if (!bookRepository.existsById(bookId)) {
            throw new IllegalArgumentException("Book with ID " + bookId + " not found.");
        }
        return blockRepository.findByBookIdOrderByPositionAsc(bookId).stream()
                .map(block -> new BlockDto(
                        block.getId(),
                        block.getBlockType().name(),
                        block.getBlockNumber(),
                        block.getPosition(),
                        buildPreview(block.getContent()),
                        block.getContent() != null ? block.getContent().length() : 0))
                .toList();
    }

    /**
     * Valida que todos los ids de bloques pertenezcan al libro antes de
     * disparar la traducción (llamada síncrona desde el controller).
     */
    @Transactional(readOnly = true)
    public void validateBlocksBelongToBook(UUID bookId, List<UUID> blockIds) {
        long valid = blockRepository.countByBookIdAndIdIn(bookId, blockIds);
        if (valid != blockIds.size()) {
            throw new IllegalArgumentException(blockIds.size() - valid
                    + " of the selected blocks do not belong to book " + bookId + ".");
        }
    }

    private static String buildPreview(String content) {
        if (content == null) {
            return "";
        }
        String collapsed = content.replaceAll("\\s+", " ").trim();
        return collapsed.length() > 150 ? collapsed.substring(0, 150) + "…" : collapsed;
    }

    /**
     * Divide el libro en chunks para traducir. Si el libro tiene bloques
     * semánticos, los chunks se generan POR BLOQUE (cada bloque se trocea
     * por separado y sus chunks quedan enlazados al bloque), nunca sobre el
     * texto completo; solo se regeneran los chunks de los bloques indicados
     * (null = todos), preservando el trabajo del resto. Sin bloques, se
     * mantiene la ruta legada de texto completo (que ahora está limpio de
     * tags).
     */
    @Transactional
    public void splitBookIntoChunks(UUID bookId, List<UUID> blockIds) {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("Book with ID " + bookId + " not found."));

        List<Block> blocks = targetBlocks(book, blockIds);
        if (!blocks.isEmpty()) {
            for (Block block : blocks) {
                if (block.getContent() == null || block.getContent().isBlank()) {
                    continue; // Bloque vacío: no genera chunks ni audio
                }
                chunksRepository.deleteAllByBlock(block);
                List<String> splitText = smartTextSplitter.split(block.getContent());
                int position = 0;
                for (String text : splitText) {
                    Chunks chunk = new Chunks();
                    chunk.setBook(book);
                    chunk.setBlock(block);
                    chunk.setOriginalText(text);
                    chunk.setStatus(ChunkStatus.AWAITING);
                    chunk.setPosition(position++);
                    chunksRepository.save(chunk);
                }
            }
            book.setStatus(ProcessingStatus.UPLOADED);
            bookRepository.save(book);
            return;
        }

        // Ruta legada: libro sin bloques → texto completo (limpio de tags).
        splitWholeText(book);
    }

    /**
     * Divide el texto completo del libro en chunks (libros sin bloques
     * semánticos).
     */
    @Transactional
    public void splitBookIntoChunks(UUID bookId) {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("Book with ID " + bookId + " not found."));
        splitWholeText(book);
    }

    private void splitWholeText(Book book) {
        if (book.getFullText() == null || book.getFullText().isEmpty()) {
            throw new IllegalArgumentException("Book with ID " + book.getId() + " has no full text to chunk.");
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

    /**
     * Bloques objetivo de una división: los ids indicados (validando que
     * pertenezcan al libro) o todos los del libro cuando no se indica
     * selección.
     */
    private List<Block> targetBlocks(Book book, List<UUID> blockIds) {
        List<Block> allBlocks = blockRepository.findByBookIdOrderByPositionAsc(book.getId());
        if (allBlocks.isEmpty()) {
            return List.of();
        }
        if (blockIds == null || blockIds.isEmpty()) {
            return allBlocks;
        }
        List<Block> selected = allBlocks.stream()
                .filter(block -> blockIds.contains(block.getId()))
                .toList();
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("None of the selected blocks belong to book " + book.getId() + ".");
        }
        return selected;
    }

    @Async("bookProcessingExecutor")
    @Transactional
    public CompletableFuture<Void> translateChunkAsync(UUID chunkId,
                                                       List<com.glez.frontendservice.model.TranslationTerm> glossary) {
        Chunks chunk = chunksRepository.findById(chunkId)
                .orElseThrow(() -> new IllegalArgumentException("Chunk with ID " + chunkId + " not found."));

        if (chunk.getOriginalText() == null || chunk.getOriginalText().isEmpty()) {
            throw new IllegalArgumentException("Chunk with ID " + chunkId + " has no original text to translate.");
        }

        chunk.setStatus(ChunkStatus.PROCESSING);
        chunksRepository.save(chunk);

        try {
            String translated = nvidiaAiService.translateChunk(chunk.getOriginalText(), "en", "es", glossary);
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

        // El glosario (globales + serie + libro) se carga/extrae UNA vez
        // por corrida y se inyecta en el prompt de cada chunk para que la
        // traducción sea consistente en todo el libro y la serie.
        List<com.glez.frontendservice.model.TranslationTerm> glossary =
                glossaryService.getOrCreateGlossary(book);

        List<Chunks> chunks = chunksRepository.findByBook(book);
        List<CompletableFuture<?>> futures = chunks.stream()
                .filter(c -> c.getStatus() == ChunkStatus.AWAITING)
                .map(c -> translateChunkAsync(c.getId(), glossary))
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
