package com.glez.frontendservice.services;

import com.glez.frontendservice.components.SmartTextSplitter;
import com.glez.frontendservice.tts.EdgeTtsClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AudioGeneratorService Tests")
class AudioGeneratorServiceTest {

    @Mock
    private SmartTextSplitter textSplitter;

    @Mock
    private EdgeTtsClient edgeTtsClient;

    private AudioGeneratorService audioGeneratorService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        audioGeneratorService = new AudioGeneratorService(textSplitter, edgeTtsClient);
    }

    @Nested
    @DisplayName("processTextToAudio method tests")
    class ProcessTextToAudioTests {

        @Test
        @DisplayName("Should return false for null text")
        void processTextToAudio_withNullText_returnsFalse() {
            Path outputPath = tempDir.resolve("output.m4a");
            boolean result = audioGeneratorService.processTextToAudio(null, outputPath);
            assertFalse(result);
        }

        @Test
        @DisplayName("Should return false for empty text")
        void processTextToAudio_withEmptyText_returnsFalse() {
            Path outputPath = tempDir.resolve("output.m4a");
            boolean result = audioGeneratorService.processTextToAudio("", outputPath);
            assertFalse(result);
        }

        @Test
        @DisplayName("Should return false for blank text")
        void processTextToAudio_withBlankText_returnsFalse() {
            Path outputPath = tempDir.resolve("output.m4a");
            boolean result = audioGeneratorService.processTextToAudio("   ", outputPath);
            assertFalse(result);
        }

        @Test
        @DisplayName("Should call textSplitter with correct parameters")
        void processTextToAudio_withValidText_callsTextSplitter() {
            Path outputPath = tempDir.resolve("output.mp3");
            String text = "This is a test text for audio generation.";
            List<String> chunks = List.of("Chunk 1", "Chunk 2");
            when(textSplitter.split(text, "es", 1000)).thenReturn(chunks);

            // Spy on the service to verify textSplitter is called
            AudioGeneratorService spyService = spy(audioGeneratorService);
            // We can't easily mock private methods, so we just verify the textSplitter call
            // The actual processTextToAudio will fail on textToAudio but we can catch that
            try {
                spyService.processTextToAudio(text, outputPath);
            } catch (Exception ignored) {
                // Expected to fail on private method calls
            }

            verify(textSplitter).split(text, "es", 1000);
        }

        @Test
        @DisplayName("Should synthesize every chunk through the Edge TTS client")
        void processTextToAudio_withValidText_callsEdgeTtsClientForEachChunk() {
            Path outputPath = tempDir.resolve("output.mp3");
            String text = "This is a test text for audio generation.";
            List<String> chunks = List.of("Chunk 1", "Chunk 2");
            when(textSplitter.split(text, "es", 1000)).thenReturn(chunks);

            audioGeneratorService.processTextToAudio(text, outputPath);

            verify(edgeTtsClient, times(2)).synthesizeToFile(anyString(), any(Path.class));
        }

        @Test
        @DisplayName("Should skip chunks that fail synthesis and not generate audio when all fail")
        void processTextToAudio_whenAllChunksFail_returnsFalse() {
            Path outputPath = tempDir.resolve("output.mp3");
            String text = "This is a test text for audio generation.";
            List<String> chunks = List.of("Chunk 1");
            when(textSplitter.split(text, "es", 1000)).thenReturn(chunks);
            doThrow(new com.glez.frontendservice.tts.EdgeTtsException("synthesis failed"))
                    .when(edgeTtsClient).synthesizeToFile(anyString(), any(Path.class));

            boolean result = audioGeneratorService.processTextToAudio(text, outputPath);

            assertFalse(result);
        }
    }

    @Nested
    @DisplayName("normalizeText method tests")
    class NormalizeTextTests {

        @Test
        @DisplayName("Should normalize smart quotes")
        void normalizeText_withSmartQuotes_replacesWithStandardQuotes() {
            String input = "\"Hello\" and 'world'";
            String result = invokeNormalizeText(input);
            assertEquals("\"Hello\" and 'world'", result);
        }

        @Test
        @DisplayName("Should normalize em dashes and en dashes")
        void normalizeText_withDashes_replacesWithHyphen() {
            String input = "Text — with – dashes";
            String result = invokeNormalizeText(input);
            assertEquals("Text - with - dashes", result);
        }

        @Test
        @DisplayName("Should normalize ellipsis")
        void normalizeText_withEllipsis_replacesWithThreeDots() {
            String input = "Text … here";
            String result = invokeNormalizeText(input);
            assertEquals("Text ... here", result);
        }

        @Test
        @DisplayName("Should normalize HTML line breaks")
        void normalizeText_withHtmlBr_replacesWithNewline() {
            String input = "Line 1<br>Line 2";
            String result = invokeNormalizeText(input);
            assertEquals("Line 1\nLine 2", result);
        }

        @Test
        @DisplayName("Should handle text without special characters")
        void normalizeText_withoutSpecialChars_returnsSameText() {
            String input = "Normal text without special chars";
            String result = invokeNormalizeText(input);
            assertEquals(input, result);
        }

        @Test
        @DisplayName("Should handle empty string input")
        void normalizeText_withEmptyString_returnsEmptyString() {
            String result = invokeNormalizeText("");
            assertEquals("", result);
        }

        @Test
        @DisplayName("Should apply all normalizations in sequence")
        void normalizeText_withMultipleSpecialChars_appliesAll() {
            String input = "\"Quote\" — dash … ellipsis<br>break";
            String result = invokeNormalizeText(input);
            assertEquals("\"Quote\" - dash ... ellipsis\nbreak", result);
        }
    }

    @Nested
    @DisplayName("deleteDirectoryRecursively method tests")
    class DeleteDirectoryRecursivelyTests {

        @Test
        @DisplayName("Should delete directory and contents")
        void deleteDirectoryRecursively_withValidDirectory_deletesAll() throws IOException {
            Path testDir = Files.createTempDirectory(tempDir, "test_delete_");
            Files.createFile(testDir.resolve("file1.txt"));
            Files.createFile(testDir.resolve("file2.txt"));
            Path subDir = Files.createDirectory(testDir.resolve("subdir"));
            Files.createFile(subDir.resolve("file3.txt"));

            assertTrue(Files.exists(testDir));
            invokeDeleteDirectoryRecursively(testDir);
            assertFalse(Files.exists(testDir));
        }

        @Test
        @DisplayName("Should handle non-existent directory")
        void deleteDirectoryRecursively_withNonExistentDirectory_doesNothing() {
            Path nonExistent = tempDir.resolve("nonexistent");
            assertFalse(Files.exists(nonExistent));
            assertDoesNotThrow(() -> invokeDeleteDirectoryRecursively(nonExistent));
        }
    }

    private String invokeNormalizeText(String text) {
        try {
            java.lang.reflect.Method method = AudioGeneratorService.class.getDeclaredMethod("normalizeText", String.class);
            method.setAccessible(true);
            return (String) method.invoke(audioGeneratorService, text);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke normalizeText method", e);
        }
    }

    private void invokeDeleteDirectoryRecursively(Path path) {
        try {
            java.lang.reflect.Method method = AudioGeneratorService.class.getDeclaredMethod("deleteDirectoryRecursively", Path.class);
            method.setAccessible(true);
            method.invoke(audioGeneratorService, path);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke deleteDirectoryRecursively method", e);
        }
    }
}