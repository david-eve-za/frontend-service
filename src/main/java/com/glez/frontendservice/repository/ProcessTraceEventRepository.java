package com.glez.frontendservice.repository;


import com.glez.frontendservice.model.Book;
import com.glez.frontendservice.model.ProcessStep;
import com.glez.frontendservice.model.ProcessTraceEvent;
import com.glez.frontendservice.model.TraceEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Repository
public interface ProcessTraceEventRepository extends JpaRepository<ProcessTraceEvent, UUID> {
    List<ProcessTraceEvent> findByBookOrderByStartedAtAscIdAsc(Book book);

    List<ProcessTraceEvent> findByBookAndStepOrderByStartedAtDesc(Book book,
                                                                   com.glez.frontendservice.model.ProcessStep step);

    /**
     * Number of genuine (concluded, FAILED) attempts of a step, capped at
     * the configured budget: one COUNT instead of loading every event of
     * the book+step and filtering in memory.
     */
    @Query("select count(e) from ProcessTraceEvent e where e.book.id = :bookId "
            + "and e.step = :step and e.status = :failed and e.attempt <= :maxAttempts")
    long countFailedAttempts(@Param("bookId") UUID bookId,
                             @Param("step") ProcessStep step,
                             @Param("failed") TraceEventStatus failed,
                             @Param("maxAttempts") int maxAttempts);

    /** Bulk delete of a book's trace events (book deletion). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("delete from ProcessTraceEvent e where e.book.id = :bookId")
    int deleteByBookIdInBulk(@Param("bookId") UUID bookId);
}
