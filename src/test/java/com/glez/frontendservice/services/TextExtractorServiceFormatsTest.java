package com.glez.frontendservice.services;

import com.glez.frontendservice.model.RegexPattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@DisplayName("TextExtractorService EPUB/ZIP formats")
class TextExtractorServiceFormatsTest {

    private static final String MARKER_TEXT = "The quick brown fox jumps over the lazy dog. HELLO_NOVEL_TEXT.";

    private TextExtractorService service;

    @BeforeEach
    void setUp() {
        RegexPatternService regexPatternService = Mockito.mock(RegexPatternService.class);
        when(regexPatternService.getPatternsForTextCleaning()).thenReturn(List.<RegexPattern>of());
        service = new TextExtractorService(regexPatternService);
    }

    @Test
    @DisplayName("extracts text from a minimal in-memory EPUB")
    void extractsTextFromEpub() throws IOException {
        byte[] epub = minimalEpub();

        String text = service.extractAndCleanText(new ByteArrayInputStream(epub), "novel - Volume 01.epub");

        assertTrue(text.contains("HELLO_NOVEL_TEXT"),
                "EPUB text should contain the marker paragraph, got: " + preview(text));
    }

    @Test
    @DisplayName("extracts text from a ZIP wrapping an EPUB (volume zips from elscione)")
    void extractsTextFromZipWrappingEpub() throws IOException {
        byte[] zip = zipOf("novel - Volume 01.epub", minimalEpub());

        String text = service.extractAndCleanText(new ByteArrayInputStream(zip), "novel - Volume 01.zip");

        assertTrue(text.contains("HELLO_NOVEL_TEXT"),
                "ZIP-wrapped EPUB text should contain the marker paragraph, got: " + preview(text));
    }

    @Test
    @DisplayName("extracts text from a plain TXT volume")
    void extractsTextFromTxt() throws IOException {
        String text = service.extractAndCleanText(
                new ByteArrayInputStream(MARKER_TEXT.getBytes(StandardCharsets.UTF_8)),
                "novel - Volume 01.txt");

        assertTrue(text.contains("HELLO_NOVEL_TEXT"));
    }

    /**
     * EPUB minimo valido: mimetype (first, stored) + container.xml + OPF + XHTML.
     */
    private byte[] minimalEpub() throws IOException {
        String xhtml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>Volume 01</title></head>
                  <body>
                    <p>%s</p>
                  </body>
                </html>
                """.formatted(MARKER_TEXT);
        String container = """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """;
        String opf = """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="id">test-id</dc:identifier>
                    <dc:title>Novel Volume 01</dc:title>
                  </metadata>
                  <manifest>
                    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="ch1"/>
                  </spine>
                </package>
                """;

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("mimetype"));
            zip.write("application/epub+zip".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("META-INF/container.xml"));
            zip.write(container.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("OEBPS/content.opf"));
            zip.write(opf.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("OEBPS/ch1.xhtml"));
            zip.write(xhtml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return buffer.toByteArray();
    }

    private byte[] zipOf(String entryName, byte[] content) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content);
            zip.closeEntry();
        }
        return buffer.toByteArray();
    }

    private String preview(String text) {
        if (text == null) {
            return "<null>";
        }
        String flattened = text.replaceAll("\\s+", " ").trim();
        return flattened.substring(0, Math.min(flattened.length(), 160));
    }
}
