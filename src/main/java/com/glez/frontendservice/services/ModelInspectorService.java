package com.glez.frontendservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glez.frontendservice.dtos.ModelSpecs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>Investigación de modelos antes de usarlos</b>: especificaciones reales y
 * disponibilidad online, para que la rotación no queme reintentos (ni cuelgue)
 * contra un proveedor caído y para que el presupuesto de salida se dimensione
 * con datos vivos cuando existan.
 *
 * <p><b>Ollama (local, 100% disponible)</b> es la fuente de especificaciones
 * REALES: {@code GET /api/version} (¿servidor arriba?), {@code GET /api/tags}
 * (¿modelo descargado?), {@code GET /api/ps} (¿cargado en VRAM?) y
 * {@code POST /api/show} (especificaciones completas: la longitud de contexto
 * llega bajo una clave dependiente de la arquitectura —
 * {@code gpt-oss.context_length} — así que se extrae por el sufijo
 * {@code .context_length}).</p>
 *
 * <p><b>NVIDIA (remoto)</b> expone {@code GET {base-url}/models} con la API
 * key: prueba de vida (¿online? ¿key válida?) y el <i>catálogo</i> de modelos
 * — el inspector verifica que cada modelo del pool EXISTA en ese catálogo
 * (detecta EOLs/typos antes de la primera llamada). La API no expone
 * contexto/salida por modelo: el pool remoto se sigue dimensionando con las
 * heurísticas de {@code app.nvidia.*} (source=CONFIGURED).</p>
 *
 * <p><b>Sondas con timeout corto (3 s)</b> y <b>caché con TTL</b>: una
 * conectividad rota (como el DNS caído de esta máquina) NO cuelga el
 * pipeline — la sonda falla rápido, el modelo se salta y la rotación
 * continúa; la caché evita sondear en cada llamada, y su TTL corto evita
 * que una caída transitoria quede pegada como permanente.</p>
 */
@Service
public class ModelInspectorService {

    private static final Logger log = LoggerFactory.getLogger(ModelInspectorService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Cliente de sondas: timeout de CONEXIÓN corto para fallar rápido. */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private final String ollamaBaseUrl;
    private final String nvidiaBaseUrl;
    private final String nvidiaApiKey;
    private final long probeCacheTtlMillis;
    private final long probeRequestTimeoutMillis;

    /** Caché de sondas por proveedor: (provider → [epochMs, online]). */
    private final Map<String, ProbeResult> probeCache = new ConcurrentHashMap<>();

    public ModelInspectorService(
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl,
            @Value("${spring.ai.openai.base-url:https://integrate.api.nvidia.com/v1}") String nvidiaBaseUrl,
            @Value("${spring.ai.openai.api-key:}") String nvidiaApiKey,
            @Value("${app.ai-inspector.probe-cache-ttl-seconds:60}") long probeCacheTtlSeconds,
            @Value("${app.ai-inspector.probe-timeout-seconds:5}") long probeRequestTimeoutSeconds) {
        this.ollamaBaseUrl = ollamaBaseUrl.endsWith("/")
                ? ollamaBaseUrl.substring(0, ollamaBaseUrl.length() - 1)
                : ollamaBaseUrl;
        this.nvidiaBaseUrl = nvidiaBaseUrl.endsWith("/")
                ? nvidiaBaseUrl.substring(0, nvidiaBaseUrl.length() - 1)
                : nvidiaBaseUrl;
        this.nvidiaApiKey = nvidiaApiKey;
        this.probeCacheTtlMillis = probeCacheTtlSeconds * 1000;
        this.probeRequestTimeoutMillis = probeRequestTimeoutSeconds * 1000;
    }

    /**
     * ¿Está el servidor de NVIDIA online y la API key válida? Sonda cacheada
     * ({@code GET /models} con Authorization): un fallo de red/DNS falla en
     * segundos, no cuelga la rotación.
     */
    public boolean isNvidiaOnline() {
        return probeCached("nvidia", () -> {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(nvidiaBaseUrl + "/models"))
                    .timeout(Duration.ofMillis(probeRequestTimeoutMillis))
                    .header("Authorization", "Bearer " + nvidiaApiKey)
                    .GET()
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean online = response.statusCode() >= 200 && response.statusCode() < 300;
            log.info("[model-inspector] Sonda de NVIDIA: {} (HTTP {})",
                    online ? "online" : "caída", response.statusCode());
            return online;
        });
    }

