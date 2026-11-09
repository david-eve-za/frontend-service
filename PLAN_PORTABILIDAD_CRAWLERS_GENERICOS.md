# Plan de Portabilidad: Persistencia para Crawlers Genéricos (No-Elscione)

## Principio Rector
> **Elscione NO se toca**. Mantiene su flujo funcional completo: `Novel` → `NovelVolume` → `NovelVolumeFile` → `Book` → `Chunk` → Split/Translate/Audio.
>
> Los 70+ crawlers generados desde Python obtienen su **propio esquema de BD** independiente, optimizado para los datos que realmente extraen.

---

## 1. Esquema de Base de Datos (10 Tablas)

### 1.1 `source_novel` — Novela principal por fuente
```sql
CREATE TABLE source_novel (
    -- PK técnica para JPA
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    
    -- Clave natural de negocio (una novela por fuente)
    source_id       VARCHAR(100)    NOT NULL,           -- ej: 'readnovelfull', 'novelupdates'
    novel_url       VARCHAR(2048)   NOT NULL,           -- URL canónica de la novela
    novel_url_hash  CHAR(64)        NOT NULL,           -- SHA256(novel_url) para índice único
    
    -- Metadatos básicos (del crawler)
    title           VARCHAR(500)    NOT NULL,
    author          VARCHAR(500),
    cover_url       VARCHAR(2048),
    synopsis        TEXT,
    language        CHAR(2),                          -- ISO 639-1: 'en', 'es', 'zh', etc.
    is_mtl          BOOLEAN         NOT NULL DEFAULT FALSE,
    is_manga        BOOLEAN         NOT NULL DEFAULT FALSE,
    is_rtl          BOOLEAN         NOT NULL DEFAULT FALSE,
    status          VARCHAR(50),                      -- 'Ongoing', 'Completed', 'Hiatus', etc.
    last_update     VARCHAR(100),                     -- Timestamp string del origen
    tags            JSONB           NOT NULL DEFAULT '[]'::jsonb,
    
    -- Conteos desnormalizados para listados rápidos
    volume_count    INT             NOT NULL DEFAULT 0,
    chapter_count   INT             NOT NULL DEFAULT 0,
    
    -- Popularidad y tracking
    popularity      BIGINT          NOT NULL DEFAULT 0,
    view_count      BIGINT          NOT NULL DEFAULT 0,
    
    -- Versionado de crawler (para invalidación de caché)
    crawler_version VARCHAR(50),
    crawler_tier    VARCHAR(20),
    extra           JSONB           NOT NULL DEFAULT '{}'::jsonb,
    
    -- Timestamps
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    last_crawled_at TIMESTAMPTZ,
    
    -- Archivo de portada local
    cover_file      VARCHAR(500),                     -- Path relativo: sources/{sourceId}/novels/{id}/cover.jpg
    
    CONSTRAINT pk_source_novel PRIMARY KEY (id),
    CONSTRAINT uq_source_novel_natural UNIQUE (source_id, novel_url_hash),
    CONSTRAINT uq_source_novel_url UNIQUE (source_id, novel_url)
);

-- Índices para consultas frecuentes
CREATE INDEX idx_source_novel_source_id       ON source_novel (source_id);
CREATE INDEX idx_source_novel_title           ON source_novel (title);
CREATE INDEX idx_source_novel_language        ON source_novel (language);
CREATE INDEX idx_source_novel_popularity      ON source_novel (popularity DESC);
CREATE INDEX idx_source_novel_updated_at      ON source_novel (updated_at DESC);
CREATE INDEX idx_source_novel_last_crawled    ON source_novel (last_crawled_at);
CREATE INDEX idx_source_novel_tags_gin        ON source_novel USING GIN (tags);
```

### 1.2 `source_volume` — Volúmenes por novela
```sql
CREATE TABLE source_volume (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    novel_id        UUID            NOT NULL,
    
    -- Clave de negocio: serial dentro de la novela (1, 2, 3...)
    serial          INT             NOT NULL,
    title           VARCHAR(500)    NOT NULL DEFAULT '',
    chapter_count   INT             NOT NULL DEFAULT 0,
    
    -- Versionado crawler
    crawler_version VARCHAR(50),
    crawler_tier    VARCHAR(20),
    extra           JSONB           NOT NULL DEFAULT '{}'::jsonb,
    
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    
    CONSTRAINT pk_source_volume PRIMARY KEY (id),
    CONSTRAINT fk_source_volume_novel FOREIGN KEY (novel_id) REFERENCES source_novel(id) ON DELETE CASCADE,
    CONSTRAINT uq_source_volume_serial UNIQUE (novel_id, serial)
);

CREATE INDEX idx_source_volume_novel_id ON source_volume (novel_id);
```

### 1.3 `source_chapter` — Capítulos por volumen
```sql
CREATE TABLE source_chapter (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    novel_id        UUID            NOT NULL,
    volume_id       UUID,                                   -- NULL si no asignado a volumen
    
    -- Clave de negocio: serial global dentro de la novela
    serial          INT             NOT NULL,
    title           VARCHAR(500)    NOT NULL,
    url             VARCHAR(2048)   NOT NULL,
    
    -- Estado de contenido
    is_done         BOOLEAN         NOT NULL DEFAULT FALSE,
    word_count      INT,
    
    -- Versionado crawler
    crawler_version VARCHAR(50),
    crawler_tier    VARCHAR(20),
    extra           JSONB           NOT NULL DEFAULT '{}'::jsonb,
    
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    last_fetched_at TIMESTAMPTZ,
    
    CONSTRAINT pk_source_chapter PRIMARY KEY (id),
    CONSTRAINT fk_source_chapter_novel FOREIGN KEY (novel_id) REFERENCES source_novel(id) ON DELETE CASCADE,
    CONSTRAINT fk_source_chapter_volume FOREIGN KEY (volume_id) REFERENCES source_volume(id) ON DELETE SET NULL,
    CONSTRAINT uq_source_chapter_serial UNIQUE (novel_id, serial)
);

CREATE INDEX idx_source_chapter_novel_id    ON source_chapter (novel_id);
CREATE INDEX idx_source_chapter_volume_id   ON source_chapter (volume_id);
CREATE INDEX idx_source_chapter_is_done     ON source_chapter (is_done) WHERE is_done = TRUE;
```

