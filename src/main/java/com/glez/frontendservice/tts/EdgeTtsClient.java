package com.glez.frontendservice.tts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java port of the Python edge-tts Communicate class
 * (https://github.com/rany2/edge-tts). Connects to Microsoft Edge's online
 * text-to-speech service over WebSocket and streams the synthesized audio
 * (24 kHz / 48 kbps / mono MP3) to the given output file.
 *
 * <p>Implemented with the JDK built-in {@code java.net.http.WebSocket},
 * so it does not require any additional dependency.
 */
@Component
public class EdgeTtsClient {

    private static final Logger log = LoggerFactory.getLogger(EdgeTtsClient.class);

    private static final Pattern VOICE_SHORT_NAME =
            Pattern.compile("^([a-z]{2,})-([A-Z]{2,})-(.+Neural)$");
    private static final Pattern VOICE_LONG_NAME =
            Pattern.compile("^Microsoft Server Speech Text to Speech Voice \\(.+,.+\\)$");
    private static final Pattern RATE_VOLUME_PATTERN = Pattern.compile("^[+-]\\d+%$");
    private static final Pattern PITCH_PATTERN = Pattern.compile("^[+-]\\d+Hz$");

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("EEE MMM dd yyyy HH:mm:ss", Locale.ENGLISH)
                    .withZone(ZoneOffset.UTC);

    @Value("${app.audio.voice:" + EdgeTtsProtocol.DEFAULT_VOICE + "}")
    private String voice;

    @Value("${app.audio.rate:+0%}")
    private String rate;

    @Value("${app.audio.volume:+0%}")
    private String volume;

    @Value("${app.audio.pitch:+0Hz}")
    private String pitch;

    @Value("${app.audio.connect-timeout:10}")
    private int connectTimeoutSeconds;

    @Value("${app.audio.receive-timeout:60}")
    private int receiveTimeoutSeconds;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * Synthesizes the given text to an MP3 audio file using Microsoft Edge's
     * online text-to-speech service.
     *
     * @param text        the text to synthesize
     * @param outputPath  the file where the MP3 audio will be written
     * @throws EdgeTtsException if the synthesis fails for any reason
     */
    public void synthesizeToFile(String text, Path outputPath) {
        if (text == null || text.isBlank()) {
            throw new EdgeTtsException("Text must not be null or blank.");
        }
        validateSettings();

        String processed = EdgeTtsTextUtils.escapeXml(
                EdgeTtsTextUtils.removeIncompatibleCharacters(text));
        List<String> chunks = EdgeTtsTextUtils.splitByByteLength(
                processed, EdgeTtsProtocol.MAX_MESSAGE_BYTES);

        long totalAudioBytes = 0;
        try (OutputStream out = Files.newOutputStream(outputPath)) {
            for (String chunk : chunks) {
                totalAudioBytes += synthesizeChunk(chunk, out);
            }
        } catch (IOException e) {
            throw new EdgeTtsException("Failed to write audio to " + outputPath, e);
        }

        if (totalAudioBytes == 0) {
            throw new EdgeTtsException.NoAudioReceived(
                    "No audio was received. Please verify that your parameters are correct.");
        }
    }

    public String getVoice() {
        return voice;
    }

    /**
     * Synthesizes a single pre-escaped text chunk, retrying once with clock
     * skew correction when the service answers 403 (mirrors the Python DRM
     * error handling).
     */
    private long synthesizeChunk(String escapedChunk, OutputStream out) {
        try {
            return streamChunkAudio(escapedChunk, out);
        } catch (WebSocketHandshakeException e) {
            handleHandshakeError(e);
            log.debug("Retrying Edge TTS request after clock skew adjustment");
            try {
                return streamChunkAudio(escapedChunk, out);
            } catch (WebSocketHandshakeException retry) {
                throw new EdgeTtsException.WebSocketError(
                        "Handshake failed after skew retry: " + retry.getMessage(), retry);
            } catch (IOException io) {
                throw new EdgeTtsException.WebSocketError("I/O error during synthesis", io);
            }
        } catch (IOException e) {
            throw new EdgeTtsException.WebSocketError("I/O error during synthesis", e);
        }
    }

    private long streamChunkAudio(String escapedChunk, OutputStream out) throws IOException {
        URI uri = URI.create(EdgeTtsProtocol.WSS_URL
                + "&ConnectionId=" + connectId()
                + "&Sec-MS-GEC=" + EdgeTtsDrm.generateSecMsGec()
                + "&Sec-MS-GEC-Version=" + EdgeTtsProtocol.SEC_MS_GEC_VERSION);

        StreamListener listener = new StreamListener();
        CompletableFutureHolder holder = new CompletableFutureHolder();
        try {
            java.net.http.WebSocket.Builder builder = httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                    .header("User-Agent", EdgeTtsProtocol.USER_AGENT)
                    .header("Accept-Encoding", "gzip, deflate, br, zstd")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Pragma", "no-cache")
                    .header("Cache-Control", "no-cache")
                    .header("Origin", EdgeTtsProtocol.ORIGIN)
                    .header("Cookie", "muid=" + EdgeTtsDrm.generateMuid() + ";");

            WebSocket webSocket = builder.buildAsync(uri, listener).join();
            holder.webSocket = webSocket;

            webSocket.sendText(speechConfigMessage(), true).join();
            webSocket.sendText(ssmlRequestMessage(escapedChunk), true).join();

            return pumpEvents(listener, out);
        } catch (CompletionException e) {
            throw unwrapCompletion(e);
        } finally {
            closeQuietly(holder.webSocket);
        }
    }

    /**
     * Consumes listener events until the service signals {@code turn.end},
     * writing every audio payload to the output stream.
     */
    private long pumpEvents(StreamListener listener, OutputStream out) {
        long audioBytes = 0;
        while (true) {
            Object event;
            try {
                event = listener.events().poll(receiveTimeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EdgeTtsException("Interrupted while waiting for audio", e);
            }
            if (event == null) {
                throw new EdgeTtsException.WebSocketError(
                        "Receive timeout (" + receiveTimeoutSeconds + "s) while waiting for service response",
                        null);
            }
            if (event instanceof StreamListener.TurnEnd) {
                return audioBytes;
            }
            if (event instanceof StreamListener.AudioPayload payload) {
                try {
                    out.write(payload.data());
                } catch (IOException e) {
                    throw new EdgeTtsException("Failed to write audio chunk", e);
                }
                audioBytes += payload.data().length;
            } else if (event instanceof StreamListener.StreamError error) {
                throw asEdgeTtsException(error.cause());
            }
        }
    }

    private void handleHandshakeError(WebSocketHandshakeException e) {
        HttpResponse<?> response = e.getResponse();
        if (response == null || response.statusCode() != 403) {
            throw new EdgeTtsException.WebSocketError(
                    "Edge TTS handshake failed: " + e.getMessage(), e);
        }
        String serverDate = response.headers().firstValue("Date").orElse(null);
        Double serverTimestamp = EdgeTtsDrm.parseRfc2616Date(serverDate);
        if (serverTimestamp == null) {
            throw new EdgeTtsException.SkewAdjustmentError("No server date in headers.", e);
        }
        double clientTimestamp = Instant.now().getEpochSecond()
                + Instant.now().getNano() / 1_000_000_000D;
        EdgeTtsDrm.adjustClockSkewSeconds(serverTimestamp - clientTimestamp);
    }

    private void closeQuietly(WebSocket webSocket) {
        if (webSocket == null) {
            return;
        }
        try {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "ok").join();
        } catch (RuntimeException ignored) {
            // The server may have closed the connection already.
        }
    }

    void validateSettings() {
        resolveVoice(voice);
        if (!RATE_VOLUME_PATTERN.matcher(rate).matches()) {
            throw new IllegalArgumentException("Invalid rate '" + rate + "'.");
        }
        if (!RATE_VOLUME_PATTERN.matcher(volume).matches()) {
            throw new IllegalArgumentException("Invalid volume '" + volume + "'.");
        }
        if (!PITCH_PATTERN.matcher(pitch).matches()) {
            throw new IllegalArgumentException("Invalid pitch '" + pitch + "'.");
        }
        if (connectTimeoutSeconds <= 0 || receiveTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("Timeouts must be greater than 0.");
        }
    }

    /**
     * Converts short voice names such as {@code es-MX-DaliaNeural} into the
     * long form the service expects, e.g.
     * {@code Microsoft Server Speech Text to Speech Voice (es-MX, DaliaNeural)}.
     */
    static String resolveVoice(String voice) {
        if (voice == null) {
            throw new IllegalArgumentException("Voice must not be null.");
        }
        Matcher shortName = VOICE_SHORT_NAME.matcher(voice);
        if (shortName.matches()) {
            String lang = shortName.group(1);
            String region = shortName.group(2);
            String name = shortName.group(3);
            int dash = name.indexOf('-');
            if (dash != -1) {
                region = region + "-" + name.substring(0, dash);
                name = name.substring(dash + 1);
            }
            return "Microsoft Server Speech Text to Speech Voice ("
                    + lang + "-" + region + ", " + name + ")";
        }
        if (!VOICE_LONG_NAME.matcher(voice).matches()) {
            throw new IllegalArgumentException("Invalid voice '" + voice + "'.");
        }
        return voice;
    }

    private String speechConfigMessage() {
        return "X-Timestamp:" + dateToString() + "\r\n"
                + "Content-Type:application/json; charset=utf-8\r\n"
                + "Path:speech.config\r\n\r\n"
                + EdgeTtsProtocol.SPEECH_CONFIG_MESSAGE;
    }

    private String ssmlRequestMessage(String escapedChunk) {
        String ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>"
                + "<voice name='" + resolveVoice(voice) + "'>"
                + "<prosody pitch='" + pitch + "' rate='" + rate + "' volume='" + volume + "'>"
                + escapedChunk
                + "</prosody>"
                + "</voice>"
                + "</speak>";
        return "X-RequestId:" + connectId() + "\r\n"
                + "Content-Type:application/ssml+xml\r\n"
                + "X-Timestamp:" + dateToString() + "Z\r\n"
                + "Path:ssml\r\n\r\n"
                + ssml;
    }

    private static String dateToString() {
        return TIMESTAMP_FORMAT.format(Instant.now())
                + " GMT+0000 (Coordinated Universal Time)";
    }

    private static String connectId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static IOException unwrapCompletion(CompletionException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        if (cause instanceof IOException io) {
            return io;
        }
        if (cause instanceof RuntimeException re) {
            throw re;
        }
        throw new EdgeTtsException("Unexpected error during WebSocket communication", cause);
    }

    private static EdgeTtsException asEdgeTtsException(Throwable t) {
        if (t instanceof EdgeTtsException edge) {
            return edge;
        }
        return new EdgeTtsException.WebSocketError(
                t.getMessage() != null ? t.getMessage() : "Unknown WebSocket error", t);
    }

    /**
     * Simple holder so the WebSocket can be closed from the finally block even
     * when the future join throws.
     */
    private static final class CompletableFutureHolder {
        volatile WebSocket webSocket;
    }

    /**
     * {@link WebSocket.Listener} that accumulates message fragments, parses
     * the service protocol frames and pushes the resulting events into a
     * queue consumed by {@link #pumpEvents(StreamListener, OutputStream)}.
     */
    private static final class StreamListener implements WebSocket.Listener {

        private final LinkedBlockingQueue<Object> events = new LinkedBlockingQueue<>();
        private final StringBuilder textBuffer = new StringBuilder();
        private final ByteArrayOutputStream binaryBuffer = new ByteArrayOutputStream();
        private volatile boolean completed;

        LinkedBlockingQueue<Object> events() {
            return events;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            webSocket.request(1);
            try {
                textBuffer.append(data);
                if (last) {
                    String message = textBuffer.toString();
                    textBuffer.setLength(0);
                    handleTextMessage(message);
                }
            } catch (RuntimeException e) {
                events.add(new StreamError(e));
            }
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.request(1);
            try {
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                binaryBuffer.writeBytes(bytes);
                if (last) {
                    byte[] message = binaryBuffer.toByteArray();
                    binaryBuffer.reset();
                    handleBinaryMessage(message);
                }
            } catch (RuntimeException e) {
                events.add(new StreamError(e));
            }
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            // The JDK automatically sends a reciprocal Pong.
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (!completed) {
                events.add(new StreamError(new EdgeTtsException.UnexpectedResponse(
                        "Connection closed before turn.end (status " + statusCode + ")")));
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            events.add(new StreamError(error));
        }

        /**
         * Handles a complete TEXT frame: headers and body separated by an
         * empty line, with a {@code Path} pseudo-header selecting the type.
         */
        private void handleTextMessage(String message) {
            int separator = message.indexOf("\r\n\r\n");
            if (separator < 0) {
                throw new EdgeTtsException.UnexpectedResponse(
                        "We received a text message, but it is missing the header separator.");
            }
            String headers = message.substring(0, separator);
            String path = headerValue(headers, "Path");
            if ("turn.end".equals(path)) {
                completed = true;
                events.add(new TurnEnd());
            } else if ("audio.metadata".equals(path)) {
                // Metadata is disabled in speech.config; ignore defensively if sent.
            } else if ("response".equals(path) || "turn.start".equals(path)) {
                // Informational frames.
            } else {
                throw new EdgeTtsException.UnknownResponse("Unknown path received: " + path);
            }
        }

        /**
         * Handles a complete BINARY frame: a 2-byte big-endian header length
         * (counting the 2 prefix bytes themselves), the headers, a CRLF
         * separator and the audio payload.
         */
        private void handleBinaryMessage(byte[] message) {
            if (message.length < 2) {
                throw new EdgeTtsException.UnexpectedResponse(
                        "We received a binary message, but it is missing the header length.");
            }
            int headerLength = ((message[0] & 0xFF) << 8) | (message[1] & 0xFF);
            if (headerLength > message.length) {
                throw new EdgeTtsException.UnexpectedResponse(
                        "The header length is greater than the length of the data.");
            }
            int headerStart = Math.min(2, headerLength);
            String headers = new String(message, headerStart,
                    Math.max(0, headerLength - headerStart), StandardCharsets.UTF_8);

            String path = headerValue(headers, "Path");
            if (!"audio".equals(path)) {
                throw new EdgeTtsException.UnexpectedResponse(
                        "Received binary message, but the path is not audio.");
            }
            int payloadStart = Math.min(headerLength + 2, message.length);
            byte[] payload = Arrays.copyOfRange(message, payloadStart, message.length);

            String contentType = headerValue(headers, "Content-Type");
            if (contentType == null) {
                if (payload.length == 0) {
                    // Termination of the stream: expected, nothing to do.
                    return;
                }
                throw new EdgeTtsException.UnexpectedResponse(
                        "Received binary message with no Content-Type, but with data.");
            }
            if (!"audio/mpeg".equals(contentType)) {
                throw new EdgeTtsException.UnexpectedResponse(
                        "Received binary message, but with an unexpected Content-Type.");
            }
            if (payload.length == 0) {
                throw new EdgeTtsException.UnexpectedResponse(
                        "Received binary message, but it is missing the audio data.");
            }
            events.add(new AudioPayload(payload));
        }

        private static String headerValue(String headers, String name) {
            for (String line : headers.split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                if (line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                    return line.substring(colon + 1).trim();
                }
            }
            return null;
        }

        record AudioPayload(byte[] data) {
        }

        record TurnEnd() {
        }

        record StreamError(Throwable cause) {
        }
    }
}
