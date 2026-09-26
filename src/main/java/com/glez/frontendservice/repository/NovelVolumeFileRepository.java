package com.glez.frontendservice.repository;

import com.glez.frontendservice.model.NovelVolumeFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NovelVolumeFileRepository extends JpaRepository<NovelVolumeFile, UUID> {
    Optional<NovelVolumeFile> findByRemoteHref(String remoteHref);

    List<NovelVolumeFile> findByRemoteHrefIn(Collection<String> remoteHrefs);

    List<NovelVolumeFile> findByVolumeId(UUID volumeId);
}
