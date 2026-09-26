package com.glez.frontendservice.tts;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EdgeTtsClient Tests")
class EdgeTtsClientTest {

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
    @DisplayName("Short voice names are converted to the Microsoft long form")
    void resolveVoice_withShortName_convertsToLongForm() {
        String resolved = EdgeTtsClient.resolveVoice("es-MX-DaliaNeural");

        assertEquals("Microsoft Server Speech Text to Speech Voice (es-MX, DaliaNeural)", resolved);
    }

    @Test
    @DisplayName("Voice names with sub-region keep their region intact")
    void resolveVoice_withSubRegion_convertsToLongForm() {
        String resolved = EdgeTtsClient.resolveVoice("en-US-ChristopherNeural");

        assertEquals("Microsoft Server Speech Text to Speech Voice (en-US, ChristopherNeural)", resolved);
    }

    @Test
    @DisplayName("Long-form voice names are accepted as-is")
    void resolveVoice_withLongName_returnsUnchanged() {
        String longName = "Microsoft Server Speech Text to Speech Voice (es-MX, DaliaNeural)";

        assertEquals(longName, EdgeTtsClient.resolveVoice(longName));
    }

    @Test
    @DisplayName("Invalid voice names are rejected")
    void resolveVoice_withInvalidName_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> EdgeTtsClient.resolveVoice("Paulina"));
        assertThrows(IllegalArgumentException.class,
                () -> EdgeTtsClient.resolveVoice(""));
        assertThrows(IllegalArgumentException.class,
                () -> EdgeTtsClient.resolveVoice(null));
    }

    @Test
    @DisplayName("synthesizeToFile rejects null or blank text")
    void synthesizeToFile_withBlankText_throws() {
        Path output = tempDir.resolve("out.mp3");

        assertThrows(EdgeTtsException.class, () -> client.synthesizeToFile(null, output));
        assertThrows(EdgeTtsException.class, () -> client.synthesizeToFile("", output));
        assertThrows(EdgeTtsException.class, () -> client.synthesizeToFile("   ", output));
    }

    @Test
    @DisplayName("Invalid rate, volume or pitch settings are rejected")
    void synthesizeToFile_withInvalidSettings_throws() {
        Path output = tempDir.resolve("out.mp3");

        setField("rate", "0%");
        assertThrows(IllegalArgumentException.class,
                () -> client.synthesizeToFile("texto de prueba", output));

        setField("rate", "+0%");
        setField("volume", "loud");
        assertThrows(IllegalArgumentException.class,
                () -> client.synthesizeToFile("texto de prueba", output));

        setField("volume", "+0%");
        setField("pitch", "+0Semitone");
        assertThrows(IllegalArgumentException.class,
                () -> client.synthesizeToFile("texto de prueba", output));
    }

    @Test
    @DisplayName("Valid settings pass validation")
    void validateSettings_withValidSettings_doesNotThrow() {
        setField("rate", "-10%");
        setField("volume", "+20%");
        setField("pitch", "-15Hz");

        assertDoesNotThrow(client::validateSettings);
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
