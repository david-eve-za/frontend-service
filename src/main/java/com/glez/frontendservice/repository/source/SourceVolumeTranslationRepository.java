package com.glez.frontendservice.repository.source;

import com.glez.frontendservice.model.source.SourceVolumeTranslation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio para traducciones de títulos de volumen.
 */
@Repository
public interface SourceVolumeTranslationRepository extends JpaRepository<SourceVolumeTranslation, UUID> {

    Optional<SourceVolumeTranslation> findByNovelIdAndVolumeSerialAndLanguage(UUID novelId, int volumeSerial, String language);

    List<SourceVolumeTranslation> findByNovelId(UUID novelId);

    void deleteByNovelId(UUID novelId);
}