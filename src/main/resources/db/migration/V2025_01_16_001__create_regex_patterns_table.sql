-- V2025_01_16_001__create_regex_patterns_table.sql
-- Tabla para patrones de expresión regular (regex patterns)
-- Compatible con H2 y PostgreSQL

-- ============================================================
-- Tabla regex_patterns — Patrones de expresión regular
-- ============================================================
CREATE TABLE regex_patterns (
    id                  UUID            NOT NULL DEFAULT gen_random_uuid(),
    name                VARCHAR(100)    NOT NULL UNIQUE,
    display_name        VARCHAR(200)    NOT NULL,
    description         TEXT,
    pattern             TEXT            NOT NULL,
    replacement         TEXT,
    pattern_type        VARCHAR(20)     NOT NULL,
    enabled             BOOLEAN         NOT NULL DEFAULT TRUE,
    case_insensitive    BOOLEAN         NOT NULL DEFAULT FALSE,
    multiline           BOOLEAN         NOT NULL DEFAULT FALSE,
    dot_all             BOOLEAN         NOT NULL DEFAULT FALSE,
    order_index         INT             NOT NULL DEFAULT 0,
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_regex_patterns PRIMARY KEY (id),
    CONSTRAINT uq_regex_patterns_name UNIQUE (name)
);

CREATE INDEX idx_regex_patterns_enabled ON regex_patterns (enabled);
CREATE INDEX idx_regex_patterns_pattern_type ON regex_patterns (pattern_type);
CREATE INDEX idx_regex_patterns_order_index ON regex_patterns (order_index);

COMMENT ON TABLE regex_patterns IS 'Patrones de expresión regular para procesamiento de texto';
COMMENT ON COLUMN regex_patterns.pattern_type IS 'Tipo: URL, SOCIAL_MEDIA, ISBN, PAGE_NUMBER, WHITESPACE, CUSTOM';

-- Función auxiliar para updated_at automático (H2 compatible)
-- Note: H2 doesn't support triggers in SQL the same way as PostgreSQL.
-- The updated_at field is managed by JPA @PreUpdate annotation in the entity classes.