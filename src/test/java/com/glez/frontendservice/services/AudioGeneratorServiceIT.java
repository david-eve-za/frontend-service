package com.glez.frontendservice.services;

import com.glez.frontendservice.components.SmartTextSplitter;
import com.glez.frontendservice.tts.EdgeTtsClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test of the audio generation pipeline:
 * text splitting, Edge TTS synthesis and ffmpeg merge.
 * Opt-in only: run with -Dedgetts.it=true
 */
@EnabledIfSystemProperty(named = "edgetts.it", matches = "true")
@DisplayName("AudioGeneratorService Integration Tests (live pipeline)")
class AudioGeneratorServiceIT {

    private SmartTextSplitter textSplitter;
    private EdgeTtsClient edgeTtsClient;
    private AudioGeneratorService audioGeneratorService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        textSplitter = new SmartTextSplitter();
        // Replicates the injection performed by Spring from application.yml.
        setField(textSplitter, "modelPath", "classpath:/model");
        setField(textSplitter, "enModel", "opennlp-en-ud-ewt-sentence-1.3-2.5.4.bin");
        setField(textSplitter, "esModel", "opennlp-es-ud-gsd-sentence-1.3-2.5.4.bin");
        setField(textSplitter, "chunkSize", 5000);
        setField(textSplitter, "chunkOverlap", 100);
        textSplitter.init();

        edgeTtsClient = new EdgeTtsClient();
        setField(edgeTtsClient, "voice", "es-MX-DaliaNeural");
        setField(edgeTtsClient, "rate", "+0%");
        setField(edgeTtsClient, "volume", "+0%");
        setField(edgeTtsClient, "pitch", "+0Hz");
        setField(edgeTtsClient, "connectTimeoutSeconds", 10);
        setField(edgeTtsClient, "receiveTimeoutSeconds", 60);
        audioGeneratorService = new AudioGeneratorService(textSplitter, edgeTtsClient);
    }

    @Test
    @DisplayName("Full pipeline: text -> Edge TTS chunks -> ffmpeg merge -> playable MP3")
    void processTextToAudio_livePipeline_producesMergedMp3() throws Exception {
        // Note: the target filename must carry a media extension, otherwise
        // ffmpeg cannot pick an output muxer (pre-existing pipeline constraint).
        Path output = tempDir.resolve("libro-prueba.mp3");
        String text = "Bienvenido al servicio de traducción de libros. "
                + "Este texto será convertido a audio mediante la síntesis de voz de Microsoft Edge. "
                + "La generación funciona por fragmentos que luego se mezclan con ffmpeg.";

        boolean result = audioGeneratorService.processTextToAudio(text, output);

        assertTrue(result, "processTextToAudio must succeed end to end");
        assertTrue(Files.exists(output), "Merged audio file must exist");
        long size = Files.size(output);
        assertTrue(size > 1000, "Merged audio too small: " + size + " bytes");
    }

    private void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field " + name, e);
        }
    }
}
