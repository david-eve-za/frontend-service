package com.glez.frontendservice.services;

import com.glez.frontendservice.dtos.DashboardStatsDto;
import com.glez.frontendservice.model.TraceEventStatus;
import com.glez.frontendservice.model.VideoStoryStep;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Métricas del Asistente de Historias para el panel de control: contadores
 * de la cadena por proyecto, serie de actividad de 14 días (proyectos
 * creados + pasos completados/fallidos) y la vista de procesos (proyectos
 * con la cadena incompleta y su estado derivado).
 *
 * <p>Consultas NATIVAS ligeras: los outputs del asistente son CLOBs
 * enormes (historia, diseños, assets, escenas, plantilla MASTER) y jamás
 * se proyectan — solo {@code CASE WHEN x IS NOT NULL} para las banderas de
 * progreso, el mismo patrón ligero del resto del panel. Sin cache: las
 * tablas del asistente son diminutas junto al catálogo y el panel sondea
 * cada 15 s.</p>
 */
@Service
@RequiredArgsConstructor
public class VideoStoryStatsService {

    private static final Logger log = LoggerFactory.getLogger(VideoStoryStatsService.class);

    public static final int ACTIVITY_WINDOW_DAYS = 14;

    /** Pasos totales de la cadena del asistente. */
    private static final int STEPS_TOTAL = 5;

    private final JdbcTemplate jdbcTemplate;
    private final VideoStoryTraceService traceService;

    /** Fila ligera de un proyecto: banderas de progreso sin tocar los CLOBs. */
    private record ProjectRow(
            UUID id,
            String title,
            Instant createdAt,
            Instant updatedAt,
            boolean hasStory,
            boolean hasDesigns,
            boolean hasAssets,
            boolean hasScenes,
            boolean hasVideo) {

        private int stepsDone() {
            return (hasStory ? 1 : 0) + (hasDesigns ? 1 : 0) + (hasAssets ? 1 : 0)
                    + (hasScenes ? 1 : 0) + (hasVideo ? 1 : 0);
        }

        private boolean isComplete() {
            return stepsDone() == STEPS_TOTAL;
        }

        /** Primer paso pendiente de la cadena (el "paso actual" del panel). */
        private String nextStep() {
            if (!hasStory) {
                return VideoStoryStep.STORY.name();
            }
            if (!hasDesigns) {
                return VideoStoryStep.DESIGNS.name();
            }
            if (!hasAssets) {
                return VideoStoryStep.ASSETS.name();
            }
            if (!hasScenes) {
                return VideoStoryStep.SCENES.name();
            }
            return VideoStoryStep.VIDEO.name();
        }
    }

    /** Métricas del asistente para el gráfico del panel de estado. */
    @Transactional(readOnly = true)
    public DashboardStatsDto.WizardStatsDto wizardStats() {
        try {
            List<ProjectRow> projects = loadProjects();
            long completedChains = projects.stream().filter(ProjectRow::isComplete).count();

            Map<String, Long> byStatus = new HashMap<>();
            jdbcTemplate.query(
                    "SELECT STATUS, COUNT(*) AS TOTAL FROM VIDEO_STORY_TRACE_EVENT GROUP BY STATUS",
                    rs -> {
                        byStatus.put(rs.getString("STATUS"), rs.getLong("TOTAL"));
                    });

            return new DashboardStatsDto.WizardStatsDto(
                    projects.size(),
                    completedChains,
                    byStatus.getOrDefault(TraceEventStatus.COMPLETED.name(), 0L),
                    byStatus.getOrDefault(TraceEventStatus.FAILED.name(), 0L),
                    activitySeries());
        } catch (Exception e) {
            // Un panel de monitoreo debe degradarse, no fallar.
            log.error("Could not compute wizard stats", e);
            return new DashboardStatsDto.WizardStatsDto(0, 0, 0, 0, List.of());
        }
    }

    /**
     * Procesos del asistente para el panel: proyectos con la cadena
     * INCOMPLETA (los completos salen, igual que los libros COMPLETED del
     * panel) con su estado derivado — RUNNING (paso en curso en esta
     * instancia), FAILED (su último intento de paso terminó fallido),
     * IN_PROGRESS (ya tiene outputs) o NEW (solo existe el proyecto).
     */
    @Transactional(readOnly = true)
    public List<DashboardStatsDto.WizardProcessDto> wizardProcesses(Set<UUID> runningIds) {
        try {
            List<ProjectRow> pending = loadProjects().stream()
                    .filter(p -> !p.isComplete())
                    .toList();
            if (pending.isEmpty()) {
                return List.of();
            }
            Map<UUID, TraceEventStatus> lastStatuses = traceService.lastStatuses(
                    pending.stream().map(ProjectRow::id).toList());

            return pending.stream()
                    .map(p -> new DashboardStatsDto.WizardProcessDto(
                            p.id(),
                            p.title(),
                            stateOf(p, runningIds, lastStatuses),
                            p.nextStep(),
                            p.stepsDone(),
                            STEPS_TOTAL,
                            runningIds.contains(p.id()),
                            p.updatedAt() != null ? p.updatedAt() : p.createdAt()))
                    .sorted(Comparator
                            .comparing((DashboardStatsDto.WizardProcessDto p) -> runningIds.contains(p.projectId()) ? 0 : 1)
                            .thenComparing(p -> "FAILED".equals(p.state()) ? 0 : 1)
                            .thenComparing(DashboardStatsDto.WizardProcessDto::updatedAt,
                                    Comparator.reverseOrder()))
                    .toList();
        } catch (Exception e) {
            log.error("Could not compute wizard processes", e);
            return List.of();
        }
    }

