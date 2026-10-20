package com.glez.frontendservice.controllers;

import com.glez.frontendservice.model.LogEvent;
import com.glez.frontendservice.repository.LogEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test del filtro traceId del histórico de logs (GET /api/logs/search):
 * cada proceso — un libro, un proyecto del asistente, cualquier traza —
 * debe poder consultarse POR SEPARADO en el histórico persistido. Corre
 * contra H2 real invocando la MISMA Specification del controlador (vía
 * reflexión: es privada, el patrón de NvidiaAiServiceTest para métodos
 * privados).
 */
@DataJpaTest
@AutoConfigureTestDatabase
@DisplayName("Log search: filtro traceId por proceso (H2 real)")
class LogStreamControllerSearchTest {

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
    private LogEventRepository logEventRepository;

    private void logWithTrace(String traceId, String message) {
        LogEvent event = new LogEvent();
        event.setLoggedAt(Instant.now());
        event.setLevel("INFO");
        event.setLogger("TestLogger");
        event.setThread("test");
        event.setMessage(message);
        event.setTraceId(traceId);
        logEventRepository.save(event);
    }

    /** Specification real del controlador (privada y estática) vía reflexión. */
    @SuppressWarnings("unchecked")
    private Specification<LogEvent> specification(String traceId) throws Exception {
        Method method = LogStreamController.class.getDeclaredMethod("searchSpecification",
                Instant.class, Instant.class, String.class, List.class, String.class, String.class, String.class);
        method.setAccessible(true);
        return (Specification<LogEvent>) method.invoke(null,
                null, null, null, List.of(), null, traceId, null);
    }

    @Test
    @DisplayName("traceId aísla los eventos de UN proceso: solo los de esa traza")
    void search_byTraceId_isolatesTheProcess() throws Exception {
        logWithTrace("proyecto-wizard-1", "[wizard] Paso 2 iniciado");
        logWithTrace("proyecto-wizard-2", "[wizard] Paso 2 iniciado");
        logWithTrace(null, "[llm] línea sin traza");
        logEventRepository.flush();

        List<LogEvent> found = logEventRepository
                .findAll(specification("proyecto-wizard-1"), PageRequest.of(0, 50))
                .getContent();

        assertEquals(1, found.size(), "Solo los eventos del proceso con esa traza");
        assertEquals("proyecto-wizard-1", found.get(0).getTraceId());
    }

    @Test
    @DisplayName("Sin traceId el histórico no filtra: devuelve todo")
    void search_withoutTraceId_returnsEverything() throws Exception {
        logWithTrace("proyecto-wizard-1", "[wizard] Paso 3");
        logWithTrace(null, "[llm] línea sin traza");
        logEventRepository.flush();

        List<LogEvent> found = logEventRepository
                .findAll(specification(null), PageRequest.of(0, 50))
                .getContent();

        assertEquals(2, found.size(), "Parámetro vacío = sin filtro de traza");
    }
}
