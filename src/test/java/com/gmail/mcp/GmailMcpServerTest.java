package com.gmail.mcp;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GmailMcpServerTest {

    private static final int DEFAULT_MAX_RESULTS = 10;

    // --- parseMaxResults ---

    @Test
    void parseMaxResults_integerNumber() {
        assertEquals(25, GmailMcpServer.parseMaxResults(Map.of("maxResults", 25)));
    }

    @Test
    void parseMaxResults_stringParseable() {
        assertEquals(50, GmailMcpServer.parseMaxResults(Map.of("maxResults", "50")));
    }

    @Test
    void parseMaxResults_stringUnparseableReturnsDefault() {
        assertEquals(DEFAULT_MAX_RESULTS, GmailMcpServer.parseMaxResults(Map.of("maxResults", "abc")));
    }

    @Test
    void parseMaxResults_nullReturnsDefault() {
        Map<String, Object> args = new HashMap<>();
        args.put("maxResults", null);
        assertEquals(DEFAULT_MAX_RESULTS, GmailMcpServer.parseMaxResults(args));
    }

    @Test
    void parseMaxResults_missingKeyReturnsDefault() {
        assertEquals(DEFAULT_MAX_RESULTS, GmailMcpServer.parseMaxResults(Map.of()));
    }

    @Test
    void parseMaxResults_zero() {
        assertEquals(0, GmailMcpServer.parseMaxResults(Map.of("maxResults", 0)));
    }

    @Test
    void parseMaxResults_negative() {
        assertEquals(-5, GmailMcpServer.parseMaxResults(Map.of("maxResults", -5)));
    }

    @Test
    void parseMaxResults_booleanReturnsDefault() {
        assertEquals(DEFAULT_MAX_RESULTS, GmailMcpServer.parseMaxResults(Map.of("maxResults", true)));
    }

    @Test
    void parseMaxResults_veryLargeNumber() {
        assertEquals(Integer.MAX_VALUE, GmailMcpServer.parseMaxResults(Map.of("maxResults", (long) Integer.MAX_VALUE)));
    }
}
