# Configuration

All properties live under `cosec.*` and are optional unless marked otherwise. Each integration is
also gated by its Gradle capability (see [Quick Start](./quick-start.md#_1-add-the-dependency)):
a property can only enable a feature whose module is on the classpath.

## Feature switches

| Property | Default | Turns on |
|---|---|---|
| `cosec.enabled` | `true` | everything below |
| `cosec.authentication.enabled` | `true` | authentication (`TokenCompositeAuthentication` and your `Authentication` beans) |
| `cosec.authentication.social.enabled` | `true` | social login (`oauth-support`) |
| `cosec.authorization.enabled` | `true` | authorization filters for WebFlux / WebMvc |
| `cosec.authorization.gateway.enabled` | `true` | the Spring Cloud Gateway filter (`gateway-support`) |
| `cosec.authorization.cache.enabled` | `true` | Redis-backed policy and permission repositories (`cache-support`) |
| `cosec.jwt.enabled` | `true` | JWT issuing and verification |
| `cosec.inject.enabled` | `false` | trusting a token already verified upstream (see [Authentication](./authentication.md#behind-a-gateway)) |
| `cosec.audit.enabled` | `true` | audit events for every decision |
| `cosec.shadow.enabled` | `false` | [shadow evaluation of candidate policies](./policies.md#trying-changes-in-shadow-mode) |
| `cosec.explain.enabled` | `false` | [explaining decisions](./troubleshooting.md#explain-a-decision) and the `cosecExplain` Actuator endpoint |
| `cosec.audit.kafka.enabled` | `true` | sending audit events to Kafka (`kafka-support` and a `KafkaTemplate` bean) |
| `cosec.limiter.enabled` | `true` | [rate limit rules](./rate-limiting.md) |
| `cosec.ip2region.enabled` | `true` | IP geolocation (`ip2region-support`) |
| `cosec.openapi.enabled` | `true` | OpenAPI integration (`openapi-support`) |

OpenTelemetry tracing has no switch: it is active whenever `opentelemetry-support` is on the classpath.

## Authorization

| Property | Default | Description |
|---|---|---|
| `cosec.authorization.allow-query-token` | `false` | Also accept the access token from the `authorization` query parameter. Off by default because URLs leak into access logs, browser history and `Referer` headers. |
| `cosec.authorization.root-enabled` | `true` | Whether the root principal bypasses all checks. The root id is `cosec`, or the value of the `-Dcosec.root` system property. Disable it where users can choose their own principal ids, or provide a `RootPrincipalMatcher` bean. |
| `cosec.authorization.remote-ip.max-trusted-index` | `1` | How many proxy hops to trust in `X-Forwarded-For` when resolving the client IP. `0` ignores the header (the service is exposed directly). `N` takes the entry appended by the N-th closest proxy. |
| `cosec.authorization.local-policy.enabled` | `false` | Load policies from local JSON files. |
| `cosec.authorization.local-policy.locations` | `classpath:cosec-policy/*-policy.json` | Resource patterns to load. |
| `cosec.authorization.local-policy.init-repository` | `false` | Write loaded policies into the `PolicyRepository` on startup. |
| `cosec.authorization.local-policy.force-refresh` | `false` | Overwrite policies that already exist in the repository. |

## JWT

| Property | Default | Description |
|---|---|---|
| `cosec.jwt.secret` | — | **Required** while JWT is enabled; startup fails if blank. Use a long random value. |
| `cosec.jwt.algorithm` | `hmac256` | `hmac256`, `hmac384` or `hmac512`. |
| `cosec.jwt.token-validity.access` | `10m` | Access token lifetime. |
| `cosec.jwt.token-validity.refresh` | `7d` | Refresh token lifetime. |
| `cosec.jwt.token-revocation.enabled` | `false` | Check every token against a revocation store, so logout takes effect immediately (needs `cache-support`). See [Authentication](./authentication.md#logout-and-revocation). |

## Cache

With `cache-support`, policies, app permissions, role grants and revoked tokens are stored in Redis and
cached in-process.

| Property | Default | Description |
|---|---|---|
| `cosec.authorization.cache.key-prefix` | `cosec` | Prefix for all Redis keys (`cosec:policy:*`, `cosec:global:policy`, `cosec:app:permission:*`, `cosec:role:permission:*`, `cosec:token:revoked:*`). |
| `cosec.authorization.cache.policy.*` | | Local cache for policies. |
| `cosec.authorization.cache.role.*` | | Local cache for role grants. |
| `cosec.authorization.cache.token.*` | `expire-after-write: 30`, `maximum-size: 100000` | Local cache for revocation lookups. |

Each local cache (`policy`, `role`, `token`) accepts:

| Property | Default | Description |
|---|---|---|
| `maximum-size` | `10000` | Maximum entries in the local cache. |
| `initial-capacity` | — | Initial capacity. |
| `expire-after-write` / `expire-after-access` | — | Local expiry, in `expire-unit`. |
| `expire-unit` | `SECONDS` | Time unit for the expiry settings. |
| `concurrency-level` | — | Deprecated and ignored (Caffeine has no concurrency level). |

Local expiry only bounds how long a stale copy can survive if an invalidation message is lost; changes
are pushed to every instance immediately. Redis failures on policy and permission reads deny the request.
See [Operations](./operations.md#redis-and-caching).

## Rate limiting

| Property | Default | Description |
|---|---|---|
| `cosec.limiter.locations` | `classpath:cosec-rate-limit/*-rate-limit.json` | Resource patterns of the [rate limit rule](./rate-limiting.md#rules) files. |
| `cosec.limiter.key-prefix` | `cosec:rate-limiter` | Redis key prefix of the counters, when Redis counts (`cache-support`). |

## Shadow mode

| Property | Default | Description |
|---|---|---|
| `cosec.shadow.locations` | `classpath:cosec-shadow/*-policy.json` | Resource patterns of the candidate policy files, one policy per file. |
| `cosec.shadow.sample-rate` | `1.0` | Share of requests evaluated against the candidates, from `0` to `1`. Each costs a second evaluation. |
| `cosec.shadow.report-rule-changes` | `false` | Also report requests with the same result but a different deciding rule. |

## Audit

| Property | Default | Description |
|---|---|---|
| `cosec.audit.kafka.topic` | `cosec-audit` | Kafka topic for audit events. |

Without Kafka, audit events are written to the log. Provide your own `AuditEventSink` bean to send them
elsewhere.

## Social login

`cosec.authentication.social.registration.<provider>` configures each OAuth provider:

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

Other fields (`scopes`, `pkce`, `ignore-check-state`, provider-specific keys such as
`alipay-public-key` or `ding-talk-corp-id`, …) map one-to-one to JustAuth's `AuthConfig`.
