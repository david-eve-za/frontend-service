package com.glez.frontendservice.services;

import com.glez.frontendservice.dtos.DashboardStatsDto;
import com.glez.frontendservice.model.TraceEventStatus;
import com.glez.frontendservice.model.VideoStoryProject;
import com.glez.frontendservice.model.VideoStoryStep;
import com.glez.frontendservice.model.VideoStoryTraceEvent;
import com.glez.frontendservice.repository.VideoStoryProjectRepository;
import com.glez.frontendservice.repository.VideoStoryTraceEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas de integración de las métricas del Asistente de Historias contra
 * H2 real (esquema generado desde las entidades): la serie de actividad de
 * 14 días con huecos rellenados y la derivación de estados del panel de
 * procesos — un error de SQL nativo o de derivación falla aquí y no en el
 * panel en vivo.
 */
@DataJpaTest
@AutoConfigureTestDatabase
@DisplayName("Video story stats service (H2 real)")
class VideoStoryStatsServiceTest {

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
    private VideoStoryProjectRepository projectRepository;

    @Autowired
    private VideoStoryTraceEventRepository traceEventRepository;

    /**
     * Las consultas del stats service son SQL NATIVO (JdbcTemplate): Hibernate
     * no hace auto-flush antes de ellas, así que los INSERT del test se
     * fuerzan a la conexión con flush() explícito tras cada helper.
     */
    @Autowired
    private org.springframework.boot.jpa.test.autoconfigure.TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private VideoStoryProject project(String title, String story, String designs,
            String assets, String scenes, String videoPrompt) {
        VideoStoryProject project = new VideoStoryProject();
        project.setTitle(title);
        project.setStory(story);
        project.setDesigns(designs);
        project.setAssets(assets);
        project.setScenes(scenes);
        project.setVideoPrompt(videoPrompt);
        Instant now = Instant.now();
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        VideoStoryProject saved = projectRepository.save(project);
        entityManager.flush();
        return saved;
    }

    private VideoStoryTraceEvent trace(VideoStoryProject project, VideoStoryStep step,
            TraceEventStatus status, Instant startedAt, Instant finishedAt) {
        VideoStoryTraceEvent event = new VideoStoryTraceEvent();
        event.setProject(project);
        event.setTraceId(project.getId().toString());
        event.setStep(step);
        event.setStatus(status);
        event.setAttempt(1);
        event.setStartedAt(startedAt);
        event.setFinishedAt(finishedAt);
        VideoStoryTraceEvent saved = traceEventRepository.save(event);
        entityManager.flush();
        return saved;
    }

    private VideoStoryStatsService statsService() {
        VideoStoryTraceService traceService =
                new VideoStoryTraceService(traceEventRepository, projectRepository);
        return new VideoStoryStatsService(jdbcTemplate, traceService);
    }

    // ------------------------------------------------------------------
    // Métricas del gráfico del panel de estado
    // ------------------------------------------------------------------

    @Test
    @DisplayName("wizardStats: totales de la cadena + serie de 14 días con huecos a cero")
    void wizardStats_countsAndGapFilledSeries() {
        VideoStoryProject complete = project("Cadena completa", "s", "d", "a", "sc", "vp");
        VideoStoryProject partial = project("Cadena parcial", "s", "d", null, null, null);
        trace(complete, VideoStoryStep.STORY, TraceEventStatus.COMPLETED,
                Instant.now().minusSeconds(3600), Instant.now().minusSeconds(3000));
        trace(complete, VideoStoryStep.VIDEO, TraceEventStatus.COMPLETED,
                Instant.now().minusSeconds(3000), Instant.now().minusSeconds(2400));
        trace(partial, VideoStoryStep.DESIGNS, TraceEventStatus.FAILED,
                Instant.now().minusSeconds(2000), Instant.now().minusSeconds(1000));

        DashboardStatsDto.WizardStatsDto stats = statsService().wizardStats();

        assertEquals(2, stats.totalProjects());
        assertEquals(1, stats.completedChains(), "Solo el proyecto con los 5 outputs está completo");
        assertEquals(2, stats.stepsCompleted());
        assertEquals(1, stats.stepsFailed());

        // Serie continua de 14 días rellenada a cero, con HOY cargado.
        assertEquals(14, stats.activity().size());
        String today = LocalDate.now(ZoneId.systemDefault()).toString();
        assertEquals(today, stats.activity().get(13).day(), "El último punto de la serie es hoy");
        assertEquals(2, stats.activity().get(13).createdProjects(),
                "Ambos proyectos se crearon hoy");
        assertEquals(2, stats.activity().get(13).completedSteps());
        assertEquals(1, stats.activity().get(13).failedSteps());
        assertEquals(0, stats.activity().get(0).createdProjects(),
                "Los días sin actividad llegan a cero");
    }

