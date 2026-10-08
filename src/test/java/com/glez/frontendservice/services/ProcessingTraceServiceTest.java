package com.glez.frontendservice.services;

import com.glez.frontendservice.model.Book;
import com.glez.frontendservice.model.ProcessStep;
import com.glez.frontendservice.model.ProcessTraceEvent;
import com.glez.frontendservice.model.TraceEventStatus;
import com.glez.frontendservice.repository.BookRepository;
import com.glez.frontendservice.repository.ProcessTraceEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProcessingTraceService Tests")
class ProcessingTraceServiceTest {

    @Mock
    private ProcessTraceEventRepository traceEventRepository;

    @Mock
    private BookRepository bookRepository;

    private ProcessingTraceService traceService;

    private UUID bookId;

    @BeforeEach
    void setUp() throws Exception {
        traceService = new ProcessingTraceService(traceEventRepository, bookRepository);
        setField("maxAttempts", 3);
        bookId = UUID.randomUUID();
        MDC.clear();
    }

    private void setField(String name, Object value) throws Exception {
        java.lang.reflect.Field field = traceService.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(traceService, value);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    private Book book() {
        Book book = new Book();
        book.setId(bookId);
        return book;
    }

    @Test
    @DisplayName("startStep persists a STARTED event, moves the checkpoint and populates MDC")
    void startStep_persistsEvent_updatesCheckpoint_andSetsMdc() {
        Book book = book();
        lenient().when(bookRepository.getReferenceById(bookId)).thenReturn(book);
        when(traceEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProcessTraceEvent event = traceService.startStep(bookId, ProcessStep.TRANSLATE, "trace-1", "2/10 chunks");

        assertNotNull(event);
        assertEquals(ProcessStep.TRANSLATE, event.getStep());
        assertEquals(TraceEventStatus.STARTED, event.getStatus());
        assertEquals(1, event.getAttempt());
        assertEquals("trace-1", event.getTraceId());
        assertEquals("2/10 chunks", event.getDetails());
        assertNotNull(event.getStartedAt());

        // Book checkpoint moved with the bulk update (no CLOB load)
        verify(bookRepository).updateCheckpoint(bookId, ProcessStep.TRANSLATE, "trace-1");

        // MDC correlation context
        assertEquals(bookId.toString(), MDC.get(ProcessingTraceService.MDC_BOOK_ID));
        assertEquals("trace-1", MDC.get(ProcessingTraceService.MDC_TRACE_ID));
        assertEquals("TRANSLATE", MDC.get(ProcessingTraceService.MDC_STEP));
    }

    @Test
    @DisplayName("Attempt counter comes from countFailedAttempts (FAILED events within the budget)")
    void startStep_countsAttempts_fromTheFailureCountQuery() {
        // The FAILED / attempt <= max filtering lives in the repository
        // COUNT query; the service only adds 1 to it.
        Book book = book();
        lenient().when(bookRepository.getReferenceById(bookId)).thenReturn(book);
        when(traceEventRepository.countFailedAttempts(bookId, ProcessStep.SPLIT, TraceEventStatus.FAILED, 3))
                .thenReturn(1L);
        when(traceEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProcessTraceEvent event = traceService.startStep(bookId, ProcessStep.SPLIT, "trace-2", null);

        assertEquals(2, event.getAttempt(),
                "One genuine FAILED attempt (within budget) => next attempt is 2");
    }

    @Test
    @DisplayName("A successful step never consumes the budget: zero failures count as zero")
    void startStep_afterSuccessfulRuns_countsZero() {
        Book book = book();
        lenient().when(bookRepository.getReferenceById(bookId)).thenReturn(book);
        when(traceEventRepository.countFailedAttempts(bookId, ProcessStep.SPLIT, TraceEventStatus.FAILED, 3))
                .thenReturn(0L);
        when(traceEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProcessTraceEvent event = traceService.startStep(bookId, ProcessStep.SPLIT, "trace-2", null);

        assertEquals(1, event.getAttempt(),
                "Block-scoped re-runs of a successful step must not exhaust the budget");
    }

    @Test
    @DisplayName("countGenuineAttempts exposes the same filter for the chunk heal")
    void countGenuineCounts_onlyGenuineEvents() {
        when(traceEventRepository.countFailedAttempts(bookId, ProcessStep.TRANSLATE, TraceEventStatus.FAILED, 3))
                .thenReturn(1L);

        assertEquals(1, traceService.countGenuineAttempts(bookId, ProcessStep.TRANSLATE));
    }

    @Test
    @DisplayName("completeStep marks the event COMPLETED with finish timestamp")
    void completeStep_marksCompleted() {
        ProcessTraceEvent event = new ProcessTraceEvent();
        event.setStep(ProcessStep.AUDIO);
        event.setStatus(TraceEventStatus.STARTED);
        event.setStartedAt(Instant.now().minusSeconds(5));
        event.setAttempt(1);
        when(traceEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        traceService.completeStep(event, "audio.mp3 generated");

        assertEquals(TraceEventStatus.COMPLETED, event.getStatus());
        assertNotNull(event.getFinishedAt());
        assertEquals("audio.mp3 generated", event.getDetails());
        verify(traceEventRepository).save(event);
    }

    @Test
    @DisplayName("failStep persists the FAILED status and the error message")
    void failStep_persistsError() {
        ProcessTraceEvent event = new ProcessTraceEvent();
        event.setStep(ProcessStep.TRANSLATE);
        event.setStatus(TraceEventStatus.STARTED);
        event.setStartedAt(Instant.now());
        when(traceEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        traceService.failStep(event, new IllegalStateException("boom"));

        assertEquals(TraceEventStatus.FAILED, event.getStatus());
        assertNotNull(event.getFinishedAt());
        assertEquals("boom", event.getErrorMessage());
        verify(traceEventRepository).save(event);
    }

    @Test
    @DisplayName("skipStep records a SKIPPED event with the reason")
    void skipStep_recordsReason() {
        lenient().when(bookRepository.getReferenceById(bookId)).thenReturn(book());
        when(traceEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        traceService.skipStep(bookId, ProcessStep.EXTRACT, "trace-3", "already extracted");

        ArgumentCaptor<ProcessTraceEvent> captor = ArgumentCaptor.forClass(ProcessTraceEvent.class);
        verify(traceEventRepository).save(captor.capture());
        assertEquals(TraceEventStatus.SKIPPED, captor.getValue().getStatus());
        assertEquals("already extracted", captor.getValue().getDetails());
        assertEquals(ProcessStep.EXTRACT, captor.getValue().getStep());
    }

    @Test
    @DisplayName("getTimeline returns the events mapped to DTOs in order")
    void getTimeline_mapsEventsToDtos() {
        Book book = book();
        when(bookRepository.existsById(bookId)).thenReturn(true);
        when(bookRepository.getReferenceById(bookId)).thenReturn(book);
        ProcessTraceEvent first = new ProcessTraceEvent();
        first.setId(UUID.randomUUID());
        first.setTraceId("t1");
        first.setStep(ProcessStep.SPLIT);
        first.setStatus(TraceEventStatus.COMPLETED);
        first.setAttempt(1);
        first.setStartedAt(Instant.now());
        first.setFinishedAt(Instant.now());
        when(traceEventRepository.findByBookOrderByStartedAtAscIdAsc(book)).thenReturn(List.of(first));

        var timeline = traceService.getTimeline(bookId);

        assertEquals(1, timeline.size());
        assertEquals("t1", timeline.get(0).traceId());
        assertEquals(ProcessStep.SPLIT, timeline.get(0).step());
        assertEquals(TraceEventStatus.COMPLETED, timeline.get(0).status());
    }

    @Test
    @DisplayName("getTimeline throws for unknown books")
    void getTimeline_withUnknownBook_throws() {
        when(bookRepository.existsById(bookId)).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> traceService.getTimeline(bookId));
    }

    @Test
    @DisplayName("clearMdc removes all correlation keys")
    void clearMdc_removesKeys() {
        ProcessingTraceService.putMdc(bookId, "trace-x", ProcessStep.AUDIO);
        ProcessingTraceService.clearMdc();
        assertNull(MDC.get(ProcessingTraceService.MDC_BOOK_ID));
        assertNull(MDC.get(ProcessingTraceService.MDC_TRACE_ID));
        assertNull(MDC.get(ProcessingTraceService.MDC_STEP));
    }
}
