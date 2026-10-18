package com.glez.frontendservice.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Validación real contra H2 de la migración del enum de
 * PROCESS_TRACE_EVENT.STATUS. hbm2ddl mapea @Enumerated(STRING) al ENUM
 * NATIVO de H2, así que la columna lleva los valores que TraceEventStatus
 * tenía al nacer la tabla — añadir CANCELLED (pausas y stops del panel) no
 * lo actualizó, y cada cancelStep reventaba al persistir en el H2 file de
 * larga vida ("Valor no permitido para la columna ... 'CANCELLED'"),
 * degradando la pausa en un FAILED.
 *
 * <p>Dos formas de esquema se prueban: la REAL (columna ENUM nativa,
 * re-declarada con el enum completo) y una vintage alternativa (CHECK
 * inline, que se ELIMINA sin reemplazar — H2 2.4.240, la versión del
 * runtime, tiene una regresión multi-sesión donde un CHECK re-creado tras
 * el drop se evalúa con la expresión VIEJA en otras conexiones; verificado
 * con probe standalone: el mismo flujo funciona en 2.3.232 y en sesión
 * única).</p>
 */
@DisplayName("DatabaseIndexInitializer: migración del enum de estado de traza")
class DatabaseIndexInitializerTest {

    private JdbcTemplate jdbcTemplate;
    private DatabaseIndexInitializer initializer;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:trace-status-check-test;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP ALL OBJECTS");
        initializer = new DatabaseIndexInitializer(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP ALL OBJECTS");
    }

    /** La forma REAL del esquema de producción: columna ENUM nativa con los 4 valores legados. */
    private void createTraceTableWithLegacyEnumColumn() {
        jdbcTemplate.execute("""
                CREATE TABLE PROCESS_TRACE_EVENT (
                    id VARCHAR(36) PRIMARY KEY,
                    status ENUM('STARTED', 'COMPLETED', 'FAILED', 'SKIPPED') NOT NULL
                )
                """);
    }

    /** Vintage alternativa: CHECK inline en una columna VARCHAR. */
    private void createTraceTableWithLegacyCheck() {
        jdbcTemplate.execute("""
                CREATE TABLE PROCESS_TRACE_EVENT (
                    id VARCHAR(36) PRIMARY KEY,
                    status VARCHAR(32) NOT NULL
                        CHECK (status IN ('STARTED', 'COMPLETED', 'FAILED', 'SKIPPED'))
                )
                """);
    }

    private long countCheckConstraints() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA = 'PUBLIC' AND TABLE_NAME = 'PROCESS_TRACE_EVENT' "
                        + "AND CONSTRAINT_TYPE = 'CHECK'",
                Long.class);
        return count != null ? count : 0L;
    }

    @Test
    @DisplayName("Forma real (ENUM nativo): el initializer re-declara la columna y CANCELLED persiste")
    void ensureTraceStatusEnum_migratesLegacyEnumColumn() {
        createTraceTableWithLegacyEnumColumn();

        // La situación del bug: CANCELLED rebotaba contra el ENUM legado.
        assertThrows(Exception.class, () -> jdbcTemplate.execute(
                "INSERT INTO PROCESS_TRACE_EVENT (id, status) VALUES ('1', 'CANCELLED')"));

        initializer.ensureTraceStatusEnum();

        assertDoesNotThrow(() -> jdbcTemplate.execute(
                "INSERT INTO PROCESS_TRACE_EVENT (id, status) VALUES ('1', 'CANCELLED')"),
                "Después de re-declarar el ENUM, cancelStep debe poder persistir CANCELLED");
        // Los valores históricos siguen válidos y los datos se conservan.
        assertDoesNotThrow(() -> jdbcTemplate.execute(
                "INSERT INTO PROCESS_TRACE_EVENT (id, status) VALUES ('2', 'STARTED')"));
        Long rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM PROCESS_TRACE_EVENT", Long.class);
        assertEquals(2L, rows, "La migración conserva las filas existentes");
    }

    @Test
    @DisplayName("Forma real: idempotente — la segunda corrida mantiene el enum completo")
    void ensureTraceStatusEnum_enumColumn_isIdempotent() {
        createTraceTableWithLegacyEnumColumn();
        initializer.ensureTraceStatusEnum();
        initializer.ensureTraceStatusEnum();

        assertDoesNotThrow(() -> jdbcTemplate.execute(
                "INSERT INTO PROCESS_TRACE_EVENT (id, status) VALUES ('1', 'CANCELLED')"),
                "Tras dos corridas el ENUM sigue aceptando CANCELLED");
    }

    @Test
    @DisplayName("Vintage CHECK inline: el constraint legado se ELIMINA (sin reemplazo: regresión H2 2.4)")
    void ensureTraceStatusEnum_removesLegacyCheck() {
        createTraceTableWithLegacyCheck();

        assertThrows(Exception.class, () -> jdbcTemplate.execute(
                "INSERT INTO PROCESS_TRACE_EVENT (id, status) VALUES ('1', 'CANCELLED')"));

        initializer.ensureTraceStatusEnum();

        assertDoesNotThrow(() -> jdbcTemplate.execute(
                "INSERT INTO PROCESS_TRACE_EVENT (id, status) VALUES ('1', 'CANCELLED')"),
                "Sin el CHECK legado, cancelStep debe poder persistir CANCELLED");
        assertEquals(0, countCheckConstraints(),
                "El CHECK legado debe quedar ELIMINADO (no reemplazado: regresión multi-sesión de H2 2.4)");
    }

    @Test
    @DisplayName("Vintage CHECK: idempotente y la PRIMARY KEY sobrevive")
    void ensureTraceStatusEnum_check_isIdempotent() {
        createTraceTableWithLegacyCheck();
        initializer.ensureTraceStatusEnum();
        List<String> afterFirst = jdbcTemplate.queryForList(
                "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA = 'PUBLIC' AND TABLE_NAME = 'PROCESS_TRACE_EVENT' "
                        + "ORDER BY CONSTRAINT_NAME",
                String.class);

        initializer.ensureTraceStatusEnum();

        List<String> afterSecond = jdbcTemplate.queryForList(
                "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA = 'PUBLIC' AND TABLE_NAME = 'PROCESS_TRACE_EVENT' "
                        + "ORDER BY CONSTRAINT_NAME",
                String.class);
        assertEquals(afterFirst, afterSecond, "La segunda corrida no debe crear ni destruir constraints");
        assertEquals(1, afterSecond.size(), "Solo la PRIMARY KEY debe quedar");
    }
}
