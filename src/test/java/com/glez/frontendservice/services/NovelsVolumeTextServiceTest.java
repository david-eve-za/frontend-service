package com.glez.frontendservice.services;

import com.glez.frontendservice.components.NovelCatalogPaths;
import com.glez.frontendservice.components.NovelFileManager;
import com.glez.frontendservice.dtos.VolumePreparedDto;
import com.glez.frontendservice.model.Book;
import com.glez.frontendservice.model.Novel;
import com.glez.frontendservice.model.NovelVolume;
import com.glez.frontendservice.model.NovelVolumeFile;
import com.glez.frontendservice.repository.NovelVolumeFileRepository;
import com.glez.frontendservice.repository.NovelVolumeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NovelsVolumeTextService hybrid text source")
class NovelsVolumeTextServiceTest {

    private static final String BASE = "https://server.elscione.com/Officially%20Translated%20Light%20Novels/";

    @Mock
    private NovelVolumeRepository volumeRepository;

    @Mock
    private NovelVolumeFileRepository fileRepository;

    @Mock
    private ElscioneApiClient apiClient;

    @Mock
    private BookProcessingService bookProcessingService;

    @TempDir
    Path tempDir;

    private NovelsVolumeTextService service;
    private NovelCatalogPaths catalogPaths;
    private NovelVolume volume;

    @BeforeEach
    void setUp() {
        catalogPaths = new NovelCatalogPaths(new NovelFileManager(tempDir.toString()), BASE);
        service = new NovelsVolumeTextService(volumeRepository, fileRepository, apiClient,
                catalogPaths, bookProcessingService);

        Novel novel = new Novel();
        novel.setId(UUID.randomUUID());
        novel.setTitle("NovelA");
        novel.setRemotePath("/Officially%20Translated%20Light%20Novels/NovelA/");

        volume = new NovelVolume();
        volume.setId(UUID.randomUUID());
        volume.setNovel(novel);
        volume.setVolumeNumber(1);
        volume.setLabel("Volume 01");
        volume.setRemoteDir(novel.getRemotePath());
    }

    private NovelVolumeFile fileOf(NovelVolumeFile.Format format, String name) {
        NovelVolumeFile file = new NovelVolumeFile();
        file.setId(UUID.randomUUID());
        file.setVolume(volume);
        file.setFormat(format);
        file.setFileName(name);
        file.setSizeBytes(100L);
        file.setRemoteHref("/Officially%20Translated%20Light%20Novels/NovelA/" + name.replace(" ", "%20"));
        return file;
    }

    @Test
    @DisplayName("uses the already downloaded local file without hitting the remote API")
    void prepareText_reusesLocalCrawlerFile() throws Exception {
        NovelVolumeFile local = fileOf(NovelVolumeFile.Format.EPUB, "NovelA - Volume 01.epub");
        Path localPath = catalogPaths.localPathOf(local.getRemoteHref());
        Files.createDirectories(localPath.getParent());
        Files.writeString(localPath, "contenido epub");
        volume.getFiles().add(local);

        when(volumeRepository.findById(volume.getId())).thenReturn(Optional.of(volume));
        when(fileRepository.findByVolumeId(volume.getId())).thenReturn(List.of(local));
        Book book = new Book();
        book.setId(UUID.randomUUID());
        book.setName(local.getFileName());
        when(bookProcessingService.storeBook(any(), eq(local.getFileName()))).thenReturn(book);

        VolumePreparedDto result = service.prepareText(volume.getId());

        assertEquals(book.getId(), result.bookId());
        assertEquals(volume.getId(), result.volumeId());
        verify(apiClient, never()).downloadToFile(anyString(), any(), any());
        verify(volumeRepository).save(argThat(saved -> saved.getBook() != null
                && saved.getBook().getId().equals(book.getId())));
    }

    @Test
    @DisplayName("downloads the file on demand when the crawler output is missing")
    void prepareText_downloadsOnDemand() throws Exception {
        NovelVolumeFile remote = fileOf(NovelVolumeFile.Format.EPUB, "NovelA - Volume 01.epub");
        String fullUrl = "https://server.elscione.com" + remote.getRemoteHref();
        when(apiClient.getFullUrl(remote.getRemoteHref())).thenReturn(fullUrl);

        when(volumeRepository.findById(volume.getId())).thenReturn(Optional.of(volume));
        when(fileRepository.findByVolumeId(volume.getId())).thenReturn(List.of(remote));
        Book book = new Book();
        book.setId(UUID.randomUUID());
        book.setName(remote.getFileName());
        when(bookProcessingService.storeBook(any(), eq(remote.getFileName())))
                .thenAnswer(inv -> {
                    byte[] bytes = Files.readAllBytes(catalogPaths.localPathOf(remote.getRemoteHref()));
                    assertTrue(new String(bytes, StandardCharsets.UTF_8).contains("descargado"),
                            "storeBook must receive the downloaded content");
                    return book;
                });

        doAnswer(inv -> {
            Path target = inv.getArgument(1);
            Files.createDirectories(target.getParent());
            Files.writeString(target, "contenido descargado");
            return null;
        }).when(apiClient).downloadToFile(anyString(), any(), any());

        VolumePreparedDto result = service.prepareText(volume.getId());

        assertEquals(book.getId(), result.bookId());
        verify(apiClient).downloadToFile(eq(fullUrl),
                eq(catalogPaths.localPathOf(remote.getRemoteHref())), any());
    }

    @Test
    @DisplayName("prefers EPUB over other formats when choosing the source file")
    void selectSourceFile_prefersEpub() {
        NovelVolumeFile pdf = fileOf(NovelVolumeFile.Format.PDF, "NovelA - Volume 01.pdf");
        NovelVolumeFile epub = fileOf(NovelVolumeFile.Format.EPUB, "NovelA - Volume 01.epub");
        when(fileRepository.findByVolumeId(volume.getId())).thenReturn(List.of(pdf, epub));

        assertEquals(epub, service.selectSourceFile(volume));
    }

    @Test
    @DisplayName("rejects volumes already linked to a book or without source files")
    void prepareText_guards() {
        Book linked = new Book();
        linked.setId(UUID.randomUUID());
        volume.setBook(linked);
        when(volumeRepository.findById(volume.getId())).thenReturn(Optional.of(volume));
        assertThrows(IllegalStateException.class, () -> service.prepareText(volume.getId()));

        volume.setBook(null);
        when(fileRepository.findByVolumeId(volume.getId())).thenReturn(List.of());
        assertThrows(IllegalArgumentException.class, () -> service.prepareText(volume.getId()));
    }
}
