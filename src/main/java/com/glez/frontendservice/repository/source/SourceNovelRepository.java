package com.glez.frontendservice.repository.source;

import com.glez.frontendservice.model.source.SourceNovel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio para novelas de crawlers genéricos.
 */
@Repository
public interface SourceNovelRepository extends JpaRepository<SourceNovel, UUID> {

    Optional<SourceNovel> findBySourceIdAndNovelUrlHash(String sourceId, String urlHash);

    Optional<SourceNovel> findBySourceIdAndNovelUrl(String sourceId, String novelUrl);

    @Query("SELECT n FROM SourceNovel n WHERE n.sourceId = :sourceId AND " +
           "(n.title ILIKE %:search% OR n.author ILIKE %:search% OR n.synopsis ILIKE %:search%)")
    Page<SourceNovel> searchBySource(String sourceId, String search, Pageable pageable);

    @Query(value = "SELECT * FROM source_novel WHERE source_id = :sourceId AND JSON_EXISTS(tags, '$[*] ? (@ == :tag)')",
            countQuery = "SELECT COUNT(*) FROM source_novel WHERE source_id = :sourceId AND JSON_EXISTS(tags, '$[*] ? (@ == :tag)')",
            nativeQuery = true)
    Page<SourceNovel> findBySourceAndTag(String sourceId, String tag, Pageable pageable);

    @Query("SELECT n FROM SourceNovel n WHERE n.sourceId = :sourceId ORDER BY n.popularity DESC")
    Page<SourceNovel> findPopularBySource(String sourceId, Pageable pageable);

    @Query("SELECT n FROM SourceNovel n WHERE n.sourceId = :sourceId ORDER BY n.updatedAt DESC")
    Page<SourceNovel> findBySourceIdOrderByUpdatedAtDesc(String sourceId, Pageable pageable);

    @Query("SELECT n FROM SourceNovel n WHERE n.sourceId = :sourceId ORDER BY n.lastCrawledAt ASC NULLS FIRST")
    Page<SourceNovel> findBySourceIdOrderByLastCrawledAtAsc(String sourceId, Pageable pageable);

    List<SourceNovel> findBySourceId(String sourceId);

    long countBySourceId(String sourceId);

    @Query("SELECT n FROM SourceNovel n WHERE n.sourceId = :sourceId AND n.lastCrawledAt IS NOT NULL ORDER BY n.lastCrawledAt ASC")
    List<SourceNovel> findStaleBySource(String sourceId, Instant since);

    @Query("SELECT DISTINCT n.language FROM SourceNovel n WHERE n.sourceId = :sourceId AND n.language IS NOT NULL")
    List<String> findLanguagesBySource(String sourceId);
}