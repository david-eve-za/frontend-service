-- V2025_01_15_001__create_source_crawler_schema.sql
-- Esquema para persistencia de crawlers genéricos (no-Elscione)
-- Separado completamente del esquema Elscione existente
-- Compatible con H2 Database

-- ============================================================
-- 1. source_crawler_meta — Metadatos de versionado por crawler
-- ============================================================
CREATE TABLE source_crawler_meta (
    source_id           VARCHAR(100)    NOT NULL,
    crawler_class       VARCHAR(200)    NOT NULL,
    version             VARCHAR(50)     NOT NULL,
    tier                VARCHAR(20),
    last_schema_change  TIMESTAMP,
    config_hash         CHAR(64),
    updated_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_source_crawler_meta PRIMARY KEY (source_id)
);

COMMENT ON TABLE source_crawler_meta IS 'Metadatos de versionado de crawlers para invalidación de caché';

-- ============================================================
-- 2. source_tag — Etiquetas/Géneros (independiente)
-- ============================================================
CREATE TABLE source_tag (
    id                  UUID            NOT NULL DEFAULT RANDOM_UUID(),
    name                VARCHAR(100)    NOT NULL,
    normalized_name     VARCHAR(100)    NOT NULL,
    category            VARCHAR(50),
    source_id           VARCHAR(100),
    usage_count         BIGINT          NOT NULL DEFAULT 0,
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_source_tag PRIMARY KEY (id),
    CONSTRAINT uq_source_tag_name_source UNIQUE (normalized_name, source_id)
);

CREATE INDEX idx_source_tag_category ON source_tag (category);
CREATE INDEX idx_source_tag_usage ON source_tag (usage_count DESC);

COMMENT ON TABLE source_tag IS 'Catálogo de etiquetas/géneros normalizados';

-- ============================================================
-- 3. source_novel — Novela principal por fuente
-- ============================================================
CREATE TABLE source_novel (
    id                  UUID            NOT NULL DEFAULT RANDOM_UUID(),
    source_id           VARCHAR(100)    NOT NULL,
    novel_url           VARCHAR(2048)   NOT NULL,
    novel_url_hash      CHAR(64)        NOT NULL,
    title               VARCHAR(500)    NOT NULL,
    author              VARCHAR(500),
    cover_url           VARCHAR(2048),
    synopsis            TEXT,
    language            CHAR(2),
    is_mtl              BOOLEAN         NOT NULL DEFAULT FALSE,
    is_manga            BOOLEAN         NOT NULL DEFAULT FALSE,
    is_rtl              BOOLEAN         NOT NULL DEFAULT FALSE,
    status              VARCHAR(50),
    last_update         VARCHAR(100),
    tags                CLOB            NOT NULL DEFAULT '[]',
    volume_count        INT             NOT NULL DEFAULT 0,
    chapter_count       INT             NOT NULL DEFAULT 0,
    popularity          BIGINT          NOT NULL DEFAULT 0,
    view_count          BIGINT          NOT NULL DEFAULT 0,
    crawler_version     VARCHAR(50),
    crawler_tier        VARCHAR(20),
    extra               CLOB            NOT NULL DEFAULT '{}',
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_crawled_at     TIMESTAMP,
    cover_file          VARCHAR(500),
    CONSTRAINT pk_source_novel PRIMARY KEY (id),
    CONSTRAINT uq_source_novel_natural UNIQUE (source_id, novel_url_hash),
    CONSTRAINT uq_source_novel_url UNIQUE (source_id, novel_url)
);

CREATE INDEX idx_source_novel_source_id     ON source_novel (source_id);
CREATE INDEX idx_source_novel_title         ON source_novel (title);
CREATE INDEX idx_source_novel_language      ON source_novel (language);
CREATE INDEX idx_source_novel_popularity    ON source_novel (popularity DESC);
CREATE INDEX idx_source_novel_updated_at    ON source_novel (updated_at DESC);
CREATE INDEX idx_source_novel_last_crawled  ON source_novel (last_crawled_at);

COMMENT ON TABLE source_novel IS 'Novelas rastreadas por crawlers genéricos (no-Elscione)';

