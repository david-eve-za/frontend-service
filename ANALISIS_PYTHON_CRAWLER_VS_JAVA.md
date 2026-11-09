# Análisis Comparativo: lightnovel-crawler (Python) vs frontend-service (Java)

## Resumen Ejecutivo

Se han identificado **dos modelos de almacenamiento completamente diferentes** en la aplicación Java:

1. **Elscione** → Persistencia completa JPA/Hibernate con pipeline de procesamiento (split, traducir, audio)
2. **Resto de fuentes (70+ crawlers generados desde Python)** → Solo crawling on-demand, **SIN persistencia**

El objetivo es unificar el modelo para que **todas las fuentes** tengan capacidades equivalentes de almacenamiento y procesamiento.

---

## 1. Arquitectura de lightnovel-crawler (Python)

### 1.1 Modelo de Datos (SQLModel/SQLAlchemy)

```python
# Tablas principales
novels                  # Obra principal
volumes                 # Volúmenes por novela
chapters                # Capítulos por novela
novel_translations      # Traducciones de metadatos (título, autor, sinopsis)
volume_translations     # Traducciones de títulos de volumen
chapter_translations    # Traducciones de capítulos (título + contenido)
novel_glossaries        # Glosarios de términos por idioma
tags / novel_tags       # Etiquetas/géneros (many-to-many)
```

### 1.2 Tabla `novels` - Campos Clave

| Campo | Tipo | Descripción |
|-------|------|-------------|
| `id` | UUID (PK) | Identificador único |
| `domain` | VARCHAR | Dominio de origen (ej. "readnovelfull.com") |
| `url` | VARCHAR (UNIQUE) | URL completa de la novela |
| `title` | VARCHAR | Título |
| `authors` | VARCHAR | Autores (comma-separated) |
| `synopsis` | TEXT | Sinopsis |
| `tags` | JSON | Lista de etiquetas/géneros |
| `cover_url` | VARCHAR | URL de portada original |
| `mtl` | BOOLEAN | Si es traducción automática |
| `rtl` | BOOLEAN | Si es right-to-left |
| `manga` | BOOLEAN | Si es manga/manhwa |
| `language` | CHAR(2) | ISO 639-1 (ej. "en", "es") |
| `volume_count` | INT | Número de volúmenes |
| `chapter_count` | INT | Número de capítulos |
| `popularity` | BIGINT | Visitas acumuladas |
| `extra` | JSON | Metadatos extra del crawler + versionado |
| `created_at/updated_at` | TIMESTAMP | Timestamps |

### 1.3 Almacenamiento de Contenido (Filesystem)

```
novels/
  {novel_id}/
    cover.jpg                    # Portada descargada
    chapters/
      {serial:06}.zst           # Contenido original comprimido (zstd)
      {serial:06}.{lang}.zst    # Traducción comprimida por idioma
```

**Ventajas:**
- Contenido fuera de la BD (evita CLOBs gigantes)
- Compresión zstd (ratio ~10:1 para texto)
- Hash SHA256 para invalidación de caché de traducciones
- Escalable a almacenamiento objeto (S3, etc.)

### 1.4 Flujo de Crawling y Persistencia

```python
CrawlerService.fetch_novel(url):
  1. crawler.read_novel(model)     # Extrae metadatos + TOC
  2. crawler.format_novel(model)   # Normaliza
  3. Busca/crea Novel en BD por URL
  4. Actualiza campos: title, authors, cover_url, synopsis, tags, etc.
  5. Detecta idioma (title + synopsis)
  6. Sync volumes (upsert por serial)
  7. Sync chapters (upsert por serial, mapea volume_id)
  8. Descarga portada → novels/{id}/cover.jpg
  9. Actualiza recommendation index
```

### 1.5 Servicios de Sincronización (Upsert Inteligente)

```python
VolumeService.sync(novel_id, crawler_volumes):
  - Compara serial numbers (clave de negocio)
  - INSERT nuevos, UPDATE existentes, DELETE removidos
  - Preserva is_done y extra de capítulos existentes

ChapterService.sync(novel_id, crawler_chapters):
  - Mapea volume_serial → volume_id (FK)
  - Upsert por serial
  - Elimina archivos de contenido de capítulos borrados
```

### 1.6 Características Avanzadas

