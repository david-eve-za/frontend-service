package com.glez.frontendservice.repository;


import com.glez.frontendservice.model.Book;
import com.glez.frontendservice.model.ProcessingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface BookRepository extends JpaRepository<Book, UUID> {
    List<Book> findByStatusIn(Collection<ProcessingStatus> statuses);
}
