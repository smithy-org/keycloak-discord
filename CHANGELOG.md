# [0.7.0](https://github.com/smithy-org/keycloak-discord/compare/v0.6.1...v0.7.0) (2026-10-07)

This fork's releases start here; built against Keycloak 26.7.5 (needs 26.4 or newer).

### Features

* redirect-free `POST /realms/{realm}/discord-exchange/token` for embedded apps (Discord Activities), minting a Keycloak token pair from an `authorize()` code
* the exchange returns Keycloak's full token response (`refresh_expires_in`, `token_type`, `session_state`, `scope`) plus `discord_access_token`
* every exchange and refusal is a Keycloak `LOGIN` / `LOGIN_ERROR` event with the broker's details (`identity_provider`, `identity_provider_identity`, `identity_provider_user_id`, `username`, `reason`); the user session carries the broker's `identity_provider` notes
* optional PKCE `codeVerifier`, forwarded to Discord as `code_verifier`
* `discord_id` user attribute set on every exchange; exchange users are named after their Discord handle

### Hardening

* a configured default client/identity provider is authoritative; a request naming another is refused
* disabled realm, identity provider or user is refused before any token is minted
* the per-address rate limit sweeps stale windows
* Keycloak 26.4+ APIs (`org.keycloak.http.simple.SimpleHttp`, `session.identityProviders()`, the 10-argument `createUserSession`)

## [0.6.1](https://github.com/wadahiro/keycloak-discord/compare/v0.6.0...v0.6.1) (2024-11-02)


### Bug Fixes

* eliminate the possibility of NullPointerException ([#56](https://github.com/wadahiro/keycloak-discord/issues/56)) ([e2b5991](https://github.com/wadahiro/keycloak-discord/commit/e2b5991c7ef33ee3ac6483ca00e3e854bb935371))

# [0.6.0](https://github.com/wadahiro/keycloak-discord/compare/v0.5.0...v0.6.0) (2024-11-02)


### Bug Fixes

* ignore discriminator if the value is "0" ([4c68b69](https://github.com/wadahiro/keycloak-discord/commit/4c68b69b0bf0d6421589e03e4baf8bbbe5138caa))


### Features

* update to keycloak 26.0.5 ([05a3df4](https://github.com/wadahiro/keycloak-discord/commit/05a3df43f21289762f72e3ac6780fd6b543d8c07))
* updated provider for Keycloak 25.x ([#49](https://github.com/wadahiro/keycloak-discord/issues/49)) ([c10480b](https://github.com/wadahiro/keycloak-discord/commit/c10480b79864a85817d20d48f949475020322090))
