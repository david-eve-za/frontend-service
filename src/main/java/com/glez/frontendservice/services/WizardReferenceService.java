package com.glez.frontendservice.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resuelve la REFERENCIA OPCIONAL del paso 5 del Asistente de Historias en
 * Video: el usuario puede aportar UNA referencia — el contenido de texto de
 * un archivo subido (leído en el navegador), una URL http/https (se recupera
 * en el servidor y se convierte a texto) o un TÉRMINO que se busca en la web
 * (DuckDuckGo HTML, sin claves de API ni dependencias nuevas).
 *
 * <p><b>Alcance estrictamente local al asistente</b>: solo lo consume el
 * paso 5 del wizard. No toca ningún otro proceso del servicio.</p>
 *
 * <p><b>Defensivas</b>: guard SSRF (solo http/https y hosts públicos — nada
 * de loopback, link-local, site-local, CGNAT ni direcciones any/multicast),
 * content-type restringido a texto (html/plain/json/xml/csv/markdown), tope
 * de lectura de 1 MB por respuesta, tope de 20 000 caracteres por referencia
 * completa, timeouts de 30 s por petición y, en la búsqueda, los SNIPPETS se
 * conservan SIEMPRE y el contenido de las 2 primeras páginas es best-effort
 * (un fallo de página se salta, no rompe la referencia).</p>
 *
 * <p><b>Semántica de error</b>: si el usuario aportó una referencia y no se
 * puede resolver, el paso falla con un mensaje accionable (IllegalArgumentException
 * → HTTP 400) en vez de descartarla en silencio: la referencia es intención
 * explícita del usuario. Sin referencia, este servicio no participa.</p>
 */
@Service
public class WizardReferenceService {

    private static final Logger log = LoggerFactory.getLogger(WizardReferenceService.class);

    /** Tope de la referencia ya resuelta que viaja al prompt de la IA. */
    static final int MAX_REFERENCE_CHARS = 20_000;

    /** Tope de bytes leídos por respuesta HTTP (la referencia se recorta después). */
    private static final int MAX_FETCH_BYTES = 1_048_576;

    /** Tope de cada página individual dentro de una búsqueda (deja hueco al resto). */
    private static final int MAX_SEARCH_PAGE_CHARS = 8_000;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    /** Endpoint HTML de DuckDuckGo: resultados parseables sin clave de API. */
    static final String DDG_SEARCH_URL = "https://html.duckduckgo.com/html/?q=";

    private static final int SEARCH_RESULT_LIMIT = 5;
    private static final int SEARCH_PAGES_TO_FETCH = 2;

    /** Content types que se aceptan como texto de referencia. */
    private static final List<String> ALLOWED_CONTENT_TYPES = List.of(
            "text/html", "text/plain", "application/json", "application/xml", "text/xml",
            "text/csv", "application/csv", "text/markdown", "application/javascript",
            "text/x-markdown");

    /** Cabeceras de un resultado de búsqueda: enlace (con uddg) + snippet. */
    record SearchHit(String url, String title, String snippet) {
    }

