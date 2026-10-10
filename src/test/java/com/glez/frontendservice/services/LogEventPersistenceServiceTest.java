package com.glez.frontendservice.services;

import ch.qos.logback.classic.Level;
import com.glez.frontendservice.model.LogEvent;
import com.glez.frontendservice.repository.LogEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("LogEventPersistenceService Tests")
class LogEventPersistenceServiceTest {

    @Mock
    private LogEventRepository repository;

    private LogEventPersistenceService service;

    @BeforeEach
    void setUp() {
        // Cola y servicio frescos por test; los @Value llegan por constructor.
        service = new LogEventPersistenceService(repository, true, "INFO", 7);
    }

    @Nested
    @DisplayName("captures (nivel mínimo configurable)")
    class CapturesTests {

        @Test
        @DisplayName("INFO and above pass; DEBUG/TRACE are skipped at the default threshold")
        void captures_infoThreshold() {
            assertTrue(service.captures(Level.ERROR));
            assertTrue(service.captures(Level.WARN));
            assertTrue(service.captures(Level.INFO));
            assertFalse(service.captures(Level.DEBUG));
            assertFalse(service.captures(Level.TRACE));
            assertFalse(service.captures(null));
        }

        @Test
        @DisplayName("A WARN threshold keeps WARN+ only; disabled captures nothing")
        void captures_warnThreshold_andDisabled() {
            LogEventPersistenceService warnService = new LogEventPersistenceService(repository, true, "warn", 7);
            assertTrue(warnService.captures(Level.WARN));
            assertFalse(warnService.captures(Level.INFO));

            LogEventPersistenceService disabled = new LogEventPersistenceService(repository, false, "INFO", 7);
            assertFalse(disabled.captures(Level.ERROR));
        }
    }

    @Nested
    @DisplayName("capture (encolado)")
    class CaptureTests {

        @Test
        @DisplayName("Enqueues the event with the MDC pipeline context")
        void capture_withMdc_enqueuesEvent() {
            service.capture("INFO", "c.g.f.services.BookProcessingService", "main",
                    "Traducción individual solicitada",
                    Map.of("bookId", "f9143480-6cae-40a9-8288-0aac08fda16d", "step", "TRANSLATE"));

            assertEquals(1, service.pendingCount());
            service.flush();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<LogEvent>> captor = ArgumentCaptor.forClass(List.class);
            verify(repository).saveAll(captor.capture());
            LogEvent saved = captor.getValue().get(0);
            assertEquals(Level.INFO.toString(), saved.getLevel());
            assertEquals("f9143480-6cae-40a9-8288-0aac08fda16d", saved.getBookId());
            assertEquals("TRANSLATE", saved.getStep());
            assertNotNull(saved.getLoggedAt());
            // El id lo emite Hibernate al persistir (no se pre-asigna).
        }

        @Test
        @DisplayName("Its own events are excluded to avoid the feedback loop")
        void capture_selfLogger_isExcluded() {
            service.capture("WARN", "com.glez.frontendservice.services.LogEventPersistenceService",
                    "log-persist", "Cola llena", Map.of());
            service.capture("WARN", "com.glez.frontendservice.logging.LogEventDbAppender",
                    "main", "append", Map.of());

            assertEquals(0, service.pendingCount());
        }

        @Test
        @DisplayName("Messages longer than the cap are truncated")
        void capture_longMessage_isTruncated() {
            String huge = "x".repeat(50_000);

            service.capture("INFO", "c.g.f.SomeClass", "main", huge, Map.of());

            service.flush();
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<LogEvent>> captor = ArgumentCaptor.forClass(List.class);
            verify(repository).saveAll(captor.capture());
            assertEquals(LogEvent.MAX_MESSAGE_LENGTH, captor.getValue().get(0).getMessage().length());
        }

        @Test
        @DisplayName("A full queue drops the event instead of blocking")
        void capture_queueFull_dropsWithoutBlocking() {
            IntStream.rangeClosed(1, 20_000).forEach(i ->
                    service.capture("INFO", "c.g.f.SomeClass", "main", "m" + i, Map.of()));
            assertEquals(20_000, service.pendingCount());

            service.capture("INFO", "c.g.f.SomeClass", "main", "overflow", Map.of());

            assertEquals(20_000, service.pendingCount());
            assertEquals(1, service.droppedCount());
        }
    }

    @Nested
    @DisplayName("flush (volcado por lotes)")
    class FlushTests {

        @Test
        @DisplayName("Drains the queue and persists it in one saveAll batch")
        void flush_persistsBatchAndEmptiesQueue() {
            when(repository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
            service.capture("INFO", "c.g.f.A", "t1", "uno", Map.of());
            service.capture("WARN", "c.g.f.B", "t2", "dos", Map.of());

            service.flush();

            verify(repository).saveAll(anyList());
            assertEquals(0, service.pendingCount());
        }

        @Test
        @DisplayName("A DB failure re-enqueues the batch for the next tick (no loss, no duplicates)")
        void flush_dbFailure_reenqueuesBatch() {
            when(repository.saveAll(anyList())).thenThrow(new RuntimeException("H2 restarting"));
            service.capture("INFO", "c.g.f.A", "t1", "uno", Map.of());

            service.flush();

            assertEquals(1, service.pendingCount());

            // La BD vuelve: el próximo tick persiste lo reencolado.
            // doAnswer (no when(...)): al re-stubear, when() EJECUTA el método
            // en el mock y el thenThrow previo seguiría activo.
            doAnswer(inv -> inv.getArgument(0)).when(repository).saveAll(anyList());
            service.flush();
            verify(repository, times(2)).saveAll(anyList());
            assertEquals(0, service.pendingCount());
        }

        @Test
        @DisplayName("An empty queue does not touch the repository")
        void flush_emptyQueue_noRepositoryCall() {
            service.flush();
            verifyNoInteractions(repository);
        }
    }

    @Nested
    @DisplayName("purge (retención)")
    class PurgeTests {

        @Test
        @DisplayName("Deletes everything older than the configured retention window")
        void purge_deletesOlderThanCutoff() {
            when(repository.deleteByLoggedAtBefore(any(Instant.class))).thenReturn(42);

            service.purge();

            ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
            verify(repository).deleteByLoggedAtBefore(cutoff.capture());
            // Corte ≈ ahora menos 7 días: el capture ocurre justo antes de este
            // now(), así que el corte es anterior a (now - 7d) pero dentro de los
            // últimos milisegundos de esa resta.
            assertTrue(cutoff.getValue().isBefore(Instant.now().minus(Duration.ofDays(7))));
            assertTrue(cutoff.getValue()
                    .isAfter(Instant.now().minus(Duration.ofDays(7)).minusSeconds(5)));
        }

        @Test
        @DisplayName("A purge failure is logged, never propagated")
        void purge_failure_doesNotThrow() {
            when(repository.deleteByLoggedAtBefore(any(Instant.class)))
                    .thenThrow(new RuntimeException("db down"));

            assertDoesNotThrow(() -> service.purge());
        }

        @Test
        @DisplayName("Retention of at least one day is enforced even with bad config")
        void purge_minimumRetention() {
            LogEventPersistenceService one = new LogEventPersistenceService(repository, true, "INFO", 0);
            Instant before = Instant.now().minusSeconds(5);

            one.purge();

            ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
            verify(repository).deleteByLoggedAtBefore(cutoff.capture());
            // retention-days=0 se clampa a 1: el corte es de hace ~1 día.
            assertTrue(cutoff.getValue().isBefore(before));
        }
    }
}
