package org.keycloak.social.discord;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.social.discord.DiscordExchangeResourceProvider.SessionStamp;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parts of the exchange endpoint that need no Keycloak session: the
 * configured-default rule for the client and provider, the per-address
 * rate limit with its sweep, and the choice of which sessions the per-user
 * cap removes.
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

    @Test
    void theCapRemovesNothingFromAnEmptyList() {
        assertEquals(List.of(), DiscordExchangeResourceProvider.oldestBeyondCap(List.of(), 3));
    }

    @Test
    void theCapRemovesNothingWhileFewerSessionsThanTheCapExist() {
        List<SessionStamp> two = List.of(new SessionStamp("a", 100), new SessionStamp("b", 200));
        assertEquals(List.of(), DiscordExchangeResourceProvider.oldestBeyondCap(two, 3));
    }

    @Test
    void theCapRemovesNothingAtExactlyTheCap() {
        List<SessionStamp> three = List.of(new SessionStamp("a", 100), new SessionStamp("b", 200), new SessionStamp("c", 300));
        assertEquals(List.of(), DiscordExchangeResourceProvider.oldestBeyondCap(three, 3));
    }

    @Test
    void beyondTheCapTheOldestGoFirstWhateverTheInputOrder() {
        List<SessionStamp> five = List.of(
                new SessionStamp("newest", 500),
                new SessionStamp("second-oldest", 200),
                new SessionStamp("middle", 300),
                new SessionStamp("oldest", 100),
                new SessionStamp("fourth", 400));
        assertEquals(List.of("oldest", "second-oldest"), DiscordExchangeResourceProvider.oldestBeyondCap(five, 3));
        assertEquals(List.of("oldest", "second-oldest", "middle", "fourth"), DiscordExchangeResourceProvider.oldestBeyondCap(five, 1));
    }

    @Test
    void aCapOfZeroOrLessDisablesTheLimit() {
        List<SessionStamp> five = List.of(
                new SessionStamp("a", 100), new SessionStamp("b", 200), new SessionStamp("c", 300),
                new SessionStamp("d", 400), new SessionStamp("e", 500));
        assertEquals(List.of(), DiscordExchangeResourceProvider.oldestBeyondCap(five, 0));
        assertEquals(List.of(), DiscordExchangeResourceProvider.oldestBeyondCap(five, -1));
    }

    @Test
    void equalTimestampsAreBrokenByIdSoTheAnswerIsStable() {
        // getStarted() is whole seconds, so a burst of retries ties.
        List<SessionStamp> burst = List.of(new SessionStamp("b", 100), new SessionStamp("c", 100), new SessionStamp("a", 100));
        List<SessionStamp> sameBurstReversed = List.of(new SessionStamp("a", 100), new SessionStamp("c", 100), new SessionStamp("b", 100));
        assertEquals(List.of("a"), DiscordExchangeResourceProvider.oldestBeyondCap(burst, 2));
        assertEquals(List.of("a"), DiscordExchangeResourceProvider.oldestBeyondCap(sameBurstReversed, 2));
        assertEquals(List.of("a", "b"), DiscordExchangeResourceProvider.oldestBeyondCap(burst, 1));
        // An older timestamp still comes before any tie on id.
        List<SessionStamp> mixed = List.of(new SessionStamp("a", 100), new SessionStamp("z", 50), new SessionStamp("b", 100));
        assertEquals(List.of("z", "a"), DiscordExchangeResourceProvider.oldestBeyondCap(mixed, 1));
    }

    @Test
    void theConfiguredCapDefaultsToThreeAndNegativeDisablesIt() {
        assertEquals(3, DiscordExchangeResourceProviderFactory.sessionCap(null));
        assertEquals(5, DiscordExchangeResourceProviderFactory.sessionCap(5));
        assertEquals(0, DiscordExchangeResourceProviderFactory.sessionCap(0));
        assertEquals(0, DiscordExchangeResourceProviderFactory.sessionCap(-7));
    }
}
