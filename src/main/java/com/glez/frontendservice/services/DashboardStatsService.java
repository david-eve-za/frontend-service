package com.glez.frontendservice.services;

import com.glez.frontendservice.dtos.DashboardStatsDto;
import com.glez.frontendservice.dtos.DashboardStatsDto.BookProcessDto;
import com.glez.frontendservice.dtos.DashboardStatsDto.BookStatsDto;
import com.glez.frontendservice.dtos.DashboardStatsDto.ChunkStatsDto;
import com.glez.frontendservice.dtos.DashboardStatsDto.DatabaseStatusDto;
import com.glez.frontendservice.dtos.DashboardStatsDto.ProcessStatusDto;
import com.glez.frontendservice.dtos.DashboardStatsDto.RunningProcessDto;
import com.glez.frontendservice.dtos.NovelsCatalogSyncStatus;
import com.glez.frontendservice.model.ProcessingStatus;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Agrega en una sola llamada todas las métricas del panel de control.
 * Los contadores por libro salen de una única consulta nativa agrupada
 * (BOOK LEFT JOIN CHUNKS) para no cargar los LOB de texto completo.
 */
@Service
@RequiredArgsConstructor
public class DashboardStatsService {

    private static final Logger log = LoggerFactory.getLogger(DashboardStatsService.class);

    private final JdbcTemplate jdbcTemplate;
    private final BookPipelineOrchestrator pipelineOrchestrator;
    private final NovelsCatalogSyncService catalogSyncService;
    private final CatalogStatsService catalogStatsService;
    private final VideoStoryStatsService videoStoryStatsService;
    private final VideoStoryWizardService videoStoryWizardService;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    /** Fila del agregado por libro: totales de chunks y estado de audio. */
    private record BookAggregates(
            UUID id,
            String name,
            String status,
            String currentStep,
            String audioFilePath,
            long totalChunks,
            long completedChunks) {

        private boolean isSplit() {
            return totalChunks > 0;
        }

        private boolean isTranslated() {
            return totalChunks > 0 && totalChunks == completedChunks;
        }
    }

    public DashboardStatsDto getStats() {
        try {
            List<BookAggregates> books = loadBookAggregates();
            return new DashboardStatsDto(
                    databaseStatus(),
                    processStatus(books),
                    bookStats(books),
                    chunkStats(books),
                    // Cached counts (Caffeine "catalogCounts"): the dashboard
                    // polls every 15s; the sync evicts them when it finishes.
                    catalogStatsService.catalogStats(),
                    // Asistente de Historias: métricas ligeras sin cache
                    // (tablas diminutas junto al catálogo).
                    videoStoryStatsService.wizardStats());
        } catch (Exception e) {
            // Un panel de monitoreo debe degradarse, no fallar: se reporta la
            // caída con métricas vacías para que la UI siempre pueda renderizar.
            log.error("Could not compute dashboard stats", e);
            return degradedStats(e.getMessage());
        }
    }

    /** Respuesta de degradación cuando la base de datos no responde. */
    private DashboardStatsDto degradedStats(String error) {
        // Los DTOs anidados se materializan en variables locales: resolver el
        // import del tipo anidado dentro de los argumentos del constructor del
        // record contenedor dispara una circularidad de atribución en javac.
        DatabaseStatusDto database = new DatabaseStatusDto("DOWN", null, null, datasourceUrl, null, error);
        ProcessStatusDto processes = new ProcessStatusDto(List.of(), catalogSyncService.getStatus(),
                List.of(), List.of());
        BookStatsDto books = new BookStatsDto(0, 0, 0, 0, 0, Map.of());
        ChunkStatsDto chunks = new ChunkStatsDto(0, 0);
        DashboardStatsDto.CatalogStatsDto catalog = new DashboardStatsDto.CatalogStatsDto(0, 0, List.of());
        DashboardStatsDto.WizardStatsDto wizard = new DashboardStatsDto.WizardStatsDto(0, 0, 0, 0, List.of());
        return new DashboardStatsDto(database, processes, books, chunks, catalog, wizard);
    }

