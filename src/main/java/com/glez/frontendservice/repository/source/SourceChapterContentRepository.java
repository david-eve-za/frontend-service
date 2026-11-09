package com.glez.frontendservice.repository.source;

import com.glez.frontendservice.model.source.SourceChapterContent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio para contenido de capítulos (metadatos + path a archivo GZIP).
 */
@Repository
public interface SourceChapterContentRepository extends JpaRepository<SourceChapterContent, UUID> {

    Optional<SourceChapterContent> findByChapterId(UUID chapterId);

    boolean existsByChapterId(UUID chapterId);
}