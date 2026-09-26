package com.glez.frontendservice.repository;

import com.glez.frontendservice.model.Book;
import com.glez.frontendservice.model.Novel;
import com.glez.frontendservice.model.NovelVolume;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NovelVolumeRepository extends JpaRepository<NovelVolume, UUID> {
    List<NovelVolume> findByNovel(Novel novel);

    long countByNovel(Novel novel);

    Optional<NovelVolume> findByBook(Book book);

    void deleteAllByNovel(Novel novel);
}
