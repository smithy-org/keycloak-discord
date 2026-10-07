/*
 * Copyright 2018 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.social.discord;

import org.keycloak.broker.oidc.OAuth2IdentityProviderConfig;
import org.keycloak.models.IdentityProviderModel;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author <a href="mailto:wadahiro@gmail.com">Hiroyuki Wada</a>
 */
public class DiscordIdentityProviderConfig extends OAuth2IdentityProviderConfig {

    /** Config key for {@link #getApiBaseUrl()}; unset or blank means {@link #DEFAULT_API_BASE_URL}. */
    public static final String API_BASE_URL = "apiBaseUrl";
    /** Discord's real HTTP API, which every production realm talks to. */
    public static final String DEFAULT_API_BASE_URL = "https://discord.com/api";

    private static final String TOKEN_PATH = "/oauth2/token";
    private static final String PROFILE_PATH = "/users/@me";
    private static final String GUILDS_PATH = "/users/@me/guilds";

    public DiscordIdentityProviderConfig(IdentityProviderModel model) {
        super(model);
    }

    public DiscordIdentityProviderConfig() {
    }

    /**
     * Base URL of the Discord HTTP API, without a trailing slash. Every
     * server-to-server call this provider makes -- the code-for-token
     * exchange, the profile lookup and the guild list -- is resolved against
     * it, so a development realm can point it at a local stand-in for Discord
     * (see the README's "Development against a stub"). The browser-facing
     * authorization URL is deliberately <em>not</em> derived from it: the
     * redirect to Discord's consent page only ever makes sense against the
     * real Discord ({@link DiscordIdentityProvider#AUTH_URL}).
     * <p>
     * Unset, blank, or nothing but slashes falls back to
     * {@link #DEFAULT_API_BASE_URL}; surrounding whitespace and trailing
     * slashes are trimmed so {@code http://discord-stub:8092/api/} works.
     */
    public String getApiBaseUrl() {
        String configured = getConfig().get(API_BASE_URL);
        if (configured == null) {
            return DEFAULT_API_BASE_URL;
        }
        String trimmed = stripTrailingSlashes(configured.trim());
        return trimmed.isEmpty() ? DEFAULT_API_BASE_URL : trimmed;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        getConfig().put(API_BASE_URL, apiBaseUrl);
    }

    /** {@code <apiBaseUrl>/oauth2/token}: where an authorization code is exchanged for Discord tokens. */
    public String discordTokenUrl() {
        return getApiBaseUrl() + TOKEN_PATH;
    }

    /** {@code <apiBaseUrl>/users/@me}: the profile of the user the access token belongs to. */
    public String discordProfileUrl() {
        return getApiBaseUrl() + PROFILE_PATH;
    }

    /** {@code <apiBaseUrl>/users/@me/guilds}: the guilds that user is a member of (the allow-list check). */
    public String discordGuildsUrl() {
        return getApiBaseUrl() + GUILDS_PATH;
    }

    private static String stripTrailingSlashes(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }

    public String getAllowedGuilds() {
        return getConfig().get("allowedGuilds");
    }

    public void setAllowedGuilds(String allowedGuilds) {
        getConfig().put("allowedGuilds", allowedGuilds);
    }

    public boolean hasAllowedGuilds() {
        String guilds = getConfig().get("allowedGuilds");
        return guilds != null && !guilds.trim().isEmpty();
    }

    public Set<String> getAllowedGuildsAsSet() {
        if (hasAllowedGuilds()) {
            String guilds = getConfig().get("allowedGuilds");
            return Arrays.stream(guilds.split(",")).map(x -> x.trim()).collect(Collectors.toSet());
        }
        return Collections.emptySet();
    }

    public void setPrompt(String prompt) {
        getConfig().put("prompt", prompt);
    }
}
