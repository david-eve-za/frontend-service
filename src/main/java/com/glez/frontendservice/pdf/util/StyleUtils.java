package com.glez.frontendservice.pdf.util;

import org.apache.pdfbox.pdmodel.font.PDFont;

import java.util.stream.Stream;

public class StyleUtils {

    private StyleUtils() {
        throw new IllegalStateException("Utility class");
    }

    public static boolean isBold(PDFont font) {
        if (font == null || font.getName() == null) {
            return false;
        }
        String name = font.getName().toLowerCase();
        return Stream.of("bold", "bld", "black").anyMatch(name::contains);
    }

    public static boolean isItalic(PDFont font) {
        if (font == null || font.getName() == null) {
            return false;
        }
        String name = font.getName().toLowerCase();
        return Stream.of("italic", "oblique").anyMatch(name::contains);
    }
}