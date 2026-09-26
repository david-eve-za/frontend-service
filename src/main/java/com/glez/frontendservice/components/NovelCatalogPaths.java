package com.glez.frontendservice.components;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Traduce hrefs remotos de elscione (percent-encoded, desde la raíz del
 * servidor) a rutas locales del directorio de descargas del crawler, de
 * forma que el gestor de novelas pueda deduplicar: un archivo ya descargado
 * por novels-sync se reutiliza en lugar de volver a descargarse.
 */
@Component
public class NovelCatalogPaths {

    private final NovelFileManager fileManager;
    private final String startPath;
    private final String decodedStartPath;

    public NovelCatalogPaths(NovelFileManager fileManager,
                             @Value("${app.novels.base-url}") String baseUrl) {
        this.fileManager = fileManager;
        this.startPath = URI.create(baseUrl).getRawPath();
        this.decodedStartPath = URLDecoder.decode(startPath, StandardCharsets.UTF_8);
    }

    /** Ruta local esperada de un archivo remoto (puede no existir aún). */
    public Path localPathOf(String remoteHref) {
        String decoded = URLDecoder.decode(remoteHref, StandardCharsets.UTF_8);
        String relative = decoded.startsWith(decodedStartPath)
                ? decoded.substring(decodedStartPath.length())
                : decoded;
        List<String> segments = Arrays.stream(relative.split("/"))
                .filter(segment -> !segment.isBlank())
                .toList();
        return fileManager.getLocalPath(segments);
    }

    /** true si el archivo remoto ya existe en el directorio local de descargas. */
    public boolean isPresentLocally(String remoteHref) {
        return fileManager.fileExists(localPathOf(remoteHref));
    }
}