-- ============================================================
-- 4. source_volume — Volúmenes por novela
-- ============================================================
CREATE TABLE source_volume (
    id                  UUID            NOT NULL DEFAULT RANDOM_UUID(),
    novel_id            UUID            NOT NULL,
    serial              INT             NOT NULL,
    title               VARCHAR(500)    NOT NULL DEFAULT '',
    chapter_count       INT             NOT NULL DEFAULT 0,
    crawler_version     VARCHAR(50),
    crawler_tier        VARCHAR(20),
    extra               CLOB            NOT NULL DEFAULT '{}',
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_source_volume PRIMARY KEY (id),
    CONSTRAINT fk_source_volume_novel FOREIGN KEY (novel_id) REFERENCES source_novel(id) ON DELETE CASCADE,
    CONSTRAINT uq_source_volume_serial UNIQUE (novel_id, serial)
);

CREATE INDEX idx_source_volume_novel_id ON source_volume (novel_id);

COMMENT ON TABLE source_volume IS 'Volúmenes de novelas de crawlers genéricos';

-- ============================================================
-- 5. source_chapter — Capítulos por novela
-- ============================================================
CREATE TABLE source_chapter (
    id                  UUID            NOT NULL DEFAULT RANDOM_UUID(),
    novel_id            UUID            NOT NULL,
    volume_id           UUID,
    serial              INT             NOT NULL,
    title               VARCHAR(500)    NOT NULL,
    url                 VARCHAR(2048)   NOT NULL,
    is_done             BOOLEAN         NOT NULL DEFAULT FALSE,
    word_count          INT,
    crawler_version     VARCHAR(50),
    crawler_tier        VARCHAR(20),
    extra               CLOB            NOT NULL DEFAULT '{}',
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_fetched_at     TIMESTAMP,
    CONSTRAINT pk_source_chapter PRIMARY KEY (id),
    CONSTRAINT fk_source_chapter_novel FOREIGN KEY (novel_id) REFERENCES source_novel(id) ON DELETE CASCADE,
    CONSTRAINT fk_source_chapter_volume FOREIGN KEY (volume_id) REFERENCES source_volume(id) ON DELETE SET NULL,
    CONSTRAINT uq_source_chapter_serial UNIQUE (novel_id, serial)
);

CREATE INDEX idx_source_chapter_novel_id   ON source_chapter (novel_id);
CREATE INDEX idx_source_chapter_volume_id  ON source_chapter (volume_id);
CREATE INDEX idx_source_chapter_is_done    ON source_chapter (is_done);

COMMENT ON TABLE source_chapter IS 'Capítulos de novelas de crawlers genéricos';

-- ============================================================
-- 6. source_chapter_content — Contenido HTML comprimido (GZIP)
-- ============================================================
CREATE TABLE source_chapter_content (
    id                  UUID            NOT NULL DEFAULT RANDOM_UUID(),
    chapter_id          UUID            NOT NULL,
    content_file        VARCHAR(500)    NOT NULL,
    content_hash        CHAR(64)        NOT NULL,
    compressed_size     BIGINT          NOT NULL,
    original_size       BIGINT          NOT NULL,
    compression         VARCHAR(10)     NOT NULL DEFAULT 'gzip',
    images              CLOB            NOT NULL DEFAULT '[]',
    extractor           VARCHAR(50),
    crawler_version     VARCHAR(50),
    crawler_tier        VARCHAR(20),
    extra               CLOB            NOT NULL DEFAULT '{}',
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_source_chapter_content PRIMARY KEY (id),
    CONSTRAINT fk_source_chapter_content_chapter FOREIGN KEY (chapter_id) REFERENCES source_chapter(id) ON DELETE CASCADE,
    CONSTRAINT uq_source_chapter_content_chapter UNIQUE (chapter_id)
);

COMMENT ON TABLE source_chapter_content IS 'Contenido HTML de capítulos comprimido en filesystem (GZIP)';

-- ============================================================
-- 7. source_novel_translation — Traducciones de metadatos
-- ============================================================
CREATE TABLE source_novel_translation (
    id                  UUID            NOT NULL DEFAULT RANDOM_UUID(),
    novel_id            UUID            NOT NULL,
    language            CHAR(2)         NOT NULL,
    title               VARCHAR(500),
    author              VARCHAR(500),
    synopsis            TEXT,
    engine              VARCHAR(50),
    model               VARCHAR(100),
    glossary_applied    BOOLEAN         NOT NULL DEFAULT FALSE,
    glossary_version    INT,
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_source_novel_translation PRIMARY KEY (id),
    CONSTRAINT fk_source_novel_translation_novel FOREIGN KEY (novel_id) REFERENCES source_novel(id) ON DELETE CASCADE,
    CONSTRAINT uq_source_novel_translation_lang UNIQUE (novel_id, language)
);

COMMENT ON TABLE source_novel_translation IS 'Traducciones de título/autor/sinopsis de novela';

