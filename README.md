# keycloak-discord

Keycloak Social Login extension for Discord.


## Compatibility

This fork is built from source and tracks the Keycloak release it is deployed on. Releases are
tags cut by hand (`v0.7.0` is the first) and recorded in `CHANGELOG.md`; nothing is published to
a Maven repository, and the upstream project's jars do not contain the token-exchange endpoint
below. The Smithy Keycloak image builds the jar from a pinned commit of this repository.

| Fork | Built against | JDK | Status |
|---|---|---|---|
| `233abc5` and later (`v0.7.0` tags the first release) | Keycloak 26.7.5 | targets 17; the Smithy image compiles on Temurin 21 | Current. Uses `org.keycloak.http.simple.SimpleHttp`, `session.identityProviders()` and the 10-argument `createUserSession`, so it needs **Keycloak 26.4 or newer**. |
| Up to `c25d602` | Keycloak 26.0.8 | 17 | Compiles unchanged against 26.7.5, but uses APIs Keycloak has deprecated (one for removal). Needed only for Keycloak older than 26.4. |

The provider compiles against Keycloak's SPI, so build it against the exact Keycloak version that
runs it: `mvn package -Dversion.keycloak=<version>` overrides the pom. A Keycloak minor release can
change SPI signatures, so rebuild and smoke-test the exchange endpoint (below) on every Keycloak
upgrade rather than assuming the previous jar still works.

Keycloak 26.5 made every user session persistent. The exchange endpoint creates its session as
`PERSISTENT` explicitly, so the refresh token it returns is refreshable after a restart.

## Install

Build the jar (see Source Build) and copy it into the `$KEYCLOAK_HOME/providers` directory, then run
`kc.sh build` (or build the image) before `start --optimized`.

## Setup

### Discord

