package com.glez.frontendservice.services;

import com.glez.frontendservice.model.Book;
import com.glez.frontendservice.model.Novel;
import com.glez.frontendservice.model.NovelVolume;
import com.glez.frontendservice.model.TranslationTerm;
import com.glez.frontendservice.repository.BlockRepository;
import com.glez.frontendservice.repository.NovelVolumeRepository;
import com.glez.frontendservice.repository.TranslationTermRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.core.ParameterizedTypeReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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

    @Mock
    private OpenAiChatModel chatModel;

    @Mock
    private NvidiaRateLimiter rateLimiter;

    private GlossaryService glossaryService;

    private Book book;
    private Novel novel;

    private MockedStatic<ChatClient> chatClientStatic;
    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec spec;
    private ChatClient.CallResponseSpec callSpec;

    /** Términos que "extraerá" el LLM en esta prueba. */
    private List<GlossaryService.TermPair> extractedTerms = new ArrayList<>();

    @BeforeEach
    void setUp() {
        glossaryService = new GlossaryService(termRepository, blockRepository,
                volumeRepository, chatModel, rateLimiter);

        book = new Book();
        book.setId(UUID.randomUUID());
        book.setFullText("The Angel Lord Moves. Rimuru looked at the sky.");

        novel = new Novel();
        novel.setId(UUID.randomUUID());

        // Cadena ChatClient.create(...) stubbeada de forma lenient: cada
        // prueba decide qué extrae el LLM (o si falla).
        chatClient = mock(ChatClient.class);
        spec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        chatClientStatic = mockStatic(ChatClient.class);
        chatClientStatic.when(() -> ChatClient.create(any(OpenAiChatModel.class))).thenReturn(chatClient);
        lenient().when(chatClient.prompt()).thenReturn(spec);
        lenient().when(spec.user(anyString())).thenReturn(spec);
        lenient().when(spec.call()).thenReturn(callSpec);
        lenient().when(callSpec.entity(any(ParameterizedTypeReference.class)))
                .thenAnswer(invocation -> extractedTerms);
    }

    @AfterEach
    void tearDown() {
        chatClientStatic.close();
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

    @Test
    @DisplayName("El glosario existente de la serie se reutiliza sin extraer de nuevo")
    void getOrCreateGlossary_existingTerms_skipsExtraction() {
        linkedToNovel();
        when(termRepository.existsForScope(novel.getId(), book.getId())).thenReturn(true);
        when(termRepository.findApplicable(novel.getId(), book.getId()))
                .thenReturn(List.of(stored("the Angel Lord", "el Señor de los Ángeles")));

        List<TranslationTerm> terms = glossaryService.getOrCreateGlossary(book);

        assertEquals(1, terms.size());
        verify(chatClient, never()).prompt();
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
        chatClientStatic.when(() -> ChatClient.create(any(OpenAiChatModel.class)))
                .thenThrow(new RuntimeException("LLM unavailable"));

        List<TranslationTerm> terms = glossaryService.getOrCreateGlossary(book);

        assertTrue(terms.isEmpty(), "A failed extraction must not block translation");
        verify(termRepository, never()).save(any(TranslationTerm.class));
    }

    @Test
    @DisplayName("Los términos extraídos se persisten acotados a la serie")
    void getOrCreateGlossary_extractsAndPersistsPerNovel() {
        linkedToNovel();
        extractedTerms.add(new GlossaryService.TermPair("the Angel Lord", "el Señor de los Ángeles"));
        when(termRepository.existsForScope(novel.getId(), book.getId())).thenReturn(false);
        when(termRepository.findApplicable(novel.getId(), book.getId()))
                .thenReturn(List.of())
                .thenReturn(List.of(stored("the Angel Lord", "el Señor de los Ángeles")));
        when(blockRepository.findByBookIdOrderByPositionAsc(book.getId())).thenReturn(List.of());

        List<TranslationTerm> terms = glossaryService.getOrCreateGlossary(book);

        ArgumentCaptor<TranslationTerm> captor = ArgumentCaptor.forClass(TranslationTerm.class);
        verify(termRepository).save(captor.capture());
        assertEquals("the Angel Lord", captor.getValue().getSourceTerm());
        assertEquals("el Señor de los Ángeles", captor.getValue().getTargetTerm());
        assertEquals(novel.getId(), captor.getValue().getNovel().getId(),
                "Terms of a series-linked book are scoped to the novel for inter-volume consistency");
        assertEquals(1, terms.size());
    }

    @Test
    @DisplayName("Los términos de libros sin serie se acotan al propio libro")
    void getOrCreateGlossary_unlinkedBook_scopesToBook() {
        noNovel();
        extractedTerms.add(new GlossaryService.TermPair("term", "término"));
        when(termRepository.existsForScope(null, book.getId())).thenReturn(false);
        when(termRepository.findApplicable(null, book.getId()))
                .thenReturn(List.of())
                .thenReturn(List.of(stored("term", "término")));
        when(blockRepository.findByBookIdOrderByPositionAsc(book.getId())).thenReturn(List.of());

        glossaryService.getOrCreateGlossary(book);

        ArgumentCaptor<TranslationTerm> captor = ArgumentCaptor.forClass(TranslationTerm.class);
        verify(termRepository).save(captor.capture());
        assertNull(captor.getValue().getNovel());
        assertEquals(book.getId(), captor.getValue().getBook().getId());
    }
}
