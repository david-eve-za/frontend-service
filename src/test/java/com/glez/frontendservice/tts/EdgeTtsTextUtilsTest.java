package com.glez.frontendservice.tts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EdgeTtsTextUtils Tests")
class EdgeTtsTextUtilsTest {

    @Test
    @DisplayName("Incompatible control characters are replaced with spaces")
    void removeIncompatibleCharacters_replacesControlChars() {
        String input = "a\u0000b\u000Bc\u001Fd\ne\rf";

        String result = EdgeTtsTextUtils.removeIncompatibleCharacters(input);

        assertEquals("a b c d\ne\rf", result);
    }

    @Test
    @DisplayName("Text without incompatible characters is unchanged")
    void removeIncompatibleCharacters_withCleanText_returnsSameText() {
        String input = "Texto normal con acentos: áéíóú ñ";

        assertEquals(input, EdgeTtsTextUtils.removeIncompatibleCharacters(input));
    }

    @Test
    @DisplayName("XML special characters are escaped")
    void escapeXml_escapesSpecialChars() {
        String ampEntity = "&" + "amp;";
        String ltEntity = "&" + "lt;";
        String gtEntity = "&" + "gt;";

        String result = EdgeTtsTextUtils.escapeXml("a & b < c > d \"quote\" 'quote'");

        assertEquals("a " + ampEntity + " b " + ltEntity + " c " + gtEntity
                + " d \"quote\" 'quote'", result);
    }

    @Test
    @DisplayName("Text shorter than the limit yields a single chunk")
    void splitByByteLength_withShortText_returnsSingleChunk() {
        List<String> chunks = EdgeTtsTextUtils.splitByByteLength("short text", 100);

        assertEquals(List.of("short text"), chunks);
    }

    @Test
    @DisplayName("Splitting prefers the last space within the limit")
    void splitByByteLength_splitsAtSpace() {
        List<String> chunks = EdgeTtsTextUtils.splitByByteLength("aaaa bbbb cccc", 10);

        assertEquals(List.of("aaaa bbbb", "cccc"), chunks);
    }

    @Test
    @DisplayName("Splitting prefers newlines over spaces")
    void splitByByteLength_prefersNewline() {
        List<String> chunks = EdgeTtsTextUtils.splitByByteLength("aa\nbb cc", 6);

        assertEquals(List.of("aa", "bb cc"), chunks);
    }

    @Test
    @DisplayName("Splitting never cuts a multi-byte UTF-8 character")
    void splitByByteLength_withMultibyteChar_neverSplitsMidCharacter() {
        // U+1F600 occupies 4 UTF-8 bytes.
        List<String> chunks = EdgeTtsTextUtils.splitByByteLength("ab\uD83D\uDE00cd", 4);

        assertEquals(List.of("ab", "\uD83D\uDE00", "cd"), chunks);
    }

    @Test
    @DisplayName("Splitting never cuts an XML entity in the middle")
    void splitByByteLength_withXmlEntity_neverSplitsEntity() {
        String ampEntity = "&" + "amp;";
        // "tom" (3 bytes) + entity (5 bytes) + "jerry" (5 bytes), limit 6.
        // The entity must never be cut: the first split lands right before '&'
        // and the second keeps the whole "&" together.
        List<String> chunks = EdgeTtsTextUtils.splitByByteLength("tom" + ampEntity + "jerry", 6);

        assertEquals(List.of("tom", ampEntity + "j", "erry"), chunks);
        for (String chunk : chunks) {
            // No chunk may end with an unterminated entity.
            int amp = chunk.lastIndexOf('&');
            assertTrue(amp < 0 || chunk.indexOf(';', amp) >= 0,
                    "Chunk contains a cut entity: " + chunk);
        }
    }

    @Test
    @DisplayName("Empty and whitespace-only chunks are dropped")
    void splitByByteLength_dropsEmptyChunks() {
        List<String> chunks = EdgeTtsTextUtils.splitByByteLength("word1    word2", 8);

        for (String chunk : chunks) {
            assertFalse(chunk.isBlank(), "No chunk may be blank: " + chunks);
        }
        assertEquals(chunks.size(), chunks.stream().distinct().toList().size());
    }

    @Test
    @DisplayName("Every chunk respects the byte limit for long unicode text")
    void splitByByteLength_withLongUnicodeText_respectsLimit() {
        String paragraph = "El zorro marrón rápido salta sobre el perro perezoso. "
                + "La niña cantó una canción hermosa en español. "
                + "😀😀😀 emoji intercalados para forzar cortes multibyte. ".repeat(3);
        int limit = 100;

        List<String> chunks = EdgeTtsTextUtils.splitByByteLength(paragraph, limit);

        assertFalse(chunks.isEmpty());
        for (String chunk : chunks) {
            assertTrue(chunk.getBytes(StandardCharsets.UTF_8).length <= limit,
                    "Chunk exceeds the limit: " + chunk);
        }
        // No content may be lost: everything except whitespace must survive.
        String rejoined = String.join("", chunks).replaceAll("\\s", "");
        assertEquals(paragraph.replaceAll("\\s", ""), rejoined);
    }

    @Test
    @DisplayName("Non-positive limits are rejected")
    void splitByByteLength_withInvalidLimit_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> EdgeTtsTextUtils.splitByByteLength("text", 0));
        assertThrows(IllegalArgumentException.class,
                () -> EdgeTtsTextUtils.splitByByteLength("text", -1));
    }

    @Test
    @DisplayName("Blank text yields no chunks")
    void splitByByteLength_withBlankText_returnsEmptyList() {
        List<String> chunks = EdgeTtsTextUtils.splitByByteLength("   \n  ", 10);

        assertTrue(chunks.isEmpty());
    }
}