Access to [Discord Developer Portal](https://discord.com/developers/applications) and create your application.
You can get Client ID and Client Secret from the created application.

### Keycloak

Note: You don't need to setup the theme in `master` realm from v0.3.0.

1. Add `discord` Identity Provider in the realm which you want to configure.
2. In the `discord` identity provider page, set `Client Id` and `Client Secret`.
3. (Optional) Set Guild Id(s) to allow federation if you want.
4. Leave `Discord API base URL` (config key `apiBaseUrl`) empty or at its default,
   `https://discord.com/api`, in production. It is where the provider sends its server-to-server
   calls -- the code-for-token exchange (`<base>/oauth2/token`), the profile lookup
   (`<base>/users/@me`) and the guild list (`<base>/users/@me/guilds`) -- and exists so a
   development realm can point them at a local stand-in for Discord (see
   [Development against a stub](#development-against-a-stub)). The browser redirect to Discord's
   consent page always goes to the real Discord. A trailing slash is ignored.

### Development against a stub

The exchange endpoint below and the profile/guild lookups can only ever be exercised against a
Discord application in production unless something answers in Discord's place. Set `apiBaseUrl`
on a development realm's `discord` identity provider to a service that serves Discord's shapes
for `/api/oauth2/token`, `/api/users/@me` and `/api/users/@me/guilds` for a seeded set of users
(the Smithy app repository ships such a stub for its dev stack):

```sh
kcadm.sh update identity-provider/instances/discord -r <realm> \
  -s config.apiBaseUrl=http://discord-stub:8092/api
```

Every Discord API call this provider makes then goes to the stub, including the token exchange
that `POST /realms/{realm}/discord-exchange/token` performs, so the whole embedded-app login can
run on a laptop with no Discord credentials. Unset the property (or set it back to
`https://discord.com/api`) to talk to Discord again. Whoever can edit the identity provider can
redirect its client secret with this setting, exactly as they can with the token URL of
Keycloak's generic OpenID Connect provider -- it is a realm-administrator control, not a
user-facing one.


## Source Build

Clone this repository and run `mvn package` (JDK 17 or newer), adding `-Dversion.keycloak=<version>`
to target a Keycloak release other than the one in `pom.xml`. The only CI is
`.github/workflows/pull_request.yml`, which builds each pull request against the pom's Keycloak
version; there is no release automation.
You can see `keycloak-discord-<version>.jar` under `target` directory.


## Redirect-free token exchange (for embedded apps)

Keycloak's normal browser-redirect broker login depends on Keycloak's own
session cookie surviving a round trip through the external provider and
back. That's fine in a regular browser tab, but it cannot work for an app
embedded in a third-party iframe (a Discord Activity, a Slack app, a
Microsoft Teams tab, etc.) -- every modern browser now treats that cookie as
third-party and blocks it, regardless of how the redirect is structured.

This fork adds `POST /realms/{realm}/discord-exchange/token`, a
`RealmResourceProvider` alongside the standard `discord` identity provider
above (which is untouched, and still works for any non-embedded use). It
takes an authorization code obtained out-of-band -- e.g. from Discord's
Embedded App SDK, `discordSdk.commands.authorize()`, which returns a code
over `postMessage` with no navigation and no cookie involved -- and mints a
Keycloak token pair for it directly, with no redirect at all.

**Request:**

```json
POST /realms/{realm}/discord-exchange/token
Content-Type: application/json

{
  "code": "<authorization code from your embedded SDK's authorize call>",
  "codeVerifier": "<optional PKCE verifier for the code_challenge you sent authorize()>",
  "identityProviderAlias": "discord",
  "clientId": "your-public-client-id"
}
```

`identityProviderAlias` and `clientId` are normally configured on the
provider itself (`identityProviderAlias` / `clientId` config, e.g. via
`KC_SPI_REALM_RESTAPI_EXTENSION_DISCORD_EXCHANGE_IDENTITY_PROVIDER_ALIAS` /
`KC_SPI_REALM_RESTAPI_EXTENSION_DISCORD_EXCHANGE_CLIENT_ID` environment
variables, or the equivalent `spi-realm-restapi-extension-discord-exchange-*`
Keycloak config options), and callers then send only `code`. **A configured
default is authoritative**: a request that names a different client or
provider is refused with `invalid_request`, so the endpoint can never be
pointed at another public client in the realm. The request fields are only
honoured for a deployment that configures no default.

`codeVerifier` is forwarded to Discord as `code_verifier` when present
(43-128 characters, RFC 7636). Whether Discord's token endpoint honours PKCE
for the Embedded App SDK flow is not documented by Discord; send it only once
you have confirmed that against a real code.

The named client **must be a public client** (no client secret) -- this
endpoint is purpose-built for embedded-app clients, which can never hold a
secret, not a general "mint a token for any client" exchange.

**Response:** Keycloak's standard token response, plus one field.

```json
{
  "access_token": "...",
  "expires_in": 300,
  "refresh_token": "...",
  "refresh_expires_in": 3600,
  "token_type": "Bearer",
  "session_state": "...",
  "scope": "openid profile email",
  "not-before-policy": 0,
  "discord_access_token": "..."
}
```

`discord_access_token` is included alongside the Keycloak tokens because
Discord's own Embedded App SDK expects it for a following
`discordSdk.commands.authenticate({ access_token })` call, if your app uses
any Discord SDK commands that require the session to be marked
authenticated. `refresh_expires_in` tells the client how long its refresh
token lives, so it can tell a dead token from a Keycloak that is merely
unreachable.

**Errors** are the fixed OAuth shapes `{"error", "error_description"}`:
`invalid_request` (400) for a bad request or a mismatched client/provider,
`invalid_grant` (400) when Discord rejects the code or the account is
disabled, `server_error` (502) when Discord cannot be reached, and
`rate_limited` (429) past 10 exchanges per minute per source address (the
realm must trust the proxy headers for that address to be the real client).

**Events.** Every exchange is recorded as a Keycloak `LOGIN` event with
`auth_method=discord-exchange`, the client, user, session,
`identity_provider`, `identity_provider_identity` (the Discord handle) and
`identity_provider_user_id` (the snowflake); every refusal is a
`LOGIN_ERROR` with the standard Keycloak error code and a `reason` detail
(`rate_limited`, `discord_status_401`, `confidential_client`, ...). The
default `jboss-logging` listener prints the errors at WARN whether or not the
realm stores events, so a failing exchange is visible in Keycloak's log. The
user session carries the same `identity_provider` /
`identity_provider_identity` notes a browser broker login would.

The user's Discord snowflake ID is also stored as a plain user attribute,
`discord_id`, on every exchange (not just first creation). Keycloak has no
built-in mapper that reads a federated identity's external id directly into
a token claim, but a user attribute can be exposed as one with the standard,
built-in `oidc-usermodel-attribute-mapper` protocol mapper -- no custom
mapper code needed on your end.


## Licence

[Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0)


## Author

- [Hiroyuki Wada](https://github.com/wadahiro)

