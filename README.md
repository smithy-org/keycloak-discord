# keycloak-discord

Keycloak Social Login extension for Discord.


## Install

Download `keycloak-discord-<version>.jar` from [Releases page](https://github.com/wadahiro/keycloak-discord/releases).
Then deploy it into `$KEYCLOAK_HOME/providers` directory.

## Setup

### Discord

Access to [Discord Developer Portal](https://discord.com/developers/applications) and create your application.
You can get Client ID and Client Secret from the created application.

### Keycloak

Note: You don't need to setup the theme in `master` realm from v0.3.0.

1. Add `discord` Identity Provider in the realm which you want to configure.
2. In the `discord` identity provider page, set `Client Id` and `Client Secret`.
3. (Optional) Set Guild Id(s) to allow federation if you want.


## Source Build

Clone this repository and run `mvn package`.
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
  "identityProviderAlias": "discord",
  "clientId": "your-public-client-id"
}
```

`identityProviderAlias` and `clientId` fall back to this provider's own
configured defaults if omitted (`identityProviderAlias` /
`clientId` config, e.g. via
`SPI_REALM_RESTAPI_EXTENSION_DISCORD_EXCHANGE_IDENTITY_PROVIDER_ALIAS` /
`SPI_REALM_RESTAPI_EXTENSION_DISCORD_EXCHANGE_CLIENT_ID` environment
variables, or the equivalent `spi-realm-restapi-extension-discord-exchange-*`
Keycloak config options) -- set one or both if every caller in your
deployment always targets the same identity provider/client, so callers only
need to send `code`.

The named client **must be a public client** (no client secret) -- this
endpoint is purpose-built for embedded-app clients, which can never hold a
secret, not a general "mint a token for any client" exchange.

**Response:**

```json
{
  "access_token": "...",
  "refresh_token": "...",
  "expires_in": 900,
  "discord_access_token": "..."
}
```

`discord_access_token` is included alongside the Keycloak tokens because
Discord's own Embedded App SDK expects it for a following
`discordSdk.commands.authenticate({ access_token })` call, if your app uses
any Discord SDK commands that require the session to be marked
authenticated.

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

