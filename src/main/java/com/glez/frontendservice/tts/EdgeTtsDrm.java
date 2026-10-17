package com.glez.frontendservice.tts;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Handles the DRM operations required by Microsoft Edge's online TTS service,
 * ported from the Python edge-tts DRM module. The only operation needed is
 * generating the Sec-MS-GEC token, plus clock-skew correction when the
 * service answers 403 because the system clock is off.
 *
 * <p><b>v2 token (edge-tts 7.3.x, October 2026)</b>: Microsoft rolled out a
 * v2 of the Sec-MS-GEC DRM (upstream issue rany2/edge-tts#490) and the
 * legacy v1 token gets HTTP 403 on every WebSocket handshake in the regions
 * where the rollout is active. The v2 token changes the digest to the format
 * {@code "<16 hex CS64>-<64 hex SHA-256 modificado>"} over the SAME
 * "ticks + trusted client token" string (same float ticks path — recomputing
 * the ticks with exact integer arithmetic produces a different string for
 * some timestamps and breaks the CS64 half). Declared version:
 * {@code 2-<chromium>} — see {@link EdgeTtsProtocol#SEC_MS_GEC_VERSION}.</p>
 *
 * <p>All the 32-bit values are kept as masked longs (0..0xFFFFFFFF): Python's
 * integers are unbounded and several intermediate sums/products exceed 32
 * bits before being masked; Java's long arithmetic reproduces every masking
 * point of the reference implementation bit-for-bit. The
 * {@code value * 0x100000001} rotation trick is also reproduced with longs:
 * the maximum product is (2^32-1)(2^32+1) = 2^64-1, which fits the 64-bit
 * wrap-around pattern exactly, and the unsigned shift ({@code >>>}) matches
 * Python's {@code >>} on the positive product.</p>
 */
final class EdgeTtsDrm {

    /**
     * Seconds between the Unix epoch (1970-01-01) and the Windows file time
     * epoch (1601-01-01).
     */
    private static final long WIN_EPOCH_SECONDS = 11_644_473_600L;

    private static final long MASK32 = 0xFFFF_FFFFL;

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

    // ------------------------------------------------------------------
    // Sec-MS-GEC v2 token (edge-tts 7.3.x drm.py)
    // ------------------------------------------------------------------

    /**
     * Generates the v2 Sec-MS-GEC token for the current time (with clock
     * skew): "<16 hex CS64>-<64 hex modified SHA-256>". The ticks keep the
     * SAME float path as the legacy v1 (see the class javadoc).
     */
    static String generateSecMsGec() {
        Instant now = Instant.now();
        return generateSecMsGec(now.getEpochSecond() + (now.getNano() / 1_000_000_000D)
                + clockSkewSeconds);
    }

    /**
     * Generates the v2 Sec-MS-GEC token for an explicit Unix timestamp, for
     * reference-vector tests (mirrors generate_sec_ms_gec_v2 of the Python
     * reference implementation).
     */
    static String generateSecMsGec(double unixTimestamp) {
        double ticks = unixTimestamp + WIN_EPOCH_SECONDS;

        // Round down to the nearest 5 minutes (300 seconds).
        ticks -= ticks % 300;

        // Convert the ticks to 100-nanosecond intervals (Windows file time).
        ticks *= 1_000_000_000D / 100D;

        String strToHash = String.format(Locale.US, "%.0f%s",
                ticks, EdgeTtsProtocol.TRUSTED_CLIENT_TOKEN);
        byte[] data = strToHash.getBytes(StandardCharsets.US_ASCII);
        byte[] digest = sha256Digest(data);
        long[] cs64 = cs64Digest(data, digest);
        return packLeHex(cs64[0], cs64[1]) + "-" + hexUpper(digest);
    }

    /** hex(struct.pack("<II", a, b)).upper() — little-endian words. */
    private static String packLeHex(long a, long b) {
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt((int) a).putInt((int) b);
        return hexUpper(buf.array());
    }

    private static String hexUpper(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Modified SHA-256 (v2 digest half)
    // ------------------------------------------------------------------

    private static final long[] SHA256_INITIAL = {
            0x6A09E667L, 0xBB67AE85L, 0x3C6EF372L, 0xA54FF53AL,
            0x510E527FL, 0x9B05688CL, 0x1F83D9ABL, 0x5BE0CD19L,
    };

    /** Custom mid-stream initial state of the v2 SHA-256 variant. */
    private static final long[] SHA256_STATE = {
            0xFD9FBEA2L, 0x91243CE7L, 0xBE499094L, 0xCD2E1E0AL,
            0x6C006F49L, 0x04BD35ABL, 0xFEB0A7E4L, 0x03EFFBD2L,
    };

    private static final long[] SHA256_SCHEDULE = {
            0xEB24BF9EL, 0x1558691FL, 0xA0C09EE2L, 0x0CFA32BAL,
            0xA622EE8CL, 0xD1DD9491L, 0xC0DD25A0L, 0xF9E70AB6L,
            0xF0C31573L, 0x7058CE4EL, 0x7188E606L, 0x00E5AB33L,
            0xD258B060L, 0x72766564L,
    };

    /** Standard FIPS 180-4 round constants. */
    private static final long[] SHA256_ROUND_CONSTANTS = {
            0x428A2F98L, 0x71374491L, 0xB5C0FBCFL, 0xE9B5DBA5L,
            0x3956C25BL, 0x59F111F1L, 0x923F82A4L, 0xAB1C5ED5L,
            0xD807AA98L, 0x12835B01L, 0x243185BEL, 0x550C7DC3L,
            0x72BE5D74L, 0x80DEB1FEL, 0x9BDC06A7L, 0xC19BF174L,
            0xE49B69C1L, 0xEFBE4786L, 0x0FC19DC6L, 0x240CA1CCL,
            0x2DE92C6FL, 0x4A7484AAL, 0x5CB0A9DCL, 0x76F988DAL,
            0x983E5152L, 0xA831C66DL, 0xB00327C8L, 0xBF597FC7L,
            0xC6E00BF3L, 0xD5A79147L, 0x06CA6351L, 0x14292967L,
            0x27B70A85L, 0x2E1B2138L, 0x4D2C6DFCL, 0x53380D13L,
            0x650A7354L, 0x766A0ABBL, 0x81C2C92EL, 0x92722C85L,
            0xA2BFE8A1L, 0xA81A664BL, 0xC24B8B70L, 0xC76C51A3L,
            0xD192E819L, 0xD6990624L, 0xF40E3585L, 0x106AA070L,
            0x19A4C116L, 0x1E376C08L, 0x2748774CL, 0x34B0BCB5L,
            0x391C0CB3L, 0x4ED8AA4AL, 0x5B9CCA4FL, 0x682E6FF3L,
            0x748F82EEL, 0x78A5636FL, 0x84C87814L, 0x8CC70208L,
            0x90BEFFFAL, 0xA4506CEBL, 0xBEF9A3F7L, 0xC67178F2L,
    };

    private static final long SHA256_ADJUSTMENT = 0xC9DC06A7L;

    /** Round constants with index 14 replaced (mid-stream resume path). */
    private static final long[] SHA256_BLOCK_CONSTANTS = blockConstants();

    private static long[] blockConstants() {
        long[] constants = SHA256_ROUND_CONSTANTS.clone();
        constants[14] = SHA256_ADJUSTMENT;
        return constants;
    }

    private static final long SHA256_SIGMA0_ADJUSTMENT = 0x059C0B80L;
    private static final long SHA256_SIGMA1_ADJUSTMENT = 0x000B92C0L;

    /** Masked sigma of the schedule expansion (used by the init pass). */
    private static long sigma0(long value) {
        return ((value >>> 7) ^ (value << 25) ^ (value >>> 18) ^ (value << 14) ^ (value >>> 3))
                & MASK32;
    }

    private static long sigma1(long value) {
        return ((value >>> 17) ^ (value << 15) ^ (value >>> 19) ^ (value << 13) ^ (value >>> 10))
                & MASK32;
    }

    /**
     * Runs the SHA-256 rounds, optionally resuming inside the first block
     * (firstRound == 14) with the adjusted block constants. Ported line by
     * line from the reference: the first loop uses simultaneous (tuple)
     * assignments and the unrolled loop updates registers sequentially in
     * the exact original order.
     */
    private static long[] sha256Rounds(long[] state, long[] words, int firstRound) {
        long a = state[0], b = state[1], c = state[2], d = state[3];
        long e = state[4], f = state[5], g = state[6], h = state[7];
        long[] constants = firstRound == 14 ? SHA256_BLOCK_CONSTANTS : SHA256_ROUND_CONSTANTS;
        int aligned = (int) Math.min(64L, ((firstRound + 7) / 8) * 8);

        for (int index = firstRound; index < aligned; index++) {
            long rotE = e * 0x100000001L;
            long rotA = a * 0x100000001L;
            long t1 = h
                    + ((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25))
                    + (g ^ (e & (f ^ g)))
                    + constants[index]
                    + words[index];
            long t2 = ((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22)) + ((a & b) | (c & (a | b)));
            long newA = (t1 + t2) & MASK32;
            long newE = (d + t1) & MASK32;
            // Asignación "simultánea" de la tupla de la referencia: se
            // aplica en orden INVERSO de dependencias para que cada lectura
            // vea el valor viejo (h<-g, g<-f, f<-e, d<-c, c<-b, b<-a).
            h = g;
            g = f;
            f = e;
            d = c;
            c = b;
            b = a;
            a = newA;
            e = newE;
        }

        for (int index = aligned; index < 64; index += 8) {
            long rotE = e * 0x100000001L;
            long rotA = a * 0x100000001L;
            long t1 = h
                    + ((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25))
                    + (g ^ (e & (f ^ g)))
                    + constants[index]
                    + words[index];
            d = (d + t1) & MASK32;
            h = (t1 + ((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22)) + ((a & b) | (c & (a | b)))) & MASK32;
            rotE = d * 0x100000001L;
            rotA = h * 0x100000001L;
            t1 = g
                    + ((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25))
                    + (f ^ (d & (e ^ f)))
                    + constants[index + 1]
                    + words[index + 1];
            c = (c + t1) & MASK32;
            g = (t1 + ((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22)) + ((h & a) | (b & (h | a)))) & MASK32;
            rotE = c * 0x100000001L;
            rotA = g * 0x100000001L;
            t1 = f
                    + ((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25))
                    + (e ^ (c & (d ^ e)))
                    + constants[index + 2]
                    + words[index + 2];
            b = (b + t1) & MASK32;
            f = (t1 + ((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22)) + ((g & h) | (a & (g | h)))) & MASK32;
            rotE = b * 0x100000001L;
            rotA = f * 0x100000001L;
            t1 = e
                    + ((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25))
                    + (d ^ (b & (c ^ d)))
                    + constants[index + 3]
                    + words[index + 3];
            a = (a + t1) & MASK32;
            e = (t1 + ((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22)) + ((f & g) | (h & (f | g)))) & MASK32;
            rotE = a * 0x100000001L;
            rotA = e * 0x100000001L;
            t1 = d
                    + ((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25))
                    + (c ^ (a & (b ^ c)))
                    + constants[index + 4]
                    + words[index + 4];
            h = (h + t1) & MASK32;
            d = (t1 + ((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22)) + ((e & f) | (g & (e | f)))) & MASK32;
            rotE = h * 0x100000001L;
            rotA = d * 0x100000001L;
            t1 = c
                    + ((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25))
                    + (b ^ (h & (a ^ b)))
                    + constants[index + 5]
                    + words[index + 5];
            g = (g + t1) & MASK32;
            c = (t1 + ((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22)) + ((d & e) | (f & (d | e)))) & MASK32;
            rotE = g * 0x100000001L;
            rotA = c * 0x100000001L;
            t1 = b
                    + ((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25))
                    + (a ^ (g & (h ^ a)))
                    + constants[index + 6]
                    + words[index + 6];
            f = (f + t1) & MASK32;
            b = (t1 + ((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22)) + ((c & d) | (e & (c | d)))) & MASK32;
            rotE = f * 0x100000001L;
            rotA = b * 0x100000001L;
            t1 = a
                    + ((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25))
                    + (h ^ (f & (g ^ h)))
                    + constants[index + 7]
                    + words[index + 7];
            e = (e + t1) & MASK32;
            a = (t1 + ((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22)) + ((b & c) | (d & (b | c)))) & MASK32;
        }
        return new long[]{a, b, c, d, e, f, g, h};
    }

    /**
     * Expands the SHA-256 word schedule starting at firstWord. The sigmas
     * are computed UNMASKED (as the reference does) — the high bits of the
     * left shifts are discarded by the final mask of the sum.
     */
    private static void expandSchedule(long[] words, int firstWord) {
        for (int index = firstWord; index < 64; index++) {
            long x = words[index - 2];
            long y = words[index - 15];
            long sigma1 = (x >>> 17) ^ (x << 15) ^ (x >>> 19) ^ (x << 13) ^ (x >>> 10);
            long sigma0 = (y >>> 7) ^ (y << 25) ^ (y >>> 18) ^ (y << 14) ^ (y >>> 3);
            words[index] = (sigma1 + words[index - 7] + sigma0 + words[index - 16]) & MASK32;
        }
    }

    /**
     * Computes the initial SHA-256 state for the first 7 bytes of the token
     * data (the v2 "salt"): the first 16 words come from a fixed schedule
     * with sigma adjustments at indices 16 and 29, the schedule expands
     * from word 31 and the rounds resume mid-stream at round 14 with the
     * adjusted block constants.
     */
    private static long[] sha256Init(byte[] salt) {
        long[] words = new long[64];
        Arrays.fill(words, 0L);
        words[14] = ((salt[0] & 0xFFL) << 16) | ((salt[1] & 0xFFL) << 8) | (salt[2] & 0xFFL);
        words[15] = ((salt[3] & 0xFFL) << 24) | ((salt[4] & 0xFFL) << 16)
                | ((salt[5] & 0xFFL) << 8) | (salt[6] & 0xFFL);
        for (int index = 16; index < 30; index++) {
            long value = SHA256_SCHEDULE[index - 16];
            if (index - 2 >= 14) {
                value += (index == 16)
                        ? (sigma1(words[index - 2]) ^ SHA256_SIGMA1_ADJUSTMENT)
                        : sigma1(words[index - 2]);
            }
            if (index - 7 >= 14) {
                value += words[index - 7];
            }
            if (index - 15 >= 14) {
                value += (index == 29)
                        ? (sigma0(words[index - 15]) ^ SHA256_SIGMA0_ADJUSTMENT)
                        : sigma0(words[index - 15]);
            }
            words[index] = value & MASK32;
        }
        words[30] = (sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14]
                + SHA256_ADJUSTMENT - SHA256_ROUND_CONSTANTS[14]) & MASK32;
        expandSchedule(words, 31);
        long[] working = sha256Rounds(SHA256_STATE, words, 14);
        long[] state = new long[8];
        for (int i = 0; i < 8; i++) {
            state[i] = (SHA256_INITIAL[i] + working[i]) & MASK32;
        }
        return state;
    }

    /**
     * Computes the modified SHA-256 digest of the token data: the first 7
     * bytes seed the mid-stream initial state and the rest of the message
     * is padded with the reference's custom length field (57 + len(data),
     * NOT the true message length — a deliberate part of the v2 variant)
     * and processed as 64-bit big-endian-word blocks.
     */
    private static byte[] sha256Digest(byte[] data) {
        if (data.length < 7) {
            throw new IllegalArgumentException("Token salt must contain at least seven bytes");
        }
        long length = 57L + data.length;
        int zeroPad = (int) Math.floorMod(55L - length, 64L);
        byte[] padded = new byte[data.length - 7 + 1 + zeroPad + 8];
        int cursor = 0;
        for (int i = 7; i < data.length; i++) {
            padded[cursor++] = data[i];
        }
        padded[cursor] = (byte) 0x80;
        cursor += 1 + zeroPad;
        long bits = length * 8L;
        for (int i = 7; i >= 0; i--) {
            padded[cursor + i] = (byte) (bits >>> (8 * (7 - i)));
        }

        long[] state = sha256Init(Arrays.copyOfRange(data, 0, 7));
        for (int offset = 0; offset < padded.length; offset += 64) {
            long[] words = new long[64];
            for (int i = 0; i < 16; i++) {
                int base = offset + 4 * i;
                words[i] = ((padded[base] & 0xFFL) << 24) | ((padded[base + 1] & 0xFFL) << 16)
                        | ((padded[base + 2] & 0xFFL) << 8) | (padded[base + 3] & 0xFFL);
            }
            expandSchedule(words, 16);
            long[] working = sha256Rounds(state, words, 0);
            for (int i = 0; i < 8; i++) {
                state[i] = (state[i] + working[i]) & MASK32;
            }
        }

        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            out[4 * i] = (byte) (state[i] >>> 24);
            out[4 * i + 1] = (byte) (state[i] >>> 16);
            out[4 * i + 2] = (byte) (state[i] >>> 8);
            out[4 * i + 3] = (byte) state[i];
        }
        return out;
    }

    // ------------------------------------------------------------------
    // CS64 checksum (v2 prefix half)
    // ------------------------------------------------------------------

    /** (coefficient, offset) pairs of the CS64 table. */
    private static final long[][] CS64_COEFFICIENTS = {
            {0xE82E91B1L, 0xB32700D0L}, {0x44DE24C6L, 0x88DFF51DL},
            {0x3BEB65BCL, 0x29514038L}, {0xC89C5933L, 0xB328CBDFL},
            {0x8D1EC211L, 0xD405B4D5L}, {0x314EF5D5L, 0x26E55F3CL},
            {0xE92491B1L, 0x607801C6L}, {0xF0A5B9D5L, 0x82B2C1FBL},
            {0x06064CF3L, 0xFA51A3DAL}, {0xDA79FA60L, 0x207CC464L},
            {0xBED20560L, 0x3C3C74F3L}, {0x89F9C9F8L, 0xD2D14B61L},
            {0x35FA38C6L, 0xBB04A2C1L}, {0xCB49E3C6L, 0xC5626167L},
    };

    private static final long CS64_FACTOR = 0x82893933L;
    private static final long CS64_ADJUSTMENT0 = 0xF2F90B1AL;
    private static final long CS64_ADJUSTMENT1 = 0x0B1A0000L;

    /** Rotate a 32-bit value left by 16 bits. */
    private static long rol16(long value) {
        value &= MASK32;
        return ((value << 16) | (value >>> 16)) & MASK32;
    }

    /** Little-endian 32-bit words of the data, zero-padded to an even count. */
    private static List<Long> wordsLe4Even(byte[] data) {
        int paddedLength = data.length + (int) Math.floorMod(-data.length, 4);
        List<Long> words = new ArrayList<>(paddedLength / 4 + 1);
        for (int offset = 0; offset < paddedLength; offset += 4) {
            long word = 0;
            for (int i = 0; i < 4; i++) {
                int index = offset + i;
                word |= (index < data.length ? (data[index] & 0xFFL) : 0L) << (8 * i);
            }
            words.add(word);
        }
        if (words.size() % 2 == 1) {
            words.add(0L);
        }
        return words;
    }

    /**
     * Computes the CS64 digest words for the token data, keyed by the first
     * two little-endian words of the SHA-256 digest (with the low bit
     * forced to 1). Ported line by line from the reference: every
     * multiplication/addition wraps at 64 bits exactly like Python's
     * unbounded arithmetic before each explicit mask.
     */
    private static long[] cs64Digest(byte[] data, byte[] digest) {
        long keyA = (leUInt32(digest, 0) | 1L);
        long keyB = (leUInt32(digest, 4) | 1L);
        long[] factors = {
                (keyA * CS64_FACTOR) & MASK32,
                (keyB * CS64_FACTOR) & MASK32,
        };

        List<Long> terms = new ArrayList<>();
        List<Long> offsets = new ArrayList<>();
        for (int index = 0; index < CS64_COEFFICIENTS.length; index++) {
            terms.add((CS64_COEFFICIENTS[index][0] * factors[index % 2]) & MASK32);
            offsets.add(CS64_COEFFICIENTS[index][1]);
        }

        byte[] prefixed = new byte[data.length + 1];
        System.arraycopy(data, 0, prefixed, 1, data.length);
        List<Long> words = wordsLe4Even(prefixed);
        for (int index = 0; index < words.size(); index++) {
            long word = words.get(index);
            long term = word * ((index % 2 == 0) ? keyA : keyB);
            long offset = rol16(word) * ((index % 2 == 0) ? 0xEF0569FBL : 0xC31713DBL);
            if (index == 0) {
                term += CS64_ADJUSTMENT0 * factors[0];
                offset += CS64_ADJUSTMENT1;
            }
            terms.add(term & MASK32);
            offsets.add(offset & MASK32);
        }

        long swapAcc = 0;
        long swapLast = 0;
        long state = 0;
        long acc = 0;
        for (int index = 0; index < terms.size(); index += 2) {
            long x = terms.get(index);
            long y = terms.get(index + 1);
            long t = rol16((offsets.get(index) + x) & MASK32);
            long q = (t * 0x76F50F34L) & MASK32;
            swapAcc += (rol16(q) * 0xC3EFEA97L + q) & MASK32;
            t = rol16((offsets.get(index + 1) + y) & MASK32);
            q = (t * 0x1881CE3CL) & MASK32;
            swapLast = (rol16(q) * 0x35BD1EC9L + q) & MASK32;
            swapAcc = (swapAcc + swapLast) & MASK32;

            state = (state * keyA + x) & MASK32;
            state = (rol16(state) * 0xCF98B111L) & MASK32;
            state = (rol16(state) * 0x87085B9FL) & MASK32;
            state = (rol16(state) * 0x12CEB96DL) & MASK32;
            state = (rol16(state) * 0x257E1D83L) & MASK32;
            acc += state;
            t = (state * keyB + y) & MASK32;
            t = (rol16(t) * 0xA27416F5L) & MASK32;
            t = (rol16(t) * 0xD38396FFL) & MASK32;
            state = (rol16((rol16(t) * 0x7C932B89L) & MASK32) * 0xBFA49F69L) & MASK32;
            acc = (acc + state) & MASK32;
        }
        return new long[]{swapLast ^ state, swapAcc ^ acc};
    }

    private static long leUInt32(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFFL)
                | ((bytes[offset + 1] & 0xFFL) << 8)
                | ((bytes[offset + 2] & 0xFFL) << 16)
                | ((bytes[offset + 3] & 0xFFL) << 24));
    }

    // ------------------------------------------------------------------
    // Shared utilities (unchanged by the v2 rollout)
    // ------------------------------------------------------------------

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
}
