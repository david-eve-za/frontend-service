package com.glez.frontendservice.services.source;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Servicio simple de detección de idioma.
 * Wrapper que puede usar librerías externas (LangDetect, CLD3, etc.)
 * o heurísticas simples basadas en texto.
 */
@Slf4j
@Service
public class LanguageDetectionService {

    /**
     * Detecta idioma ISO 639-1 a partir de texto.
     * Retorna código de 2 letras (ej: "en", "es", "zh", "ja", "ko") o null.
     */
    public String detect(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }

        try {
            // Intentar con Locale.forLanguageTag si parece un tag válido
            // Para detección real, integrar librería como:
            // - com.optimaize.langdetect (LangDetect)
            // - com.github.cld3 (CLD3)
            // - org.apache.tika (Tika Language Identifier)

            // Heurística simple por ahora: buscar caracteres característicos
            return detectByHeuristics(text);
        } catch (Exception e) {
            log.warn("Language detection failed", e);
            return null;
        }
    }

    private String detectByHeuristics(String text) {
        String sample = text.length() > 1000 ? text.substring(0, 1000) : text;

        // Chino (simplificado/tradicional)
        if (sample.matches(".*[\\u4e00-\\u9fff].*")) {
            // Diferenciar zh-CN vs zh-TW por caracteres específicos si necesario
            return "zh";
        }

        // Japonés (hiragana/katakana/kanji)
        if (sample.matches(".*[\\u3040-\\u309f\\u30a0-\\u30ff].*")) {
            return "ja";
        }

        // Coreano (hangul)
        if (sample.matches(".*[\\uac00-\\ud7af].*")) {
            return "ko";
        }

        // Árabe
        if (sample.matches(".*[\\u0600-\\u06ff].*")) {
            return "ar";
        }

        // Cirílico (ruso, etc.)
        if (sample.matches(".*[\\u0400-\\u04ff].*")) {
            return "ru";
        }

        // Tailandés
        if (sample.matches(".*[\\u0e00-\\u0e7f].*")) {
            return "th";
        }

        // Vietnamita (muchos diacríticos)
        if (sample.matches(".*[àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ].*")) {
            return "vi";
        }

        // Por defecto, asumir inglés si es principalmente ASCII
        long asciiCount = sample.chars().filter(c -> c < 128).count();
        double asciiRatio = (double) asciiCount / sample.length();
        if (asciiRatio > 0.9) {
            return "en";
        }

        // Fallback: usar Locale por defecto del sistema
        return Locale.getDefault().getLanguage();
    }

    /**
     * Normaliza código de idioma a ISO 639-1 (2 letras).
     * Ej: "zh-CN" -> "zh", "en-US" -> "en", "spa" -> "es"
     */
    public String normalize(String lang) {
        if (lang == null || lang.isBlank()) {
            return null;
        }
        String base = lang.trim().toLowerCase().split("[_-]")[0];
        // Mapear códigos ISO 639-2/T de 3 letras a 639-1 de 2 letras
        return switch (base) {
            case "spa" -> "es";
            case "eng" -> "en";
            case "fra" -> "fr";
            case "deu" -> "de";
            case "ita" -> "it";
            case "por" -> "pt";
            case "rus" -> "ru";
            case "jpn" -> "ja";
            case "kor" -> "ko";
            case "chi", "zho" -> "zh";
            case "ara" -> "ar";
            case "tha" -> "th";
            case "vie" -> "vi";
            default -> base.length() == 2 ? base : null;
        };
    }
}