    /** Cualquier elemento con clase result__a / result__snippet, con sus atributos. */
    private static final Pattern DDG_ANCHOR =
            Pattern.compile("<a\\b([^>]*)>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern DDG_SNIPPET =
            Pattern.compile("<([a-z]+)\\b[^>]*class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</\\1>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HREF_ATTR = Pattern.compile("href=\"([^\"]+)\"");
    private static final Pattern UDDG_PARAM = Pattern.compile("[?&]uddg=([^&]+)");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    /** Guard SSRF activo (producción). */
    private final boolean ssrfGuard;

    /** Endpoint de búsqueda (sustituible en tests). */
    private final String searchUrl;

    public WizardReferenceService() {
        this(true, DDG_SEARCH_URL);
    }

    /** Constructor de tests: permite desactivar el guard para servir contra 127.0.0.1. */
    WizardReferenceService(boolean ssrfGuard) {
        this(ssrfGuard, DDG_SEARCH_URL);
    }

    /** Constructor de tests: guard desactivable + endpoint de búsqueda sustituible. */
    WizardReferenceService(boolean ssrfGuard, String searchUrl) {
        this.ssrfGuard = ssrfGuard;
        this.searchUrl = searchUrl;
    }

    /**
     * Resuelve la referencia del paso 5 según el campo aportado (a lo sumo
     * UNO). Devuelve null si no se aportó ninguna referencia; falla con
     * IllegalArgumentException si se aportó más de una o la aportada no se
     * puede resolver.
     */
    public String resolveReference(String referenceFileContent, String referenceUrl,
            String referenceSearch) {
        int provided = 0;
        provided += isBlank(referenceFileContent) ? 0 : 1;
        provided += isBlank(referenceUrl) ? 0 : 1;
        provided += isBlank(referenceSearch) ? 0 : 1;
        if (provided > 1) {
            throw new IllegalArgumentException(
                    "Aporta solo UNA referencia: archivo, URL o término de búsqueda, no varios.");
        }

        long start = System.currentTimeMillis();
        try {
            if (!isBlank(referenceUrl)) {
                String text = fetchUrlAsText(referenceUrl.trim());
                return requireNonBlank(text, "La URL aportada no devolvió contenido de texto utilizable.",
                        referenceUrl.trim());
            }
            if (!isBlank(referenceSearch)) {
                String text = searchWebAsText(referenceSearch.trim());
                return requireNonBlank(text, "La búsqueda no produjo resultados utilizables.",
                        referenceSearch.trim());
            }
            if (!isBlank(referenceFileContent)) {
                // El archivo ya llega como texto (leído en el navegador): se
                // normaliza y se recorta al tope de la referencia.
                String text = referenceFileContent.strip();
                return cap(text, MAX_REFERENCE_CHARS);
            }
            return null;
        } finally {
            if (provided > 0) {
                log.info("[wizard] Referencia resuelta en {} ms (guard SSRF: {})",
                        System.currentTimeMillis() - start, ssrfGuard);
            }
        }
    }

    // ═══════════════════ URL: fetch + HTML→texto ═══════════════════

    /**
     * Recupera una URL http/https pública y la convierte a texto legible
     * (HTML limpio o texto plano). Falla con IllegalArgumentException si la
     * URL no es válida, el host no es público, el content-type no es de
     * texto o el estado no es 200.
     */
    String fetchUrlAsText(String url) {
        URI uri = requirePublicHttpUrl(url);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,text/plain,application/json;q=0.9,*/*;q=0.5")
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalArgumentException("La URL aportada respondió con estado HTTP "
                        + response.statusCode() + ": no se pudo recuperar la referencia.");
            }
            String contentType = response.headers().firstValue("content-type").orElse("text/html")
                    .toLowerCase(Locale.ROOT);
            if (ALLOWED_CONTENT_TYPES.stream().noneMatch(contentType::contains)) {
                throw new IllegalArgumentException("La URL aportada no es contenido de texto ("
                        + contentType + "): no sirve como referencia.");
            }
            byte[] body = response.body();
            if (body != null && body.length > MAX_FETCH_BYTES) {
                body = java.util.Arrays.copyOf(body, MAX_FETCH_BYTES);
            }
            String raw = new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8);
            return contentType.contains("html") ? htmlToText(raw) : raw.strip();
        } catch (IOException e) {
            throw new IllegalArgumentException("No se pudo recuperar la URL de referencia ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage() + "). "
                    + "Comprueba la dirección o inténtalo de nuevo.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("La recuperación de la URL de referencia fue interrumpida.");
        }
    }

    // ═══════════ Búsqueda web: DuckDuckGo HTML + páginas top ═══════════

    /**
     * Busca un término en la web (DuckDuckGo HTML) y compone una referencia
     * con los snippets de los primeros resultados más el CONTENIDO best-effort
     * de las 2 primeras páginas. Si la búsqueda no llega a resolverse, falla
     * con IllegalArgumentException.
     */
    String searchWebAsText(String term) {
        String url = searchUrl + URLEncoder.encode(term, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html")
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        String html;
        try {
            HttpResponse<byte[]> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalArgumentException("La búsqueda web respondió con estado HTTP "
                        + response.statusCode() + ": no se pudo resolver la referencia.");
            }
            html = new String(response.body(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("No se pudo realizar la búsqueda web ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage() + "). "
                    + "Comprueba la conexión o inténtalo de nuevo.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("La búsqueda web fue interrumpida.");
        }

        List<SearchHit> hits = parseDdgResults(html);
        if (hits.isEmpty()) {
            throw new IllegalArgumentException(
                    "La búsqueda web no devolvió resultados utilizables: prueba con otro término.");
        }

        StringBuilder text = new StringBuilder();
        text.append("Web search results for \"").append(term).append("\":\n\n");
        int shown = 0;
        for (SearchHit hit : hits) {
            if (shown >= SEARCH_RESULT_LIMIT) {
                break;
            }
            shown++;
            text.append(shown).append(". ").append(hit.title().strip()).append('\n')
                    .append("   URL: ").append(hit.url()).append('\n');
            if (!isBlank(hit.snippet())) {
                text.append("   Summary: ").append(hit.snippet().strip()).append('\n');
            }
            text.append('\n');
        }

        // Contenido best-effort de las primeras páginas: un fallo se salta y
        // se conserva el resto de la referencia (los snippets ya son útiles).
        int fetchedPages = 0;
        for (SearchHit hit : hits) {
            if (fetchedPages >= SEARCH_PAGES_TO_FETCH) {
                break;
            }
            String pageUrl = hit.url();
            if (isBlank(pageUrl)) {
                continue;
            }
            try {
                String page = fetchUrlAsText(pageUrl);
                if (!isBlank(page)) {
                    text.append("--- Content of ").append(pageUrl).append(" ---\n\n")
                            .append(cap(page.strip(), MAX_SEARCH_PAGE_CHARS)).append("\n\n");
                    fetchedPages++;
                }
            } catch (RuntimeException e) {
                log.debug("[wizard] Página de resultados no recuperable ({}): {}",
                        pageUrl, e.getMessage());
            }
        }
        return text.toString();
    }

    /** Extrae los resultados (enlace + snippet) del HTML de DuckDuckGo. */
    static List<SearchHit> parseDdgResults(String html) {
        if (html == null || html.isBlank()) {
            return List.of();
        }

        // Snippets primero (menos numerosos): cualquier elemento con la clase.
        List<String> snippets = new ArrayList<>();
        Matcher snippetMatcher = DDG_SNIPPET.matcher(html);
        while (snippetMatcher.find() && snippets.size() < SEARCH_RESULT_LIMIT + 3) {
            snippets.add(decodeEntities(htmlToText(snippetMatcher.group(2))));
        }

        // Enlaces: cada <a> con clase result__a, en orden; el uddg lleva la URL real.
        List<SearchHit> hits = new ArrayList<>();
        Matcher anchor = DDG_ANCHOR.matcher(html);
        int index = 0;
        while (anchor.find() && hits.size() < SEARCH_RESULT_LIMIT) {
            String attrs = anchor.group(1);
            if (!attrs.toLowerCase(Locale.ROOT).contains("result__a")) {
                continue;
            }
            Matcher href = HREF_ATTR.matcher(attrs);
            if (!href.find()) {
                continue;
            }
            String url = extractUddgUrl(href.group(1));
            if (url == null) {
                url = decodeEntities(href.group(1));
            }
            String title = decodeEntities(htmlToText(anchor.group(2)));
            String snippet = index < snippets.size() ? snippets.get(index) : "";
            hits.add(new SearchHit(url, title, snippet));
            index++;
        }
        return hits;
    }

    /** El href de DDG es //duckduckgo.com/l/?uddg=<url codificada>: extrae y decodifica. */
    private static String extractUddgUrl(String href) {
        if (href == null) {
            return null;
        }
        Matcher uddg = UDDG_PARAM.matcher(href.replace("\u0026amp;", "\u0026"));
        if (!uddg.find()) {
            return null;
        }
        try {
            return java.net.URLDecoder.decode(uddg.group(1), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ═══════════════════ HTML → texto plano ═══════════════════

    /**
     * Convierte HTML a texto legible para el prompt: elimina comentarios,
     * script/style/noscript/head, convierte los cierres de bloque en saltos
     * de línea, quita las etiquetas, decodifica entidades y colapsa el
     * whitespace.
     */
    static String htmlToText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String text = html;
        text = text.replaceAll("(?is)<!--.*?-->", " ");
        text = text.replaceAll("(?is)<head\b.*?</head>", " ");
        text = text.replaceAll("(?is)<(script|style|noscript|svg|template)\b.*?</\\1>", " ");
        // Cierres de bloque → salto de línea (párrafos, filas, encabezados…).
        text = text.replaceAll("(?i)</(p|div|br|li|tr|h[1-6]|section|article|blockquote)>", "\n");
        text = text.replaceAll("(?i)<br\\s*/?>", "\n");
        text = text.replaceAll("<[^>]+>", " ");
        text = decodeEntities(text);
        // Colapsa espacios por línea y líneas vacías consecutivas.
        text = text.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        text = text.replaceAll("(?m)^\\s+$", "");
        text = text.replaceAll("\\n{3,}", "\\n\\n");
        return text.strip();
    }

    /** Entidades HTML esenciales + numéricas decimales/hex. */
    static String decodeEntities(String text) {
        if (text == null || text.isEmpty() || text.indexOf('&') < 0) {
            return text == null ? "" : text;
        }
        Matcher numeric = Pattern.compile("&#(x[0-9a-fA-F]+|\\d+);").matcher(text);
        StringBuilder out = new StringBuilder();
        while (numeric.find()) {
            try {
                int code = numeric.group(1).startsWith("x") || numeric.group(1).startsWith("X")
                        ? Integer.parseInt(numeric.group(1).substring(1), 16)
                        : Integer.parseInt(numeric.group(1));
                numeric.appendReplacement(out,
                        Matcher.quoteReplacement(new String(Character.toChars(Math.max(0, code)))));
            } catch (IllegalArgumentException e) {
                numeric.appendReplacement(out, Matcher.quoteReplacement(numeric.group()));
            }
        }
        numeric.appendTail(out);

        String decoded = out.toString()
                .replace("\u0026amp;", "\u0026")
                .replace("\u0026lt;", "<")
                .replace("\u0026gt;", ">")
                .replace("\u0026quot;", "\"")
                .replace("\u0026apos;", "'")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
                .replace("&mdash;", "—")
                .replace("&ndash;", "–")
                .replace("&hellip;", "…")
                .replace("&laquo;", "«")
                .replace("&raquo;", "»");
        return decoded;
    }

    // ═══════════════════ Guard SSRF ═══════════════════

    /**
     * Valida que la URL sea http/https y apunte a un host PÚBLICO: nada de
     * loopback, any-local, link-local, site-local, multicast, CGNAT
     * (100.64.0.0/10) ni IPv6 unique-local (fc00::/7).
     */
    URI requirePublicHttpUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("La URL de referencia no es válida: " + url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme))) {
            throw new IllegalArgumentException(
                    "La referencia URL solo admite http/https: " + url);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("La URL de referencia no tiene host: " + url);
        }
        if (!ssrfGuard) {
            return uri;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(
                    "El host de la referencia no se pudo resolver: " + uri.getHost());
        }
        for (InetAddress address : addresses) {
            if (isPrivateAddress(address)) {
                throw new IllegalArgumentException(
                        "La referencia no puede apuntar a direcciones privadas o locales: "
                                + uri.getHost());
            }
        }
        return uri;
    }

    /** ¿Es una dirección que un servidor público nunca debería fetchear? */
    static boolean isPrivateAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] ip = address.getAddress();
        // CGNAT IPv4 100.64.0.0/10.
        if (ip.length == 4 && (ip[0] & 0xFF) == 100
                && (ip[1] & 0xC0) == 64) {
            return true;
        }
        // IPv6 unique-local fc00::/7.
        return ip.length == 16 && (ip[0] & 0xFE) == 0xFC;
    }

    // ═══════════════════ Helpers ═══════════════════

    private static String requireNonBlank(String text, String message, String input) {
        if (isBlank(text)) {
            throw new IllegalArgumentException(message + " («" + input + "»)");
        }
        return text;
    }

    static String cap(String text, int limit) {
        if (text == null || text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + "\n…(referencia recortada a " + limit + " caracteres)";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
