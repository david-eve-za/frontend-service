-- Flyway Migration V1: Create crawler schema for multi-source novel catalog
-- This schema is separate from the existing elscione-specific tables (novel, novel_volume, novel_volume_file)
-- to allow independent evolution and avoid conflicts.
-- Compatible with H2 Database

-- Source registry table
CREATE TABLE crawler_source (
    id VARCHAR(100) PRIMARY KEY,
    display_name VARCHAR(200) NOT NULL,
    icon VARCHAR(100),
    language CHAR(2) NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    rate_limit_rpm INT DEFAULT 30,
    requires_login BOOLEAN DEFAULT FALSE,
    enabled BOOLEAN DEFAULT TRUE,
    tags VARCHAR(1000),  -- comma-separated tags
    config_json CLOB,    -- JSON config for source-specific settings
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_crawler_source_enabled ON crawler_source(enabled);
CREATE INDEX idx_crawler_source_language ON crawler_source(language);

-- Novels per source (generic, source-agnostic)
CREATE TABLE crawler_novel (
    id UUID PRIMARY KEY,
    source_id VARCHAR(100) NOT NULL REFERENCES crawler_source(id) ON DELETE CASCADE,
    source_novel_id VARCHAR(500) NOT NULL,  -- URL or ID from source
    title VARCHAR(500) NOT NULL,
    author VARCHAR(500),
    cover_url VARCHAR(1000),
    synopsis TEXT,
    language CHAR(2),
    is_mtl BOOLEAN DEFAULT FALSE,
    is_manga BOOLEAN DEFAULT FALSE,
    tags VARCHAR(2000),  -- comma-separated
    status VARCHAR(100), -- Ongoing, Completed, Hiatus, etc.
    last_update VARCHAR(100), -- Source's last update string
    last_synced_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (source_id, source_novel_id)
);

CREATE INDEX idx_crawler_novel_source ON crawler_novel(source_id);
CREATE INDEX idx_crawler_novel_title ON crawler_novel(title);
CREATE INDEX idx_crawler_novel_last_synced ON crawler_novel(last_synced_at);

-- Volumes per novel
CREATE TABLE crawler_volume (
    id UUID PRIMARY KEY ,
    novel_id UUID NOT NULL REFERENCES crawler_novel(id) ON DELETE CASCADE,
    source_volume_id VARCHAR(200) NOT NULL,
    volume_number INT,
    title VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (novel_id, source_volume_id)
);

CREATE INDEX idx_crawler_volume_novel ON crawler_volume(novel_id);

-- Chapters per volume
CREATE TABLE crawler_chapter (
    id UUID PRIMARY KEY ,
    volume_id UUID NOT NULL REFERENCES crawler_volume(id) ON DELETE CASCADE,
    source_chapter_id VARCHAR(200) NOT NULL,
    chapter_number INT NOT NULL,
    title VARCHAR(500) NOT NULL,
    url VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (volume_id, source_chapter_id)
);

CREATE INDEX idx_crawler_chapter_volume ON crawler_chapter(volume_id);

-- Chapter content cache (optional, for performance)
CREATE TABLE crawler_chapter_content (
    id UUID PRIMARY KEY ,
    chapter_id UUID NOT NULL REFERENCES crawler_chapter(id) ON DELETE CASCADE,
    html_content TEXT,
    images_json CLOB,  -- JSON array of image URLs
    fetched_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP,  -- TTL for cache invalidation
    UNIQUE (chapter_id)
);

CREATE INDEX idx_crawler_chapter_content_expires ON crawler_chapter_content(expires_at);

-- Source sync status tracking (for monitoring)
CREATE TABLE crawler_sync_status (
    id UUID PRIMARY KEY ,
    source_id VARCHAR(100) NOT NULL REFERENCES crawler_source(id) ON DELETE CASCADE,
    operation VARCHAR(50) NOT NULL,  -- 'search', 'get_novel', 'get_chapters', 'get_chapter_content', 'sync_catalog'
    status VARCHAR(20) NOT NULL,     -- 'success', 'failed', 'partial'
    novels_processed INT DEFAULT 0,
    chapters_processed INT DEFAULT 0,
    error_message TEXT,
    started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP
);

CREATE INDEX idx_crawler_sync_status_source ON crawler_sync_status(source_id);
CREATE INDEX idx_crawler_sync_status_started ON crawler_sync_status(started_at);

-- Insert default sources (will be overridden by application.yml config at runtime)
-- These are placeholder records; actual registration happens via SourceRegistry at startup
-- Note: H2 doesn't support MERGE INTO with KEY, using INSERT with conditional
INSERT INTO crawler_source (id, display_name, icon, language, base_url, rate_limit_rpm, requires_login, enabled, tags)
SELECT 'elscione', 'Elscione', 'pi pi-book', 'es', 'https://server.elscione.com/Officially%20Translated%20Light%20Novels/', 20, false, true, 'official,spanish,light-novel'
WHERE NOT EXISTS (SELECT 1 FROM crawler_source WHERE id = 'elscione');

INSERT INTO crawler_source (id, display_name, icon, language, base_url, rate_limit_rpm, requires_login, enabled, tags)
SELECT 'novelasligeras', 'Novelas Ligeras', 'pi pi-book', 'es', 'https://novelasligeras.net/', 30, false, true, 'spanish,light-novel,web'
WHERE NOT EXISTS (SELECT 1 FROM crawler_source WHERE id = 'novelasligeras');

INSERT INTO crawler_source (id, display_name, icon, language, base_url, rate_limit_rpm, requires_login, enabled, tags)
SELECT 'novelaenespanol', 'Novela en Español', 'pi pi-book', 'es', 'https://novelaenespanol.com/', 60, false, true, 'spanish,light-novel,wordpress'
WHERE NOT EXISTS (SELECT 1 FROM crawler_source WHERE id = 'novelaenespanol');

INSERT INTO crawler_source (id, display_name, icon, language, base_url, rate_limit_rpm, requires_login, enabled, tags)
SELECT 'novelasligera_com', 'Novelas Ligera', 'pi pi-book', 'es', 'https://novelasligera.com/', 30, false, true, 'spanish,light-novel,elementor'
WHERE NOT EXISTS (SELECT 1 FROM crawler_source WHERE id = 'novelasligera_com');

INSERT INTO crawler_source (id, display_name, icon, language, base_url, rate_limit_rpm, requires_login, enabled, tags)
SELECT 'skynovels', 'SkyNovels', 'pi pi-book', 'es', 'https://skynovels.net/', 30, false, true, 'spanish,light-novel'
WHERE NOT EXISTS (SELECT 1 FROM crawler_source WHERE id = 'skynovels');

INSERT INTO crawler_source (id, display_name, icon, language, base_url, rate_limit_rpm, requires_login, enabled, tags)
SELECT 'domentranslations', 'Domentranslations', 'pi pi-book', 'es', 'https://domentranslations.wordpress.com/', 60, false, true, 'spanish,light-novel,wordpress,translation'
WHERE NOT EXISTS (SELECT 1 FROM crawler_source WHERE id = 'domentranslations');

INSERT INTO crawler_source (id, display_name, icon, language, base_url, rate_limit_rpm, requires_login, enabled, tags)
SELECT 'novelupdates', 'NovelUpdates', 'pi pi-globe', 'en', 'https://www.novelupdates.com/', 120, false, true, 'multi,directory,api'
WHERE NOT EXISTS (SELECT 1 FROM crawler_source WHERE id = 'novelupdates');

INSERT INTO crawler_source (id, display_name, icon, language, base_url, rate_limit_rpm, requires_login, enabled, tags)
SELECT 'foxaholic', 'Foxaholic', 'pi pi-globe', 'en', 'https://foxaholic.com/', 30, false, true, 'multi,translation,web'
WHERE NOT EXISTS (SELECT 1 FROM crawler_source WHERE id = 'foxaholic');