package com.glez.frontendservice.services;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests del servicio de REFERENCIA OPCIONAL del paso 5 del Asistente de
 * Historias: modo archivo (passthrough), modo URL (fetch + HTML→texto) y
 * modo búsqueda web (parseo del HTML de DuckDuckGo + páginas top
 * best-effort), con el guard SSRF cubierto por unidades puras.
 *
 * <p>El endpoint de búsqueda y el guard SSRF son inyectables: los tests de
 * HTTP corren contra un {@link HttpServer} local del JDK con el guard
 * DESACTIVADO (solo el test constructor lo permite); las reacciones del
 * guard se prueban con IP literales, sin red.</p>
 */
@DisplayName("Wizard reference service tests")
class WizardReferenceServiceTest {

    /** La misma página de la que cuelgan los resultados del test. */
    private static final String PAGE1_HTML = "<html><head><script>var spy = 1;</script></head>"
            + "<body><h1>Panadería de Kioto</h1><p>El pan de molde y el anpan nacieron aquí.</p>"
            + "</body></html>";

    private HttpServer server;
    private String baseUrl;
    private WizardReferenceService service;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        int port = server.getAddress().getPort();
        baseUrl = "http://127.0.0.1:" + port;
        // Guard DESACTIVADO solo para poder servir contra 127.0.0.1.
        service = new WizardReferenceService(false, baseUrl + "/search?q=");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        byte[] body;
        String type = "text/html; charset=utf-8";
        int status = 200;
        switch (path) {
            case "/page1" -> body = PAGE1_HTML.getBytes(StandardCharsets.UTF_8);
            case "/page2" -> {
                body = "Historia del pan japonés en texto plano.".getBytes(StandardCharsets.UTF_8);
                type = "text/plain; charset=utf-8";
            }
            case "/binary" -> {
                body = new byte[]{0, 1, 2, 3};
                type = "application/octet-stream";
            }
            case "/empty" -> body = "   ".getBytes(StandardCharsets.UTF_8);
            case "/search" -> body = ddgResultsHtml().getBytes(StandardCharsets.UTF_8);
            case "/search-empty" -> body = "<html><body>Sin resultados</body></html>"
                    .getBytes(StandardCharsets.UTF_8);
            default -> {
                status = 404;
                body = "not found".getBytes(StandardCharsets.UTF_8);
            }
        }
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** HTML con la forma de html.duckduckgo.com: enlaces result__a (uddg) + snippets. */
    private String ddgResultsHtml() {
        return "<html><head><title>panaderías</title></head><body>"
                + "<div class=\"results\">"
                // Resultado 1: URL irrecuperable (puerto 1, conexión rechazada) — se salta best-effort.
                + "<h2 class=\"result__title\"><a rel=\"nofollow\" class=\"result__a\" "
                + "href=\"//duckduckgo.com/l/?uddg=http%3A%2F%2F127.0.0.1%3A1%2Fpanaderias"
                + "\u0026amp;rut=abc\">Panaderías tradicionales de Kioto</a></h2>"
                + "<a class=\"result__snippet\" href=\"x\">Las <b>panaderías de Kioto</b> famosas por su mochi…</a>"
                // Resultado 2: página local 1 — su contenido se embebe.
                + "<h2 class=\"result__title\"><a rel=\"nofollow\" class=\"result__a\" "
                + "href=\"//duckduckgo.com/l/?uddg=" + encode(baseUrl + "/page1")
                + "\u0026amp;rut=def\">Historia del pan de Kioto</a></h2>"
                + "<a class=\"result__snippet\">El pan de molde japonés nació en una panadería de Tokio…</a>"
                // Resultado 3: página local 2 (texto plano).
                + "<h2 class=\"result__title\"><a rel=\"nofollow\" class=\"result__a\" "
                + "href=\"//duckduckgo.com/l/?uddg=" + encode(baseUrl + "/page2")
                + "\u0026amp;rut=ghi\">El anpan y su origen</a></h2>"
                + "<a class=\"result__snippet\">Un dulce con pasta de judía.</a>"
                + "</div></body></html>";
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // ═══════════════════ resolveReference: contrato ═══════════════════

    @Test
    @DisplayName("Sin ninguna referencia devuelve null (la referencia es 100% opcional)")
    void resolveReference_none_returnsNull() {
        assertNull(service.resolveReference(null, null, null));
        assertNull(service.resolveReference("  ", "", "  "));
    }

    @Test
    @DisplayName("Más de una referencia falla pidiendo UNA sola")
    void resolveReference_moreThanOne_fails() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.resolveReference("contenido", "http://ejemplo.com", null));
        assertTrue(e.getMessage().contains("solo UNA referencia"), e.getMessage());
    }

    @Test
    @DisplayName("Modo archivo: el contenido llega tal cual (leído en el navegador)")
    void resolveReference_fileContent_passthrough() {
        String reference = service.resolveReference(
                "  Notas visuales de la panadería: madera cálida, vapor, luz de tarde.  ",
                null, null);
        assertEquals("Notas visuales de la panadería: madera cálida, vapor, luz de tarde.", reference);
    }

    @Test
    @DisplayName("Modo archivo: el contenido se recorta al tope de la referencia")
    void resolveReference_fileContent_capsToLimit() {
        String big = "x".repeat(WizardReferenceService.MAX_REFERENCE_CHARS + 500);
        String reference = service.resolveReference(big, null, null);
        assertTrue(reference.length() > WizardReferenceService.MAX_REFERENCE_CHARS);
        assertTrue(reference.startsWith("x".repeat(100)));
        assertTrue(reference.endsWith("caracteres)"));
    }

    // ═══════════════════ Modo URL ═══════════════════

    @Test
    @DisplayName("Modo URL: recupera la página, limpia el HTML y decodifica entidades")
    void resolveReference_url_fetchesAndStripsHtml() {
        String reference = service.resolveReference(null, baseUrl + "/page1", null);

        // El contenido llega limpio: sin script, sin etiquetas, con el texto real.
        assertTrue(reference.contains("Panadería de Kioto"), reference);
        assertTrue(reference.contains("El pan de molde y el anpan nacieron aquí."), reference);
        assertFalse(reference.contains("<script>"), reference);
        assertFalse(reference.contains("<h1>"), reference);
        assertFalse(reference.contains("</body>"), reference);
    }

    @Test
    @DisplayName("Modo URL: estado no-200 falla con el código en el mensaje")
    void resolveReference_urlNon200_fails() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.resolveReference(null, baseUrl + "/missing", null));
        assertTrue(e.getMessage().contains("estado HTTP 404"), e.getMessage());
    }

    @Test
    @DisplayName("Modo URL: content-type binario se rechaza (solo texto como referencia)")
    void resolveReference_urlBinaryContent_fails() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.resolveReference(null, baseUrl + "/binary", null));
        assertTrue(e.getMessage().contains("no es contenido de texto"), e.getMessage());
    }

    @Test
    @DisplayName("Modo URL: contenido vacío tras la limpieza falla con mensaje accionable")
    void resolveReference_urlEmptyContent_fails() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.resolveReference(null, baseUrl + "/empty", null));
        assertTrue(e.getMessage().contains("no devolvió contenido"), e.getMessage());
    }

    // ═══════════════════ Modo búsqueda web ═══════════════════

    @Test
    @DisplayName("Modo búsqueda: snippets de los resultados + contenido best-effort de las páginas top")
    void resolveReference_search_composesSnippetsAndTopPages() {
        String reference = service.resolveReference(
                null, null, "panaderías tradicionales de Kioto");

        assertTrue(reference.startsWith("Web search results for \"panaderías tradicionales de Kioto\":"),
                reference);
        // Título + URL decodificada del uddg + snippet del primer resultado.
        assertTrue(reference.contains("1. Panaderías tradicionales de Kioto"), reference);
        assertTrue(reference.contains("http://127.0.0.1:1/panaderias"), reference);
        assertTrue(reference.contains("Las panaderías de Kioto famosas por su mochi"), reference);
        // Segundo resultado.
        assertTrue(reference.contains("2. Historia del pan de Kioto"), reference);
        assertTrue(reference.contains("URL: " + baseUrl + "/page1"), reference);
        // Contenido best-effort: la página irrecuperable se SALTA y las
        // locales se embeben con su cabecera.
        assertFalse(reference.contains("--- Content of http://127.0.0.1:1"), reference);
        assertTrue(reference.contains("--- Content of " + baseUrl + "/page1 ---"), reference);
        assertTrue(reference.contains("Panadería de Kioto"), reference);
        assertTrue(reference.contains("--- Content of " + baseUrl + "/page2 ---"), reference);
        assertTrue(reference.contains("Historia del pan japonés en texto plano."), reference);
    }

    @Test
    @DisplayName("Modo búsqueda: sin resultados utilizables falla con mensaje accionable")
    void resolveReference_searchWithoutResults_fails() {
        WizardReferenceService emptySearch =
                new WizardReferenceService(false, baseUrl + "/search-empty?q=");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> emptySearch.resolveReference(null, null, "término raro sin resultados"));
        assertTrue(e.getMessage().contains("no devolvió resultados"), e.getMessage());
    }

    // ═══════════════════ Guard SSRF (unidades puras, sin red) ═══════════════════

    @Test
    @DisplayName("Guard SSRF: rechaza esquemas que no son http/https y hosts locales")
    void requirePublicHttpUrl_rejectsNonHttpAndLocalHosts() {
        WizardReferenceService guarded = new WizardReferenceService(true);

        assertThrows(IllegalArgumentException.class,
                () -> guarded.requirePublicHttpUrl("ftp://ejemplo.com/x"));
        assertThrows(IllegalArgumentException.class,
                () -> guarded.requirePublicHttpUrl("texto sin esquema"));
        assertThrows(IllegalArgumentException.class,
                () -> guarded.requirePublicHttpUrl("http://127.0.0.1/admin"));
        assertThrows(IllegalArgumentException.class,
                () -> guarded.requirePublicHttpUrl("http://10.0.0.1/interno"));
        assertThrows(IllegalArgumentException.class,
                () -> guarded.requirePublicHttpUrl("http://192.168.1.10/router"));
        assertThrows(IllegalArgumentException.class,
                () -> guarded.requirePublicHttpUrl("http://localhost/config"));
    }

    @Test
    @DisplayName("Guard SSRF: clasificación de direcciones privadas vs públicas")
    void isPrivateAddress_classifiesCorrectly() throws Exception {
        assertTrue(WizardReferenceService.isPrivateAddress(
                InetAddress.getByAddress(new byte[]{10, 0, 0, 1})), "10/8 es privada");
        assertTrue(WizardReferenceService.isPrivateAddress(
                InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 1, 1})), "192.168/16 es privada");
        assertTrue(WizardReferenceService.isPrivateAddress(
                InetAddress.getByAddress(new byte[]{(byte) 169, (byte) 254, 1, 1})), "link-local es privada");
        assertTrue(WizardReferenceService.isPrivateAddress(
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1})), "loopback es privada");
        assertTrue(WizardReferenceService.isPrivateAddress(
                InetAddress.getByAddress(new byte[]{100, 64, 0, 1})), "CGNAT 100.64/10 es privada");
        assertTrue(WizardReferenceService.isPrivateAddress(
                InetAddress.getByAddress(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1})),
                "IPv6 loopback es privada");
        assertTrue(WizardReferenceService.isPrivateAddress(
                InetAddress.getByAddress(new byte[]{(byte) 0xFC, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1})),
                "IPv6 unique-local fc00::/7 es privada");

        assertFalse(WizardReferenceService.isPrivateAddress(
                InetAddress.getByAddress(new byte[]{93, (byte) 184, (byte) 216, 34})), "IP pública");
        assertFalse(WizardReferenceService.isPrivateAddress(
                InetAddress.getByAddress(new byte[]{0x26, 0x06, 0x47, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x20})),
                "IPv6 pública");
    }

    // ═══════════════════ HTML → texto y parseo DDG ═══════════════════

    @Test
    @DisplayName("htmlToText: quita script/head/etiquetas, decodifica entidades y colapsa")
    void htmlToText_stripsAndDecodes() {
        String html = "<html><head><title>Título</title></head>"
                + "<body><!-- comentario --><script>var x = 1;</script>"
                + "<style>.a { color: red }</style>"
                + "<p>Caf\u0026#233; y pan \u0026amp; mantequilla</p><div>Segundo bloque</div>"
                + "</body></html>";
        String text = WizardReferenceService.htmlToText(html);

        assertFalse(text.contains("script"));
        assertFalse(text.contains("<p>"));
        assertFalse(text.contains("comentario"));
        assertTrue(text.contains("Café y pan & mantequilla"), text);
        assertTrue(text.contains("Segundo bloque"), text);
        assertTrue(text.contains("\n"), "Los bloques generan saltos de línea");
    }

    @Test
    @DisplayName("parseDdgResults: extrae título, URL real (uddg decodificada) y snippet")
    void parseDdgResults_extractsHits() {
        String html = ddgResultsHtml();
        List<WizardReferenceService.SearchHit> hits = WizardReferenceService.parseDdgResults(html);

        assertEquals(3, hits.size());
        assertEquals("Panaderías tradicionales de Kioto", hits.get(0).title());
        assertEquals("http://127.0.0.1:1/panaderias", hits.get(0).url());
        assertTrue(hits.get(0).snippet().contains("mochi"), hits.get(0).snippet());
        assertEquals(baseUrl + "/page1", hits.get(1).url());
        assertEquals("El anpan y su origen", hits.get(2).title());
    }

    @Test
    @DisplayName("cap: recorta al límite dejando constancia del recorte")
    void cap_marksTrimmedReferences() {
        String text = "a".repeat(100);
        String capped = WizardReferenceService.cap(text, 10);
        assertTrue(capped.startsWith("a".repeat(10)));
        assertTrue(capped.endsWith("caracteres)"));
        assertEquals(text, WizardReferenceService.cap(text, 100));
    }

    @Test
    @DisplayName("El constructor por defecto (Spring) levanta con el guard activo")
    void defaultConstructor_enablesGuard() {
        WizardReferenceService spring = new WizardReferenceService();
        assertThrows(IllegalArgumentException.class,
                () -> spring.requirePublicHttpUrl("http://127.0.0.1/x"));
    }
}