### 1.4 `source_chapter_content` — Contenido HTML (filesystem + metadatos)
```sql
CREATE TABLE source_chapter_content (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    chapter_id      UUID            NOT NULL,
    
    -- Almacenamiento en filesystem (comprimido GZIP)
    content_file    VARCHAR(500)    NOT NULL,          -- Path: sources/{sourceId}/novels/{novelId}/chapters/{serial:06}.gz
    content_hash    CHAR(64)        NOT NULL,          -- SHA256 del HTML original (para invalidación traducciones)
    compressed_size BIGINT          NOT NULL,
    original_size   BIGINT          NOT NULL,
    compression     VARCHAR(10)     NOT NULL DEFAULT 'gzip',  -- 'gzip' | 'lzma' (futuro)
    
    -- Imágenes extraídas
    images          JSONB           NOT NULL DEFAULT '[]'::jsonb,  -- [{url, local_path, width, height}]
    
    -- Metadatos de extracción
    extractor       VARCHAR(50),                      -- 'jsoup', 'readability', etc.
    crawler_version VARCHAR(50),
    crawler_tier    VARCHAR(20),
    extra           JSONB           NOT NULL DEFAULT '{}'::jsonb,
    
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    
    CONSTRAINT pk_source_chapter_content PRIMARY KEY (id),
    CONSTRAINT fk_source_chapter_content_chapter FOREIGN KEY (chapter_id) REFERENCES source_chapter(id) ON DELETE CASCADE,
    CONSTRAINT uq_source_chapter_content_chapter UNIQUE (chapter_id)
);
```

### 1.5 `source_novel_translation` — Traducciones de metadatos
```sql
CREATE TABLE source_novel_translation (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    novel_id        UUID            NOT NULL,
    language        CHAR(2)         NOT NULL,          -- Código destino: 'es', 'fr', etc.
    
    title           VARCHAR(500),
    author          VARCHAR(500),
    synopsis        TEXT,
    
    -- Motor de traducción usado
    engine          VARCHAR(50),                       -- 'google', 'deepl', 'nvidia', etc.
    model           VARCHAR(100),
    
    -- Glosario aplicado
    glossary_applied BOOLEAN       NOT NULL DEFAULT FALSE,
    glossary_version INT,
    
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    
    CONSTRAINT pk_source_novel_translation PRIMARY KEY (id),
    CONSTRAINT fk_source_novel_translation_novel FOREIGN KEY (novel_id) REFERENCES source_novel(id) ON DELETE CASCADE,
    CONSTRAINT uq_source_novel_translation_lang UNIQUE (novel_id, language)
);
```

### 1.6 `source_volume_translation` — Traducciones de títulos de volumen
```sql
CREATE TABLE source_volume_translation (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    novel_id        UUID            NOT NULL,
    volume_serial   INT             NOT NULL,
    language        CHAR(2)         NOT NULL,
    
    volume_title    VARCHAR(500)    NOT NULL,
    
    engine          VARCHAR(50),
    model           VARCHAR(100),
    
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    
    CONSTRAINT pk_source_volume_translation PRIMARY KEY (id),
    CONSTRAINT fk_source_volume_translation_novel FOREIGN KEY (novel_id) REFERENCES source_novel(id) ON DELETE CASCADE,
    CONSTRAINT uq_source_volume_translation_unique UNIQUE (novel_id, volume_serial, language)
);
```

### 1.7 `source_chapter_translation` — Traducciones de capítulos
```sql
CREATE TABLE source_chapter_translation (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    novel_id        UUID            NOT NULL,
    chapter_serial  INT             NOT NULL,
    language        CHAR(2)         NOT NULL,
    
    chapter_title   VARCHAR(500),
    
    -- Contenido traducido (archivo separado comprimido)
    content_file    VARCHAR(500),                       -- sources/{sourceId}/novels/{novelId}/chapters/{serial:06}.{lang}.gz
    content_hash    CHAR(64),                           -- Hash del contenido ORIGINAL (no traducido) para cache invalidation
    compressed_size BIGINT,
    compression     VARCHAR(10)     NOT NULL DEFAULT 'gzip',
    
    engine          VARCHAR(50),
    model           VARCHAR(100),
    glossary_applied BOOLEAN       NOT NULL DEFAULT FALSE,
    glossary_version INT,
    
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    
    CONSTRAINT pk_source_chapter_translation PRIMARY KEY (id),
    CONSTRAINT fk_source_chapter_translation_novel FOREIGN KEY (novel_id) REFERENCES source_novel(id) ON DELETE CASCADE,
    CONSTRAINT uq_source_chapter_translation_unique UNIQUE (novel_id, chapter_serial, language)
);

CREATE INDEX idx_source_chapter_translation_novel_serial ON source_chapter_translation (novel_id, chapter_serial);
```

### 1.8 `source_novel_glossary` — Glosarios por novela e idioma
```sql
CREATE TABLE source_novel_glossary (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    novel_id        UUID            NOT NULL,
    language        CHAR(2)         NOT NULL,          -- Idioma DESTINO de la traducción
    
    -- JSON: término_original → traducción_elegida
    terms           JSONB           NOT NULL DEFAULT '{}'::jsonb,
    
    version         INT             NOT NULL DEFAULT 1,
    auto_extracted  BOOLEAN         NOT NULL DEFAULT FALSE,
    
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    
    CONSTRAINT pk_source_novel_glossary PRIMARY KEY (id),
    CONSTRAINT fk_source_novel_glossary_novel FOREIGN KEY (novel_id) REFERENCES source_novel(id) ON DELETE CASCADE,
    CONSTRAINT uq_source_novel_glossary_lang UNIQUE (novel_id, language)
);
```

