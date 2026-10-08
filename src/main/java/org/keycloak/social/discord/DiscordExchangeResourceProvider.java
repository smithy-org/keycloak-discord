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
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.events.EventBuilder;
import org.keycloak.events.EventType;
import org.keycloak.http.simple.SimpleHttp;
import org.keycloak.http.simple.SimpleHttpRequest;
import org.keycloak.http.simple.SimpleHttpResponse;
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

import java.util.Iterator;
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
 * <p>
 * Every outcome is recorded as a Keycloak {@code LOGIN} / {@code LOGIN_ERROR}
 * event with the same details the broker flow records, so a deployment's
 * event listeners (the default {@code jboss-logging} one logs errors at WARN)
 * see this endpoint exactly as they see a browser login.
 */
public class DiscordExchangeResourceProvider implements RealmResourceProvider {

    private static final Logger log = Logger.getLogger(DiscordExchangeResourceProvider.class);

    /** Value of the {@code auth_method} event detail and the user session's auth method. */
    static final String AUTH_METHOD = "discord-exchange";

    private static final int MAX_CODE_LENGTH = 512;
    /** RFC 7636 s4.1: a code verifier is 43 to 128 characters. */
    private static final int MIN_CODE_VERIFIER_LENGTH = 43;
    private static final int MAX_CODE_VERIFIER_LENGTH = 128;

    /**
     * Single-node in-memory rate limit: this provider instance is created
     * per request by Keycloak, so the counters live on the factory-shared
     * static map, keyed by source IP. A fixed window is enough to blunt
     * abuse of an endpoint that triggers two outbound Discord API calls per
     * request; it is not a substitute for edge-level rate limiting in a
     * multi-node deployment. Stale windows are swept every
     * {@link #RATE_LIMIT_SWEEP_EVERY} calls so the map cannot grow without
     * bound over a long uptime.
     */
    private static final Map<String, RequestWindow> RATE_LIMIT = new ConcurrentHashMap<>();
    private static final AtomicInteger RATE_LIMIT_CALLS = new AtomicInteger();
    private static final int RATE_LIMIT_MAX_REQUESTS = 10;
    private static final long RATE_LIMIT_WINDOW_MS = 60_000;
    private static final int RATE_LIMIT_SWEEP_EVERY = 256;
    private static final int RATE_LIMIT_MAX_ENTRIES = 50_000;

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
        RealmModel realm = session.getContext().getRealm();
        String clientIp = session.getContext().getConnection().getRemoteAddr();
        EventBuilder event = new EventBuilder(realm, session, session.getContext().getConnection())
                .event(EventType.LOGIN)
                .detail(Details.AUTH_METHOD, AUTH_METHOD);

        if (!checkRateLimit(clientIp)) {
            log.warnf("discord-exchange rate limit exceeded for %s", clientIp);
            return refuse(event, Errors.INVALID_REQUEST, "rate_limited",
                    Response.Status.TOO_MANY_REQUESTS, "rate_limited", "Too many requests.");
        }

        if (!realm.isEnabled()) {
            return refuse(event, Errors.REALM_DISABLED, null,
                    Response.Status.BAD_REQUEST, "invalid_request", "Realm is disabled.");
        }

        if (req == null || isBlank(req.code) || req.code.length() > MAX_CODE_LENGTH) {
            return refuse(event, Errors.INVALID_REQUEST, "missing_code",
                    Response.Status.BAD_REQUEST, "invalid_request", "Missing or invalid 'code'.");
        }
        if (req.codeVerifier != null
                && (req.codeVerifier.length() < MIN_CODE_VERIFIER_LENGTH
                || req.codeVerifier.length() > MAX_CODE_VERIFIER_LENGTH)) {
            return refuse(event, Errors.INVALID_CODE_VERIFIER, null,
                    Response.Status.BAD_REQUEST, "invalid_request", "Invalid 'codeVerifier' length.");
        }

        // A configured default is authoritative: this endpoint mints tokens
        // for one deployment's embedded-app client against one Discord
        // identity provider, so a request naming a different client (any
        // other public client in the realm) or provider is refused rather
        // than honoured. The request fields remain for deployments that
        // configure no default at all.
        String identityProviderAlias = resolveConfigured(defaultIdentityProviderAlias, req.identityProviderAlias);
        String clientId = resolveConfigured(defaultClientId, req.clientId);
        if (identityProviderAlias == null || clientId == null) {
            return refuse(event, Errors.INVALID_REQUEST, "client_or_provider_mismatch",
                    Response.Status.BAD_REQUEST, "invalid_request",
                    "identityProviderAlias/clientId must match the configured defaults, or be given when none is configured.");
        }
        event.detail(Details.IDENTITY_PROVIDER, identityProviderAlias);

