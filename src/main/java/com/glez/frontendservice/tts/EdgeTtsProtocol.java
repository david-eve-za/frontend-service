package com.glez.frontendservice.tts;

/**
 * Protocol constants for Microsoft Edge's online text-to-speech service,
 * ported from the Python edge-tts package (https://github.com/rany2/edge-tts).
 */
final class EdgeTtsProtocol {

    private EdgeTtsProtocol() {
    }

    static final String TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";

    private static final String BASE_URL = "speech.platform.bing.com/consumer/speech/synthesize/readaloud";

    static final String WSS_URL = "wss://" + BASE_URL + "/edge/v1?TrustedClientToken=" + TRUSTED_CLIENT_TOKEN;

    static final String CHROMIUM_FULL_VERSION = "143.0.3650.75";
    static final String CHROMIUM_MAJOR_VERSION = CHROMIUM_FULL_VERSION.split("\\.", 2)[0];

    /**
     * Sec-MS-GEC v2 (despliegue de Microsoft desde 2026-10): el token viaja
     * en formato "<16 hex CS64>-<64 hex SHA-256 modificado>" y la versión
     * declarada debe llevar el prefijo 2-. Un token v1 obtiene HTTP 403 en
     * el handshake del WebSocket en las regiones con el despliegue activo.
     * Alineado con edge-tts 7.3.1 (constants.py: CHROMIUM_FULL_VERSION se
     * mantiene en 143.0.3650.75 — el fix es el TOKEN, no el host ni el UA).
     */
    static final String SEC_MS_GEC_VERSION = "2-" + CHROMIUM_FULL_VERSION;

    static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            + " (KHTML, like Gecko) Chrome/" + CHROMIUM_MAJOR_VERSION + ".0.0.0 Safari/537.36"
            + " Edg/" + CHROMIUM_MAJOR_VERSION + ".0.0.0";

    static final String ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold";

    /**
     * Audio output format requested to the service. Constant bitrate MP3,
     * 24 kHz, 48 kbps, mono.
     */
    static final String OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3";

    /**
     * Maximum byte length of the SSML payload per synthesis request.
     */
    static final int MAX_MESSAGE_BYTES = 4096;

    static final String DEFAULT_VOICE = "es-MX-DaliaNeural";

    static final String SPEECH_CONFIG_MESSAGE = "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{"
            + "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\""
            + "},"
            + "\"outputFormat\":\"" + OUTPUT_FORMAT + "\""
            + "}}}}\r\n";
}
