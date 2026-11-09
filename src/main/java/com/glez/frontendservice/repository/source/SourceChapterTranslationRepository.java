package com.glez.frontendservice.repository.source;

import com.glez.frontendservice.model.source.SourceChapterTranslation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio para traducciones de capítulos.
 */
@Repository
public interface SourceChapterTranslationRepository extends JpaRepository<SourceChapterTranslation, UUID> {

    Optional<SourceChapterTranslation> findByNovelIdAndChapterSerialAndLanguage(UUID novelId, int chapterSerial, String language);

    List<SourceChapterTranslation> findByNovelId(UUID novelId);

    List<SourceChapterTranslation> findByNovelIdAndLanguage(UUID novelId, String language);

    void deleteByNovelId(UUID novelId);
}