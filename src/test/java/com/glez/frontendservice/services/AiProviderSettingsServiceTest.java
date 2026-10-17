package com.glez.frontendservice.services;

import com.glez.frontendservice.model.AiProviderConfig;
import com.glez.frontendservice.repository.AiProviderConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests del selector de proveedor de IA por caso de uso
 * ({@link AiProviderSettingsService}): defaults del yml cuando no hay fila
 * persistida, actualización parcial en caliente con persistencia y
 * validación de valores.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AI provider settings service tests")
class AiProviderSettingsServiceTest {

    @Mock
    private AiProviderConfigRepository repository;

    private AiProviderSettingsService service;

    @BeforeEach
    void setUp() {
        service = new AiProviderSettingsService(repository);
        // Defaults de application.yml inyectados por Spring en producción.
        ReflectionTestUtils.setField(service, "defaultTranslationProvider", "nvidia");
        ReflectionTestUtils.setField(service, "defaultStoryProvider", "nvidia");
    }

    @Test
    @DisplayName("Sin fila persistida: siembra con los defaults del yml y devuelve nvidia/nvidia")
    void translationProvider_noRow_seedsYamlDefaults() {
        when(repository.findById(1L)).thenReturn(Optional.empty());
        when(repository.save(any(AiProviderConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        assertEquals("nvidia", service.translationProvider());
        assertEquals("nvidia", service.storyProvider());
        // La fila sembrada con id fijo 1 queda persistida.
        verify(repository).save(org.mockito.ArgumentMatchers.argThat(c ->
                c.getId() != null && c.getId() == 1L
                        && "nvidia".equals(c.getTranslationProvider())
                        && "nvidia".equals(c.getStoryProvider())));
    }

    @Test
    @DisplayName("Con fila persistida: carga la selección guardada (sobrevive reinicios)")
    void translationProvider_existingRow_loadsPersistedSelection() {
        AiProviderConfig saved = new AiProviderConfig(1L, "ollama", "nvidia", null);
        when(repository.findById(1L)).thenReturn(Optional.of(saved));

        assertEquals("ollama", service.translationProvider());
        assertEquals("nvidia", service.storyProvider());
        // No re-siembra: la fila ya existe.
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Update parcial en caliente: cambia solo lo pedido y persiste")
    void update_partial_persistsAndReflectsImmediately() {
        AiProviderConfig saved = new AiProviderConfig(1L, "nvidia", "nvidia", null);
        when(repository.findById(1L)).thenReturn(Optional.of(saved));
        when(repository.save(any(AiProviderConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        AiProviderConfig updated = service.update(null, "ollama");

        // Solo el proveedor de historias cambió; el de traducción quedó.
        assertEquals("nvidia", service.translationProvider());
        assertEquals("ollama", service.storyProvider());
        assertEquals("ollama", updated.getStoryProvider());
        assertEquals("nvidia", updated.getTranslationProvider());
        verify(repository).save(saved);
    }

    @Test
    @DisplayName("Update normaliza mayúsculas y espacios: 'OLLAMA ' → 'ollama'")
    void update_normalizesValues() {
        AiProviderConfig saved = new AiProviderConfig(1L, "nvidia", "nvidia", null);
        when(repository.findById(1L)).thenReturn(Optional.of(saved));
        when(repository.save(any(AiProviderConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        AiProviderConfig updated = service.update(" OLLAMA ", null);

        assertEquals("ollama", updated.getTranslationProvider());
        assertEquals("ollama", service.translationProvider());
    }

    @Test
    @DisplayName("Proveedores inválidos se rechazan sin tocar el estado")
    void update_invalidProvider_rejected() {
        AiProviderConfig saved = new AiProviderConfig(1L, "nvidia", "nvidia", null);
        when(repository.findById(1L)).thenReturn(Optional.of(saved));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.update("anthropic", null));
        assertEquals("nvidia", service.translationProvider(), "El estado no cambió");
    }
}
