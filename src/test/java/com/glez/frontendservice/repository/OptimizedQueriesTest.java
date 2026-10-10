package com.glez.frontendservice.repository;

import com.glez.frontendservice.model.Block;
import com.glez.frontendservice.model.Book;
import com.glez.frontendservice.model.ChunkStatus;
import com.glez.frontendservice.model.Chunks;
import com.glez.frontendservice.model.Novel;
import com.glez.frontendservice.model.NovelVolume;
import com.glez.frontendservice.model.NovelVolumeFile;
import com.glez.frontendservice.model.ProcessStep;
import com.glez.frontendservice.model.ProcessTraceEvent;
import com.glez.frontendservice.model.ProcessingStatus;
import com.glez.frontendservice.model.TraceEventStatus;
import com.glez.frontendservice.model.TranslationTerm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de integración de las consultas introducidas por la optimización
 * de base de datos: proyecciones ligeras, updates en bloque, deletes en
 * bloque e índices compuestos. Todas corren contra H2 real (esquema
 * generado desde las entidades), de modo que un error de JPQL o una
 * anotación @Index mal puesta falla aquí y no en producción.
 */
@DataJpaTest
@AutoConfigureTestDatabase
@DisplayName("Optimized database queries and indexes")
class OptimizedQueriesTest {

