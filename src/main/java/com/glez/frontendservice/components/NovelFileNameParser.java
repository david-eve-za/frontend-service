package com.glez.frontendservice.components;

import com.glez.frontendservice.model.NovelVolumeFile;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser de los nombres de archivo de la biblioteca elscione. Patrones reales
 * observados:
 *
 * <ul>
 *   <li>"Ai no Kusabi - Volume 01 [June][Scans].pdf"</li>
 *   <li>"The Isle Of Paramounts - Reborn... - Volume 02 [J-Novel Club].epub"</li>
 * </ul>
 *
 * El título de la obra es todo lo que precede al ÚLTIMO segmento " - Volume N"
 * (los títulos pueden contener guiones), y el grupo traductor se extrae de los
 * corchetes finales.
 */
@Component
public class NovelFileNameParser {

    /**
     * Grupo 1: título de la obra; grupo 2: número de volumen (admite 1.5);
     * grupo 3: label crudo del volumen; el resto ([grupos]) va aparte.
     */
    private static final Pattern VOLUME_PATTERN =
            Pattern.compile("^(?<title>.+?)\\s+-\\s+(?<label>Vol(ume)?\\.?\\s*(?<number>\\d+(?:\\.\\d+)?)).*$",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern GROUP_PATTERN =
            Pattern.compile("\\[([^\\]]*)\\]");

    /** Extrae número/label/grupo traductor de un nombre de archivo de volumen. */
    public ParsedVolumeFile parse(String fileName) {
        String stem = stripExtension(fileName);
        NovelVolumeFile.Format format = formatOf(fileName);

        Integer volumeNumber = null;
        String label = stem;
        String translatorGroup = null;

        Matcher matcher = VOLUME_PATTERN.matcher(stem);
        if (matcher.matches()) {
            String number = matcher.group("number");
            volumeNumber = number.contains(".")
                    ? Integer.valueOf((int) Double.parseDouble(number))
                    : Integer.valueOf(number);
            label = matcher.group("label");
            java.util.regex.Matcher groups = GROUP_PATTERN.matcher(stem);
            StringBuilder groupBuilder = new StringBuilder();
            while (groups.find()) {
                if (groupBuilder.length() > 0) {
                    groupBuilder.append(' ');
                }
                groupBuilder.append(groups.group(1).trim());
            }
            if (!groupBuilder.isEmpty()) {
                translatorGroup = groupBuilder.toString();
            }
        }
        return new ParsedVolumeFile(volumeNumber, label, translatorGroup, format);
    }

    /**
     * Título de la obra inferido de un archivo suelto (todo lo que precede al
     * segmento " - Volume N"); vacío si el nombre no sigue el patrón.
     */
    public Optional<String> novelTitleFromLooseFile(String fileName) {
        Matcher matcher = VOLUME_PATTERN.matcher(stripExtension(fileName));
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String title = matcher.group("title").trim();
        // Los corchetes de grupos NO son parte del título.
        return Optional.of(GROUP_PATTERN.matcher(title).replaceAll("").trim())
                .filter(t -> !t.isBlank());
    }

    public NovelVolumeFile.Format formatOf(String fileName) {
        if (fileName == null) {
            return NovelVolumeFile.Format.OTHER;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String ext = dot >= 0 ? lower.substring(dot + 1) : "";
        return switch (ext) {
            case "epub" -> NovelVolumeFile.Format.EPUB;
            case "pdf" -> NovelVolumeFile.Format.PDF;
            case "zip" -> NovelVolumeFile.Format.ZIP;
            case "txt" -> NovelVolumeFile.Format.TXT;
            default -> NovelVolumeFile.Format.OTHER;
        };
    }

    private String stripExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /**
     * Resultado del parseo de un archivo de volumen.
     *
     * @param volumeNumber    número de volumen; nulo si el nombre no lo declara
     * @param label           label legible ("Volume 01" o el stem del archivo)
     * @param translatorGroup grupo(s) traductor(es) de los corchetes; nulo si no hay
     * @param format          formato detectado por extensión
     */
    public record ParsedVolumeFile(Integer volumeNumber, String label,
                                   String translatorGroup, NovelVolumeFile.Format format) {
    }
}
