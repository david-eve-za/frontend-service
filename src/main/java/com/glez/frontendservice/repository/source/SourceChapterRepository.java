package com.glez.frontendservice.repository.source;

import com.glez.frontendservice.model.source.SourceChapter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio para capítulos de crawlers genéricos.
 */
@Repository
public interface SourceChapterRepository extends JpaRepository<SourceChapter, UUID> {

    List<SourceChapter> findByNovelIdOrderBySerialAsc(UUID novelId);

    List<SourceChapter> findByVolumeIdOrderBySerialAsc(UUID volumeId);

    Optional<SourceChapter> findByNovelIdAndSerial(UUID novelId, int serial);

    long countByNovelIdAndDoneTrue(UUID novelId);

    @Query("SELECT c FROM SourceChapter c WHERE c.novel.id = :novelId AND c.url = :url")
    Optional<SourceChapter> findByNovelIdAndUrl(UUID novelId, String url);
}