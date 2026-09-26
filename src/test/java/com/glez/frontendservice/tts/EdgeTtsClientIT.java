package com.glez.frontendservice.tts;

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
 * Live integration tests against the real Microsoft Edge TTS service.
 * Opt-in only: run with -Dedgetts.it=true
 */
@EnabledIfSystemProperty(named = "edgetts.it", matches = "true")
@DisplayName("EdgeTtsClient Integration Tests (live service)")
class EdgeTtsClientIT {

    private EdgeTtsClient client;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        client = new EdgeTtsClient();
        setField("voice", "es-MX-DaliaNeural");
        setField("rate", "+0%");
        setField("volume", "+0%");
        setField("pitch", "+0Hz");
        setField("connectTimeoutSeconds", 10);
        setField("receiveTimeoutSeconds", 60);
    }

    @Test
    @DisplayName("Short text is synthesized to a playable MP3")
    void synthesizeToFile_shortText_producesMp3() throws Exception {
        Path output = tempDir.resolve("short.mp3");

        client.synthesizeToFile("Hola mundo, esta es una prueba de síntesis de voz con Edge TTS.", output);

        assertTrue(Files.exists(output), "Output file must exist");
        long size = Files.size(output);
        assertTrue(size > 1000, "Audio too small to be valid: " + size + " bytes");
        byte[] data = Files.readAllBytes(output);
        assertEquals(0xFF, data[0] & 0xFF, "MP3 must start with a frame sync byte");
    }

    @Test
    @DisplayName("Long text is split into several synthesis requests and merged correctly")
    void synthesizeToFile_longText_producesSingleMp3() throws Exception {
        Path output = tempDir.resolve("long.mp3");
        String paragraph = "El veloz zorro marrón salta sobre el perro perezoso mientras la luna "
                + "ilumina el tranquilo valle. ";
        String longText = paragraph.repeat(40); // ~22 KB, several 4 KB chunks

        client.synthesizeToFile(longText, output);

        long size = Files.size(output);
        assertTrue(size > 100_000, "Long audio expected but got " + size + " bytes");
        byte[] data = Files.readAllBytes(output);
        assertEquals(0xFF, data[0] & 0xFF, "MP3 must start with a frame sync byte");
    }

    private void setField(String name, Object value) {
        try {
            Field field = EdgeTtsClient.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(client, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field " + name, e);
        }
    }
}
