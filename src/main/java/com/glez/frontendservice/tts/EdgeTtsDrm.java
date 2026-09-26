package com.glez.frontendservice.tts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.DateTimeException;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Handles the DRM operations required by Microsoft Edge's online TTS service,
 * ported from the Python edge-tts DRM module. The only operation needed is
 * generating the Sec-MS-GEC token, plus clock-skew correction when the
 * service answers 403 because the system clock is off.
 */
final class EdgeTtsDrm {

    /**
     * Seconds between the Unix epoch (1970-01-01) and the Windows file time
     * epoch (1601-01-01).
     */
    private static final long WIN_EPOCH_SECONDS = 11_644_473_600L;

    private static final DateTimeFormatter RFC_2616_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
                    .withZone(java.time.ZoneOffset.UTC);

    /**
     * Clock skew in seconds applied to the system time before hashing,
     * corrected from the server's Date header on 403 responses.
     */
    private static volatile double clockSkewSeconds = 0.0D;

    private EdgeTtsDrm() {
    }

    static double getClockSkewSeconds() {
        return clockSkewSeconds;
    }

    static void adjustClockSkewSeconds(double skewSeconds) {
        clockSkewSeconds += skewSeconds;
    }

    /**
     * Generates the Sec-MS-GEC token value: the current time converted to
     * Windows file time, rounded down to the nearest 5 minutes, concatenated
     * with the trusted client token and hashed with SHA-256 (uppercase hex).
     */
    static String generateSecMsGec() {
        double unixNow = Instant.now().getEpochSecond()
                + (Instant.now().getNano() / 1_000_000_000D)
                + clockSkewSeconds;

        // Switch to Windows file time epoch (1601-01-01 00:00:00 UTC).
        double ticks = unixNow + WIN_EPOCH_SECONDS;

        // Round down to the nearest 5 minutes (300 seconds).
        ticks -= ticks % 300;

        // Convert the ticks to 100-nanosecond intervals (Windows file time format).
        ticks *= 1_000_000_000D / 100D;

        String strToHash = String.format(Locale.US, "%.0f%s", ticks, EdgeTtsProtocol.TRUSTED_CLIENT_TOKEN);

        return sha256Hex(strToHash).toUpperCase(Locale.ROOT);
    }

    /**
     * Parses an RFC 2616 date string (e.g. the HTTP Date header) into a Unix
     * timestamp in seconds, or null if parsing failed.
     */
    static Double parseRfc2616Date(String date) {
        if (date == null) {
            return null;
        }
        try {
            ZonedDateTime parsed = ZonedDateTime.parse(date, RFC_2616_DATE);
            return parsed.toEpochSecond() + (parsed.getNano() / 1_000_000_000D);
        } catch (DateTimeException ignored) {
            return null;
        }
    }

    /**
     * Generates a random MUID cookie value (32 uppercase hex characters).
     */
    static String generateMuid() {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 16; i++) {
            sb.append(String.format("%02X", ThreadLocalRandom.current().nextInt(256)));
        }
        return sb.toString();
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.US_ASCII));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
