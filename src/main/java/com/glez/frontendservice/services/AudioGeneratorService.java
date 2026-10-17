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
    private final int ttsPermits;
    private final int connectTimeoutSeconds;
    private final int receiveTimeoutSeconds;
    private final long minTtsTotalTimeoutSeconds;
    private final int mergeTimeoutMinutes;
    private final ExecutorService ttsExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private static final Logger log = LoggerFactory.getLogger(AudioGeneratorService.class);


    public AudioGeneratorService(SmartTextSplitter textSplitter,
                                 EdgeTtsClient edgeTtsClient,
                                 @Value("${app.audio.tts-concurrency:3}") int ttsConcurrency,
                                 @Value("${app.audio.connect-timeout:10}") int connectTimeoutSeconds,
                                 @Value("${app.audio.receive-timeout:60}") int receiveTimeoutSeconds,
                                 @Value("${app.audio.tts-min-total-timeout-seconds:600}") long minTtsTotalTimeoutSeconds,
                                 @Value("${app.audio.merge-timeout-minutes:15}") int mergeTimeoutMinutes) {
        this.textSplitter = textSplitter;
        this.edgeTtsClient = edgeTtsClient;
        this.ttsPermits = Math.max(1, ttsConcurrency);
        this.ttsConcurrency = new Semaphore(ttsPermits, true);
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.receiveTimeoutSeconds = receiveTimeoutSeconds;
        this.minTtsTotalTimeoutSeconds = minTtsTotalTimeoutSeconds;
        this.mergeTimeoutMinutes = mergeTimeoutMinutes;
    }

    // Mapa de normalización idéntico a tu versión Python
    private static final Map<String, String> NORMALIZATION_MAP = Map.of(
            "”", "\"", "“", "\"",
            "’", "'", "‘", "'",
            "—", "-", "–", "-",
            "…", "...",
            "<br>", "\n"
    );

    /**
     * Sintetiza un texto (el de un bloque o el libro completo) a un mp3:
     * trocea en fragmentos de ~1000 caracteres, los sintetiza en paralelo
     * con Edge TTS (con un tope de concurrencia) y los mezcla con ffmpeg.
     *
     * <p>Hitos de log: el éxito se registra en INFO (ruta destino,
     * fragmentos y caracteres — el tamaño de un audio puede sorprender en
     * un análisis posterior) y los fallos ya tenían sus WARN/ERROR; el
     * texto por debajo del mínimo del splitter se sintetiza como pieza
     * única (el splitter lo descartaría en silencio).</p>
     */
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
            log.info("Sintetizando audio de {} caracteres en {} fragmento(s) hacia {}",
                    textContent.length(), chunks.size(), outputFilename.getFileName());

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
                CompletableFuture.allOf(futures).get(ttsTotalTimeoutSeconds(total), TimeUnit.SECONDS);
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

    /**
     * Presupuesto total de la síntesis (red de seguridad, no el detector
     * de cuelgues — cada fragmento ya está acotado por los timeouts de
     * Edge TTS): cada "ola" de {@code ttsPermits} fragmentos en paralelo
     * solo puede durar lo que su fragmento más lento (connect + receive
     * timeout + margen), así que el total es olas × techo por fragmento
     * con un suelo mínimo configurable. El cap FIJO de 10 min mataba
     * corridas legítimas de libros completos (cientos de fragmentos a
     * ~15 s por pieza con 3 en paralelo).
     */
    long ttsTotalTimeoutSeconds(int fragments) {
        long waves = (long) Math.ceil((double) fragments / ttsPermits);
        long perFragmentCeiling = connectTimeoutSeconds + receiveTimeoutSeconds + 15L;
        return Math.max(minTtsTotalTimeoutSeconds, waves * perFragmentCeiling);
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
        // ffmpeg escribe su banner y las estadísticas de progreso en
        // stderr: con los pipes por defecto de ProcessBuilder, el búfer
        // del SO (~64 KB) se llena en mezclas largas y ffmpeg se BLOQUEA
        // escribiendo — waitFor() nunca regresaría y el paso AUDIO
        // quedaría colgado con el libro en PROCESSING. La salida se
        // descarta: el exit code es el veredicto.
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);

        Process process = pb.start();
        boolean finished = process.waitFor(mergeTimeoutMinutes, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            log.error("ffmpeg excedió el tope de {} min mezclando {} fragmentos hacia {}; proceso terminado a la fuerza",
                    mergeTimeoutMinutes, audioFiles.size(), targetFile);
            return false;
        }
        return process.exitValue() == 0;
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