### 1.9 `source_tag` / `source_novel_tag` — Etiquetas/Géneros (Many-to-Many)
```sql
CREATE TABLE source_tag (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    name            VARCHAR(100)    NOT NULL,
    normalized_name VARCHAR(100)    NOT NULL,          -- Lowercase, sin acentos, para matching
    category        VARCHAR(50),                       -- 'genre', 'theme', 'demographic', 'other'
    source_id       VARCHAR(100),                      -- NULL = global, valor = específico de fuente
    usage_count     BIGINT          NOT NULL DEFAULT 0,
    
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    
    CONSTRAINT pk_source_tag PRIMARY KEY (id),
    CONSTRAINT uq_source_tag_name_source UNIQUE (normalized_name, source_id)
);

CREATE INDEX idx_source_tag_category ON source_tag (category);
CREATE INDEX idx_source_tag_usage ON source_tag (usage_count DESC);

CREATE TABLE source_novel_tag (
    novel_id        UUID            NOT NULL,
    tag_id          UUID            NOT NULL,
    
    assigned_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    source          VARCHAR(50)     NOT NULL DEFAULT 'crawler',  -- 'crawler', 'anilist', 'manual'
    
    CONSTRAINT pk_source_novel_tag PRIMARY KEY (novel_id, tag_id),
    CONSTRAINT fk_source_novel_tag_novel FOREIGN KEY (novel_id) REFERENCES source_novel(id) ON DELETE CASCADE,
    CONSTRAINT fk_source_novel_tag_tag FOREIGN KEY (tag_id) REFERENCES source_tag(id) ON DELETE CASCADE
);
```

### 1.10 `source_crawler_meta` — Metadatos de versionado por crawler
```sql
CREATE TABLE source_crawler_meta (
    source_id       VARCHAR(100)    NOT NULL,
    crawler_class   VARCHAR(200)    NOT NULL,          -- FQCN del crawler Java
    version         VARCHAR(50)     NOT NULL,
    tier            VARCHAR(20),                       -- 'official', 'community', 'generated'
    last_schema_change TIMESTAMPTZ,
    config_hash     CHAR(64),                          -- Hash de selectores/config para detectar cambios
    
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    
    CONSTRAINT pk_source_crawler_meta PRIMARY KEY (source_id)
);
```

---

## 2. Entidades JPA (Paquete: `com.glez.frontendservice.model.source`)

### 2.1 `SourceNovel.java`
```java
@Entity
@Table(name = "source_novel", indexes = {
    @Index(name = "idx_source_novel_source_id", columnList = "source_id"),
    @Index(name = "idx_source_novel_title", columnList = "title"),
    @Index(name = "idx_source_novel_language", columnList = "language"),
    @Index(name = "idx_source_novel_popularity", columnList = "popularity DESC"),
    @Index(name = "idx_source_novel_updated_at", columnList = "updated_at DESC"),
    @Index(name = "idx_source_novel_last_crawled", columnList = "last_crawled_at"),
    @Index(name = "idx_source_novel_tags_gin", columnList = "tags", columnDefinition = "GIN")
},
uniqueConstraints = {
    @UniqueConstraint(name = "uq_source_novel_natural", columnNames = {"source_id", "novel_url_hash"}),
    @UniqueConstraint(name = "uq_source_novel_url", columnNames = {"source_id", "novel_url"})
})
@Data @NoArgsConstructor @AllArgsConstructor
public class SourceNovel {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "source_id", nullable = false, length = 100)
    private String sourceId;

    @Column(name = "novel_url", nullable = false, length = 2048)
    private String novelUrl;

    @Column(name = "novel_url_hash", nullable = false, length = 64)
    private String novelUrlHash;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(length = 500)
    private String author;

    @Column(name = "cover_url", length = 2048)
    private String coverUrl;

    @Column(columnDefinition = "TEXT")
    private String synopsis;

    @Column(name = "language", length = 2)
    private String language;

    @Column(name = "is_mtl", nullable = false)
    private boolean mtl = false;

    @Column(name = "is_manga", nullable = false)
    private boolean manga = false;

    @Column(name = "is_rtl", nullable = false)
    private boolean rtl = false;

    @Column(length = 50)
    private String status;

    @Column(name = "last_update", length = 100)
    private String lastUpdate;

    @Column(columnDefinition = "JSONB", nullable = false)
    private List<String> tags = new ArrayList<>();

    @Column(name = "volume_count", nullable = false)
    private int volumeCount = 0;

    @Column(name = "chapter_count", nullable = false)
    private int chapterCount = 0;

    @Column(name = "popularity", nullable = false)
    private long popularity = 0;

    @Column(name = "view_count", nullable = false)
    private long viewCount = 0;

    @Column(name = "crawler_version", length = 50)
    private String crawlerVersion;

    @Column(name = "crawler_tier", length = 20)
    private String crawlerTier;

    @Column(columnDefinition = "JSONB", nullable = false)
    private Map<String, Object> extra = new HashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_crawled_at")
    private Instant lastCrawledAt;

    @Column(name = "cover_file", length = 500)
    private String coverFile;

    @OneToMany(mappedBy = "novel", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("serial ASC")
    private List<SourceVolume> volumes = new ArrayList<>();

    // Helpers
    public static String computeUrlHash(String sourceId, String novelUrl) {
        return Hashing.sha256().hashString(sourceId + "|" + novelUrl, StandardCharsets.UTF_8).toString();
    }
}
```

