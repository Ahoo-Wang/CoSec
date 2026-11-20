# CoSec 8.0 usability review, group B: the starter, configuration, operations and deployment

Read-only review. Scope as extended by the coordinator: group B is the starter, configuration **and deployment** (a section of
its own, 4b and Appendix C); the other groups are A the code surface, C the JSON policy documents with the control plane and
the migration tool, D the model, E the documentation. Nothing was built or run except one standalone probe (UB-1) against the Spring Boot 4.1.2 / Spring
Framework 7.0.9 jars of the Gradle cache, kept outside the repository. No Redis-backed test was run. Every claim cites
code (file, declaration). "Doc" means `wiki/operations/*.md`, `wiki/integration/web.md` or `wiki/guide/*.md`, read
against the code, never the other way round.

## 1. Baseline

- Baseline: `origin/main` at `8207a42651def246b938bdb434b2cbe594872cfe` (the commit this worktree started on; it contains #1655).
- Re-fetched at the end: **#1657 had merged** (squash `dd4fc214`, 2026-11-18 23:43 UTC, after my baseline; I read nothing of it that bears on a finding). **#1654 had not merged** (open): nothing below depends on it. `docs/roadmap.md` §9/§10, the second drill's 28 findings, #1656 and the 2026-11-07 review (U1 to U20) were read first; none of the findings below repeats one of them.

## 2. The five that matter most

1. **UB-1 (S1)** A switch whose value is present but not the string `true` is read as "on" by the binder and as "off" by the `@ConditionalOnProperty` that wires the beans. `COSEC_PIPELINE_MODE=` (empty, as a Helm template leaves it) wires no pipeline, passes the mode check, and a Spring Cloud Gateway host starts as an open proxy; `cosec.enabled=1|on|<empty>` switches every CoSec auto-configuration off; nothing logs it.
2. **UB-2 (S1)** The gateway image starts an unauthenticated, unencrypted JMX agent on port 5555, on every interface, by default (`applicationDefaultJvmArgs`); the documented `docker run` example does nothing about it, and only the Kubernetes manifests close it.
3. **UB-16 (S1)** Redis is the only copy of the documents, the revocations, the sessions and the API keys, yet no page asks for persistence, replication or a backup; the one Redis requirement named is `noeviction`, and nothing checks the rest. A Redis that restarts empty drops every document (503 everywhere) and every revocation.
4. **UB-3 (S2)** An instance whose platform document is absent (new deployment, wrong `cosec.key-prefix`, wrong Redis database) refuses traffic and logs nothing about why, at startup or later.
5. **UB-4 (S3)** `cosec.authorization.remote-ip.max-trusted-index` defaults to `1`: on a host that clients reach directly, a client writes its own `environment.ip` with `X-Forwarded-For`. Documented, logged at INFO as a neutral reading, never as a risk.

Next in line: UB-5 (startup says nothing about what CoSec is configured as), UB-18 (the control plane and the login service have no deployable artifact, so the gateway-server topology cannot be operated past its first document without building an application), UB-19 and UB-20 (gateway mode rests on the network alone, and an HMAC secret makes every verifier an issuer), UB-10 (a Spring Cloud Gateway host's own controllers are outside CoSec, with no startup line).

Count: **S1 3, S2 1, S3 16, S4 5** (25 findings: UB-1 to UB-15 in configuration and operations, UB-16 to UB-25 in deployment; the cap is 35).

## 3. Appendices

### Appendix A: every `cosec.*` property

Default = the value in the Kotlin properties class. "Documented" = `wiki/operations/configuration.md`; all 93 metadata keys are documented and every default I compared is the same (the page is correct today, but nothing ties it to the code: UB-7). "Validated" = a wrong value stops startup: `yes (P)` names the property in the message, `yes (p)` stops startup but names a Kotlin parameter or a concept, not the key (UB-6), `binder` = only Spring's type conversion (a boolean or enum fails with Boot's own analysis, which names the key and the valid values), `no`.

| Property | Default | Documented | Validated |
|---|---|---|---|
| `cosec.enabled` | `true` | `true` | **no**: any value but `true` is off (UB-1) |
| `cosec.key-prefix` | `cosec` | `cosec` | no (blank or a bad prefix is accepted) |
| `cosec.pipeline.mode` | `enforce` | `enforce` | binder; `gateway` on a gateway host yes (P); **empty: no (UB-1)** |
| `cosec.pipeline.timeout` | 5s | 5s | yes (p) `PipelineOptions` "The pipeline timeout must be positive" |
| `cosec.pipeline.authentication-timeout` | 1s | 1s | yes (p) |
| `cosec.pipeline.allow-query-token` | false | false | binder |
| `cosec.pipeline.allow-cookie-token` | false | false | binder |
| `cosec.pipeline.api-key-header` | `X-API-Key` | `X-API-Key` | yes (p) not blank |
| `cosec.pipeline.trace-level` | `off` | `off` | binder |
| `cosec.pipeline.identity.max-age` | 60s | 60s | yes (p), gateway mode only (`IdentityGate`) |
| `cosec.pipeline.identity.max-size` | 8KB | 8KB | yes (p), gateway mode only |
| `cosec.authorization.scope.app-id` | unset | none | partly: only `*` is refused (`AppId`) |
| `cosec.authorization.remote-ip.max-trusted-index` | 1 | 1 | partly: contradictions with the server and strategy yes (P); a negative number no |
| `cosec.authorization.membership.timeout` | 500ms | 500ms | yes (p) |
| `cosec.authorization.attributes.timeout` | 300ms | 300ms | yes (p) |
| `cosec.authorization.rate-limit.timeout` | 500ms | 500ms | yes (p) |
| `cosec.authorization.routes.location` | unset | unset | yes (P) missing location, empty map, default file present and property unset; a gateway host ignores it silently |
| `cosec.authorization.snapshot.local-max-age` | 30s | 30s | no (CoCache's spec builder) |
| `cosec.authorization.snapshot.revalidate-interval` | 10s | 10s | yes (P) |
| `cosec.authorization.snapshot.lag-warn` | 60s | 60s | yes (P) through `revalidate-interval`; `lag-warn <= lag-escalate` yes (p) |
| `cosec.authorization.snapshot.lag-escalate` | 5m | 5m | yes (p) |
| `cosec.authorization.snapshot.fail-closed.tenants` | empty | empty | partly: a tenant id with a bad shape is refused (`SnapshotKey`), message names the id |
| `cosec.authorization.snapshot.fail-closed.max-lag` | 30m | 30m | yes (p) not negative |
| `cosec.authorization.snapshot.cold-load-timeout` | 2s | 2s | yes (p) |
| `cosec.authorization.snapshot.max-apps` | 1024 | 1024 | yes (p) "maxApps must be positive." |
| `cosec.authorization.snapshot.max-tenants` | 1024 | 1024 | yes (p) |
| `cosec.authorization.snapshot.prewarm-apps` | empty | empty | partly: a bad id is refused (`SnapshotKey.App`), message names the id |
| `cosec.authorization.snapshot.prewarm-tenants` | empty | empty | partly, as above |
| `cosec.authorization.snapshot.compile-threads` | 2 | 2 | yes (p) |
| `cosec.authorization.snapshot.eviction-check` | `fail` | `fail` | binder |
| `cosec.authorization.shadow.enabled` | true | true | **no**: same lenient condition as UB-1 |
| `cosec.authorization.shadow.sample-rate` | 0.1 | 0.1 | yes (p) "between 0 and 1" |
| `cosec.authorization.shadow.report-interval` | 10s | 10s | yes (P) |
| `cosec.authorization.shadow.instance-id` | random | random UUID | yes |
| `cosec.authentication.revocation.skew` | 5s | 5s | no (a negative skew is not refused) |
| `cosec.authentication.revocation.fail-closed` | false | false | binder |
| `cosec.authentication.revocation.cache.local-max-age` | 30s | 30s | no (CoCache) |
| `cosec.authentication.revocation.cache.maximum-size` | 100000 | 100000 | no (CoCache) |
| `cosec.authentication.revocation.cache.initial-capacity` | unset | CoCache's | no |
| `cosec.authentication.revocation.cache.expire-after-access` | unset | none | no |
| `cosec.authentication.login.enabled` | true | true | **no**: lenient condition |
| `cosec.authentication.login.refresh.path` | unset | unset | yes (P) `LoginRoutesCheck` |
| `cosec.authentication.login.logout.path` | unset | unset | yes (P) |
| `cosec.authentication.login.social.enabled` | true | true | **no**: lenient condition |
| `cosec.authentication.login.social.registration.<name>.*` | none | listed | yes (P) unknown field and `ignore-check-state: true` |
| `cosec.jwt.enabled` | true; false in gateway mode | same | **no**: lenient condition, empty = off (UB-1) |
| `cosec.jwt.secret` | blank | **required** | yes (P) blank, and shorter than the algorithm needs |
| `cosec.jwt.algorithm` | `hmac256` | `hmac256` | binder |
| `cosec.jwt.token-validity.access` | 10m | 10m | no (zero or negative issues expired tokens) |
| `cosec.jwt.token-validity.refresh` | 7d | 7d | only against `cosec.control.approval.expire-after` (P) |
| `cosec.jwt.session.lifetime` | 30d | 30d | yes (p) "A session lifetime is positive" |
| `cosec.jwt.session.rotation-leeway` | 30s | 30s | yes (p) |
| `cosec.api-key.enabled` | false | false | yes (P): `true` or `false` only |
| `cosec.api-key.store` | `memory` | `memory` | binder |
| `cosec.api-key.keys[n].*` | empty | listed | yes (P) every field, duplicates, shared hash |
| `cosec.audit.enabled` | true | true | **no**: lenient condition |
| `cosec.audit.kafka.enabled` | true | true | **no**: lenient condition |
| `cosec.audit.kafka.topic` | `cosec-audit` | `cosec-audit` | no |
| `cosec.audit.kafka.delivery` | `best-effort` | `best-effort` | yes (P) |
| `cosec.metrics.enabled` | true | true | **no**: lenient condition |
| `cosec.ip2region.enabled` | true | true | **no**: lenient condition |
| `cosec.ip2region.ipv6-location` | unset | none | yes (P) must exist |
| `cosec.openapi.enabled` | true | true | **no**: lenient condition |
| `cosec.cosid.generator` | `default` | `default` | yes (P) set without module, or without effect |
| `cosec.observability.listener.required-timeout` | 1s | 1s | yes (p) |
| `cosec.observability.listener.queue-capacity` | 1024 | 1024 | yes (p) |
| `cosec.observability.listener.drain-timeout` | 5s | 5s | no |
| `cosec.gateway.unprotected-endpoints` | empty | empty | yes (P) `gateway` is never accepted |
| `cosec.gateway.identity.enabled` | true | true | **no**: lenient condition |
| `cosec.gateway.identity.claims` | empty | empty | no |
| `cosec.gateway.identity.max-size` | 8KB | 8KB | no here; a larger identity is a 500 at request time |
| `cosec.gateway.identity.forward-authorization` | false | false | binder |
| `cosec.control.enabled` | true | true | yes (P) `true` or `false` only (the one switch done properly) |
| `cosec.control.app-id` | `cosec-control` | same | no |
| `cosec.control.http.base-path` | `/cosec/control` | same | no |
| `cosec.control.http.max-body-size` | 16MB | 16MB | no |
| `cosec.control.revisions.max-count` | 50 | 50 | no |
| `cosec.control.revisions.min-age` | 7d | 7d | no |
| `cosec.control.revisions.orphan-age` | 1h | 1h | no |
| `cosec.control.revisions.reclaim-interval` | 1h | 1h | yes (P) positive |
| `cosec.control.changes.expire-after` | 7d | 7d | yes (p) "Invalid change options." |
| `cosec.control.approval.expire-after` | 72h | 72h | yes (P) against `jwt.token-validity.refresh`; negative yes (p) |
| `cosec.control.approval.four-eyes` | false | false | binder |
| `cosec.control.break-glass.max-duration` | 4h | 4h | yes (p) "Invalid change options." |
| `cosec.control.shadow.min-duration` | 15m | 15m | yes (p) "Invalid shadow window options." |
| `cosec.control.shadow.min-samples` | 1000 | 1000 | yes (p), same message |
| `cosec.control.shadow.max-duration` | 24h | 24h | yes (p), same message |
| `cosec.control.shadow.check-interval` | 1m | 1m | yes (P) positive |
| `cosec.control.audit.required-timeout` | 5s | 5s | no |
| `cosec.control.audit.kafka.enabled` | true | true | **no**: lenient condition |
| `cosec.control.audit.kafka.topic` | `cosec-control-audit` | same | no |
| `cosec.control.audit.kafka.delivery` | `best-effort` | same | binder |

Where a property depends silently on another: `cosec.pipeline.mode` (every `cosec.api-key.*`, `cosec.authorization.snapshot.*`, `shadow.*`, `routes.location`, `audit`/`metrics` and tracing are inert in `gateway` mode, UB-9); `cosec.authorization.snapshot.cold-load-timeout` against `cosec.pipeline.timeout` (UB-11); `cosec.jwt.enabled` against the mode.

### Appendix B: every startup failure the starter can produce

"In troubleshooting" = the message's own words are searchable in `wiki/operations/troubleshooting.md` (the skill `cosec-troubleshoot` follows it, with the gaps noted in UB-13).

| Message (start) | Where thrown | In troubleshooting |
|---|---|---|
| `CoSec does not read <n> cosec.* properties, which would do nothing:` | `UnknownProperties.check`, a `BeforeBeans` task (runs first since #1655) | yes |
| `<what> needs <artifact> (me.ahoo.cosec:<artifact>) on the classpath: add it to the application's dependencies (STA-1).` (webmvc, webflux, gateway, redis API keys, required Kafka, metrics, control plane, cosid) | `CoSecModuleCheckAutoConfiguration.checkModule` | yes (table) |
| `cosec-cocache needs a ReactiveStringRedisTemplate bean: ... (STA-1).` / `... needs a CacheRuntime bean: ... (STA-1).` | `CoCacheRedis` checks | yes |
| `cosec.cosid.generator is set, but CoSec's ids do not come from CosId: ...` | `CosIdGenerator` check | yes |
| `cosec.control.enabled must be true or false, not [x].` | `ControlPlaneCondition.written` | yes |
| `cosec.pipeline.mode=gateway on a Spring Cloud Gateway host: ...` / binding failure of `cosec.pipeline.mode` | `CoSecIdentityGuardAutoConfiguration`, Boot's binder | yes |
| `Actuator endpoints [..] are exposed over HTTP on the gateway's application port ...` and `cosec.gateway.unprotected-endpoints lists [gateway], which it never accepts ...` | `CoSecGatewayEndpointCheckAutoConfiguration.requireProtected` | yes |
| `cosec.authorization.remote-ip.max-trusted-index is set, but Tomcat's RemoteIpValve ...`, `=0 reads the connection's peer, but ...`, `The native forward-headers strategy is on ...`, `server.forward-headers-strategy=framework: ...`, `CoSec cannot tell whether the ForwardedHeaderFilter bean ...` | `ClientIpReadings`, `ClientIpConfigurationException` | yes |
| `Redis may evict CoSec's policy snapshot ...` / `Cannot tell whether Redis may evict ...` | `EvictionPolicyCheck.check` | yes |
| `CoSec's Redis template [..] reads from replicas ...` | `ReplicaReads.check` | yes |
| `cosec.authorization.snapshot.revalidate-interval (x) ...` | `toOptions()` | yes |
| `cosec.authorization.shadow.report-interval is at least one millisecond, not x.` | `Shadow.cosecShadowReporting` | **no** (configuration page only) |
| `An instance id has only letters, digits, '.', '_' and '-': [x].` | `ShadowRecorder` | yes |
| `Property [cosec.jwt.secret] must not be blank ...` / `... has n bytes, and HMAC256 needs at least 32 ...` | `cosecTokenAlgorithm` | yes |
| `CoSec's tokens are on, but TokenStore, RevocationReader, TokenFamilyStore are missing. ...` | `TokenStores.failure` | yes |
| `<names> is set, but this application serves no HTTP ...`, `... on a Spring Cloud Gateway host ...`, `... is not one plain absolute path ...`, `... name one path ...`, `... but login or JWT is off ...` | `LoginRoutesCheck` | yes (table row) |
| `cosec.authentication.login.refresh.path is set, but the RefreshTokenAuthentication bean refreshes X ... (IDN-25)` | `cosecRefreshRoute` | yes |
| `classpath:cosec-routes.json exists but ... is not set ...`, `The route map [x] (...) does not exist ...`, and the codec's errors | `RouteMaps.load` | yes |
| `cosec.ip2region.ipv6-location [x] does not exist.` | `Ip2Region` bean | **no** (named in one table row about IPv6 clients, not this message) |
| `API keys are enabled, but no ApiKeyReader is available: with cosec.api-key.store=redis, ...` | `apiKeyReader` | **text differs**: the page quotes `... no ApiKeyReader is available for the store <class>: ...`, which the code never writes (UB-13) |
| `cosec.api-key.enabled=true needs a MembershipProvider bean ... (IDN-6).`, `API key [id] needs ...`, `Duplicate API key ids`, `... have the same hash`, `cosec.api-key.enabled=[x] is neither true nor false.` | `CoSecApiKeyAutoConfiguration`, `ApiKeyProperties` | yes, except `have the same hash` |
| `cosec.audit.kafka.delivery=required, but no Kafka audit listener was created ...` | `cosecDecisionListeners` | yes |
| `cosec.audit.kafka.delivery=[x] is neither required nor best-effort.` | `kafkaAuditDelivery` | **no** |
| `cosec.control.audit.kafka.delivery=required, but no Kafka change listener was created ...` | `cosecChangeListeners` | yes |
| `The control plane checks its approvers' revocations, but no revocation reader is available ...` | `ControlPlane.revocations` | yes |
| `cosec.jwt.token-validity.refresh (x) ... is shorter than cosec.control.approval.expire-after (y) ...` | `checkRetention` | yes |
| `The control plane's X is CoSec's own [name], never an application bean: remove [..] (RFC 0011 D4).` | `cosecControlAuthorizationCheck` | **no** |
| `cosec.control.revisions.reclaim-interval must be positive.`, `cosec.control.shadow.check-interval must be positive.` | `ControlMaintenanceScheduler` | yes |
| `Invalid change options.`, `Invalid shadow window options.` | `ChangeOptions`, `ShadowOptions` | yes (but the message names no value, UB-6) |
| `STA-5: n CorsConfigurationSource beans of the application: the CORS filter before CoSec takes one. ...` | `cosecCorsFilter` | **no** (`web.md` only) |
| `Property [cosec.authentication.login.social.registration.<k>.ignore-check-state] is true: ...` | `socialProviderManager` | **no** (configuration page only) |
| `The pipeline timeout must be positive, not x.` and the like (authentication, membership, attribute, rate limit timeouts, API key header, listener timeout and capacity, identity limits, session lifetime, sample rate, `maxApps must be positive.`) | `PipelineOptions`, `SnapshotOptions`, `DecisionListeners`, `IdentityGate`, `TokenFamilies`, `ShadowListener` | `lagWarn must not exceed lagEscalate.` yes; the others **no** |
| `Decision listener x is registered twice.`, `CoSec's <Cache> is the store of record: a Store.`, CoCache's locked-setting errors | `DecisionListeners`, `CacheLocks`, CoCache | yes |
| No `RequestGate` bean (a servlet or WebFlux host whose mode condition matched neither `enforce` nor `gateway`, UB-1) | Spring, `cosecPipelineServletFilter(pipeline: RequestGate)` | no, and no message of CoSec's |

### Appendix C: topologies, what each needs, where it is documented, what verifies it

"Verified" lists only what the code checks at startup or per request; "not verified" the facts that make the topology safe and that no process checks.

| Topology | Processes | Modules and properties each needs | Must reach | Trust boundary | Documented | Verified / not verified |
|---|---|---|---|---|---|---|
| 1. One service that enforces | one Spring Boot application | `cosec-spring-boot`, `cosec-webflux` or `cosec-webmvc`, `cosec-cocache` with `spring-boot-data-redis` and `cocache-spring-boot-starter`; `cosec.jwt.secret`; `spring.data.redis.*`; optional `cosec-control`, `cosec-kafka`, `cosec-micrometer` | Redis (the primary, `noeviction`, ACL); Kafka if audit goes there | Whoever holds `cosec.jwt.secret` can mint tokens (UB-20); `X-Forwarded-For` hops (UB-4) | `guide/quick-start.md`, `introduction.md#modules`, `integration/web.md`, `operations/storage.md`; no deployment page (UB-17) | Verified: STA-1 module checks, the three token stores, eviction and replica checks, client-address check, readiness on the platform document. Not verified: Redis persistence or HA (UB-16), key prefix, the secret across processes |
| 2. A gateway decides, services behind it trust it (`gateway` mode) | the gateway (`cosec-gateway-server`, or `cosec-gateway` with `cosec-cocache`); each service: `cosec-webflux`/`webmvc` and `cosec.pipeline.mode=gateway` | gateway: `cosec.jwt.secret`, Redis, routes; service: nothing else (no Redis, no secret; `cosec.jwt.enabled` off by default) | gateway to Redis and to the services | Only the gateway may reach the service: the header is unsigned base64url JSON (UB-19) | `operations/deployment.md#services-behind-the-gateway`, `integration/web.md#services-behind-the-gateway`, `scenarios/gateway.md`, `guide/security-model.md#trust-boundaries` | Verified: a WARN at the service's startup; `gateway` on a gateway host fails; per request `IdentityGate` refuses an unknown version, an old or oversized header. Not verified: who can reach the service; `k8s/` has no policy for it |
| 3. The gateway and the service both decide | gateway as above; service in `enforce` mode | service: the gateway's `cosec.jwt.secret`, Redis, `cosec.key-prefix`, its own `scope.app-id`; gateway: `forward-authorization: true` | both to Redis | the secret is shared by both (UB-20) | `scenarios/gateway.md` | Verified: nothing across processes: a different secret is a `401 token-invalid` at the service, a different prefix a `503`. Not verified: that the two agree |
| 4. A service that only verifies another's tokens | one application in `enforce` mode, JWT on, no login | the issuer's secret and algorithm; a `RevocationReader` that reads the issuer's revocations (`cosec-cocache` on the issuer's Redis with the issuer's prefix) or `RevocationReader.NONE`; a platform document in its own store | the issuer's Redis, or nothing | the issuer's secret | `integration/authentication.md` (logout and revocation); a scenario page is in #1654, unmerged | Verified: the three-store startup message; the `NONE` WARN (#1655). Not verified: that the prefix is the issuer's (a different one reads an empty table: revoked tokens accepted, silently) |
| 5. A login service | one application with `cosec-login` and `cosec-jwt` (the starter brings both) | `cosec.jwt.*`, `cosec.authentication.login.*` (served refresh and logout paths), `cosec-cocache` for the stores; behind a gateway, `cosec.jwt.enabled=true` | Redis | it signs: the secret is the root of trust | `integration/authentication.md`, `integration/clients.md#what-your-application-serves` | Verified: secret length, stores, `IDN-23`/`IDN-24` route checks. Not verified: that the gateway and verifiers hold the same secret |
| 6. The control plane | one application of the operator's own: `cosec-control` + `cosec-cocache`; no image exists (UB-18) | `cosec.control.*`; Redis credentials that may write the documents; data-plane instances set `cosec.control.enabled=false` (read-only); first administrator by `cosec-migrate bootstrap`; a way to issue a `(platform)` token | Redis (write); optionally Kafka | whoever holds a `(platform)` admin token or the secret (UB-20) | `operations/storage.md#publishing-documents`, `configuration.md#control-plane`, `policies/testing.md`, quick start's last section | Verified: STA-1 when `cosec.control.enabled=true` is written, the revocation reader, token retention against approval expiry, non-replaceable beans, Redis permission failures. Not verified: that an administrator can obtain a token at all |
| 7. The gateway server image | `ahoowang/cosec-gateway`, `ghcr.io/ahoo-wang/cosec-gateway`, an Aliyun mirror | `COSEC_JWT_SECRET`, `SPRING_DATA_REDIS_*`, a `config/application.yaml` with routes (UB-21) | Redis, the routes' backends | the image's JMX port (UB-2) | `operations/deployment.md`, `k8s/README.md` | Verified: the `aot-smoke` job runs the image with its own config; CI builds the overlays and checks them against the Kubernetes schemas. Not verified: that the manifests' properties are known keys (UB-22) |

## 4. Findings: configuration and operations

### UB-1 (S1): a switch that is present but not `true` is read two ways, and one of them is "CoSec off"

- **Who and what:** an operator or a Helm/Compose template setting `cosec.pipeline.mode`, `cosec.enabled`, `cosec.jwt.enabled` and the other `*.enabled` switches from an environment variable or a values file.
- **What happens, with proof.**
  - `ConditionalOnEnforceMode.kt` is `@ConditionalOnProperty(value = [MODE_KEY], havingValue = "enforce", matchIfMissing = true)`. `matchIfMissing` applies to an absent key only; a key that is present and empty (`COSEC_PIPELINE_MODE=`, or `mode:` with no value in YAML, which Boot reads as an empty string) fails `havingValue`. The binder, in the same startup, reads an empty enum as unset and keeps `Mode.ENFORCE`. I proved both on Spring Boot 4.1.2 (probe in the scratchpad: `Probe.java`): with `cosec.pipeline.mode=""` the `enforce` bean is **not** created and `Binder` binds `ENFORCE`; with `cosec.enabled` set to `1`, `on` or empty, `ConditionalOnCoSecEnabled`'s bean is not created, with `true` it is.
  - `CoSecIdentityGuardAutoConfiguration.cosecPipelineModeCheck` reads the bound value (`ENFORCE`), so it passes. On a **Spring Cloud Gateway host** every enforce-mode auto-configuration is off, `PipelineGatewayFilter` (`CoSecPipelineWebAutoConfiguration.Gateway`, `@ConditionalOnEnforceMode`) is not created, and the only beans left are `IdentityStripFilter` and `IdentityHeadersFilter`: the gateway forwards every route with no decision, no 401, no 403 (read from the auto-configuration conditions; I did not boot a gateway). On a **servlet or WebFlux host** the filter bean asks for a `RequestGate` that nothing creates, so startup fails with Spring's "No qualifying bean of type RequestGate", which names neither the property nor the cause.
  - `cosec.enabled=1`, `on`, `yes` (as a string) or empty is "false" to `ConditionalOnCoSecEnabled`, though Spring's own boolean conversion (and `ConfigurationPropertiesBinding` of `CoSecProperties.enabled`) reads `1`, `on`, `yes` as true. Every CoSec auto-configuration, including the `cosec-webmvc`/`cosec-webflux` module check, is gated on it, so the host starts with no filter and no log line. `cosec.enabled=false` set on purpose logs nothing either (UB-5).
  - The project already knows the pattern: `ApiKeyProperties.kt` (KDoc of `apiKeysEnabled`) says "Spring's conversion would read it as true while the condition does not" and parses strictly; `ControlPlaneCondition.written` and `kafkaAuditDelivery` do too. The twelve other switches and the mode were left with the lenient annotation. `UnknownPropertiesTest` and the mode tests cover typos (`gatway`), never an empty value.
  - For `cosec.jwt.enabled` the same empty value turns JWT off in enforce mode (fails closed: every token is `401 token-invalid`, a confusing symptom).
- **Recommendation:** one internal condition class, `OnCoSecSwitch(key, default)`, that reads the key as the binder does (`Binder.get(environment).bind(key, ...)`, as `credentialHeaders` and `ControlPlaneCondition` already do), treats a blank value as unset, and **fails startup** (naming the key and `true`/`false`, or the enum values) for anything that does not parse. Use it for `cosec.enabled`, `cosec.pipeline.mode` (both `enforce` and `gateway` sides), and every `*.enabled`. Add the missing tests (blank, `1`, `on`, `TRUE`, ` true `). Add a `WARN` when `cosec.enabled=false` (UB-5). No unsafe configuration becomes easier: blank now means the default, which is `enforce` and on.
- **Touches:** a bean condition and a startup message; no property name or default changes. A configuration that relied on `cosec.enabled=1` meaning off stops doing so: that is a behaviour fix, not an API break (no `!`), but name it in the release notes.
- **Size:** S to M. **When:** before RC1.

### UB-2 (S1): the gateway image ships an unauthenticated JMX port on every interface

- **Who and what:** an operator running `ahoowang/cosec-gateway` with Docker, Compose or any Kubernetes manifest of their own (the repository's manifests are fine).
- **What happens, with proof.** `platform/gateway-server/build.gradle.kts` `applicationDefaultJvmArgs` contains `-Dcom.sun.management.jmxremote`, `...authenticate=false`, `...ssl=false`, `...port=5555`, and no `jmxremote.host` or `java.rmi.server.hostname`: the JDK binds the agent to all interfaces, and anyone who reaches the port can load MBeans (remote code execution) in the process that holds `COSEC_JWT_SECRET` and the trust boundary. The `Dockerfile` runs `bin/cosec-gateway-server`, which carries those defaults. The page `wiki/operations/deployment.md` shows `docker run -d -p 8080:8080 ... ahoowang/cosec-gateway` under "The image" without a word about JMX; the fact appears only in the Kubernetes subsection ("the image's start script starts a JMX agent on port 5555 with no authentication; this keeps it inside the pod") and in a comment of `k8s/base/deployment.yaml`, where `-Dcom.sun.management.jmxremote.host=127.0.0.1` closes it. Any other container or pod on the same network can connect (with `--network host` or a published port, anyone); on Kubernetes without that flag or a NetworkPolicy, every pod of the cluster.
- **Recommendation:** remove the four `jmxremote` properties from the defaults (JMX is then opt-in through `JAVA_OPTS`, and `jcmd`/`docker exec` need none), or at least default to `-Dcom.sun.management.jmxremote.host=127.0.0.1 -Djava.rmi.server.hostname=127.0.0.1`. Move the sentence into "The image" either way. Check `aot-train.sh` (it sets its own ephemeral JMX ports according to the deployment page) still trains.
- **Trade-off:** someone who attaches VisualVM or JConsole remotely to port 5555 loses that, and has `kubectl port-forward` or a `JAVA_OPTS` opt-in instead. That is the safe direction.
- **Touches:** the image's JVM defaults and docs; breaking only for users of that port (an application, not a published library: no ABI). **Size:** S. **When:** before RC1.

### UB-3 (S2): an instance that waits for its platform document says nothing

- **Who and what:** anyone starting a first instance (quick start, `kubectl apply -k k8s/overlays/dev`), and anyone whose publisher used another `cosec.key-prefix` or Redis database than the instance.
- **What happens, with proof.** `DocumentReadiness.missing()` reports the platform document while `source.peek()?.policies` is not `Present`; `SnapshotReadiness` refuses traffic quietly. `Prewarms.attempt` logs ("Snapshot prewarm failed for ...") only for `DocumentState.Failed`, and an absent document is not failed. `KeyEffects.write` has no INFO case, and `installed()` records only a metric: **nothing is logged when a document is installed either**. Per request, `LoadView` returns `state.unavailable(SourceUnavailable)`, which reaches a log only through the audit listener, and only once traffic arrives, which readiness prevents. The result: pod `0/1 Running`, a quiet log, an HTTP 503 `unavailable` with no reason in the body (by design). The most likely cause (a wrong prefix or Redis database) is exactly what the operator must be told to look at. `troubleshooting.md` ("Instance is not ready") and the `cosecSnapshot` health detail (`platform: absent`) are right, but need Actuator and a probe the operator thinks of.
- **Recommendation:** a `WARN`, once the first load answers "absent": `No platform document under cosec:snapshot:... in Redis <host:port/db>: this instance refuses traffic (readiness) and answers 503 until one is published ("cosec-migrate publish --key-prefix ...", cosec.key-prefix is "cosec").` Repeat it at the `lag-warn` cadence while it lasts, and log one `INFO` line when the platform document (and each prewarmed one) is installed, with its revision. Both are log lines: no state, no new property.
- **Touches:** a message only; non-breaking. **Size:** S. **When:** before RC1.

### UB-4 (S3): `max-trusted-index` defaults to `1`, which is wrong for a host that clients reach directly

- **Who and what:** an operator running a WebFlux, WebMvc or gateway host without a proxy (a VM, Compose, a lab) with policies or rate-limit keys that read `environment.ip`.
- **What happens, with proof.** `RuntimeAuthorizationProperties.RemoteIp.maxTrustedIndex = 1`. Off a cloud platform `strategyOf` returns `NONE`, and `ClientIpReadings.decide` then reads "the X-Forwarded-For entry 1 from the right, or the connection's peer without one": a client that sends `X-Forwarded-For: 10.1.2.3` is `10.1.2.3`. An ALLOW on a CIDR, an IP-keyed rate limit and the blacklist then follow what the client wrote. `web.md` ("Client IP behind proxies") says "On a host that clients reach directly, set the index to 0", and startup logs the reading at INFO, so the operator is told; but the zero-configuration path is the unsafe one, and the log line says what it reads, not that a direct client controls it.
- **Recommendation (a decision for the maintainer):** keep `1` (a host behind exactly one proxy is the common case, and `0` on Reactor Netty or Jetty under `native` is refused), but when the strategy is `NONE` and the property is **not written**, log a `WARN`: "trusts one X-Forwarded-For hop: a client that reaches this host directly chooses its own address; set max-trusted-index to the number of proxies in front of it, or 0 if there are none". The stricter alternative, failing startup in that one case until the property is written, makes the choice explicit but breaks every host that relies on the default today.
- **Trade-off:** default `0` would be fail-safe against spoofing, but behind a proxy every client then shares the proxy's address: IP-keyed limits collapse into one bucket and an IP blacklist stops matching, silently.
- **Touches:** a message (WARN); the stricter option touches the default behaviour (breaking for authorization semantics, `!`). **Size:** S. **When:** before RC1 for the WARN; the stricter option 8.1.

### UB-5 (S3): startup does not say what CoSec is configured as

- **Who and what:** an operator reading the log of a new instance, or deciding whether a deployed one is the one they configured.
- **What happens, with proof.** The starter logs: the client-address reading (`ClientIp`), the route map (`RouteMaps`), `No BlacklistSource bean`, ip2region versions, the gateway-mode WARN, the `NONE` revocation WARN, `Control plane off`, and the suspend-handler report. It does **not** log: that CoSec is off (`cosec.enabled=false`: no line anywhere; UB-1), the mode (`enforce` is silent), JWT on or off and its algorithm, whether API keys are on, which rate limiter was created (`CoSecPipelineIntegrationAutoConfiguration.RateLimiting` picks `ReactiveRedisRateLimiter` or `LocalRateLimiter` without a line; without `cosec-cocache` every rule counts per instance, so `N` replicas allow `N` times the limit), which decision listeners are registered (audit log, Kafka, metrics, tracing), whether shadow windows are on and at what rate, the key prefix, whether the control plane runs (only the "off" case is logged), or which documents it waits for.
- **Recommendation:** one `INFO` line at the end of startup (a `StartupTask.AfterSingletons`) in the form `CoSec 8.0.0-beta.3: mode=enforce, jwt=hmac256, api-keys=off, rate-limiter=redis, listeners=[audit, metrics, kafka-audit], shadow=0.1, control-plane=off, key-prefix=cosec, documents=waiting for platform`, with `rate-limiter=local (per instance)` flagged at `WARN` when more than one instance is plausible (no way to know: say it once), and a `WARN` `CoSec is off: cosec.enabled=false, no request is authorized by this host` when it is off.
- **Touches:** a message; non-breaking. **Size:** S. **When:** before RC1 (cheap; the rest of this review leans on it).

### UB-6 (S3): a wrong value stops startup, but the message names a Kotlin parameter, not the property

- **Who and what:** an operator who writes `cosec.pipeline.timeout: 0`, `cosec.authorization.snapshot.max-apps: 0`, a sample rate of `2`, a break-glass of `48h`.
- **What happens, with proof.** The properties classes bind without checking; the checks are `require` calls in the runtime and control modules, so the failure is a `BeanCreationException` whose cause says: `The pipeline timeout must be positive, not PT0S.` (`PipelineOptions`), `maxApps must be positive.`, `lagWarn must not exceed lagEscalate.` (`SnapshotOptions`), `The shadow sample rate is between 0 and 1, not 2.0.` (`ShadowListener`), `The listener queue capacity must be positive` (`DecisionListeners`), `A session lifetime is positive` (`TokenFamilies`), and, for six different conditions of `cosec.control.*`, the single text `Invalid change options.` (`ChangeOptions.init`; `ShadowOptions` likewise). Appendix A marks each `(p)`. `revalidate-interval`, `jwt.secret`, `report-interval` and the API-key fields already do it properly (they name the key), which shows the intended standard. No `Validator` or `@Validated` is on any properties class.
- **Recommendation:** validate in the starter, where the key is known: give each properties class a Spring `Validator` (no new dependency: Boot runs a `Validator` for a class annotated `@ConfigurationProperties` when it implements `org.springframework.validation.Validator` or a bean named `configurationPropertiesValidator` exists), so Boot's failure analyzer prints `Property: cosec.pipeline.timeout, Value: PT0S, Reason: must be positive`. Or, smaller, catch `IllegalArgumentException` where the starter builds `PipelineOptions`, `SnapshotOptions`, `ChangeOptions` and `ShadowOptions` and rethrow with the property of the argument. Make the control-plane `require` say which condition failed.
- **Touches:** messages only; non-breaking. **Size:** M. **When:** 8.0.x.

### UB-7 (S3): the IDE metadata has no defaults, and nothing ties the configuration page to the code

- **Who and what:** an operator or developer typing `cosec.` in an IDE; the maintainer who changes a default.
- **What happens, with proof.** `host/spring-boot/src/test/resources/configuration-metadata.json` is the pinned copy of what `spring-boot-configuration-processor` generates: 93 entries, **92 without `defaultValue`** (the one with a default is Boot's `management.endpoint.cosec-service-generator.access`). Most properties are `var x = Duration.ofSeconds(5)`: Kotlin initialisers are not visible to the processor, and even the constructor-bound classes that carry `@DefaultValue("true")` (`CoSecProperties`, `JwtProperties`, `AuditProperties`) have no `defaultValue` in the golden file. So the IDE shows no default for any switch, duration, size or number, and `ConfigurationMetadataTest` pins the absence. The defaults live in the class and in `configuration.md`; every one I compared matches today, but no test compares them.
- **Recommendation:** write defaults into the metadata (an `additional-spring-configuration-metadata.json` produced by a test that binds an empty environment to each class and records the values), and add a test that fails when `configuration.md`'s default column and the bound default disagree. UB-1's enum and boolean hints (`hints`, empty today) can ride along (`enforce`, `gateway`).
- **Touches:** build and tests; no property changes. **Size:** M. **When:** 8.0.x.

### UB-8 (S3): the decision timer cannot tell a `token-expired` from a `forbidden`, and the one documented fail-open has no health signal

- **Who and what:** an operator alerting on 401, 403, 429 and 503.
- **What happens, with proof.** `MetricsListener.onDecision` tags `cosec.authorization` with `result`, `source` and (on `indeterminate`) `cause` only. `DecisionEvent.outcome` is an `OutcomeSummary` that carries `status` and `problem: ProblemType?`, which the listener ignores: an anonymous implicit deny (401 `unauthorized`), an authenticated one (403 `forbidden`), and the explicit deny behind a rejected token (401 `token-invalid`, `token-expired`, `token-revoked`) fall into `implicit_deny` or `explicit_deny`. The 503 side is well covered (`cause`), 429 has its own `result`; 401 versus 403 and the three token reasons do not. An operator can still use Spring's `http.server.requests` status, and the audit log (Appendix: troubleshooting "Find out what decided"), but "a client's token expiry bug" and "someone probing" look alike on the CoSec dashboard. Separately, `cosec.authentication.revocation.fail-closed=false` (the default, a documented exception) leaves a revoked token accepted while Redis cannot be read; its signals are a `WARN` at most every ten seconds and `cosec.revocation.unchecked`, which exists only with `cosec-micrometer` and a `MeterRegistry`. No health indicator carries it, though the other two documented Redis conditions (`cosecSnapshot`, `cosecSnapshotEviction`) do.
- **Recommendation:** add a `problem` tag (the ten closed `ProblemType` slugs: bounded cardinality) to `cosec.authorization`, and a `cosecRevocation` health indicator (UP, or `unchecked since <time>` with the count; not in the readiness group, like the eviction one).
- **Touches:** a metric tag (it splits existing series by one dimension: a sum over the new tag is the old series, so dashboards that aggregate keep working) and an additive bean; non-breaking. **Size:** S to M. **When:** before RC1 for the tag (meter dimensions are what dashboards pin); the indicator 8.0.x.

### UB-9 (S3): a module that is on the classpath, or a property that is written, silently does nothing when what it needs is missing

- **Who and what:** an operator who adds `cosec-kafka`, `cosec-micrometer` or `cosec-opentelemetry` and the properties of a feature, in a fleet that shares one configuration between roles.
- **What happens, with proof.**
  - `CoSecPipelineIntegrationAutoConfiguration.Kafka` is `@ConditionalOnClass(KafkaAuditListener) @ConditionalOnBean(KafkaOperations)`: with `cosec-kafka` present and no `KafkaOperations` bean (no `spring-boot-starter-kafka`, no `spring.kafka.bootstrap-servers`) no listener is created and nothing is logged, unless `delivery=required` (the only checked case, `STA-1`). `Metrics` likewise needs a `MeterRegistry` bean; without Actuator no meter exists and no line says so. The `cosec.metrics.enabled=true` check passes with the module present.
  - In `cosec.pipeline.mode=gateway` the whole integration, API-key, snapshot and membership auto-configurations are off (`@ConditionalOnEnforceMode`): `cosec.api-key.enabled=true`, `cosec.authorization.snapshot.*`, `shadow.*`, `cosec.audit.*`, `cosec.metrics.*` and `cosec.authorization.routes.location` are accepted by the unknown-key check (they are known keys) and do nothing. The troubleshooting page says so for API keys; no startup line does. On a Spring Cloud Gateway host `cosecRouteMap` returns `RouteMap.EMPTY` before looking at `routes.location`, also silently.
  - The precedent is in the starter: `cosec.cosid.generator` and the login-route paths fail startup when they are set and could do nothing ("a property that is set and does nothing fails startup", `CoSecModuleCheckAutoConfiguration` KDoc).
- **Recommendation:** where the operator wrote the feature's own key (`cosec.audit.kafka.enabled=true`, `cosec.metrics.enabled=true`, `cosec.api-key.enabled=true` in gateway mode, `routes.location` on a gateway host), fail startup or at least `WARN` with the cause ("`cosec.audit.kafka.enabled=true`, but there is no KafkaOperations bean: no audit event goes to Kafka"). Unwritten defaults stay silent as today.
- **Touches:** messages; failing instead of warning makes a configuration that starts today stop (breaking for such hosts: choose `WARN` first). **Size:** M. **When:** 8.0.x.

### UB-10 (S3): on a Spring Cloud Gateway host, the application's own controllers and functional routes are outside CoSec, and nothing says so at startup

- **Who and what:** a team that adds Spring Cloud Gateway to a WebFlux application that has its own endpoints (a BFF, a fallback controller), or adds a controller to the gateway.
- **What happens, with proof.** The starter decides "gateway host" by class presence (`GATEWAY_GLOBAL_FILTER`): `cosecPipelineWebFilter` is `@ConditionalOnMissingClass(GATEWAY_GLOBAL_FILTER)` and the WebFlux module check is skipped, so the host gets only `PipelineGatewayFilter`, a `GlobalFilter` that runs on routes. Spring Cloud Gateway's route mapping has the order `1` by default (`spring.cloud.gateway.server.webflux.handler-mapping.order`, default `1` in the 5.0.4 jar's metadata, read with `javap`), after WebFlux's annotated-controller mapping (0) and `RouterFunction` mapping (-1; Spring's defaults, not re-read in the jar), so a controller or functional route of the application is answered before any gateway route or `GlobalFilter` is reached and never meets `PipelineGatewayFilter`. `web.md` and `deployment.md` say CoSec "runs on gateway routes only"; `CoSecGatewayEndpointCheckAutoConfiguration` guards the actuator, and nothing guards a controller. The two sentences in the docs are the only warning.
- **Recommendation:** at startup on a gateway host, `WARN` once when the context holds a handler mapping of the application's own (a `@Controller`/`@RestController` bean outside Spring's, or a `RouterFunction` bean): "CoSec authorizes the gateway's routes only; these handlers are not under it: ...". Fail startup only with an opt-in property if the maintainer wants the same strictness as STA-2.
- **Touches:** a message. **Size:** S to M. **When:** before RC1 if the maintainer agrees it is S1-worthy for BFFs, else 8.0.x.

### UB-11 (S3): rolling restart: a ready instance has the platform only, and a raised `cold-load-timeout` is not checked against `pipeline.timeout`

- **Who and what:** an operator restarting a fleet with many tenants or apps, or tuning timeouts.
- **What happens, with proof.** Readiness waits for the platform document and the lists `prewarm-apps`, `prewarm-tenants`, both empty by default (`DocumentReadiness.missing`). The first request of each tenant or app on a fresh instance is a cold load, bounded by `cold-load-timeout` (2s), and answers `503` `SourceUnavailable` past it. During a restart of every instance while Redis is slow, the 503s are exactly those first requests. `deployment.md` ("Readiness") and `configuration.md` define the lists, but no page says who should be in them (the most active tenants) or that a static list is the only warm-up there is. `cold-load-timeout` and the stage timeouts live beside `cosec.pipeline.timeout` (5s) with no check that one fits in the other: `cold-load-timeout=10s` makes every slow cold load a `deadline` 503 (`DEADLINE_EXCEEDED`, reason `deadline`), not the `source_unavailable` the troubleshooting page sends the operator to.
- **Recommendation:** one paragraph in `deployment.md` ("prewarm the tenants that carry the traffic; the rest pays one cold load of at most `cold-load-timeout`"), and a startup check that `cold-load-timeout` is below `cosec.pipeline.timeout` (message naming both keys).
- **Touches:** docs and one message; the check can stop a configuration that starts today (breaking only for a value that was already wrong). **Size:** S. **When:** 8.0.x.

### UB-12 (S4): the same concept has several spellings

- **Who and what:** anyone writing a configuration from memory.
- **What happens, with proof (from the properties classes).** Stage timeouts: `cosec.pipeline.authentication-timeout` but `cosec.authorization.{membership,attributes,rate-limit}.timeout`, `cosec.authorization.snapshot.cold-load-timeout`, `cosec.observability.listener.required-timeout`, `cosec.control.audit.required-timeout`. The forwarded identity's size: `cosec.gateway.identity.max-size` (the gateway) and `cosec.pipeline.identity.max-size` (the service), two namespaces for one header. "Shadow": `cosec.authorization.shadow.*` (the data plane's part) and `cosec.control.shadow.*` (the control plane's windows). "Audit and listeners": `cosec.audit.*`, `cosec.metrics.enabled` and `cosec.observability.listener.*` all configure decision listeners; `cosec.control.audit.kafka.*` mirrors `cosec.audit.kafka.*`. Plural and singular: `cosec.api-key`, `cosec.authorization.routes`, `cosec.authorization.attributes`, `cosec.authorization.membership`. The app: `cosec.authorization.scope.app-id` and `cosec.control.app-id`. Switches: twelve `*.enabled` booleans, one mode enum (`cosec.pipeline.mode`), `eviction-check: fail|warn`, `delivery: required|best-effort`; `cosec.api-key.enabled` and `cosec.control.enabled` reject non-booleans, the other ten do not (UB-1).
- **Recommendation:** no rename now: each one costs a migration of every deployment's configuration at the RC stage, and U11 already spent the budget for it. Cross-link the paired pairs in `configuration.md` (the identity size, the two shadow groups). If one rename is done, `cosec.pipeline.authentication-timeout` into the `cosec.authentication.*` tree is the one with a sibling group; the unknown-key check would name the new key.
- **Touches:** docs, or a property rename (breaking: startup fails with the table link). **Size:** S (docs), M (rename). **When:** docs 8.0.x; renames before RC1 or not before 9.0.

### UB-13 (S4): troubleshooting and the skill miss some messages, and quote one that the code does not write

- **What happens, with proof (Appendix B).** Not searchable by their words in `troubleshooting.md`: `STA-5: n CorsConfigurationSource beans ...`, `cosec.authorization.shadow.report-interval is at least one millisecond`, `cosec.audit.kafka.delivery=[x] is neither required nor best-effort`, the social `ignore-check-state` message, `The control plane's X is CoSec's own [name], never an application bean`, `cosec.ip2region.ipv6-location [x] does not exist.`, and the "must be positive" family of UB-6. The page quotes `no ApiKeyReader is available for the store <class>`, the code writes `no ApiKeyReader is available: with cosec.api-key.store=redis, a ReactiveStringRedisTemplate is needed.` (`CoSecPipelineAutoConfiguration.apiKeyReader`). `skills/cosec-troubleshoot/SKILL.md` has no entry for the client-address failures (`ClientIpReadings`, no match for "client address", `PIP-16` or `forward-headers`), the largest family of startup messages the starter has.
- **Recommendation:** add a row or a line for each; fix the quoted text; add the client-address entry to the skill (the "Other startup failures" table of `troubleshooting.md` is the place for the short ones). A test that reads every `check`/`require` message of the starter and requires its first words in the page would keep it so.
- **Touches:** docs and the skill only. **Size:** S. **When:** 8.0.x.

### UB-14 (S4): the shipped Kubernetes sample opens CORS to every origin, and keeps a demo route

- **Who and what:** an operator copying `k8s/base/application.yaml`.
- **What happens, with proof.** `spring.cloud.gateway.server.webflux.globalcors.cors-configurations.'[/**]'` has `allowed-origins: "*"`, `allowed-methods: "*"`, `allowed-headers: "*"`, `exposed-headers: "*"`, `max-age: 1800000`. No `allow-credentials`, so cookies do not cross, but any web page may call the gateway with a token it holds and read the answer, and the sample teaches `*` as the starting point of a security gateway; `max-age` is in seconds (20 days; browsers cap it). The same file routes `/cosec-auth/**` to `lb://cosec-auth` through a `simple` discovery list: a demo backend that exists nowhere, in the base the production overlay builds on, and `deployment.md` calls the overlay "a starting point" without listing what to delete.
- **Recommendation:** a placeholder origin with a comment ("your front end's origin"), or leave CORS out of the base and show it in the docs; mark the demo route and its discovery list as the part to replace.
- **Touches:** a sample file; not breaking. **Size:** S. **When:** 8.0.x.

### UB-15 (S4): which starter beans an application may replace is not uniform

- **What happens, with proof.** Of the beans the starter declares, most carry `@ConditionalOnMissingBean` (replaceable; `extending.md` lists the extension points as "one bean, replacing the built-in one"). The control plane's authorization beans are deliberately not replaceable and a `StartupTask` fails when another exists (`cosecControlAuthorizationCheck`). Two sit between: `compositeAuthentication` in `CoSecAuthenticationAutoConfiguration` is `@Primary` with no back-off (an application's own `CompositeAuthentication` makes two primaries: `NoUniqueBeanDefinitionException`), and `tokenCompositeAuthentication` (`CoSecJwtAutoConfiguration.OnAuthentication`) has no back-off at all. An application's own `SnapshotDocumentStore` beside `cosec-cocache` is the known `@Primary` case (not repeated).
- **Recommendation:** `@ConditionalOnMissingBean` on both, or say in `extending.md` that they are not replaceable and why; one table of "replaceable / not / checked" in the page.
- **Touches:** two bean conditions, a doc table; non-breaking for anyone who does not declare them. **Size:** S. **When:** 8.0.x.

## 4b. Findings: deployment

### UB-16 (S1): Redis is the only copy of everything, and no page asks for persistence, replication or a backup

- **Who and what:** an operator provisioning Redis for CoSec (a managed service, a VM, a Helm chart), reading "Redis requirements" in `storage.md` as the list of what to set.
- **What happens, with proof.** `storage.md` ("Redis is the store of record") says the documents and the control plane's records "have no other copy", and `AGENTS.md` names Redis the store of record of the snapshots; the same Redis holds the revoked tokens and subjects, the refresh-token families (sessions), API keys with `cosec.api-key.store=redis`, memberships and shadow reports. The requirements list names `maxmemory-policy noeviction`, the primary reads, ACLs and the client; a search of `storage.md`, `deployment.md`, `security-model.md`, `introduction.md` and `authentication.md` for persistence, AOF, RDB, backup, durability or sentinel finds only "from a backup" (the revision-chain repair) and "durability the control plane relies on" (a link back to the same list). The only Redis in the repository, the dev overlay, runs `--appendonly yes` on an `emptyDir` that its own page says dies with the pod. What an operator who runs Redis without persistence gets: after a restart every platform, app and tenant document is gone and every request is `503` until someone republishes (the history is gone with it, and the docs offer no export of the live documents, only `GET` through the control plane and `verify-chain`); every revocation is gone, so a revoked or subject-revoked token is accepted again until it expires (access tokens last 10 minutes by default, `cosec.jwt.token-validity.access`; any longer you set is the exposure); sessions end. Under asynchronous replication a failover drops the last acknowledged writes, which the page already describes for documents ("regressed from revision a to b") but not for a revocation written just before. Nothing checks any of it: `EvictionPolicyCheck` reads `INFO memory` only, and CoSec never reads `INFO persistence`.
- **Sharing one Redis.** `cosec.key-prefix` is the only isolation (`CoSecProperties.keysOf`); two environments given the same prefix on one Redis share documents, revocations and a control plane, and nothing can tell (a prefix mismatch the other way is UB-3). `noeviction` also means a Redis shared with an application's own LRU caches cannot be shared: the CoSec check refuses to start against any node that may evict, which the page states but does not draw as a sizing consequence.
- **Recommendation:** add to "Redis requirements": persistence on (AOF `appendfsync everysec` at least, or the managed service's equivalent), a replica with failover and what asynchronous replication can lose, a backup of the whole keyspace under the prefix and a restore test, the prefix per environment, a Redis of its own or `noeviction` shared. Add a start-up read of `INFO persistence` (`aof_enabled`, `rdb_last_save_status`) next to the eviction read, as a `WARN` (`cosec.authorization.snapshot.persistence-check`: `warn` default, `fail`, `off`, like `eviction-check`), and say in `deployment.md` how to take the documents out of a running deployment (the control API's `GET /documents/<doc>` for each head).
- **Trade-off:** none unsafe; a managed Redis that hides `INFO persistence` needs `off`.
- **Touches:** docs and an additive startup read (a new property with a non-failing default); not breaking. **Size:** S docs, M check. **When:** docs before RC1; the check 8.0.x.

### UB-17 (S3): no page states the topologies, and the only deployment page is the gateway server's

- **Who and what:** an operator choosing between "a service that enforces", "a gateway that decides", "a service behind it in `gateway` mode", "a service that only verifies tokens", "a login service" and "the control plane".
- **What happens, with proof.** The facts for each exist (Appendix C) in `introduction.md` ("How it fits"), `scenarios/gateway.md` (two topologies), `web.md` ("Services behind the gateway"), `deployment.md` ("Services behind the gateway"), `authentication.md`, and, once #1654 merges, a resource-server scenario: five pages, none a comparison across all six, and none says which modules, properties and network paths each process needs in one table. `deployment.md` is titled "Deploying the Gateway Server" and describes the image; for an application (the first topology, the one the quick start builds) there is no page on probes, Redis, secrets, scaling, shutdown and the Redis ACL as a set: they are in `web.md` (probes), `storage.md` (Redis), `configuration.md` (properties) and `deployment.md` only for the gateway's manifests.
- **Recommendation:** one page, "Choosing a deployment", with Appendix C's table (processes, modules and properties, reaches, trust boundary, what the starter checks, what it does not), and a section "Deploying an application" that links each existing fact once.
- **Touches:** docs only. **Size:** M. **When:** before RC1 (E owns the prose; B owns the facts).

### UB-18 (S3): the control plane and the login service have no deployable artifact

- **Who and what:** an operator who deployed `cosec-gateway-server` and now wants to change a document through the control plane, or to issue tokens.
- **What happens, with proof.** `deployment.md` says the image "has no control plane: documents are published by `cosec-migrate` or by a control-plane application on the same Redis", and stops. `platform/gateway-server/build.gradle.kts` depends on `cocache`, `webflux`, `gateway`, `opentelemetry`, `spring-boot`, `kafka`, `ip2region`, not on `control`; `cosec.authentication.login.enabled: false` is set in its config. So the topology the manifests build ends at the first `cosec-migrate publish`: every later change needs a Spring Boot application the operator writes, with `cosec-control`, `cosec-cocache`, the secret, Redis credentials that may write, and a token for the `(platform)` administrator (`cosec-migrate bootstrap` binds the principal; the quick start issues the token from a demo `ApplicationRunner`). Nothing in `k8s/` or the docs shows that application's deployment, its Redis user, or how an administrator's token is issued in production (a login service: also the operator's own).
- **Recommendation:** either an image (`cosec-control-server`: the control API only, with the admin login left to the operator's identity service) or one worked, deployable example under `wiki/examples/` and `k8s/overlays/control-plane/` (Deployment, its Redis ACL user, `cosec.control.enabled`, `cosec-migrate bootstrap` and a token procedure), so the second day of the gateway topology is not a blank page. An image is new surface; the example is the 8.0 answer.
- **Touches:** docs and a sample; an image would be new (not breaking). **Size:** M (example), L (image). **When:** example 8.0.x; image 8.1.

### UB-19 (S3): `gateway` mode rests on the network alone, and nothing in `k8s/` or at startup helps hold it

- **Who and what:** an operator putting services behind the gateway in `gateway` mode.
- **What happens, with proof.** `IdentityCodec` documents the header as "base64url (no padding) of compact JSON": no MAC, no signature, no shared key; `security-model.md#trust-boundaries` puts the caller's identity under "The gateway, and anyone else who can reach the service". The service logs a `WARN` once at startup (`CoSecGatewayModeAutoConfiguration`). `deployment.md` says to enforce the boundary with a `NetworkPolicy`, a private network or mutual TLS, and `k8s/` ships exactly one `NetworkPolicy`, the gateway's own (`overlays/production/networkpolicy.yaml`, ingress on `http` from any source); a service namespace with no policy, the Kubernetes default, lets any pod send `CoSec-Identity: ...` as the platform administrator. `cosec.pipeline.identity.max-age` (60 s) bounds only how old a captured header may be, which a forger sets to now.
- **Recommendation:** ship a service-side `NetworkPolicy` example (`k8s/examples/gateway-mode-service-networkpolicy.yaml`, "admit only pods labelled as the gateway") and name it in the WARN's link; design, for 8.1, an optional HMAC over the header with a key shared by the gateway and its services (a `cosec.pipeline.identity.key`), which turns the boundary from "reach" into "reach and know a key" without making the safe path harder.
- **Touches:** a sample file; the HMAC is a new, additive property and a format member (a `CoSec-Identity` format change: `ask first`). **Size:** S (sample), L (HMAC). **When:** sample before RC1; HMAC 8.1.

### UB-20 (S3): an HMAC secret makes every verifier an issuer, and the secret has no rotation path

- **Who and what:** an operator who gives the gateway's `cosec.jwt.secret` to services (topologies 3 to 5), or rotates the secret.
- **What happens, with proof.** `cosecTokenAlgorithm` builds one HMAC `Algorithm` from `cosec.jwt.secret` for both signing (`JwtTokenConverter`) and verifying (`JwtTokenVerifier(algorithm)`): the secret of a service that "only verifies" also signs. `scenarios/gateway.md` gives "the gateway's `cosec.jwt.secret`" to a deciding service, and `troubleshooting.md` says to set it "on the issuer and on every service that verifies its tokens". No page says that a compromised verifier can sign a token for any tenant, principal and role, including the `(platform)` administrator of the control plane. For rotation, `troubleshooting.md` offers: "the tokens signed with the old one stop working". There is one `Algorithm` and no key id: during a rolling restart some instances accept only old tokens and the rest only new, so every client sees `token-invalid` on some requests, and all sessions are logged out at once.
- **Recommendation:** state the first plainly in `configuration.md#jwt` and the topology page (a verifier's secret is an issuer's); for rotation, accept a list of verification secrets (`cosec.jwt.previous-secrets`, verify-only, tried after the current one) so a rotation is: add the new as current and keep the old as previous, roll, then drop the old after the refresh lifetime. The asymmetric and JWKS path is the 8.1 identity-provider RFC; this is the stop-gap that fits 8.0.
- **Touches:** docs; a new additive property. **Size:** S (docs), M (rotation). **When:** docs before RC1; rotation 8.1 with the RFC or 8.0.x.

### UB-21 (S3): the image's lean defaults live in a file that the documented way of configuring it replaces

- **Who and what:** an operator mounting their own `config/application.yaml` (the documented way to give the gateway its routes).
- **What happens, with proof.** `applicationDefaultJvmArgs` sets `-Dspring.config.location=file:./config/`, which replaces Spring's default locations, and the jar carries no `application.yaml` of its own (`src/main/resources` holds only the banner). The image's defaults, `cosec.audit.kafka.enabled: false`, `cosec.authentication.login.enabled: false`, `cosec.ip2region.enabled: false`, are the content of `src/dist/config/application.yaml`, which a mounted file replaces whole. `deployment.md` lists them as what the image does, without saying that replacing the file drops them; `k8s/base/application.yaml` repeats all three, which is how that one works. Kafka is in the image (`spring-boot-starter-kafka`, `cosec-kafka`): with `cosec.audit.kafka.enabled` back at its default `true`, `KafkaAutoConfiguration`'s template satisfies `@ConditionalOnBean(KafkaOperations)` and the audit listener is created against `localhost:9092` (read from the auto-configuration conditions; not run); ip2region and login come back on. A second consequence of the same location: `application-<profile>.yaml` is read only from `config/`.
- **Recommendation:** ship the three defaults where an external file cannot remove them: `-Dspring.config.additional-location=file:./config/` (the external file then adds to the defaults in the jar, and wins), with the dist file moved into the jar; or say in "The image" that a mounted configuration must repeat `k8s/base/application.yaml`'s `cosec:` block.
- **Touches:** the image's configuration mechanism; a mounted file that relied on replacing now merges (not breaking for a file that sets what it needs). **Size:** S. **When:** before RC1.

### UB-22 (S3): the shipped configuration and the manifests are never tested against the starter

- **Who and what:** the maintainer renaming a property; an operator applying `k8s/` after an upgrade.
- **What happens, with proof.** `k8s/base/application.yaml` and `platform/gateway-server/src/dist/config/application.yaml` set `cosec.audit.kafka.enabled`, `cosec.authentication.login.enabled`, `cosec.jwt.algorithm`, `cosec.jwt.secret`, `cosec.ip2region.enabled`, `management.endpoint.gateway.access` and Spring Cloud Gateway keys; the starter refuses any `cosec.*` key it does not read (`UnknownProperties`), so a rename (U11 renamed three groups) crash-loops the image. A search of every Kotlin, Python, Kotlin DSL and shell file for those two paths finds none in a test; the CI job `k8s` only builds the overlays and validates them against Kubernetes 1.35 and 1.37 schemas (`kubeconform -strict`), and `aot-smoke.sh` starts the image with its own dist config plus `SPRING_APPLICATION_JSON` and environment variables, never the manifests' file. The properties and probe paths in `deployment.yaml` happen to be right today (read: probes `/actuator/health/liveness` and `/readiness` with `management.endpoint.health.probes.enabled`, ports `http`/8080, `enableServiceLinks: false`, `JAVA_OPTS` after the image defaults, uid 999 as `useradd -r` makes it); nothing keeps them so.
- **Recommendation:** a test in `:spring-boot` that loads both `application.yaml` files through `UnknownProperties.of` (and through `PipelineProperties` binding) and fails on an unknown key; in the `aot-smoke` job, run once with the k8s `application.yaml` mounted.
- **Touches:** tests and CI only. **Size:** S. **When:** before RC1 (the RC freeze is when renames stop).

### UB-23 (S3): no page says how to upgrade or roll back a fleet

- **Who and what:** an operator moving a running deployment from 8.0.x to 8.0.y or 8.1, with a gateway, services behind it, a control plane and Redis.
- **What happens, with proof.** What exists is scattered: "upgrade the services behind a gateway before the gateway" (`web.md`, `upgrading.md` for one specific format change); "a document the control plane writes that an older instance cannot compile is `503` for the requests that need it" (`troubleshooting.md`, `DocumentInvalid`: "finish the rolling upgrade"); "run `repair-references` once every instance runs this version, and again after a downgrade" (`upgrading.md`); and, in the preview sections, several "during a rolling upgrade" cautions (`upgrading.md`, the alpha.1 and alpha.2 sections). The section that holds those preview sections says "These notes are removed when 8.0 is released", and the roadmap (R9) deletes the alpha sections at GA tag. After GA nothing states the order (Redis, control plane, data plane, gateway, or the reverse), what a mixed fleet is promised (document `schema` and `CoSec-Identity` version readers, Redis key formats), that new document members should be published only once every instance reads them, or how to roll a version back (`upgrading.md#rollback` covers 7.x to 8.0 only). A document rollback, by contrast, is complete (`POST /documents/<doc>:rollback`).
- **Recommendation:** an "Upgrading a running deployment" section that survives the preview cleanup: order (control plane and Redis first, services behind a gateway, the gateway last, as the one rule that exists), the compatibility promise per kind of change (patch: none to do; minor: new document members and `CoSec-Identity` members only after the fleet reads them), downgrade (what to rerun: `repair-references`, `verify-chain`), and the `503` signature of a mixed fleet.
- **Touches:** docs only. **Size:** M. **When:** before RC1 (its cleanup is GA).

### UB-24 (S3): scaling is documented in counts and bytes of container, not in bytes of document, and an operator cannot go from a symptom to the knob

- **Who and what:** an operator sizing the heap or tuning `max-tenants`, `max-apps` and the timeouts.
- **What happens, with proof.** `deployment.md` ("Memory") sizes the gateway process well (heap, direct memory, native, a measured RSS), and says the compiled documents "live on the heap: size it for your documents and traffic", then gives no figure for a document. The bound the code gives is a count: `max-apps` and `max-tenants` default to 1024 each (`RuntimeAuthorizationProperties.Snapshot`), and a document may be 8 MiB (`ChangeOptions.maxDocumentBytes`, checked on control-plane writes; its compiled size is not stated anywhere). The dev overlay's heap is 256 MiB and the production overlay's 1 GiB; with `ExitOnOutOfMemoryError` a deployment whose tenants' documents are large cycles pods, and the pod's symptom (`OOMKilled`, or a Java `OutOfMemoryError` restart) names neither `max-tenants` nor a document. The snapshot meters count entries held (`cosec.snapshot.installed`) but carry no bytes. The other direction is well mapped: "sustained `evictions` with `loads{result=cold}` rising: raise `max-apps` / `max-tenants`" (`observability.md`), and "slow stage: find it in the trace". Per-request timeouts (`pipeline.timeout` 5 s, stage timeouts, `cold-load-timeout` 2 s) are documented individually; UB-11 covers the one pair that conflicts.
- **Recommendation:** measure and print: bytes of heap per held document of a typical and of a maximal document (the benchmark `docs/perf/` has the harness), write "heap ≈ documents held × size × k" with the two defaults in the memory section, add a gauge for the sum of held source sizes, and put "OutOfMemoryError or heap pressure: lower `max-tenants`/`max-apps` or raise the heap" in the troubleshooting symptom list.
- **Touches:** docs, one meter. **Size:** M. **When:** 8.0.x.

### UB-25 (S4): the gateway image's tags are not listed, and which one to deploy is not said

- **Who and what:** an operator writing the `image:` line.
- **What happens, with proof.** `deployment.md` names three registries; `docker-deploy.yml` tags every push with the branch (`main`, `7.x`), the pull request, the full semantic version and `major.minor`, and builds nightly. The page does not list those tags, say that the branch tags move with every merge, or advise a version tag (or a digest) in production; `k8s/base/kustomization.yaml` pins `newTag: 8.0.0-beta.3`, set by the release pull request (`CONTRIBUTING.md`), which is the right practice and is not written where an operator reads.
- **Recommendation:** two sentences in "The image": use a version tag or a digest; `main`, `7.x` and `8.0` float.
- **Touches:** docs only. **Size:** S. **When:** 8.0.x.

## 5. What is good and must not be changed

- The unknown-property check as the first startup task (a `BeforeBeans` task; STA-3 keeps every check eager under lazy initialization), with Kubernetes service links exempted by shape, and no switch to turn it off.
- `CoSecModuleCheckAutoConfiguration` (STA-1): each missing module is named with its artifact and the feature that needs it; a gateway always strips the client's `CoSec-Identity`, whatever `cosec.enabled` says.
- The eviction check (`SNP-3`) refusing to start, the monitor re-reading the policy, and the indicator being an alert kept out of readiness; the replica-read check; the client-address check that refuses to guess (`PIP-16`).
- One startup message for all three missing token stores, the `NONE` warning, the JWT secret length check that names the key.
- Readiness that follows the platform document and latches; liveness untouched; the probe documentation (STA-4) and the manifests' probe choice; `restricted`-level pod security and `enableServiceLinks: false` in `k8s/`.
- `cosec.control.enabled` and `cosec.api-key.enabled` parsing `true`/`false` only (the model UB-1 asks the others to follow).
- The configuration page: every property documented, every default I compared equal to the code.
- The audit log's WARN/ERROR split, the per-cause `indeterminate` metric, and the `me.ahoo.cosec.audit` request-id trail described in "Find out what decided".
- The CoCache locks and the control plane's non-replaceable beans.
- The deployment page's honesty about memory (a measured RSS, ZGC's up-front commit, the fixed `-Xmx` beating `MaxRAMPercentage`), the probe guidance (never `/actuator/health` for a probe, liveness never on the platform document), the preStop plus graceful shutdown plus drain arithmetic that matches `terminationGracePeriodSeconds: 45`, and `unhealthyPodEvictionPolicy: AlwaysAllow` so a drain never waits for a platform document.
- The pod hardening of `k8s/` (non-root, read-only root, dropped capabilities, no service-account token, `enableServiceLinks: false`) and a base that pins the image tag.
- The documented rollback of a document through the control plane, and the revision chain with `verify-chain`.

## 6. For another group

- **A (code surface):** `JwtTokenConverter.membershipClaimLimit` has no property in the starter; the page `authentication.md` says the starter's converter has no limit, so a principal with many roles issues a token that can exceed a server's header limit. `compositeAuthentication` `@Primary` (UB-15) touches an SPI-adjacent bean.
- **C (policy and control plane):** `cosec.authorization.shadow.enabled=false` on every instance leaves a shadow window with no samples, which closes only at `max-duration` as insufficient evidence, and nothing says so at publish; the control-plane validation texts (`Invalid change options.`) name no value; `cosec-migrate publish` in the quick start needs `--key-prefix` when the application's prefix is not `cosec`, and a wrong prefix is UB-3.
- **D (model):** nothing found in my scope.
- **E (documentation):** quick start prerequisites are long (Redis, `cosec-migrate`, CoCache 8 starter) but correct; its dependency block has no Actuator, though the text names `/actuator/health/readiness` ("with Spring Boot Actuator"); the `docker run` example needs the JMX sentence (UB-2); troubleshooting rows listed in UB-13; the Redis requirements and fleet-upgrade prose of UB-16 and UB-23; the topology page of UB-17 (B owns the facts, Appendix C).

## 7. Triage table (one line per finding; no new findings, no re-ranking)

| id | severity | defect | the fix touches | breaking | size | when | needs the maintainer |
|---|---|---|---|---|---|---|---|
| UB-1 | S1 | Empty or non-`true` switch values (`cosec.pipeline.mode=`, `cosec.enabled=1`) turn CoSec off silently | bean condition, message | behaviour (malformed values only) | S-M | before RC1 | yes: semantics of malformed values |
| UB-2 | S1 | Gateway image starts unauthenticated JMX on port 5555, every interface | the image, docs | behaviour | S | before RC1 | yes: removes remote-JMX default |
| UB-3 | S2 | Absent platform document: instance unready, no log line says why | message | no | S | before RC1 | no: log lines only |
| UB-4 | S3 | `max-trusted-index` default 1 lets direct clients pick their IP | message; default only if stricter | no (WARN); behaviour (strict) | S | before RC1 | yes: default's trade-off |
| UB-5 | S3 | Startup logs no summary of mode, JWT, limiter, listeners, "CoSec off" | message | no | S | before RC1 | no: log lines only |
| UB-6 | S3 | Invalid-value messages name Kotlin parameters, not the `cosec.*` property | message | no | M | 8.0.x | no: messages only |
| UB-7 | S3 | Metadata has no defaults; nothing ties the docs table to code | build, tests, metadata | no | M | 8.0.x | no: tooling only |
| UB-8 | S3 | Decision timer cannot split 401/403 or token reasons; fail-open has no health | metric tag, a bean | no | S-M | before RC1 (tag) | no: additive tag |
| UB-9 | S3 | Kafka, metrics, gateway-mode properties silently do nothing when unmet | message | no (WARN); behaviour (fail) | M | 8.0.x | yes: warn versus fail |
| UB-10 | S3 | Gateway host's own controllers sit outside CoSec; no startup warning | message | no | S-M | before RC1 or 8.0.x | yes: severity call |
| UB-11 | S3 | Ready instance holds platform only; `cold-load-timeout` unchecked against `pipeline.timeout` | docs, message | behaviour (invalid combos fail) | S | 8.0.x | no: fixes wrong values |
| UB-12 | S4 | Same concept spelled differently across property groups | docs; property names if renamed | property (if renamed) | S docs, M rename | docs 8.0.x; rename before RC1 or 9.0 | yes: renames break configs |
| UB-13 | S4 | Some startup messages missing from troubleshooting; one quoted text wrong | docs, skill | no | S | 8.0.x | no: docs only |
| UB-14 | S4 | k8s base sample ships CORS `*` and a demo route | a manifest | no | S | 8.0.x | no: sample file |
| UB-15 | S4 | Two starter beans neither back off nor are documented non-replaceable | bean condition, docs | no | S | 8.0.x | no: unused by apps |
| UB-16 | S1 | Redis persistence, replication, backup never required; nothing checks persistence | docs, a new property, message | no | S docs, M check | docs before RC1; check 8.0.x | no: documents requirement |
| UB-17 | S3 | No page states topologies; no deployment page for applications | docs | no | M | before RC1 | no: docs only |
| UB-18 | S3 | No deployable control plane or login service; gateway topology stalls after first publish | docs, sample manifest; image optional | no | M example, L image | example 8.0.x; image 8.1 | yes: a new image |
| UB-19 | S3 | `gateway` mode trusts an unsigned header; no service-side NetworkPolicy shipped | a manifest; HMAC adds property and format | no (sample); property (HMAC) | S sample, L HMAC | sample before RC1; HMAC 8.1 | yes: header format change |
| UB-20 | S3 | One HMAC secret: verifiers can mint tokens; rotation invalidates every token | docs; a new property | property (additive) | S docs, M rotation | docs before RC1; rotation 8.0.x or 8.1 | yes: new security property |
| UB-21 | S3 | Mounting `config/application.yaml` drops image defaults (Kafka audit, login, ip2region) | the image's config mechanism | behaviour | S | before RC1 | no: restores intended defaults |
| UB-22 | S3 | Shipped yaml and manifests never tested against the unknown-key check | tests, CI | no | S | before RC1 | no: tests only |
| UB-23 | S3 | No fleet upgrade, mixed-version or version-rollback guidance after GA | docs | no | M | before RC1 | yes: states compatibility promise |
| UB-24 | S3 | No per-document heap figure; OOM symptom not mapped to knobs | docs, a meter | no | M | 8.0.x | no: measurement and docs |
| UB-25 | S4 | Image tags unlisted; floating tags not told from version tags | docs | no | S | 8.0.x | no: docs only |
