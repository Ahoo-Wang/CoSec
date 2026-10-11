# Authentication

CoSec verifies a JWT on every request and turns it into the principal that policies evaluate. Issuing
tokens is up to your login endpoint; CoSec provides the building blocks.

## Tokens

A login produces a **composite token**: a short-lived access token and a longer-lived refresh token
(lifetimes: [`cosec.jwt.token-validity`](./configuration.md#jwt)). Tokens are HMAC-signed with
`cosec.jwt.secret`.

The access token carries the principal:

| Claim | Content |
|---|---|
| `jti` | token id (used for revocation) |
| `sub` | principal id |
| `roles` | role ids |
| `policies` | ids of the policies attached to the principal |
| `attributes` | free-form principal attributes, usable in conditions as `context.principal.attributes.<name>` |
| `tenantId` | the tenant, when the principal is tenant-scoped |

Because roles and policies travel in the token, changes to them apply from the next login or refresh.

**Sending the token.** Clients send the access token in the `Authorization` header (`Bearer` prefix
optional) or an `authorization` cookie. The `authorization` query parameter is accepted only with
`cosec.authorization.allow-query-token=true`.

A missing, expired or invalid token makes the caller anonymous, and policies decide what anonymous callers
may do. If the request is then denied, the response is `401` with reason `Token Expired` or `Token Invalid`
instead of the generic `Implicit Deny`.

## Logging in

Implement `Authentication<C, CoSecPrincipal>` for your credential type `C`. Every `Authentication` bean
is registered automatically, and `TokenCompositeAuthentication` dispatches by credential type:

```kotlin
tokenCompositeAuthentication.authenticateAsToken(credentials) // Mono<CompositeToken>
```

See the [Quick Start](./quick-start.md#_4-issue-tokens) for a complete example.

## Refreshing

`TokenVerifier.refresh(compositeToken)` accepts the expired access token together with its refresh token.
It checks that both are signed with your key, that the refresh token is still valid, and that the
refresh token belongs to that access token. It returns the principal from the access token; issue new
tokens for it with `TokenConverter.toToken(principal)`.

## Logout and revocation

JWTs are stateless: without extra work a token stays valid until it expires. To make logout immediate,
enable revocation (requires `cache-support`):

```yaml
cosec:
  jwt:
    token-revocation:
      enabled: true
```

Then call `TokenRevoker.revoke(accessToken)` from your logout endpoint. The token id is recorded in Redis
for the refresh-token lifetime. Every instance then treats the token as invalid, and refreshing it fails.

With revocation enabled, every request checks the store. The check is answered from a local cache when
possible, otherwise it reads Redis on a worker thread rather than the event loop. If Redis is unreachable,
the check **allows** the token rather than rejecting every authenticated request. Signature and expiry are
still enforced, so keep access tokens short-lived.

## Multi-tenancy

A principal may act within a tenant. When it does, the token carries `tenantId` and the security context
exposes that tenant to policies (`context.tenantId`, `inTenant`). `(0)` is the default tenant and
`(platform)` is the platform tenant. To let a user switch tenants, extend
`AbstractSwitchTenantAuthentication` and implement `switchTenant(targetTenantId, previousPrincipal)`; issue a
new token for the principal it returns.

## Social login

With `oauth-support`, `SocialAuthentication` signs users in through
[JustAuth](https://github.com/justauth/JustAuth) providers configured under
[`cosec.authentication.social.registration`](./configuration.md#social-login):

1. Redirect the user to `socialAuthentication.authorizeUrl("github")`.
2. In your callback, pass a `SocialCredentials` implementation (the provider plus the OAuth callback) to
   `TokenCompositeAuthentication.authenticateAsToken`.
3. A `SocialUserPrincipalConverter` bean maps the provider's user to your principal (look the user up,
   assign roles). The default uses the social user id directly.

## Behind a gateway

When a gateway such as `cosec-gateway-server` has already verified the token, downstream services can
skip verification and just read the principal from it:

```yaml
cosec:
  inject:
    enabled: true
```

::: danger Trust boundary
In this mode the token's **signature is not verified**, only its expiry. Enable it only for services that
are reachable exclusively through the verifying gateway. Otherwise anyone can forge a principal.
:::

Injected services can read the current security context in the same way; see
[Web Integration](./web-integration.md#accessing-the-security-context).