### 2.2 `SourceVolume.java`
```java
@Entity
@Table(name = "source_volume", indexes = {
    @Index(name = "idx_source_volume_novel_id", columnList = "novel_id")
},
uniqueConstraints = @UniqueConstraint(name = "uq_source_volume_serial", columnNames = {"novel_id", "serial"}))
@Data @NoArgsConstructor @AllArgsConstructor
public class SourceVolume {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "novel_id", nullable = false)
    @JsonIgnore
    private SourceNovel novel;

    @Column(nullable = false)
    private int serial;

    @Column(length = 500, nullable = false)
    private String title = "";

    @Column(name = "chapter_count", nullable = false)
    private int chapterCount = 0;

    @Column(name = "crawler_version", length = 50)
    private String crawlerVersion;

    @Column(name = "crawler_tier", length = 20)
    private String crawlerTier;

    @Column(columnDefinition = "JSONB", nullable = false)
    private Map<String, Object> extra = new HashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "volume", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("serial ASC")
    private List<SourceChapter> chapters = new ArrayList<>();
}
```

### 2.3 `SourceChapter.java`
```java
@Entity
@Table(name = "source_chapter", indexes = {
    @Index(name = "idx_source_chapter_novel_id", columnList = "novel_id"),
    @Index(name = "idx_source_chapter_volume_id", columnList = "volume_id"),
    @Index(name = "idx_source_chapter_is_done", columnList = "is_done", where = "is_done = true")
},
uniqueConstraints = @UniqueConstraint(name = "uq_source_chapter_serial", columnNames = {"novel_id", "serial"}))
@Data @NoArgsConstructor @AllArgsConstructor
public class SourceChapter {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "novel_id", nullable = false)
    @JsonIgnore
    private SourceNovel novel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "volume_id")
    @JsonIgnore
    private SourceVolume volume;

    @Column(nullable = false)
    private int serial;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(name = "is_done", nullable = false)
    private boolean done = false;

    private Integer wordCount;

    @Column(name = "crawler_version", length = 50)
    private String crawlerVersion;

    @Column(name = "crawler_tier", length = 20)
    private String crawlerTier;

    @Column(columnDefinition = "JSONB", nullable = false)
    private Map<String, Object> extra = new HashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_fetched_at")
    private Instant lastFetchedAt;

    @OneToOne(mappedBy = "chapter", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    private SourceChapterContent content;
}
```

### 2.4 `SourceChapterContent.java`
```java
@Entity
@Table(name = "source_chapter_content",
uniqueConstraints = @UniqueConstraint(name = "uq_source_chapter_content_chapter", columnNames = "chapter_id"))
@Data @NoArgsConstructor @AllArgsConstructor
public class SourceChapterContent {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chapter_id", nullable = false)
    @MapsId
    @JsonIgnore
    private SourceChapter chapter;

    @Column(name = "content_file", nullable = false, length = 500)
    private String contentFile;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "compressed_size", nullable = false)
    private long compressedSize;

    @Column(name = "original_size", nullable = false)
    private long originalSize;

    @Column(name = "compression", nullable = false, length = 10)
    private String compression = "gzip";  // 'gzip' | 'lzma'

    @Column(columnDefinition = "JSONB", nullable = false)
    private List<ChapterImageInfo> images = new ArrayList<>();

    @Column(length = 50)
    private String extractor;

    @Column(name = "crawler_version", length = 50)
    private String crawlerVersion;

    @Column(name = "crawler_tier", length = 20)
    private String crawlerTier;

    @Column(columnDefinition = "JSONB", nullable = false)
    private Map<String, Object> extra = new HashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ChapterImageInfo {
        private String url;
        private String localPath;
        private Integer width;
        private Integer height;
    }
}
```

### 2.5 Entidades de Traducción y Glosario
```java
// SourceNovelTranslation, SourceVolumeTranslation, SourceChapterTranslation
// SourceNovelGlossary, SourceTag, SourceNovelTag, SourceCrawlerMeta
// (Estructura similar, omitida por brevedad - ver SQL arriba)
```

---

## 3. Repositorios

```java
// SourceNovelRepository.java
public interface SourceNovelRepository extends JpaRepository<SourceNovel, UUID> {
    Optional<SourceNovel> findBySourceIdAndNovelUrlHash(String sourceId, String urlHash);
    Optional<SourceNovel> findBySourceIdAndNovelUrl(String sourceId, String novelUrl);
    
    @Query("SELECT n FROM SourceNovel n WHERE n.sourceId = :sourceId AND " +
           "(n.title ILIKE %:search% OR n.author ILIKE %:search% OR n.synopsis ILIKE %:search%)")
    Page<SourceNovel> searchBySource(String sourceId, String search, Pageable pageable);

    @Query("SELECT n FROM SourceNovel n WHERE n.sourceId = :sourceId AND :tag = ANY(n.tags)")
    Page<SourceNovel> findBySourceAndTag(String sourceId, String tag, Pageable pageable);

    @Query("SELECT n FROM SourceNovel n WHERE n.sourceId = :sourceId ORDER BY n.popularity DESC")
    Page<SourceNovel> findPopularBySource(String sourceId, Pageable pageable);

    long countBySourceId(String sourceId);
}

// SourceVolumeRepository.java
public interface SourceVolumeRepository extends JpaRepository<SourceVolume, UUID> {
    List<SourceVolume> findByNovelIdOrderBySerialAsc(UUID novelId);
    Optional<SourceVolume> findByNovelIdAndSerial(UUID novelId, int serial);
    long countByNovelId(UUID novelId);
}

// SourceChapterRepository.java
public interface SourceChapterRepository extends JpaRepository<SourceChapter, UUID> {
    List<SourceChapter> findByNovelIdOrderBySerialAsc(UUID novelId);
    List<SourceChapter> findByVolumeIdOrderBySerialAsc(UUID volumeId);
    Optional<SourceChapter> findByNovelIdAndSerial(UUID novelId, int serial);
    long countByNovelIdAndDoneTrue(UUID novelId);
}

// SourceChapterContentRepository.java
public interface SourceChapterContentRepository extends JpaRepository<SourceChapterContent, UUID> {
    Optional<SourceChapterContent> findByChapterId(UUID chapterId);
}
```

