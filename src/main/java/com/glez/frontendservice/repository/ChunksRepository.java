package com.glez.frontendservice.repository;


import com.glez.frontendservice.model.Book;
import com.glez.frontendservice.model.Chunks;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ChunksRepository extends JpaRepository<Chunks, UUID> {
    List<Chunks> findByBook(Book book);
    Page<Chunks> findByBook(Book book, Pageable pageable); // New method
    void deleteAllByBook(Book book);
    long countByBook(Book book);
    long countByBookAndStatus(Book book, com.glez.frontendservice.model.ChunkStatus status);
}
