package com.glez.frontendservice.repository;

import com.glez.frontendservice.model.Book;
import com.glez.frontendservice.model.ProcessStep;
import com.glez.frontendservice.model.ProcessingStatus;
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
public interface BookRepository extends JpaRepository<Book, UUID> {
    List<Book> findByStatusIn(Collection<ProcessingStatus> statuses);

    // ------------------------------------------------------------------
    // Optimized pipeline/book paths: none of these load the full_text CLOB.
    // ------------------------------------------------------------------

    /**
     * Ids of books in the given statuses (startup recovery): avoids loading
     * every matching Book entity with its multi-megabyte full_text CLOB.
     */
    @Query("select b.id from Book b where b.status in :statuses")
    List<UUID> findIdsByStatusIn(@Param("statuses") Collection<ProcessingStatus> statuses);

    /** Light book info for the catalog views: no entity load, no CLOB read. */
    @Query("select b.id as id, b.status as status, b.currentStep as currentStep, "
            + "b.lastTraceId as lastTraceId, b.audioFilePath as audioFilePath "
            + "from Book b where b.id in :bookIds")
    List<BookStatusView> findStatusViews(@Param("bookIds") Collection<UUID> bookIds);

    /** Book projection used by the novels manager (status derived from Book). */
    interface BookStatusView {
        UUID getId();

        ProcessingStatus getStatus();

        ProcessStep getCurrentStep();

        String getLastTraceId();

        String getAudioFilePath();
    }

    /**
     * Every book with only its light columns (list endpoint), ordered by
     * name: never loads the multi-megabyte full_text CLOB of any book.
     */
    @Query("select b.id as id, b.name as name, b.status as status, b.currentStep as currentStep, "
            + "b.lastTraceId as lastTraceId, b.audioFilePath as audioFilePath "
            + "from Book b order by b.name asc")
    List<BookListView> findBookListViews();

    /** Light book row for listings. */
    interface BookListView {
        UUID getId();

        String getName();

        ProcessingStatus getStatus();

        ProcessStep getCurrentStep();

        String getLastTraceId();

        String getAudioFilePath();
    }

    /**
     * Whether the book has non-blank extracted text, without reading the
     * CLOB. Native SQL because Hibernate's JPQL type checker rejects
     * trim()/length() on CLOB-mapped strings (H2 itself supports them).
     */
    @Query(value = "select count(*) > 0 from book b "
            + "where b.id = :bookId and b.full_text is not null "
            + "and length(trim(b.full_text)) > 0",
            nativeQuery = true)
    boolean hasExtractedText(@Param("bookId") UUID bookId);

    /** Checkpoint update (current step + trace) without loading the CLOB. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("update Book b set b.currentStep = :step, b.lastTraceId = :traceId where b.id = :bookId")
    int updateCheckpoint(@Param("bookId") UUID bookId,
                         @Param("step") ProcessStep step,
                         @Param("traceId") String traceId);

    /** Status transition guarded by the expected previous status; returns rows changed. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("update Book b set b.status = :target where b.id = :bookId and b.status = :expected")
    int updateStatusWhen(@Param("bookId") UUID bookId,
                         @Param("expected") ProcessingStatus expected,
                         @Param("target") ProcessingStatus target);

    /** Status + trace pointer update (failure marking) without loading the CLOB. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("update Book b set b.status = :status, b.lastTraceId = :traceId where b.id = :bookId")
    int updateStatusAndTrace(@Param("bookId") UUID bookId,
                             @Param("status") ProcessingStatus status,
                             @Param("traceId") String traceId);

    /** Bulk delete that skips loading the book (and its CLOB) before deleting. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("delete from Book b where b.id = :bookId")
    int deleteByIdInBulk(@Param("bookId") UUID bookId);
}
