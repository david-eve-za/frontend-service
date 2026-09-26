package com.glez.frontendservice.repository;


import com.glez.frontendservice.model.Book;
import com.glez.frontendservice.model.ProcessTraceEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ProcessTraceEventRepository extends JpaRepository<ProcessTraceEvent, UUID> {
    List<ProcessTraceEvent> findByBookOrderByStartedAtAscIdAsc(Book book);

    List<ProcessTraceEvent> findByBookAndStepOrderByStartedAtDesc(Book book,
                                                                   com.glez.frontendservice.model.ProcessStep step);
}
