---
title: "Configuration"
description: "Every cosec.* property, its type, its default and what it changes."
---

# Configuration

For developers and operators tuning a deployment. This page is the single reference for the `cosec.*` properties the
starter binds, and for the `cocache.*` properties that change how CoSec behaves; other pages name a property and link
here for its type and default.

Every property is optional unless marked **required**. A property only acts when the module it configures is on the
classpath: each integration is a module the application adds next to the starter
([Modules](../guide/introduction.md#modules)). Durations take
Spring's format (`500ms`, `10s`, `7d`), data sizes `16MB`; sets and lists take a comma-separated value or a YAML list;
enum values are case-insensitive. Properties that 7.x had and 8.0 does not are listed in
[Upgrading](./upgrading.md#removed-and-renamed-properties).

## Unknown properties

Startup fails on a `cosec.*` key CoSec does not read, whatever else is configured (`cosec.enabled=false` included): a
misspelled, renamed or removed key would otherwise do nothing. A key is known when the configuration metadata on the
classpath (`META-INF/spring-configuration-metadata.json`) declares it, or when it lies under a map- or list-valued
property. The message lists every unknown key and links
[the table of removed and renamed keys](./upgrading.md#removed-and-renamed-properties). No property turns this check
off: a key prefix a release replaced, had it been ignored, would leave token revocations where nothing reads them.
The check runs before any bean is created, the application's own included, so a configuration that still sets
removed keys hears of every one of them before any other startup failure, such as a missing token store.

This check, and every other startup check of the starter (missing modules, exposed actuator endpoints on a gateway,
Redis's eviction policy, replica reads, API keys without a `MembershipProvider`), runs with
`spring.main.lazy-initialization=true` as well (`STA-3`): no bean asks for a check, and a lazily initialized
application would otherwise skip them all.

Keys are compared as Spring's binder compares them, so every spelling it documents for a known key is known:
`local-max-age`, `localMaxAge` or `local_max_age` in a properties file, and `COSEC_AUTHORIZATION_SNAPSHOT_LOCALMAXAGE`
as an environment variable (upper case, dots as underscores, dashes dropped). A variable that is not that spelling of a
key CoSec reads stays unknown, a typo (`COSEC_PIPELINE_TIMEUOT`) and the legacy `COSEC_AUTHORIZATION_SNAPSHOT_LOCAL_MAX_AGE`
alike: the message names it as `cosec.authorization.snapshot.local.max.age`.

One thing is never an unknown key: the variables Kubernetes injects for a Service named `cosec` or `cosec-*` (service
links), which Spring reads as `cosec.*` keys (`COSEC_GATEWAY_SERVICE_HOST` as `cosec.gateway.service.host`). The check
skips exactly their shapes, matched on the variable names and only in the process environment, where `<SVC>` is
`COSEC` or `COSEC_<NAME>`:

- `<SVC>_SERVICE_HOST`, `<SVC>_SERVICE_PORT` and `<SVC>_SERVICE_PORT_<PORT NAME>`;
- `<SVC>_PORT`, when the value is a `tcp://`, `udp://` or `sctp://` URL;
- `<SVC>_PORT_<port>_<TCP|UDP|SCTP>` and the same with `_PROTO`, `_PORT` or `_ADDR`.

Every other `COSEC_*` variable is checked (`COSEC_JWT_SECRT` fails startup), and so is the same key in
`application.yaml`, a system property or any other source. No CoSec property has one of these names, and a known key is
never unknown whatever its source, so the exemption cannot hide a property CoSec reads. Setting
`enableServiceLinks: false` on the pod keeps the variables out of the environment altogether
([Troubleshooting](./troubleshooting.md#startup-fails-on-an-unknown-property)).

## Feature switches

A switch accepts `true` or `false` (any case, no spaces around it), and the enums `cosec.pipeline.mode` and
`cosec.api-key.store` the values listed for them; leave a key out to take its default. Any other written value (`1`,
`on`, an empty string) fails startup, naming the key, the value and what is accepted: Spring's binder would read some
of them as true while the condition that wires the feature reads them as off
([Startup fails on an unreadable switch](./troubleshooting.md#startup-fails-on-an-unreadable-switch)). A switch written
`false` switches its feature off, and the startup summary says so
([Startup summary](./observability.md#startup-summary)).

| Property | Type | Default | Turns on |
|---|---|---|---|
| `cosec.enabled` | boolean | `true` | every CoSec auto-configuration |
| `cosec.authentication.login.enabled` | boolean | `true` | the login beans, which turn credentials into a token: `CompositeAuthentication` over your `Authentication` beans, `TokenCompositeAuthentication` with JWT, and social login. Authenticating requests in the pipeline does not depend on it |
| `cosec.authentication.login.social.enabled` | boolean | `true` | social login (`cosec-social`; needs `cosec.authentication.login.enabled`) |
| `cosec.jwt.enabled` | boolean | `true`; `false` with `cosec.pipeline.mode=gateway` | issuing, verifying and revoking JWTs. Off, the pipeline rejects every token (`token-invalid`) and treats a request without one as anonymous |
| `cosec.api-key.enabled` | boolean | `false` | [API keys](../integration/authentication.md) for machine clients. `true` or `false` only: any other value fails startup. Needs a `MembershipProvider` bean, which the `cosec-gateway-server` image does not have: as shipped it cannot use API keys ([deployment](./deployment.md#the-image)). |
| `cosec.audit.enabled` | boolean | `true` | the audit log listener, and the Kafka audit listener with `cosec.audit.kafka.enabled` |
| `cosec.audit.kafka.enabled` | boolean | `true` | the Kafka audit listener (`cosec-kafka` and a `KafkaOperations` bean) |
| `cosec.metrics.enabled` | boolean | `true` | the metrics listener and the [snapshot source's meters](./observability.md#snapshot-metrics) (`cosec-micrometer` and a `MeterRegistry` bean, Spring Boot's own auto-configured one included). Written as `true` without `cosec-micrometer`, startup fails; left unset, metrics stay optional |
| `cosec.ip2region.enabled` | boolean | `true` | the `ext.ip2region.*` attribute provider (`cosec-ip2region`) |
| `cosec.ip2region.ipv6-location` | string | none | A Spring resource location (`file:`, `classpath:`) of the IPv6 `.xdb` database, loaded beside the bundled IPv4 one. Unset, an IPv6 client's region is unavailable. A location that does not exist fails startup. |
| `cosec.openapi.enabled` | boolean | `true` | the bearer-auth customizer of the OpenAPI document (`cosec-openapi`) |
| `cosec.control.enabled` | boolean | `true` | the control plane, when `cosec-control` and `cosec-cocache` are on the classpath; its routes need a WebFlux or WebMvc application. Off, the instance runs none of it and reads the policy documents and candidates read-only ([Control plane](#control-plane)) |
| `cosec.authorization.shadow.enabled` | boolean | `true` | this instance's part in shadow windows (`cosec-cocache`); see [Shadow windows](#shadow-windows) |
| `cosec.authorization.snapshot.prewarm-host-apps` | boolean | `true` | loading the apps this host names at startup, and holding them (`SNP-20`); see [Snapshot](#snapshot) |
| `cosec.gateway.identity.enabled` | boolean | `true` | on a Spring Cloud Gateway host, forwarding the verified identity to the services behind it; see [Gateway identity](#gateway-identity) |

These have no switch:

- **Tracing** is on whenever `cosec-opentelemetry` is on the classpath ([Tracing](./observability.md#tracing)).
- **The rate limiter** is always there: on Redis when a `ReactiveStringRedisTemplate` bean exists (`cosec-cocache`),
  otherwise counted per instance ([Rate limiting](#rate-limiting)).
- **Token revocation** is always on with JWT ([Startup](./troubleshooting.md#startup-fails-without-a-token-store)).
- **The `cosecServiceGenerator` actuator endpoint** is there whenever `cosec-openapi`, springdoc and Spring Boot Actuator are;
  expose it like any actuator endpoint, and restrict it with Spring Boot's `management.endpoint.cosec-service-generator.access`
  (`unrestricted` by default).

## Key prefix

Every key a deployment writes to Redis lives under one prefix (`SNP-9`): snapshot documents, control-plane records,
shadow reports, revocations, memberships, API keys, rate limit counters and OAuth states
([Redis keys](./storage.md#redis-keys)). Two deployments on one Redis that use different prefixes share nothing.
Within one application, CoCache refuses to create a cache whose storage keys would overlap another's, so a prefix that
puts a CoSec cache under one of the application's own caches fails startup
([Troubleshooting](./troubleshooting.md#startup-fails-on-overlapping-cache-keys)).
It replaced `cosec.authorization.snapshot.key-prefix`, `cosec.authorization.cache.key-prefix` and
`cosec.api-key.key-prefix`: an environment that sets one of them fails startup, as any key CoSec does not read does
([Troubleshooting](./troubleshooting.md#startup-fails-on-a-removed-key-prefix)).

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.key-prefix` | string | `cosec` | The prefix of every Redis key, with a trailing `:` removed. Every service of one deployment, its control plane and `cosec-migrate --key-prefix` use the same value. With the Redis rate limiter, a prefix with a hash tag of its own, such as `{cosec}`, puts every rate limit counter on one Redis Cluster slot ([Rate limiting](../policies/rate-limiting.md)). |

## Pipeline

`cosec.pipeline.*`: how one request is read and bounded.

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.pipeline.timeout` | duration | `5s` | The whole evaluation of one request. Past it the request is answered `503` `unavailable`. |
| `cosec.pipeline.authentication-timeout` | duration | `1s` | One call of the `Authenticator`. Past it, `503`. |
| `cosec.pipeline.allow-query-token` | boolean | `false` | Also read the access token from the `authorization` query parameter. Off because URLs end up in access logs, browser history and `Referer` headers. |
| `cosec.pipeline.allow-cookie-token` | boolean | `false` | Also read the access token from the `authorization` cookie. Off because a browser sends a cookie on its own, on a cross-site request too: turn it on only with your own CSRF defense ([Authentication](../integration/authentication.md)). |
| `cosec.pipeline.api-key-header` | string | `X-API-Key` | The request header an API key comes in. No document may read it: every compiler of the host refuses a reference to it, as to the other credential headers ([Headers](../policies/language.md#headers)). |
| `cosec.pipeline.trace-level` | `off`, `full`, `exhaustive` | `off` | How much of each evaluation the engine traces. Decision events carry the trace as `evaluationTrace` (null at `off`, and when the request did not reach the engine); a higher level costs time on every request. |
| `cosec.pipeline.mode` | `enforce`, `gateway` | `enforce` | `enforce`: the host decides with policies. `gateway`: a service behind a CoSec gateway reads the caller from the `CoSec-Identity` header the gateway forwards, and wires no authenticator, API keys, snapshot, policies, control plane or Redis of its own ([Services behind the gateway](../integration/web.md#services-behind-the-gateway)). Any other value fails startup, and so does `gateway` on a Spring Cloud Gateway host. In `gateway` mode only the gateway may be able to reach the service, and startup fails until `cosec.pipeline.identity.only-gateway-can-reach` says so. In `gateway` mode `cosec.jwt.enabled` defaults to `false`: a service that also issues tokens sets it to `true`. |
| `cosec.pipeline.caller` | `credential`, `forwarded-identity` | `credential` | Where the caller of a request comes from on a host that decides with policies (`enforce`). `credential`: the token or API key the request carries, verified here. `forwarded-identity`: the `CoSec-Identity` header the gateway, or a service the operator trusts, states, believed as `gateway` mode believes it, with everything `enforce` wires (the snapshot, the control plane): it is what a control-plane host, or an IAM service that embeds the control plane, runs. The host then verifies no credential (set `cosec.jwt.enabled=false` for it to hold no signing secret), reads the token tables only for the revocations of an approver, and **starts only under the statement of what can reach it** (`cosec.pipeline.identity.only-gateway-can-reach` or `only-trusted-callers-can-reach`): it believes whoever reaches it, contains mistakes and keeps the audit, and does not defend against a compromised workload that can reach it (`STA-9`). It fails startup on a Spring Cloud Gateway host, and with a hidden-method filter, as `gateway` mode does. On such a host the web adapter does not decide a request, as in `gateway` mode: the gateway decided what came through it, and for a call that did not come through the gateway only the control plane's own authorization of each operation, as the principal the caller states, decides. A route of the application's own on such a host (the IAM service of the embedded deployment) is authorized at the gateway or not at all. |
| `cosec.pipeline.identity.only-gateway-can-reach` | boolean | none, required | In `gateway` mode, or with `cosec.pipeline.caller=forwarded-identity`, your statement that nothing but the gateway can reach this host (a NetworkPolicy that admits the gateway's pods, mutual TLS, a private network): it believes any `CoSec-Identity`, which has no signature. The host does not start unless this or `only-trusted-callers-can-reach` is `true`; absent, empty, `false`, `1` and `on` fail alike, with a message that says what to write. With it, startup logs one `INFO` line. A host with such a gate also fails to start with a hidden-method filter (`spring.mvc.hiddenmethod.filter.enabled`, `spring.webflux.hiddenmethod.filter.enabled`, or a `HiddenHttpMethodFilter` bean) ([Services behind the gateway](./deployment.md#services-behind-the-gateway)). |
| `cosec.pipeline.identity.only-trusted-callers-can-reach` | boolean | none | The statement of a host that services call directly, such as a control-plane host: nothing but the gateway and the services you name can reach it (a NetworkPolicy per port that admits those pods, mutual TLS, a private network). It satisfies the same check as `only-gateway-can-reach`, and the host's startup message names both. It is not a defence: a workload that can reach the host can state any principal, a platform administrator included, and be both the author and the approver of a change. The network is the boundary. |
| `cosec.pipeline.identity.max-age` | duration | `60s` | In `gateway` mode, an identity issued longer ago than this, plus `cosec.authentication.revocation.skew`, is refused: `401` `token-expired`. |
| `cosec.pipeline.identity.max-size` | data size | `8KB` | In `gateway` mode, a larger `CoSec-Identity` header is refused: `400` `invalid-request`. |

## Authorization

`cosec.authorization.*`: the host's scope and the per-stage timeouts.

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.authorization.scope.app-id` | string | none | The app this host serves. A credential that declares another app, or none, is rejected (`403` `scope-rejected`). Unset, the credential's app applies ([Apps](../integration/authentication.md#apps)). On a gateway, a route's metadata `cosec-app-id` takes precedence for that route's requests ([Gateway](../integration/web.md#gateway)). |
| `cosec.authorization.remote-ip.max-trusted-index` | int | `1` | How many proxy hops of `X-Forwarded-For` to trust when resolving the client IP. `0` ignores the header (the host is exposed directly); `N` takes the entry the N-th closest proxy appended. Under the `native` forward-headers strategy, set explicitly on Tomcat or `0` on Reactor Netty or Jetty, startup fails, and so does any other server or none (a war in an external container); `framework` always fails startup ([Client IP behind proxies](../integration/web.md#client-ip-behind-proxies), `PIP-16`). |
| `cosec.authorization.membership.timeout` | duration | `500ms` | One call of the `MembershipProvider`. Past it, or when it fails, `503`. |
| `cosec.authorization.membership.unknown-ttl` | duration | `5s` | How long an instance remembers that the `MembershipLoader` did not know a principal (the token of a removed member, still valid), so that its requests do not reach the loader each time. `0s` remembers nothing; at most `1m`, the default life of a cached membership, and a longer value fails startup. A principal you add is known by every instance when it ends, and at once on the instance where `invalidate` ran. A failed or timed-out load is never remembered ([Memberships](../integration/authentication.md#memberships)). |
| `cosec.authorization.attributes.timeout` | duration | `300ms` | One call of an `AttributeProvider` that sets no timeout of its own. Past it, its attributes are unavailable, and a statement that needs them makes the decision Indeterminate. |
| `cosec.authorization.routes.location` | comma-separated resource locations | unset | The route maps a WebFlux or WebMvc host loads at startup, each logged with its service and route count. A location that does not exist, a map that does not parse, or a map with no routes fails startup. Unset, no route map is loaded and every request is authorized in the `http` namespace, unless `classpath:cosec-routes.json` exists: startup then fails until the property names it or is set empty. Empty runs without a route map on purpose. A Spring Cloud Gateway host ignores it and loads no route map. |

## Gateway

`cosec.gateway.*`, on a Spring Cloud Gateway host (`cosec-gateway`) that decides with policies.

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.gateway.unprotected-endpoints` | set of endpoint ids | empty | Actuator endpoints that may be exposed over HTTP on the gateway's application port, where CoSec does not authorize them, because something else protects them. Any other one but `health` fails startup there (`STA-2`); `gateway`, which changes routes, is never accepted. On a separate `management.server.port` any endpoint may be exposed ([Actuator on a gateway](../integration/web.md#actuator-on-a-gateway)). |

## Gateway identity

`cosec.gateway.identity.*`, on a Spring Cloud Gateway host (`cosec-gateway`): what the gateway forwards to the
services behind it. On a permit with a principal it sets `CoSec-Identity` to the identity it verified (format:
[`schema/8.0/cosec-identity.schema.json`](https://cosec.ahoo.me/schema/8.0/cosec-identity.schema.json)),
on a route that receives it: a route to a registered service (`lb://`), or one with the metadata
`cosec-identity: true`. A route with `cosec-identity: false`, or to any other URI without the metadata, receives no
identity ([Forwarding the identity](../integration/web.md#forwarding-the-identity)). The gateway always removes a
`CoSec-Identity` the client sent, whatever these properties, the mode or `cosec.enabled` say.

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.gateway.identity.enabled` | boolean | `true` | Forward the verified identity to the routes that receive it. Off, the gateway still strips the client's. |
| `cosec.gateway.identity.claims` | set of attribute names | empty | Principal attributes forwarded with the identity as `claims`, such as `name` and `email` (for a JWT, keys of its `attributes` claim, not top-level claims): strings of at most 1024 characters, numbers and booleans. Any other value fails the request (`500`). |
| `cosec.gateway.identity.max-size` | data size | `8KB` | A larger encoded identity fails the request closed (`500`) rather than being cut. |
| `cosec.gateway.identity.forward-authorization` | boolean | `false` | Whether the client's credentials still go downstream: the `Authorization` header, the API key header (`cosec.pipeline.api-key-header`), the `authorization` query parameter and the `authorization` cookie. Off, the gateway removes them from every request it forwards (the cookie from each `Cookie` header, other cookies kept): a service behind it needs none. On, they go only to the routes that would receive the identity (`lb://`, or `cosec-identity: true`), whether or not `cosec.gateway.identity.enabled` is on, and only when the pipeline accepted them: a rejected token (expired, revoked, invalid) stays at the gateway even when a public route lets the request through as anonymous. Turn it on only for services that verify the token again or call other APIs on the user's behalf. |

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
| `cosec.authorization.snapshot.local-max-age` | duration | `30s` | How long CoCache's local copy of a document may serve a cold load before Redis is asked again. It applies whatever a cache customizer or `cocache.*` property sets. |
| `cosec.authorization.snapshot.revalidate-interval` | duration | `10s` | How often a pass starts that confirms every installed document against Redis with a conditional read; while a pass is still in flight, the next starts as soon as it ends, never overlapped. A change whose invalidation message was lost is installed by the first pass that reaches its document after the change is readable (`SNP-6`). At least `1s`, at most `5m`, less than `lag-warn` and, with `fail-closed.tenants`, less than `fail-closed.max-lag`; anything else fails startup. Each pass costs Redis a few microseconds per document, whatever its size ([Storage](./storage.md#what-revalidation-costs-redis)). |
| `cosec.authorization.snapshot.lag-warn` | duration | `60s` | A document not confirmed for longer is logged at `WARN`. Must not exceed `lag-escalate`. |
| `cosec.authorization.snapshot.lag-escalate` | duration | `5m` | A document not confirmed for longer is logged at `ERROR` as stale. |
| `cosec.authorization.snapshot.fail-closed.tenants` | set of tenant ids | empty | Tenants that must not be decided on old documents: when the oldest document a request of such a tenant reads was confirmed longer ago than `max-lag`, the request is `503` (`SourceUnavailable`) unless a DENY matches. `*` names every tenant: a host with more tenants than anyone lists chooses freshness over availability for all of them. Other tenants keep deciding on what is installed. |
| `cosec.authorization.snapshot.fail-closed.max-lag` | duration | `30m` | The lag beyond which the tenants above fail closed. Not negative. |
| `cosec.authorization.snapshot.cold-load-timeout` | duration | `2s` | How long a request waits for a document that is not installed. Past it the document counts as failed for that request. Must be positive. |
| `cosec.authorization.snapshot.max-apps` | int | `1024` | The most app documents held in memory (positive); the least recently used one is dropped beyond it, and loaded again when needed. Under a burst of cold loads a family may for a moment hold more: at most twice this bound, and never more than 4,096 above it, plus the documents being loaded at that moment; beyond that the thread that loaded the document evicts up to 64 of them before it answers, and housekeeping the rest ([Storage](./storage.md#how-instances-read-documents)). |
| `cosec.authorization.snapshot.max-tenants` | int | `16384` | The same for tenant documents (positive). The platform document is never dropped. Sized so that an instance holds the tenants it serves: a tenant document of 5 roles of 20 permissions and 20 bindings retains about 30 KB, so all 16,384 places take about 490 MB of heap ([Sizing a SaaS gateway](./deployment.md#sizing-a-saas-gateway)). |
| `cosec.authorization.snapshot.max-absent-apps` | int | `256` | The most app ids held as having no app document (positive), apart from `max-apps` (`SNP-7`): app ids come from requests, so a client naming made-up apps cannot push out a real one, and each id it names beyond this is read from Redis again. |
| `cosec.authorization.snapshot.max-absent-tenants` | int | `65536` | The most tenant ids held as having no tenant document (positive), apart from `max-tenants` (`SNP-7`). Every request of such a tenant reads this entry; one dropped is read from Redis again on the tenant's next request. About 470 bytes each: about 30 MB when every place is used. |
| `cosec.authorization.snapshot.reload-concurrency` | int | `16` | Documents a revalidation pass confirms at once (positive). A pass takes about the entries held × one conditional read ÷ this: 80,000 entries at 1 ms a read take 25 s at 4, 12.6 s at 8 and 6.3 s at 16 (measured); 16 keeps a pass of the default bounds full within the default interval for reads up to about 2 ms. The average read rate is unchanged, entries ÷ `revalidate-interval`; only the burst during a pass is higher. Past `revalidate-interval`, passes run back to back and lag grows with them. |
| `cosec.authorization.snapshot.prewarm-apps` | list of app ids | empty | App documents loaded at startup, after the platform document, which is always loaded. Readiness refuses traffic until the platform document and every listed document are installed; liveness is not affected. |
| `cosec.authorization.snapshot.prewarm-tenants` | list of tenant ids | empty | Tenant documents loaded at startup, as above. |
| `cosec.authorization.snapshot.prewarm-host-apps` | boolean | `true` | Load the apps this host names at startup, as `prewarm-apps` are, and hold them (`SNP-20`): its `cosec.authorization.scope.app-id` and, on a gateway, the `cosec-app-id` of every route. Readiness waits for each until Redis answers with a document or with none. When the gateway's routes are refreshed, an app a route names for the first time is loaded then, off the request path. A held app document is replaced within seconds of a publish, by the invalidation message or the next revalidation pass, as the platform document is. Off, these apps load on their first request; `prewarm-apps` keeps working either way. |
| `cosec.authorization.snapshot.compile-threads` | int | `2` | Threads that compile documents. Must be positive. |
| `cosec.authorization.snapshot.eviction-check` | `fail`, `warn` | `fail` | What startup does when Redis' `maxmemory-policy` is not `noeviction` on every node, or cannot be read (a failed or denied `INFO memory`, a wrapped or custom CoCache backend). `fail` stops startup. `warn` starts and logs a `WARN` that begins with `!!!` ([Redis requirements](./storage.md#redis-requirements)). The in-memory CoCache backend always passes. Either way the instance reads the policy again every `revalidate-interval`, and the `cosecSnapshotEviction` health indicator is `DOWN` while Redis may evict; a policy it cannot read is `DOWN` under `fail` and `UP` with `eviction: unknown` under `warn` ([Troubleshooting](./troubleshooting.md#cosecsnapshoteviction-is-down)). |

## Shadow windows

`cosec.authorization.shadow.*`: the data plane's part in the control plane's shadow windows. It needs `cosec-cocache`.
How windows work: [Testing changes](../policies/testing.md).

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.authorization.shadow.enabled` | boolean | `true` | Evaluate sampled requests again on the staged candidates, and write this instance's reports and status to Redis. Off, this instance's traffic counts for nothing in a window, a candidate it cannot compile does not block one, and it writes no status for the propagation view. |
| `cosec.authorization.shadow.sample-rate` | double, `0` to `1` | `0.1` | Share of requests evaluated again on the candidates, off the request path. |
| `cosec.authorization.shadow.report-interval` | duration | `10s` | How often the instance writes its reports and its status. At least `1ms`: a shorter interval fails startup. |
| `cosec.authorization.shadow.instance-id` | string | a random UUID | The prefix of this instance's id in reports and statuses: the id is `<instance-id>.<suffix>`, with a suffix new at every start, so the id is unique per process start. A restarted process therefore never replaces the reports of the one before it, whose `CANDIDATE_ERROR` would otherwise vanish and unblock a window. An ACL confines an instance to its keys with `<instance-id>.*` ([Storage](./storage.md#who-may-write-which-keys)). Only letters, digits, `.`, `_` and `-`, since it ends a Redis key. Any other id fails startup. |

## Authentication

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.authentication.revocation.skew` | duration | `5s` | How far an issuer's clock may run ahead of this instance's. A token issued up to this long after a subject revocation is still revoked by it, and a token's issue time (`iat`) and not-before time (`nbf`) may be this much later than this instance's clock (`IDN-29`); a token's expiry gets no such room. |
| `cosec.authentication.revocation.fail-closed` | boolean | `false` | Whether a request's token is refused (`503`) while the revocations cannot be read, instead of accepted ([the documented exception](../guide/security-model.md#the-two-documented-exceptions)). A refresh fails then either way. |
| `cosec.authentication.revocation.read-timeout` | duration | `300ms` | How long each read of CoSec's own revocation reader (the CoCache one, over the revoked-token and revoked-subject caches) may take. Past it Redis counts as unreadable: a request's token is accepted (or refused, with `fail-closed`) and a refresh fails. Positive, and twice it (a token's two reads) less than `cosec.pipeline.authentication-timeout`, or startup fails naming both: otherwise a Redis that hangs makes every authenticated request `503`. An application's own `RevocationReader` bounds its reads itself ([Extending](../architecture/extending.md)). |
| `cosec.authentication.token-cache.enabled` | boolean | `true` | Whether a request's token, once CoSec's own `JwtTokenVerifier` verified it, is answered from memory until it expires instead of having its signature checked again ([the verified-token cache](../integration/authentication.md#the-verified-token-cache), `IDN-32`). The revocation and reserved-attribute checks run on every request either way. Never for a `TokenVerifier` bean of your own. |
| `cosec.authentication.token-cache.max-entries` | integer | `16384` | The most verified tokens kept; one more evicts one, which costs one verification when it comes again, never another answer. An entry of a large token (a dozen roles in four spaces, eight policies) retains about 7.3 KB, so the default holds at most about 115 MB, under a tenth of a 2 GB heap: size it to the tokens in use at once on one instance. |
| `cosec.authentication.login.refresh.path` | string | unset | Where the starter serves the refresh route, `POST` of the pair (`IDN-23`, [Building a client](../integration/clients.md#what-your-application-serves)). Unset, none is served. A document must permit an anonymous `POST` of it. Set on a gateway, on a host that serves no HTTP, with login or JWT off, or to anything but one plain absolute path, startup fails. |
| `cosec.authentication.login.logout.path` | string | unset | Where the starter serves the logout route, `POST` of the pair, `204` (`IDN-24`); as `refresh.path` otherwise, and not the same path. |

## JWT

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.jwt.secret` | string | none | **Required** while JWT is enabled; startup fails when it is blank or shorter than the algorithm needs: 32 bytes (UTF-8) for `hmac256`, 48 for `hmac384`, 64 for `hmac512` (RFC 7518 §3.2). Use a long random value from a secret store. HMAC is symmetric: **every process that holds the secret can sign a token for any tenant, principal and role**, the `(platform)` administrator included, so give it only to processes you would trust to issue. Rotating it invalidates every token signed with the old one: instances still on the old secret and instances on the new one each answer `401` `token-invalid` for the other's tokens until the restart ends, and every session logs in again ([Choosing a deployment](./topologies.md#what-every-topology-shares)). |
| `cosec.jwt.algorithm` | `hmac256`, `hmac384`, `hmac512` | `hmac256` | Signing algorithm. Issuers and verifiers must agree. |
| `cosec.jwt.token-validity.access` | duration | `10m` | Access token lifetime. |
| `cosec.jwt.token-validity.refresh` | duration | `7d` | Refresh token lifetime; a revocation is kept in Redis this long, plus one second. A refresh token never outlives its session. |
| `cosec.jwt.session.lifetime` | duration | `30d` | How long a session lasts from its login, however often it is refreshed ([Sessions](../integration/authentication.md#sessions)); then the user signs in again. The latest a changed role carried in the token applies. |
| `cosec.jwt.session.rotation-leeway` | duration | `30s` | How long after a refresh the pair it replaced is answered with a pair of the current generation instead of ending the session: the window a client has to retry a refresh whose response it lost, and in which two tabs refreshing at once both keep a pair. Cover your clients' timeout and one retry. A copy answered within it is caught at the next refresh after it. |
| `cosec.jwt.session.memberships-on-refresh` | string | unset | How the starter's refresh route gives a refreshed token its memberships (its roles and attached policies) when you declare no `MembershipRefresh<RefreshTokenCredentials>` bean. No default: with the route served (`cosec.authentication.login.refresh.path`), startup fails until it is `from-token` (any letter case; absent, empty, `true` and every other value fail), your statement that a refreshed token keeps the memberships of the token it replaces, so a removed role applies at the member's next sign-in, `cosec.jwt.session.lifetime` at the latest. A `MembershipRefresh` bean reads the roles again at each refresh instead, and this is not asked ([How long a removed role lives](../integration/authentication.md#how-long-a-removed-role-lives), `STA-8`). |

With JWT enabled, startup fails unless revocations and sessions can be recorded and read: `cosec-cocache`, or a
`TokenStore`, a `RevocationReader` and a `TokenFamilyStore` bean of your own. A service that only verifies the tokens
another service issues reads the issuer's revocations
([A service that verifies tokens another service issues](../scenarios/resource-server.md)).

## API keys

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.api-key.store` | `memory`, `redis` | `memory` | `memory`: the keys configured below. `redis`: keys shared by every instance, under `<cosec.key-prefix>:api-key`; needs `cosec-cocache`, a `StringRedisTemplate` and a `ReactiveStringRedisTemplate`. |
| `cosec.api-key.keys[n].*` | list | empty | Keys of the `memory` store; ignored with `redis`. |

The header is [`cosec.pipeline.api-key-header`](#pipeline). API keys need a `MembershipProvider` bean, or the
application does not start (`IDN-6`, [API keys](../integration/authentication.md#api-keys)). Each entry of
`cosec.api-key.keys`, where any other field fails startup:

| Field | Type | Default | Meaning |
|---|---|---|---|
| `id` | string | none | **Required.** The key's id; ids must be unique. |
| `hash` | string | none | **Required.** SHA-256 of the key, 64 lowercase hex characters. Never configure the key itself. Two entries with one hash fail startup: they are one key. |
| `principal-id` | string | none | **Required.** The principal the key authenticates as. |
| `tenant-id` | string | none | **Required.** Tenant of the principal. |
| `app` | string | none | The app the key belongs to ([Apps](../integration/authentication.md#apps)). Left out, it declares none, and a host with an app refuses it. |
| `roles` | set | none | The cap: the key holds only these of its principal's roles (`role`, `role@space`), and no attached policy or principal binding; `[]` leaves only what `Anyone` bindings give. Set this or `uncapped`. |
| `uncapped` | boolean | `false` | `true` for a key that holds everything its principal holds. Exactly one of `roles` and `uncapped: true` is set, or the application does not start. |
| `expires-at` | instant | none | After this instant the key is rejected. |
| `enabled` | boolean | `true` | A disabled key is rejected. |

## Cache

`cosec.authentication.revocation.cache.*`: the revoked token and revoked subject caches, created with JWT and `cosec-cocache`.
Snapshot documents have their own properties ([Snapshot](#snapshot)).

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.authentication.revocation.cache.*` | [local cache settings](#local-cache-settings) | `local-max-age: 30s`, `maximum-size: 500000` | Local copy of revocation lookups (`RevokedTokenCache` and `RevokedSubjectCache`). An entry exists only for a token or principal the instance saw in the last 30 seconds, and a miss is a Redis read on the request path. One retains about 290 bytes (a token, its id included) or 230 bytes (a principal): both tables full take about 260 MB ([Sizing a SaaS gateway](./deployment.md#what-a-replica-holds)). |

### Local cache settings

| Property | Type | Default | Effect |
|---|---|---|---|
| `maximum-size` | long | `500000` | Maximum entries in the local copy. |
| `initial-capacity` | int | CoCache's | Initial capacity of the local copy. |
| `local-max-age` | duration (a bare number is seconds) | `30s` | How long a local copy lives at most: the bound on staleness when an invalidation message is lost. |
| `expire-after-access` | duration (a bare number is seconds) | none | Drop a local copy not read for this long. |

These size and age the local copy only. A revocation's lifetime in Redis is set when it is written.

## Audit

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.audit.kafka.topic` | string | `cosec-audit` | Kafka topic of the audit events. |
| `cosec.audit.kafka.delivery` | `best-effort`, `required` | `best-effort` | How the Kafka audit listener is delivered. `required` waits for the broker's acknowledgement, so a permit that cannot be audited is a `503`; configure the producer with `acks=all`. Startup fails when it is `required` without `cosec-kafka` ([STA-1](./troubleshooting.md#startup-fails-on-a-missing-module)). |

## Decision listeners

`cosec.observability.listener.*`: the time a `REQUIRED` decision listener has, and the queues of the `BEST_EFFORT` ones
([Decision listeners](./observability.md#decision-listeners)).

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.observability.listener.required-timeout` | duration | `1s` | How long one `REQUIRED` decision listener may take. A failure or timeout turns a permit into `503`. |
| `cosec.observability.listener.queue-capacity` | int | `1024` | Events one `BEST_EFFORT` listener may have waiting. A full queue drops the next event and counts it (`cosec.listener.dropped`). |
| `cosec.observability.listener.drain-timeout` | duration | `5s` | On shutdown, how long queued events still go out; the rest is then dropped. The Kafka audit listener, and the control plane's Kafka change listener, get as long again for the sends they still hold. |

## Control plane

`cosec.control.*`, with `cosec-control` and `cosec-cocache` ([Publishing documents](./storage.md#publishing-documents)).

The control plane checks every approver for revocation as the pipeline reads it: startup fails without a
`RevocationReader` bean or CoSec's tokens with `cosec-cocache`. Declare `RevocationReader.NONE` only when no approver's
credential is ever revoked.

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.control.enabled` | boolean | `true` | Whether this instance runs the control plane, when `cosec-control` and `cosec-cocache` are on its classpath. `false` leaves out everything of it (its store, its own snapshot source, its change listeners, maintenance and routes), and the instance reads the policy documents and candidates read-only, so its Redis user needs only read permission on them ([Who may write which keys](./storage.md#who-may-write-which-keys)); it logs `Control plane off on this instance` at startup. A fleet that ships one image for both roles sets it on the data-plane role, usually as `COSEC_CONTROL_ENABLED=false`. Off, a request to a control route is decided by the pipeline like any path the application does not serve (`401`, `403`); only where a document permits that path does it get the framework's `404`. The other `cosec.control.*` keys are not refused while it is off. `true` or `false` only: any other value fails startup, and `true` written without `cosec-control` or `cosec-cocache` fails it naming the artifact (STA-1). |
| `cosec.control.analysis.route-maps` | string | unset | The services' route maps, comma-separated resource locations, read and refused as `cosec.authorization.routes.location` is. A change then carries a `ROUTES` warning listing the `http:` DENY statements and rate limit rules of its candidate that a mapped route escapes ([Route maps](../scenarios/route-maps.md)). Unset, nothing is compared, and a change with such statements says so. Each host's own warning at install needs only its own route map, not this. |
| `cosec.control.app-id` | string | `cosec-control` | The app control-plane actions are authorized in. |
| `cosec.control.http.base-path` | string | `/cosec/control` | Where the control API is served. Its routes are under `{base-path}/v1`, so moving the API keeps the version. |
| `cosec.control.http.enabled` | boolean | `true` | Whether the control API's routes are served on this instance (`CTL-58`): `false` serves none, for an application that embeds the control plane and reaches it in process. `true` or `false` only. |
| `cosec.control.http.service-port` | int | unset | A port of its own for services that call the control plane directly, serving the **service profile** of the control API (`CTL-58`): the tenant and app documents (reads, and `:apply` on a tenant's) and the change flow that needs no person (propose a tenant's document, read, rebase, abandon, run or skip the shadow window, publish). Every other route, the platform document, approvals, rollback, break-glass and the queries included, is a `404` there, as is a change of any document but a tenant's named by its id, a listing of the platform document's changes, and any path outside the control API. The control plane's facade (`ControlService`) reaches exactly these routes. It never widens what a caller may do: each route is authorized for the principal the caller states. WebFlux only; not the application's port or the management port (`0` binds one, announced as `local.control.service.port`). A host with a service port states `only-trusted-callers-can-reach=true`: `only-gateway-can-reach` fails startup there, since the port admits services. Unset, no such port. It stops by unbinding, waiting for the requests in flight up to `spring.lifecycle.timeout-per-shutdown-phase`, and closing every connection still open. |
| `cosec.control.http.max-body-size` | data size | `10MB` | The largest request body the control API reads, on WebMvc and WebFlux alike (`CTL-41`): a document's 8 MB cap and room for the operations' JSON around it. A larger body is a `400` `invalid-request`. On WebFlux it replaces `spring.codec.max-in-memory-size` for the control routes. A request without a permitted principal has no body read at all (`401`). |
| `cosec.control.maintenance.enabled` | boolean | `true` | Whether this instance runs the control plane's maintenance passes: reclaiming revisions, closing shadow windows, reconciling the app list and the staged list (`CTL-59`). `false` for an instance that only serves, such as an IAM service that embeds the control plane beside a host that runs them: the host says so once at startup (`INFO`), because a deployment where no host runs them closes no shadow window, reclaims nothing and reconciles no staged list, and nothing else says so. `true` or `false` only. |
| `cosec.control.revisions.max-count` | int | `50` | Revisions always kept per document; older ones are reclaimed once also older than `min-age`. Revisions an open change rests on are kept. |
| `cosec.control.revisions.min-age` | duration | `7d` | Revisions younger than this are always kept. |
| `cosec.control.revisions.orphan-age` | duration | `1h` | Records of conflicted publishes are removed once this old, and so is an open-change set's member whose change has no record (a change is listed before it is written), counted from when this instance first found it missing. |
| `cosec.control.revisions.reclaim-interval` | duration | `1h` | How often each control-plane instance reclaims revisions and expires the open changes of each document; a lease lets one instance at a time do each document. Must be positive. |
| `cosec.control.changes.expire-after` | duration | `7d` | An open change request expires this long after it was proposed, and leaves the list of open changes. |
| `cosec.control.approval.expire-after` | duration | `72h` | How long an approval stays valid. Startup fails when it is longer than `cosec.jwt.token-validity.refresh`, how long a token revocation is kept: an approval would outlive the revocation of its token. |
| `cosec.control.approval.four-eyes` | boolean | `false` | Whether the changes of a platform principal holding `cosec:approve` on the document (the trust root) are analyzed too: what the analysis cannot prove contained then needs another platform principal's approval. A change that escalates needs one either way. |
| `cosec.control.break-glass.max-duration` | duration | `4h` | The longest a break-glass binding may last. More than `24h`, or not positive, fails startup. |
| `cosec.control.shadow.min-duration` | duration | `15m` | A shadow window closes no earlier than this, once it also has `min-samples`. |
| `cosec.control.shadow.min-samples` | long | `1000` | Compared samples a window needs to close before `max-duration`. `0` lets a window close on time alone, with no sample at all if no request came: the shadow step then proves nothing, and startup logs a `WARN` saying so. |
| `cosec.control.shadow.max-duration` | duration | `24h` | A window closes at this age with fewer samples, as insufficient evidence the publisher must acknowledge. Must not be less than `min-duration`. |
| `cosec.control.shadow.check-interval` | duration | `1m` | How often shadowing changes are looked at, by one instance at a time; a window closes up to this late. Must be positive. |
| `cosec.control.audit.required-timeout` | duration | `5s` | How long every `REQUIRED` change listener together may take to record a gated step (publish, break-glass) before the step fails with `503`. |
| `cosec.control.audit.kafka.enabled` | boolean | `true` | The Kafka change listener (`cosec-kafka` and a `KafkaOperations` bean). |
| `cosec.control.audit.kafka.topic` | string | `cosec-control-audit` | Kafka topic of the change events. |
| `cosec.control.audit.kafka.delivery` | `best-effort`, `required` | `best-effort` | `required` makes publishing and break-glass wait for the broker. Startup fails when it is `required` and no Kafka change listener could be created. |

## Ids

`cosec.cosid.*`, with `cosec-cosid` and an application on CosId 5 ([Ids](../architecture/extending.md#ids)).

| Property | Type | Default | Effect |
|---|---|---|---|
| `cosec.cosid.generator` | string | `default` | The CosId generator whose ids CoSec takes, with its codec: request, decision event, token and change ids. A generator whose ids are not 1 to 64 characters of `A-Z a-z 0-9 . _ -` fails startup. Set without `cosec-cosid`, startup fails and names it ([Troubleshooting](./troubleshooting.md#startup-fails-on-a-missing-module)); set where CoSec's ids would not come from it (no `CosIdRuntime` bean, or an `IdGenerator` bean of the application's own), startup fails too. |

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
`ding-talk-exclusive-corp-id`. Booleans default to `false`, the others to none; `ignore-check-state: true` fails
startup, since the state is what keeps a forged callback out. `ignore-check-redirect-uri: true` starts, with a `WARN` naming the registration: it only stops JustAuth from checking that the registration's own `redirect-uri` is an http or https URL (a value you write, not one a request carries, so it guards no callback), and a custom-scheme redirect needs it. Any other unknown field fails startup, such as a misspelt `pcke`. Social login keeps its OAuth states in Redis through a
`StringRedisTemplate`.

## CoCache properties that affect CoSec

With `cosec-cocache`, CoSec creates its caches through CoCache 8's `CoCacheFactory`, so CoCache's configuration layers
reach them like any other cache. The full list of CoCache properties is in [CoCache's documentation](https://cocache.ahoo.me/guide/configuration).
Every CoCache duration needs a unit (`2s`, `10m`); a bare number fails startup.

| Property | Effect on CoSec |
|---|---|
| `cocache.backend` | `redis` (default) or `memory`. With `memory` nothing is shared between instances, the eviction checks pass, and every instance starts with no documents: local development and tests only. |
| `cocache.defaults.cache.*`, `cocache.defaults.store.*` | Fill only what a cache's declaration leaves unwritten. CoSec's caches declare what they depend on, so these change little: `MembershipCache` is a `cache`, every other CoSec cache a `store` ([Precedence](#precedence)). |
| `cocache.overrides.cache.*`, `cocache.overrides.store.*` | Tighten staleness bounds (`local-max-age`, `absent-ttl`) and `read-failure` over every cache, except for the fields CoSec locks. |
| `cocache.caches.[<CacheName>].*` | The policy of one cache, by the names above. Setting a field CoSec locks fails startup ([Precedence](#precedence)). So does a key CoCache does not know or that does not apply to that cache, usually a typo, unless `cocache.caches-validation: warn` ([Troubleshooting](./troubleshooting.md#startup-fails-on-a-locked-cache-setting)). CoSec publishes the name of every cache it may create, so an entry for one this instance does not create (a control-plane cache on a service without the control plane, `MembershipCache` without a `MembershipLoader`, a revocation table with JWT off) starts. |
| `cocache.key-prefix` | None: CoSec locks its own prefix, [`cosec.key-prefix`](#key-prefix). |
| `cocache.redis.command-timeout` | How long one Redis command may take (`2s` by default). A snapshot read that times out is a failed read ([When Redis is unavailable](./storage.md#when-redis-is-unavailable)). |
| `cocache.redis.channel-mode` | `pubsub` or `sharded` invalidation. Every instance, and the control plane, must agree. |
| `cocache.redis.assume-maxmemory-policy` | The policy CoCache's own eviction check assumes when `INFO memory` cannot be read. CoCache refuses to start with `allkeys-*`, or with an unreadable policy and this unset, because CoSec's documents and records are `Store`s; `cosec.authorization.snapshot.eviction-check=warn` does not change that ([Redis requirements](./storage.md#redis-requirements)). |
| `cocache.redis.store-eviction-check` | What CoCache does with a `volatile-*` policy and a `Store` whose policy has a finite `ttl` (the shadow caches): `warn` (default), `fail` or `off`. It counts the revocation tables too, whose entries carry a lifetime of their own, but only once the instance has written or read one, so not at startup; CoSec's own check refuses every policy but `noeviction` from the start ([Redis requirements](./storage.md#redis-requirements)). |
| `cocache.redis.store-eviction-check-interval` | How often CoCache reads the policy again, for its log and its `storeEviction` health detail. CoSec reads it again every `revalidate-interval` on its own, for `cosecSnapshotEviction`. |
| `spring.data.redis.*` | The Redis CoCache connects to, and the one the rate limiter and the Redis API key store use. |

The control plane's caches and the shadow caches are created with the same locks as the snapshot store, so the same
configuration rules apply to them.

## Precedence

Every CoSec cache is assembled by CoCache's layers, field by field; a later step wins over an earlier one:

1. CoCache's type default (`store`: no `ttl`, `local-max-age` 30 s; `cache`: `ttl` 1 h);
2. `cocache.defaults.cache.*` or `cocache.defaults.store.*`, for the fields CoSec's declaration leaves unwritten;
3. CoSec's declaration: `cosec.authorization.snapshot.local-max-age` for the snapshot store,
   `cosec.authentication.revocation.cache.*` for the revocation tables;
4. the application's CoCache `CacheSpecCustomizer` beans;
5. `cocache.overrides.*`, which only tighten;
6. `cocache.caches.[<CacheName>].*`;
7. CoSec's constraints, applied last, after `unlock` too.

A value CoSec declares wins over `cocache.defaults.*` whatever it is, one equal to CoCache's own default (a
`maximum-size` of `10000`) included. So a `cocache.caches.[<CacheName>].*` property wins over the matching `cosec.authentication.revocation.cache.*` one. CoSec
locks some fields (CoCache's `lock`): the global layers skip them, a `cocache.caches` entry that sets one fails startup
(unless the entry lists it in `unlock`, CoCache's break glass), and a customizer that changes one throws. Some are also
constrained (step 7), so even `unlock` cannot change them:

| Cache | Locked | Constrained | Why |
|---|---|---|---|
| `PolicySnapshotCache` and the control plane's and shadow window's stores of record (`RevisionRecordCache`, `RevisionIndexCache`, `ControlLeaseCache`, `ControlListCache`, `ControlAppListCache`, `ChangeRequestCache`, `StageCache`, `CandidateCache`) | `ttl`, `unknown-read`; the snapshot store also `local-max-age` | `ttl` never expires, `unknown-read: throw` | Redis is the store of record ([Storage](./storage.md#redis-is-the-store-of-record)): an expiry or a failed read taken for "absent" would silently drop a document, and with it its DENY statements. As CoCache `Store`s, they never reclaim content they cannot read, and a failed delete throws instead of being retried later, when it could delete a newer write. |
| `ReferenceIndexCache`, `ControlDocumentSetCache`, `ControlChangeSetCache`, `CandidateStagedCache` (the control plane's sets of record: the back-references, the document list, the open changes and the staged list) | `ttl`, `local-max-age`, `degraded-local-max-age` | `ttl` never expires; both local max ages 1 ns | Redis is their store of record too, and a removal check that read a local copy a lost invalidation left behind could let through a removal a referrer blocks (`CTL-42`). CoCache 8.1 offers a `SetStore` no read that skips its local copy and refuses a local max age of zero; it keeps a local expiry in milliseconds and serves a copy only while that expiry is still ahead of its clock, so a copy kept for 1 ns is never served, not even within the millisecond it was filled in. A failed read of a `SetStore` always throws. |
| `ShadowReportCache`, `ShadowReporterCache`, `InstanceStatusCache`, `InstanceIndexCache` | `unknown-read`, `ttl` | `unknown-read: throw`; `ttl` the declared 8 days (reports, reporters) or 2 minutes (statuses; the list of instances keeps the 8 days) | An outage must not read as "no samples" or "no instance", and an instance's samples must not expire from a window it reported in. |
| `MembershipCache` | `read-failure`, `unknown-read` | `ttl` finite (one minute when `forever`), `ttl-jitter: 0s`, `read-failure` and `unknown-read: throw` | A failed read must never become "no memberships", and a lost invalidation must expire. |
| `TokenFamilyCache` | `unknown-read` | `unknown-read: throw` | A session that cannot be read refuses a refresh (`503`), never reads as absent. Each entry carries its session's remaining lifetime, written with it. |
| `RevokedTokenCache`, `RevokedSubjectCache` | `read-timeout`, `unknown-read`, `absent-ttl` | `read-timeout`: the store's command timeout; `unknown-read: throw`; `absent-ttl: 30s` | A "not revoked" answer is kept locally for 30 seconds at most: a longer `absent-ttl` would accept a revoked token for that long after a lost invalidation, and `local-max-age` can only shorten it. As `Store`s they never reclaim an unreadable revocation, which would make its tokens valid again. A failed read is unknown, never "not revoked": absent would let a refresh issue a token on a read that failed (`IDN-14`). A read that times out is unavailable, which the pipeline's verification accepts, so no layer, `cocache.defaults.store.read-timeout` included, may time it out sooner. Whether verification accepts it is `cosec.authentication.revocation.fail-closed`. |
| every CoSec cache | key prefix, namespace, key encoder, codec, key filter, `ttl` function, data source | — | The storage layout is CoSec's, where every instance and the control plane look for it. |

**Next:** where the documents live and how instances read them, in [Storage and caching](./storage.md).
