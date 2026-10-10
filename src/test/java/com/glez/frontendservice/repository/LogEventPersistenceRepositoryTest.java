package com.glez.frontendservice.repository;

import com.glez.frontendservice.model.LogEvent;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de integración del histórico de logs persistido: el JPQL del
 * borrado en bloque de la retención, las especificaciones de búsqueda del
 * endpoint y los índices declarados en la entidad — contra H2 real, de
 * modo que un error de JPQL o una anotación @Index mal puesta falla aquí.
 */
@DataJpaTest
@AutoConfigureTestDatabase
@DisplayName("Persisted log events: retention delete, search specifications and indexes")
class LogEventPersistenceRepositoryTest {

    /** El slice @DataJpaTest no incluye la auto-configuración de caché. */
    @TestConfiguration
    static class CacheManagerTestConfig {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager();
        }
    }

    @Autowired
    private LogEventRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("Retention bulk delete removes only events older than the cutoff")
    void retentionDelete_removesOnlyOldEvents() {
        Instant now = Instant.now();
        repository.save(new LogEvent(null, now.minus(Duration.ofDays(9)), "INFO",
                "c.g.f.Old", "t", "antiguo", null, null, null));
        repository.save(new LogEvent(null, now.minus(Duration.ofDays(2)), "WARN",
                "c.g.f.Recent", "t", "reciente warn", null, null, null));
        repository.save(new LogEvent(null, now.minus(Duration.ofHours(1)), "ERROR",
                "c.g.f.Recent", "t", "reciente error", "book-1", "trace-1", "TRANSLATE"));
        entityManager.flush();

        int deleted = repository.deleteByLoggedAtBefore(now.minus(Duration.ofDays(7)));

        assertEquals(1, deleted);
        assertEquals(2, repository.count());
        assertTrue(repository.findAll().stream().noneMatch(e -> "antiguo".equals(e.getMessage())));
    }

    @Test
    @DisplayName("Search specifications filter by time range, level, book and free text")
    void searchSpecifications_filterEveryDimension() {
        Instant now = Instant.now();
        repository.save(new LogEvent(null, now.minus(Duration.ofHours(3)), "ERROR",
                "c.g.f.services.ChunkTranslationService", "t",
                "Error translating chunk abc", "book-1", "chunk-1", "TRANSLATE"));
        repository.save(new LogEvent(null, now.minus(Duration.ofHours(2)), "INFO",
                "c.g.f.services.BookProcessingService", "t",
                "Traducción individual solicitada", "book-1", "chunk-1", "TRANSLATE"));
        repository.save(new LogEvent(null, now.minus(Duration.ofHours(1)), "WARN",
                "c.g.f.services.NovelsCatalogSyncService", "t",
                "pausa anti-ban", null, null, null));
        entityManager.flush();

        // Nivel exacto.
        assertEquals(1, repository.count(levelSpec("ERROR")));
        assertEquals(1, repository.count(levelSpec("WARN")));

        // Por libro del pipeline.
        assertEquals(2, repository.count(bookSpec("book-1")));
        assertEquals(0, repository.count(bookSpec("book-other")));

        // Rango de tiempo: solo la última hora. (Mismo patrón cb.and(...) que
        // el controller: conjunction().getExpressions().add() NO aplica los
        // predicados en Hibernate 6.)
        Specification<LogEvent> lastHour = (root, query, cb) -> cb.and(
                cb.greaterThanOrEqualTo(root.get("loggedAt"), now.minus(Duration.ofHours(1))));
        assertEquals(1, repository.count(lastHour));

        // Texto libre en mensaje o clase, paginado y orden descendente.
        Page<LogEvent> page = repository.findAll(textSpec("traducción"),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "loggedAt")));
        assertEquals(1, page.getTotalElements());
        assertEquals("Traducción individual solicitada", page.getContent().get(0).getMessage());
    }

    @Test
    @DisplayName("The retention/search indexes exist on the generated table")
    void indexes_exist() {
        Object count = entityManager.createNativeQuery(
                        "select count(*) from INFORMATION_SCHEMA.INDEXES "
                                + "where TABLE_NAME = 'LOG_EVENT' and INDEX_NAME in "
                                + "('IDX_LOG_EVENT_LOGGED_AT','IDX_LOG_EVENT_BOOK_LOGGED_AT','IDX_LOG_EVENT_LEVEL_LOGGED_AT')")
                .getSingleResult();
        assertEquals(3L, ((Number) count).longValue());
    }

    private static Specification<LogEvent> levelSpec(String level) {
        return (root, query, cb) -> cb.equal(root.get("level"), level);
    }

    private static Specification<LogEvent> bookSpec(String bookId) {
        return (root, query, cb) -> cb.equal(root.get("bookId"), bookId);
    }

    private static Specification<LogEvent> textSpec(String text) {
        return (root, query, cb) -> cb.like(cb.lower(root.get("message")),
                "%" + text.toLowerCase() + "%");
    }
}
