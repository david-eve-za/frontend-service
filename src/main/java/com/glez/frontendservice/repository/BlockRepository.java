package com.glez.frontendservice.repository;

import com.glez.frontendservice.model.Block;
import com.glez.frontendservice.model.Book;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface BlockRepository extends JpaRepository<Block, UUID> {
    List<Block> findByBook(Book book);

    /** Bloques de un libro en orden de lectura, sin cargar la entidad Book (LOB). */
    @Query("select b from Block b where b.book.id = :bookId order by b.position asc")
    List<Block> findByBookIdOrderByPositionAsc(@Param("bookId") UUID bookId);

    /** Cuenta cuántos de los ids pertenecen realmente al libro. */
    @Query("select count(b) from Block b where b.id in :blockIds and b.book.id = :bookId")
    long countByBookIdAndIdIn(@Param("bookId") UUID bookId, @Param("blockIds") Collection<UUID> blockIds);

    /** Bloques que aún no tienen audio, en orden de lectura. */
    @Query("select b from Block b where b.book.id = :bookId and b.audioFilePath is null order by b.position asc")
    List<Block> findBookIdWithoutAudio(@Param("bookId") UUID bookId);

    /** Bulk delete of a book's blocks without loading the entities first. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("delete from Block b where b.book.id = :bookId")
    int deleteByBookIdInBulk(@Param("bookId") UUID bookId);
}
