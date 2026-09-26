package com.glez.frontendservice.repository;

import com.glez.frontendservice.model.Novel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface NovelRepository extends JpaRepository<Novel, UUID> {
    Optional<Novel> findByRemotePath(String remotePath);

    /** Novela virtual (sin carpeta remota) creada desde archivos sueltos de la raíz. */
    Optional<Novel> findByTitleAndRemotePathIsNull(String title);

    Page<Novel> findByTitleContainingIgnoreCase(String title, Pageable pageable);

    long countByRemotePathIsNotNull();
}
