package com.glez.frontendservice.services;


import com.glez.frontendservice.components.SmartTextSplitter;
import com.glez.frontendservice.dtos.BlockDto;
import com.glez.frontendservice.model.*;
import com.glez.frontendservice.repository.BlockRepository;
import com.glez.frontendservice.repository.BookRepository;
import com.glez.frontendservice.repository.ChunksRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
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

    private static final Logger log = LoggerFactory.getLogger(BookProcessingService.class);

    private final BookRepository bookRepository;
    private final TextExtractorService textExtractorService;
    private final NvidiaAiService nvidiaAiService;
    private final AudioGeneratorService audioGeneratorService;
    private final ChunksRepository chunksRepository;
    private final BlockRepository blockRepository;
    private final com.glez.frontendservice.repository.ProcessTraceEventRepository processTraceEventRepository;
    private final com.glez.frontendservice.repository.TranslationTermRepository translationTermRepository;
    private final GlossaryService glossaryService;
    private final ChunkTranslationService chunkTranslationService;
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

    /**
     * Cancela las tareas de traducción en vuelo del libro (petición de
     * stop del panel): interrumpe los virtual threads de los chunks que
     * aún no terminan y devuelve cuántas se cancelaron de verdad. El
     * borrado de libros reusa este mismo mecanismo.
     */
    public int cancelActiveTasks(UUID bookId) {
        List<CompletableFuture<?>> futures = activeTasks.remove(bookId);
        if (futures == null) {
            return 0;
        }
        int cancelled = 0;
        for (CompletableFuture<?> future : futures) {
            if (future.cancel(true)) {
                cancelled++;
            }
        }
        return cancelled;
    }

    /**
     * Elimina un libro y TODO su estado derivado (chunks, bloques, trazas del
     * pipeline y términos del glosario) con borrados en bloque — ninguna
     * fila se materializa antes de borrar — y cancela las tareas asíncronas
     * activas del libro. Es destructivo e irreversible: el hito queda en el
     * log para auditoría posterior.
     */
    @Transactional
    public void deleteBook(UUID bookId) {
        if (!bookRepository.existsById(bookId)) {
            throw new IllegalArgumentException("Book with ID " + bookId + " not found.");
        }

        cancelActiveTasks(bookId);

        // Bulk deletes in JPQL: none of them loads the chunk/block/trace/
        // term rows (nor the book CLOB) before deleting, in contrast with
        // the derived deleteAllBy* methods.
        int chunks = chunksRepository.deleteByBookIdInBulk(bookId);
        int blocks = blockRepository.deleteByBookIdInBulk(bookId);
        int traces = processTraceEventRepository.deleteByBookIdInBulk(bookId);
        int terms = translationTermRepository.deleteByBookIdInBulk(bookId);
        bookRepository.deleteByIdInBulk(bookId);
        log.info("Libro {} eliminado con su estado derivado: {} chunks, {} bloques, {} trazas, {} términos de glosario",
                bookId, chunks, blocks, traces, terms);
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

        blockRepository.deleteByBookIdInBulk(bookId);

        int position = 0;
        StringBuilder cleanText = new StringBuilder();
        List<Block> toSave = new ArrayList<>(blocks.size());
        for (BlockSaveRequest.BlockItem item : blocks) {
            Block block = new Block();
            block.setBook(book);
            block.setBlockType(Block.BlockType.valueOf(item.type().toUpperCase(Locale.ROOT)));
            block.setBlockNumber(item.id());
            block.setContent(item.content());
            block.setPosition(position++);
            toSave.add(block);

            if (item.content() != null && !item.content().isBlank()) {
                if (cleanText.length() > 0) {
                    cleanText.append("\n\n");
                }
                cleanText.append(item.content());
            }
        }
        // One saveAll: a single transaction + JDBC-batched INSERTs instead
        // of one round-trip per block (a novel can carry hundreds).
        blockRepository.saveAll(toSave);

        // Texto limpio: los tags jamás se persisten ni llegan al traductor.
        book.setFullText(cleanText.toString());
        bookRepository.save(book);
        log.info("Bloques guardados del libro {}: {} bloques, texto limpio reconstruido de {} caracteres",
                bookId, position, cleanText.length());
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
            List<Chunks> toSave = new ArrayList<>();
            for (Block block : blocks) {
                if (block.getContent() == null || block.getContent().isBlank()) {
                    continue; // Bloque vacío: no genera chunks ni audio
                }
                chunksRepository.deleteByBlockIdInBulk(block.getId());
                List<String> splitText = smartTextSplitter.split(block.getContent());
                int position = 0;
                for (String text : splitText) {
                    Chunks chunk = new Chunks();
                    chunk.setBook(book);
                    chunk.setBlock(block);
                    chunk.setOriginalText(text);
                    chunk.setStatus(ChunkStatus.AWAITING);
                    chunk.setPosition(position++);
                    toSave.add(chunk);
                }
            }
            // Chunks de todos los bloques en un saveAll: transacción única
            // + INSERTs con JDBC batching (antes: uno por chunk).
            chunksRepository.saveAll(toSave);
            log.info("Chunks regenerados por bloque (libro {}): {} chunks en {} bloque(s) seleccionado(s)",
                    bookId, toSave.size(), blocks.size());
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

        chunksRepository.deleteByBookIdInBulk(book.getId());

        List<String> splitText = smartTextSplitter.split(book.getFullText());

        List<Chunks> toSave = new ArrayList<>(splitText.size());
        for (int i = 0; i < splitText.size(); i++) {
            String text = splitText.get(i);
            Chunks chunk = new Chunks();
            chunk.setBook(book);
            chunk.setOriginalText(text);
            chunk.setStatus(ChunkStatus.AWAITING);
            chunk.setPosition(i);
            toSave.add(chunk);
        }
        chunksRepository.saveAll(toSave);
        log.info("Chunks regenerados del texto completo (libro {}): {} chunks", book.getId(), toSave.size());

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

    // SIN @Transactional: la extracción del glosario (llamada LLM + espera
    // del rate limiter) y el despacho no deben pinear una conexión del
    // pool; cada repositorio usa su propia transacción corta.
    @Async("bookProcessingExecutor")
    public CompletableFuture<Void> processBookChunksAsync(UUID bookId) {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("Book with ID " + bookId + " not found."));

        // El glosario (globales + serie + libro) se carga/extrae UNA vez
        // por corrida y se inyecta en el prompt de cada chunk para que la
        // traducción sea consistente en todo el libro y la serie.
        List<com.glez.frontendservice.model.TranslationTerm> glossary =
                glossaryService.getOrCreateGlossary(book);

        // Solo los IDs de los chunks AWAITING: no se materializa ningún
        // original_text/translated_text CLOB para el despacho (cada chunk
        // se recarga por id dentro de su propia transacción de traducción).
        List<UUID> pendingChunkIds = chunksRepository.findIdsByBookIdAndStatus(
                bookId, com.glez.frontendservice.model.ChunkStatus.AWAITING);
        log.info("Traducción despachada (libro {}): {} chunks pendientes", bookId, pendingChunkIds.size());
        // Despacho a través del PROXY (bean separado, nunca
        // auto-invocación): cada chunk traduce en su propio virtual
        // thread, en paralelo, con la cuota RPM gobernando el ritmo.
        List<CompletableFuture<?>> futures = pendingChunkIds.stream()
                .map(chunkId -> chunkTranslationService.translateChunk(chunkId, glossary))
                .collect(Collectors.toList());

        activeTasks.put(bookId, futures);
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]));
    }

    /**
     * Vista paralela de los chunks del libro: texto original y traducción con
     * el badge del bloque propietario, en orden de lectura determinista
     * (posición del bloque y, dentro de él, posición del chunk). Con
     * blockIds se limita a los chunks de esos bloques (vista del alcance
     * de traducción marcado); null/vacío = todos los chunks del libro.
     */
    public PaginatedResponse<ChunkDto> getBookChunks(UUID bookId, List<UUID> blockIds, Pageable pageable) {
        if (!bookRepository.existsById(bookId)) {
            throw new IllegalArgumentException("Book with ID " + bookId + " not found.");
        }
        Page<ChunksRepository.ChunkView> chunkViews =
                (blockIds == null || blockIds.isEmpty())
                        ? chunksRepository.findChunkViewsByBookId(bookId, pageable)
                        : chunksRepository.findChunkViewsByBookIdAndBlockIdIn(bookId, blockIds, pageable);

        Page<ChunkDto> chunkDtoPage = chunkViews.map(view -> new ChunkDto(
                view.getId(),
                view.getOriginalText(),
                view.getTranslatedText(),
                view.getStatus(),
                view.getPosition(),
                view.getErrorMessage(),
                view.getBlockId(),
                view.getBlockType() != null ? view.getBlockType().name() : null,
                view.getBlockNumber()
        ));

        return new PaginatedResponse<>(chunkDtoPage);
    }

    /** Compatibilidad: vista completa del libro (sin filtro de bloques). */
    public PaginatedResponse<ChunkDto> getBookChunks(UUID bookId, Pageable pageable) {
        return getBookChunks(bookId, null, pageable);
    }

    /**
     * Traduce UN chunk suelto sin afectar al resto del libro: valida que el
     * libro exista, que el chunk le pertenezca y que tenga texto, reutiliza
     * el glosario de la serie/libro y lo despacha a través del bean de
     * traducción (virtual thread propio, cuota RPM compartida, transacciones
     * cortas). El controller rechaza con 409 si hay una corrida del pipeline
     * en marcha sobre el libro. Un chunk COMPLETED puede re-traducirse: solo
     * cambia su fila.
     */
    public void translateSingleChunk(UUID bookId, UUID chunkId) {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("Book with ID " + bookId + " not found."));
        Chunks chunk = chunksRepository.findById(chunkId)
                .orElseThrow(() -> new IllegalArgumentException("Chunk with ID " + chunkId + " not found."));
        if (!chunksRepository.existsByIdAndBookId(chunkId, bookId)) {
            throw new IllegalArgumentException("Chunk " + chunkId + " does not belong to book " + bookId + ".");
        }
        if (chunk.getOriginalText() == null || chunk.getOriginalText().isBlank()) {
            throw new IllegalArgumentException("Chunk " + chunkId + " has no original text to translate.");
        }
        if (chunk.getStatus() == ChunkStatus.PROCESSING) {
            // Con la cura de arranque, un PROCESSING en caliente es SIEMPRE
            // una traducción realmente en curso: re-despacharlo pondría dos
            // virtual threads escribiendo la misma fila.
            throw new IllegalArgumentException("Chunk " + chunkId + " is already being translated.");
        }
        // Mismo contexto de traza que el pipeline (bookId/traceId/step)
        // para que la solicitud sea visible y filtrable en el visor de
        // logs en vivo; el worker asíncrono rellena el suyo por chunk.
        MDC.put("bookId", bookId.toString());
        MDC.put("traceId", chunkId.toString());
        MDC.put("step", "TRANSLATE");
        try {
            log.info("Traducción individual solicitada: chunk {} del libro {} ({} caracteres, estado previo: {})",
                    chunkId, bookId, chunk.getOriginalText().length(), chunk.getStatus());
            List<TranslationTerm> glossary = glossaryService.getOrCreateGlossary(book);
            chunkTranslationService.translateChunk(chunkId, glossary);
        } finally {
            MDC.clear();
        }
    }
}