    /**
     * Catálogo de modelos de NVIDIA (ids publicados en {base-url}/models):
     * sirve para VERIFICAR que cada modelo del pool existe antes de usarlo
     * (detecta EOLs y typos sin gastar una sola llamada al modelo). Null si
     * el proveedor está offline.
     */
    public List<String> nvidiaCatalogIds() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(nvidiaBaseUrl + "/models"))
                    .timeout(Duration.ofMillis(probeRequestTimeoutMillis))
                    .header("Authorization", "Bearer " + nvidiaApiKey)
                    .GET()
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }
            return parseNvidiaCatalog(response.body());
        } catch (Exception e) {
            log.info("[model-inspector] Catálogo de NVIDIA inaccesible: {}", e.getMessage());
            return null;
        }
    }

    /** ¿Está el servidor Ollama local arriba? (localhost: sin DNS de por medio). */
    public boolean isOllamaOnline() {
        return probeCached("ollama", () -> {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaBaseUrl + "/api/version"))
                    .timeout(Duration.ofMillis(probeRequestTimeoutMillis))
                    .GET()
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("[model-inspector] Sonda de Ollama: online ({})", ollamaBaseUrl);
            return true;
        });
    }

    /**
     * Especificaciones REALES de un modelo local de Ollama ({@code POST
     * /api/show}): contexto (clave por sufijo {@code .context_length} porque
     * es dependiente de la arquitectura: {@code gpt-oss.context_length},
     * {@code llama.context_length}, ...), parámetros, cuantización, familia
     * y capacidades (un {@code thinking} presente confirma que el modelo
     * razona y consume parte del presupuesto de salida).
     */
    public ModelSpecs inspectOllamaModel(String modelName) {
        boolean serverOnline = isOllamaOnline();
        boolean pulled = serverOnline && ollamaPulledModels().contains(modelName);
        boolean loaded = serverOnline && ollamaLoadedModels().contains(modelName);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaBaseUrl + "/api/show"))
                    .timeout(Duration.ofMillis(30_000))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(Map.of("model", modelName))))
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return unavailableOllamaSpecs(modelName, serverOnline, pulled, loaded);
            }
            return parseOllamaShow(modelName, response.body(), serverOnline, pulled, loaded);
        } catch (Exception e) {
            log.warn("[model-inspector] No se pudieron leer las specs de Ollama '{}': {}",
                    modelName, e.getMessage());
            return unavailableOllamaSpecs(modelName, serverOnline, pulled, loaded);
        }
    }

    /**
     * Parser puro de {@code /api/show} de Ollama (package-private para
     * pruebas): la longitud de contexto llega bajo una clave dependiente
     * de la arquitectura ({@code gpt-oss.context_length},
     * {@code llama.context_length}, ...) así que se extrae por el sufijo
     * {@code .context_length}. Las capacidades importan: un
     * {@code thinking} presente confirma que el modelo razona y consume
     * parte del presupuesto de salida.
     */
    ModelSpecs parseOllamaShow(String modelName, String body, boolean online,
                                 boolean pulled, boolean loaded) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode info = root.path("model_info");

            Long contextLength = null;
            for (java.util.Iterator<String> it = info.fieldNames(); it.hasNext(); ) {
                String field = it.next();
                if (field.endsWith(".context_length")) {
                    contextLength = info.path(field).asLong();
                }
            }
            Long parameterCount = info.path("general.parameter_count").isMissingNode()
                    ? null : info.path("general.parameter_count").asLong();
            String quantization = root.path("details").path("quantization_level").asText(null);
            String family = root.path("details").path("family").asText(null);
            List<String> capabilities = new ArrayList<>();
            for (JsonNode capability : root.path("capabilities")) {
                capabilities.add(capability.asText());
            }

            log.info("[model-inspector] Ollama '{}' specs: contexto {} tokens, {} parámetros, cuantización {}, capacidades {}{}",
                    modelName, contextLength, parameterCount, quantization, capabilities,
                    loaded ? " (cargado en memoria)" : "");
            return new ModelSpecs(modelName, "OLLAMA", online, pulled, loaded,
                    true, contextLength, parameterCount, quantization, family,
                    List.copyOf(capabilities), "LIVE_SPECS");
        } catch (Exception e) {
            log.warn("[model-inspector] Respuesta de /api/show ilegible para '{}': {}",
                    modelName, e.getMessage());
            return unavailableOllamaSpecs(modelName, online, pulled, loaded);
        }
    }

    private ModelSpecs unavailableOllamaSpecs(String modelName, boolean online,
                                               boolean pulled, boolean loaded) {
        return new ModelSpecs(modelName, "OLLAMA", online, pulled, loaded,
                false, null, null, null, null, List.of(), "LIVE_SPECS");
    }

    /**
     * Parser puro del catálogo de NVIDIA ({@code GET /models}): lista de
     * ids publicados, para verificar que cada modelo del pool existe.
     */
    List<String> parseNvidiaCatalog(String body) {
        try {
            List<String> ids = new ArrayList<>();
            for (JsonNode node : objectMapper.readTree(body).path("data")) {
                String id = node.path("id").asText(null);
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
            return ids;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Nombres de los modelos descargados en Ollama (GET /api/tags). */
    public List<String> ollamaPulledModels() {
        return ollamaModelNames("/api/tags");
    }

    /** Nombres de los modelos cargados en VRAM ahora mismo (GET /api/ps). */
    public List<String> ollamaLoadedModels() {
        return ollamaModelNames("/api/ps");
    }

    private List<String> ollamaModelNames(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaBaseUrl + path))
                    .timeout(Duration.ofMillis(probeRequestTimeoutMillis))
                    .GET()
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            List<String> names = new ArrayList<>();
            for (JsonNode model : objectMapper.readTree(response.body()).path("models")) {
                String name = model.path("name").asText(null);
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
            return names;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Sonda cacheada con TTL: la primera llamada por ventana golpea el
     * proveedor; las siguientes sirven de memoria. Un resultado fallido
     * caduca igual (ventanas cortas => las recuperaciones se detectan
     * rápido).
     */
    private boolean probeCached(String provider, ProbeCall call) {
        ProbeResult cached = probeCache.get(provider);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.timestamp() < probeCacheTtlMillis) {
            return cached.online();
        }
        boolean online;
        try {
            online = call.probe();
        } catch (Exception e) {
            log.warn("[model-inspector] Sonda de {} falló: {}", provider, e.getMessage());
            online = false;
        }
        probeCache.put(provider, new ProbeResult(now, online));
        if (!online) {
            log.warn("[model-inspector] {} reportado OFFLINE", provider);
        }
        return online;
    }

    @FunctionalInterface
    private interface ProbeCall {
        boolean probe() throws Exception;
    }

    private record ProbeResult(long timestamp, boolean online) {
    }
}
