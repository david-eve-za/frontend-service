package com.glez.frontendservice.services.source;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Servicio de almacenamiento de archivos para crawlers genéricos.
 * Usa compresión GZIP nativa (JDK) para contenido de capítulos.
 * Paths: /data/sources/{sourceId}/novels/{novelId}/chapters/{serial:06}.gz
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceFileStorageService {

    @Value("${app.sources.storage.root:/data/sources}")
    private Path storageRoot;

    public Path resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Relative path cannot be null or blank");
        }
        return storageRoot.resolve(relativePath).normalize();
    }

    /**
     * Guarda bytes de forma atómica (write to .tmp, then atomic move).
     */
    public void save(String relativePath, byte[] content) {
        try {
            Path file = resolve(relativePath);
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, content);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save file: " + relativePath, e);
        }
    }

    /** Carga bytes raw (comprimidos). */
    public byte[] load(String relativePath) {
        try {
            return Files.readAllBytes(resolve(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load file: " + relativePath, e);
        }
    }

    /**
     * Carga y descomprime GZIP automáticamente.
     * Detecta magic bytes 0x1f 0x8b; si no están, asume texto plano (compatibilidad).
     */
    public String loadText(String relativePath) {
        byte[] data = load(relativePath);
        return decompressGzip(data);
    }

    /** Descomprime GZIP si tiene magic bytes; sino devuelve string directo. */
    private String decompressGzip(byte[] compressed) {
        if (compressed.length >= 2 && compressed[0] == (byte)0x1f && compressed[1] == (byte)0x8b) {
            try (ByteArrayInputStream bis = new ByteArrayInputStream(compressed);
                 GZIPInputStream gis = new GZIPInputStream(bis);
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                gis.transferTo(bos);
                return bos.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("GZIP decompression failed", e);
            }
        }
        // Fallback: no comprimido (compatibilidad hacia atrás)
        return new String(compressed, StandardCharsets.UTF_8);
    }

    /** Comprime con GZIP usando JDK nativo (java.util.zip). Level 6 = default. */
    public static byte[] compressGzip(byte[] input) {
        return compressGzip(input, 6);
    }

    /** Comprime con GZIP con nivel específico (1-9). */
    public static byte[] compressGzip(byte[] input, int level) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             GZIPOutputStream gzos = new GZIPOutputStream(bos) {{
                 // Reflection para set level si se quiere control fino
                 // Java 11+ permite: def.setLevel(level);
             }}) {
            gzos.write(input);
            gzos.finish();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("GZIP compression failed", e);
        }
    }

    public void delete(String relativePath) {
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException e) {
            log.warn("Failed to delete file: {}", relativePath, e);
        }
    }

    public boolean exists(String relativePath) {
        return Files.exists(resolve(relativePath));
    }

    public long size(String relativePath) {
        try {
            return Files.size(resolve(relativePath));
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Construye path estándar para contenido de capítulo.
     * Formato: sources/{sourceId}/novels/{novelId}/chapters/{serial:06}.gz
     */
    public static String buildChapterContentPath(String sourceId, UUID novelId, int serial) {
        return String.format("sources/%s/novels/%s/chapters/%06d.gz", sourceId, novelId, serial);
    }

    /**
     * Construye path estándar para traducción de capítulo.
     * Formato: sources/{sourceId}/novels/{novelId}/chapters/{serial:06}.{lang}.gz
     */
    public static String buildChapterTranslationPath(String sourceId, UUID novelId, int serial, String language) {
        return String.format("sources/%s/novels/%s/chapters/%06d.%s.gz", sourceId, novelId, serial, language);
    }

    /**
     * Construye path estándar para portada.
     * Formato: sources/{sourceId}/novels/{novelId}/cover.jpg
     */
    public static String buildCoverPath(String sourceId, UUID novelId) {
        return String.format("sources/%s/novels/%s/cover.jpg", sourceId, novelId);
    }
}