## Unreleased

### Hardening

* `maxSessionsPerUser` exchange setting (`KC_SPI_REALM_RESTAPI_EXTENSION_DISCORD_EXCHANGE_MAX_SESSIONS_PER_USER`; default `3`, `0` disables): after each exchange the user's oldest sessions in the realm are removed until the cap holds, never the one just created, so a client that re-runs the exchange whenever its refresh token is refused cannot pile up sessions that each live out the realm's SSO maximum. Deliberately not "revoke all other sessions", which would log a user with the app open on two devices out of each one in turn

### Build

* `pull_request.yml` builds with `mvn -B -C` (`--strict-checksums`): a dependency whose checksum does not match the repository's fails the build instead of warning

# [0.7.0](https://github.com/smithy-org/keycloak-discord/compare/v0.6.1...v0.7.0) (2026-10-07)

This fork's releases start here; built against Keycloak 26.7.5 (needs 26.4 or newer).

### Features

* redirect-free `POST /realms/{realm}/discord-exchange/token` for embedded apps (Discord Activities), minting a Keycloak token pair from an `authorize()` code
* the exchange returns Keycloak's full token response (`refresh_expires_in`, `token_type`, `session_state`, `scope`) plus `discord_access_token`
* every exchange and refusal is a Keycloak `LOGIN` / `LOGIN_ERROR` event with the broker's details (`identity_provider`, `identity_provider_identity`, `identity_provider_user_id`, `username`, `reason`); the user session carries the broker's `identity_provider` notes
* optional PKCE `codeVerifier`, forwarded to Discord as `code_verifier`
* `discord_id` user attribute set on every exchange; exchange users are named after their Discord handle
* `apiBaseUrl` identity-provider property (default `https://discord.com/api`): the token exchange, profile and guild requests -- the exchange endpoint's included -- are resolved against it, so a development realm can point them at a local stand-in for Discord; the browser authorization URL stays Discord's. The `TOKEN_URL`, `PROFILE_URL` and `GROUP_URL` constants on `DiscordIdentityProvider` are gone in favour of `DiscordIdentityProviderConfig.discordTokenUrl()` / `discordProfileUrl()` / `discordGuildsUrl()`

### Hardening

* a configured default client/identity provider is authoritative; a request naming another is refused
* disabled realm, identity provider or user is refused before any token is minted
* the per-address rate limit sweeps stale windows
* Keycloak 26.4+ APIs (`org.keycloak.http.simple.SimpleHttp`, `session.identityProviders()`, the 10-argument `createUserSession`)

### Build

* upstream's semantic-release workflow (`release.yml`, `.releaserc`) removed: releases are tagged by hand and this file is written by hand; `pull_request.yml` is the only CI

## [0.6.1](https://github.com/wadahiro/keycloak-discord/compare/v0.6.0...v0.6.1) (2024-11-02)


### Bug Fixes

* eliminate the possibility of NullPointerException ([#56](https://github.com/wadahiro/keycloak-discord/issues/56)) ([e2b5991](https://github.com/wadahiro/keycloak-discord/commit/e2b5991c7ef33ee3ac6483ca00e3e854bb935371))

# [0.6.0](https://github.com/wadahiro/keycloak-discord/compare/v0.5.0...v0.6.0) (2024-11-02)


### Bug Fixes

* ignore discriminator if the value is "0" ([4c68b69](https://github.com/wadahiro/keycloak-discord/commit/4c68b69b0bf0d6421589e03e4baf8bbbe5138caa))


### Features

* update to keycloak 26.0.5 ([05a3df4](https://github.com/wadahiro/keycloak-discord/commit/05a3df43f21289762f72e3ac6780fd6b543d8c07))
* updated provider for Keycloak 25.x ([#49](https://github.com/wadahiro/keycloak-discord/issues/49)) ([c10480b](https://github.com/wadahiro/keycloak-discord/commit/c10480b79864a85817d20d48f949475020322090))
