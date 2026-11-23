# CoSec 8.0 RC deep review, round 1, area G: the host, its images and the extensions

The first round of the two deep reviews RC1 requires of the integrated system (`docs/roadmap.md` §4, RFC 0008 §3.9
prerequisite 6), for area G: the Spring Boot starter (`host/spring-boot`), the gateway image
(`platform/gateway-server`), the Kubernetes manifests (`k8s/`) and the extensions that hold tokens, login and
observability (`extensions/jwt`, `login`, `social`, `kafka`, `micrometer`, `opentelemetry`, `cosid`, `ip2region`). The
request path, the adapters and token verification in `core/runtime` were area C; the snapshots and the Redis stores
area D; the control plane areas E and F. Their findings are not repeated here.

| | |
|---|---|
| Commit reviewed | `origin/main` at `b06fd180` (#1921) |
| Date | 2026-11-23 |
| Scope | `host/spring-boot` (120 Kotlin files, 10,886 lines, and `AutoConfiguration.imports`); `platform/gateway-server` (`GatewayServer.kt`, `application.yaml`, `build.gradle.kts`, `Dockerfile`); `k8s/` (22 files, 1,030 lines); `extensions/jwt` (5 files, 837 lines), `login` (24, 1,555), `social` (13, 631), `kafka` (3, 369), `micrometer` (3, 483), `opentelemetry` (2, 226), `cosid` (1, 82), `ip2region` (1, 98, without its `.xdb` data). 173 Kotlin files, 15,195 lines, and the manifests |
| Reviewer | one read-only session, which read the starter, the image and the manifests itself, and one read-only sub-reviewer it briefed for the extensions; none is an author of the code reviewed. Every finding below was checked against the code by the lead reviewer. No code, test, rule or RFC was changed |
| Method | the trust boundary first, then every file read end to end against RFC 0011 §6, RFC 0009 §11, RFC 0006 §7.3 and §9.5, the `STA` rules, `IDN-13` to `IDN-34`, the `OBS` rules, `wiki/operations/configuration.md`, `deployment.md`, `wiki/integration/authentication.md` and `observability.md`, with an attacker's and an operator's view: for each startup check, the property combinations that would route around it. One probe was run and deleted (below) |

**Severity** as in areas A and E. **S1**: a request permitted that should be denied, cross-tenant access, privilege
escalation or fail open, remotely and without privilege. **S2**: the same with a privileged position or a rare
condition; a denial of service a low-privileged party triggers cheaply; a credential leak. **S3**: weakened defence in
depth, a denial of service that needs privilege, behaviour that misleads an operator, a mismatch between the
specification and the code with no exploitable path. **S4**: hardening and documentation.

## Summary

No S1 and no S2. No property combination was found that starts a host with a weaker gate than its log says. Every
startup check (`STA-1` to `STA-12`) runs under the conditions of the feature it guards, and is a `StartupTask`, which
lazy initialization never defers. A value a condition cannot read fails startup rather than switching a feature off.
A probe confirmed that an application's own filter registrations, typed, star-projected or raw, never make the
starter's servlet filter back off. The token, login and social extensions hold `IDN-13` to `IDN-30`: no unsigned
token, no access and refresh confusion, no issue on a failed revocation read, the social state single-use and bound to
the browser. No metric tag is client-controlled.

The two S3s:
- The shipped control-plane manifests state the network boundary for the operator. The dev overlay, meant for kind,
  then runs a control plane any pod of the cluster can drive as the platform administrator, since kind's network
  plugin does not enforce the policies the statement rests on.
- Session family records have no bound per principal, and each lives the whole session lifetime: an authenticated
  user who signs in or switches tenant in a loop fills a `noeviction` Redis.

Five findings that also affect the released `7.x` line are handled privately (`SECURITY.md`) and are not described
here.

| Id | Severity | Area | Finding | Status |
|---|---|---|---|---|
| DR1-G-1 | S3 | k8s | `k8s/control/deployment.yaml` makes the `STA-7`/`STA-9` statement for the operator; on the dev overlay's kind cluster, whose network plugin does not enforce `NetworkPolicy`, any pod can call the control plane as the platform administrator and approve its own change | open |
| DR1-G-2 | S3 | login, cocache | Session family records are unbounded per principal and each lives `cosec.jwt.session.lifetime` (30 days), whether or not it is ever refreshed: a login or tenant-switch loop fills the `noeviction` Redis, and every write then fails | open |
| DR1-G-3 | S4 | login | A refresh moves the family's generation before the pair is issued; a converter failure or a cancellation after it leaves the client with the previous generation, which the next retry past the leeway reads as reuse and ends the session | open |
| DR1-G-4 | S4 | kafka, docs | A BEST_EFFORT Kafka audit record lost asynchronously is counted by no meter; `observability.md` gives the audit listener a fixed 1,024 waiting sends, which is the change listener's | open |
| DR1-G-5 | S4 | starter | On a host in `cosec.pipeline.mode=gateway` with the control plane's classes, no control plane is wired and nothing says so, while the startup notice says it serves its routes | open |
| DR1-G-6 | S4 | starter | `STA-1` checks that the web adapter's module is on the classpath, not that its filter was registered: an excluded auto-configuration leaves a host with no gate, said only by `web=none` in an INFO line | open |
| DR1-G-7 | S4 | architecture | No rule states that `platform/*` depends only on published modules; it holds today only by the combination of two other rules | open |
| DR1-G-8 | S4 | gateway image | The image's application directory, its jars and AOT cache included, is owned by the user it runs as | open |
| DR1-G-9 | S4 | docs | `authentication.md` says the social state store runs on a dispatcher of its own; `JwtProperties` says a revocation is kept one second longer than the refresh validity, which only CoCache's store does | open |

Five further findings (three S3, two S4) also affect `7.x` and are handled privately.

## Files read

Every main source file of the scope, end to end, by the lead reviewer for the starter, the image and the manifests, and
by the sub-reviewer for the extensions. Line counts include the license header.

| Part | Files | Lines |
|---|---|---|
| starter, root package (module, property and switch checks, the startup summary, `StartupTask`) | 16 | 1,437 |
| starter `pipeline/` (the pipeline, snapshot, web, gateway-mode and identity-guard auto-configurations, client address, eviction and replica checks, readiness, derived bounds) | 38 | 4,301 |
| starter `control/` (the control plane's wiring, routes, service port, maintenance, store format) | 27 | 2,384 |
| starter `authentication/`, `route/`, `social/` | 16 | 1,240 |
| starter `jwt/`, `apikey/`, `actuate/`, `audit/`, `authorization/cache/`, `micrometer/`, `openapi/`, `cosid/`, `ip2region/` | 23 | 1,524 |
| `AutoConfiguration.imports` | 1 | 25 |
| `platform/gateway-server`: `GatewayServer.kt`, `application.yaml`, `build.gradle.kts`, `Dockerfile` | 4 | 204 |
| `k8s/`: `base/`, `control/`, `overlays/dev`, `overlays/production`, `examples/`, `README.md` | 22 | 1,030 |
| `extensions/jwt`, `login`, `social`, `kafka`, `micrometer`, `opentelemetry`, `cosid`, `ip2region` | 52 | 4,281 |

**Not read:** the gateway image's `banner.txt` and `aot-smoke.sh` beyond its header; `ip2region.xdb` (data). Tests were
read only to decide whether a property has a guard.

## Trust boundary

| Input | Comes from | Verified before it is used | What the code assumes |
|---|---|---|---|
| `cosec.*` properties and environment variables | the operator | every `cosec.*` key must be one CoSec reads (`UnknownProperties`); every switch a condition reads must hold a value it accepts (`CoSecSwitches`, `apiKeysEnabled`, `kafkaAuditDelivery`, `ControlPlaneCondition.written`) | a value is the operator's intent; the checks make a misspelt or unreadable one fail startup rather than switch a feature off |
| The classpath | the application's build | the module checks (`STA-1`) name a missing module a feature needs | a class present means its module is meant to be used; a Spring Cloud Gateway class on the classpath makes the host a gateway |
| `CoSec-Identity` on a host whose gate is an `IdentityGate` | whoever reaches the host | its codec, age and size (area C); nothing proves who wrote it | the operator's statement (`STA-7`, `STA-9`) that only the gateway, or the services named, can reach the host |
| `X-Forwarded-For`, `Forwarded` | the client, then each proxy | `ClientIpReadings` picks one reader per host from the server and its forwarded-header strategy, and refuses a setup where no reading is safe (`PIP-16`) | `max-trusted-index` is the number of proxies in front of the host |
| Login, refresh, logout and social callback bodies | anonymous clients | as `IDN-13` to `IDN-25` and the routes' body limits | the routes are decided by the documents like any route (`IDN-23`) |
| Kubernetes manifests | the project, applied by the operator | `ControlManifestsTest` holds the control host's statement to its three policies | the cluster's network plugin enforces `NetworkPolicy` |

Attackers considered: an anonymous client; an authenticated user; a tenant administrator; a workload inside the
cluster; an operator who sets a property wrongly, leaves one at its default, upgrades or rolls back in the wrong order,
or trusts a startup line; someone with Redis write access (out of the model).

## Findings

### DR1-G-1 · S3 · The shipped manifests make the control host's boundary statement for the operator

- **Location:** `k8s/control/deployment.yaml` sets `COSEC_PIPELINE_IDENTITY_ONLYTRUSTEDCALLERSCANREACH=true`;
  `k8s/overlays/dev` includes `../../control`. `k8s/README.md` describes the dev overlay as "for trying CoSec out on any
  cluster, kind included" and, further down, says kind's network plugin does not enforce the policies.
  `test/system-test/kind/run.sh` applies the dev overlay on kind and notes "Enforcement is not tested".
- **Trigger:** `kubectl apply -k k8s/overlays/dev` on kind, or on any cluster whose network plugin does not enforce
  `NetworkPolicy` (the production overlay inherits the same statement).
- **Actual:** the control host starts, believing every `CoSec-Identity` header (`STA-9`). Any pod of the cluster
  reaches `cosec-control:80` (a ClusterIP Service), states the platform administrator, publishes any policy of any
  tenant, and approves it by stating a second principal (`GatewayModeTrust.exposure` says exactly this).
- **Expected:** `STA-7` makes the host refuse to start until the operator states the boundary, "once it is true"
  (`GatewayModeTrust.requireBoundaryStated`). The statement exists so that a person checks the network before the host
  believes the header. The manifest writes it in advance, so the check passes on a cluster where it is false, and
  `ControlManifestsTest` can only hold the statement to the presence of the policies, not to their enforcement.
- **Why S3:** the consequence is the whole control plane, but it needs a workload inside the cluster and a network
  plugin that does not enforce policies. The comments in three places tell the operator to test enforcement. The
  coordinator may weigh S2 for the dev overlay, which is offered for a plugin known not to enforce.
- **Reproduced:** no; by the manifests, the README and the system test's notes.
- **Fix direction:** keep the statement out of `control/` and have each overlay write it: the production overlay beside
  a step that proves enforcement (a pod of another label that must fail to connect, run as a Job before the control
  Deployment), the dev overlay not at all, running the control host on a loopback-only address with the gateway as its
  only caller, or with a WARN that names the plugin's lack of enforcement. At least, say in the dev overlay's first
  lines that it is unsafe on a shared cluster.
- **Regression guard:** a `ControlManifestsTest` case that fails when `control/` itself carries the statement, and one
  that requires the enforcement check beside the overlay that writes it.

### DR1-G-2 · S3 · Session family records are unbounded and each is kept for the whole session lifetime

- **Location:** `TokenFamilies.create`, called by `start` (every login) and `startAt` (every tenant switch,
  `SwitchTenantAuthentication` through `LiveSessions.Live.fork`). `CoCacheTokenFamilyStore.lifetime` gives each record
  the time left until `family.expiresAt`, that is login + `cosec.jwt.session.lifetime` (30 days by default).
- **Trigger:** an authenticated user calls the application's login or tenant-switch route in a loop; a switch to the
  same tenant is accepted where the application's `TenantSwitch` accepts it.
- **Actual:** each call writes one `<prefix>:token:family:<id>` record that lives 30 days, though its refresh token
  lapses after 7 days unused. Nothing bounds the records per principal. Under the `noeviction` policy CoSec requires,
  Redis eventually refuses every write: logouts and revocations, refreshes and control-plane publishes fail with 503.
  Reads continue, so nothing fails open.
- **Expected:** what one party can make the store hold is bounded, as the control plane's records are meant to be
  (DR1-F-6) and the anonymous social starts should be. `storage.md` says "one key per live session", with no bound
  and no sizing.
- **Why S3:** a denial of service of every write, but it needs a valid login per call and a long loop; a rate-limit
  rule on the login and switch routes contains it. It is the authenticated analogue of a privately handled finding of
  area D, with a TTL ten thousand times longer.
- **Guard:** none found.
- **Reproduced:** no; by the code.
- **Fix direction:** expire a family at the earlier of its lifetime's end and its last rotation plus the refresh
  validity (rewritten at each rotation), and cap the live families of a principal, ending the oldest. Document the
  rate-limit rule for the login and switch routes, and the sizing in `storage.md`.
- **Regression guard:** a `CoCacheTokenFamilyStore` test that an unrefreshed family's key expires with its refresh
  token; a test of the cap.

### DR1-G-3 · S4 · A refresh spends a generation before the pair is issued

- **Location:** `RefreshTokenAuthentication.authenticate` moves the family (`live.next(token)`, the conditional write
  of `TokenFamilies.rotate`); only then does `RefreshRoute.answer` call `converter.toToken(...)`, and
  `TokenCompositeAuthentication` check that the caller is still active.
- **Trigger:** after the write, the converter throws (an application `TokenConverter` over a remote signer that is
  down; a `MembershipRefresh` that answers an attribute `checkIssuable` refuses), or the client disconnects.
- **Actual:** the route answers 500 and the client keeps the previous generation. A retry within
  `rotation-leeway` (30 s) gets the current pair (`IDN-16`); the first retry after it is read as reuse, the family is
  revoked, and the user is signed out. `RouteFailure`'s KDoc says "Neither 503 nor 500 ever ends a client's session";
  `RefreshTokenAuthentication`'s "spends no generation" covers only a refused membership.
- **Fix direction:** make the pair, or at least run the issuability checks, before the conditional write and drop it
  when the write loses; correct the KDoc.
- **Regression guard:** a test where the converter throws once after the rotation and a retry past the leeway still
  refreshes, or is refused without revoking the family.

### DR1-G-4 · S4 · Lost BEST_EFFORT audit records are not metered

- **Location:** `KafkaAuditListener.onDecision` hands a record to `BoundedSender`; an asynchronous failure
  (`queued.sent.whenComplete`) goes to `failed`, which counts in a private `failures` reset by each WARN line. The
  listener meter `cosec.listener.failures` counts only what throws synchronously: a full or closed queue, a
  serialization error.
- **Trigger:** a broker that answers metadata but fails or times out deliveries.
- **Actual:** every record is lost with a WARN every 10 seconds, and no meter moves until the producer's buffer and the
  send queue fill. Separately, `observability.md` (and its Chinese twin) says the audit listener sends "with at most
  1,024 sends waiting"; the starter gives it a capacity derived from the heap (`DerivedHeap.budget().decisionQueueCapacity`),
  and 1,024 is `KafkaChangeListener`'s default.
- **Fix direction:** count asynchronous failures in the listener's statistics or a `cosec.audit.kafka.failures`
  counter; correct both pages.
- **Regression guard:** a test with a producer whose future fails, asserting the meter.

### DR1-G-5 · S4 · Control-plane classes on a gateway-mode service: no control plane, and a notice that says otherwise

- **Location:** `CoSecControlAutoConfiguration` is `@ConditionalOnBean(PolicySnapshotCache)`, which
  `CoSecSnapshotAutoConfiguration` makes only in `enforce` mode. `CoSecControlServicePortAutoConfiguration` is
  conditional on `ControlPlaneCondition` alone (classes present, `cosec.control.enabled` not `false`), and its
  `cosecControlPlaneNotice` logs "Control plane: HTTP routes on /cosec/control (port …)" at startup.
  `CoSecModuleCheckAutoConfiguration.ControlPlane` checks the classes only.
- **Trigger:** a service in `cosec.pipeline.mode=gateway` whose classpath has `cosec-control` and `cosec-cocache`, with
  `cosec.control.enabled` unset or written `true`.
- **Actual:** no control plane, no routes, no word; the notice says the routes are served. `GatewayModeTrust` already
  treats the control plane as never wired in `gateway` mode. The direction is safe (nothing is served).
- **Expected:** `STA-1`: a feature turned on by a property never silently does nothing.
- **Fix direction:** fail startup when `cosec.control.enabled=true` is written in `gateway` mode, and make the notice's
  condition the control plane's actual wiring.

### DR1-G-6 · S4 · `STA-1` checks the adapter's module, not the adapter

- **Location:** `CoSecModuleCheckAutoConfiguration.WebMvc` and `WebFlux` check `ClassUtils.isPresent` of the filter
  class. The filter is registered by `CoSecPipelineWebAutoConfiguration`.
- **Trigger:** `spring.autoconfigure.exclude` names `CoSecPipelineWebAutoConfiguration` (or the application's own
  `FilterRegistrationBean<PipelineServletFilter>` or `PipelineWebFilter` bean replaces CoSec's with one it never
  registers).
- **Actual:** the host starts with no gate. `StartupSummary` says `web=none` in its INFO line; nothing else.
- **Fix direction:** an `AfterSingletons` check on a web host with CoSec enabled that a gate is registered (one of the
  three adapter beans, or a registration whose filter is CoSec's), failing startup otherwise; or, at least, the
  summary at WARN when `web=none` on a web application.

### DR1-G-7 · S4 · No rule that the images depend only on published modules

- **Location:** `DependencyRulesTest` has `main code never depends on a test or platform project` and
  `the control server is the starter with the control plane, and no gateway, Servlet stack, login or CosId` (a
  deny-list). The gateway image has no rule; "the platforms have no facts" (`DependencyRulesTest`'s own KDoc).
- **Actual:** today every project outside `test/` and `platform/`, except the `bom` and the internal `dependencies`
  platform, is published, so the property holds by composition. A future unpublished project outside `test/` (a tool, a
  sample) could be declared by an image without any rule failing.
- **Fix direction:** an allow-list rule: the projects a `platform/*` project declares are in the published set (the
  root build's `publishProjects`), plus `dependencies`.

### DR1-G-8 · S4 · The image's application directory is writable by the process

- **Location:** `platform/gateway-server/Dockerfile` copies the distribution with `--chown=appuser:appgroup` and runs
  as `appuser`, so the jars, the start script and the AOT cache are the process's own to rewrite.
- **Actual:** the Kubernetes base mounts the root filesystem read-only, which closes it there; `docker run` as the
  quick start shows does not.
- **Fix direction:** copy as root with read-only modes, and give `appuser` only `logs/` and `data/`.

### DR1-G-9 · S4 · Two statements the code does not bear out

- `wiki/integration/authentication.md` says the social state store's Redis calls run on a dispatcher of the store's
  own, at most 16 threads. `RedisSocialStateStore` awaits the reactive client and has no dispatcher (its KDoc says
  so). Fix the page in both languages.
- `JwtProperties.TokenValidity.refresh` says a revocation is kept "this long, plus one second". The second is added by
  `CoCacheTokenStore.revoke`; another `TokenStore` gets the bare TTL. Say where the second comes from.

## Threats checked and found sound

| Threat | Code | Rule and guard |
|---|---|---|
| A value a `@ConditionalOnProperty` would read as "off" (`1`, `on`, empty) switching a feature off without a word | `CoSecSwitches` (checked against the compiled conditions by `CoSecSwitchesTest`), `apiKeysEnabled`, `kafkaAuditDelivery`, `ControlPlaneCondition.written` | UB-1 |
| A misspelt or removed `cosec.*` key doing nothing; Kubernetes service links refused | `UnknownProperties` (relaxed names), `RemovedProperties`, `RetiredCacheSettings`, `KubernetesServiceLinks` (process environment only, exact shapes) | `UnknownPropertiesTest`, `STA-12` |
| A check skipped under `spring.main.lazy-initialization` | every check is a `StartupTask`; `StartupTaskExclusion` is imported wherever one is declared | `STA-3`, `StartupTaskTest` |
| An application filter registration making CoSec's servlet filter back off | `@ConditionalOnMissingBean` on `FilterRegistrationBean<PipelineServletFilter>` | **probe, run and deleted:** with an application registration typed `FilterRegistrationBean<Pass>`, star-projected, or raw (`withBean(FilterRegistrationBean::class.java)`), `cosecPipelineServletFilter` was still registered |
| `gateway` mode or the forwarded-identity caller on a Spring Cloud Gateway host forwarding every request unchecked | `CoSecIdentityGuardAutoConfiguration.cosecPipelineModeCheck`, unconditional; the identity strip and headers filters with CoSec off too | `STA-9`, `IdentityGuardAutoConfigurationTest` |
| A host that believes `CoSec-Identity` without the operator's statement, by property or by an application's own `IdentityGate` | `CoSecGatewayModeAutoConfiguration.cosecGatewayModeBoundaryCheck`, `CoSecPipelineWebAutoConfiguration.cosecWebGateTrustCheck`; values read through the binder, `true` only | `STA-7`, `STA-9`, `GatewayModeTrustTest`, `ForwardedIdentityCallerTest` |
| A hidden-method filter re-deciding the method behind a gateway, by property, bean or `FilterRegistrationBean` | `GatewayModeTrust.hiddenMethod` | `STA-6` |
| An actuator endpoint, `gateway` above all, on the gateway's application port | `CoSecGatewayEndpointCheckAutoConfiguration.requireProtected`; the image serves the actuator on 8081 with `gateway` access `none` | `STA-2` |
| A client choosing its own address through a forwarded-header strategy | `ClientIpReadings`: `framework` refused, `web-server` read per server, unknown strategies read strictly, rewriting filters refused, the members read by reflection pinned | `PIP-16`, `ClientIpReadingsTest` |
| The control API or the login routes on the management port, or the application's routes on the service port | `ApplicationPort.predicate`, `ServicePortFilter`, `ServicePortRoutes` | `CTL-58`, `CTL-69`, `STA-4` |
| A control route reached without the pipeline on a gateway host (a path the `ControlRoutesWebFilter` prefix test misses, e.g. an encoded segment) | the routes read the security context `RequestSecurity.of` accepts, and `ControlHttp` answers 401 without one | `RC1-41` |
| An application bean replacing the control plane's own authorization | `cosecControlAuthorizationCheck` (`NOT_REPLACEABLE`) | RFC 0011 D4, `StarterWiringTest` |
| An approval outliving its approver's revocation | `CoSecControlAutoConfiguration.checkRetention` | `RC1-11` |
| Redis evicting documents or revocations; reads from a lagging replica | `EvictionPolicyCheck` (stricter than CoCache's: `volatile-*` refused), `EvictionPolicyMonitor`, `ReplicaReads` | `SNP-3`, `RC1-47` |
| A refresh route that silently copies memberships | `RefreshMembershipChoice` | `STA-8` |
| A JWT secret too short for its algorithm, or a missing token store making logout do nothing | `cosecJwtSigning`, `TokenStores` stand-ins | `RC1-24`, RFC 0008 S1b |
| Secrets in startup lines | `StartupSummary`, `RedisLocation` (no credential), the property and switch messages (key names and switch values only) | UB-4, UB-5 |
| Unsigned tokens; access and refresh tokens taken for each other | `Jwts.requireSigning`; `toVerifiedToken`, `requireRefreshToken`, `requireSameSession` | `IDN-13`, `IDN-28` |
| Clock skew widening a token's life | only `iat` and `nbf` get the skew; `exp` is strict | `IDN-29` |
| A cached verification outliving the token, or skipping revocation | `VerifiedTokenCache`: SHA-256 of the whole token, successes only, answered only inside the window, revocation and reserved-attribute checks after it on every request | `IDN-32` |
| A token issued on a failed revocation or family read; two refreshes of one pair both moving it | `LiveSessions`, `TokenFamilies` (conditional create and replace, `notAfter`, marks rather than deletes) | `IDN-14` to `IDN-19` |
| A forged or replayed social callback | 128-bit state from `SecureRandom`, taken with `GETDEL` before the provider is called, binding compared in constant time, `ignore-check-state` refused at startup | `IDN-20` |
| A REQUIRED Kafka audit failure permitting | a failed `deliver` turns a permit into 503 | `OBS-1`, `OBS-2`, `ListenerIsolationTest` |
| Client-controlled metric cardinality; ids in tags | every tag is a closed set; ids are span attributes only | `OBS-5`, `OBS-6` |
| ip2region reading a client-written address, or skipping a DENY for an unsupported address | it reads the resolver's `remoteIp`; an address of a version without a database is unavailable (Indeterminate) | `PIP-16`, round 2 M5 |
| Defaults that differ between the configuration page and the code | every row of `configuration.md`'s tables compared with its property class | none needed: they agree |

## Coordinator's candidates

**`cosec-kafka` writes audit records by data binding and has no `RuntimeHintsRegistrar`.** Not a defect in 8.0:
Spring AOT and GraalVM native images are unsupported (`deployment.md`, "AOT cache"), and the images' AOT cache is the
JDK's (JEP 483), which keeps ordinary reflection. Were native images supported, Jackson would throw on the
`AuditEventData` it cannot introspect: a REQUIRED listener would turn every permit into 503 (fail closed), a
BEST_EFFORT one would drop and count. The change listener builds its JSON by hand and needs no hints. When native
support lands: hints for the audit records and a native smoke test. Graded **no finding**.

**Nothing guards that `platform/*` depends only on published modules.** Confirmed, and graded S4 (DR1-G-7): the
property holds today by the composition of `NEVER_DEPENDED_ON` and the published set.

**Can a combination of properties route around a startup check (`STA-1` to `STA-12`)?** Each was read with its
conditions. No combination weakens the gate while the log claims otherwise. In particular:
- `cosec.enabled=false` turns every check off except the unknown-property check, the gateway's identity strip and
  the mode check, and logs one WARN that nothing is protected. This is the documented off switch.
- `STA-1` follows each feature's own conditions; the adapter check is by class, not bean (DR1-G-6). A control plane
  written `true` on a `gateway`-mode service is silently absent (DR1-G-5).
- `STA-2` runs only in `enforce` mode, and `gateway` mode on a gateway host fails startup unconditionally.
- `STA-6`, `STA-7` and `STA-9` follow the gate, by property or by bean. An application `RequestGate` that wraps an
  `IdentityGate` without being one would escape them; that is application code.
- `STA-8` is skipped where the application declares its own `RefreshTokenAuthentication`, by design.
- `STA-11` and the service port's refusals run before any bean.
- The shipped manifests answer `STA-7` for the operator (DR1-G-1).
- `cosec.authorization.snapshot.eviction-check=warn` and `cosec.authentication.revocation.fail-closed=false` (the
  default) are the documented, operator-chosen exceptions.
