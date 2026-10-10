package com.glez.frontendservice.services;

import com.glez.frontendservice.model.Book;
import com.glez.frontendservice.model.Novel;
import com.glez.frontendservice.model.NovelVolume;
import com.glez.frontendservice.model.TranslationTerm;
import com.glez.frontendservice.repository.BlockRepository;
import com.glez.frontendservice.repository.NovelVolumeRepository;
import com.glez.frontendservice.repository.TranslationTermRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("GlossaryService Tests")
class GlossaryServiceTest {

    @Mock
    private TranslationTermRepository termRepository;

    @Mock
    private BlockRepository blockRepository;

    @Mock
    private NovelVolumeRepository volumeRepository;

    /** Rotación de modelos stubbeada: el glosario NUNCA llama al ChatClient directo. */
    @Mock
    private NvidiaAiService nvidiaAiService;

    private GlossaryService glossaryService;

    private Book book;
    private Novel novel;

    /** Términos que "extraerá" el LLM en esta prueba (como JSON crudo). */
    private final List<GlossaryService.TermPair> extractedTerms = new ArrayList<>();

    @BeforeEach
    void setUp() {
        glossaryService = new GlossaryService(termRepository, blockRepository,
                volumeRepository, nvidiaAiService);

        book = new Book();
        book.setId(UUID.randomUUID());
        book.setFullText("The Angel Lord Moves. Rimuru looked at the sky.");

        novel = new Novel();
        novel.setId(UUID.randomUUID());
    }

    private void linkedToNovel() {
        NovelVolume volume = new NovelVolume();
        volume.setId(UUID.randomUUID());
        volume.setBook(book);
        volume.setNovel(novel);
        lenient().when(volumeRepository.findByBook(book)).thenReturn(Optional.of(volume));
    }

    private void noNovel() {
        lenient().when(volumeRepository.findByBook(book)).thenReturn(Optional.empty());
    }

    private TranslationTerm stored(String source, String target) {
        TranslationTerm term = new TranslationTerm();
        term.setId(UUID.randomUUID());
        term.setSourceTerm(source);
        term.setTargetTerm(target);
        return term;
    }

    /** El LLM responde con el JSON de los términos (opcionalmente con vallas de código). */
    private void llmResponds() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < extractedTerms.size(); i++) {
            GlossaryService.TermPair pair = extractedTerms.get(i);
            json.append("{\"source\":\"").append(pair.source())
                    .append("\",\"target\":\"").append(pair.target()).append("\"}");
            if (i < extractedTerms.size() - 1) {
                json.append(',');
            }
        }
        json.append(']');
        // Los modelos envuelven el JSON con frecuencia: se prueba el parser real.
        when(nvidiaAiService.callModelWithRotation(anyString()))
                .thenReturn("```json\n" + json + "\n```");
    }

    @Test
    @DisplayName("El glosario existente de la serie se reutiliza sin extraer de nuevo")
    void getOrCreateGlossary_existingTerms_skipsExtraction() {
        linkedToNovel();
        when(termRepository.existsForScope(novel.getId(), book.getId())).thenReturn(true);
        when(termRepository.findApplicable(novel.getId(), book.getId()))
                .thenReturn(List.of(stored("the Angel Lord", "el Señor de los Ángeles")));

        List<TranslationTerm> terms = glossaryService.getOrCreateGlossary(book);

        assertEquals(1, terms.size());
        verify(nvidiaAiService, never()).callModelWithRotation(anyString());
    }

    @Test
    @DisplayName("Un fallo de extracción degrada con elegancia: se traduce sin glosario")
    void getOrCreateGlossary_extractionFails_returnsExistingTerms() {
        linkedToNovel();
        when(termRepository.existsForScope(novel.getId(), book.getId())).thenReturn(false);
        when(termRepository.findApplicable(novel.getId(), book.getId()))
                .thenReturn(List.of())
                .thenReturn(List.of());
        when(blockRepository.findByBookIdOrderByPositionAsc(book.getId())).thenReturn(List.of());
        when(nvidiaAiService.callModelWithRotation(anyString()))
                .thenThrow(new RuntimeException("all models exhausted"));

        List<TranslationTerm> terms = glossaryService.getOrCreateGlossary(book);

        assertTrue(terms.isEmpty(), "A failed extraction must not block translation");
        verify(termRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("Los términos extraídos se persisten acotados a la serie (vía rotación de modelos)")
    void getOrCreateGlossary_extractsAndPersistsPerNovel() {
        linkedToNovel();
        extractedTerms.add(new GlossaryService.TermPair("the Angel Lord", "el Señor de los Ángeles"));
        llmResponds();
        when(termRepository.existsForScope(novel.getId(), book.getId())).thenReturn(false);
        when(termRepository.findApplicable(novel.getId(), book.getId()))
                .thenReturn(List.of())
                .thenReturn(List.of(stored("the Angel Lord", "el Señor de los Ángeles")));
        when(blockRepository.findByBookIdOrderByPositionAsc(book.getId())).thenReturn(List.of());

        List<TranslationTerm> terms = glossaryService.getOrCreateGlossary(book);

        // La extracción pasó por la rotación (el prompt incluye los títulos).
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(nvidiaAiService).callModelWithRotation(prompt.capture());
        assertTrue(prompt.getValue().contains("Chapter titles and excerpt"));
        // One batched saveAll (single transaction + JDBC batching)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TranslationTerm>> captor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(termRepository).saveAll(captor.capture());
        TranslationTerm saved = captor.getValue().get(0);
        assertEquals("the Angel Lord", saved.getSourceTerm());
        assertEquals("el Señor de los Ángeles", saved.getTargetTerm());
        assertEquals(novel.getId(), saved.getNovel().getId(),
                "Terms of a series-linked book are scoped to the novel for inter-volume consistency");
        assertEquals(1, terms.size());
    }

    @Test
    @DisplayName("Los términos de libros sin serie se acotan al propio libro")
    void getOrCreateGlossary_unlinkedBook_scopesToBook() {
        noNovel();
        extractedTerms.add(new GlossaryService.TermPair("term", "término"));
        llmResponds();
        when(termRepository.existsForScope(null, book.getId())).thenReturn(false);
        when(termRepository.findApplicable(null, book.getId()))
                .thenReturn(List.of())
                .thenReturn(List.of(stored("term", "término")));
        when(blockRepository.findByBookIdOrderByPositionAsc(book.getId())).thenReturn(List.of());

        glossaryService.getOrCreateGlossary(book);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TranslationTerm>> captor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(termRepository).saveAll(captor.capture());
        TranslationTerm saved = captor.getValue().get(0);
        assertNull(saved.getNovel());
        assertEquals(book.getId(), saved.getBook().getId());
    }

    @Test
    @DisplayName("Una respuesta del LLM sin JSON es un fallo de extracción, no un crash")
    void getOrCreateGlossary_unparseableResponse_degradesGracefully() {
        linkedToNovel();
        when(termRepository.existsForScope(novel.getId(), book.getId())).thenReturn(false);
        when(termRepository.findApplicable(novel.getId(), book.getId()))
                .thenReturn(List.of())
                .thenReturn(List.of());
        when(blockRepository.findByBookIdOrderByPositionAsc(book.getId())).thenReturn(List.of());
        when(nvidiaAiService.callModelWithRotation(anyString()))
                .thenReturn("Lo siento, no pude extraer los términos.");

        List<TranslationTerm> terms = glossaryService.getOrCreateGlossary(book);

        assertTrue(terms.isEmpty(), "Un LLM que no responde JSON degrada sin glosario, sin bloquear");
        verify(termRepository, never()).saveAll(anyList());
    }
}
