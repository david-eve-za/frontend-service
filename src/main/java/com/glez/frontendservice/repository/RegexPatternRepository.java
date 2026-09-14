package com.glez.frontendservice.repository;

import com.glez.frontendservice.model.RegexPattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RegexPatternRepository extends JpaRepository<RegexPattern, UUID> {

    Optional<RegexPattern> findByName(String name);

    List<RegexPattern> findByEnabledTrueOrderByOrderIndexAsc();

    Page<RegexPattern> findAll(Pageable pageable);

    @Query("SELECT r FROM RegexPattern r WHERE r.enabled = true AND r.patternType = :type ORDER BY r.orderIndex ASC")
    List<RegexPattern> findByPatternType(@Param("type") RegexPattern.PatternType type);

    boolean existsByName(String name);
}