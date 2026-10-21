---
title: "Authentication"
description: "Issue, refresh and revoke tokens, accept API keys, and log users in through social providers."
---

# Authentication

For developers building the login side of an application protected by CoSec. CoSec authorizes requests from the
principal in a token; this page shows how to issue those tokens, keep them fresh, revoke them, and authenticate
callers that do not log in interactively. Every property mentioned here, with its default, is listed in
[Configuration](../operations/configuration.md#jwt).

## Tokens

A login produces a **composite token**: a short-lived access token and a longer-lived refresh token, both JWTs
signed with `cosec.jwt.secret` (HMAC-SHA256 by default, or HMAC-SHA384/512). With JWT enabled, which is the
default, the application does not start until the secret is set.

The access token carries the principal, so authorizing a request needs no lookup:

| Claim | Content |
|---|---|
| `jti` | The token id, used for [revocation](#logout-and-revocation). |
| `sub` | The principal id. |
| `roles` | Role ids, written `role` or `role@space` ([Roles and spaces](#roles-and-spaces)). Left out when empty. |
| `policies` | Ids of the policies attached to the principal. Left out when empty. |
| `attributes` | Free-form principal attributes; conditions read them as `context.principal.attributes.<name>`. |
| `tenantId` | The tenant, when the principal has one ([Tenants](#tenants)). |
| `iat`, `exp` | Issued and expiry time. A token without `exp` is rejected. |

The refresh token carries only its own `jti`, `exp`, and, as `sub`, the `jti` of the access token it belongs to.

Token ids come from the application's `StringIdGenerator` bean if it has one (for example from CosId), otherwise
from `UuidGenerator`.

**Sending the token.** CoSec reads the access token from, in this order:

1. the `Authorization` header (the `Bearer ` prefix is optional);
2. the `authorization` query parameter, only with `cosec.authorization.allow-query-token=true`, because URLs leak
   into access logs and `Referer` headers;
3. the `authorization` cookie.

**A rejected token is not an error.** A missing, expired, badly signed or revoked token makes the caller anonymous,
and policies decide what anonymous callers may do: a public path still works. If the request is denied, the
response is `401` with the reason `Token Expired` or `Token Invalid` instead of the generic `Implicit Deny`
([Responses and status codes](./web.md#responses-and-status-codes)).

## Roles and spaces

A principal can hold different roles in different spaces of an app. Write each role in the token as:

- `role`: the role in the default space (a request without `CoSec-Space-Id`);
- `role@space`: the role in `space` only. A principal that is `admin` in two spaces carries `admin@team-a` and
  `admin@team-b`.

A role id must not otherwise contain `@`; a malformed role (empty role or space, more than one `@`) applies
nowhere.

What the suffix changes:

- **Role permissions** follow it strictly: a request with `CoSec-Space-Id: space` gets only the grants of the
  `…@space` roles, a request without the header only those of the unsuffixed roles
  ([Role grant scope](../policies/role-permissions.md#role-grant-scope), [Spaces](../policies/role-permissions.md#spaces)).
- **`inRole`** matches more broadly, so a deny on a bare `inRole` keeps working after tokens move to `role@space`,
  but no form of it limits an allow to the request's space. The exact rules and why are in
  [Role scope and inRole](../guide/security-model.md#role-scope-and-inrole); the condition itself is in
  [Matching roles](../policies/language.md#matching-roles).

The client chooses the space with a header, but only among the spaces the token states. The token is therefore
the only proof of membership: issue `role@space` only after checking that the user belongs to that space.

Roles and policies travel in the token, so a change to a user's roles takes effect when the user next logs in;
[refreshing](#refreshing-tokens) keeps the old claims.

## Logging in

CoSec does not ship a login endpoint. You check the credentials, CoSec turns the principal into tokens:

1. Implement `Authentication<C, CoSecPrincipal>` for your credential type `C`, and declare it as a bean. The
   starter registers every `Authentication` bean by the credential type it supports.
2. Call `TokenCompositeAuthentication.authenticateAsToken(credentials)` from your endpoint. It picks the
   `Authentication` for the credentials' type (or a supertype), and returns a `Mono<CompositeToken>` with the
   `accessToken` and `refreshToken`.

```kotlin
data class PasswordCredentials(val username: String, val password: String) : Credentials

@Component
class PasswordAuthentication(private val users: UserService) :
    Authentication<PasswordCredentials, CoSecPrincipal> {
    override val supportCredentials = PasswordCredentials::class.java

    override fun authenticate(credentials: PasswordCredentials): Mono<CoSecPrincipal> =
        users.verify(credentials.username, credentials.password)
            .map { user -> SimplePrincipal(id = user.id, roles = user.roles) }
}

@RestController
class LoginController(private val authentication: TokenCompositeAuthentication) {
    @PostMapping("/auth/login")
    fun login(@RequestBody credentials: PasswordCredentials): Mono<out CompositeToken> =
        authentication.authenticateAsToken(credentials)
}
```

The login path must be reachable by anonymous callers, so it needs a policy that allows it, as in the
[Quick Start](../guide/quick-start.md#write-a-first-policy). To issue tokens yourself, for example with a custom
lifetime, call `TokenConverter.toToken(principal)` or `toToken(principal, accessValidity, refreshValidity)`.

## Refreshing tokens

A client whose access token expired sends both tokens back. `TokenVerifier.refresh(compositeToken)` checks that:

- the refresh token is validly signed and not expired;
- the access token, which may be expired, is validly signed with the same key;
- the refresh token belongs to that access token (its `sub` is the access token's `jti`);
- with revocation enabled, the access token was not revoked.

It returns the principal from the old access token; issue new tokens for it with `TokenConverter.toToken`. The
starter registers no refresh endpoint. `SimpleRefreshTokenAuthentication` does the check above as an
`Authentication`, so with it declared as a bean, a refresh is a login with credentials that implement
`RefreshTokenCredentials`:

```kotlin
@Bean
fun refreshTokenAuthentication(tokenVerifier: TokenVerifier) = SimpleRefreshTokenAuthentication(tokenVerifier)

data class RefreshCredentials(override val accessToken: String, override val refreshToken: String) :
    RefreshTokenCredentials

@PostMapping("/auth/refresh")
fun refresh(@RequestBody credentials: RefreshCredentials): Mono<out CompositeToken> =
    authentication.authenticateAsToken(credentials)
```

Two things a refresh does not do:

- **It does not re-read the user.** The new token carries the claims of the old one. To pick up changed roles
  on refresh, write your own `Authentication` for refresh credentials that verifies them with
  `TokenVerifier.refresh` and then loads the user.
- **It does not invalidate the old refresh token.** A refresh token stays usable until it expires. To end a
  session, [revoke](#logout-and-revocation) its access token, which also blocks the refresh token bound to it.

## Logout and revocation

JWTs are stateless: without extra work a token stays valid until it expires. To make logout take effect at once,
enable revocation with `cosec.jwt.token-revocation.enabled=true`. It needs `cache-support`: without it the
starter logs a warning and logout does nothing.

From your logout endpoint, call `TokenRevoker.revoke(accessToken)`. It verifies the token, then records its `jti`
in Redis for the refresh token's lifetime. From then on every instance rejects the access token as
`Token Invalid`, and refreshing it fails. Revoke before the access token expires: an expired token cannot be
revoked, and its refresh token then stays usable.

With revocation enabled, every authenticated request checks the store, so the reactive adapters parse tokens off
the event loop. Two properties of the check matter:

- **"Not revoked" is cached locally for 30 seconds.** A revocation through `TokenRevoker` invalidates that answer on
  every instance at once. If the invalidation message is lost, or the revocation is written to Redis some other
  way, an instance may accept the token for up to 30 seconds more.
- **It allows the token when Redis is unreachable.** This is one of
  [the two documented exceptions](../guide/security-model.md#the-two-documented-exceptions) to fail closed: the signature and expiry are still checked, and rejecting
  every authenticated request during a Redis outage would be worse. Keep access tokens short-lived so this
  window stays small. A revocation entry that Redis returns but CoSec cannot decode still fails the request
  ([When Redis is unavailable](../operations/storage.md#when-redis-is-unavailable)).

## API keys

Machine clients (jobs, partner integrations, internal services) often cannot log in. Give them an API key: whoever
sends it in the `X-API-Key` header acts as the key's principal, with its roles, policies and tenant, and is
authorized like anyone else. API keys are off by default ([API key properties](../operations/configuration.md#api-keys)).

A request that carries a key is judged by the key alone, even if it also sends a token. An unknown, disabled or
expired key makes the request anonymous with the reason `Token Invalid`, so a denial answers `401`. The
principal gets the attribute `apiKeyId`, naming the key used. CoSec never stores or logs the key itself, only its
SHA-256 hash.

**In memory**, from configuration. Create a key with `ApiKeys.generate()`, give it to the client once, and
configure only `ApiKeys.hash(key)`:

```yaml
cosec:
  api-key:
    enabled: true
    keys:
      - id: billing-sync
        hash: <SHA-256 hex of the key, from ApiKeys.hash(key)>
        principal-id: svc-billing
        roles: [billing]
        expires-at: 2027-01-01T00:00:00Z
```

Each key needs an `id`, a 64-character lowercase hex `hash` and a `principal-id`, and ids must be unique; otherwise
the application does not start. Rotate a key by adding the new one, moving the client over, then removing the old
one.

**In Redis**, shared by every instance and changeable at runtime: set `cosec.api-key.store: redis` (needs
`cache-support`). Manage keys through the `RedisApiKeyStore` bean:

| Call | Effect |
|---|---|
| `issue(apiKey)` | Creates a key, stores only its hash, and returns the key. Show it once; it cannot be recovered. |
| `save(hash, apiKey)` | Replaces the key with that hash, for example to set `enabled = false` or change its roles. |
| `revoke(hash)` | Removes the key. It stops working on the next request. |

Every request with a key then reads Redis, off the event loop. If Redis cannot be read, the request fails with
`500`: unlike token revocation, an API key lookup fails closed.

**Elsewhere**: declare your own `ApiKeyStore` bean that looks keys up by hash. If the lookup blocks (a database
call), set `cosec.api-key.store-blocking=true` so the reactive adapters call it off the event loop.

## Tenants

A principal may act within a tenant. The principal your `Authentication` returns decides it: a `TenantPrincipal`
(such as `SimpleTenantPrincipal(principal, SimpleTenant(tenantId))`) puts its tenant in the token's `tenantId`
claim; a token without the claim belongs to the default tenant `(0)`. Policies read the tenant as
`context.tenantId` or with `inTenant`. What CoSec does and does not isolate between tenants is in
[Tenant isolation](../guide/security-model.md#tenant-isolation).

To let a user switch tenants, extend `AbstractSwitchTenantAuthentication`, implement
`switchTenant(targetTenantId, previousPrincipal)` (check that the user belongs to the target tenant), and issue a
new token for the principal it returns.

**The platform tenant `(platform)` is privileged.** The root principal, if enabled with
`cosec.authorization.root-enabled`, is recognized only in it: a principal is root only when its tenant
is `(platform)` and its id equals the `cosec.root` system property (`cosec` by default). A user tenant whose
identity provider issues the id `cosec` gets no root rights. Issue tokens for `(platform)` only from a source that
users cannot influence. Why, and what root bypasses, is in [Root principal](../guide/security-model.md#root-principal).

## Social login

With `oauth-support`, `SocialAuthentication` signs users in through [JustAuth](https://github.com/justauth/JustAuth)
providers. Configure each provider under `cosec.authentication.social.registration.<name>`, with its JustAuth
`type`, client id, secret and redirect URI ([Social login properties](../operations/configuration.md#social-login)).

1. Send the user to `socialAuthentication.authorizeUrl("<name>")`. The URL carries a fresh OAuth `state`, generated
   by the application's `StringIdGenerator` bean if it has one, otherwise by `UuidGenerator`. The state is kept in
   Redis for 3 minutes, so social login needs a `StringRedisTemplate` (or your own JustAuth `AuthStateCache` bean).
2. In your callback endpoint, bind the provider's callback parameters (`code`, `state`, ...) to
   `JustAuthCredentials("<name>")`, and pass it to `TokenCompositeAuthentication.authenticateAsToken`.
3. A `SocialUserPrincipalConverter` turns the provider's user into your principal. The default,
   `DirectSocialUserPrincipalConverter`, makes a principal with the id `<provider user id>@<name>`, the provider's
   raw user info as attributes, and **no roles, policies or tenant**. To look the user up in your own system and
   give them roles, declare your own `SocialUserPrincipalConverter` bean.

## Behind a gateway

When a gateway such as `cosec-gateway-server` has already authorized the request, the services behind it can read
the principal from the forwarded token without verifying it again:

```yaml
cosec:
  authorization:
    enabled: false   # the gateway authorized the request
  inject:
    enabled: true    # read the principal from the forwarded token
```

The service then registers `InjectSecurityContextFilter` (Spring MVC) or `ReactiveInjectSecurityContextWebFilter`
(WebFlux), which bind the [security context](./web.md#accessing-the-security-context) as usual. They check the
token's `exp` and `nbf`, but **not its signature and not revocation**. A token that cannot be read makes the
caller anonymous, and a path with traversal segments gets `400`. Both settings are needed: with authorization still
enabled, the service keeps verifying and authorizing every request itself.

::: danger Trust boundary
Without signature verification, anyone who can reach the service directly can forge any principal. Enable this
only for services that are reachable exclusively through the verifying gateway; see
[Trust boundaries](../guide/security-model.md#trust-boundaries).
:::