---

## 4. Servicio de Persistencia (Core Logic)

### 4.1 `SourceCrawlerPersistenceService.java`
```java
@Service
@RequiredArgsConstructor
@Transactional
public class SourceCrawlerPersistenceService {

    private final SourceNovelRepository novelRepo;
    private final SourceVolumeRepository volumeRepo;
    private final SourceChapterRepository chapterRepo;
    private final SourceChapterContentRepository contentRepo;
    private final SourceTagRepository tagRepo;
    private final SourceNovelTagRepository novelTagRepo;
    private final SourceCrawlerMetaRepository metaRepo;
    private final SourceFileStorageService fileStorage;
    private final LanguageDetectionService langDetector;

    /** Upsert completo: novela + volúmenes + capítulos (igual que Python sync) */
    public SourceNovel upsertNovel(String sourceId, CrawlerNovelModel model, String crawlerVersion, String crawlerTier) {
        String urlHash = SourceNovel.computeUrlHash(sourceId, model.getUrl());
        
        SourceNovel novel = novelRepo.findBySourceIdAndNovelUrlHash(sourceId, urlHash)
                .orElseGet(() -> new SourceNovel());
        
        boolean isNew = novel.getId() == null;
        if (isNew) {
            novel.setSourceId(sourceId);
            novel.setNovelUrl(model.getUrl());
            novel.setNovelUrlHash(urlHash);
            novel.setCreatedAt(Instant.now());
        }
        
        // Actualizar metadatos
        novel.setTitle(model.getTitle());
        novel.setAuthor(model.getAuthor());
        novel.setCoverUrl(model.getCoverUrl());
        novel.setSynopsis(model.getSynopsis());
        novel.setLanguage(normalizeLanguage(model.getLanguage()));
        novel.setMtl(model.isMtl());
        novel.setManga(model.isManga());
        novel.setRtl(model.isRtl());
        novel.setStatus(model.getStatus());
        novel.setLastUpdate(model.getLastUpdate());
        novel.setTags(model.getTags() != null ? model.getTags() : List.of());
        novel.setCrawlerVersion(crawlerVersion);
        novel.setCrawlerTier(crawlerTier);
        novel.setExtra(mergeExtra(novel.getExtra(), model.getExtras(), crawlerVersion, crawlerTier));
        novel.setUpdatedAt(Instant.now());
        novel.setLastCrawledAt(Instant.now());
        
        // Detectar idioma si no viene del crawler
        if (novel.getLanguage() == null) {
            String sample = model.getTitle() + "\n" + (model.getSynopsis() != null ? model.getSynopsis() : "");
            novel.setLanguage(langDetector.detect(sample));
        }

        // Sync volúmenes
        List<SourceVolume> syncedVolumes = syncVolumes(novel, model.getVolumes(), crawlerVersion, crawlerTier);
        novel.setVolumes(syncedVolumes);
        novel.setVolumeCount(syncedVolumes.size());
        novel.setChapterCount(syncedVolumes.stream().mapToInt(SourceVolume::getChapterCount).sum());

        // Sync tags
        syncTags(novel, model.getTags());

        return novelRepo.save(novel);
    }

    private List<SourceVolume> syncVolumes(SourceNovel novel, List<CrawlerVolumeModel> crawlerVolumes, 
                                           String version, String tier) {
        Map<Integer, SourceVolume> existingBySerial = volumeRepo.findByNovelIdOrderBySerialAsc(novel.getId())
                .stream().collect(Collectors.toMap(SourceVolume::getSerial, v -> v));
        
        Set<Integer> wantedSerials = crawlerVolumes.stream().map(CrawlerVolumeModel::getId).collect(Collectors.toSet());
        Set<Integer> existingSerials = existingBySerial.keySet();

        // DELETE removidos
        existingSerials.stream()
            .filter(s -> !wantedSerials.contains(s))
            .map(existingBySerial::get)
            .forEach(volumeRepo::delete);

        // INSERT/UPDATE
        List<SourceVolume> result = new ArrayList<>();
        for (CrawlerVolumeModel cv : crawlerVolumes) {
            SourceVolume vol = existingBySerial.get(cv.getId());
            if (vol == null) {
                vol = new SourceVolume();
                vol.setNovel(novel);
                vol.setSerial(cv.getId());
                vol.setCreatedAt(Instant.now());
            }
            vol.setTitle(cv.getTitle());
            vol.setChapterCount(cv.getChapters() != null ? cv.getChapters().size() : 0);
            vol.setCrawlerVersion(version);
            vol.setCrawlerTier(tier);
            vol.setExtra(mergeExtra(vol.getExtra(), cv.getExtras(), version, tier));
            vol.setUpdatedAt(Instant.now());
            
            // Sync capítulos de este volumen
            List<SourceChapter> chapters = syncChapters(novel, vol, cv.getChapters(), version, tier);
            vol.setChapters(chapters);
            
            result.add(volumeRepo.save(vol));
        }
        return result;
    }

    private List<SourceChapter> syncChapters(SourceNovel novel, SourceVolume volume, 
                                             List<CrawlerChapterModel> crawlerChapters,
                                             String version, String tier) {
        if (crawlerChapters == null) return List.of();
        
        Map<Integer, SourceChapter> existingBySerial = chapterRepo.findByNovelIdOrderBySerialAsc(novel.getId())
                .stream().collect(Collectors.toMap(SourceChapter::getSerial, c -> c));
        
        Set<Integer> wantedSerials = crawlerChapters.stream().map(CrawlerChapterModel::getId).collect(Collectors.toSet());
        Set<Integer> existingSerials = existingBySerial.keySet();

        // DELETE removidos (y su contenido en filesystem)
        existingSerials.stream()
            .filter(s -> !wantedSerials.contains(s))
            .map(existingBySerial::get)
            .forEach(ch -> {
                contentRepo.findByChapterId(ch.getId()).ifPresent(cc -> {
                    fileStorage.delete(cc.getContentFile());
                    contentRepo.delete(cc);
                });
                chapterRepo.delete(ch);
            });

        // INSERT/UPDATE
        List<SourceChapter> result = new ArrayList<>();
        for (CrawlerChapterModel cc : crawlerChapters) {
            SourceChapter ch = existingBySerial.get(cc.getId());
            if (ch == null) {
                ch = new SourceChapter();
                ch.setNovel(novel);
                ch.setSerial(cc.getId());
                ch.setCreatedAt(Instant.now());
            }
            ch.setVolume(volume);
            ch.setTitle(cc.getTitle());
            ch.setUrl(cc.getUrl());
            ch.setCrawlerVersion(version);
            ch.setCrawlerTier(tier);
            ch.setExtra(mergeExtra(ch.getExtra(), cc.getExtras(), version, tier));
            ch.setUpdatedAt(Instant.now());
            
            result.add(chapterRepo.save(ch));
        }
        return result;
    }

    /** Guarda contenido de capítulo (comprimido GZIP en filesystem) */
    public SourceChapterContent saveChapterContent(UUID chapterId, String html, List<String> imageUrls, 
                                                   String extractor, String version, String tier) {
        SourceChapter chapter = chapterRepo.getReferenceById(chapterId);
        SourceNovel novel = chapter.getNovel();
        
        // Comprimir con GZIP (JDK nativo, sin dependencias externas)
        byte[] htmlBytes = html.getBytes(StandardCharsets.UTF_8);
        byte[] compressed = compressGzip(htmlBytes);
        String contentHash = Hashing.sha256().hashBytes(htmlBytes).toString();
        String relPath = String.format("sources/%s/novels/%s/chapters/%06d.gz", 
                novel.getSourceId(), novel.getId(), chapter.getSerial());
        
        fileStorage.save(relPath, compressed);
        
        SourceChapterContent content = contentRepo.findByChapterId(chapterId)
                .orElseGet(SourceChapterContent::new);
        content.setChapter(chapter);
        content.setContentFile(relPath);
        content.setContentHash(contentHash);
        content.setCompressedSize(compressed.length);
        content.setOriginalSize(htmlBytes.length);
        content.setCompression("gzip");
        content.setExtractor(extractor);
        content.setCrawlerVersion(version);
        content.setCrawlerTier(tier);
        content.setImages(extractImageInfo(imageUrls, novel, chapter));
        content.setUpdatedAt(Instant.now());
        
        chapter.setDone(true);
        chapter.setLastFetchedAt(Instant.now());
        chapterRepo.save(chapter);
        
        return contentRepo.save(content);
    }

    /** Comprime con GZIP usando JDK nativo (java.util.zip) */
    private byte[] compressGzip(byte[] input) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             GZIPOutputStream gzos = new GZIPOutputStream(bos)) {
            gzos.write(input);
            gzos.finish();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("GZIP compression failed", e);
        }
    }

    /** Descomprime GZIP */
    private String decompressGzip(byte[] compressed) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(compressed);
             GZIPInputStream gis = new GZIPInputStream(bis);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            gis.transferTo(bos);
            return bos.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("GZIP decompression failed", e);
        }
    }

    private List<SourceChapterContent.ChapterImageInfo> extractImageInfo(List<String> urls, SourceNovel novel, SourceChapter chapter) {
        // Descargar imágenes y guardar en filesystem, retornar metadatos
        // Implementación similar a Python's ChapterImageService
    }

    private String normalizeLanguage(String lang) { ... }
    private Map<String, Object> mergeExtra(Map<String, Object> existing, Map<String, Object> incoming, 
                                           String version, String tier) { ... }
    private void syncTags(SourceNovel novel, List<String> tags) { ... }
}
```

