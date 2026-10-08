/*
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

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resource.RealmResourceProviderFactory;

/**
 * Registers {@link DiscordExchangeResourceProvider} under
 * {@code /realms/{realm}/discord-exchange/token} -- a redirect-free way to mint
 * a token for a user whose Discord identity has already been established
 * out of band (e.g. a Discord Activity's {@code authorize()} SDK call, which
 * cannot complete Keycloak's normal browser-redirect broker login: that flow
 * depends on Keycloak's own session cookie, which is third-party inside an
 * embedded iframe and gets blocked by every modern browser).
 * <p>
 * {@code identityProviderAlias} and {@code clientId} are deployment-specific
 * (which realm's Discord identity provider, and which client's tokens to
 * mint) and are read here as configurable defaults -- via this provider's
 * standard Keycloak SPI config (e.g.
 * {@code SPI_REALM_RESTAPI_EXTENSION_DISCORD_EXCHANGE_IDENTITY_PROVIDER_ALIAS}),
 * not hardcoded, so this stays usable by any deployment. A caller may still
 * override either per request. {@code maxSessionsPerUser} (same scope, e.g.
 * {@code ..._MAX_SESSIONS_PER_USER}; default {@value #DEFAULT_MAX_SESSIONS_PER_USER},
 * {@code 0} disables) bounds how many sessions one user accumulates through
 * the endpoint.
 */
public class DiscordExchangeResourceProviderFactory implements RealmResourceProviderFactory {

    public static final String ID = "discord-exchange";
    /** Sessions one user may hold in the realm after an exchange before the oldest are removed. */
    static final int DEFAULT_MAX_SESSIONS_PER_USER = 3;

    private String defaultIdentityProviderAlias;
    private String defaultClientId;
    private int maxSessionsPerUser = DEFAULT_MAX_SESSIONS_PER_USER;

    @Override
    public RealmResourceProvider create(KeycloakSession session) {
        return new DiscordExchangeResourceProvider(session, defaultIdentityProviderAlias, defaultClientId, maxSessionsPerUser);
    }

    @Override
    public void init(Config.Scope config) {
        this.defaultIdentityProviderAlias = config.get("identityProviderAlias");
        this.defaultClientId = config.get("clientId");
        this.maxSessionsPerUser = sessionCap(config.getInt("maxSessionsPerUser", DEFAULT_MAX_SESSIONS_PER_USER));
    }

    /** Unset means the default; zero or anything negative disables the cap. */
    static int sessionCap(Integer configured) {
        if (configured == null) {
            return DEFAULT_MAX_SESSIONS_PER_USER;
        }
        return Math.max(0, configured);
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // no-op
    }

    @Override
    public void close() {
        // no-op
    }

    @Override
    public String getId() {
        return ID;
    }
}