        IdentityProviderModel idpModel = session.identityProviders().getByAlias(identityProviderAlias);
        if (idpModel == null || !DiscordIdentityProviderFactory.PROVIDER_ID.equals(idpModel.getProviderId())) {
            return refuse(event, Errors.IDENTITY_PROVIDER_ERROR, "no_discord_provider",
                    Response.Status.BAD_REQUEST, "invalid_request",
                    "No discord identity provider configured with alias '" + identityProviderAlias + "'.");
        }
        if (!idpModel.isEnabled()) {
            return refuse(event, Errors.IDENTITY_PROVIDER_ERROR, "identity_provider_disabled",
                    Response.Status.BAD_REQUEST, "invalid_request",
                    "Identity provider '" + identityProviderAlias + "' is disabled.");
        }

        ClientModel client = realm.getClientByClientId(clientId);
        if (client == null) {
            event.client(clientId);
            return refuse(event, Errors.CLIENT_NOT_FOUND, null,
                    Response.Status.BAD_REQUEST, "invalid_request", "Unknown client '" + clientId + "'.");
        }
        event.client(client);
        if (!client.isEnabled()) {
            return refuse(event, Errors.CLIENT_DISABLED, null,
                    Response.Status.BAD_REQUEST, "invalid_request", "Client '" + clientId + "' is disabled.");
        }
        // This endpoint exists specifically to mint tokens for a Discord
        // Activity client -- which, being embedded-app code with no way to
        // hold a secret, is necessarily a public client. Enforcing that
        // here keeps this a purpose-built exchange, not a general "mint a
        // token for any client" primitive.
        if (!client.isPublicClient()) {
            return refuse(event, Errors.INVALID_CLIENT, "confidential_client",
                    Response.Status.BAD_REQUEST, "invalid_request", "Client '" + clientId + "' is not a public client.");
        }

        DiscordIdentityProviderConfig idpConfig = new DiscordIdentityProviderConfig(idpModel);
        DiscordIdentityProvider provider = new DiscordIdentityProvider(session, idpConfig);

        String tokenResponse;
        try (SimpleHttpResponse discordResponse = buildTokenRequest(idpConfig, req.code, req.codeVerifier).asResponse()) {
            int status = discordResponse.getStatus();
            tokenResponse = discordResponse.asString();
            if (status < 200 || status >= 300) {
                log.warnf("discord-exchange: token endpoint rejected code, status=%s", status);
                return refuse(event, Errors.INVALID_CODE, "discord_status_" + status,
                        Response.Status.BAD_REQUEST, "invalid_grant", "Discord rejected the authorization code.");
            }
        } catch (Exception e) {
            log.warn("discord-exchange: token request to Discord failed", e);
            return refuse(event, Errors.IDENTITY_PROVIDER_ERROR, "discord_token_endpoint_unreachable",
                    Response.Status.BAD_GATEWAY, "server_error", "Could not reach Discord's token endpoint.");
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
            return refuse(event, Errors.IDENTITY_PROVIDER_ERROR, "discord_profile_unavailable",
                    Response.Status.BAD_GATEWAY, "server_error", "Could not resolve the Discord identity.");
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
            return refuse(event, Errors.IDENTITY_PROVIDER_ERROR, "discord_no_access_token",
                    Response.Status.BAD_GATEWAY, "server_error", "Discord did not return an access token.");
        }

        String discordUserId = identity.getId();
        if (isBlank(discordUserId)) {
            return refuse(event, Errors.IDENTITY_PROVIDER_ERROR, "discord_no_user_id",
                    Response.Status.BAD_GATEWAY, "server_error", "Discord did not return a user id.");
        }
        event.detail(Details.IDENTITY_PROVIDER_USER_ID, discordUserId);
        if (!isBlank(identity.getUsername())) {
            event.detail(Details.IDENTITY_PROVIDER_USERNAME, identity.getUsername());
        }

        UserModel user = findOrCreateFederatedUser(realm, identityProviderAlias, discordUserId, identity.getUsername());
        event.user(user).detail(Details.USERNAME, user.getUsername());
        if (!user.isEnabled()) {
            return refuse(event, Errors.USER_DISABLED, null,
                    Response.Status.BAD_REQUEST, "invalid_grant", "Account disabled.");
        }

        // Outside the normal request pipeline (AuthenticationProcessor,
        // TokenEndpoint), nothing else sets this -- but TokenManager's
        // protocol-mapper chain reads it (e.g. resolving client attributes
        // for mappers like discord-id) and NPEs on a null client.
        session.getContext().setClient(client);

