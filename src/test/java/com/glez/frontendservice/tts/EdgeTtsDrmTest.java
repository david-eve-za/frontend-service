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
    @DisplayName("Sec-MS-GEC token is 64 uppercase hex characters")
    void generateSecMsGec_returnsUppercaseHexToken() {
        String token = EdgeTtsDrm.generateSecMsGec();

        assertNotNull(token);
        assertEquals(64, token.length());
        assertTrue(token.matches("[0-9A-F]{64}"),
                "Token must be 64 uppercase hex chars but was: " + token);
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
