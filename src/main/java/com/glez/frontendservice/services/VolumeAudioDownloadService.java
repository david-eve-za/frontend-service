package com.glez.frontendservice.services;

import com.glez.frontendservice.model.NovelVolume;
import com.glez.frontendservice.repository.BookRepository;
import com.glez.frontendservice.repository.NovelVolumeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Resuelve la descarga del audiolibro generado de un volumen del gestor de
 * novelas: el path NUNCA viene de la petición — se resuelve por el id del
 * volumen → Book enlazado → {@code audioFilePath} de la BD (proyección
 * ligera {@link BookRepository.BookAudioView}, sin cargar el CLOB
 * {@code full_text}).
 *
 * <p>Dos formas de audiolibro: el legado de texto completo (UN archivo mp3
 * que se sirve directo como {@code audio/mpeg}) y el de bloques semánticos
 * (un directorio {@code "<libro> - audiolibro/01 - ....mp3"} que se
 * comprime AL VUELO como ZIP — un volumen completo puede ocupar cientos de
 * MB y jamás se materializa en memoria ni en disco temporal).</p>
 */
@Service
public class VolumeAudioDownloadService {

    private static final Logger log = LoggerFactory.getLogger(VolumeAudioDownloadService.class);

    private final NovelVolumeRepository volumeRepository;
    private final BookRepository bookRepository;

    public VolumeAudioDownloadService(NovelVolumeRepository volumeRepository,
                                      BookRepository bookRepository) {
        this.volumeRepository = volumeRepository;
        this.bookRepository = bookRepository;
    }

    /**
     * Descarga resuelta: qué servir (un mp3 o el ZIP de un directorio) y con
     * qué nombre entregarlo al navegador.
     */
    public record AudioDownload(String filename, boolean zip, Path source, List<Path> entries) {
    }

    /**
     * Resuelve la descarga del audiolibro del volumen.
     *
     * @throws IllegalArgumentException volumen inexistente, sin Book
     *         enlazado o sin audio generado
     * @throws IllegalStateException el audio apuntado por la BD ya no
     *         existe en disco (p. ej. borrado a mano): la BD conserva el
     *         puntero y el botón de la UI se habilita por ese flag —
     *         el error debe ser visible y distinguible.
     */
    public AudioDownload resolve(UUID volumeId) {
        NovelVolume volume = volumeRepository.findById(volumeId)
                .orElseThrow(() -> new IllegalArgumentException("Volume " + volumeId + " not found"));
        UUID bookId = volume.getBookIdOrNull();
        if (bookId == null) {
            throw new IllegalArgumentException("Volume " + volumeId + " has no pipeline book linked");
        }
        BookRepository.BookAudioView book = bookRepository.findAudioViewById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("Book " + bookId + " not found"));
        String audioPath = book.getAudioFilePath();
        if (audioPath == null || audioPath.isBlank()) {
            throw new IllegalArgumentException("The book of volume " + volumeId + " has no generated audio yet");
        }

        Path path = Path.of(audioPath);
        if (!Files.exists(path)) {
            throw new IllegalStateException("The audio of volume " + volumeId + " vanished from disk: " + audioPath);
        }

        if (Files.isRegularFile(path)) {
            // Legado: un único mp3 con todo el texto traducido.
            return new AudioDownload(path.getFileName().toString(), false, path, List.of());
        }

        // Bloques semánticos: directorio con un mp3 por bloque — se sirve
        // como ZIP al vuelo. Solo los mp3 REGULARES, en orden de lectura
        // (el prefijo 01, 02… de los nombres lo garantiza).
        List<Path> mp3s;
        try (Stream<Path> files = Files.list(path)) {
            mp3s = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".mp3"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Could not list the audio directory of volume " + volumeId
                    + ": " + e.getMessage());
        }
        if (mp3s.isEmpty()) {
            throw new IllegalStateException("The audio directory of volume " + volumeId
                    + " has no mp3 files: " + audioPath);
        }
        String zipName = path.getFileName().toString() + ".zip";
        log.info("Descarga de audio del volumen {}: ZIP al vuelo de {} mp3(s) desde {}",
                volumeId, mp3s.size(), path);
        return new AudioDownload(zipName, true, path, mp3s);
    }

    /** Escribe la descarga (mp3 directo o ZIP al vuelo) al stream de salida. */
    public void writeTo(AudioDownload download, OutputStream out) throws IOException {
        if (!download.zip()) {
            Files.copy(download.source(), out);
            return;
        }
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Path entry : download.entries()) {
                // Solo el nombre del archivo: el ZIP no debe reproducir la
                // jerarquía de directorios del servidor.
                zip.putNextEntry(new ZipEntry(entry.getFileName().toString()));
                Files.copy(entry, zip);
                zip.closeEntry();
            }
        }
    }
}