    /** Estado del proceso del proyecto: la corrida en memoria manda. */
    private static String stateOf(ProjectRow project, Set<UUID> runningIds,
            Map<UUID, TraceEventStatus> lastStatuses) {
        if (runningIds.contains(project.id())) {
            return "RUNNING";
        }
        if (lastStatuses.get(project.id()) == TraceEventStatus.FAILED) {
            return "FAILED";
        }
        return project.stepsDone() > 0 ? "IN_PROGRESS" : "NEW";
    }

    /**
     * Actividad del asistente por día (proyectos creados + pasos
     * completados/fallidos) en la ventana reciente, con los días sin
     * actividad rellenados a cero — GROUP BY FORMATDATETIME nativo (H2),
     * el mismo patrón del gráfico del catálogo.
     */
    private List<DashboardStatsDto.WizardActivityPointDto> activitySeries() {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalDate firstDay = today.minusDays(ACTIVITY_WINDOW_DAYS - 1L);
        Timestamp cutoff = Timestamp.from(firstDay.atStartOfDay(zone).toInstant());

        // day ("yyyy-MM-dd") -> [creados, completados, fallidos]
        //
        // CAST(x AS TIMESTAMP) es OBLIGATORIO: las columnas son TIMESTAMP
        // WITH TIME ZONE y FORMATDATETIME las renderiza en UTC — sin el
        // cast, los conteos caían en el día UTC y la serie (etiquetada con
        // LocalDate de la zona local) los perdía entre las 19:00-24:00
        // locales (bug destapado por el test de la serie a esa hora).
        Map<String, long[]> byDay = new HashMap<>();
        jdbcTemplate.query(
                "SELECT FORMATDATETIME(CAST(created_at AS TIMESTAMP), 'yyyy-MM-dd') AS day_key, COUNT(*) AS TOTAL "
                        + "FROM VIDEO_STORY_PROJECTS WHERE created_at >= ? "
                        + "GROUP BY FORMATDATETIME(CAST(created_at AS TIMESTAMP), 'yyyy-MM-dd')",
                rs -> {
                    byDay.computeIfAbsent(rs.getString("day_key"), key -> new long[3])[0]
                            += rs.getLong("TOTAL");
                },
                cutoff);
        jdbcTemplate.query(
                "SELECT FORMATDATETIME(CAST(finished_at AS TIMESTAMP), 'yyyy-MM-dd') AS day_key, STATUS, COUNT(*) AS TOTAL "
                        + "FROM VIDEO_STORY_TRACE_EVENT WHERE finished_at >= ? "
                        + "GROUP BY FORMATDATETIME(CAST(finished_at AS TIMESTAMP), 'yyyy-MM-dd'), STATUS",
                rs -> {
                    long[] counts = byDay.computeIfAbsent(rs.getString("day_key"), key -> new long[3]);
                    if (TraceEventStatus.COMPLETED.name().equals(rs.getString("STATUS"))) {
                        counts[1] += rs.getLong("TOTAL");
                    } else if (TraceEventStatus.FAILED.name().equals(rs.getString("STATUS"))) {
                        counts[2] += rs.getLong("TOTAL");
                    }
                },
                cutoff);

        List<DashboardStatsDto.WizardActivityPointDto> series = new ArrayList<>(ACTIVITY_WINDOW_DAYS);
        for (int i = 0; i < ACTIVITY_WINDOW_DAYS; i++) {
            long[] counts = byDay.get(firstDay.plusDays(i).toString());
            series.add(new DashboardStatsDto.WizardActivityPointDto(
                    firstDay.plusDays(i).toString(),
                    counts != null ? counts[0] : 0,
                    counts != null ? counts[1] : 0,
                    counts != null ? counts[2] : 0));
        }
        return series;
    }

    /** Todos los proyectos en filas ligeras: los CLOBs de outputs nunca se leen. */
    private List<ProjectRow> loadProjects() {
        String sql = """
                SELECT ID, TITLE, CREATED_AT, UPDATED_AT,
                       CASE WHEN STORY IS NOT NULL THEN 1 ELSE 0 END AS HAS_STORY,
                       CASE WHEN DESIGNS IS NOT NULL THEN 1 ELSE 0 END AS HAS_DESIGNS,
                       CASE WHEN ASSETS IS NOT NULL THEN 1 ELSE 0 END AS HAS_ASSETS,
                       CASE WHEN SCENES IS NOT NULL THEN 1 ELSE 0 END AS HAS_SCENES,
                       CASE WHEN VIDEO_PROMPT IS NOT NULL THEN 1 ELSE 0 END AS HAS_VIDEO
                FROM VIDEO_STORY_PROJECTS
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new ProjectRow(
                UUID.fromString(rs.getString("ID")),
                rs.getString("TITLE"),
                rs.getTimestamp("CREATED_AT") != null ? rs.getTimestamp("CREATED_AT").toInstant() : null,
                rs.getTimestamp("UPDATED_AT") != null ? rs.getTimestamp("UPDATED_AT").toInstant() : null,
                rs.getInt("HAS_STORY") == 1,
                rs.getInt("HAS_DESIGNS") == 1,
                rs.getInt("HAS_ASSETS") == 1,
                rs.getInt("HAS_SCENES") == 1,
                rs.getInt("HAS_VIDEO") == 1));
    }
}
