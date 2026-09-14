package com.glez.frontendservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.regex.Pattern;

@Entity
@Table(name = "regex_patterns", uniqueConstraints = @UniqueConstraint(columnNames = "name"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegexPattern {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "pattern", nullable = false, columnDefinition = "TEXT")
    private String pattern;

    @Column(name = "replacement", columnDefinition = "TEXT")
    private String replacement;

    @Enumerated(EnumType.STRING)
    @Column(name = "pattern_type", nullable = false)
    private PatternType patternType;

    @Builder.Default
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Builder.Default
    @Column(name = "case_insensitive", nullable = false)
    private Boolean caseInsensitive = false;

    @Builder.Default
    @Column(name = "multiline", nullable = false)
    private Boolean multiline = false;

    @Builder.Default
    @Column(name = "dot_all", nullable = false)
    private Boolean dotAll = false;

    @Builder.Default
    @Column(name = "order_index", nullable = false)
    private Integer orderIndex = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public enum PatternType {
        URL,
        SOCIAL_MEDIA,
        ISBN,
        PAGE_NUMBER,
        WHITESPACE,
        CUSTOM
    }

    public Pattern toPattern() {
        int flags = 0;
        if (Boolean.TRUE.equals(caseInsensitive)) flags |= java.util.regex.Pattern.CASE_INSENSITIVE;
        if (Boolean.TRUE.equals(multiline)) flags |= java.util.regex.Pattern.MULTILINE;
        if (Boolean.TRUE.equals(dotAll)) flags |= java.util.regex.Pattern.DOTALL;
        return java.util.regex.Pattern.compile(pattern, flags);
    }
}