package com.glez.frontendservice.services.source;

import com.glez.frontendservice.model.source.*;
import com.glez.frontendservice.model.source.dto.*;
import com.glez.frontendservice.repository.source.*;
import com.google.common.hash.Hashing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Servicio de persistencia para crawlers genéricos.
 * Implementa upsert idempotente por serial numbers (como Python lightnovel-crawler).
 * Maneja: novela → volúmenes → capítulos → contenido (GZIP filesystem).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SourceCrawlerPersistenceService {

    private final SourceNovelRepository novelRepo;
    private final SourceVolumeRepository volumeRepo;
    private final SourceChapterRepository chapterRepo;
    private final SourceChapterContentRepository contentRepo;
    private final SourceTagRepository tagRepo;
    private final SourceNovelTranslationRepository novelTranslationRepo;
    private final SourceVolumeTranslationRepository volumeTranslationRepo;
    private final SourceChapterTranslationRepository chapterTranslationRepo;
    private final SourceNovelGlossaryRepository glossaryRepo;
    private final SourceCrawlerMetaRepository metaRepo;
    private final SourceFileStorageService fileStorage;
    private final LanguageDetectionService langDetector;

    // ============================================================
    // UPSERT PRINCIPAL: Novela + Volúmenes + Capítulos
    // ============================================================

    /**
     * Persiste o actualiza novela completa con volúmenes y capítulos.
     * Idempotente: mismo source_id + novel_url_hash = misma novela.
     */
    public SourceNovel upsertNovel(String sourceId, CrawlerNovelModel model, String crawlerVersion, String crawlerTier) {
        String urlHash = SourceNovel.computeUrlHash(sourceId, model.getUrl());

        SourceNovel novel = novelRepo.findBySourceIdAndNovelUrlHash(sourceId, urlHash)
                .orElseGet(SourceNovel::new);

        boolean isNew = novel.getId() == null;
        if (isNew) {
            novel.setSourceId(sourceId);
            novel.setNovelUrl(model.getUrl());
            novel.setNovelUrlHash(urlHash);
        }

        // Actualizar metadatos básicos
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
        novel.setTags(model.getTags() != null ? new ArrayList<>(model.getTags()) : new ArrayList<>());
        novel.setCrawlerVersion(crawlerVersion);
        novel.setCrawlerTier(crawlerTier);
        novel.setExtra(mergeExtra(novel.getExtra(), model.getExtras(), crawlerVersion, crawlerTier));
        novel.setLastCrawledAt(Instant.now());

        // Detectar idioma si no viene del crawler
        if (novel.getLanguage() == null) {
            String sample = model.getTitle() + "\n" + (model.getSynopsis() != null ? model.getSynopsis() : "");
            novel.setLanguage(langDetector.detect(sample));
        }

        // Sync volúmenes (upsert por serial)
        List<SourceVolume> syncedVolumes = syncVolumes(novel, model.getVolumes(), crawlerVersion, crawlerTier);
        novel.setVolumes(syncedVolumes);
        novel.setVolumeCount(syncedVolumes.size());
        novel.setChapterCount(syncedVolumes.stream().mapToInt(SourceVolume::getChapterCount).sum());

        // Sync tags
        syncTags(novel, model.getTags());

        SourceNovel saved = novelRepo.save(novel);
        log.debug("Upserted novel: {} (source={}, volumes={}, chapters={})",
                saved.getTitle(), sourceId, saved.getVolumeCount(), saved.getChapterCount());
        return saved;
    }

    private List<SourceVolume> syncVolumes(SourceNovel novel, List<CrawlerVolumeModel> crawlerVolumes,
                                           String version, String tier) {
        if (crawlerVolumes == null) {
            crawlerVolumes = List.of();
        }

        Map<Integer, SourceVolume> existingBySerial = volumeRepo.findByNovelIdOrderBySerialAsc(novel.getId())
                .stream().collect(Collectors.toMap(SourceVolume::getSerial, v -> v));

        Set<Integer> wantedSerials = crawlerVolumes.stream().map(CrawlerVolumeModel::getId).collect(Collectors.toSet());
        Set<Integer> existingSerials = existingBySerial.keySet();

        // DELETE volúmenes removidos (cascade borra capítulos y contenido)
        existingSerials.stream()
                .filter(s -> !wantedSerials.contains(s))
                .map(existingBySerial::get)
                .forEach(volumeRepo::delete);

        // INSERT/UPDATE volúmenes
        List<SourceVolume> result = new ArrayList<>();
        for (CrawlerVolumeModel cv : crawlerVolumes) {
            SourceVolume vol = existingBySerial.get(cv.getId());
            if (vol == null) {
                vol = new SourceVolume();
                vol.setNovel(novel);
                vol.setSerial(cv.getId());
            }
            vol.setTitle(cv.getTitle() != null ? cv.getTitle() : "");
            vol.setChapterCount(cv.getChapters() != null ? cv.getChapters().size() : 0);
            vol.setCrawlerVersion(version);
            vol.setCrawlerTier(tier);
            vol.setExtra(mergeExtra(vol.getExtra(), cv.getExtras(), version, tier));

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

        // DELETE capítulos removidos (y su contenido en filesystem)
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

        // INSERT/UPDATE capítulos
        List<SourceChapter> result = new ArrayList<>();
        for (CrawlerChapterModel cc : crawlerChapters) {
            SourceChapter ch = existingBySerial.get(cc.getId());
            if (ch == null) {
                ch = new SourceChapter();
                ch.setNovel(novel);
                ch.setSerial(cc.getId());
            }
            ch.setVolume(volume);
            ch.setTitle(cc.getTitle() != null ? cc.getTitle() : "");
            ch.setUrl(cc.getUrl());
            ch.setCrawlerVersion(version);
            ch.setCrawlerTier(tier);
            ch.setExtra(mergeExtra(ch.getExtra(), cc.getExtras(), version, tier));

            result.add(chapterRepo.save(ch));
        }
        return result;
    }

    // ============================================================
    // CONTENIDO DE CAPÍTULO (GZIP filesystem)
    // ============================================================

    /**
     * Guarda contenido HTML de capítulo comprimido en GZIP.
     * Retorna metadatos guardados (path, hash, tamaños).
     */
    public SourceChapterContent saveChapterContent(UUID chapterId, String html, List<String> imageUrls,
                                                   String extractor, String version, String tier) {
        SourceChapter chapter = chapterRepo.getReferenceById(chapterId);
        SourceNovel novel = chapter.getNovel();

        byte[] htmlBytes = html.getBytes(StandardCharsets.UTF_8);
        byte[] compressed = SourceFileStorageService.compressGzip(htmlBytes);
        String contentHash = Hashing.sha256().hashBytes(htmlBytes).toString();
        String relPath = SourceFileStorageService.buildChapterContentPath(novel.getSourceId(), novel.getId(), chapter.getSerial());

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

    /**
     * Carga contenido de capítulo desde filesystem (descomprime GZIP).
     */
    public String loadChapterContent(UUID chapterId) {
        return contentRepo.findByChapterId(chapterId)
                .map(cc -> fileStorage.loadText(cc.getContentFile()))
                .orElse(null);
    }

    /**
     * Verifica si el contenido cacheado está obsoleto (crawler version/tier cambió).
     */
    public boolean isStale(SourceChapterContent content, String currentVersion, String currentTier) {
        if (content == null) return true;
        return !Objects.equals(content.getCrawlerVersion(), currentVersion) ||
               !Objects.equals(content.getCrawlerTier(), currentTier);
    }

    private List<SourceChapterContent.ChapterImageInfo> extractImageInfo(List<String> urls, SourceNovel novel, SourceChapter chapter) {
        // TODO: Implementar descarga de imágenes y guardado en filesystem
        // Por ahora retornamos metadatos básicos
        if (urls == null) return List.of();
        return urls.stream().map(url -> {
            SourceChapterContent.ChapterImageInfo info = new SourceChapterContent.ChapterImageInfo();
            info.setUrl(url);
            // TODO: descargar y guardar imagen, setear localPath
            return info;
        }).toList();
    }

    // ============================================================
    // CONSULTAS DE LECTURA
    // ============================================================

    public Optional<SourceNovel> findBySourceIdAndNovelUrlHash(String sourceId, String urlHash) {
        return novelRepo.findBySourceIdAndNovelUrlHash(sourceId, urlHash);
    }

    public Optional<SourceNovel> findBySourceIdAndNovelUrl(String sourceId, String novelUrl) {
        return novelRepo.findBySourceIdAndNovelUrl(sourceId, novelUrl);
    }

    public List<SourceNovel> searchBySource(String sourceId, String search, int page, int size) {
        return novelRepo.searchBySource(sourceId, search, org.springframework.data.domain.PageRequest.of(page, size)).getContent();
    }

    public List<SourceNovel> findBySourceAndTag(String sourceId, String tag, int page, int size) {
        return novelRepo.findBySourceAndTag(sourceId, tag, org.springframework.data.domain.PageRequest.of(page, size)).getContent();
    }

    public List<SourceNovel> findPopularBySource(String sourceId, int page, int size) {
        return novelRepo.findPopularBySource(sourceId, org.springframework.data.domain.PageRequest.of(page, size)).getContent();
    }

    public List<SourceNovel> findAllBySource(String sourceId, int page, int size) {
        return novelRepo.findBySourceIdOrderByUpdatedAtDesc(sourceId, org.springframework.data.domain.PageRequest.of(page, size)).getContent();
    }

    public Optional<SourceChapter> findChapterByUrl(String sourceId, String chapterUrl) {
        // Buscar novela primero por URL base, luego capítulo por URL
        // Implementación simplificada: buscar en todos los capítulos de la fuente
        return novelRepo.findBySourceId(sourceId).stream()
                .flatMap(n -> chapterRepo.findByNovelIdOrderBySerialAsc(n.getId()).stream())
                .filter(c -> Objects.equals(c.getUrl(), chapterUrl))
                .findFirst();
    }

    // ============================================================
    // TAGS
    // ============================================================

    private void syncTags(SourceNovel novel, List<String> crawlerTags) {
        if (crawlerTags == null || crawlerTags.isEmpty()) return;

        Set<String> normalizedCrawlerTags = crawlerTags.stream()
                .map(SourceTag::normalize)
                .collect(Collectors.toSet());

        // Buscar/crear tags en BD
        List<SourceTag> existingTags = tagRepo.findByNormalizedNameInAndSourceId(
                new ArrayList<>(normalizedCrawlerTags), novel.getSourceId());
        Map<String, SourceTag> existingByNorm = existingTags.stream()
                .collect(Collectors.toMap(SourceTag::getNormalizedName, t -> t));

        List<SourceTag> tagsToAssign = new ArrayList<>();
        for (String norm : normalizedCrawlerTags) {
            SourceTag tag = existingByNorm.get(norm);
            if (tag == null) {
                tag = new SourceTag();
                tag.setName(crawlerTags.stream().filter(t -> SourceTag.normalize(t).equals(norm)).findFirst().orElse(norm));
                tag.setNormalizedName(norm);
                tag.setSourceId(novel.getSourceId());
                tag = tagRepo.save(tag);
            }
            tag.setUsageCount(tag.getUsageCount() + 1);
            tagsToAssign.add(tag);
        }

        // Actualizar asociación novela-tags
        novel.setTagEntities(new ArrayList<>(tagsToAssign));
        tagRepo.saveAll(tagsToAssign);
    }

    // ============================================================
    // MÉTODOS DE CONSULTA ADICIONALES
    // ============================================================

    public Optional<SourceChapterContent> findChapterContentById(UUID chapterId) {
        return contentRepo.findByChapterId(chapterId);
    }

    /**
     * Obtiene una novela de la BD o la obtiene via crawler y la persiste.
     * Usa una función callback para evitar dependencia circular con AbstractGeneratedCrawler.
     */
    public SourceNovel getOrFetchNovel(String sourceId, String novelUrl, 
                                       java.util.function.Function<String, CrawlerNovelModel> fetcher) {
        return findBySourceIdAndNovelUrl(sourceId, novelUrl)
                .orElseGet(() -> {
                    CrawlerNovelModel model = fetcher.apply(novelUrl);
                    return upsertNovel(sourceId, model, "1.0.0", "generated");
                });
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private String normalizeLanguage(String lang) {
        if (lang == null) return null;
        return langDetector.normalize(lang);
    }

    private Map<String, Object> mergeExtra(Map<String, Object> existing, Map<String, Object> incoming,
                                           String version, String tier) {
        Map<String, Object> merged = new HashMap<>();
        if (existing != null) merged.putAll(existing);
        if (incoming != null) merged.putAll(incoming);
        merged.put("crawler_version", version);
        merged.put("crawler_tier", tier);
        merged.put("last_updated", Instant.now().toString());
        return merged;
    }
}