package com.glez.frontendservice.services;

import com.glez.frontendservice.model.ProcessingStatus;
import com.glez.frontendservice.repository.BookRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * On application startup, automatically resumes the pipeline of every book
 * that was left mid-processing by a crash or restart (status PROCESSING)
 * and every book that previously failed (status FAILED), bounded by the
 * configured max attempts per step.
 */
@Component
public class ProcessingStartupRecovery {

    private static final Logger log = LoggerFactory.getLogger(ProcessingStartupRecovery.class);

    private final BookRepository bookRepository;
    private final BookPipelineOrchestrator orchestrator;

    @Value("${app.processing.auto-resume:true}")
    private boolean autoResume;

    public ProcessingStartupRecovery(BookRepository bookRepository,
                                     BookPipelineOrchestrator orchestrator) {
        this.bookRepository = bookRepository;
        this.orchestrator = orchestrator;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedBooks() {
        if (!autoResume) {
            log.info("Auto-resume disabled (app.processing.auto-resume=false)");
            return;
        }
        List<com.glez.frontendservice.model.Book> recoverable = bookRepository.findByStatusIn(
                List.of(ProcessingStatus.PROCESSING, ProcessingStatus.FAILED));
        if (recoverable.isEmpty()) {
            log.info("No books to resume on startup");
            return;
        }
        log.info("Resuming {} book(s) interrupted by a previous run", recoverable.size());
        for (com.glez.frontendservice.model.Book book : recoverable) {
            try {
                orchestrator.processBook(book.getId());
            } catch (Exception e) {
                log.error("Could not resume book {}", book.getId(), e);
            }
        }
    }
}