### 4.2 `SourceFileStorageService.java`
```java
@Service
@RequiredArgsConstructor
public class SourceFileStorageService {
    @Value("${app.sources.storage.root:/data/sources}")
    private Path storageRoot;

    public Path resolve(String relativePath) {
        return storageRoot.resolve(relativePath).normalize();
    }

    public void save(String relativePath, byte[] content) {
        Path file = resolve(relativePath);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, content);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public byte[] load(String relativePath) {
        return Files.readAllBytes(resolve(relativePath));
    }

    public String loadText(String relativePath) {
        byte[] data = load(relativePath);
        return decompressGzip(data);
    }

    /** Descomprime GZIP (detecta por magic bytes 0x1f 0x8b) */
    private String decompressGzip(byte[] compressed) {
        if (compressed.length >= 2 && compressed[0] == (byte)0x1f && compressed[1] == (byte)0x8b) {
            try (ByteArrayInputStream bis = new ByteArrayInputStream(compressed);
                 GZIPInputStream gis = new GZIPInputStream(bis);
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                gis.transferTo(bos);
                return bos.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("GZIP decompression failed", e);
            }
        }
        // Fallback: no comprimido (compatibilidad hacia atrás)
        return new String(compressed, StandardCharsets.UTF_8);
    }

    public void delete(String relativePath) {
        Files.deleteIfExists(resolve(relativePath));
    }

    public boolean exists(String relativePath) {
        return Files.exists(resolve(relativePath));
    }
}
```

---

