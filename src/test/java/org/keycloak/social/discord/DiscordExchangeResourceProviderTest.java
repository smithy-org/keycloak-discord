package org.keycloak.social.discord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.social.discord.DiscordExchangeResourceProvider.DiscordProfile;
import org.keycloak.social.discord.DiscordExchangeResourceProvider.ProfileRefresh;
import org.keycloak.social.discord.DiscordExchangeResourceProvider.SessionStamp;
import org.keycloak.social.discord.DiscordExchangeResourceProvider.StoredProfile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.keycloak.social.discord.DiscordExchangeResourceProvider.ATTR_DISCORD_AVATAR;
import static org.keycloak.social.discord.DiscordExchangeResourceProvider.ATTR_DISCORD_GLOBAL_NAME;
import static org.keycloak.social.discord.DiscordExchangeResourceProvider.ATTR_DISCORD_ID;
import static org.keycloak.social.discord.DiscordExchangeResourceProvider.ATTR_DISCORD_USERNAME;
import static org.keycloak.social.discord.DiscordExchangeResourceProvider.planProfileRefresh;

/**
 * The parts of the exchange endpoint that need no Keycloak session: the
 * configured-default rule for the client and provider, the per-address
 * rate limit with its sweep, the choice of which sessions the per-user cap
 * removes, and what each exchange writes to the user from the Discord
 * profile.
 */
class DiscordExchangeResourceProviderTest {

    private static final String SNOWFLAKE = "123456789012345678";
    private static final Predicate<String> NAME_IS_FREE = name -> false;
    private static final Predicate<String> NAME_IS_TAKEN = name -> true;

    @BeforeEach
    void resetRateLimit() {
        DiscordExchangeResourceProvider.resetRateLimit();
    }

    /** A user as an earlier exchange left it: named after the handle, every attribute in place. */
    private static StoredProfile storedUser(String handle, String globalName, String avatar) {
        Map<String, String> attributes = new HashMap<>();
        attributes.put(ATTR_DISCORD_ID, SNOWFLAKE);
        attributes.put(ATTR_DISCORD_USERNAME, handle);
        if (globalName != null) {
            attributes.put(ATTR_DISCORD_GLOBAL_NAME, globalName);
        }
        if (avatar != null) {
            attributes.put(ATTR_DISCORD_AVATAR, avatar);
        }
        return new StoredProfile(handle, handle, attributes);
    }

    private static Map<String, String> changes(String... namesAndValues) {
        Map<String, String> changes = new HashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            changes.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return changes;
    }

    @Test
    void anUnchangedProfileWritesNothing() {
        StoredProfile stored = storedUser("alice", "Alice", "a1b2c3");
        ProfileRefresh refresh = planProfileRefresh(stored, SNOWFLAKE, new DiscordProfile("alice", "Alice", "a1b2c3"), NAME_IS_TAKEN);

        assertEquals(Map.of(), refresh.attributes());
        assertNull(refresh.renameTo());
        assertNull(refresh.linkUserName());
        assertFalse(refresh.refreshesProfile());
    }

    @Test
    void aChangedHandleRenamesTheUserAndTheFederatedIdentityWhenTheNameIsFree() {
        StoredProfile stored = storedUser("alice", "Alice", "a1b2c3");
        ProfileRefresh refresh = planProfileRefresh(stored, SNOWFLAKE, new DiscordProfile("alice_renamed", "Alice", "a1b2c3"), NAME_IS_FREE);

        assertEquals("alice_renamed", refresh.renameTo());
        assertEquals("alice_renamed", refresh.linkUserName());
        assertEquals(changes(ATTR_DISCORD_USERNAME, "alice_renamed"), refresh.attributes());
        assertTrue(refresh.refreshesProfile());
    }

    @Test
    void aTakenNameKeepsTheOldUsernameButStillRecordsTheNewHandle() {
        StoredProfile stored = storedUser("alice", "Alice", "a1b2c3");
        ProfileRefresh refresh = planProfileRefresh(stored, SNOWFLAKE, new DiscordProfile("bob", "Alice", "a1b2c3"), NAME_IS_TAKEN);

        assertNull(refresh.renameTo());
        assertEquals("bob", refresh.linkUserName());
        assertEquals(changes(ATTR_DISCORD_USERNAME, "bob"), refresh.attributes());
    }

    @Test
    void theClashCheckOnlyRunsWhenTheHandleChanged() {
        StoredProfile stored = storedUser("alice", "Alice", "a1b2c3");
        Predicate<String> mustNotBeAsked = name -> {
            throw new AssertionError("asked whether '" + name + "' is taken");
        };
        planProfileRefresh(stored, SNOWFLAKE, new DiscordProfile("alice", "Alicia", null), mustNotBeAsked);
    }

