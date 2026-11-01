package com.glez.frontendservice.repository;

import com.glez.frontendservice.model.Novel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NovelRepository extends JpaRepository<Novel, UUID> {
    Optional<Novel> findByRemotePath(String remotePath);

    /** Novela virtual (sin carpeta remota) creada desde archivos sueltos de la raíz. */
    Optional<Novel> findByTitleAndRemotePathIsNull(String title);

    Page<Novel> findByTitleContainingIgnoreCase(String title, Pageable pageable);

    long countByRemotePathIsNotNull();

    /** Light (id, remotePath) view of every folder-backed novel. */
    @Query("select n.id as id, n.remotePath as remotePath from Novel n where n.remotePath is not null")
    List<RemotePathView> findIdAndRemotePath();

    /** Novel id + remote folder path projection. */
    interface RemotePathView {
        UUID getId();

        String getRemotePath();
    }

    // ------------------------------------------------------------------
    // Pass-completion flags: each catalog-sync pass flags the novel folders
    // it fully lists, later incremental runs skip them, and a new cycle
    // (full re-sync) starts once every folder is flagged.
    // ------------------------------------------------------------------

    /** Carpetas completadas en la pasada actual (para saltarlas con un solo query por corrida). */
    @Query("select n.remotePath from Novel n where n.listingComplete = true")
    List<String> findRemotePathsByListingCompleteTrue();

    /** Carpetas de obra que faltan por listar en la pasada actual. */
    long countByRemotePathIsNotNullAndListingCompleteFalse();

    /**
     * Reinicia todos los flags de la pasada (arranque de un ciclo completo):
     * un solo bulk update, sin materializar entidades.
     *
     * @return filas reiniciadas
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("update Novel n set n.listingComplete = false where n.remotePath is not null")
    int resetListingComplete();

    /** Novelas cuyo remotePath empieza con el prefijo dado (para detectar subdirectorios). */
    @Query("select n from Novel n where n.remotePath like :prefix%")
    List<Novel> findByRemotePathStartingWith(String prefix);

    /** Novelas sin remotePath (archivos sueltos en la raíz). */
    List<Novel> findByRemotePathIsNull();

    /** Elimina todas las novelas sueltas (remotePath == null) con títulos que empiezan por "!". */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("delete from Novel n where n.remotePath is null and n.title like '!%'")
    int deleteLooseCategoryNovels();
}
