package com.glez.frontendservice.repository;

import com.glez.frontendservice.model.TranslationTerm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TranslationTermRepository extends JpaRepository<TranslationTerm, UUID> {

    /**
     * Todos los términos que aplican a un libro: los globales (sin serie
     * ni libro), los de su serie y los del propio libro.
     */
    @Query("select t from TranslationTerm t where t.novel.id = :novelId or t.book.id = :bookId "
            + "or (t.novel is null and t.book is null) order by t.sourceTerm asc")
    List<TranslationTerm> findApplicable(@Param("novelId") UUID novelId, @Param("bookId") UUID bookId);

    /** ¿Ya existe un glosario del alcance de la serie (novel) o del libro? */
    @Query("select count(t) > 0 from TranslationTerm t where "
            + "(:novelId is not null and t.novel.id = :novelId) "
            + "or (:bookId is not null and t.book.id = :bookId)")
    boolean existsForScope(@Param("novelId") UUID novelId, @Param("bookId") UUID bookId);

    List<TranslationTerm> findByNovelIdOrderBySourceTermAsc(UUID novelId);

    List<TranslationTerm> findByBookIdOrderBySourceTermAsc(UUID bookId);

    List<TranslationTerm> findByNovelIsNullAndBookIsNullOrderBySourceTermAsc();

    /** Términos acotados a un libro (para el borrado del libro). */
    void deleteAllByBook(com.glez.frontendservice.model.Book book);
}
