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

    private record IndexDefinition(String sql, String purpose) {
    }

    /** Column addition for existing tables (see COLUMN_MIGRATIONS). */
    private record ColumnMigration(String sql, String purpose) {
    }
}
