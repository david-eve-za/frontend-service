package com.glez.frontendservice.components;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("NovelFileManager Tests")
class NovelFileManagerTest {

    @TempDir
    Path tempDir;

    private NovelFileManager fileManager;

    @BeforeEach
    void setUp() {
        fileManager = new NovelFileManager(tempDir.resolve("novels-output").toString());
    }

    @Test
    @DisplayName("Constructor creates the base output directory")
    void constructor_createsBaseDirectory() {
        assertTrue(Files.isDirectory(tempDir.resolve("novels-output")));
    }

    @Test
    @DisplayName("sanitizePath replaces forbidden characters like the Python script")
    void sanitizePath_replacesForbiddenCharacters() {
        assertEquals("a_b_c_d_e", fileManager.sanitizePath("a?b=c&d:e"));
    }

    @Test
    @DisplayName("sanitizePath keeps spaces and dots untouched")
    void sanitizePath_keepsSpacesAndDots() {
        assertEquals("Book A Vol.1", fileManager.sanitizePath("Book A Vol.1"));
    }

    @Test
    @DisplayName("getLocalPath resolves nested segments under the base directory")
    void getLocalPath_resolvesNestedSegmentsUnderBase() {
        Path path = fileManager.getLocalPath(List.of("Book A", "Volume 1", "file.epub"));
        Path expected = fileManager.getBaseOutputDir()
                .resolve("Book A")
                .resolve("Volume 1")
                .resolve("file.epub");
        assertEquals(expected, path);
    }

    @Test
    @DisplayName("getLocalPath sanitizes every segment")
    void getLocalPath_sanitizesEachSegment() {
        Path path = fileManager.getLocalPath(List.of("Di:r?name", "file&name"));
        assertEquals(fileManager.getBaseOutputDir().resolve("Di_r_name").resolve("file_name"), path);
    }

    @Test
    @DisplayName("ensureDirectory creates missing directories and is idempotent")
    void ensureDirectory_createsDirectoryAndIsIdempotent() {
        Path dir = fileManager.getBaseOutputDir().resolve("series").resolve("volume");
        fileManager.ensureDirectory(dir);
        fileManager.ensureDirectory(dir);
        assertTrue(Files.isDirectory(dir));
    }

    @Test
    @DisplayName("fileExists detects existing and missing files")
    void fileExists_detectsExistingAndMissingFiles() throws IOException {
        Path file = fileManager.getBaseOutputDir().resolve("exists.epub");
        Files.writeString(file, "data");
        assertTrue(fileManager.fileExists(file));
        assertFalse(fileManager.fileExists(fileManager.getBaseOutputDir().resolve("missing.epub")));
    }
}
