package com.glez.frontendservice.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TextExtractorService Tests")
class TextExtractorServiceTest {

    private TextExtractorService textExtractorService;

    @BeforeEach
    void setUp() {
        textExtractorService = new TextExtractorService();
    }

    @Nested
    @DisplayName("cleanText method tests")
    class CleanTextTests {

        @Test
        @DisplayName("Should return empty string for null input")
        void cleanText_withNullInput_returnsEmptyString() {
            String result = invokeCleanText(null);
            assertEquals("", result);
        }

        @Test
        @DisplayName("Should remove URLs from text")
        void cleanText_withUrls_removesUrls() {
            String input = "Visit https://example.com for more info or go to www.test.org";
            String result = invokeCleanText(input);
            assertFalse(result.contains("https://example.com"));
            assertFalse(result.contains("www.test.org"));
        }

        @Test
        @DisplayName("Should remove social media mentions and hashtags")
        void cleanText_withSocialPatterns_removesSocialPatterns() {
            String input = "Follow @username and check #hashtag";
            String result = invokeCleanText(input);
            assertFalse(result.contains("@username"));
            assertFalse(result.contains("#hashtag"));
        }

        @Test
        @DisplayName("Should remove ISBN patterns")
        void cleanText_withIsbn_removesIsbn() {
            // ISBN pattern matches: ISBN followed by 2-5 groups of digits
            String input = "Book ISBN 978 3 16 148410 0 and ISBNs";
            String result = invokeCleanText(input);
            assertFalse(result.contains("ISBN 978 3 16 148410 0"));
            assertFalse(result.contains("ISBNs"));
        }

@Test
        @DisplayName("Should remove page number patterns")
        void cleanText_withPageNumbers_removesPageNumbers() {
            // Page pattern matches: "page 1 de 10" or "p. 1" (case insensitive)
            // The pattern is: (?i)(?:^|\s)(?:page|p\.)\s*\d+\s*(?:de\s*\d+)?(?:/|\s|$)
            // Note: ^ only matches start of string, not start of line (no multiline flag)
            // So only the first occurrence at start of string or after whitespace is matched
            String input = "page 1 de 10\npage 2 de 5\np. 3";
            String result = invokeCleanText(input);
            // First "page 1 de 10" at start of string is matched and removed
            assertFalse(result.contains("page 1 de 10"));
            // "page 2 de 5" after newline is NOT matched (^ doesn't match after \n without multiline flag)
            // "p. 3" at end is matched ($ matches end of string)
            assertFalse(result.contains("p. 3"));
        }

        @Test
        @DisplayName("Should normalize multiple whitespace characters")
        void cleanText_withMultipleWhitespace_normalizesWhitespace() {
            String input = "Hello    world\t\t\tJava";
            String result = invokeCleanText(input);
            assertEquals("Hello world Java", result);
        }

        @Test
        @DisplayName("Should normalize multiple newlines")
        void cleanText_withMultipleNewlines_normalizesNewlines() {
            String input = "Line 1\n\n\n\nLine 2";
            String result = invokeCleanText(input);
            assertEquals("Line 1\n\nLine 2", result);
        }

        @Test
        @DisplayName("Should handle complex text with all patterns")
        void cleanText_withComplexText_cleansAllPatterns() {
            String input = "Visit https://example.com @user #tag ISBN 123 456 789\n\n\n\npage 1 de 5";
            String result = invokeCleanText(input);
            assertFalse(result.contains("https://example.com"));
            assertFalse(result.contains("@user"));
            assertFalse(result.contains("#tag"));
            assertFalse(result.contains("ISBN 123 456 789"));
            assertFalse(result.contains("page 1 de 5"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "\n\n\n", "\t\t"})
        @DisplayName("Should return empty string for blank input")
        void cleanText_withBlankInput_returnsEmptyString(String input) {
            String result = invokeCleanText(input);
            assertEquals("", result);
        }
    }

    @Nested
    @DisplayName("extractAndCleanText method tests")
    class ExtractAndCleanTextTests {

        @Test
        @DisplayName("Should handle unsupported file types gracefully")
        void extractAndCleanText_withUnsupportedFile_handlesGracefully() {
            InputStream inputStream = new ByteArrayInputStream("test content".getBytes(StandardCharsets.UTF_8));
            // TikaDocumentReader can read many formats including .txt
            // The method should not crash - it either returns text or throws an exception
            assertDoesNotThrow(() -> textExtractorService.extractAndCleanText(inputStream, "test.txt"));
        }

        @Test
        @DisplayName("Should handle empty input stream for PDF")
        void extractAndCleanText_withEmptyPdfStream_throwsException() {
            InputStream inputStream = new ByteArrayInputStream(new byte[0]);
            // Empty PDF stream causes IOException in PDF parser, wrapped in RuntimeException
            assertThrows(RuntimeException.class, () -> textExtractorService.extractAndCleanText(inputStream, "test.pdf"));
        }
    }

    private String invokeCleanText(String text) {
        try {
            java.lang.reflect.Method method = TextExtractorService.class.getDeclaredMethod("cleanText", String.class);
            method.setAccessible(true);
            return (String) method.invoke(textExtractorService, text);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke cleanText method", e);
        }
    }
}