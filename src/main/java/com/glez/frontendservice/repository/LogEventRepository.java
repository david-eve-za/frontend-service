package com.glez.frontendservice.repository;

import com.glez.frontendservice.model.LogEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Repository
public interface LogEventRepository extends JpaRepository<LogEvent, java.util.UUID>,
        JpaSpecificationExecutor<LogEvent> {

    /**
     * Purga de retención: borra en bloque los eventos más antiguos que el
     * corte, sin materializar entidades (los índices sobre logged_at lo
     * mantienen barato).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("delete from LogEvent e where e.loggedAt < :cutoff")
    int deleteByLoggedAtBefore(@Param("cutoff") Instant cutoff);
}
