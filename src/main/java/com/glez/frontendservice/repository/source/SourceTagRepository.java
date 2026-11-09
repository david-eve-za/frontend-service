package com.glez.frontendservice.repository.source;

import com.glez.frontendservice.model.source.SourceTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio para etiquetas/tags.
 */
@Repository
public interface SourceTagRepository extends JpaRepository<SourceTag, UUID> {

    Optional<SourceTag> findByNormalizedNameAndSourceId(String normalizedName, String sourceId);

    Optional<SourceTag> findByNameAndSourceId(String name, String sourceId);

    List<SourceTag> findBySourceId(String sourceId);

    List<SourceTag> findByCategory(String category);

    List<SourceTag> findByNormalizedNameInAndSourceId(List<String> normalizedNames, String sourceId);
}