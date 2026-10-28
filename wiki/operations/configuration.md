---
title: "Configuration"
description: "Every cosec.* property, its type, its default and what it changes."
---

# Configuration

For developers and operators tuning a deployment. This page is the single reference for the `cosec.*` properties the
starter binds, and for the `cocache.*` properties that change how CoSec behaves; other pages name a property and link
here for its type and default.

Every property is optional unless marked **required**. A property only acts when the module it configures is on the
classpath: each integration is a starter feature variant ([Modules](../guide/introduction.md#modules)). Durations take
Spring's format (`500ms`, `10s`, `7d`), data sizes `16MB`; sets and lists take a comma-separated value or a YAML list;
enum values are case-insensitive. Properties that 7.x had and 8.0 does not are listed in
[Upgrading](./upgrading.md#removed-and-renamed-properties).

## Unknown properties

Startup fails on a `cosec.*` key CoSec does not read, whatever else is configured (`cosec.enabled=false` included): a
misspelled, renamed or removed key would otherwise do nothing. A key is known when the configuration metadata on the
classpath (`META-INF/spring-configuration-metadata.json`) declares it, or when it lies under a map- or list-valued
property. The message lists every unknown key and, for a 7.x key, what replaced it
([the 7.x key table](./upgrading.md#removed-and-renamed-properties)).

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.unknown-properties` | `fail`, `warn` | `fail` | `fail` stops startup on an unknown `cosec.*` key; `warn` logs the same list at `WARN` and starts. |

## Feature switches

| Property | Type | Default | Turns on |
|---|---|---|---|
| `cosec.enabled` | boolean | `true` | every CoSec auto-configuration |
| `cosec.authentication.login.enabled` | boolean | `true` | the login beans, which turn credentials into a token: `CompositeAuthentication` over your `Authentication` beans, `TokenCompositeAuthentication` with JWT, and social login. Authenticating requests in the pipeline does not depend on it |
| `cosec.authentication.login.social.enabled` | boolean | `true` | social login (`oauth-support`; needs `cosec.authentication.login.enabled`) |
| `cosec.jwt.enabled` | boolean | `true` | issuing, verifying and revoking JWTs. Off, the pipeline rejects every token (`token-invalid`) and treats a request without one as anonymous |
| `cosec.api-key.enabled` | boolean | `false` | [API keys](../integration/authentication.md) for machine clients |
| `cosec.audit.enabled` | boolean | `true` | the audit log listener, and the Kafka audit listener with `cosec.audit.kafka.enabled` |
| `cosec.audit.kafka.enabled` | boolean | `true` | the Kafka audit listener (`kafka-support` and a `KafkaOperations` bean) |
| `cosec.metrics.enabled` | boolean | `true` | the metrics listener (`micrometer-support` and a `MeterRegistry` bean) |
| `cosec.ip2region.enabled` | boolean | `true` | the `ext.ip2region.*` attribute provider (`ip2region-support`) |
| `cosec.openapi.enabled` | boolean | `true` | the bearer-auth customizer of the OpenAPI document (`openapi-support`) |
| `cosec.authorization.shadow.enabled` | boolean | `true` | this instance's part in shadow windows (`cache-support`); see [Shadow windows](#shadow-windows) |
| `cosec.gateway.identity.enabled` | boolean | `true` | on a Spring Cloud Gateway host, forwarding the verified identity to the services behind it; see [Gateway identity](#gateway-identity) |

These have no switch:

- **Tracing** is on whenever `opentelemetry-support` is on the classpath ([Tracing](./observability.md#tracing)).
- **The control plane** is on whenever `control-support` and `cache-support` are on the classpath; its routes need a
  WebFlux or WebMvc application ([Control plane](#control-plane)).
- **The rate limiter** is always there: on Redis when a `ReactiveStringRedisTemplate` bean exists (`cache-support`),
  otherwise counted per instance ([Rate limiting](#rate-limiting)).
- **Token revocation** is always on with JWT ([Startup](./troubleshooting.md#startup-fails-without-a-token-store)).
- **The `cosecServiceGenerator` actuator endpoint** is there whenever `openapi-support` and Spring Boot Actuator are;
  expose it like any actuator endpoint.

## Pipeline

`cosec.pipeline.*`: how one request is read and bounded.

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.pipeline.timeout` | duration | `5s` | The whole evaluation of one request. Past it the request is answered `503` `unavailable`. |
| `cosec.pipeline.authentication-timeout` | duration | `1s` | One call of the `Authenticator`. Past it, `503`. |
| `cosec.pipeline.allow-query-token` | boolean | `false` | Also read the access token from the `authorization` query parameter. Off because URLs end up in access logs, browser history and `Referer` headers. |
| `cosec.pipeline.api-key-header` | string | `X-API-Key` | The request header an API key comes in. |
| `cosec.pipeline.trace-level` | `off`, `full`, `exhaustive` | `off` | How much of each evaluation the engine traces. Decision events carry the trace as `evaluationTrace` (null at `off`, and when the request did not reach the engine); a higher level costs time on every request. |
| `cosec.pipeline.mode` | `enforce`, `gateway` | `enforce` | `enforce`: the host decides with policies. `gateway`: a service behind a CoSec gateway reads the caller from the `CoSec-Identity` header the gateway forwards, and wires no authenticator, API keys, snapshot, policies, control plane or Redis of its own ([Services behind the gateway](../integration/web.md#services-behind-the-gateway)). Any other value fails startup, and so does `gateway` on a Spring Cloud Gateway host. In `gateway` mode only the gateway may be able to reach the service: startup logs a `WARN` saying so. JWT is not limited to `enforce` mode: a service that issues no tokens sets `cosec.jwt.enabled=false`, or startup asks for a secret and a token store. |
| `cosec.pipeline.gateway.max-age` | duration | `60s` | In `gateway` mode, an identity issued longer ago than this, plus `cosec.authentication.revocation.skew`, is refused: `401` `token-expired`. |
| `cosec.pipeline.gateway.max-size` | data size | `8KB` | In `gateway` mode, a larger `CoSec-Identity` header is refused: `400` `invalid-request`. |

## Authorization

`cosec.authorization.*`: the host's scope and the per-stage timeouts.

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.authorization.scope.app-id` | string | none | The app this host serves. A request that declares another app with `CoSec-App-Id` is rejected (`403` `scope-rejected`, `401` for an anonymous caller). Unset, requests declare their app ([Trust boundaries](../guide/security-model.md#trust-boundaries)). |
| `cosec.authorization.remote-ip.max-trusted-index` | int | `1` | How many proxy hops of `X-Forwarded-For` to trust when resolving the client IP. `0` ignores the header (the host is exposed directly); `N` takes the entry the N-th closest proxy appended. |
| `cosec.authorization.membership.timeout` | duration | `500ms` | One call of the `MembershipProvider`. Past it, or when it fails, `503`. |
| `cosec.authorization.attributes.timeout` | duration | `300ms` | One call of an `AttributeProvider` that sets no timeout of its own. Past it, its attributes are unavailable, and a statement that needs them makes the decision Indeterminate. |
| `cosec.authorization.routes.location` | comma-separated resource locations | `classpath:cosec-routes.json` | The route maps a WebFlux or WebMvc host loads at startup. A location that does not exist, or a map that does not parse, fails startup. Set it empty to run without a route map: every request is then authorized in the `http` namespace. A Spring Cloud Gateway host ignores it and loads no route map. |

## Gateway identity

`cosec.gateway.identity.*`, on a Spring Cloud Gateway host (`gateway-support`): what the gateway forwards to the
services behind it. On a permit with a principal it sets `CoSec-Identity` to the identity it verified (format:
[`schema/cosec-identity.schema.json`](https://github.com/Ahoo-Wang/CoSec/blob/main/schema/cosec-identity.schema.json)).
It always removes a `CoSec-Identity` the client sent, whatever these properties, the mode or `cosec.enabled` say. A
route with the metadata `cosec-identity: false` forwards no identity.

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.gateway.identity.enabled` | boolean | `true` | Forward the verified identity. Off, the gateway still strips the client's. |
| `cosec.gateway.identity.claims` | set of attribute names | empty | Principal attributes forwarded with the identity as `claims`, such as `name` and `email` (for a JWT, keys of its `attributes` claim, not top-level claims): strings of at most 1024 characters, numbers and booleans. Any other value fails the request (`500`). |
| `cosec.gateway.identity.max-size` | data size | `8KB` | A larger encoded identity fails the request closed (`500`) rather than being cut. |
| `cosec.gateway.identity.forward-authorization` | boolean | `false` | Whether the client's `Authorization` header still goes downstream. Off, the gateway removes it from every request it forwards: a service behind it needs no token. Turn it on only for services that call other APIs on the user's behalf. A token in a cookie is still forwarded. |

A credential that never expires (an API key without `expires-at`) cannot be forwarded: its permits are answered `500`.

## Rate limiting

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.authorization.rate-limit.timeout` | duration | `500ms` | One call of the `RateLimiter`. Past it, the rule's `strictFailure` decides: `503`, or the rule is skipped. |

Rate limit rules are part of the documents ([Rate limiting](../policies/rate-limiting.md)). The limiter is
`ReactiveRedisRateLimiter` when a `ReactiveStringRedisTemplate` bean exists, otherwise `LocalRateLimiter`, which counts
per instance. The Redis counters have a fixed key prefix ([Redis keys](./storage.md#redis-keys)). A `RateLimiter` bean
of your own replaces both.

## Snapshot

`cosec.authorization.snapshot.*`: how an instance reads and holds the platform, app and tenant documents
([How instances read documents](./storage.md#how-instances-read-documents)).

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.authorization.snapshot.key-prefix` | string | `cosec` | The Redis namespace of the snapshot documents, the control plane's records and the shadow keys, with a trailing `:` removed. Every service that reads the same platform, and the control plane, must use the same value. |
| `cosec.authorization.snapshot.local-max-age` | duration | `30s` | How long CoCache's local copy of a document may serve a cold load before Redis is asked again. It applies whatever a cache customizer or `cocache.*` property sets. |
| `cosec.authorization.snapshot.revalidate-interval` | duration | `10s` | How often every installed document is confirmed against Redis with a conditional read. `0` turns the periodic pass off; invalidation messages still reload changed documents. |
| `cosec.authorization.snapshot.lag-warn` | duration | `60s` | A document not confirmed for longer is logged at `WARN`. Must not exceed `lag-escalate`. |
| `cosec.authorization.snapshot.lag-escalate` | duration | `5m` | A document not confirmed for longer is logged at `ERROR` as stale. |
| `cosec.authorization.snapshot.fail-closed.tenants` | set of tenant ids | empty | Tenants that must not be decided on old documents: when the oldest document a request of such a tenant reads was confirmed longer ago than `max-lag`, the request is `503` (`SourceUnavailable`) unless a DENY matches. Other tenants keep deciding on what is installed. |
| `cosec.authorization.snapshot.fail-closed.max-lag` | duration | `30m` | The lag beyond which the tenants above fail closed. |
| `cosec.authorization.snapshot.cold-load-timeout` | duration | `2s` | How long a request waits for a document that is not installed. Past it the document counts as failed for that request. |
| `cosec.authorization.snapshot.max-apps` | int | `1024` | The most app documents held in memory; the least recently used one is dropped beyond it, and loaded again when needed. |
| `cosec.authorization.snapshot.max-tenants` | int | `1024` | The same for tenant documents. The platform document is never dropped. |
| `cosec.authorization.snapshot.prewarm-apps` | list of app ids | empty | App documents loaded at startup, after the platform document, which is always loaded. Readiness refuses traffic until the platform document and every listed document are installed; liveness is not affected. |
| `cosec.authorization.snapshot.prewarm-tenants` | list of tenant ids | empty | Tenant documents loaded at startup, as above. |
| `cosec.authorization.snapshot.compile-threads` | int | `2` | Threads that compile documents. |
| `cosec.authorization.snapshot.eviction-check` | `fail`, `warn` | `fail` | What startup does when Redis' `maxmemory-policy` is not `noeviction` on every node, or cannot be read (a failed or denied `CONFIG GET`, a wrapped or custom CoCache backend). `fail` stops startup. `warn` starts and logs a `WARN` that begins with `!!!` ([Redis requirements](./storage.md#redis-requirements)). The in-memory CoCache backend always passes. |

## Shadow windows

`cosec.authorization.shadow.*`: the data plane's part in the control plane's shadow windows. It needs `cache-support`.
How windows work: [Testing changes](../policies/testing.md).

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.authorization.shadow.enabled` | boolean | `true` | Evaluate sampled requests again on the staged candidates, and write this instance's reports and status to Redis. Off, this instance's traffic counts for nothing in a window, a candidate it cannot compile does not block one, and it writes no status for the propagation view. |
| `cosec.authorization.shadow.sample-rate` | double, `0` to `1` | `0.1` | Share of requests evaluated again on the candidates, off the request path. |
| `cosec.authorization.shadow.report-interval` | duration | `10s` | How often the instance writes its reports and its status. |
| `cosec.authorization.shadow.instance-id` | string | a random UUID | This instance's id in reports and statuses. Unique per running instance; only letters, digits, `.`, `_` and `-`, since it ends a Redis key. Any other id fails startup. |

## Authentication

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.authentication.revocation.skew` | duration | `5s` | How much later than a subject revocation a token may have been issued and still be revoked by it: room for an issuer whose clock runs ahead. |

## JWT

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.jwt.secret` | string | none | **Required** while JWT is enabled; startup fails when it is blank. Use a long random value from a secret store. |
| `cosec.jwt.algorithm` | `hmac256`, `hmac384`, `hmac512` | `hmac256` | Signing algorithm. Issuers and verifiers must agree. |
| `cosec.jwt.token-validity.access` | duration | `10m` | Access token lifetime. |
| `cosec.jwt.token-validity.refresh` | duration | `7d` | Refresh token lifetime; a revocation is kept in Redis this long, plus one second. |

With JWT enabled, startup fails unless revocations can be recorded and read: `cache-support`, or a `TokenStore` and a
`RevocationReader` bean of your own.

## API keys

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.api-key.store` | `memory`, `redis` | `memory` | `memory`: the keys configured below. `redis`: keys shared by every instance; needs `cache-support`, a `StringRedisTemplate` and a `ReactiveStringRedisTemplate`. |
| `cosec.api-key.key-prefix` | string | `cosec:api-key` | Redis key prefix of the `redis` store, with a trailing `:` removed. Independent of every other prefix ([Redis keys](./storage.md#redis-keys)). |
| `cosec.api-key.keys[n].*` | list | empty | Keys of the `memory` store; ignored with `redis`. |

The header is [`cosec.pipeline.api-key-header`](#pipeline). Each entry of `cosec.api-key.keys`:

| Field | Type | Default | Meaning |
|---|---|---|---|
| `id` | string | none | **Required.** The key's id; ids must be unique. |
| `hash` | string | none | **Required.** SHA-256 of the key, 64 lowercase hex characters. Never configure the key itself. |
| `principal-id` | string | none | **Required.** The principal the key authenticates as. |
| `tenant-id` | string | the default tenant | Tenant of the principal. |
| `roles` | set | empty | Roles, claimed as memberships. |
| `policies` | set | empty | Policies attached to the principal, claimed as memberships. |
| `attributes` | map | empty | Principal attributes. |
| `expires-at` | instant | none | After this instant the key is rejected. |
| `enabled` | boolean | `true` | A disabled key is rejected. |

## Cache

`cosec.authorization.cache.*`: the revoked token and revoked subject caches, created with JWT and `cache-support`.
Snapshot documents have their own properties ([Snapshot](#snapshot)).

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.authorization.cache.key-prefix` | string | `cosec` | Prefix of the revocation keys, with a trailing `:` removed. Independent of `cosec.authorization.snapshot.key-prefix`. |
| `cosec.authorization.cache.token.*` | [local cache settings](#local-cache-settings) | `local-max-age: 30s`, `maximum-size: 100000` | Local copy of revocation lookups (`RevokedTokenCache` and `RevokedSubjectCache`). |

### Local cache settings

| Property | Type | Default | Effect |
|---|---|---|---|
| `maximum-size` | long | CoCache's | Maximum entries in the local copy. |
| `initial-capacity` | int | CoCache's | Initial capacity of the local copy. |
| `local-max-age` | duration (a bare number is seconds) | CoCache's | How long a local copy lives at most: the bound on staleness when an invalidation message is lost. |
| `expire-after-access` | duration (a bare number is seconds) | none | Drop a local copy not read for this long. |

These size and age the local copy only. A revocation's lifetime in Redis is set when it is written.

## Audit

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.audit.required.timeout` | duration | `1s` | How long one `REQUIRED` decision listener may take. A failure or timeout turns a permit into `503` ([Decision listeners](./observability.md#decision-listeners)). |
| `cosec.audit.kafka.topic` | string | `cosec-audit` | Kafka topic of the audit events. |
| `cosec.audit.kafka.delivery` | `best-effort`, `required` | `best-effort` | How the Kafka audit listener is delivered. `required` waits for the broker's acknowledgement, so a permit that cannot be audited is a `503`; configure the producer with `acks=all`. |

## Decision listeners

`cosec.observability.listener.*`: the queues of the `BEST_EFFORT` decision listeners
([Decision listeners](./observability.md#decision-listeners)).

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.observability.listener.queue-capacity` | int | `1024` | Events one `BEST_EFFORT` listener may have waiting. A full queue drops the next event and counts it (`cosec.listener.dropped`). |
| `cosec.observability.listener.drain-timeout` | duration | `5s` | On shutdown, how long queued events still go out; the rest is then dropped. |

## Control plane

`cosec.control.*`, with `control-support` and `cache-support` ([Publishing documents](./storage.md#publishing-documents)).

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.control.app-id` | string | `cosec-control` | The app control-plane actions are authorized in. |
| `cosec.control.http.base-path` | string | `/cosec/control/v1` | Where the control API's routes are served. |
| `cosec.control.http.max-body-size` | data size | `16MB` | The largest request body the control API reads on a WebMvc host. A larger body is a `400` `invalid-request`, unread; a request without a permitted principal has no body read at all (`401`). On WebFlux, `spring.codec.max-in-memory-size` (256 KB by default) bounds the body instead. |
| `cosec.control.revisions.max-count` | int | `50` | Revisions always kept per document; older ones are reclaimed once also older than `min-age`. Revisions an open change rests on are kept. |
| `cosec.control.revisions.min-age` | duration | `7d` | Revisions younger than this are always kept. |
| `cosec.control.revisions.orphan-age` | duration | `1h` | Records of conflicted publishes are removed once this old. |
| `cosec.control.revisions.reclaim-interval` | duration | `1h` | How often each control-plane instance reclaims revisions; a lease lets one instance at a time do each document. Must be positive. |
| `cosec.control.changes.expire-after` | duration | `7d` | An open change request expires this long after it was proposed, and leaves the list of open changes. |
| `cosec.control.approval.expire-after` | duration | `72h` | How long an approval stays valid. |
| `cosec.control.approval.four-eyes` | boolean | `false` | Whether the changes of an operator holding `cosec:approve` on the document (the trust root) are analyzed too: what the analysis cannot prove contained then needs a second person's approval. A change that escalates needs one either way. |
| `cosec.control.break-glass.max-duration` | duration | `4h` | The longest a break-glass binding may last. More than `24h` fails startup. |
| `cosec.control.shadow.min-duration` | duration | `15m` | A shadow window closes no earlier than this, once it also has `min-samples`. |
| `cosec.control.shadow.min-samples` | long | `1000` | Compared samples a window needs to close before `max-duration`. |
| `cosec.control.shadow.max-duration` | duration | `24h` | A window closes at this age with fewer samples, as insufficient evidence the publisher must acknowledge. Must not be less than `min-duration`. |
| `cosec.control.shadow.check-interval` | duration | `1m` | How often shadowing changes are looked at, by one instance at a time; a window closes up to this late. Must be positive. |
| `cosec.control.audit.required-timeout` | duration | `5s` | How long every `REQUIRED` change listener together may take to record a gated step (publish, break-glass) before the step fails with `503`. |
| `cosec.control.audit.kafka.enabled` | boolean | `true` | The Kafka change listener (`kafka-support` and a `KafkaOperations` bean). |
| `cosec.control.audit.kafka.topic` | string | `cosec-control-audit` | Kafka topic of the change events. |
| `cosec.control.audit.kafka.delivery` | `best-effort`, `required` | `best-effort` | `required` makes publishing and break-glass wait for the broker. Startup fails when it is `required` and no Kafka change listener could be created. |

## Social login

`cosec.authentication.login.social.registration.<name>.*` configures one OAuth provider per `<name>`:

```yaml
cosec:
  authentication:
    login:
      social:
        registration:
          github:
            type: github           # a JustAuth AuthDefaultSource
            client-id: ${GITHUB_CLIENT_ID}
            client-secret: ${GITHUB_CLIENT_SECRET}
            redirect-uri: https://app.example.com/oauth/github/callback
```

`type` (a JustAuth `AuthDefaultSource`) and `client-id` are required. The other fields map one-to-one to JustAuth's
`AuthConfig`: `client-secret`, `redirect-uri`, `alipay-public-key`, `union-id`, `stack-overflow-key`, `agent-id`,
`usertype`, `domain-prefix`, `http-config`, `ignore-check-state`, `scopes`, `device-id`, `client-os-type`, `pack-id`,
`pkce`, `auth-server-id`, `ignore-check-redirect-uri`, `tenant-id`, `kid`, `team-id`, `login-type` (default
`CorpApp`), `lang` (default `zh`), `ding-talk-org-type`, `ding-talk-corp-id`, `ding-talk-exclusive-login` and
`ding-talk-exclusive-corp-id`. Booleans default to `false`, the others to none. Social login keeps its OAuth state in
Redis through a `StringRedisTemplate`.

## CoCache properties that affect CoSec

With `cache-support`, CoSec creates its caches on CoCache's runtime. The full list of CoCache properties is in
[CoCache's documentation](https://cocache.ahoo.me).

| Property | Effect on CoSec |
|---|---|
| `cocache.backend` | `redis` (default) or `memory`. With `memory` nothing is shared between instances, the eviction check passes, and every instance starts with no documents: local development and tests only. |
| `cocache.defaults.*` | The policy of every CoCache cache, CoSec's `PolicySnapshotCache`, `RevokedTokenCache` and `RevokedSubjectCache` included, except for what CoSec fixes ([Precedence](#precedence)). |
| `cocache.caches.<CacheName>.*` | The policy of one cache, by the names above. |
| `cocache.redis.command-timeout` | How long one Redis command may take (`2s` by default). A snapshot read that times out is a failed read ([When Redis is unavailable](./storage.md#when-redis-is-unavailable)). |
| `cocache.redis.channel-mode` | `pubsub` or `sharded` invalidation. Every instance, and the control plane, must agree. |
| `spring.data.redis.*` | The Redis CoCache connects to, and the one the rate limiter and the Redis API key store use. |

The control plane's caches and the shadow caches are created from CoSec's own specs, with their policies fixed.

## Precedence

For `PolicySnapshotCache`, `RevokedTokenCache` and `RevokedSubjectCache`, the cache policy is assembled in this order;
a later step wins over an earlier one:

1. CoSec's default for the cache: `cosec.authorization.snapshot.local-max-age` for the snapshot cache,
   `cosec.authorization.cache.token.*` for the revocation caches;
2. the application's CoCache `CacheSpecCustomizer` beans;
3. `cocache.defaults.*`, then `cocache.caches.<CacheName>.*`;
4. CoSec's fixed settings, applied last and impossible to override.

So a `cocache.*` property wins over the matching `cosec.authorization.cache.token.*` one. The fixed settings of step 4:

| Cache | Fixed | Why |
|---|---|---|
| `PolicySnapshotCache` | `ttl` none, `read-failure: throw`, `foreign-format: fail`; no loader (a customizer that sets one fails startup); `local-max-age` from `cosec.authorization.snapshot.local-max-age` | Redis is the store of record ([Storage](./storage.md#redis-is-the-store-of-record)): an expiry, a degraded read or a reclaim would silently drop a document, and with it its DENY statements. |
| `RevokedTokenCache`, `RevokedSubjectCache` | `foreign-format: fail` | Reclaiming an unreadable revocation would make its tokens valid again. |
| every CoSec cache | key prefix, namespace and key encoder | The storage layout is CoSec's. A `key-prefix` from a customizer or `cocache.*` is ignored. |

**Next:** where the documents live and how instances read them, in [Storage and caching](./storage.md).