    /**
     * El slice @DataJpaTest no incluye la auto-configuración de caché, pero
     * el @EnableCaching de App exige un CacheManager al arrancar el contexto.
     */
    @TestConfiguration
    static class CacheManagerTestConfig {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager();
        }
    }

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private ChunksRepository chunksRepository;

    @Autowired
    private BlockRepository blockRepository;

    @Autowired
    private ProcessTraceEventRepository traceEventRepository;

    @Autowired
    private TranslationTermRepository termRepository;

    @Autowired
    private NovelRepository novelRepository;

    @Autowired
    private NovelVolumeRepository volumeRepository;

    @Autowired
    private NovelVolumeFileRepository fileRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Book book(ProcessingStatus status, String fullText) {
        Book book = new Book();
        book.setName("test-book.pdf");
        book.setStatus(status);
        book.setFullText(fullText);
        return bookRepository.save(book);
    }

    private Chunks chunk(Book book, Block block, ChunkStatus status, int position, int attempts) {
        Chunks chunk = new Chunks();
        chunk.setBook(book);
        chunk.setBlock(block);
        chunk.setOriginalText("original " + position);
        chunk.setStatus(status);
        chunk.setPosition(position);
        chunk.setAttempts(attempts);
        return chunksRepository.save(chunk);
    }

    private ProcessTraceEvent trace(Book book, ProcessStep step, TraceEventStatus status, int attempt) {
        ProcessTraceEvent event = new ProcessTraceEvent();
        event.setBook(book);
        event.setTraceId("t");
        event.setStep(step);
        event.setStatus(status);
        event.setAttempt(attempt);
        event.setStartedAt(Instant.now());
        return traceEventRepository.save(event);
    }

    // ------------------------------------------------------------------
    // Composite indexes (informational schema check)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The query-driven composite indexes exist in the schema")
    void compositeIndexesExist() {
        List<String> indexes = jdbcTemplate.query(
                "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES WHERE TABLE_SCHEMA = 'PUBLIC'",
                (rs, n) -> rs.getString(1));
        for (String expected : List.of(
                "IDX_NOVEL_TITLE",
                "IDX_BLOCKS_BOOK_POSITION",
                "IDX_CHUNKS_BOOK_STATUS",
                "IDX_CHUNKS_BLOCK_POSITION",
                "IDX_TRACE_BOOK_STEP_STARTED")) {
            assertTrue(indexes.contains(expected), "Missing index " + expected);
        }
    }

    // ------------------------------------------------------------------
    // Book projections and bulk updates
    // ------------------------------------------------------------------

    @Test
    @DisplayName("findStatusViews/findBookListViews never load the full_text CLOB")
    void bookProjectionsReturnLightRows() {
        Book completed = book(ProcessingStatus.COMPLETED, "texto completo");
        Book processing = book(ProcessingStatus.PROCESSING, "otro texto");

        List<BookRepository.BookStatusView> states = bookRepository.findStatusViews(
                List.of(completed.getId(), processing.getId()));
        assertEquals(2, states.size());
        // No implicit order: assert per id, not per position.
        BookRepository.BookStatusView completedState = states.stream()
                .filter(s -> s.getId().equals(completed.getId())).findFirst().orElseThrow();
        assertEquals(ProcessingStatus.COMPLETED, completedState.getStatus());
        BookRepository.BookStatusView processingState = states.stream()
                .filter(s -> s.getId().equals(processing.getId())).findFirst().orElseThrow();
        assertEquals(ProcessingStatus.PROCESSING, processingState.getStatus());

        List<BookRepository.BookListView> list = bookRepository.findBookListViews();
        assertEquals(2, list.size());
        assertEquals("test-book.pdf", list.get(0).getName());

        List<UUID> recoverable = bookRepository.findIdsByStatusIn(List.of(ProcessingStatus.PROCESSING));
        assertEquals(List.of(processing.getId()), recoverable);
    }

    @Test
    @DisplayName("hasExtractedText detects null/empty/space-only text without reading the CLOB")
    void hasExtractedTextChecksBlanknessInSql() {
        Book withText = book(ProcessingStatus.UPLOADED, "texto real");
        Book blank = book(ProcessingStatus.UPLOADED, "     ");
        Book nullText = book(ProcessingStatus.UPLOADED, null);
        bookRepository.saveAndFlush(blank);

        assertTrue(bookRepository.hasExtractedText(withText.getId()));
        assertFalse(bookRepository.hasExtractedText(blank.getId()),
                "Space-only text trims to empty in SQL (LENGTH(TRIM(x)) = 0)");
        assertFalse(bookRepository.hasExtractedText(nullText.getId()));
    }

    @Test
    @DisplayName("updateCheckpoint/updateStatusWhen/updateStatusAndTrace move state without loading entities")
    void bulkBookUpdatesAreGuarded() {
        Book book = book(ProcessingStatus.PROCESSING, "x");
        UUID id = book.getId();

        assertEquals(1, bookRepository.updateCheckpoint(id, ProcessStep.TRANSLATE, "trace-1"));
        assertEquals(1, bookRepository.updateStatusWhen(id, ProcessingStatus.PROCESSING, ProcessingStatus.COMPLETED));
        // Guarded transition does not fire on unexpected status
        assertEquals(0, bookRepository.updateStatusWhen(id, ProcessingStatus.PROCESSING, ProcessingStatus.FAILED));
        assertEquals(1, bookRepository.updateStatusAndTrace(id, ProcessingStatus.FAILED, "trace-2"));

        Book reloaded = bookRepository.findById(id).orElseThrow();
        assertEquals(ProcessingStatus.FAILED, reloaded.getStatus());
        assertEquals("trace-2", reloaded.getLastTraceId());
        assertEquals(ProcessStep.TRANSLATE, reloaded.getCurrentStep());
    }

    // ------------------------------------------------------------------
    // Chunk projections and bulk operations
    // ------------------------------------------------------------------

    @Test
    @DisplayName("countByBookId, status views and id scans read only light columns")
    void chunkLightQueries() {
        Book book = book(ProcessingStatus.PROCESSING, "x");
        Chunks a = chunk(book, null, ChunkStatus.AWAITING, 0, 0);
        Chunks b = chunk(book, null, ChunkStatus.FAILED, 1, 2);
        Chunks c = chunk(book, null, ChunkStatus.COMPLETED, 2, 1);

        assertEquals(3, chunksRepository.countByBookId(book.getId()));

        List<ChunksRepository.ChunkStatusView> views = chunksRepository.findStatusViewsByBookId(book.getId());
        assertEquals(3, views.size());

        assertEquals(List.of(a.getId()),
                chunksRepository.findIdsByBookIdAndStatus(book.getId(), ChunkStatus.AWAITING));
        assertEquals(List.of(a.getId(), b.getId()),
                chunksRepository.findIdsByBookIdAndStatusNot(book.getId(), ChunkStatus.COMPLETED));
    }

    @Test
    @DisplayName("Per-block audio chunks arrive in one query ordered by position")
    void blockChunksByBookId() {
        Book book = book(ProcessingStatus.AWAITING, "x");
        Block block = new Block();
        block.setBook(book);
        block.setBlockType(Block.BlockType.CHAPTER);
        block.setBlockNumber(1);
        block.setPosition(0);
        block.setContent("contenido");
        blockRepository.saveAndFlush(block);
        chunk(book, block, ChunkStatus.COMPLETED, 1, 0);
        chunk(book, block, ChunkStatus.COMPLETED, 0, 0);
        chunk(book, null, ChunkStatus.COMPLETED, 5, 0); // legacy chunk: excluded

        List<Chunks> blockChunks = chunksRepository.findBlockChunksByBookId(book.getId());
        assertEquals(2, blockChunks.size(), "Only block-bound chunks are returned");
        assertTrue(blockChunks.stream().allMatch(c -> c.getBlock() != null));
        assertEquals(0, blockChunks.get(0).getPosition(), "Reading order by position");
    }

    @Test
    @DisplayName("requeueByStatus, healAttempts and incrementFailedAttempts update rows in bulk")
    void chunkBulkUpdates() {
        Book book = book(ProcessingStatus.PROCESSING, "x");
        Chunks stale = chunk(book, null, ChunkStatus.PROCESSING, 0, 5);
        Chunks failed = chunk(book, null, ChunkStatus.FAILED, 1, 5);
        Chunks done = chunk(book, null, ChunkStatus.COMPLETED, 2, 1);

        assertEquals(2, chunksRepository.requeueByStatus(book.getId(),
                List.of(ChunkStatus.PROCESSING, ChunkStatus.FAILED), ChunkStatus.AWAITING));

        assertEquals(2, chunksRepository.healAttempts(book.getId(),
                List.of(ChunkStatus.AWAITING, ChunkStatus.FAILED, ChunkStatus.PROCESSING), 1));
        // Only rows ABOVE the healed value change: a second call is a no-op.
        assertEquals(0, chunksRepository.healAttempts(book.getId(),
                List.of(ChunkStatus.AWAITING, ChunkStatus.FAILED, ChunkStatus.PROCESSING), 1));

        assertEquals(2, chunksRepository.incrementFailedAttempts(book.getId(), ChunkStatus.COMPLETED));

        Chunks healed = chunksRepository.findById(stale.getId()).orElseThrow();
        assertEquals(ChunkStatus.AWAITING, healed.getStatus());
        assertEquals(2, healed.getAttempts(), "heal clamped to 1, then +1 for the failed run");
        Chunks untouched = chunksRepository.findById(done.getId()).orElseThrow();
        assertEquals(1, untouched.getAttempts(), "Completed chunks never consume attempts");
    }

    @Test
    @DisplayName("requeueAllStaleProcessing heals orphan PROCESSING rows across ALL books (startup cure)")
    void globalOrphanProcessingRequeue() {
        Book bookA = book(ProcessingStatus.AWAITING, "a");
        Book bookB = book(ProcessingStatus.AWAITING, "b");
        chunk(bookA, null, ChunkStatus.PROCESSING, 0, 1);
        chunk(bookB, null, ChunkStatus.PROCESSING, 0, 1);
        chunk(bookA, null, ChunkStatus.COMPLETED, 1, 0);

        int healed = chunksRepository.requeueAllStaleProcessing(ChunkStatus.PROCESSING, ChunkStatus.AWAITING);

        assertEquals(2, healed, "GLOBAL: cruza libros, sin filtro de bookId");
        assertEquals(0, chunksRepository.countByBookAndStatus(bookA, ChunkStatus.PROCESSING));
        assertEquals(0, chunksRepository.countByBookAndStatus(bookB, ChunkStatus.PROCESSING));
        assertEquals(1, chunksRepository.countByBookAndStatus(bookA, ChunkStatus.COMPLETED),
                "Los COMPLETED quedan intactos");
    }

    @Test
    @DisplayName("Bulk deletes remove a book's chunks/blocks/traces/terms in one statement each")
    void bulkDeletesByBookId() {
        Book book = book(ProcessingStatus.FAILED, "x");
        Block block = new Block();
        block.setBook(book);
        block.setBlockType(Block.BlockType.PROLOGUE);
        block.setBlockNumber(1);
        block.setPosition(0);
        blockRepository.saveAndFlush(block);
        chunk(book, block, ChunkStatus.COMPLETED, 0, 0); // block-bound chunk
        chunk(book, null, ChunkStatus.COMPLETED, 1, 0);   // legacy chunk (no block)
        trace(book, ProcessStep.TRANSLATE, TraceEventStatus.FAILED, 1);

        TranslationTerm term = new TranslationTerm();
        term.setBook(book);
        term.setSourceTerm("source");
        term.setTargetTerm("target");
        term.setCreatedAt(Instant.now());
        term.setUpdatedAt(Instant.now());
        termRepository.saveAndFlush(term);

        assertEquals(1, chunksRepository.deleteByBlockIdInBulk(block.getId()), "Only the block-bound chunk");
        assertEquals(1, chunksRepository.deleteByBookIdInBulk(book.getId()), "The remaining legacy chunk");
        assertEquals(1, blockRepository.deleteByBookIdInBulk(book.getId()));
        assertEquals(1, traceEventRepository.deleteByBookIdInBulk(book.getId()));
        assertEquals(1, termRepository.deleteByBookIdInBulk(book.getId()));
        assertEquals(1, bookRepository.deleteByIdInBulk(book.getId()));

        assertTrue(bookRepository.findById(book.getId()).isEmpty());
        assertEquals(0, chunksRepository.countByBookId(book.getId()));
    }

    // ------------------------------------------------------------------
    // Grouped aggregates (N+1 killers)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("countByBookIds aggregates total/completed for many books in ONE query")
    void groupedChunkCounts() {
        Book bookA = book(ProcessingStatus.PROCESSING, "a");
        Book bookB = book(ProcessingStatus.PROCESSING, "b");
        chunk(bookA, null, ChunkStatus.COMPLETED, 0, 1);
        chunk(bookA, null, ChunkStatus.AWAITING, 1, 0);
        chunk(bookB, null, ChunkStatus.COMPLETED, 0, 1);

        List<ChunksRepository.ChunkCountView> counts = chunksRepository.countByBookIds(
                List.of(bookA.getId(), bookB.getId()), ChunkStatus.COMPLETED);
        assertEquals(2, counts.size());
        ChunksRepository.ChunkCountView viewA = counts.stream()
                .filter(v -> v.getBookId().equals(bookA.getId())).findFirst().orElseThrow();
        assertEquals(2, viewA.getTotal());
        assertEquals(1, viewA.getCompleted());
    }

    @Test
    @DisplayName("countByNovelIds and findByVolumeIdIn batch the catalog page queries")
    void catalogBatchQueries() {
        Novel novel = new Novel();
        novel.setTitle("Novela A");
        novel.setCreatedAt(Instant.now());
        novelRepository.saveAndFlush(novel);

        NovelVolume volume1 = new NovelVolume();
        volume1.setNovel(novel);
        volume1.setSourceKey("vol:1");
        volume1.setCreatedAt(Instant.now());
        volumeRepository.saveAndFlush(volume1);
        NovelVolume volume2 = new NovelVolume();
        volume2.setNovel(novel);
        volume2.setSourceKey("vol:2");
        volume2.setCreatedAt(Instant.now());
        volumeRepository.saveAndFlush(volume2);

        NovelVolumeFile file1 = new NovelVolumeFile();
        file1.setVolume(volume1);
        file1.setRemoteHref("/a/v1.epub");
        file1.setFormat(NovelVolumeFile.Format.EPUB);
        file1.setCreatedAt(Instant.now());
        fileRepository.saveAndFlush(file1);

        List<NovelVolumeRepository.NovelVolumeCount> counts =
                volumeRepository.countByNovelIds(List.of(novel.getId()));
        assertEquals(1, counts.size());
        assertEquals(2, counts.get(0).getVolumeCount());

        List<NovelVolumeFile> files = fileRepository.findByVolumeIdIn(List.of(volume1.getId(), volume2.getId()));
        assertEquals(1, files.size());
        assertEquals(volume1.getId(), files.get(0).getVolume().getId());

        List<NovelVolume> byNovel = volumeRepository.findByNovel(novel);
        // findByNovel still works (legacy path)
        assertEquals(2, byNovel.size());
    }

    // ------------------------------------------------------------------
    // Trace counting
    // ------------------------------------------------------------------

    @Test
    @DisplayName("countFailedAttempts counts only FAILED events within the budget")
    void genuineAttemptCounting() {
        Book book = book(ProcessingStatus.FAILED, "x");
        trace(book, ProcessStep.TRANSLATE, TraceEventStatus.COMPLETED, 1);
        trace(book, ProcessStep.TRANSLATE, TraceEventStatus.FAILED, 2);
        trace(book, ProcessStep.TRANSLATE, TraceEventStatus.STARTED, 2); // interrupted
        trace(book, ProcessStep.TRANSLATE, TraceEventStatus.FAILED, 4); // pseudo/blocked
        trace(book, ProcessStep.SPLIT, TraceEventStatus.FAILED, 1);      // other step

        assertEquals(1, traceEventRepository.countFailedAttempts(
                book.getId(), ProcessStep.TRANSLATE, TraceEventStatus.FAILED, 3));
        assertEquals(1, traceEventRepository.countFailedAttempts(
                book.getId(), ProcessStep.SPLIT, TraceEventStatus.FAILED, 3));
        assertEquals(0, traceEventRepository.countFailedAttempts(
                UUID.randomUUID(), ProcessStep.TRANSLATE, TraceEventStatus.FAILED, 3));
    }
}
