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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.util.SimpleHttp;
import org.keycloak.events.EventBuilder;
import org.keycloak.events.EventType;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.FederatedIdentityModel;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.protocol.oidc.TokenManager;
import org.keycloak.representations.AccessTokenResponse;
import org.keycloak.services.Urls;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.util.DefaultClientSessionContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code POST /realms/{realm}/discord-exchange/token}.
 * <p>
 * Exchanges a Discord authorization code (from
 * {@code discordSdk.commands.authorize()}, Discord's own iframe-safe login
 * primitive for embedded apps) for a Keycloak-issued token pair, with no
 * browser redirect and no dependency on Keycloak's session cookie. Reuses
 * {@link DiscordIdentityProvider}'s existing Discord-response parsing
 * ({@link DiscordIdentityProvider#getFederatedIdentity(String)}, inherited
 * from Keycloak's {@code AbstractOAuth2IdentityProvider}) rather than
 * reimplementing it -- the one part that is <em>not</em> reused is that
 * class's inherited token-request builder, which always targets Keycloak's
 * own {@code /broker/{alias}/endpoint} redirect URI. Discord's documented
 * Activity token exchange sends no {@code redirect_uri} at all (the
 * Embedded App SDK handles the return trip itself), so this builds the
 * token request directly instead.
 */
public class DiscordExchangeResourceProvider implements RealmResourceProvider {

    private static final Logger log = Logger.getLogger(DiscordExchangeResourceProvider.class);

    private static final int MAX_CODE_LENGTH = 512;

    /**
     * Single-node in-memory rate limit: this provider instance is created
     * per request by Keycloak, so the counters live on the factory-shared
     * static map, keyed by source IP. A fixed window is enough to blunt
     * abuse of an endpoint that triggers two outbound Discord API calls per
     * request; it is not a substitute for edge-level rate limiting in a
     * multi-node deployment.
     */
    private static final Map<String, RequestWindow> RATE_LIMIT = new ConcurrentHashMap<>();
    private static final int RATE_LIMIT_MAX_REQUESTS = 10;
    private static final long RATE_LIMIT_WINDOW_MS = 60_000;

    private final KeycloakSession session;
    private final String defaultIdentityProviderAlias;
    private final String defaultClientId;

    public DiscordExchangeResourceProvider(KeycloakSession session, String defaultIdentityProviderAlias, String defaultClientId) {
        this.session = session;
        this.defaultIdentityProviderAlias = defaultIdentityProviderAlias;
        this.defaultClientId = defaultClientId;
    }

    @Override
    public Object getResource() {
        return this;
    }

    @Override
    public void close() {
        // no-op
    }

    @POST
    @Path("token")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response token(ExchangeRequest req) {
        String clientIp = session.getContext().getConnection().getRemoteAddr();
        if (!checkRateLimit(clientIp)) {
            log.warnf("discord-exchange rate limit exceeded for %s", clientIp);
            return errorResponse(Response.Status.TOO_MANY_REQUESTS, "rate_limited", "Too many requests.");
        }

        if (req == null || isBlank(req.code) || req.code.length() > MAX_CODE_LENGTH) {
            return errorResponse(Response.Status.BAD_REQUEST, "invalid_request", "Missing or invalid 'code'.");
        }

        String identityProviderAlias = firstNonBlank(req.identityProviderAlias, defaultIdentityProviderAlias);
        String clientId = firstNonBlank(req.clientId, defaultClientId);
        if (isBlank(identityProviderAlias) || isBlank(clientId)) {
            return errorResponse(Response.Status.BAD_REQUEST, "invalid_request",
                    "No identityProviderAlias/clientId given and no default configured.");
        }

        RealmModel realm = session.getContext().getRealm();

        IdentityProviderModel idpModel = realm.getIdentityProviderByAlias(identityProviderAlias);
        if (idpModel == null || !"discord".equals(idpModel.getProviderId())) {
            return errorResponse(Response.Status.BAD_REQUEST, "invalid_request",
                    "No discord identity provider configured with alias '" + identityProviderAlias + "'.");
        }

        ClientModel client = realm.getClientByClientId(clientId);
        if (client == null || !client.isEnabled()) {
            return errorResponse(Response.Status.BAD_REQUEST, "invalid_request", "Unknown or disabled client '" + clientId + "'.");
        }
        // This endpoint exists specifically to mint tokens for a Discord
        // Activity client -- which, being embedded-app code with no way to
        // hold a secret, is necessarily a public client. Enforcing that
        // here keeps this a purpose-built exchange, not a general "mint a
        // token for any client" primitive.
        if (!client.isPublicClient()) {
            return errorResponse(Response.Status.BAD_REQUEST, "invalid_request", "Client '" + clientId + "' is not a public client.");
        }

        DiscordIdentityProviderConfig idpConfig = new DiscordIdentityProviderConfig(idpModel);
        DiscordIdentityProvider provider = new DiscordIdentityProvider(session, idpConfig);

        String tokenResponse;
        try (SimpleHttp.Response discordResponse = buildTokenRequest(idpConfig, req.code).asResponse()) {
            int status = discordResponse.getStatus();
            tokenResponse = discordResponse.asString();
            if (status < 200 || status >= 300) {
                log.warnf("discord-exchange: token endpoint rejected code, status=%s", status);
                return errorResponse(Response.Status.BAD_REQUEST, "invalid_grant", "Discord rejected the authorization code.");
            }
        } catch (Exception e) {
            log.warn("discord-exchange: token request to Discord failed", e);
            return errorResponse(Response.Status.BAD_GATEWAY, "server_error", "Could not reach Discord's token endpoint.");
        }

        BrokeredIdentityContext identity;
        try {
            // Reuses DiscordIdentityProvider's existing response parsing --
            // extracts access_token, then its overridden
            // doGetFederatedIdentity() calls Discord's /users/@me and
            // builds the BrokeredIdentityContext from the real profile.
            identity = provider.getFederatedIdentity(tokenResponse);
        } catch (Exception e) {
            log.warn("discord-exchange: failed to resolve Discord identity from token response", e);
            return errorResponse(Response.Status.BAD_GATEWAY, "server_error", "Could not resolve the Discord identity.");
        }

        // getFederatedIdentity() above only extracts the access_token to
        // resolve the profile -- it does NOT populate
        // BrokeredIdentityContext.getToken() (that only happens in
        // AbstractOAuth2IdentityProvider's browser-broker callback handler,
        // which this endpoint never calls). Parse Discord's raw token
        // response ourselves so the client gets a real access_token for its
        // own discordSdk.commands.authenticate() call.
        String discordAccessToken;
        try {
            discordAccessToken = new ObjectMapper().readTree(tokenResponse).path("access_token").asText(null);
        } catch (Exception e) {
            discordAccessToken = null;
        }
        if (isBlank(discordAccessToken)) {
            log.warn("discord-exchange: Discord token response had no access_token field");
            return errorResponse(Response.Status.BAD_GATEWAY, "server_error", "Discord did not return an access token.");
        }

        String discordUserId = identity.getId();
        if (isBlank(discordUserId)) {
            return errorResponse(Response.Status.BAD_GATEWAY, "server_error", "Discord did not return a user id.");
        }

        UserModel user = findOrCreateFederatedUser(realm, identityProviderAlias, discordUserId, identity.getUsername());

        // Outside the normal request pipeline (AuthenticationProcessor,
        // TokenEndpoint), nothing else sets this -- but TokenManager's
        // protocol-mapper chain reads it (e.g. resolving client attributes
        // for mappers like discord-id) and NPEs on a null client.
        session.getContext().setClient(client);

        EventBuilder event = new EventBuilder(realm, session, session.getContext().getConnection());
        event.event(EventType.LOGIN);
        event.client(client);

        UserSessionModel userSession = session.sessions().createUserSession(
                realm, user, user.getUsername(), clientIp, "discord-exchange", false, null, discordUserId);
        AuthenticatedClientSessionModel clientSession = session.sessions().createClientSession(realm, client, userSession);
        clientSession.setProtocol(OIDCLoginProtocol.LOGIN_PROTOCOL);
        clientSession.setNote(org.keycloak.OAuth2Constants.SCOPE, "openid");
        // TokenManager.initToken() reads the token's "iss" claim from this
        // note, not from the request URI directly -- normally set by
        // AuthenticationProcessor, which this endpoint bypasses. Without it
        // the minted token's issuer is null and Jackson omits "iss" from
        // the JSON entirely (confirmed live: relay rejected the token with
        // "missing field `iss`").
        clientSession.setNote(OIDCLoginProtocol.ISSUER,
                Urls.realmIssuer(session.getContext().getUri().getBaseUri(), realm.getName()));

        ClientSessionContext clientSessionCtx = DefaultClientSessionContext.fromClientSessionScopeParameter(clientSession, session);

        TokenManager tokenManager = new TokenManager();
        AccessTokenResponse tokens = tokenManager
                .responseBuilder(realm, client, event, session, userSession, clientSessionCtx)
                .generateAccessToken()
                .generateRefreshToken()
                .build();

        event.success();

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("access_token", tokens.getToken());
        body.put("refresh_token", tokens.getRefreshToken());
        body.put("expires_in", tokens.getExpiresIn());
        body.put("discord_access_token", discordAccessToken);
        return Response.ok(body, MediaType.APPLICATION_JSON_TYPE).build();
    }

    /**
     * Builds the Discord token-exchange request directly rather than via
     * {@code AbstractOAuth2IdentityProvider}'s inherited
     * {@code generateTokenRequest()} -- that method always appends a
     * {@code redirect_uri} pointing at Keycloak's own broker callback,
     * which does not match the code Discord issued for an
     * {@code authorize()} SDK call (Discord's own docs: no
     * {@code redirect_uri} parameter at all for this flow), and would fail
     * with {@code redirect_uri_mismatch}.
     */
    private SimpleHttp buildTokenRequest(DiscordIdentityProviderConfig idpConfig, String code) {
        return SimpleHttp.doPost(DiscordIdentityProvider.TOKEN_URL, session)
                .param("client_id", idpConfig.getClientId())
                .param("client_secret", idpConfig.getClientSecret())
                .param("grant_type", "authorization_code")
                .param("code", code);
    }

    private UserModel findOrCreateFederatedUser(RealmModel realm, String identityProviderAlias, String discordUserId, String discordUsername) {
        FederatedIdentityModel link = new FederatedIdentityModel(identityProviderAlias, discordUserId, discordUsername);
        UserModel user = session.users().getUserByFederatedIdentity(realm, link);
        if (user == null) {
            user = session.users().addUser(realm, preferredUsername(realm, discordUserId, discordUsername));
            user.setEnabled(true);
            session.users().addFederatedIdentity(realm, user, link);
        } else {
            selfHealPlaceholderUsername(realm, user, discordUserId, discordUsername);
        }
        // Keycloak has no built-in mapper that reads a federated identity's
        // external id directly into a token claim -- stored as a plain user
        // attribute instead so deployments can expose it with the standard,
        // built-in oidc-usermodel-attribute-mapper (no custom mapper code
        // needed). Set on every exchange, not just creation, so it stays
        // correct even for a user whose federated link predates this field.
        user.setSingleAttribute("discord_id", discordUserId);
        return user;
    }

    /**
     * The Discord handle is the natural Keycloak username: globally unique
     * on Discord's side, and exactly what downstream consumers of the
     * {@code preferred_username} claim want to display. The old
     * snowflake-derived {@code discord_<id>} name survives only as the
     * fallback for a blank handle or a username collision (a different
     * account -- e.g. one created by the browser broker before this
     * federated link existed -- already holding the name).
     */
    private String preferredUsername(RealmModel realm, String discordUserId, String discordUsername) {
        if (isBlank(discordUsername)) {
            return "discord_" + discordUserId;
        }
        if (session.users().getUserByUsername(realm, discordUsername) != null) {
            log.warnf("discord-exchange: username '%s' already taken, creating user under snowflake name instead", discordUsername);
            return "discord_" + discordUserId;
        }
        return discordUsername;
    }

    /**
     * Users created by earlier versions of this endpoint were named
     * {@code discord_<snowflake>} even though the real handle was already
     * known -- rename them to the handle on their next exchange. Safe for
     * every downstream consumer: tokens identify the account by {@code sub}
     * (the Keycloak user id), which a username rename never changes.
     */
    private void selfHealPlaceholderUsername(RealmModel realm, UserModel user, String discordUserId, String discordUsername) {
        if (isBlank(discordUsername)) {
            return;
        }
        if (!("discord_" + discordUserId).equals(user.getUsername())) {
            return;
        }
        if (session.users().getUserByUsername(realm, discordUsername) != null) {
            return;
        }
        log.infof("discord-exchange: renaming placeholder user '%s' to Discord handle '%s'", user.getUsername(), discordUsername);
        user.setUsername(discordUsername);
    }

    private boolean checkRateLimit(String clientIp) {
        long now = System.currentTimeMillis();
        RequestWindow window = RATE_LIMIT.computeIfAbsent(clientIp, ip -> new RequestWindow(now));
        synchronized (window) {
            if (now - window.windowStart > RATE_LIMIT_WINDOW_MS) {
                window.windowStart = now;
                window.count.set(0);
            }
            return window.count.incrementAndGet() <= RATE_LIMIT_MAX_REQUESTS;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String firstNonBlank(String a, String b) {
        return !isBlank(a) ? a : b;
    }

    private Response errorResponse(Response.Status status, String error, String description) {
        // Fixed, generic messages only -- never echo back internal
        // exception detail or raw upstream responses to the caller.
        Map<String, String> body = Map.of("error", error, "error_description", description);
        return Response.status(status).entity(body).type(MediaType.APPLICATION_JSON_TYPE).build();
    }

    private static final class RequestWindow {
        volatile long windowStart;
        final AtomicInteger count = new AtomicInteger(0);

        RequestWindow(long windowStart) {
            this.windowStart = windowStart;
        }
    }

    public static final class ExchangeRequest {
        @JsonProperty("code")
        public String code;
        @JsonProperty("identityProviderAlias")
        public String identityProviderAlias;
        @JsonProperty("clientId")
        public String clientId;
    }
}