| Característica | Implementación |
|----------------|----------------|
| **Traducciones** | Tablas separadas (novel/volume/chapter)_translations con FK cascade |
| **Glosarios** | novel_glossaries (JSON terms por idioma) |
| **Popularidad** | Contador BIGINT actualizado desde actividad de lectura |
| **Detección idioma** | Automática (title + synopsis + chapter body) |
| **Versionado crawler** | Campo `extra` con stamp(version, tier) para invalidación |
| **Reintentos vacíos** | Contador `empty_attempts` en chapter.extra (max 3) |

---

## 2. Arquitectura Actual frontend-service (Java)

### 2.1 Modelo Elscione (Persistente)

```java
@Entity Novel {
  UUID id
  String remotePath (UNIQUE)  // href percent-encoded
  String title
  String category
  Instant lastSyncedAt
  boolean listingComplete
  List<NovelVolume> volumes
  // + Anilist enrichment fields (11 campos)
}

@Entity NovelVolume {
  UUID id
  Novel novel
  Integer volumeNumber
  String label
  String translatorGroup
  String remoteDir
  String sourceKey (UNIQUE con novel_id)  // "vol:N" o "file:..."
  boolean removedFromSource
  Book book  // Pipeline link
  List<NovelVolumeFile> files
}

@Entity Book {  // Pipeline de procesamiento
  UUID id
  String fullText (CLOB)
  Chunk[] chunks  // Split, translate, audio status
}
```

### 2.2 Modelo Crawlers Generados (NO Persistente)

```java
// Solo DTOs de respuesta - NO entidades JPA
record NovelSearchResult(title, url, info, coverUrl, sourceNovelId)
record NovelDetail(title, author, coverUrl, synopsis, tags, volumes, language, isMtl, isManga, sourceNovelId, status, lastUpdate)
record VolumeInfo(id, title, chapters)
record ChapterInfo(id, title, url, volumeId)
record ChapterContent(html, images, success, errorMessage)
```

**Flujo actual:**
```
GET /api/sources/{sourceId}/search?q=...
  → SourceController.search()
    → SourceCrawler.search(query)
      → AbstractGeneratedCrawler.search()
        → HTTP request → parse HTML → return List<NovelSearchResult>
```
**No hay persistencia en BD** - cada petición re-crawlea.

### 2.3 Diferencias Críticas

| Aspecto | Elscione (Java) | Otros Crawlers (Java) | Python lightnovel-crawler |
|---------|-----------------|----------------------|---------------------------|
| **Persistencia metadatos** | ✅ Completa (Novel, Volume, File) | ❌ Ninguna | ✅ Completa (novels, volumes, chapters) |
| **Contenido capítulos** | ✅ Via Book.fullText (CLOB) | ❌ Solo on-demand | ✅ Archivos comprimidos (zstd) |
| **Portadas** | ✅ NovelVolumeFile | ❌ Solo URL | ✅ Archivo local (cover.jpg) |
| **Traducciones** | ✅ Pipeline Book→Chunks | ❌ No | ✅ Tablas dedicadas |
| **Glosarios** | ✅ GlossaryService | ❌ No | ✅ novel_glossaries |
| **Tags/Géneros** | ❌ Solo Anilist | ❌ No | ✅ tags + novel_tags |
| **Popularidad** | ❌ No | ❌ No | ✅ Contador BIGINT |
| **Detección idioma** | ❌ No | ❌ No | ✅ Automática |
| **Versionado crawler** | ❌ No | ❌ No | ✅ extra.stamp() |
| **Idempotencia** | ✅ sourceKey | ❌ No | ✅ Upsert por serial |

---

## 3. Problemas Identificados

### 3.1 Inconsistencia de Experiencia de Usuario
- **Elscione**: Catálogo local rápido, búsqueda instantánea, pipeline completo
- **Otras fuentes**: Búsqueda lenta (HTTP cada vez), sin historial, sin procesamiento

### 3.2 Duplicación de Esfuerzo
- 70+ crawlers generados replican lógica de parsing que ya existe en Python
- Cada petición HTTP re-descarga metadatos que podrían estar en caché local

### 3.3 Escalabilidad
- Sin persistencia, no se puede hacer recomendaciones, popularidad, historial de lectura
- Cada usuario que ve la misma novela genera petición HTTP al origen

