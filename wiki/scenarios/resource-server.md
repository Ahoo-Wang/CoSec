---
title: "A Service That Verifies Tokens Another Service Issues"
description: "A resource server: verify the tokens an issuer signs, decide in your own code, keep no policies of your own, and read the issuer's revocations so that a logout takes effect here too."
---

# A Service That Verifies Tokens Another Service Issues

For a team whose service receives tokens that another service issues (an identity provider built on CoSec) and decides
what a caller may do in its own code, with no policies of its own. This page is that service: what it needs, the one
document it publishes, and the one thing it must not get wrong, which is whose revocations it reads. The service that
issues the tokens is [A service that only issues tokens](../integration/authentication.md#a-service-that-only-issues-tokens);
the mechanics of revocation are in [Logout and revocation](../integration/authentication.md#logout-and-revocation).

## What you need

| | A service that verifies tokens another service issues |
|---|---|
| Dependencies | The [Quick start](../guide/quick-start.md#add-the-dependency)'s: `cosec-spring-boot`, the web adapter, and **`cosec-cocache`** with Spring's Redis starter and CoCache's |
| Configuration | The issuer's `cosec.jwt.secret` and `cosec.jwt.algorithm`; `spring.data.redis.*` of **the issuer's Redis**; the issuer's `cosec.key-prefix` |
| Files | `platform.json`, below, on the classpath |
| Beans | An in-memory `SnapshotDocumentStore` marked `@Primary`, which holds that document |
| Redis | The issuer's, with `maxmemory-policy noeviction` ([Redis requirements](../operations/storage.md#redis-requirements)) |
| Left out | Policies, apps, spaces, route maps, the control plane, a document in Redis |
| Concepts | A [rejected token is anonymous](../integration/authentication.md#tokens), revocation, [default deny](../guide/security-model.md#default-deny) |

The default mode, `enforce`, verifies the token of every request and decides with the platform document. The service
does not run `cosec.pipeline.mode=gateway`: that mode verifies nothing and believes any `CoSec-Identity` header
([Services behind the gateway](../integration/web.md#services-behind-the-gateway)).

## Revocation: read the issuer's

A token is a signed claim, and only the issuer knows that it ended a session. When a user logs out, or the issuer
calls `revokeSubject`, the issuer writes the revocation to its Redis. Every service that verifies its tokens has to read
that Redis, or it accepts the revoked token until it expires.

- Use `cosec-cocache` on **the issuer's Redis**, with **the issuer's `cosec.key-prefix`**. It brings the three beans the
  starter needs (`TokenStore`, `RevocationReader`, `TokenFamilyStore`), and the revocation reader reads the issuer's
  keys. The service reads them and revokes nothing unless your code calls `TokenRevoker`. A revocation takes effect here
  as it does on the issuer, within the local cache's 30 seconds at most
  ([Logout and revocation](../integration/authentication.md#logout-and-revocation)).
- A service that cannot reach the issuer's Redis registers all three beans itself. Its `RevocationReader` is then the
  only one that matters: it must answer from the issuer's revocations, however they reach it.
- **`RevocationReader.NONE` is for tokens that nobody, anywhere, revokes.** It is not for "tokens this service never
  revokes", which describes every resource server. With it, a user who logged out, or whose tokens the issuer
  revoked after a compromise, keeps their access to this service until the token expires (the issuer's `cosec.jwt.token-validity.access`, ten minutes by default).
  The service starts and logs one `WARN`, which is easy to miss: no request fails.
- While the issuer's Redis cannot be read, a request's token is accepted unless `cosec.authentication.revocation.fail-closed`
  is `true`, as everywhere ([Logout and revocation](../integration/authentication.md#logout-and-revocation)). A service
  that decides things worth more than that window sets it.

Verifying a token needs the issuer's signing secret, so this service can sign tokens too. Keep the secret in a secret
store, and give it only to services that verify.

## The document

The service holds no policies of its own, but the pipeline still needs a platform document, and until one is installed
every request is `503` and readiness refuses traffic. This one permits every request whose caller proved a principal:

<<< @/examples/resource-server/documents/platform.json [documents/platform.json]

It does not permit anonymous callers, on purpose. A token that is expired, badly signed or revoked makes the caller
anonymous ([Tokens](../integration/authentication.md#tokens)); a document that also permits anonymous requests would
hand such a request to your code as an anonymous principal, with a `200` where the client should learn that its token
was rejected. With this document that request is `401` with the problem `token-expired`, `token-revoked` or
`token-invalid`, and your code never runs. The second policy lets the orchestrator's probes through
([Actuator on an application](../integration/web.md#actuator-on-an-application)); a service with other public routes
adds them here.

The document is not published to Redis: the issuer's Redis holds the issuer's documents under the same prefix, and the
service would read those. Put it in memory instead, from a file at startup:

```kotlin
@Configuration
class ResourceServerDocuments {
    @Bean
    @Primary
    fun snapshotDocumentStore(): SnapshotDocumentStore = InMemorySnapshotDocumentStore().apply {
        val bytes = ClassPathResource("cosec/platform.json").inputStream.use { it.readBytes() }
        put(SnapshotKey.Platform, SnapshotDocuments.read(bytes))
    }
}
```

`@Primary` is not optional while `cosec-cocache` is on the classpath: it brings the Redis store of record as a second
`SnapshotDocumentStore`, and without a primary one the application does not start. Each instance holds its own copy of
the document, and changing it is a deployment.

## Decide in your code

The service decides what a caller may do from the request's `SecurityContext`
([Accessing the security context](../integration/web.md#accessing-the-security-context)): `principal.id`,
`tenantId`, `scope.appId`, and `scope.roles`, the roles the token carries for the request's space. A request reaches your code only
with a verified, unrevoked token, so `principal.authenticated` is true there.

```kotlin
@GetMapping("/orders/{id}")
suspend fun order(@PathVariable id: String, security: SecurityContext): Order {
    if ("order-viewer" !in security.scope.roles) throw ResponseStatusException(HttpStatus.FORBIDDEN)
    return orders.find(security.tenantId, id)
}
```

`scope.appId` is the token's `aud`, which the issuer set: a token without one has no app here. Setting
`cosec.authorization.scope.app-id` on this service makes it refuse (`403` `scope-rejected`) every token that declares
another app or none ([Apps](../integration/authentication.md#apps)). The context is for the
request in progress; to decide for work that runs after the request, see
[Checks in code](../integration/checks.md#work-that-is-not-the-request).

## Check it

The scenarios in [`wiki/examples/resource-server`](https://github.com/Ahoo-Wang/CoSec/tree/main/wiki/examples/resource-server/scenarios)
run against this document on every build, with the [test kit](../policies/testing.md#testing-decisions):

| Request | Caller | Decision |
|---|---|---|
| `GET /orders/1` | a verified token | Permit |
| `POST /orders` | a verified token for the app `order` | Permit |
| `GET /orders/1` | anonymous, or a token that is expired, revoked or invalid | Deny: `401` |
| `GET /actuator/health/readiness` | anonymous | Permit |

The scenarios decide a document; that a revoked token is refused rests on the issuer's revocations being the ones this
service reads. Test it on the real wiring: revoke a token at the issuer, and see this service answer `401`
`token-revoked` for it within the local cache's 30 seconds.

**Next:** the issuing side, in [Authentication](../integration/authentication.md).