-- ============================================================
-- 8. source_volume_translation — Traducciones de títulos de volumen
-- ============================================================
CREATE TABLE source_volume_translation (
    id                  UUID            NOT NULL DEFAULT RANDOM_UUID(),
    novel_id            UUID            NOT NULL,
    volume_serial       INT             NOT NULL,
    language            CHAR(2)         NOT NULL,
    volume_title        VARCHAR(500)    NOT NULL,
    engine              VARCHAR(50),
    model               VARCHAR(100),
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_source_volume_translation PRIMARY KEY (id),
    CONSTRAINT fk_source_volume_translation_novel FOREIGN KEY (novel_id) REFERENCES source_novel(id) ON DELETE CASCADE,
    CONSTRAINT uq_source_volume_translation_unique UNIQUE (novel_id, volume_serial, language)
);

COMMENT ON TABLE source_volume_translation IS 'Traducciones de títulos de volumen';

-- ============================================================
-- 9. source_chapter_translation — Traducciones de capítulos
-- ============================================================
CREATE TABLE source_chapter_translation (
    id                  UUID            NOT NULL DEFAULT RANDOM_UUID(),
    novel_id            UUID            NOT NULL,
    chapter_serial      INT             NOT NULL,
    language            CHAR(2)         NOT NULL,
    chapter_title       VARCHAR(500),
    content_file        VARCHAR(500),
    content_hash        CHAR(64),
    compressed_size     BIGINT,
    compression         VARCHAR(10)     NOT NULL DEFAULT 'gzip',
    engine              VARCHAR(50),
    model               VARCHAR(100),
    glossary_applied    BOOLEAN         NOT NULL DEFAULT FALSE,
    glossary_version    INT,
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_source_chapter_translation PRIMARY KEY (id),
    CONSTRAINT fk_source_chapter_translation_novel FOREIGN KEY (novel_id) REFERENCES source_novel(id) ON DELETE CASCADE,
    CONSTRAINT uq_source_chapter_translation_unique UNIQUE (novel_id, chapter_serial, language)
);

CREATE INDEX idx_source_chapter_translation_novel_serial ON source_chapter_translation (novel_id, chapter_serial);

COMMENT ON TABLE source_chapter_translation IS 'Traducciones de capítulos (título + contenido)';

-- ============================================================
-- 10. source_novel_glossary — Glosarios por novela e idioma
-- ============================================================
CREATE TABLE source_novel_glossary (
    id                  UUID            NOT NULL DEFAULT RANDOM_UUID(),
    novel_id            UUID            NOT NULL,
    language            CHAR(2)         NOT NULL,
    terms               CLOB            NOT NULL DEFAULT '{}',
    version             INT             NOT NULL DEFAULT 1,
    auto_extracted      BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_source_novel_glossary PRIMARY KEY (id),
    CONSTRAINT fk_source_novel_glossary_novel FOREIGN KEY (novel_id) REFERENCES source_novel(id) ON DELETE CASCADE,
    CONSTRAINT uq_source_novel_glossary_lang UNIQUE (novel_id, language)
);

COMMENT ON TABLE source_novel_glossary IS 'Glosarios término→traducción para traducción asistida';

-- ============================================================
-- 11. source_novel_tag — Many-to-Many novela ↔ tag
-- ============================================================
CREATE TABLE source_novel_tag (
    novel_id            UUID            NOT NULL,
    tag_id              UUID            NOT NULL,
    assigned_at         TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    source              VARCHAR(50)     NOT NULL DEFAULT 'crawler',
    CONSTRAINT pk_source_novel_tag PRIMARY KEY (novel_id, tag_id),
    CONSTRAINT fk_source_novel_tag_novel FOREIGN KEY (novel_id) REFERENCES source_novel(id) ON DELETE CASCADE,
    CONSTRAINT fk_source_novel_tag_tag FOREIGN KEY (tag_id) REFERENCES source_tag(id) ON DELETE CASCADE
);

COMMENT ON TABLE source_novel_tag IS 'Asociación novela ↔ etiquetas';

-- ============================================================
-- Función auxiliar para updated_at automático (H2 compatible)
-- ============================================================
-- Note: H2 doesn't support triggers in SQL the same way as PostgreSQL.
-- The updated_at field is managed by JPA @PreUpdate annotation in the entity classes.

-- ============================================================
-- Initial data for source_crawler_meta (optional, populated at runtime)
-- ============================================================
-- No initial data - populated at runtime by SourceRegistry