package com.glez.frontendservice.repository.source;

import com.glez.frontendservice.model.source.SourceVolume;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio para volúmenes de crawlers genéricos.
 */
@Repository
public interface SourceVolumeRepository extends JpaRepository<SourceVolume, UUID> {

    List<SourceVolume> findByNovelIdOrderBySerialAsc(UUID novelId);

    Optional<SourceVolume> findByNovelIdAndSerial(UUID novelId, int serial);

    long countByNovelId(UUID novelId);

    void deleteByNovelId(UUID novelId);
}