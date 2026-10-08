package com.glez.frontendservice.services;

import com.glez.frontendservice.dtos.DashboardStatsDto;
import com.glez.frontendservice.dtos.NovelsCatalogSyncStatus;
import com.glez.frontendservice.model.ProcessStep;
import com.glez.frontendservice.model.ProcessingStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test de integración ligero: la agregación nativa corre contra una H2 en
 * memoria con el esquema mínimo de BOOK/CHUNKS, de modo que también se
 * valida la SQL real del panel (joins, alias y casings de H2).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DashboardStatsService Tests")
class DashboardStatsServiceTest {

    private static final String JDBC_URL = "jdbc:h2:mem:dashboard-stats-test;DB_CLOSE_DELAY=-1";

    @Mock
    private BookPipelineOrchestrator pipelineOrchestrator;

    @Mock
    private NovelsCatalogSyncService catalogSyncService;

    @Mock
    private CatalogStatsService catalogStatsService;

    private JdbcTemplate jdbcTemplate;
    private DashboardStatsService service;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(JDBC_URL, "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP ALL OBJECTS");
        jdbcTemplate.execute("""
                CREATE TABLE book (
                    id VARCHAR(36) PRIMARY KEY,
                    name VARCHAR(255),
                    status VARCHAR(32),
                    current_step VARCHAR(32),
                    audio_file_path VARCHAR(512),
                    full_text TEXT
                )""");
        jdbcTemplate.execute("""
                CREATE TABLE chunks (
                    id VARCHAR(36) PRIMARY KEY,
                    book_id VARCHAR(36),
                    original_text TEXT,
                    translated_text TEXT,
                    status VARCHAR(32),
                    position INT,
                    attempts INT
                )""");

        service = new DashboardStatsService(jdbcTemplate, pipelineOrchestrator, catalogSyncService,
                catalogStatsService);
        setField("datasourceUrl", JDBC_URL);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP ALL OBJECTS");
    }

    private void setField(String name, Object value) throws Exception {
        Field field = service.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }

    private void insertBook(UUID id, String name, String status, String step, String audioPath) {
        jdbcTemplate.update("INSERT INTO book (id, name, status, current_step, audio_file_path) VALUES (?, ?, ?, ?, ?)",
                id.toString(), name, status, step, audioPath);
    }

    private void insertChunks(UUID bookId, int completed, int pending) {
        for (int i = 0; i < completed; i++) {
            jdbcTemplate.update("INSERT INTO chunks (id, book_id, status, position, attempts) VALUES (?, ?, ?, ?, 0)",
                    UUID.randomUUID().toString(), bookId.toString(), "COMPLETED", i);
        }
        for (int i = 0; i < pending; i++) {
            jdbcTemplate.update("INSERT INTO chunks (id, book_id, status, position, attempts) VALUES (?, ?, ?, ?, 0)",
                    UUID.randomUUID().toString(), bookId.toString(), "AWAITING", completed + i);
        }
    }

    @Test
    @DisplayName("getStats agrega etapas del pipeline, estados y chunks en una sola llamada")
    void aggregatesPipelineStagesStatusesAndChunks() {
        // Libro 1: 10/10 completados, sin audio → split + traducido + listo p/ audio
        UUID book1 = UUID.randomUUID();
        // Libro 2: 2/5 completados, sin audio → split, no traducido
        UUID book2 = UUID.randomUUID();
        // Libro 3: 4/4 completados, con audio → split + traducido + audio
        UUID book3 = UUID.randomUUID();
        insertBook(book1, "libro-1", "FAILED", "TRANSLATE", null);
        insertBook(book2, "libro-2", "PROCESSING", "TRANSLATE", null);
        insertBook(book3, "libro-3", "COMPLETED", "FINALIZE", "/tmp/audio3.mp3");
        insertChunks(book1, 10, 0);
        insertChunks(book2, 2, 3);
        insertChunks(book3, 4, 0);

        when(pipelineOrchestrator.getRunningBookIds()).thenReturn(Set.of(book2));
        when(catalogSyncService.getStatus()).thenReturn(NovelsCatalogSyncStatus.idle());
        when(catalogStatsService.catalogStats()).thenReturn(
                new DashboardStatsDto.CatalogStatsDto(7L, 19L));

        DashboardStatsDto stats = service.getStats();

        assertEquals(3, stats.books().total());
        assertEquals(3, stats.books().split());
        assertEquals(2, stats.books().translated());
        assertEquals(1, stats.books().audioCreated());
        assertEquals(1, stats.books().readyForAudio());
        assertEquals(1, stats.books().byStatus().get("FAILED"));
        assertEquals(1, stats.books().byStatus().get("PROCESSING"));
        assertEquals(1, stats.books().byStatus().get("COMPLETED"));
        assertEquals(0, stats.books().byStatus().get("UPLOADED"));
        assertEquals(19, stats.chunks().total());
        assertEquals(16, stats.chunks().completed());
        assertEquals(7, stats.catalog().novels());
        assertEquals(19, stats.catalog().volumes());

        assertEquals(1, stats.processes().running().size());
        assertEquals("libro-2", stats.processes().running().get(0).name());
        assertEquals(ProcessingStatus.PROCESSING.name(), stats.processes().running().get(0).status());
        assertEquals(ProcessStep.TRANSLATE.name(), stats.processes().running().get(0).currentStep());

        assertEquals("UP", stats.database().status());
        assertEquals("H2", stats.database().product());
        assertNull(stats.database().error());
    }

    @Test
    @DisplayName("La caída de la base de datos se reporta como DOWN con el error")
    void databaseDownIsReported() throws Exception {
        DataSource failingDataSource = mock(DataSource.class);
        when(failingDataSource.getConnection()).thenThrow(new RuntimeException("connection refused"));
        service = new DashboardStatsService(new JdbcTemplate(failingDataSource), pipelineOrchestrator,
                catalogSyncService, catalogStatsService);
        setField("datasourceUrl", JDBC_URL);
        when(catalogSyncService.getStatus()).thenReturn(NovelsCatalogSyncStatus.idle());

        DashboardStatsDto stats = service.getStats();

        assertEquals("DOWN", stats.database().status());
        assertEquals("connection refused", stats.database().error());
        assertNull(stats.database().product());
        // El resto del panel sigue funcionando aunque la BD caiga a mitad de llamada.
        assertEquals(0, stats.books().total());
        assertTrue(stats.processes().running().isEmpty());
    }

    @Test
    @DisplayName("Sin libros el panel devuelve ceros y ningún proceso activo")
    void emptyDatabaseYieldsZeros() {
        when(pipelineOrchestrator.getRunningBookIds()).thenReturn(Set.of());
        when(catalogSyncService.getStatus()).thenReturn(NovelsCatalogSyncStatus.idle());
        when(catalogStatsService.catalogStats()).thenReturn(
                new DashboardStatsDto.CatalogStatsDto(0L, 0L));

        DashboardStatsDto stats = service.getStats();

        assertEquals("UP", stats.database().status());
        assertEquals(0, stats.books().total());
        assertEquals(0, stats.books().split());
        assertEquals(0, stats.chunks().total());
        assertTrue(stats.processes().running().isEmpty());
        assertEquals(NovelsCatalogSyncStatus.State.IDLE, stats.processes().catalogSync().state());
    }
}
