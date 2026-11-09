package com.glez.frontendservice.repository.source;

import com.glez.frontendservice.model.source.SourceNovelGlossary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio para glosarios de novela.
 */
@Repository
public interface SourceNovelGlossaryRepository extends JpaRepository<SourceNovelGlossary, UUID> {

    Optional<SourceNovelGlossary> findByNovelIdAndLanguage(UUID novelId, String language);

    void deleteByNovelId(UUID novelId);
}