package com.glez.frontendservice.config;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Creates the query-driven database indexes — and the column additions the
 * entity model needs — idempotently on startup.
 *
 * <p>They are declared on the entities via {@code @Table(indexes = ...)} /
 * {@code @Column} for fresh databases, but {@code hbm2ddl} update mode does
 * not reliably add NEW indexes to tables that already exist (like the
 * long-lived H2 file database this service ships with) and CANNOT add a
 * NOT NULL column to a populated table (no DEFAULT to backfill existing
 * rows), so this initializer guarantees them with {@code CREATE INDEX IF
 * NOT EXISTS} / {@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS}. Statements
 * are cheap, idempotent and named exactly like the entity declarations, so
 * both mechanisms converge on the same physical schema.</p>
 *
 * <p>Each entry documents the query pattern it serves, so nobody deletes
 * an "unused" index without checking {@code EXPLAIN ANALYZE} first.</p>
 */
@Component
@RequiredArgsConstructor
public class DatabaseIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(DatabaseIndexInitializer.class);

    /**
     * Column migrations for existing tables, in order. hbm2ddl-update cannot
     * apply these: a NOT NULL column on a populated table needs an explicit
     * DEFAULT to backfill, which the entity mapping cannot express.
     */
    private static final List<ColumnMigration> COLUMN_MIGRATIONS = List.of(
            new ColumnMigration(
                    "ALTER TABLE NOVEL ADD COLUMN IF NOT EXISTS LISTING_COMPLETE BOOLEAN DEFAULT FALSE NOT NULL",
                    "flag de carpeta completada en la pasada de sync (saltos en pasadas incrementales)"),
            new ColumnMigration(
                    "ALTER TABLE NOVEL ADD COLUMN IF NOT EXISTS LAST_LISTED_AT TIMESTAMP WITH TIME ZONE",
                    "momento del último listado completo de la carpeta de la obra"));

    /** (sql, purpose) pairs executed in order on startup. */
    private static final List<IndexDefinition> INDEXES = List.of(
            new IndexDefinition(
                    "CREATE INDEX IF NOT EXISTS idx_novel_title ON NOVEL (TITLE)",
                    "catalog listing: ORDER BY title pagination without table scan + sort"),
            new IndexDefinition(
                    "CREATE INDEX IF NOT EXISTS idx_blocks_book_position ON BLOCKS (BOOK_ID, POSITION)",
                    "blocks of a book in reading order (split/translate/audio steps)"),
            new IndexDefinition(
                    "CREATE INDEX IF NOT EXISTS idx_chunks_book_status ON CHUNKS (BOOK_ID, STATUS)",
                    "chunk counts and pending-chunk scans per book"),
            new IndexDefinition(
                    "CREATE INDEX IF NOT EXISTS idx_chunks_block_position ON CHUNKS (BLOCK_ID, POSITION)",
                    "chunks of a block in position order (per-block translation/audio)"),
            new IndexDefinition(
                    "CREATE INDEX IF NOT EXISTS idx_trace_book_step_started "
                            + "ON PROCESS_TRACE_EVENT (BOOK_ID, STEP, STARTED_AT)",
                    "genuine-failure attempt counts per book + step"));

    private final JdbcTemplate jdbcTemplate;

    /** Ordered before the startup pipeline recovery: cheap, once, idempotent. */
    @Order(0)
    @EventListener(ApplicationReadyEvent.class)
    public void createIndexes() {
        for (ColumnMigration migration : COLUMN_MIGRATIONS) {
            try {
                jdbcTemplate.execute(migration.sql());
            } catch (Exception e) {
                log.warn("Could not ensure column for: {} ({})", migration.purpose(), e.getMessage());
            }
        }
        ensureTraceStatusEnum();
        for (IndexDefinition index : INDEXES) {
            try {
                // IF NOT EXISTS: a no-op when hbm2ddl already created it.
                jdbcTemplate.execute(index.sql());
            } catch (Exception e) {
                log.warn("Could not ensure index for: {} ({})", index.purpose(), e.getMessage());
            }
        }
        try {
            // Refresh optimizer statistics so the new indexes are picked
            // up by the query planner without a manual ANALYZE.
            jdbcTemplate.execute("ANALYZE");
        } catch (Exception e) {
            log.debug("ANALYZE after index creation skipped: {}", e.getMessage());
        }
        log.info("Database indexes ensured ({} definitions)", INDEXES.size());
    }

    /**
     * Ensures PROCESS_TRACE_EVENT.STATUS accepts the FULL enum. hbm2ddl
     * maps {@code @Enumerated(STRING)} to H2's NATIVE ENUM type, so the
     * column carries the value list TraceEventStatus had when the table
     * was born — adding CANCELLED (pausas y stops del panel) did NOT
     * update it, so every cancelStep exploded at persist time on the
     * long-lived H2 file ("Valor no permitido para la columna
     * ...'CANCELLED'") and the caller's catch degraded the pause into a
     * FAILED run.
     *
     * <p>Two schema shapes are handled: (1) the real production shape, a
     * native ENUM column — re-declared with the canonical 5-value list
     * (H2 preserves the rows; multi-connection verified); (2) a legacy
     * inline CHECK constraint — dropped, NOT replaced: H2 2.4.240 has a
     * multi-session regression where a CHECK re-created after a drop is
     * enforced with the STALE expression on other connections (works on
     * 2.3.232 and in single sessions; verified with a standalone probe).
     * The enum itself keeps being enforced by Java/Hibernate.</p>
     */
    void ensureTraceStatusEnum() {
        try {
            // (1) Columna ENUM nativa (la forma de hbm2ddl en H2): re-declarar
            // con el enum canónico completo. Coste: reescritura de ~cientos
            // de filas en el arranque; idempotente en efecto.
            Integer enumColumns = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                            + "WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = 'PROCESS_TRACE_EVENT' "
                            + "AND COLUMN_NAME = 'STATUS' AND DATA_TYPE = 'ENUM'",
                    Integer.class);
            if (enumColumns != null && enumColumns > 0) {
                jdbcTemplate.execute("ALTER TABLE PROCESS_TRACE_EVENT ALTER COLUMN STATUS "
                        + "ENUM('STARTED','COMPLETED','FAILED','SKIPPED','CANCELLED') NOT NULL");
                log.info("ENUM de PROCESS_TRACE_EVENT.STATUS re-declarado con el enum completo "
                        + "(CANCELLED incluido)");
            }

            // (2) CHECK legado de otra vintage de esquema: eliminarlo si no
            // permite CANCELLED (H2 2.x: TABLE_CONSTRAINTS + CHECK_CONSTRAINTS).
            List<String> definitions = jdbcTemplate.queryForList(
                    "SELECT cc.CHECK_CLAUSE FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc "
                            + "JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc "
                            + "ON tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA "
                            + "AND tc.CONSTRAINT_NAME = cc.CONSTRAINT_NAME "
                            + "WHERE tc.CONSTRAINT_SCHEMA = 'PUBLIC' "
                            + "AND tc.TABLE_NAME = 'PROCESS_TRACE_EVENT' "
                            + "AND tc.CONSTRAINT_TYPE = 'CHECK'",
                    String.class);
            boolean allowsCancelled = definitions.stream()
                    .allMatch(sql -> sql != null && sql.toUpperCase().contains("CANCELLED"));
            if (definitions.isEmpty() || allowsCancelled) {
                return; // sin CHECK legado (o ya con el enum completo)
            }
            List<String> names = jdbcTemplate.queryForList(
                    "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                            + "WHERE CONSTRAINT_SCHEMA = 'PUBLIC' AND TABLE_NAME = 'PROCESS_TRACE_EVENT' "
                            + "AND CONSTRAINT_TYPE = 'CHECK'",
                    String.class);
            for (String name : names) {
                jdbcTemplate.execute("ALTER TABLE PROCESS_TRACE_EVENT DROP CONSTRAINT " + name);
            }
            log.info("CHECK obsoleto de PROCESS_TRACE_EVENT.STATUS eliminado ({} constraint(s)): "
                    + "el enum se valida en Java/Hibernate", names.size());
        } catch (Exception e) {
            log.warn("Could not refresh the trace status enum: {}", e.getMessage());
        }
    }

    private record IndexDefinition(String sql, String purpose) {
    }

    /** Column addition for existing tables (see COLUMN_MIGRATIONS). */
    private record ColumnMigration(String sql, String purpose) {
    }
}