    @Test
    void aCaseOnlyDifferenceIsNotARenameBecauseKeycloakStoresUsernamesInLowerCase() {
        // Keycloak lower-cases the username it stores, while the handle the
        // exchange sees keeps its case when the provider's "case-sensitive
        // original username" option is on: a handle that differs from the
        // stored username only in case is the same name, not a rename.
        Map<String, String> attributes = changes(ATTR_DISCORD_ID, SNOWFLAKE, ATTR_DISCORD_USERNAME, "Alice#1234");
        StoredProfile stored = new StoredProfile("alice#1234", "Alice#1234", attributes);
        Predicate<String> mustNotBeAsked = name -> {
            throw new AssertionError("asked whether '" + name + "' is taken");
        };
        ProfileRefresh refresh = planProfileRefresh(stored, SNOWFLAKE, new DiscordProfile("Alice#1234", null, null), mustNotBeAsked);

        assertNull(refresh.renameTo());
        assertNull(refresh.linkUserName());
        assertEquals(Map.of(), refresh.attributes());
    }

    @Test
    void theDisplayNameAndAvatarAreSetWhenTheyAppearAndRemovedWhenTheyGo() {
        StoredProfile withoutEither = storedUser("alice", null, null);
        ProfileRefresh set = planProfileRefresh(withoutEither, SNOWFLAKE, new DiscordProfile("alice", "Alice", "a1b2c3"), NAME_IS_TAKEN);
        assertEquals(changes(ATTR_DISCORD_GLOBAL_NAME, "Alice", ATTR_DISCORD_AVATAR, "a1b2c3"), set.attributes());
        assertTrue(set.refreshesProfile());

        StoredProfile withBoth = storedUser("alice", "Alice", "a1b2c3");
        ProfileRefresh changed = planProfileRefresh(withBoth, SNOWFLAKE, new DiscordProfile("alice", "Alicia", "d4e5f6"), NAME_IS_TAKEN);
        assertEquals(changes(ATTR_DISCORD_GLOBAL_NAME, "Alicia", ATTR_DISCORD_AVATAR, "d4e5f6"), changed.attributes());

        ProfileRefresh cleared = planProfileRefresh(withBoth, SNOWFLAKE, new DiscordProfile("alice", null, null), NAME_IS_TAKEN);
        assertEquals(changes(ATTR_DISCORD_GLOBAL_NAME, null, ATTR_DISCORD_AVATAR, null), cleared.attributes());
        assertTrue(cleared.attributes().containsKey(ATTR_DISCORD_GLOBAL_NAME), "a null value means remove");
        assertNull(cleared.renameTo());
        assertNull(cleared.linkUserName());
    }

    @Test
    void discordIdIsFilledInWhereMissingAndNeverChangedAfterwards() {
        // A user whose federated link predates the attribute.
        StoredProfile legacy = new StoredProfile("alice", "alice", Map.of());
        ProfileRefresh filledIn = planProfileRefresh(legacy, SNOWFLAKE, new DiscordProfile("alice", null, null), NAME_IS_TAKEN);
        assertEquals(changes(ATTR_DISCORD_ID, SNOWFLAKE, ATTR_DISCORD_USERNAME, "alice"), filledIn.attributes());

        // Once present it is left alone, whatever else changes.
        StoredProfile current = storedUser("alice", "Alice", "a1b2c3");
        ProfileRefresh everythingElse = planProfileRefresh(current, SNOWFLAKE, new DiscordProfile("alice2", "Alicia", "d4e5f6"), NAME_IS_FREE);
        assertFalse(everythingElse.attributes().containsKey(ATTR_DISCORD_ID));
        assertEquals(3, everythingElse.attributes().size());
    }

    @Test
    void aFreshlyCreatedUserGetsEveryAttributeAndNoRename() {
        // findOrCreateFederatedUser() named the user after the handle and
        // stored the handle on the link; the attributes are still empty.
        StoredProfile created = new StoredProfile("alice", "alice", Map.of());
        ProfileRefresh refresh = planProfileRefresh(created, SNOWFLAKE, new DiscordProfile("alice", "Alice", "a1b2c3"), NAME_IS_TAKEN);

        assertEquals(changes(ATTR_DISCORD_ID, SNOWFLAKE, ATTR_DISCORD_USERNAME, "alice",
                ATTR_DISCORD_GLOBAL_NAME, "Alice", ATTR_DISCORD_AVATAR, "a1b2c3"), refresh.attributes());
        assertNull(refresh.renameTo());
        assertNull(refresh.linkUserName());
    }

