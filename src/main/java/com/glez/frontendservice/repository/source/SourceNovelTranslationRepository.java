package com.glez.frontendservice.repository.source;

import com.glez.frontendservice.model.source.SourceNovelTranslation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio para traducciones de metadatos de novela.
 */
@Repository
public interface SourceNovelTranslationRepository extends JpaRepository<SourceNovelTranslation, UUID> {

    Optional<SourceNovelTranslation> findByNovelIdAndLanguage(UUID novelId, String language);

    List<SourceNovelTranslation> findByNovelId(UUID novelId);

    void deleteByNovelId(UUID novelId);
}