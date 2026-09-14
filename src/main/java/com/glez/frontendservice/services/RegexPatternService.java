package com.glez.frontendservice.services;

import com.glez.frontendservice.model.RegexPattern;
import com.glez.frontendservice.repository.RegexPatternRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.PatternSyntaxException;

@Slf4j
@Service
@RequiredArgsConstructor
public class RegexPatternService {

    private final RegexPatternRepository regexPatternRepository;

    @PostConstruct
    @Transactional
    public void initDefaultPatterns() {
        createDefaultPatterns();
    }

    private void createDefaultPatterns() {
        // URL Pattern
        createIfNotExists("url-cleaner", "URL Cleaner",
                "Removes URLs from text (http, https, www)",
                "https?://\\S+|\\b(?:www\\.)?[\\w.-]+\\.(?:com|org|net|gov|edu|io|co|ai|app|blog|info|biz|dev|me|xyz)(?:/\\S*)?\\b",
                "", RegexPattern.PatternType.URL, 1, true, true, false, false);

        // Social Media Pattern
        createIfNotExists("social-media-cleaner", "Social Media Cleaner",
                "Removes @mentions and #hashtags",
                "@\\w+|#\\w+",
                "", RegexPattern.PatternType.SOCIAL_MEDIA, 2, true, true, false, false);

        // ISBN Pattern
        createIfNotExists("isbn-cleaner", "ISBN Cleaner",
                "Removes ISBN patterns",
                "ISBN(?:\\s*-?\\s*\\d{1,5}){2,5}[xX]?|\\bISBNs\\b",
                "", RegexPattern.PatternType.ISBN, 3, true, true, false, false);

        // Page Number Pattern
        createIfNotExists("page-number-cleaner", "Page Number Cleaner",
                "Removes page number patterns (page X, p. X, page X de Y)",
                "(?i)(?:^|\\s)(?:page|p\\.)\\s*\\d+\\s*(?:de\\s*\\d+)?(?:/|\\s|$)|(?i)(?:^|\\s)page\\|\\s*\\d+",
                "", RegexPattern.PatternType.PAGE_NUMBER, 4, true, true, false, false);

        // Whitespace Pattern
        createIfNotExists("whitespace-cleaner", "Whitespace Cleaner",
                "Normalizes multiple whitespace characters to single space",
                "[ \\t]+",
                " ", RegexPattern.PatternType.WHITESPACE, 5, true, false, false, false);

        log.info("Default regex patterns initialized");
    }

    private void createIfNotExists(String name, String displayName, String description,
                                   String pattern, String replacement,
                                   RegexPattern.PatternType type, int orderIndex,
                                   boolean enabled, boolean caseInsensitive,
                                   boolean multiline, boolean dotAll) {
        if (!regexPatternRepository.existsByName(name)) {
            RegexPattern regexPattern = RegexPattern.builder()
                    .name(name)
                    .displayName(displayName)
                    .description(description)
                    .pattern(pattern)
                    .replacement(replacement)
                    .patternType(type)
                    .enabled(enabled)
                    .caseInsensitive(caseInsensitive)
                    .multiline(multiline)
                    .dotAll(dotAll)
                    .orderIndex(orderIndex)
                    .build();
            regexPatternRepository.save(regexPattern);
            log.info("Created default regex pattern: {}", name);
        }
    }

    @Transactional(readOnly = true)
    public Page<RegexPattern> getAllPatterns(Pageable pageable) {
        return regexPatternRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public List<RegexPattern> getAllPatternsList() {
        return regexPatternRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<RegexPattern> getEnabledPatterns() {
        return regexPatternRepository.findByEnabledTrueOrderByOrderIndexAsc();
    }

    @Transactional(readOnly = true)
    public List<RegexPattern> getEnabledPatternsByType(RegexPattern.PatternType type) {
        return regexPatternRepository.findByPatternType(type);
    }

    @Transactional(readOnly = true)
    public Optional<RegexPattern> getPatternById(UUID id) {
        return regexPatternRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<RegexPattern> getPatternByName(String name) {
        return regexPatternRepository.findByName(name);
    }

    @Transactional
    public RegexPattern createPattern(RegexPattern pattern) {
        validatePattern(pattern.getPattern());
        if (regexPatternRepository.existsByName(pattern.getName())) {
            throw new IllegalArgumentException("Pattern with name '" + pattern.getName() + "' already exists");
        }
        return regexPatternRepository.save(pattern);
    }

    @Transactional
    public RegexPattern updatePattern(UUID id, RegexPattern updatedPattern) {
        RegexPattern existing = regexPatternRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Pattern not found with id: " + id));

        if (!existing.getName().equals(updatedPattern.getName()) &&
                regexPatternRepository.existsByName(updatedPattern.getName())) {
            throw new IllegalArgumentException("Pattern with name '" + updatedPattern.getName() + "' already exists");
        }

        validatePattern(updatedPattern.getPattern());

        existing.setDisplayName(updatedPattern.getDisplayName());
        existing.setDescription(updatedPattern.getDescription());
        existing.setPattern(updatedPattern.getPattern());
        existing.setReplacement(updatedPattern.getReplacement());
        existing.setPatternType(updatedPattern.getPatternType());
        existing.setEnabled(updatedPattern.getEnabled());
        existing.setCaseInsensitive(updatedPattern.getCaseInsensitive());
        existing.setMultiline(updatedPattern.getMultiline());
        existing.setDotAll(updatedPattern.getDotAll());
        existing.setOrderIndex(updatedPattern.getOrderIndex());

        return regexPatternRepository.save(existing);
    }

    @Transactional
    public void deletePattern(UUID id) {
        regexPatternRepository.deleteById(id);
    }

    @Transactional
    public RegexPattern togglePattern(UUID id) {
        RegexPattern pattern = regexPatternRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Pattern not found with id: " + id));
        pattern.setEnabled(!pattern.getEnabled());
        return regexPatternRepository.save(pattern);
    }

    @Transactional(readOnly = true)
    public List<RegexPattern> getPatternsForTextCleaning() {
        return regexPatternRepository.findByEnabledTrueOrderByOrderIndexAsc()
                .stream()
                .filter(p -> p.getPatternType() != RegexPattern.PatternType.CUSTOM || p.getEnabled())
                .toList();
    }

    private void validatePattern(String pattern) {
        try {
            java.util.regex.Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regex pattern: " + e.getMessage());
        }
    }
}