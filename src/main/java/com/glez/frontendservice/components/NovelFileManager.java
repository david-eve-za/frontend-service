package com.glez.frontendservice.components;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gestiona el árbol local de novelas: sanitiza nombres de ruta (como
 * FileManager.sanitize_path del script Python), resuelve rutas bajo el
 * directorio base de salida y crea directorios de forma idempotente
 * memorizando los ya creados.
 */
@Slf4j
@Component
public class NovelFileManager {

    private final Path baseOutputDir;
    private final Set<Path> createdDirectories = ConcurrentHashMap.newKeySet();

    public NovelFileManager(@Value("${app.novels.output-dir:./output}") String baseOutputDir) {
        this.baseOutputDir = Paths.get(baseOutputDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.baseOutputDir);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create novels output directory " + this.baseOutputDir, e);
        }
    }

    public String sanitizePath(String path) {
        return path.replace("?", "_").replace("=", "_").replace("&", "_").replace(":", "_");
    }

    public Path getLocalPath(List<String> pathSegments) {
        Path result = baseOutputDir;
        for (String segment : pathSegments) {
            String sanitized = sanitizePath(segment);
            if (!sanitized.isEmpty()) {
                result = result.resolve(sanitized);
            }
        }
        return result;
    }

    public Path getBaseOutputDir() {
        return baseOutputDir;
    }

    public void ensureDirectory(Path path) {
        if (createdDirectories.contains(path) && Files.isDirectory(path)) {
            return;
        }
        try {
            Files.createDirectories(path);
            createdDirectories.add(path);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create directory " + path, e);
        }
    }

    public boolean fileExists(Path path) {
        return Files.exists(path);
    }
}
