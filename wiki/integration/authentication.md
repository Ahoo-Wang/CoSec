---
title: "Authentication"
description: "How CoSec reads credentials, and how to issue, refresh and revoke tokens, accept API keys, and log users in through social providers."
---

# Authentication

For developers building the login side of an application protected by CoSec. CoSec authorizes requests from the
principal a credential proves; this page shows how the pipeline reads credentials, how to issue tokens and keep
them fresh, how to revoke them, and how to authenticate callers that do not log in interactively. Every property
mentioned here, with its default, is listed in [Configuration](../operations/configuration.md).

## Tokens

A login produces a **composite token**: a short-lived access token and a longer-lived refresh token, both JWTs
signed with `cosec.jwt.secret` (HMAC-SHA256 by default, or HMAC-SHA384/512). With JWT enabled, which is the
default, the application does not start until the secret is set.

The access token carries the principal, so authenticating a request needs no lookup beyond the revocation check:

| Claim | Content |
|---|---|
| `jti` | The token id, used for [revocation](#logout-and-revocation). |
| `sub` | The principal id. |
| `roles` | Role ids, written `role` or `role@space` ([Roles and spaces](#roles-and-spaces)). Left out when empty. |
| `policies` | Ids of the policies attached to the principal. Left out when empty. |
| `attributes` | Free-form principal attributes; conditions read them as `subject.attributes.<name>`. |
| `tenantId` | The tenant, when the principal has one ([Tenants](#tenants)). |
| `cosec.membership` | `"provider"` in place of `roles` and `policies`, when they come from a `MembershipProvider` ([Memberships](#memberships)). Left out otherwise. |
| `iat`, `exp` | Issued and expiry time. A token without `exp` is rejected. `iat` decides which tokens [revoking a principal](#revoking-every-token-of-a-principal) rejects. |

The refresh token carries only its own `jti`, `iat`, `exp`, and, as `sub`, the `jti` of the access token it belongs
to. Token ids come from the application's `StringIdGenerator` bean if it has one (for example from CosId),
otherwise from `UuidGenerator`. The lifetimes are `cosec.jwt.token-validity.access` (10 minutes) and
`cosec.jwt.token-validity.refresh` (7 days).

**A rejected token is not an error.** An expired, badly signed or revoked token makes the caller anonymous, and the
policies decide what anonymous callers may do: a public path still works. If the request is denied, the response
is `401` with the problem type `token-expired`, `token-revoked` or `token-invalid` instead of `unauthorized`
([Responses and status codes](./web.md#responses-and-status-codes)).

**Delegation tokens are refused.** A token with an `act` claim (an [RFC 8693](https://www.rfc-editor.org/rfc/rfc8693)
delegation token: `sub` is the party acted for, `act` the one acting) is rejected as `token-invalid`, even when its
signature and expiry are valid (`IDN-4`). A deny is audited with the reason code `DELEGATION_UNSUPPORTED`; on a permit,
such as a path anonymous callers may use, the audit records `ALLOW`, and the reason appears only in the decision
event's `subject.rejected`. Read as its `sub`,
such a token would give the actor everything the subject may do, and 8.0 does not evaluate delegation. CoSec never
issues one. A `may_act` claim only says who may act for the subject, so it is ignored.

## Authenticators

The pipeline reads the request's credentials, then asks one `Authenticator` who the caller is. The starter's is a
`CompositeAuthenticator` of two:

- **`TokenAuthenticator`** verifies the token with the JWT algorithm, then asks the revocation reader whether the
  token, or every token of its principal, was revoked. Only `TokenVerifier.verify` (and `refresh`) turns a token into
  a principal: CoSec has no API that reads a token's claims without checking its signature. Without JWT
  (`cosec.jwt.enabled=false`), a request with a token is rejected as `token-invalid`.
- **`ApiKeyAuthenticator`**, only with `cosec.api-key.enabled=true`, looks up the [API key](#api-keys).

A request that carries an API key (and API keys are enabled) is judged by the key alone, even if it also sends a
token: a rejected key never falls back to the token. Any other request goes to the token authenticator; one with
no credential at all is anonymous.

**Where credentials are read**, in this order for the token:

1. the `Authorization` header (the `Bearer ` prefix is optional);
2. the `authorization` query parameter, only with `cosec.pipeline.allow-query-token=true`, because URLs leak into
   access logs and `Referer` headers;
3. the `authorization` cookie.

Blank values are skipped. The API key comes from the header `cosec.pipeline.api-key-header` (default `X-API-Key`)
only.

**Failures.** An authenticator that throws, reports a failed dependency (`AuthnResult.Unavailable`, such as an API
key store that cannot be read) or does not answer within `cosec.pipeline.authentication-timeout` (1 second) makes
the request `503`: an unreadable store never makes a credential valid or invalid.

**Your own.** Declare an `Authenticator` bean to replace the starter's. It is a `suspend` function that must not
block the calling thread, which may be an event loop; see [Extending CoSec](../architecture/extending.md).

## Roles and spaces

A principal can hold different roles in different spaces. Write each role in the token as:

- `role`: the role in the default space (a request without `CoSec-Space-Id`);
- `role@space`: the role in `space` only. A principal that is `admin` in two spaces carries `admin@team-a` and
  `admin@team-b`.

Strings are taken exactly, with no trimming or case folding. A malformed role (empty role or space, more than one
`@`, or `role@*`, since `*` means any space in documents) applies in no space.

What the space changes:

- **Role bindings** follow it strictly: a request with `CoSec-Space-Id: team-a` is bound only through the roles
  written `…@team-a`, a request without the header only through the roles without a suffix
  ([Role permissions](../policies/role-permissions.md)).
- **Conditions** can read the roles of the request's space (`scope.roles`) or of every space (`subject.roles`);
  which to use for what is in [Roles in conditions](../guide/security-model.md#roles-in-conditions).

The client chooses the space with `CoSec-Space-Id`, but only among the spaces the credential states: a space the
principal has no role in is rejected. The token is therefore the only proof of membership: issue `role@space` only
after checking that the user belongs to that space.

Roles and policies travel in the token, so a change to a user's roles takes effect when the user next logs in;
[refreshing](#refreshing-tokens) keeps the old claims. To take access away at once,
[revoke every token of the principal](#revoking-every-token-of-a-principal), or move the principal's memberships to
a provider.

## Memberships

CoSec reads the `roles` and `policies` claims as the principal's **memberships**: its roles in each space, and the
policies attached to it, in claim order. A token without a tenant belongs to the default tenant `(0)`.

**Large or fast-changing memberships.** A token that names many roles or policies grows with every request, and a
role removed from the user stays in the token until it expires. Such a token can carry `cosec.membership: "provider"`
instead of `roles` and `policies`: its memberships then come from the application's `MembershipProvider` bean,
asked with the principal's tenant and id on every request. `JwtTokenConverter` writes the marker itself once the two
claims would take more than its `membershipClaimLimit` bytes; the starter's converter has no limit, so issue such
tokens with your own converter or issuer. A token refreshed from a marked token keeps the marker.

**The provider.** `MembershipProvider` is a `suspend` function that must not block the calling thread. It answers
`Memberships` whose source is `PROVIDER`; `null` when it does not know the principal, which is not the same as
knowing it with no memberships; and `active = false` for a principal that is deactivated. It reports a failed lookup
by throwing. `InMemoryMembershipProvider` serves tests and local development, and answers `null` for a principal it
was not given. `cosec-cocache` has `CoCacheMembershipProvider`, which caches the answers of your `MembershipLoader` in
Redis, shared by every instance, and drops them on `invalidate(tenant, principal)` when the principal's memberships
change; entries also expire after the cache's TTL. The starter declares it as the `MembershipProvider` when the
application has a `MembershipLoader` bean and no `MembershipProvider` of its own. An unknown principal is not cached:
each request for it asks the loader again. A principal has one source: claims or the provider, never both.

**Unknown or deactivated means refused.** When the provider answers `null` or `active = false`, the credential that
asked for it (a marked token, or an [API key](#api-keys)) is refused: the request is evaluated as anonymous, and a
denial answers `401` `token-invalid`. The decision event's `subject.rejected` is `API_KEY_INVALID` for a key and
`TOKEN_INVALID` for a token. Deactivating a principal therefore deactivates every one of its keys at once (`IDN-3`).

**Failure means `503`.** The request is unavailable, never decided with "no memberships", when:

- the credential asks for the provider and there is none;
- the provider throws, answers memberships whose source is not `PROVIDER`, or does not answer within
  `cosec.authorization.membership.timeout` (500 ms);
- the marker is anything but `"provider"`, or the token carries the marker together with `roles` or `policies`.

No memberships would mean no roles, so a DENY bound to a role would no longer apply.

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

`Credentials` and `Authentication` here are the login types of `me.ahoo.cosec.login` (`TokenCompositeAuthentication`
and `TokenConverter` are in `me.ahoo.cosec.login.token`), from `cosec-login`, which the starter brings; they are not
the pipeline's `Credentials` and `Authenticator` (`me.ahoo.cosec.spi.authn`).
`cosec.authentication.login.enabled` (on by default) switches only these login beans, `TokenCompositeAuthentication`
and social login included; requests are authenticated by the pipeline either way. The login path must be reachable by
anonymous callers, so it needs an `Anyone` binding that permits it, as in the
[Quick start](../guide/quick-start.md#write-a-first-policy). To issue tokens yourself, for example with a custom
lifetime, call `TokenConverter.toToken(principal)` or `toToken(principal, accessValidity, refreshValidity)`.

## Refreshing tokens

A client whose access token expired sends both tokens back. `TokenVerifier.refresh(compositeToken)` checks that:

- the refresh token is validly signed and not expired;
- the access token, which may be expired, is validly signed with the same key;
- the refresh token belongs to that access token (its `sub` is the access token's `jti`);
- the access token was not revoked, and neither were its principal's tokens.

A failed check throws a `TokenVerificationException`; a refresh token that was issued with another access token
throws its subclass `RefreshTokenMismatchException`. It returns the principal from the old access token; issue new
tokens for it with `TokenConverter.toToken`. Refreshing a [delegation token](#tokens) (one with an `act` claim) fails
too, with `DelegationUnsupportedException`. The starter registers no refresh endpoint.
`SimpleRefreshTokenAuthentication` does the check above as an `Authentication`, so with it declared as a bean, a
refresh is a login with credentials that implement `RefreshTokenCredentials`:

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

- **It does not re-read the user.** The new token carries the claims of the old one. To pick up changed roles on
  refresh, write your own `Authentication` for refresh credentials that verifies them with `TokenVerifier.refresh`
  and then loads the user.
- **It does not invalidate the old refresh token.** A refresh token stays usable until it expires. To end a
  session, [revoke](#logout-and-revocation) its access token, which also blocks the refresh token bound to it.

## Logout and revocation

JWTs are stateless: without extra work a token stays valid until it expires. So that logout takes effect at once,
revocation is always on, with no switch. It needs `cosec-cocache`, or a `TokenStore` and a `RevocationReader` bean of
your own. With JWT enabled and neither, startup fails: a logout that silently does nothing is not an option. A
service that only verifies tokens it never revokes still needs the reader; one that issues no tokens and reads none
can set `cosec.jwt.enabled=false`.

From your logout endpoint, call `TokenRevoker.revoke(accessToken)`. It verifies the token, then records its `jti`
in Redis for the refresh token's lifetime. From then on every instance rejects the access token as `token-revoked`,
and refreshing it fails. Revoke before the access token expires: an expired token cannot be revoked, and its refresh
token then stays usable. Revoking a [delegation token](#tokens) fails too, since it never verifies: there is nothing to
revoke. `TokenRevoker` is synchronous: call it off the event loop.

Every authenticated request checks revocation, without blocking. Three properties of the check matter:

- **"Not revoked" is cached locally for 30 seconds** (`cosec.authorization.cache.token.*`). A revocation through
  `TokenRevoker` invalidates that answer on every instance at once. If the invalidation message is lost, or the
  revocation is written to Redis some other way, an instance may accept the token for up to 30 seconds more.
- **A degraded read counts as "not revoked".** When Redis cannot be read, the revocation caches answer as for a
  missing entry, and the token is accepted. This is one of
  [the two documented exceptions](../guide/security-model.md#the-two-documented-exceptions) to failing closed: the
  signature and expiry are still checked, and rejecting every authenticated request during a Redis outage would be
  worse. Keep access tokens short-lived so this window stays small.
- **Any other error fails the request.** A revocation entry that Redis returns but CoSec cannot decode, or an error
  a custom `RevocationReader` throws, is `503`, never an accepted token.

### Revoking every token of a principal

To lock a user out at once, for example after a compromise or when roles carried in the token must stop applying,
call `TokenRevoker.revokeSubject(tenant, principal, issuedBefore)`. `issuedBefore` defaults to now. Every access and
refresh token of that principal in that tenant whose `iat` is before `issuedBefore` is then rejected as
`token-revoked`, on every instance; tokens issued later, such as after the user logs in again, are accepted.

- A principal without a tenant is in the default tenant `(0)`.
- `iat` has whole seconds, so a token issued in the same second as the revocation, even just after it, is rejected
  too. A token without `iat` is always rejected once its principal has been revoked.
- `iat` comes from the issuer's clock and `issuedBefore` from the clock of the instance that revokes. So that an
  issuer whose clock runs ahead cannot stamp a token issued before the revocation with a later `iat`, requests also
  reject tokens issued up to `cosec.authentication.revocation.skew` (5 seconds) after `issuedBefore`. A user who
  logs in again within that window must log in once more. [Refreshing](#refreshing-tokens) applies the same skew,
  so such a token cannot be refreshed into a new one either.
- The entry is kept for the refresh token lifetime past `issuedBefore`. A later call with an earlier `issuedBefore`
  does not shorten it, even when two instances revoke the same principal at once.
- It needs the same store as revoking one token. A `TokenStore` that cannot record it throws
  `UnsupportedOperationException` instead of doing nothing.
- The check behaves as described above: a local "not revoked" answer for up to 30 seconds if an invalidation is
  lost, and the token is accepted on a degraded read.

## API keys

Machine clients (jobs, partner integrations, internal services) often cannot log in. Give them an API key: whoever
sends it in the `cosec.pipeline.api-key-header` header (`X-API-Key`) acts as the key's principal in the key's
tenant, and is authorized like anyone else. API keys are off by default; turn them on with
`cosec.api-key.enabled=true`.

**A key grants nothing of its own** (`IDN-1`). Its memberships are its principal's, from the
[`MembershipProvider`](#memberships), so a key is never more than its principal. What the principal is decides what
the key is: a key of a user is a personal access token, a key of a workload principal (a service account) is that
service's credential. Roles and bindings are written for the principal, in documents or in the provider, never on
the key. A key may only narrow them, with `roles`, its **cap** (`IDN-2`):

| `roles` | The key holds |
|---|---|
| unset | everything its principal holds, attached policies included |
| `[billing, auditor@team-a]` | only those of its principal's roles, matched exactly by space (`role` is the default space, `role@space` that space), and no attached policies: a cap is written in roles. A role in the cap that the principal does not hold gives nothing. |
| `[]` | no roles and no attached policies |

A request that carries a key is judged by the key alone ([Authenticators](#authenticators)). An unknown, disabled
or expired key makes the request anonymous; a denial then answers `401` `token-invalid`. So does a key whose
principal the provider does not know or knows as deactivated (`IDN-3`): disabling a service account disables every one
of its keys. The principal gets the attribute `apiKeyId`, naming the key used, and no other attribute from the key.
CoSec never stores or logs the key itself, only its SHA-256 hash (`ApiKeys.hash`). A key cannot act for someone else:
delegation is [refused](#tokens) for every credential.

**A provider is required.** With `cosec.api-key.enabled=true` and no `MembershipProvider` bean, the application does
not start (`IDN-6`): add `cosec-cocache` and a `MembershipLoader` bean, which configures `CoCacheMembershipProvider`,
or declare a `MembershipProvider` of your own. The check runs where keys are authenticated, in
`cosec.pipeline.mode=enforce`; a service behind a gateway (`gateway` mode) reads no keys, only the identity the
gateway verified, so it needs no provider for them.

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
        tenant-id: acme
        roles: [billing, billing-admin@eu]
        expires-at: 2027-01-01T00:00:00Z
      - id: billing-admin
        hash: <SHA-256 hex of the other key>
        principal-id: svc-billing
        tenant-id: acme
        # no roles: the key holds everything svc-billing holds
```

Each key needs an `id`, a 64-character lowercase hex `hash`, a `principal-id` and a `tenant-id`, and ids must be
unique; otherwise the application does not start. So does a key configured with `policies` or `attributes`, which
keys no longer carry: unknown fields fail binding instead of being dropped. Give a key used through a gateway an `expires-at`: the gateway refuses to forward the
identity of a credential that never expires ([Forwarding the identity](./web.md#forwarding-the-identity)). Rotate a
key by adding the new one, moving the client over, then removing the old one.

**In Redis**, shared by every instance and changeable at runtime: set `cosec.api-key.store: redis` (needs
`cosec-cocache`; keys live under `cosec.api-key.key-prefix`, default `cosec:api-key`). The pipeline reads them with
the non-blocking `RedisApiKeyReader`. Manage keys through the `RedisApiKeyStore` bean:

| Call | Effect |
|---|---|
| `issue(apiKey)` | Creates a key, stores only its hash, and returns the key. Show it once; it cannot be recovered. |
| `save(hash, apiKey)` | Replaces the key with that hash, for example to set `enabled = false` or change its roles. |
| `revoke(hash)` | Removes the key. It stops working on the next request. |

Every request with a key then reads Redis. If Redis cannot be read, the request is `503`: unlike token revocation, an
API key lookup fails closed. A key is stored as `{"format":2,"id":…,"principalId":…,"tenantId":…,"roles":[…]|null,
"expiresAt":…|null,"enabled":…}`, every field always written. A key stored in the format before 8.0, which granted
roles and policies of its own, is refused when it is read, never taken as a key without a cap: the request is `503`
and the log names `cosec-migrate api-keys`, which converts such keys
([Upgrading](../operations/upgrading.md#api-keys-narrow-and-memberships-decide)).

**Elsewhere**: declare your own `ApiKeyReader` bean, a non-blocking lookup by hash that throws when it cannot read.
With API keys enabled and neither the in-memory store nor an `ApiKeyReader`, startup fails.

## Tenants

A principal may act within a tenant. The principal your `Authentication` returns decides it: a `TenantPrincipal`
(such as `SimpleTenantPrincipal(principal, SimpleTenant(tenantId))`) puts its tenant in the token's `tenantId` claim;
a token without the claim, or with an empty one, belongs to the default tenant `(0)`. The tenant decides which tenant
document a request reads, and conditions read it as `subject.tenant` or `scope.tenant`. What CoSec does and does not
isolate between tenants is in [Tenant isolation](../guide/security-model.md#tenant-isolation).

To let a user switch tenants, extend `AbstractSwitchTenantAuthentication`, implement
`switchTenant(targetTenantId, previousPrincipal)` (check that the user belongs to the target tenant), and issue a
new token for the principal it returns.

**The platform tenant `(platform)` is privileged**: only its principals can be granted `cosec:root` through
break-glass, and only they act on the platform and app documents in the control plane
([Break-glass](../guide/security-model.md#break-glass)). Issue tokens for `(platform)` only from a source that users
cannot influence.

## Social login

With `cosec-social`, `SocialAuthentication` signs users in through [JustAuth](https://github.com/justauth/JustAuth)
providers. Configure each provider under `cosec.authentication.login.social.registration.<name>`, with its JustAuth
`type`, client id, secret and redirect URI ([Configuration](../operations/configuration.md)).

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

A service behind `cosec-gateway-server`, or any CoSec gateway, either trusts the gateway or decides again. No service
trusts a token without verifying it.

- **Trust the gateway's identity** (`cosec.pipeline.mode=gateway`). The gateway verifies the credential, checks
  revocation and decides, then forwards who the caller is in `CoSec-Identity`. The service needs no secret, keys,
  documents or Redis, and must be reachable only through the gateway. Setup, answers and the security context are in
  [Services behind the gateway](./web.md#services-behind-the-gateway).
- **Decide again** (the default, `enforce`). The service runs its own pipeline: it verifies the token's signature,
  expiry and revocation, and decides with the same documents.

To decide again, set `cosec.gateway.identity.forward-authorization=true` on the gateway: by default it removes the
`Authorization` header before forwarding. Give the services and the gateway the same `cosec.jwt.secret`, the same
Redis, and the same `cosec.authorization.snapshot.key-prefix`, so they read the same documents and the same
revocations. The gateway sets `CoSec-Request-Id` to its own request id ([Gateway](./web.md#gateway)), so each
service's outcome carries the same id. Set `cosec.authorization.scope.app-id` on each service, and
`cosec.authorization.remote-ip.max-trusted-index` to the number of proxies in front of it, the gateway included
([Trust boundaries](../guide/security-model.md#trust-boundaries)).

**Next:** ask for decisions from your own code, in [Checks in code](./checks.md).