## 5. Integración en `AbstractGeneratedCrawler`

```java
@Component
public abstract class AbstractGeneratedCrawler implements SourceCrawler {

    @Autowired protected SourceCrawlerPersistenceService persistence;
    @Autowired protected SourceFileStorageService fileStorage;

    @Override
    public List<NovelSearchResult> search(String query) {
        // 1. Si query vacío → listar de BD (paginado)
        if (query == null || query.isBlank()) {
            return persistence.findAllBySource(getSourceId(), 0, 50)  // Pageable
                    .stream().map(this::toSearchResult).toList();
        }

        // 2. Buscar en BD primero (full-text en title/author/synopsis)
        List<SourceNovel> cached = persistence.searchBySource(getSourceId(), query, PageRequest.of(0, 50));
        if (!cached.isEmpty()) {
            return cached.stream().map(this::toSearchResult).toList();
        }

        // 3. Fallback: HTTP crawl + persistir
        String searchUrl = buildSearchUrl(query);
        if (searchUrl == null) return List.of();

        String html = webClient.get().uri(searchUrl).retrieve().bodyToMono(String.class).block();
        Document doc = Jsoup.parse(html);
        List<SearchResult> results = parseSearchResults(doc, query);

        // 4. Para cada resultado, obtener/persistir detalle completo
        return results.stream().map(r -> {
            SourceNovel novel = persistence.getOrFetchNovel(getSourceId(), r.getUrl(), this);
            return toSearchResult(novel);
        }).toList();
    }

    @Override
    public NovelDetail getNovelDetails(String novelUrl) {
        String urlHash = SourceNovel.computeUrlHash(getSourceId(), novelUrl);
        return persistence.findBySourceIdAndNovelUrlHash(getSourceId(), urlHash)
                .map(this::toNovelDetail)
                .orElseGet(() -> {
                    // Crawl + persist
                    CrawlerNovelModel model = crawlNovelDetails(novelUrl);
                    SourceNovel novel = persistence.upsertNovel(getSourceId(), model, getVersion(), getTier());
                    return toNovelDetail(novel);
                });
    }

    @Override
    public List<ChapterInfo> getChapters(String novelUrl) {
        String urlHash = SourceNovel.computeUrlHash(getSourceId(), novelUrl);
        SourceNovel novel = persistence.findBySourceIdAndNovelUrlHash(getSourceId(), urlHash)
                .orElseThrow(() -> new CrawlerException(getSourceId(), "getChapters", "Novel not found: " + novelUrl));
        
        return novel.getVolumes().stream()
                .flatMap(v -> v.getChapters().stream()
                        .map(c -> new ChapterInfo(c.getSerial(), c.getTitle(), c.getUrl(), v.getSerial())))
                .toList();
    }

    @Override
    public ChapterContent getChapterContent(String chapterUrl) {
        // Buscar capítulo por URL en BD
        SourceChapter chapter = persistence.findChapterByUrl(getSourceId(), chapterUrl)
                .orElseGet(() -> {
                    // Crawl + persist
                    SourceNovel novel = getOrFetchNovelForChapter(chapterUrl);
                    return crawlAndPersistChapter(novel, chapterUrl);
                });

        // Verificar caché de contenido
        SourceChapterContent content = contentRepo.findByChapterId(chapter.getId()).orElse(null);
        boolean stale = content == null || isStale(content, getVersion(), getTier());

        if (!stale && fileStorage.exists(content.getContentFile())) {
            String html = fileStorage.loadText(content.getContentFile());
            return ChapterContent.success(html, content.getImages().stream().map(ChapterImageInfo::getUrl).toList());
        }

        // Re-crawl
        return crawlAndSaveChapterContent(chapter);
    }
}
```

---

## 6. Nuevos Endpoints API

```java
@RestController
@RequestMapping("/api/sources")
@RequiredArgsConstructor
public class SourceCatalogController {

    private final SourceNovelRepository novelRepo;

    @GetMapping("/{sourceId}/novels")
    public PageResponse<SourceNovelDto> listNovels(
            @PathVariable String sourceId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "updated") String sort) {  // updated | popularity | title | chapters
        
        Pageable pageable = buildPageable(page, size, sort);
        Page<SourceNovel> result;
        
        if (search != null && !search.isBlank()) {
            result = novelRepo.searchBySource(sourceId, search, pageable);
        } else if (tag != null) {
            result = novelRepo.findBySourceAndTag(sourceId, tag, pageable);
        } else if ("popularity".equals(sort)) {
            result = novelRepo.findPopularBySource(sourceId, pageable);
        } else {
            result = novelRepo.findBySourceIdOrderByUpdatedAtDesc(sourceId, pageable);
        }
        
        return PageResponse.from(result.map(this::toDto));
    }

    @GetMapping("/{sourceId}/novels/{novelId}")
    public ResponseEntity<NovelDetailDto> getNovel(
            @PathVariable String sourceId,
            @PathVariable UUID novelId) {
        return novelRepo.findById(novelId)
                .filter(n -> n.getSourceId().equals(sourceId))
                .map(this::toDetailDto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{sourceId}/novels/{novelId}/volumes")
    public List<VolumeDto> getVolumes(@PathVariable String sourceId, @PathVariable UUID novelId) { ... }

    @GetMapping("/{sourceId}/novels/{novelId}/chapters")
    public List<ChapterDto> getChapters(@PathVariable String sourceId, @PathVariable UUID novelId) { ... }

    @GetMapping("/{sourceId}/novels/{novelId}/chapters/{chapterSerial}/content")
    public ResponseEntity<ChapterContentDto> getChapterContent(
            @PathVariable String sourceId,
            @PathVariable UUID novelId,
            @PathVariable int chapterSerial) { ... }
}
```

---

## 7. Migración Flyway

