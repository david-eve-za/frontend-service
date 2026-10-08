package com.glez.frontendservice.repository;

import com.glez.frontendservice.model.Block;
import com.glez.frontendservice.model.Book;
import com.glez.frontendservice.model.ChunkStatus;
import com.glez.frontendservice.model.Chunks;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface ChunksRepository extends JpaRepository<Chunks, UUID> {
    List<Chunks> findByBook(Book book);
    Page<Chunks> findByBook(Book book, Pageable pageable); // New method
    long countByBook(Book book);
    long countByBookAndStatus(Book book, com.glez.frontendservice.model.ChunkStatus status);

    /** Chunks de un bloque concreto, en orden de posición dentro del bloque. */
    List<Chunks> findByBlockOrderByPositionAsc(Block block);

    // ------------------------------------------------------------------
    // Optimized paths: counts and id-only scans never materialize the
    // original_text/translated_text CLOBs.
    // ------------------------------------------------------------------

    /** Chunk count by book id: replaces findByBook(book).size() (CLOB load). */
    long countByBookId(UUID bookId);

    /** Light (id, status, attempts) view of every chunk of a book. */
    @Query("select c.id as id, c.status as status, c.attempts as attempts "
            + "from Chunks c where c.book.id = :bookId")
    List<ChunkStatusView> findStatusViewsByBookId(@Param("bookId") UUID bookId);

    /** Chunk status/attempt projection used by the TRANSLATE step. */
    interface ChunkStatusView {
        UUID getId();

        ChunkStatus getStatus();

        Integer getAttempts();
    }

    /** Grouped total/completed counts for many books in ONE query (N+1 killer). */
    @Query("select c.book.id as bookId, count(c) as total, "
            + "sum(case when c.status = :completed then 1 else 0 end) as completed "
            + "from Chunks c where c.book.id in :bookIds group by c.book.id")
    List<ChunkCountView> countByBookIds(@Param("bookIds") Collection<UUID> bookIds,
                                        @Param("completed") ChunkStatus completed);

    /** Aggregate chunk counters per book. */
    interface ChunkCountView {
        UUID getBookId();

        long getTotal();

        long getCompleted();
    }

    /** Ids of the chunks of a book in one status, reading order. */
    @Query("select c.id from Chunks c where c.book.id = :bookId and c.status = :status "
            + "order by c.position asc")
    List<UUID> findIdsByBookIdAndStatus(@Param("bookId") UUID bookId,
                                        @Param("status") ChunkStatus status);

    /** Ids of the chunks of a book NOT in a status (genuinely failed chunks). */
    @Query("select c.id from Chunks c where c.book.id = :bookId and c.status <> :status")
    List<UUID> findIdsByBookIdAndStatusNot(@Param("bookId") UUID bookId,
                                           @Param("status") ChunkStatus status);

    /** All block-bound chunks of a book (per-block audio step), reading order. */
    @Query("select c from Chunks c where c.book.id = :bookId and c.block is not null "
            + "order by c.position asc")
    List<Chunks> findBlockChunksByBookId(@Param("bookId") UUID bookId);

    /** Bulk requeue of stale statuses (FAILED/PROCESSING -> AWAITING). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("update Chunks c set c.status = :target where c.book.id = :bookId and c.status in :statuses")
    int requeueByStatus(@Param("bookId") UUID bookId,
                        @Param("statuses") Collection<ChunkStatus> statuses,
                        @Param("target") ChunkStatus target);

    /** Bulk heal of inflated legacy attempt counters; returns rows healed. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("update Chunks c set c.attempts = :healed "
            + "where c.book.id = :bookId and c.status in :statuses and c.attempts > :healed")
    int healAttempts(@Param("bookId") UUID bookId,
                     @Param("statuses") Collection<ChunkStatus> statuses,
                     @Param("healed") int healed);

    /** Bulk attempt increment for every non-completed chunk after a failed run. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("update Chunks c set c.attempts = c.attempts + 1 "
            + "where c.book.id = :bookId and c.status <> :completed")
    int incrementFailedAttempts(@Param("bookId") UUID bookId,
                                @Param("completed") ChunkStatus completed);

    /** Bulk delete of a book's chunks (regeneration), without loading CLOBs. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("delete from Chunks c where c.book.id = :bookId")
    int deleteByBookIdInBulk(@Param("bookId") UUID bookId);

    /** Bulk delete of a block's chunks (block-scoped regeneration). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("delete from Chunks c where c.block.id = :blockId")
    int deleteByBlockIdInBulk(@Param("blockId") UUID blockId);
}
