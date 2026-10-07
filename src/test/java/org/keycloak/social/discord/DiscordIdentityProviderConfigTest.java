package org.keycloak.social.discord;

import org.junit.jupiter.api.Test;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The configurable Discord API base URL: its default, its trimming, the
 * URLs derived from it, and the two places that consume it -- the identity
 * provider's constructor (which feeds Keycloak's own token / user-info URL
 * fields) and the factory's admin-console property.
 */
class DiscordIdentityProviderConfigTest {

    private static final String STUB = "http://discord-stub:8092/api";

    @Test
    void unsetMeansTheRealDiscordApi() {
        DiscordIdentityProviderConfig config = new DiscordIdentityProviderConfig(new IdentityProviderModel());

        assertEquals("https://discord.com/api", config.getApiBaseUrl());
        assertEquals("https://discord.com/api/oauth2/token", config.discordTokenUrl());
        assertEquals("https://discord.com/api/users/@me", config.discordProfileUrl());
        assertEquals("https://discord.com/api/users/@me/guilds", config.discordGuildsUrl());
    }

    @Test
    void blankOrSlashOnlyAlsoMeansTheDefault() {
        for (String blank : new String[]{"", "   ", "/", " // "}) {
            DiscordIdentityProviderConfig config = new DiscordIdentityProviderConfig();
            config.setApiBaseUrl(blank);
            assertEquals(DiscordIdentityProviderConfig.DEFAULT_API_BASE_URL, config.getApiBaseUrl(), "'" + blank + "'");
        }
    }

    @Test
    void aConfiguredBaseUrlIsUsedForEveryApiCall() {
        IdentityProviderModel model = new IdentityProviderModel();
        model.getConfig().put("apiBaseUrl", STUB);
        DiscordIdentityProviderConfig config = new DiscordIdentityProviderConfig(model);

        assertEquals(STUB, config.getApiBaseUrl());
        assertEquals(STUB + "/oauth2/token", config.discordTokenUrl());
        assertEquals(STUB + "/users/@me", config.discordProfileUrl());
        assertEquals(STUB + "/users/@me/guilds", config.discordGuildsUrl());
    }

    @Test
    void trailingSlashesAndWhitespaceAreTrimmed() {
        DiscordIdentityProviderConfig config = new DiscordIdentityProviderConfig();

        config.setApiBaseUrl(STUB + "/");
        assertEquals(STUB, config.getApiBaseUrl());
        assertEquals(STUB + "/oauth2/token", config.discordTokenUrl());

        config.setApiBaseUrl("  " + STUB + "//  ");
        assertEquals(STUB, config.getApiBaseUrl());
    }

    @Test
    void theSetterStoresUnderTheConfigKey() {
        DiscordIdentityProviderConfig config = new DiscordIdentityProviderConfig();
        config.setApiBaseUrl(STUB);

        assertEquals(STUB, config.getConfig().get(DiscordIdentityProviderConfig.API_BASE_URL));
        assertEquals("apiBaseUrl", DiscordIdentityProviderConfig.API_BASE_URL);
    }

    @Test
    void theProviderFeedsKeycloaksTokenAndUserInfoUrlsFromItButKeepsTheRealAuthorizationUrl() {
        DiscordIdentityProviderConfig config = new DiscordIdentityProviderConfig();
        config.setApiBaseUrl(STUB);

        new DiscordIdentityProvider(null, config);

        assertEquals(STUB + "/oauth2/token", config.getTokenUrl());
        assertEquals(STUB + "/users/@me", config.getUserInfoUrl());
        assertEquals("https://discord.com/oauth2/authorize", config.getAuthorizationUrl());
    }

    @Test
    void theFactoryExposesItAsAStringPropertyDefaultingToTheRealApi() {
        List<ProviderConfigProperty> properties = new DiscordIdentityProviderFactory().getConfigProperties();
        ProviderConfigProperty property = properties.stream()
                .filter(p -> "apiBaseUrl".equals(p.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no apiBaseUrl property among " + properties));

        assertEquals(ProviderConfigProperty.STRING_TYPE, property.getType());
        assertEquals("https://discord.com/api", property.getDefaultValue());
        assertEquals("Discord API base URL", property.getLabel());
        assertTrue(property.getHelpText().contains("development"), property.getHelpText());
    }
}
