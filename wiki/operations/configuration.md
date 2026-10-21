---
title: "Configuration"
description: "Every cosec.* property, its default and what it changes."
---

# Configuration

For developers and operators tuning a deployment. This page is the single reference for `cosec.*` properties, and
for the `cocache.*` properties that change how CoSec behaves; other pages name a property and link here for its
type and default.

Every property is optional unless marked **required**. A property can only turn on a feature whose module is on the
classpath: each integration is also gated by its Gradle capability ([Add the dependency](../guide/quick-start.md#add-the-dependency)).
Durations take Spring's format (`10m`, `7d`); sets and lists take a comma-separated value or a YAML list.

## Feature switches

| Property | Type | Default | Turns on |
|---|---|---|---|
| `cosec.enabled` | boolean | `true` | everything CoSec auto-configures |
| `cosec.authentication.enabled` | boolean | `true` | authentication (`TokenCompositeAuthentication` and your `Authentication` beans) |
| `cosec.authentication.social.enabled` | boolean | `true` | social login (`oauth-support`) |
| `cosec.authorization.enabled` | boolean | `true` | the WebFlux and WebMvc authorization filters |
| `cosec.authorization.gateway.enabled` | boolean | `true` | the Spring Cloud Gateway filter (`gateway-support`) |
| `cosec.authorization.cache.enabled` | boolean | `true` | the Redis policy and permission repositories (`cache-support`) |
| `cosec.jwt.enabled` | boolean | `true` | issuing and verifying JWTs |
| `cosec.api-key.enabled` | boolean | `false` | [API keys](../integration/authentication.md#api-keys) for machine clients |
| `cosec.inject.enabled` | boolean | `false` | trusting a token already verified upstream: its signature is not checked again, its time claims are ([Behind a gateway](../integration/authentication.md#behind-a-gateway)) |
| `cosec.audit.enabled` | boolean | `true` | an audit event for every decision |
| `cosec.audit.kafka.enabled` | boolean | `true` | sending audit events to Kafka (`kafka-support`, a `KafkaTemplate` bean, and no other `AuditEventSink` bean) |
| `cosec.metrics.enabled` | boolean | `true` | the `cosec.authorization` timer (`micrometer-support` and a `MeterRegistry`) |
| `cosec.explain.enabled` | boolean | `false` | the `AuthorizationExplainer` bean and the `cosecExplain` Actuator endpoint |
| `cosec.shadow.enabled` | boolean | `false` | shadow evaluation of candidate policies and app permissions |
| `cosec.limiter.enabled` | boolean | `true` | rate limit rules |
| `cosec.ip2region.enabled` | boolean | `true` | IP geolocation (`ip2region-support`) |
| `cosec.openapi.enabled` | boolean | `true` | the OpenAPI integration (`openapi-support`) |

OpenTelemetry tracing has no switch: it is on whenever `opentelemetry-support` is on the classpath
([Observability](./observability.md#tracing)).

## Authorization

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.authorization.allow-query-token` | boolean | `false` | Also read the access token from the `authorization` query parameter. Off because URLs leak into access logs, browser history and `Referer` headers. |
| `cosec.authorization.root-enabled` | boolean | `false` | Let the root principal bypass policy and permission checks. Root is the principal of the platform tenant (`(platform)`) whose id is the `cosec.root` system property (`cosec` by default); a principal of any other tenant, or without one, is never root, and the blacklist applies to root too. For another rule, provide a `RootPrincipalMatcher` bean ([Root principal](../guide/security-model.md#root-principal)). |
| `cosec.authorization.remote-ip.max-trusted-index` | int | `1` | How many proxy hops to trust in `X-Forwarded-For` when resolving the client IP. `0` ignores the header (the service is exposed directly); `N` takes the entry appended by the N-th closest proxy. |
| `cosec.authorization.local-policy.enabled` | boolean | `false` | Load policies from local JSON files. |
| `cosec.authorization.local-policy.locations` | set of resource patterns | `classpath:cosec-policy/*-policy.json` | Where the files are. A location that matches no file, other than the default, stops startup, and so do two files with the same policy id ([Loading policy files](../policies/language.md)). |
| `cosec.authorization.local-policy.init-repository` | boolean | `false` | Write the loaded policies into the `PolicyRepository` at startup. Authorization reads only the repository. |
| `cosec.authorization.local-policy.force-refresh` | boolean | `false` | Overwrite policies that already exist in the repository. Without it, an existing id is read and kept. |

`cosec.root` is a JVM system property (`-Dcosec.root=<id>`), not a Spring property.

## JWT

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.jwt.secret` | string | — | **Required** while JWT is enabled; startup fails when it is blank. Use a long random value from a secret store. |
| `cosec.jwt.algorithm` | `hmac256`, `hmac384`, `hmac512` | `hmac256` | Signing algorithm. Issuers and verifiers must agree. |
| `cosec.jwt.token-validity.access` | duration | `10m` | Access token lifetime. |
| `cosec.jwt.token-validity.refresh` | duration | `7d` | Refresh token lifetime. |
| `cosec.jwt.token-revocation.enabled` | boolean | `false` | Check every token against the revocation store, so logout takes effect at once (needs `cache-support`). See [Logout and revocation](../integration/authentication.md#logout-and-revocation). |

## API keys

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.api-key.header` | string | `X-API-Key` | Request header that carries the key. |
| `cosec.api-key.store` | `memory`, `redis` | `memory` | `memory`: the keys configured below. `redis`: keys shared by every instance (needs `cache-support`). |
| `cosec.api-key.key-prefix` | string | `cosec:api-key` | Redis key prefix of the `redis` store ([Redis keys](./storage.md#redis-keys)). Independent of `cosec.authorization.cache.key-prefix`. |
| `cosec.api-key.store-blocking` | boolean | `false` | Parse the security context off the event loop, for a custom `ApiKeyStore` that blocks. The `redis` store always does. |
| `cosec.api-key.keys[n].*` | list | empty | Keys of the `memory` store; ignored with `redis`. |

Each entry of `cosec.api-key.keys`:

| Field | Type | Default | Meaning |
|---|---|---|---|
| `id` | string | — | **Required.** The key's id. |
| `hash` | string | — | **Required.** SHA-256 of the key, 64 lowercase hex characters. Never configure the key itself. |
| `principal-id` | string | — | **Required.** The principal the key authenticates as. |
| `tenant-id` | string | none | Tenant of the principal. |
| `roles` | set | empty | Roles, `role@space` for a non-default space. |
| `policies` | set | empty | Policy ids of the principal. |
| `attributes` | map | empty | Principal attributes. |
| `expires-at` | instant | none | After this instant the key is rejected. |
| `enabled` | boolean | `true` | A disabled key is rejected. |

## Cache

With `cache-support`, policies, app permissions, role grants and revoked tokens live in Redis and are cached in
process ([Storage](./storage.md)).

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.authorization.cache.key-prefix` | string | `cosec` | Prefix of every CoSec cache key and of the schema marker, with a trailing `:` removed; also the start of the policy history keys, untrimmed ([Redis keys](./storage.md#redis-keys)). |
| `cosec.authorization.cache.policy.*` | [local cache settings](#local-cache-settings) | CoCache's defaults | Local copy of policies (`PolicyCache`). |
| `cosec.authorization.cache.role.*` | [local cache settings](#local-cache-settings) | CoCache's defaults | Local copy of role grants (`RolePermissionCache`). |
| `cosec.authorization.cache.token.*` | [local cache settings](#local-cache-settings) | `expire-after-write: 30`, `maximum-size: 100000` | Local copy of revocation lookups (`RevokedTokenCache`). |
| `cosec.authorization.cache.history.enabled` | boolean | `false` | Record a revision of every saved policy ([Policy history](./storage.md#policy-history-and-rollback)). |
| `cosec.authorization.cache.history.max-revisions` | int | `50` | Revisions kept per policy; older ones are dropped. |
| `cosec.authorization.cache.migration.legacy-key-prefix` | string | `cosec.authorization.cache.key-prefix` | The 6.x key prefix, exactly as it was configured (a trailing `:` is kept). Read by the [6.x migration](./upgrading.md#migrating-the-data) and the [schema check](./storage.md#schema-marker). |
| `cosec.authorization.cache.migration.missing-guard-sentinel` | string | `_nil_` | `cocache.redis.missing-guard-sentinel` of the 6.x deployment: the value 6.x stored for a cached miss. |
| `cosec.authorization.cache.migration.backup-prefix` | string | `cocache5-backup:` | Where the CoCache migration backs up each 6.x value it converts, for a rollback. |
| `cosec.authorization.cache.migration.check-schema` | boolean | `true` | Refuse to start while CoSec data exists in Redis without the 7.0 schema marker. Set `false` only in the one-off job that runs the migration. |

`AppPermissionCache` and `GlobalPolicyIndexCache` have no `cosec.*` settings: tune them with
`cocache.caches.AppPermissionCache.*` and `cocache.caches.GlobalPolicyIndexCache.*`.

### Local cache settings

`policy`, `role` and `token` each accept:

| Property | Type | Default | Effect |
|---|---|---|---|
| `maximum-size` | long | CoCache's, 10,000 (`token`: `100000`) | Maximum entries in the local copy. |
| `initial-capacity` | int | CoCache's | Initial capacity of the local copy. |
| `expire-after-write` | long, in `expire-unit` | CoCache's, 5 minutes (`token`: `30`) | How long a local copy lives at most (CoCache's `localMaxAge`): the bound on staleness if an invalidation message is lost. It cannot be unlimited. |
| `expire-after-access` | long, in `expire-unit` | none | Drop a local copy not read for this long. |
| `expire-unit` | `TimeUnit` | `SECONDS` | Unit of the two expiry settings. |
| `concurrency-level` | int | — | Deprecated and ignored: Caffeine has no concurrency level. |

These settings size and age the local copy only. Store-of-record entries never expire in Redis, whatever is set here.

## Rate limiting

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.limiter.locations` | set of resource patterns | `classpath:cosec-rate-limit/*-rate-limit.json` | Rate limit rule files, each holding one rule or an array of rules. A location that matches no file, other than the default, stops startup, and so does a rule id declared twice. |
| `cosec.limiter.key-prefix` | string | `cosec:rate-limiter` | Redis key prefix of the counters, when Redis counts ([Redis keys](./storage.md#redis-keys)). Independent of `cosec.authorization.cache.key-prefix`. |

Rules themselves are described in [Rate limiting](../policies/rate-limiting.md#rules).

## Shadow mode

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.shadow.locations` | set of resource patterns | `classpath:cosec-shadow/*-policy.json` | Candidate policy files, one policy per file. A location that matches no file, other than the default, stops startup, and so do two files with the same policy id. |
| `cosec.shadow.app-permission-locations` | set of resource patterns | `classpath:cosec-shadow/*-app-permission.json` | Candidate app permission files, one per file. Same rules as above. |
| `cosec.shadow.sample-rate` | double, `0` to `1` | `1.0` | Share of requests also evaluated against the candidates. Each costs a second evaluation. |
| `cosec.shadow.report-rule-changes` | boolean | `false` | Also report requests whose result is the same but decided by a different rule. |

How to use it: [Trying changes in shadow mode](../policies/testing.md#trying-changes-in-shadow-mode).

## Audit

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.audit.kafka.topic` | string | `cosec-audit` | Kafka topic of the audit events. |

Without Kafka, events go to the log. Where events go and what they hold: [Audit events](./observability.md#audit-events).

## Social login

`cosec.authentication.social.registration.<name>.*` configures one OAuth provider per `<name>`:

```yaml
cosec:
  authentication:
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
`ding-talk-exclusive-corp-id`. Booleans default to `false`, the others to none. How social login works:
[Social login](../integration/authentication.md#social-login).

## CoCache properties that affect CoSec

CoSec creates its caches on CoCache's runtime under the names `PolicyCache`, `GlobalPolicyIndexCache`,
`AppPermissionCache`, `RolePermissionCache` and `RevokedTokenCache`. CoCache's own properties apply to them, with the
exceptions below. The full list is in [CoCache's documentation](https://cocache.ahoo.me).

| Property | Effect on CoSec |
|---|---|
| `cocache.backend` | `redis` (default) or `memory`. With `memory` there is no Redis, no cross-instance consistency and no [schema check](./storage.md#schema-marker): local development and tests only. |
| `cocache.defaults.*` | Policy of every CoCache cache, CoSec's included, for example `local-max-age` or `local-maximum-size`. |
| `cocache.caches.<CacheName>.*` | Policy of one cache, by the names above. The only way to tune `AppPermissionCache` and `GlobalPolicyIndexCache`. |
| `cocache.defaults.lease-ttl` | How long the lease marker left by reading a missing value lives (10 seconds by default). |
| `cocache.redis.channel-mode` | `pubsub` or `sharded` invalidation; every instance must agree. |
| `cocache.redis.set-cache-eviction-check` | What CoCache does at startup when Redis' `maxmemory-policy` can evict the global policy index ([Redis requirements](./storage.md#redis-requirements)). |
| `spring.data.redis.*` | The Redis that CoCache, and so CoSec, connects to. CoCache builds its own Lettuce client from it. |

CoCache 6 renamed or removed several 5.x properties, for example `cocache.redis.strict-failure` and
`cocache.redis.missing-guard-sentinel`. A removed property fails startup and names its replacement.

## Precedence

For each CoSec cache, the policy is assembled in this order; a later step wins over an earlier one:

1. CoSec's default for the cache, with `cosec.authorization.cache.policy`, `role` or `token` applied;
2. the application's CoCache `CacheSpecCustomizer` beans;
3. `cocache.defaults.*`, then `cocache.caches.<CacheName>.*`;
4. CoSec's fixed settings, applied last and impossible to override.

So a `cocache.*` property wins over the matching `cosec.authorization.cache.*` one: for example
`cocache.caches.PolicyCache.local-max-age` wins over `cosec.authorization.cache.policy.expire-after-write`.

The fixed settings of step 4 are:

| Cache | Fixed | Why |
|---|---|---|
| `PolicyCache`, `GlobalPolicyIndexCache`, `AppPermissionCache`, `RolePermissionCache` | `ttl` none, `read-failure: throw`, `foreign-format: fail` | They are the store of record ([Storage](./storage.md#redis-is-the-store-of-record)): an expiry, a degraded read or a reclaim would silently drop a policy or a grant. |
| `RevokedTokenCache` | `foreign-format: fail` | Reclaiming an unreadable revocation would make its token valid again. |
| every CoSec cache | key prefix, namespace and key encoder | The storage layout is set by `cosec.authorization.cache.key-prefix`. A `key-prefix` from a customizer or `cocache.*` is ignored. |
