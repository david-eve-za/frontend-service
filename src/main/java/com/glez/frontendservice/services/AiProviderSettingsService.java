package com.glez.frontendservice.services;

import com.glez.frontendservice.model.AiProviderConfig;
import com.glez.frontendservice.repository.AiProviderConfigRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Selección de proveedor de IA por caso de uso (traducción / asistente de
 * historias), conmutable EN CALIENTE desde la página de Configuración:
 *
 * <ul>
 *   <li><b>nvidia</b> → pool de NVIDIA ({@code app.nvidia.models}), que ya
 *       incluye los fallbacks locales al final.</li>
 *   <li><b>ollama</b> → pool 100% local ({@code app.ollama.models}).</li>
 * </ul>
 *
 * <p>La selección se PERSISTE en la fila única {@code ai_provider_config}
 * (sobrevive reinicios) y se cachea en memoria con AtomicReference — el hot
 * path de la rotación ({@link NvidiaAiService}) la lee por llamada sin
 * tocar la BD. Los defaults al arrancar vienen de application.yml
 * (app.ai.translation-provider / app.ai.story-provider) y solo siembran la
 * fila la primera vez.</p>
 */
@Service
@RequiredArgsConstructor
public class AiProviderSettingsService {

    private static final Logger log = LoggerFactory.getLogger(AiProviderSettingsService.class);

    private final AiProviderConfigRepository repository;

    /** Default de arranque del proveedor de traducción (application.yml). */
    @Value("${app.ai.translation-provider:nvidia}")
    private String defaultTranslationProvider;

    /** Default de arranque del proveedor del asistente de historias (application.yml). */
    @Value("${app.ai.story-provider:nvidia}")
    private String defaultStoryProvider;

    private final AtomicReference<String> translationProvider = new AtomicReference<>("nvidia");
    private final AtomicReference<String> storyProvider = new AtomicReference<>("nvidia");
    private volatile boolean initialized = false;

    /** Proveedor activo del pipeline de traducción (nvidia | ollama). */
    public String translationProvider() {
        ensureInitialized();
        return translationProvider.get();
    }

    /** Proveedor activo del Asistente de Historias (nvidia | ollama). */
    public String storyProvider() {
        ensureInitialized();
        return storyProvider.get();
    }

    /**
     * Actualiza la selección (parcial: null = mantener la actual), valida
     * los valores, persiste en BD y refresca la cache en memoria — el
     * cambio aplica a la SIGUIENTE llamada sin reiniciar la app.
     */
    public AiProviderConfig update(String newTranslationProvider, String newStoryProvider) {
        ensureInitialized();
        if (newTranslationProvider != null) {
            normalizeOrThrow(newTranslationProvider);
        }
        if (newStoryProvider != null) {
            normalizeOrThrow(newStoryProvider);
        }
        AiProviderConfig config = repository.findById(1L).orElseGet(this::seedConfig);
        if (newTranslationProvider != null) {
            config.setTranslationProvider(normalizeOrThrow(newTranslationProvider));
            translationProvider.set(normalizeOrThrow(newTranslationProvider));
        }
        if (newStoryProvider != null) {
            config.setStoryProvider(normalizeOrThrow(newStoryProvider));
            storyProvider.set(normalizeOrThrow(newStoryProvider));
        }
        config.setUpdatedAt(Instant.now());
        AiProviderConfig saved = repository.save(config);
        log.info("[ai-providers] Selección actualizada: traducción={}, historias={}",
                saved.getTranslationProvider(), saved.getStoryProvider());
        return saved;
    }

    // ═══════════════════════ Internos ═══════════════════════

    /** Carga la fila persistida (o la siembra con los defaults del yml) una sola vez. */
    private synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        AiProviderConfig config = repository.findById(1L).orElseGet(this::seedConfig);
        translationProvider.set(normalizeOrThrow(config.getTranslationProvider()));
        storyProvider.set(normalizeOrThrow(config.getStoryProvider()));
        initialized = true;
        log.info("[ai-providers] Selección cargada: traducción={}, historias={}",
                translationProvider.get(), storyProvider.get());
    }

    private AiProviderConfig seedConfig() {
        AiProviderConfig config = new AiProviderConfig();
        config.setId(1L);
        config.setTranslationProvider(normalizeOrThrow(defaultTranslationProvider));
        config.setStoryProvider(normalizeOrThrow(defaultStoryProvider));
        config.setUpdatedAt(Instant.now());
        return repository.save(config);
    }

    /** Solo se aceptan "nvidia" o "ollama" (insensible a mayúsculas). */
    private static String normalizeOrThrow(String value) {
        if (value == null) {
            throw new IllegalArgumentException("El proveedor no puede ser nulo (usa nvidia o ollama).");
        }
        String normalized = value.trim().toLowerCase();
        if (!"nvidia".equals(normalized) && !"ollama".equals(normalized)) {
            throw new IllegalArgumentException(
                    "Proveedor desconocido «" + value + "»: solo nvidia o ollama.");
        }
        return normalized;
    }
}
