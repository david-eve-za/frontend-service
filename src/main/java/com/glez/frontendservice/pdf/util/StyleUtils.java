package com.glez.frontendservice.pdf.util;

import org.apache.pdfbox.pdmodel.font.PDFont;
import java.util.stream.Stream; // Required for Stream.of

public class StyleUtils {

    // 1. Add a private constructor to prevent instantiation of this utility class.
    private StyleUtils() {
        throw new IllegalStateException("Utility class");
    }

    public static boolean isBold(PDFont font) {
        // 2. Add a null check for font.getName() for increased robustness.
        if (font == null || font.getName() == null) {
            return false;
        }
        String name = font.getName().toLowerCase();
        // 3. Use Stream.anyMatch for a more declarative and scalable check.
        return Stream.of("bold", "bld", "black").anyMatch(name::contains);
    }

    public static boolean isItalic(PDFont font) {
        // 2. Add a null check for font.getName() for increased robustness.
        if (font == null || font.getName() == null) {
            return false;
        }
        String name = font.getName().toLowerCase();
        // 3. Use Stream.anyMatch for a more declarative and scalable check.
        return Stream.of("italic", "oblique").anyMatch(name::contains);
    }
}