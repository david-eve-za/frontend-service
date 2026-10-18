package com.glez.frontendservice.services;

import com.glez.frontendservice.model.Book;
import com.glez.frontendservice.model.NovelVolume;
import com.glez.frontendservice.model.ProcessingStatus;
import com.glez.frontendservice.repository.BookRepository;
import com.glez.frontendservice.repository.NovelVolumeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("VolumeAudioDownloadService Tests")
class VolumeAudioDownloadServiceTest {

    @Mock
    private NovelVolumeRepository volumeRepository;

    @Mock
    private BookRepository bookRepository;

    private VolumeAudioDownloadService service;

    @TempDir
    Path tempDir;

    private UUID volumeId;
    private UUID bookId;

    @BeforeEach
    void setUp() {
        service = new VolumeAudioDownloadService(volumeRepository, bookRepository);
        volumeId = UUID.randomUUID();
        bookId = UUID.randomUUID();
    }

    private void stubVolumeWithBook() {
        NovelVolume volume = new NovelVolume();
        Book book = new Book();
        book.setId(bookId);
        volume.setBook(book);
        when(volumeRepository.findById(volumeId)).thenReturn(Optional.of(volume));
    }

    private void stubBookAudio(String audioPath) {
        when(bookRepository.findAudioViewById(bookId)).thenReturn(Optional.of(audioView(audioPath)));
    }

    private record AudioViewRow(UUID id, String name, String audioFilePath, String ttsVoice)
            implements BookRepository.BookAudioView {
        @Override
        public UUID getId() {
            return id;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getAudioFilePath() {
            return audioFilePath;
        }

        @Override
        public String getTtsVoice() {
            return ttsVoice;
        }
    }

    private BookRepository.BookAudioView audioView(String audioFilePath) {
        return new AudioViewRow(bookId, "una novela - Volume 01", audioFilePath, null);
    }

    @Test
    @DisplayName("Audio legado (archivo único): se sirve el mp3 con su nombre de archivo")
    void resolve_legacySingleFile_servesMp3() throws IOException {
        Path mp3 = tempDir.resolve("una novela - Volume 01.mp3");
        Files.writeString(mp3, "fake mp3 bytes");
        stubVolumeWithBook();
        stubBookAudio(mp3.toString());

        VolumeAudioDownloadService.AudioDownload download = service.resolve(volumeId);

        assertFalse(download.zip());
        assertEquals("una novela - Volume 01.mp3", download.filename());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.writeTo(download, out);
        assertEquals("fake mp3 bytes", out.toString());
    }

    @Test
    @DisplayName("El formato de voz corta se valida antes de tocar la BD (isValidShortVoice)")
    void isValidShortVoice_validatesEdgeVoiceNames() {
        assertTrue(com.glez.frontendservice.tts.EdgeTtsClient.isValidShortVoice("es-MX-JorgeNeural"));
        assertTrue(com.glez.frontendservice.tts.EdgeTtsClient.isValidShortVoice("en-US-EmmaMultilingualNeural"));
        assertFalse(com.glez.frontendservice.tts.EdgeTtsClient.isValidShortVoice("Paulina"),
                "Las voces de macOS 'say' ya no son válidas: Edge espera short names");
        assertFalse(com.glez.frontendservice.tts.EdgeTtsClient.isValidShortVoice("no soy una voz"));
        assertFalse(com.glez.frontendservice.tts.EdgeTtsClient.isValidShortVoice(null));
    }

    @Test
    @DisplayName("Audio por bloques (directorio): ZIP al vuelo con los mp3 en orden de lectura")
    void resolve_blockDirectory_servesZipOnTheFly() throws IOException {
        Path audioDir = tempDir.resolve("una novela - Volume 01 - audiolibro");
        Files.createDirectories(audioDir);
        Files.writeString(audioDir.resolve("01 - Prologo.mp3"), "prologo");
        Files.writeString(audioDir.resolve("02 - Capitulo 01.mp3"), "capitulo");
        Files.writeString(audioDir.resolve("03 - Epilogo.mp3"), "epilogo");
        Files.writeString(audioDir.resolve("notas.txt"), "ruido que no viaja");
        stubVolumeWithBook();
        stubBookAudio(audioDir.toString());

        VolumeAudioDownloadService.AudioDownload download = service.resolve(volumeId);

        assertTrue(download.zip());
        assertEquals("una novela - Volume 01 - audiolibro.zip", download.filename());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.writeTo(download, out);

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry first = zip.getNextEntry();
            assertNotNull(first);
            assertEquals("01 - Prologo.mp3", first.getName(), "Orden de lectura preservado");
            ZipEntry second = zip.getNextEntry();
            assertNotNull(second);
            assertEquals("02 - Capitulo 01.mp3", second.getName());
            ZipEntry third = zip.getNextEntry();
            assertNotNull(third);
            assertEquals("03 - Epilogo.mp3", third.getName());
            assertNull(zip.getNextEntry(), "El .txt del directorio NO viaja en el ZIP");
        }
    }

    @Test
    @DisplayName("Volumen sin Book enlazado: error claro")
    void resolve_withoutBook_throws() {
        NovelVolume volume = new NovelVolume();
        when(volumeRepository.findById(volumeId)).thenReturn(Optional.of(volume));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.resolve(volumeId));
        assertTrue(e.getMessage().contains("no pipeline book"));
    }

    @Test
    @DisplayName("Book sin audio generado: error claro (404 en el endpoint)")
    void resolve_withoutAudio_throws() {
        stubVolumeWithBook();
        stubBookAudio(null);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.resolve(volumeId));
        assertTrue(e.getMessage().contains("no generated audio"));
    }

    @Test
    @DisplayName("Audio apuntado por la BD pero borrado del disco: error distinguible (409)")
    void resolve_audioVanishedFromDisk_throws() {
        stubVolumeWithBook();
        stubBookAudio(tempDir.resolve("borrado.mp3").toString());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.resolve(volumeId));
        assertTrue(e.getMessage().contains("vanished from disk"));
    }

    @Test
    @DisplayName("Directorio de audio sin mp3: error distinguible")
    void resolve_directoryWithoutMp3s_throws() throws IOException {
        Path audioDir = tempDir.resolve("vacio - audiolibro");
        Files.createDirectories(audioDir);
        stubVolumeWithBook();
        stubBookAudio(audioDir.toString());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.resolve(volumeId));
        assertTrue(e.getMessage().contains("no mp3 files"));
    }

    @Test
    @DisplayName("La proyección del Book no se carga por findById (nunca toca el CLOB)")
    void resolve_usesLightProjection_neverLoadsTheBookEntity() {
        stubVolumeWithBook();
        stubBookAudio(null);

        assertThrows(IllegalArgumentException.class, () -> service.resolve(volumeId));

        // La resolución NUNCA pasa por findById del Book (que materializaría
        // el full_text CLOB): solo por la proyección ligera.
        org.mockito.Mockito.verify(bookRepository, org.mockito.Mockito.never()).findById(any(UUID.class));
        org.mockito.Mockito.verify(bookRepository).findAudioViewById(bookId);
    }
}
