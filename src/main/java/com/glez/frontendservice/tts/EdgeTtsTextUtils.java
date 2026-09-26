package com.glez.frontendservice.tts;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Text preprocessing utilities for the Edge TTS protocol, ported from the
 * Python edge-tts package: removal of characters the service does not
 * support, XML escaping and byte-length-aware text splitting.
 */
final class EdgeTtsTextUtils {

    private EdgeTtsTextUtils() {
    }

    /**
     * The service does not support a couple of character ranges. Most
     * important being the vertical tab character which is commonly present
     * in OCR-ed PDFs. Characters in [0-8], [11-12] and [14-31] are replaced
     * by a space.
     */
    static String removeIncompatibleCharacters(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            if ((cp >= 0 && cp <= 8) || (cp >= 11 && cp <= 12) || (cp >= 14 && cp <= 31)) {
                sb.append(' ');
            } else {
                sb.append(Character.toChars(cp));
            }
        });
        return sb.toString();
    }

    /**
     * Escapes the XML special characters {@code &}, {@code <} and {@code >},
     * equivalent to Python's {@code xml.sax.saxutils.escape}.
     */
    static String escapeXml(String text) {
        // Entities are assembled from fragments to keep the source intact.
        String ampEntity = "&" + "amp;";
        String ltEntity = "&" + "lt;";
        String gtEntity = "&" + "gt;";
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append(ampEntity);
                case '<' -> sb.append(ltEntity);
                case '>' -> sb.append(gtEntity);
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Splits text into UTF-8 chunks, each not exceeding {@code byteLength}
     * bytes. Splitting prioritizes natural boundaries (newlines over spaces),
     * never cuts a multi-byte UTF-8 character in half and never splits an
     * XML entity (like {@code &}) in the middle.
     */
    static List<String> splitByByteLength(String text, int byteLength) {
        if (byteLength <= 0) {
            throw new IllegalArgumentException("byteLength must be greater than 0");
        }
        List<String> chunks = new ArrayList<>();
        byte[] remaining = text.getBytes(StandardCharsets.UTF_8);

        while (remaining.length > byteLength) {
            int splitAt = findLastNewlineOrSpaceWithinLimit(remaining, byteLength);

            if (splitAt < 0) {
                // No newline or space found, split at a safe UTF-8 boundary
                // within the limit instead.
                splitAt = findSafeUtf8SplitPoint(remaining, byteLength);
            }

            // Avoid cutting an XML entity (e.g. "&") in the middle.
            splitAt = adjustSplitPointForXmlEntity(remaining, splitAt);

            if (splitAt <= 0) {
                throw new IllegalArgumentException(
                        "Maximum byte length is too small or invalid text structure near '&' or invalid UTF-8");
            }

            byte[] stripped = strip(remaining, 0, splitAt);
            if (stripped.length > 0) {
                chunks.add(new String(stripped, StandardCharsets.UTF_8));
            }

            remaining = slice(remaining, splitAt);
        }

        byte[] stripped = strip(remaining, 0, remaining.length);
        if (stripped.length > 0) {
            chunks.add(new String(stripped, StandardCharsets.UTF_8));
        }
        return chunks;
    }

    /**
     * Finds the index of the rightmost newline (preferred) or space within
     * the first {@code limit} bytes, or -1 if neither is found.
     */
    private static int findLastNewlineOrSpaceWithinLimit(byte[] text, int limit) {
        int splitAt = lastIndexOf(text, 0, limit, (byte) '\n');
        if (splitAt < 0) {
            splitAt = lastIndexOf(text, 0, limit, (byte) ' ');
        }
        return splitAt;
    }

    /**
     * Finds the rightmost byte index within the first {@code limit} bytes
     * such that the prefix {@code text[0..index)} is a valid UTF-8 sequence,
     * preventing a split in the middle of a multi-byte character.
     */
    private static int findSafeUtf8SplitPoint(byte[] text, int limit) {
        int end = Math.min(limit, text.length);
        if (end == 0) {
            return 0;
        }
        // Walk back over UTF-8 continuation bytes (0b10xxxxxx).
        int i = end - 1;
        while (i >= 0 && (text[i] & 0xC0) == 0x80) {
            i--;
        }
        if (i < 0) {
            return 0;
        }
        int sequenceLength = utf8SequenceLength(text[i]);
        if (sequenceLength < 0) {
            return 0;
        }
        // If the sequence is complete inside the prefix, the split is safe.
        if (i + sequenceLength <= end) {
            return end;
        }
        // The sequence is truncated by the limit: exclude the character.
        return i;
    }

    private static int utf8SequenceLength(byte leadingByte) {
        if ((leadingByte & 0x80) == 0) {
            return 1;
        }
        if ((leadingByte & 0xE0) == 0xC0) {
            return 2;
        }
        if ((leadingByte & 0xF0) == 0xE0) {
            return 3;
        }
        if ((leadingByte & 0xF8) == 0xF0) {
            return 4;
        }
        return -1;
    }

    /**
     * Moves the proposed split point backward to prevent splitting inside
     * an XML entity such as {@code &}.
     */
    private static int adjustSplitPointForXmlEntity(byte[] text, int splitAt) {
        while (splitAt > 0) {
            int ampersandIndex = lastIndexOf(text, 0, splitAt, (byte) '&');
            if (ampersandIndex < 0) {
                break;
            }
            if (indexOf(text, ampersandIndex, splitAt, (byte) ';') != -1) {
                // The entity is terminated before the split point: safe.
                break;
            }
            // Unterminated entity: move the split point to the ampersand.
            splitAt = ampersandIndex;
        }
        return splitAt;
    }

    /**
     * Strips ASCII whitespace from both ends of {@code text[start..end)}.
     */
    private static byte[] strip(byte[] text, int start, int end) {
        int from = start;
        int to = end;
        while (from < to && isAsciiWhitespace(text[from])) {
            from++;
        }
        while (to > from && isAsciiWhitespace(text[to - 1])) {
            to--;
        }
        byte[] result = new byte[to - from];
        System.arraycopy(text, from, result, 0, to - from);
        return result;
    }

    private static boolean isAsciiWhitespace(byte b) {
        return b == ' ' || b == '\t' || b == '\n' || b == 0x0B || b == 0x0C || b == '\r';
    }

    private static byte[] slice(byte[] text, int from) {
        byte[] result = new byte[text.length - from];
        System.arraycopy(text, from, result, 0, result.length);
        return result;
    }

    private static int lastIndexOf(byte[] haystack, int from, int to, byte needle) {
        for (int i = to - 1; i >= from; i--) {
            if (haystack[i] == needle) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOf(byte[] haystack, int from, int to, byte needle) {
        for (int i = from; i < to; i++) {
            if (haystack[i] == needle) {
                return i;
            }
        }
        return -1;
    }
}