    @Test
    void aPlaceholderUserIsRenamedToTheHandleOnceItIsFree() {
        // Created under the snowflake name because the handle was taken at
        // the time (or by an earlier version that never used the handle).
        StoredProfile placeholder = new StoredProfile("discord_" + SNOWFLAKE, "alice", changes(ATTR_DISCORD_ID, SNOWFLAKE));
        DiscordProfile profile = new DiscordProfile("alice", null, null);

        assertNull(planProfileRefresh(placeholder, SNOWFLAKE, profile, NAME_IS_TAKEN).renameTo());
        assertEquals("alice", planProfileRefresh(placeholder, SNOWFLAKE, profile, NAME_IS_FREE).renameTo());
    }

    @Test
    void aBlankHandleLeavesEveryUsernameAlone() {
        StoredProfile stored = storedUser("alice", "Alice", "a1b2c3");
        for (String blank : new String[]{null, "", "  "}) {
            ProfileRefresh refresh = planProfileRefresh(stored, SNOWFLAKE, new DiscordProfile(blank, "Alice", "a1b2c3"), NAME_IS_FREE);
            assertNull(refresh.renameTo(), "'" + blank + "'");
            assertNull(refresh.linkUserName(), "'" + blank + "'");
            assertEquals(Map.of(), refresh.attributes(), "'" + blank + "'");
        }
    }

    @Test
    void theProfileIsReadFromTheDocumentTheProviderKeepsOnTheContext() throws Exception {
        DiscordProfile profile = DiscordProfile.from(identityFor(
                "{\"id\":\"" + SNOWFLAKE + "\",\"username\":\"alice\",\"discriminator\":\"0\","
                        + "\"global_name\":\"Alice\",\"avatar\":\"a1b2c3\",\"email\":\"alice@example.com\"}"));

        assertEquals("alice", profile.username());
        assertEquals("Alice", profile.globalName());
        assertEquals("a1b2c3", profile.avatar());
    }

    @Test
    void aLegacyDiscriminatorStaysPartOfTheHandleWhichKeycloakLowerCases() throws Exception {
        DiscordProfile profile = DiscordProfile.from(identityFor(
                "{\"id\":\"" + SNOWFLAKE + "\",\"username\":\"Alice\",\"discriminator\":\"1234\",\"global_name\":null,\"avatar\":null}"));

        // BrokeredIdentityContext.getUsername() lower-cases the handle unless
        // the provider's "case-sensitive original username" option is on, so
        // by default the exchange already works in Keycloak's case.
        assertEquals("alice#1234", profile.username());
    }

    @Test
    void aNullDisplayNameOrAvatarReadsAsAbsent() throws Exception {
        DiscordProfile nulls = DiscordProfile.from(identityFor(
                "{\"id\":\"" + SNOWFLAKE + "\",\"username\":\"alice\",\"discriminator\":\"0\",\"global_name\":null,\"avatar\":null}"));
        assertNull(nulls.globalName());
        assertNull(nulls.avatar());

        DiscordProfile missing = DiscordProfile.from(identityFor(
                "{\"id\":\"" + SNOWFLAKE + "\",\"username\":\"alice\",\"discriminator\":\"0\"}"));
        assertNull(missing.globalName());
        assertNull(missing.avatar());

        // No document on the context at all (not how the provider builds an
        // identity, but nothing downstream should depend on that).
        DiscordProfile bare = DiscordProfile.from(new BrokeredIdentityContext(SNOWFLAKE, enabledProvider()));
        assertNull(bare.username());
        assertNull(bare.globalName());
        assertNull(bare.avatar());
    }

    /** The identity exactly as the exchange gets it: built by the provider's own profile parsing. */
    private static BrokeredIdentityContext identityFor(String discordUsersMeJson) throws Exception {
        JsonNode document = new ObjectMapper().readTree(discordUsersMeJson);
        DiscordIdentityProvider provider = new DiscordIdentityProvider(null, new DiscordIdentityProviderConfig(enabledProvider()));
        return provider.extractIdentityFromProfile(null, document);
    }

    /** {@link BrokeredIdentityContext} refuses a disabled provider, and a fresh model is disabled. */
    private static IdentityProviderModel enabledProvider() {
        IdentityProviderModel model = new IdentityProviderModel();
        model.setEnabled(true);
        return model;
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
