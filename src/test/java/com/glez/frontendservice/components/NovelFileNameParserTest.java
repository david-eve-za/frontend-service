package com.glez.frontendservice.components;

import com.glez.frontendservice.model.NovelVolumeFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("NovelFileNameParser Tests")
class NovelFileNameParserTest {

    private final NovelFileNameParser parser = new NovelFileNameParser();

    @Test
    @DisplayName("parses volume number, label and translator group from real elscione names")
    void parsesRealVolumeNames() {
        NovelFileNameParser.ParsedVolumeFile parsed =
                parser.parse("Ai no Kusabi - Volume 01 [June][Scans].pdf");

        assertEquals(1, parsed.volumeNumber());
        assertEquals("Volume 01", parsed.label());
        assertEquals("June Scans", parsed.translatorGroup());
        assertEquals(NovelVolumeFile.Format.PDF, parsed.format());
    }

    @Test
    @DisplayName("titles containing dashes keep everything before the last Volume segment")
    void titleWithDashesStaysIntact() {
        Optional<String> title = parser.novelTitleFromLooseFile(
                "The Isle Of Paramounts - Reborn Into A Slow Life - Volume 02 [J-Novel Club].epub");

        assertEquals(Optional.of("The Isle Of Paramounts - Reborn Into A Slow Life"), title);
    }

    @Test
    @DisplayName("files without a volume segment get null number, stem label and no group")
    void parsesFileWithoutVolume() {
        NovelFileNameParser.ParsedVolumeFile parsed = parser.parse("Artbook 2024.epub");

        assertNull(parsed.volumeNumber());
        assertEquals("Artbook 2024", parsed.label());
        assertNull(parsed.translatorGroup());
        assertEquals(NovelVolumeFile.Format.EPUB, parsed.format());
    }

    @Test
    @DisplayName("detects the format of every catalog file extension and ignores the rest")
    void detectsFormats() {
        assertEquals(NovelVolumeFile.Format.EPUB, parser.formatOf("x - Volume 1.EPUB"));
        assertEquals(NovelVolumeFile.Format.ZIP, parser.formatOf("x - Volume 1.zip"));
        assertEquals(NovelVolumeFile.Format.TXT, parser.formatOf("x - Volume 1.txt"));
        assertEquals(NovelVolumeFile.Format.OTHER, parser.formatOf("GenerateCompletedLinks.bat"));
        assertEquals(NovelVolumeFile.Format.OTHER, parser.formatOf("cover.webp"));
    }

    @Test
    @DisplayName("non-volume files yield no loose novel title")
    void noTitleForNonVolumeFiles() {
        assertTrue(parser.novelTitleFromLooseFile("GenerateCompletedLinksExecute.bat").isEmpty());
    }
}