### 3.4 Mantenibilidad
- Dos stacks de parsing: Java (Elscione) + Python-generado (resto)
- Bugs en selectores HTML requieren regenerar crawlers Java

---

## 4. Propuesta de Unificación

### Opción A: Persistencia Unificada (Recomendada)
Extender el modelo JPA actual para soportar **todas las fuentes** con el mismo esquema que Elscione, adaptando conceptos de Python.

### Opción B: Delegar a Python (Microservicio)
Levantar lightnovel-crawler como servicio y que Java lo consuma via REST/gRPC.

### Opción C: Híbrida
Persistencia ligera en Java (metadatos + TOC) + contenido on-demand + Python para procesamiento pesado.

---

## 5. Plan de Implementación (Opción A - Recomendada)

### Fase 1: Esquema de BD Unificado
- [ ] Nueva entidad `SourceNovel` (generaliza `Novel` de Elscione)
- [ ] Nueva entidad `SourceVolume` (generaliza `NovelVolume`)
- [ ] Nueva entidad `SourceChapter` (metadatos + archivo contenido)
- [ ] Migración de datos Elscione existentes
- [ ] Índices: domain, url(unique), title, language, popularity

### Fase 2: Servicio de Persistencia Genérico
- [ ] `SourceCrawlerPersistenceService` con upsert por serial (como Python)
- [ ] Almacenamiento de contenido en filesystem (zstd)
- [ ] Descarga de portadas
- [ ] Detección de idioma automática

### Fase 3: Integración en Crawlers
- [ ] Modificar `AbstractGeneratedCrawler` para persistir tras fetch
- [ ] Search usa BD local + fallback a HTTP
- [ ] Detail usa BD local + fallback a HTTP

### Fase 4: Pipeline de Procesamiento Unificado
- [ ] Extender `Book`/`Chunk` para fuentes genéricas
- [ ] Split/Translate/Audio para cualquier fuente
- [ ] Glosarios por novela/fuente

### Fase 5: Features Avanzadas (Paridad Python)
- [ ] Traducciones (metadatos + capítulos)
- [ ] Glosarios
- [ ] Popularidad / Recomendaciones
- [ ] Versionado crawler para invalidación de caché

---

## 6. Estimación de Esfuerzo

| Fase | Dificultad | Tiempo Estimado |
|------|------------|-----------------|
| 1. Esquema BD | Media | 2-3 días |
| 2. Servicio Persistencia | Alta | 3-5 días |
| 3. Integración Crawlers | Media | 2-3 días |
| 4. Pipeline Unificado | Alta | 5-7 días |
| 5. Features Avanzadas | Media | 3-5 días |
| **Total** | | **15-23 días** |

---

## 7. Decisiones Técnicas Pendientes

1. **¿Mantener dualidad Elscione/Generados o migración completa?**
   - Migración completa = breaking changes en API actual
   - Dualidad = mantener dos caminos de código

2. **¿Filesystem local vs S3/MinIO para contenido?**
   - Python usa filesystem local con paths deterministas
   - Java podría necesitar almacenamiento distribuido

3. **¿Compresión zstd en Java?**
   - Requiere librería nativa (zstd-jni) o pure Java (lento)

4. **¿Traducciones en BD o archivos?**
   - Python: archivos + metadatos en BD
   - Java actual: todo en BD (Chunks con translatedText)

5. **¿Sincronización incremental vs completa?**
   - Python: sync por serial numbers
   - Elscione: sync por sourceKey + listingComplete flags

---

## 8. Conclusión

El **gap principal** es que 70+ fuentes funcionan como "catálogo remoto on-demand" mientras Elscione es "catálogo local con pipeline". La unificación requiere:

1. **Esquema BD genérico** que sustituya al específico de Elscione
2. **Capa de persistencia** en `AbstractGeneratedCrawler` (upsert por serial)
3. **Almacenamiento de contenido** fuera de BD (filesystem + compresión)
4. **Pipeline unificado** Book/Chunk para todas las fuentes

**Recomendación**: Implementar Opción A por fases, starting con persistencia de metadatos + TOC (Fases 1-3), que ya aporta 80% del valor (búsqueda instantánea, historial, offline).