        // Persistent, as every token-bearing session must be: a token pair is
        // only refreshable while its session is stored.
        UserSessionModel userSession = session.sessions().createUserSession(
                null, realm, user, user.getUsername(), clientIp, AUTH_METHOD, false, null, discordUserId,
                UserSessionModel.SessionPersistenceState.PERSISTENT);
        // The same user-session notes Keycloak's broker login records, so
        // anything that reads them (protocol mappers, the account console's
        // session list, event details) sees this session as a Discord login.
        userSession.setNote(Details.IDENTITY_PROVIDER, identityProviderAlias);
        if (!isBlank(identity.getUsername())) {
            userSession.setNote(Details.IDENTITY_PROVIDER_USERNAME, identity.getUsername());
        }
        event.session(userSession);

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

        // The standard token response (access_token, expires_in,
        // refresh_token, refresh_expires_in, token_type, session_state,
        // scope, ...), so a client can tell how long its refresh token lives,
        // plus Discord's own access token for the Embedded App SDK's
        // authenticate() call.
        tokens.setOtherClaims("discord_access_token", discordAccessToken);
        return Response.ok(tokens, MediaType.APPLICATION_JSON_TYPE).build();
    }

    /**
     * Builds the Discord token-exchange request directly rather than via
     * {@code AbstractOAuth2IdentityProvider}'s inherited
     * {@code generateTokenRequest()} -- that method always appends a
     * {@code redirect_uri} pointing at Keycloak's own broker callback,
     * which does not match the code Discord issued for an
     * {@code authorize()} SDK call (Discord's own docs: no
     * {@code redirect_uri} parameter at all for this flow), and would fail
     * with {@code redirect_uri_mismatch}. A PKCE {@code code_verifier} is
     * forwarded when the caller sent one with its {@code code_challenge}.
     */
    private SimpleHttpRequest buildTokenRequest(DiscordIdentityProviderConfig idpConfig, String code, String codeVerifier) {
        SimpleHttpRequest request = SimpleHttp.create(session).doPost(DiscordIdentityProvider.TOKEN_URL)
                .param("client_id", idpConfig.getClientId())
                .param("client_secret", idpConfig.getClientSecret())
                .param("grant_type", "authorization_code")
                .param("code", code);
        if (!isBlank(codeVerifier)) {
            request.param("code_verifier", codeVerifier);
        }
        return request;
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

    /**
     * A configured default wins; a request value is accepted only when it
     * equals the default or no default is configured. Returns null when the
     * two disagree or neither is set.
     */
    static String resolveConfigured(String configured, String requested) {
        if (isBlank(configured)) {
            return isBlank(requested) ? null : requested;
        }
        if (!isBlank(requested) && !configured.equals(requested)) {
            return null;
        }
        return configured;
    }

    static boolean checkRateLimit(String clientIp) {
        long now = System.currentTimeMillis();
        if (RATE_LIMIT_CALLS.incrementAndGet() % RATE_LIMIT_SWEEP_EVERY == 0) {
            sweepRateLimit(now);
        }
        RequestWindow window = RATE_LIMIT.computeIfAbsent(clientIp, ip -> new RequestWindow(now));
        synchronized (window) {
            if (now - window.windowStart > RATE_LIMIT_WINDOW_MS) {
                window.windowStart = now;
                window.count.set(0);
            }
            return window.count.incrementAndGet() <= RATE_LIMIT_MAX_REQUESTS;
        }
    }

    /** Drops windows that closed more than a window ago; a map that has still
     *  grown past the cap (an address flood) is cleared outright, which only
     *  ever grants a few extra requests. */
    static void sweepRateLimit(long now) {
        if (RATE_LIMIT.size() > RATE_LIMIT_MAX_ENTRIES) {
            RATE_LIMIT.clear();
            return;
        }
        Iterator<Map.Entry<String, RequestWindow>> it = RATE_LIMIT.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().windowStart > 2 * RATE_LIMIT_WINDOW_MS) {
                it.remove();
            }
        }
    }

    /** Test seam. */
    static int rateLimitEntries() {
        return RATE_LIMIT.size();
    }

    /** Test seam. */
    static void resetRateLimit() {
        RATE_LIMIT.clear();
        RATE_LIMIT_CALLS.set(0);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Records the failure as a {@code LOGIN_ERROR} event (with its reason)
     *  and returns the fixed, generic OAuth error body. */
    private Response refuse(EventBuilder event, String keycloakError, String reason,
                            Response.Status status, String oauthError, String description) {
        if (reason != null) {
            event.detail(Details.REASON, reason);
        }
        event.error(keycloakError);
        return errorResponse(status, oauthError, description);
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
        /** PKCE verifier matching the {@code code_challenge} the caller gave
         *  {@code authorize()}; optional, forwarded to Discord as
         *  {@code code_verifier}. */
        @JsonProperty("codeVerifier")
        public String codeVerifier;
        @JsonProperty("identityProviderAlias")
        public String identityProviderAlias;
        @JsonProperty("clientId")
        public String clientId;
    }
}