```sql
-- V2025_01_15_001__create_source_crawler_schema.sql

-- 1. source_crawler_meta (primero, sin FK)
CREATE TABLE source_crawler_meta (...);

-- 2. source_tag (independiente)
CREATE TABLE source_tag (...);

-- 3. source_novel (FK a source_crawler_meta implícita por source_id)
CREATE TABLE source_novel (...);

-- 4. source_volume (FK a source_novel)
CREATE TABLE source_volume (...);

-- 5. source_chapter (FK a source_novel, source_volume)
CREATE TABLE source_chapter (...);

-- 6. source_chapter_content (FK a source_chapter)
CREATE TABLE source_chapter_content (...);

-- 7. Tablas de traducción
CREATE TABLE source_novel_translation (...);
CREATE TABLE source_volume_translation (...);
CREATE TABLE source_chapter_translation (...);

-- 8. Glosarios
CREATE TABLE source_novel_glossary (...);

-- 9. Tags many-to-many
CREATE TABLE source_novel_tag (...);
```

---

## 8. Configuración (application.yml)

```yaml
app:
  sources:
    storage:
      root: /data/sources          # Raíz para archivos (covers, chapters, images)
    persistence:
      enabled: true                # Feature flag
      auto-sync-popular: true      # Sync automático de novelas populares
      sync-interval-hours: 24
    crawler:
      default-timeout-seconds: 30
      max-retries: 3
      user-agent: "FrontendService/1.0 (+https://github.com/...)"
    compression:
      algorithm: gzip              # 'gzip' | 'lzma' (futuro)
      level: 6                     # 1-9 (6 = balance default)

spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          lob:
            non_contextual_creation: true
```

---

## 9. Testing Strategy

| Componente | Test Type | Cobertura |
|------------|-----------|-----------|
| `SourceCrawlerPersistenceService` | Unit + Integration | Upsert idempotente, delete cascade, merge extra |
| `SourceFileStorageService` | Unit | **GZIP compress/decompress**, atomic write, cleanup, magic bytes detection |
| `AbstractGeneratedCrawler` | Integration (Testcontainers) | Search → persist → retrieve, stale detection |
| `SourceCatalogController` | @WebMvcTest | Paginación, filtros, sorting, 404s |
| Repositories | @DataJpaTest | Queries custom, índices |

---

## 10. Checklist de Implementación

### Fase 1: Esquema y Entidades (Días 1-3)
- [ ] Flyway migration V2025_01_15_001
- [ ] 10 Entidades JPA en `model.source`
- [ ] 8 Repositorios
- [ ] Configuración `application.yml`

### Fase 2: Servicios Core (Días 4-8)
- [ ] `SourceCrawlerPersistenceService` con upsert-by-serial
- [ ] `SourceFileStorageService` con **GZIP** (JDK nativo, sin dependencias)
- [ ] `LanguageDetectionService` wrapper
- [ ] `SourceCrawlerMetaRepository` + cache de versión

### Fase 3: Integración Crawlers (Días 9-12)
- [ ] Modificar `AbstractGeneratedCrawler`:
  - [ ] `search()` → BD first, HTTP fallback + persist
  - [ ] `getNovelDetails()` → BD first, HTTP fallback + persist
  - [ ] `getChapters()` → BD join volumes+chapters
  - [ ] `getChapterContent()` → Filesystem cache + stale check
- [ ] Eliminar parsing duplicado en crawlers concretos (delegar a persistencia)

### Fase 4: API Catálogo (Días 13-15)
- [ ] `SourceCatalogController` con endpoints paginados
- [ ] DTOs: `SourceNovelDto`, `VolumeDto`, `ChapterDto`, `ChapterContentDto`
- [ ] Búsqueda full-text + filtros por tag + sorting

### Fase 5: Features Avanzadas (Días 16-22)
- [ ] Traducciones: entidades + servicio + API
- [ ] Glosarios: CRUD + aplicación en traducción
- [ ] Popularidad: contador + endpoint `/popular`
- [ ] Versionado crawler: invalidación automática de caché
- [ ] Scheduler: sync periódico de novelas populares

---

## 11. Separación Estricta Elscione vs Genéricos

| Capa | Elscione (Intacto) | Genéricos (Nuevo) |
|------|---------------------|-------------------|
| Entidades | `Novel`, `NovelVolume`, `NovelVolumeFile`, `Book`, `Chunk` | `SourceNovel`, `SourceVolume`, `SourceChapter`, `SourceChapterContent` |
| Tablas | `novels`, `novel_volumes`, `novel_volume_files`, `books`, `chunks` | `source_novel`, `source_volume`, `source_chapter`, `source_chapter_content` |
| Repos | `NovelRepository`, `NovelVolumeRepository`... | `SourceNovelRepository`, `SourceVolumeRepository`... |
| Servicios | `NovelsCatalogSyncService`, `NovelsCatalogQueryService` | `SourceCrawlerPersistenceService`, `SourceCatalogService` |
| Controladores | `NovelsManagerController` | `SourceCatalogController` |
| Pipeline | `BookPipelineOrchestrator` → Split/Translate/Audio | **(Fase 5)** Extender `Book`/`Chunk` para sources genéricos |

**Regla de oro**: Ninguna clase en `com.glez.frontendservice.model` (Elscione) referencia a `model.source` y viceversa. Dos mundos separados que solo convergen en la UI (merged search results).

---

## 12. Próximos Pasos Inmediatos

1. **Aprobar este plan** → Crear branch `feature/source-crawler-persistence`
2. **Ejecutar Flyway** en dev → Verificar migración limpia
3. **Implementar Fase 1** completa antes de tocar crawlers
4. **Test de integración** con 2-3 crawlers reales (readnovelfull, novelupdates, foxaholic)
5. **Code review** antes de Fase 2

---

**¿Autorizas el inicio de la Fase 1?**