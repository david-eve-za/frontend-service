package com.glez.frontendservice.repository.source;

import com.glez.frontendservice.model.source.SourceCrawlerMeta;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repositorio para metadatos de versionado de crawlers.
 */
@Repository
public interface SourceCrawlerMetaRepository extends JpaRepository<SourceCrawlerMeta, String> {

    Optional<SourceCrawlerMeta> findBySourceId(String sourceId);
}