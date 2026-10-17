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

    /**
     * Light block rows (id, type, number, position, audio path) for the
     * AUDIO step, in reading order: the block entities are NOT loaded,
     * so the chapter-length content CLOB of every block is never read.
     */
    @Query("select b.id as id, b.blockType as blockType, b.blockNumber as blockNumber, "
            + "b.position as position, b.audioFilePath as audioFilePath "
            + "from Block b where b.book.id = :bookId order by b.position asc")
    List<BlockAudioView> findAudioViewsByBookId(@Param("bookId") UUID bookId);

    /** Light block row for the AUDIO step (no content CLOB). */
    interface BlockAudioView {
        UUID getId();

        Block.BlockType getBlockType();

        Integer getBlockNumber();

        Integer getPosition();

        String getAudioFilePath();
    }

    /** Audio path update without loading the block (and its content CLOB). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("update Block b set b.audioFilePath = :path where b.id = :blockId")
    int updateAudioPathInBulk(@Param("blockId") UUID blockId, @Param("path") String path);

    /** Bulk delete of a book's blocks without loading the entities first. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("delete from Block b where b.book.id = :bookId")
    int deleteByBookIdInBulk(@Param("bookId") UUID bookId);
}
