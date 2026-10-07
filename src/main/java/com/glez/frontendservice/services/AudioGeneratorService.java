package com.glez.frontendservice.services;


import com.glez.frontendservice.components.SmartTextSplitter;
import com.glez.frontendservice.tts.EdgeTtsClient;
import com.glez.frontendservice.tts.EdgeTtsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class AudioGeneratorService {

    private final SmartTextSplitter textSplitter;
    private final EdgeTtsClient edgeTtsClient;
    private final Semaphore ttsConcurrency;
    private final ExecutorService ttsExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private static final Logger log = LoggerFactory.getLogger(AudioGeneratorService.class);


    public AudioGeneratorService(SmartTextSplitter textSplitter,
                                 EdgeTtsClient edgeTtsClient,
                                 @Value("${app.audio.tts-concurrency:3}") int ttsConcurrency) {
        this.textSplitter = textSplitter;
        this.edgeTtsClient = edgeTtsClient;
        this.ttsConcurrency = new Semaphore(Math.max(1, ttsConcurrency), true);
    }

    // Mapa de normalización idéntico a tu versión Python
    private static final Map<String, String> NORMALIZATION_MAP = Map.of(
            "”", "\"", "“", "\"",
            "’", "'", "‘", "'",
            "—", "-", "–", "-",
            "…", "...",
            "<br>", "\n"
    );

    public boolean processTextToAudio(String textContent, Path outputFilename) {
        if (textContent == null || textContent.isBlank()) {
            log.warn("El texto de entrada está vacío. Saltando generación de audio.");
            return false;
        }

        try {
            List<String> chunks = textSplitter.split(textContent, "es",1000);
            // El TokenTextSplitter descarta textos por debajo de su mínimo
            // (~350 chars): un bloque corto se sintetiza como pieza única.
            if (chunks.isEmpty()) {
                chunks = List.of(textContent);
            }

            // 2. Crear directorio temporal gestionado
            Path tempDir = Files.createTempDirectory("audio_chunks_");

            try {
                // 2b. Sintetizar los fragmentos en paralelo con virtual
                // threads, con un tope suave de concurrencia hacia Edge
                // TTS (p.ej. 3 simultáneas) y conservando el orden de
                // mezcla posterior.
                int total = chunks.size();
                @SuppressWarnings("unchecked")
                CompletableFuture<Path>[] futures = new CompletableFuture[total];
                for (int i = 0; i < total; i++) {
                    final int index = i;
                    final String normalized = normalizeText(chunks.get(i));
                    futures[i] = CompletableFuture.supplyAsync(() -> {
                        ttsConcurrency.acquireUninterruptibly();
                        try {
                            Path chunkPath = tempDir.resolve(String.format("chunk_%04d.mp3", index));
                            return textToAudio(normalized, chunkPath) ? chunkPath : null;
                        } finally {
                            ttsConcurrency.release();
                        }
                    }, ttsExecutor);
                }
                CompletableFuture.allOf(futures).get(10, TimeUnit.MINUTES);
                List<Path> audioFiles = new ArrayList<>(total);
                for (CompletableFuture<Path> future : futures) {
                    Path path = future.getNow(null);
                    if (path != null) {
                        audioFiles.add(path);
                    }
                }

                if (audioFiles.isEmpty()) {
                    log.warn("Ningún fragmento de audio pudo generarse para la entrada; ¿falló Edge TTS?");
                    return false;
                }

                // 3. Mezclar archivos con ffmpeg
                return mergeAudioFiles(audioFiles, outputFilename, tempDir);

            } finally {
                // Limpieza de archivos temporales (equivalente a TemporaryDirectory en Python)
                deleteDirectoryRecursively(tempDir);
            }

        } catch (Exception e) {
            log.error("Error inesperado en el procesamiento de audio", e);
            return false;
        }
    }

    private String normalizeText(String text) {
        String result = text;
        for (Map.Entry<String, String> entry : NORMALIZATION_MAP.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private boolean textToAudio(String text, Path outputPath) {
        try {
            edgeTtsClient.synthesizeToFile(text, outputPath);
            return true;
        } catch (EdgeTtsException e) {
            log.error("Falló la síntesis de audio con Edge TTS para el fragmento", e);
            return false;
        }
    }

    private boolean mergeAudioFiles(List<Path> audioFiles, Path targetFile, Path tempDir) throws IOException, InterruptedException {
        // Crear el archivo de lista para ffmpeg
        Path listFile = tempDir.resolve("ffmpeg_file_list.txt");
        try (BufferedWriter writer = Files.newBufferedWriter(listFile)) {
            for (Path file : audioFiles) {
                writer.write(String.format("file '%s'\n", file.toAbsolutePath()));
            }
        }

        // Ejecutar ffmpeg con los mismos parámetros de tu script
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", listFile.toString(),
                "-af", "volume=0.3dB", "-ac", "2", "-c:a", "libmp3lame", "-b:a", "64k", "-ar", "24000",
                targetFile.toString()
        );

        return pb.start().waitFor() == 0;
    }

    private void deleteDirectoryRecursively(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                    .sorted((p1, p2) -> p2.compareTo(p1))
                    .forEach(p -> {
                        try { Files.delete(p); } catch (IOException ignored) {}
                    });
        }
    }
}