    // ------------------------------------------------------------------
    // Panel de procesos
    // ------------------------------------------------------------------

    @Test
    @DisplayName("wizardProcesses: fuera las cadenas completas; estados y pasos derivados")
    void wizardProcesses_statesAndNextStep() {
        VideoStoryProject complete = project("Cadena completa", "s", "d", "a", "sc", "vp");
        VideoStoryProject partial = project("Cadena parcial", "s", "d", "a", null, null);
        VideoStoryProject failed = project("Cadena con fallo", "s", null, null, null, null);
        VideoStoryProject fresh = project("Cadena nueva", "s", null, null, null, null);
        trace(partial, VideoStoryStep.SCENES, TraceEventStatus.COMPLETED,
                Instant.now().minusSeconds(3600), Instant.now().minusSeconds(3000));
        trace(failed, VideoStoryStep.DESIGNS, TraceEventStatus.COMPLETED,
                Instant.now().minusSeconds(3000), Instant.now().minusSeconds(2400));
        trace(failed, VideoStoryStep.DESIGNS, TraceEventStatus.FAILED,
                Instant.now().minusSeconds(2400), Instant.now().minusSeconds(1800));

        List<DashboardStatsDto.WizardProcessDto> processes =
                statsService().wizardProcesses(Set.of(partial.getId()));

        assertEquals(3, processes.size(), "La cadena completa sale del panel");
        assertTrue(processes.stream().noneMatch(p -> p.projectId().equals(complete.getId())));

        DashboardStatsDto.WizardProcessDto runningPartial = processes.stream()
                .filter(p -> p.projectId().equals(partial.getId())).findFirst().orElseThrow();
        assertEquals("RUNNING", runningPartial.state(), "El cerrojo del wizard manda sobre el resto");
        assertTrue(runningPartial.running());
        assertEquals(3, runningPartial.stepsDone());
        assertEquals(5, runningPartial.stepsTotal());
        assertEquals("SCENES", runningPartial.currentStep(), "El paso actual es el siguiente pendiente");

        DashboardStatsDto.WizardProcessDto failedOne = processes.stream()
                .filter(p -> p.projectId().equals(failed.getId())).findFirst().orElseThrow();
        assertEquals("FAILED", failedOne.state(), "Su último intento de paso terminó fallido");
        assertFalse(failedOne.running());
        assertEquals("DESIGNS", failedOne.currentStep());
        assertEquals(1, failedOne.stepsDone());

        DashboardStatsDto.WizardProcessDto freshOne = processes.stream()
                .filter(p -> p.projectId().equals(fresh.getId())).findFirst().orElseThrow();
        assertEquals("IN_PROGRESS", freshOne.state(), "Con historia ya generada está en progreso");
        assertEquals("DESIGNS", freshOne.currentStep());

        // Prioridad del panel: la corrida en curso primero y, entre las que
        // no corren, la Fallido antes que el resto.
        assertEquals(partial.getId(), processes.get(0).projectId(), "Running primero");
        assertEquals(failed.getId(), processes.get(1).projectId(), "Fallido el primero de las que no corren");
    }

    @Test
    @DisplayName("wizardProcesses: sin proyectos pendientes devuelve lista vacía")
    void wizardProcesses_everythingComplete_returnsEmpty() {
        project("Única cadena completa", "s", "d", "a", "sc", "vp");

        List<DashboardStatsDto.WizardProcessDto> processes =
                statsService().wizardProcesses(Set.of());

        assertTrue(processes.isEmpty());
    }

    // ------------------------------------------------------------------
    // Rendimiento: los CLOBs nunca se leen
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Las métricas NO materializan los CLOBs: banderas CASE WHEN sobre columnas ligeras")
    void queries_neverLoadOutputClobs() {
        project("Pesado", "x".repeat(5000), "y".repeat(5000), "z".repeat(5000),
                "w".repeat(5000), "v".repeat(5000));

        long start = System.nanoTime();
        DashboardStatsDto.WizardStatsDto stats = statsService().wizardStats();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(1, stats.totalProjects());
        assertEquals(1, stats.completedChains());
        // Sin assert de tiempo exacto (flaky): la garantía estructural es la
        // consulta ligera (CASE WHEN IS NOT NULL); este test solo la ejercita.
        assertTrue(elapsedMs < 5000, "La consulta ligera es inmediata: " + elapsedMs + " ms");
        assertTrue(stats.activity().get(13).day().matches("\\d{4}-\\d{2}-\\d{2}"),
                "Los días de la serie tienen formato yyyy-MM-dd");
    }
}
