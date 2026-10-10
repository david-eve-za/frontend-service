package com.glez.frontendservice.services;

import com.glez.frontendservice.dtos.ModelSpecs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prueba los parsers del inspector de modelos con fixtures capturadas EN
 * VIVO: la respuesta de {@code /api/show} de Ollama para gpt-oss:20b
 * (Ollama 0.40.0, 2026-10-10) y el catálogo de NVIDIA ({@code /models}).
 */
@DisplayName("ModelInspectorService parsing tests")
class ModelInspectorServiceTest {

    private ModelInspectorService inspector;

    @BeforeEach
    void setUp() {
        inspector = new ModelInspectorService("http://localhost:11434",
                "https://integrate.api.nvidia.com/v1", "test-key", 60, 5);
    }

    /** Fixture real de POST /api/show {"model":"gpt-oss:20b"} capturada hoy. */
    private static final String OLLAMA_SHOW_GPT_OSS = """
            {
              "modified_at": "2026-10-10T10:11:52Z",
              "capabilities": ["completion", "tools", "thinking"],
              "details": {
                "family": "gpt-oss",
                "families": ["gpt-oss"],
                "quantization_level": "MXFP4",
                "parameter_size": "20.9B"
              },
              "model_info": {
                "general.architecture": "gpt-oss",
                "general.file_type": 4,
                "general.parameter_count": 20914757184,
                "gpt-oss.block_count": 24,
                "gpt-oss.context_length": 131072,
                "gpt-oss.embedding_length": 2880,
                "gpt-oss.rope.scaling.original_context_length": 4096
              }
            }
            """;

    @Test
    @DisplayName("Ollama /api/show: extrae el contexto de la clave ARQUITECTURA.context_length y las specs completas")
    void ollamaShow_parsesArchDependentContextKey() {
        ModelSpecs specs = inspector.parseOllamaShow("gpt-oss:20b", OLLAMA_SHOW_GPT_OSS, true, true, false);

        assertEquals("gpt-oss:20b", specs.name());
        assertEquals("OLLAMA", specs.provider());
        assertTrue(specs.online());
        assertTrue(specs.pulled());
        assertFalse(specs.loadedInMemory());
        assertEquals(131072L, specs.contextLength(),
                "El contexto llega bajo gpt-oss.context_length: se extrae por el SUFIJO, no hardcodeado");
        assertEquals(20914757184L, specs.parameterCount());
        assertEquals("MXFP4", specs.quantizationLevel());
        assertEquals("gpt-oss", specs.family());
        assertEquals(List.of("completion", "tools", "thinking"), specs.capabilities());
        assertEquals("LIVE_SPECS", specs.source());
    }

    @Test
    @DisplayName("El 'thinking' de las capabilities confirma que el modelo razona (consume presupuesto de salida)")
    void ollamaShow_thinkingCapabilityDetected() {
        ModelSpecs specs = inspector.parseOllamaShow("gpt-oss:20b", OLLAMA_SHOW_GPT_OSS, true, true, true);

        assertTrue(specs.capabilities().contains("thinking"),
                "gpt-oss razona: las piezas deben reservar reasoning tokens del cupo de salida");
        assertTrue(specs.loadedInMemory());
    }

    @Test
    @DisplayName("Ollama /api/show con una arquitectura distinta (llama.context_length) también se parsea")
    void ollamaShow_otherArchitectureContextKey() {
        String llamaShow = """
                {
                  "capabilities": ["completion"],
                  "details": {"family": "llama", "quantization_level": "Q4_K_M"},
                  "model_info": {
                    "general.architecture": "llama",
                    "general.parameter_count": 40523094400,
                    "llama.context_length": 8192
                  }
                }
                """;

        ModelSpecs specs = inspector.parseOllamaShow("llama:405b", llamaShow, true, true, false);

        assertEquals(8192L, specs.contextLength(), "El sufijo .context_length funciona para cualquier arquitectura");
        assertEquals(40523094400L, specs.parameterCount());
    }

    @Test
    @DisplayName("Una respuesta ilegible de /api/show degrada a specs vacías sin reventar")
    void ollamaShow_unparseableBody_degradesGracefully() {
        ModelSpecs specs = inspector.parseOllamaShow("gpt-oss:20b", "no json aqui", true, false, false);

        assertNull(specs.contextLength());
        assertFalse(specs.verifiedInCatalog());
        assertEquals("LIVE_SPECS", specs.source());
    }

    @Test
    @DisplayName("El catálogo de NVIDIA (/models) se parsea como lista de ids")
    void nvidiaCatalog_parsesIds() {
        String catalog = """
                {"data": [
                  {"id": "openai/gpt-oss-20b", "object": "model"},
                  {"id": "z-ai/glm-5.3", "object": "model"},
                  {"id": "moonshotai/kimi-k3", "object": "model"}
                ]}
                """;

        List<String> ids = inspector.parseNvidiaCatalog(catalog);

        assertEquals(List.of("openai/gpt-oss-20b", "z-ai/glm-5.3", "moonshotai/kimi-k3"), ids);
    }

    @Test
    @DisplayName("Un catálogo corrupto devuelve lista vacía en lugar de reventar")
    void nvidiaCatalog_corruptBody_returnsEmpty() {
        assertTrue(inspector.parseNvidiaCatalog("{{{").isEmpty());
    }
}
