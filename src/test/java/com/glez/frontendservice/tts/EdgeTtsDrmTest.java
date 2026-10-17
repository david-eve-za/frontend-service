package com.glez.frontendservice.tts;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EdgeTtsDrm Tests")
class EdgeTtsDrmTest {

    @AfterEach
    void resetClockSkew() {
        // Restore the static state so other tests are not affected.
        EdgeTtsDrm.adjustClockSkewSeconds(-EdgeTtsDrm.getClockSkewSeconds());
    }

    @Test
    @DisplayName("Sec-MS-GEC v2 token is '<16 uppercase hex>-<64 uppercase hex>'")
    void generateSecMsGec_returnsUppercaseV2Format() {
        String token = EdgeTtsDrm.generateSecMsGec();

        assertNotNull(token);
        assertEquals(81, token.length(), "16 hex + '-' + 64 hex");
        assertTrue(token.matches("[0-9A-F]{16}-[0-9A-F]{64}"),
                "Token must be <16 hex>-<64 hex> uppercase but was: " + token);
    }

    @Test
    @DisplayName("v2 tokens match the Python reference implementation (edge-tts 7.3.1) byte-for-byte")
    void generateSecMsGec_matchesPythonReferenceVectors() {
        // Vectores generados con drm.py 7.3.1 VERBATIM (script de
        // referencia de edge-tts upstream) para timestamps explícitos.
        assertEquals("52BC7A17B50DFEC9-28C4394775C8751CD2F6859C030C06DB1489ACAB3B3A7651831FC26895177ED6",
                EdgeTtsDrm.generateSecMsGec(0.0));
        assertEquals("F7535A20A262E331-28675BF85C7106BBF20035A78FCB0B6890813EAD5C3B983EF3424931B720BABB",
                EdgeTtsDrm.generateSecMsGec(300.0));
        assertEquals("BA405D1577B8E0CE-61F01E88539977A1A1E969572D82EECFA45FB1E5DEBD122D6ACBAF0054F959E5",
                EdgeTtsDrm.generateSecMsGec(1234567890.5));
        assertEquals("35331C6609FA5EAD-4C75F4FE7755668221F8FF1E0968992B87161A1A8961121625C04F26300932D3",
                EdgeTtsDrm.generateSecMsGec(1660000000.125));
        assertEquals("3689BC1F78238B9A-DD2A2B71647FAA9B316154E46E5EEA92B85708C5C753EC77984ED8A78A848763",
                EdgeTtsDrm.generateSecMsGec(1763000000.75));
        assertEquals("452146D9A9DBED9A-8D3964906D00EB5E7D124996A1B864F710389C6007B99C551316A53768E95F37",
                EdgeTtsDrm.generateSecMsGec(1792249234.9));
    }

    @Test
    @DisplayName("Timestamps within the same 300-second window share one token")
    void generateSecMsGec_sameWindow_sameToken() {
        // 300.0 y 301.25 caen en la misma ventana redondeada de 5 minutos.
        assertEquals(EdgeTtsDrm.generateSecMsGec(300.0),
                EdgeTtsDrm.generateSecMsGec(301.25));
        assertNotEquals(EdgeTtsDrm.generateSecMsGec(300.0),
                EdgeTtsDrm.generateSecMsGec(600.0));
    }

    @Test
    @DisplayName("Sec-MS-GEC token is deterministic within the same 5-minute window")
    void generateSecMsGec_isDeterministicWithinSameWindow() {
        boolean matched = false;
        // Retry to be robust if the test runs exactly on a 5-minute boundary.
        for (int attempt = 0; attempt < 5 && !matched; attempt++) {
            String first = EdgeTtsDrm.generateSecMsGec();
            String second = EdgeTtsDrm.generateSecMsGec();
            matched = first.equals(second);
        }
        assertTrue(matched, "Consecutive calls within the same window must produce the same token");
    }

    @Test
    @DisplayName("Clock skew adjustment changes the generated token")
    void adjustClockSkewSeconds_changesToken() {
        String before = EdgeTtsDrm.generateSecMsGec();
        EdgeTtsDrm.adjustClockSkewSeconds(300);
        String after = EdgeTtsDrm.generateSecMsGec();

        assertNotEquals(before, after, "A 5-minute skew must shift the rounded window");
    }

    @Test
    @DisplayName("RFC 2616 dates are parsed into Unix timestamps")
    void parseRfc2616Date_parsesHttpDate() {
        String date = "Fri, 25 Sep 2026 12:00:00 GMT";

        Double parsed = EdgeTtsDrm.parseRfc2616Date(date);

        assertNotNull(parsed);
        assertEquals(Instant.parse("2026-09-25T12:00:00Z").getEpochSecond(), parsed, 0.001D);
    }

    @Test
    @DisplayName("RFC 2616 parsing returns null for invalid or missing dates")
    void parseRfc2616Date_withInvalidInput_returnsNull() {
        assertNull(EdgeTtsDrm.parseRfc2616Date("not-a-date"));
        assertNull(EdgeTtsDrm.parseRfc2616Date(""));
        assertNull(EdgeTtsDrm.parseRfc2616Date(null));
    }

    @Test
    @DisplayName("MUID is 32 uppercase hex characters")
    void generateMuid_returnsUppercaseHex() {
        String muid = EdgeTtsDrm.generateMuid();

        assertNotNull(muid);
        assertEquals(32, muid.length());
        assertTrue(muid.matches("[0-9A-F]{32}"));
    }
}
