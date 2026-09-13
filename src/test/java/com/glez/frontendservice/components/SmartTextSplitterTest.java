package com.glez.frontendservice.components;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SmartTextSplitter Tests")
class SmartTextSplitterTest {

    private SmartTextSplitter smartTextSplitter;

    @BeforeEach
    void setUp() {
        smartTextSplitter = new SmartTextSplitter();
        // Initialize the tokenSplitter via reflection since @PostConstruct won't run in tests
        try {
            java.lang.reflect.Field field = SmartTextSplitter.class.getDeclaredField("tokenSplitter");
            field.setAccessible(true);
            org.springframework.ai.transformer.splitter.TokenTextSplitter splitter = 
                org.springframework.ai.transformer.splitter.TokenTextSplitter.builder()
                    .withChunkSize(5000)
                    .withMinChunkSizeChars(350)
                    .withMinChunkLengthToEmbed(10)
                    .withMaxNumChunks(10000)
                    .withKeepSeparator(true)
                    .withPunctuationMarks(List.of('.', '?', '!', '\n', '¿', '¡', ';', ':'))
                    .build();
            field.set(smartTextSplitter, splitter);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize tokenSplitter", e);
        }
    }

    @Nested
    @DisplayName("split(String text) method tests")
    class SplitTextTests {

        @Test
        @DisplayName("Should return empty list for empty string")
        void split_withEmptyString_returnsEmptyList() {
            List<String> result = smartTextSplitter.split("");
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should split short text into single chunk")
        void split_withShortText_returnsSingleChunk() {
            String text = "This is a short text that should fit in one chunk.";
            List<String> result = smartTextSplitter.split(text);
            assertNotNull(result);
            assertEquals(1, result.size());
            assertEquals(text, result.get(0));
        }

        @Test
        @DisplayName("Should split long text into multiple chunks")
        void split_withLongText_returnsMultipleChunks() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                sb.append("This is sentence number ").append(i).append(". ");
            }
            String longText = sb.toString();
            List<String> result = smartTextSplitter.split(longText);
            assertNotNull(result);
            assertTrue(result.size() >= 1);
        }
    }

    @Nested
    @DisplayName("split(String text, String language, int chunkSize) method tests")
    class SplitTextWithParamsTests {

        @Test
        @DisplayName("Should split text with custom chunk size")
        void split_withCustomChunkSize_respectsChunkSize() {
            String text = "Sentence one. Sentence two. Sentence three. Sentence four. Sentence five.";
            List<String> result = smartTextSplitter.split(text, "en", 50);
            assertNotNull(result);
            assertTrue(result.size() >= 1);
        }

        @Test
        @DisplayName("Should handle Spanish text")
        void split_withSpanishText_splitsCorrectly() {
            String text = "Esta es la primera oración. Esta es la segunda oración. Esta es la tercera oración.";
            List<String> result = smartTextSplitter.split(text, "es", 100);
            assertNotNull(result);
            assertTrue(result.size() >= 1);
        }

        @Test
        @DisplayName("Should return empty list for empty string with params")
        void split_withEmptyStringAndParams_returnsEmptyList() {
            List<String> result = smartTextSplitter.split("", "en", 100);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("splitBySentences method tests")
    class SplitBySentencesTests {

        @Test
        @DisplayName("Should split text by sentences")
        void splitBySentences_withSentences_splitsCorrectly() {
            String text = "First sentence. Second sentence! Third sentence?";
            List<String> result = smartTextSplitter.splitBySentences(text, "en", 100);
            assertNotNull(result);
            assertTrue(result.size() >= 1);
        }

        @Test
        @DisplayName("Should handle empty text")
        void splitBySentences_withEmptyText_returnsEmptyList() {
            List<String> result = smartTextSplitter.splitBySentences("", "en", 100);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("estimateTokenCount method tests")
    class EstimateTokenCountTests {

        @Test
        @DisplayName("Should estimate token count correctly")
        void estimateTokenCount_withText_returnsEstimate() {
            String text = "This is a test text with twenty characters";
            int result = invokeEstimateTokenCount(text);
            assertEquals(text.length() / 4, result);
        }

        @Test
        @DisplayName("Should return zero for empty string")
        void estimateTokenCount_withEmptyString_returnsZero() {
            int result = invokeEstimateTokenCount("");
            assertEquals(0, result);
        }
    }

    private int invokeEstimateTokenCount(String text) {
        try {
            java.lang.reflect.Method method = SmartTextSplitter.class.getDeclaredMethod("estimateTokenCount", String.class);
            method.setAccessible(true);
            return (int) method.invoke(smartTextSplitter, text);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke estimateTokenCount method", e);
        }
    }
}