    /**
     * Agregado por libro en una sola consulta nativa. Solo toca columnas
     * ligeras: los LOB (full_text, original_text, translated_text) nunca
     * se proyectan.
     */
    private List<BookAggregates> loadBookAggregates() {
        String sql = """
                SELECT b.ID, b.NAME, b.STATUS, b.CURRENT_STEP, b.AUDIO_FILE_PATH,
                       COUNT(c.ID) AS TOTAL_CHUNKS,
                       COALESCE(SUM(CASE WHEN c.STATUS = 'COMPLETED' THEN 1 ELSE 0 END), 0) AS COMPLETED_CHUNKS
                FROM BOOK b
                LEFT JOIN CHUNKS c ON c.BOOK_ID = b.ID
                GROUP BY b.ID, b.NAME, b.STATUS, b.CURRENT_STEP, b.AUDIO_FILE_PATH
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new BookAggregates(
                UUID.fromString(rs.getString("ID")),
                rs.getString("NAME"),
                rs.getString("STATUS"),
                rs.getString("CURRENT_STEP"),
                rs.getString("AUDIO_FILE_PATH"),
                rs.getLong("TOTAL_CHUNKS"),
                rs.getLong("COMPLETED_CHUNKS")));
    }

    /**
     * Estado de la base de datos: la conexión (y sus metadatos) actúa como
     * health check; el tamaño en disco se deriva de la URL jdbc:h2:file:.
     */
    private DatabaseStatusDto databaseStatus() {
        try (Connection connection = Objects.requireNonNull(jdbcTemplate.getDataSource()).getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            return new DatabaseStatusDto(
                    "UP",
                    metadata.getDatabaseProductName(),
                    metadata.getDatabaseProductVersion(),
                    datasourceUrl,
                    databaseFileSizeBytes(),
                    null);
        } catch (Exception e) {
            log.error("Database health check failed", e);
            return new DatabaseStatusDto(
                    "DOWN",
                    null,
                    null,
                    datasourceUrl,
                    null,
                    e.getMessage());
        }
    }

    /** Tamaño del archivo .mv.db cuando la URL es jdbc:h2:file:<path>; null si no existe. */
    private Long databaseFileSizeBytes() {
        if (datasourceUrl == null || !datasourceUrl.startsWith("jdbc:h2:file:")) {
            return null;
        }
        Path dbFile = Path.of(datasourceUrl.substring("jdbc:h2:file:".length()) + ".mv.db");
        try {
            return Files.exists(dbFile) ? Files.size(dbFile) : null;
        } catch (Exception e) {
            log.warn("Could not measure database file size for {}", dbFile, e);
            return null;
        }
    }

    private ProcessStatusDto processStatus(List<BookAggregates> books) {
        Set<UUID> runningIds = pipelineOrchestrator.getRunningBookIds();
        Map<UUID, BookAggregates> byId = new HashMap<>();
        books.forEach(book -> byId.put(book.id(), book));

        List<RunningProcessDto> running = runningIds.stream()
                .map(id -> {
                    BookAggregates book = byId.get(id);
                    return new RunningProcessDto(
                            id,
                            book != null ? book.name() : null,
                            book != null ? book.status() : null,
                            book != null ? book.currentStep() : null);
                })
                .toList();

        // Vista de "procesos" para el panel: todo libro con ciclo de vida
        // activo (COMPLETED fuera), las corridas reales primero y luego
        // por prioridad de estado. Sale del MISMO agregado: cero queries
        // extra por poll.
        List<BookProcessDto> bookProcesses = books.stream()
                .filter(book -> !ProcessingStatus.COMPLETED.name().equals(book.status()))
                .sorted(Comparator
                        .comparing((BookAggregates book) -> runningIds.contains(book.id()) ? 0 : 1)
                        .thenComparing(book -> statusPriority(book.status()))
                        .thenComparing(book -> book.name() != null ? book.name() : ""))
                .map(book -> new BookProcessDto(
                        book.id(),
                        book.name(),
                        book.status(),
                        book.currentStep(),
                        runningIds.contains(book.id())))
                .toList();

        NovelsCatalogSyncStatus sync = catalogSyncService.getStatus();
        // Procesos del Asistente de Historias: proyectos con la cadena
        // incompleta, con el cerrojo del wizard como fuente de "running".
        List<DashboardStatsDto.WizardProcessDto> wizardProcesses =
                videoStoryStatsService.wizardProcesses(videoStoryWizardService.getRunningProjectIds());
        return new ProcessStatusDto(running, sync, bookProcesses, wizardProcesses);
    }

    /** Orden estable de estados para la lista de procesos del panel. */
    private static int statusPriority(String status) {
        if (status == null) {
            return 9;
        }
        return switch (status) {
            case "PROCESSING" -> 0; // Running
            case "AWAITING" -> 1;   // Idle (checkpoint alcanzado)
            case "STOPPED" -> 2;    // Detenido por el usuario
            case "UPLOADED" -> 3;   // Idle (nunca arrancado)
            case "FAILED" -> 4;     // Reintentable
            default -> 9;
        };
    }

    /**
     * Contadores por etapa del pipeline. Las semánticas replican
     * NovelsCatalogQueryService para que el panel y el gestor de novelas
     * siempre coincidan.
     */
    private BookStatsDto bookStats(List<BookAggregates> books) {
        long split = books.stream().filter(BookAggregates::isSplit).count();
        long translated = books.stream().filter(BookAggregates::isTranslated).count();
        long audioCreated = books.stream().filter(book -> book.audioFilePath() != null).count();
        long readyForAudio = books.stream()
                .filter(BookAggregates::isTranslated)
                .filter(book -> book.audioFilePath() == null)
                .count();

        Map<String, Long> byStatus = new HashMap<>();
        for (ProcessingStatus status : ProcessingStatus.values()) {
            byStatus.put(status.name(), 0L);
        }
        for (BookAggregates book : books) {
            if (book.status() != null) {
                byStatus.merge(book.status(), 1L, Long::sum);
            }
        }

        return new BookStatsDto(books.size(), split, translated, audioCreated, readyForAudio, byStatus);
    }

    private ChunkStatsDto chunkStats(List<BookAggregates> books) {
        long total = books.stream().mapToLong(BookAggregates::totalChunks).sum();
        long completed = books.stream().mapToLong(BookAggregates::completedChunks).sum();
        return new ChunkStatsDto(total, completed);
    }
}
