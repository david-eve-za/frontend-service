package com.glez.frontendservice.repository;

import com.glez.frontendservice.model.Book;
import com.glez.frontendservice.model.Novel;
import com.glez.frontendservice.model.NovelVolume;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NovelVolumeRepository extends JpaRepository<NovelVolume, UUID> {
    List<NovelVolume> findByNovel(Novel novel);

    long countByNovel(Novel novel);

    Optional<NovelVolume> findByBook(Book book);

    /**
     * Volume counts for many novels in ONE grouped query: replaces the
     * per-novel countByNovel of the catalog listing (N+1).
     */
    @Query("select v.novel.id as novelId, count(v) as volumeCount "
            + "from NovelVolume v where v.novel.id in :novelIds group by v.novel.id")
    List<NovelVolumeCount> countByNovelIds(@Param("novelIds") Collection<UUID> novelIds);

    /** Volume-count aggregate per novel. */
    interface NovelVolumeCount {
        UUID getNovelId();

        long getVolumeCount();
    }

    /**
     * Bulk removedFromSource marking for every volume of the given novels
     * in ONE update (before: load each volume and save it row by row).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("update NovelVolume v set v.removedFromSource = true, v.updatedAt = :now "
            + "where v.novel.id in :novelIds and v.removedFromSource = false")
    int markRemovedFromSource(@Param("novelIds") Collection<UUID> novelIds, @Param("now") Instant now);
}
