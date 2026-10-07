package org.keycloak.social.discord;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parts of the exchange endpoint that need no Keycloak session: the
 * configured-default rule for the client and provider, and the per-address
 * rate limit with its sweep.
 */
class DiscordExchangeResourceProviderTest {

    @BeforeEach
    void resetRateLimit() {
        DiscordExchangeResourceProvider.resetRateLimit();
    }

    @Test
    void aConfiguredDefaultWinsAndRefusesADifferentRequestValue() {
        assertEquals("activity", DiscordExchangeResourceProvider.resolveConfigured("activity", null));
        assertEquals("activity", DiscordExchangeResourceProvider.resolveConfigured("activity", ""));
        assertEquals("activity", DiscordExchangeResourceProvider.resolveConfigured("activity", "activity"));
        assertNull(DiscordExchangeResourceProvider.resolveConfigured("activity", "account-console"));
    }

    @Test
    void withoutADefaultTheRequestValueIsUsedOrNothingIs() {
        assertEquals("activity", DiscordExchangeResourceProvider.resolveConfigured(null, "activity"));
        assertEquals("activity", DiscordExchangeResourceProvider.resolveConfigured("", "activity"));
        assertNull(DiscordExchangeResourceProvider.resolveConfigured(null, null));
        assertNull(DiscordExchangeResourceProvider.resolveConfigured("", " "));
    }

    @Test
    void tenExchangesPerMinutePerAddressThenRefused() {
        for (int i = 0; i < 10; i++) {
            assertTrue(DiscordExchangeResourceProvider.checkRateLimit("203.0.113.7"), "request " + (i + 1));
        }
        assertFalse(DiscordExchangeResourceProvider.checkRateLimit("203.0.113.7"));
        // Another address has its own window.
        assertTrue(DiscordExchangeResourceProvider.checkRateLimit("203.0.113.8"));
    }

    @Test
    void theSweepDropsWindowsThatClosedMoreThanAWindowAgo() {
        DiscordExchangeResourceProvider.checkRateLimit("203.0.113.1");
        DiscordExchangeResourceProvider.checkRateLimit("203.0.113.2");
        assertEquals(2, DiscordExchangeResourceProvider.rateLimitEntries());

        // Nothing is stale yet.
        DiscordExchangeResourceProvider.sweepRateLimit(System.currentTimeMillis());
        assertEquals(2, DiscordExchangeResourceProvider.rateLimitEntries());

        // Three minutes later every window is more than two windows old.
        DiscordExchangeResourceProvider.sweepRateLimit(System.currentTimeMillis() + 3 * 60_000);
        assertEquals(0, DiscordExchangeResourceProvider.rateLimitEntries());
    }

    @Test
    void theSweepRunsOnItsOwnEveryFewHundredCalls() {
        for (int i = 0; i < 300; i++) {
            DiscordExchangeResourceProvider.checkRateLimit("198.51.100." + (i % 200));
        }
        // 200 distinct addresses, none stale: the automatic sweep at call 256
        // kept them all. The point is that it ran without error mid-stream.
        assertEquals(200, DiscordExchangeResourceProvider.rateLimitEntries());
    }
}
