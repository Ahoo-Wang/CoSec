# Review round 2, part 4: `core/runtime`, `host/spring-boot`, `test/testkit`

Base: `origin/main` at `3ed2eb0e4` (#1729), read 2026-11-20. Read-only, nothing changed, nothing run.

## Hand-over (kept current; the review is NOT complete)

- **Findings so far: 2** (R2D-1 S3, R2D-2 S3), both verified, neither breaking after RC.
- **`core/runtime`: partly read.**
  - Read: the snapshot package's front (`SnapshotSource.kt` whole, `SnapshotFamilies`, `SnapshotFamily`,
    `SnapshotOptions`, `DocumentSnapshots`, `DocumentSnapshotViews`, `SnapshotViews`, `SuspendingSnapshotSource`,
    `ViewDocuments`, `SnapshotSet`, `SnapshotEntry`, `PeekedKey`, `GuardedSnapshotMetrics`, `KeyRunner`); `Pipeline.kt`;
    `stage/LoadView`, `PublishBestEffort`, `DeliverRequired`; `event/DecisionListeners.kt` down to `drain`.
  - Not read: the key state machine (`KeyDrain`, `KeyEffects`, `KeyLoads`, `KeyTransitions`, `KeyWaiter`, `Passes`,
    `Prewarms`, `SnapshotThreads`), so nothing is said about a lost wake-up, a bound exceeded in a race or a failed entry
    kept; the other stages (`ReadRequest`, `Authenticate`, `ResolveMembers`, `MembershipResolver`, `AttributeFetching`,
    `Evaluate`, `RunObligations`, `Respond`, `ForwardIdentity`); `authn/*`, `http/*` (what `runtime` does with `HttpFacts`),
    `ratelimit/*`, `audit/*`, `shadow/*`, `identity/*`; the comments that predate #1725 (SNP-14) and #1731 (SNP-20).
- **`host/spring-boot`: sampled only.** Checked: lazy initialization, the meaning of "AOT" for the gateway image, and the
  property names against `wiki/operations/configuration.md` (sound, below). Not read: the auto-configurations as a set
  (conditions, ordering, replaceability of beans), `STA-6/7/8` checks, the pinned metadata's switch table.
- **`test/testkit`: surface checked only** (the ABI dump and the markers, not the code or the samples of `testing.md`); no finding. Sound: the public types are `PolicyTestKit`, `PipelineFixture`, `PermittedRequest`, `IdentityHeader`, `AttributeRequests`, `EngineOutcome`, the two `CoSecTestBindings` and two exceptions, which are the names `wiki/policies/testing.md` uses; `KitScenarios` and `PermittingHost` are `internal`, and the files CoSec's conformance suite reads (`EngineScenarios`, `EngineExpectations`, `DocumentSnapshotCompiler`) carry `@InternalCoSecApi`. Not done: running the page's samples against the kit by reading.
- **Read next, in this order:** (1) `snapshot/KeyDrain.kt`, `KeyEffects.kt`, `KeyLoads.kt`, `Prewarms.kt` against the
  bounds of #1725 and the host-apps hold of #1731; (2) the other stages, `authn/*`, `RunObligations`, `Respond`;
  (3) `host/spring-boot/.../pipeline/*AutoConfiguration.kt` read as a set; (4) `test/testkit` against
  `wiki/policies/testing.md` (which classes are public only for CoSec's own tests).

## Findings

### R2D-1 (core/runtime, S3, breaking after RC: no, size S) Composing a view builds and validates six snapshot keys per request

- **Where.** `core/runtime/.../snapshot/DocumentSnapshotViews.kt`: `keysOf` and `view`.
- **What is wrong.** For a request with a tenant and an app (the target's usual one), `keysOf` builds one
  `SnapshotKey.Tenant` and one `SnapshotKey.App`; `view` then builds the same two again to look up the document
  (`set.entry(SnapshotKey.Tenant(id.value))`, `set.entry(SnapshotKey.App(id.value))`) and a third time each for the
  `ViewVersion` (`tenantKey`, and `set.entry(SnapshotKey.App(it.value))`). Each construction runs the key's `init`,
  which is `ScopeResolver.isValidAppId`, a scan of the whole id (up to 128 characters), so one request validates its
  tenant id and its app id three times each, allocates six keys, and `SnapshotSet.entry` compares keys by `data class`
  equality (`shared.key == key`, then `scoped.firstOrNull { it.key == key }`). All of it is derivable from the entries
  `peekAll` already returned, in the order asked.
- **Evidence.** Read of the two functions. `DocumentSnapshotViewsBenchmark` sets up a tenant and no app, so it does not
  include the six keys of the app case; I read no number.
- **Fix.** Read `set.scoped[0]`/`[1]` by position (the order is `keys.documents`, known in `keysOf`) and keep the keys
  `keysOf` made. A benchmark with an app in the key would show it.

### R2D-2 (core/runtime, S3, breaking after RC: no, size S) A failing REQUIRED listener logs, with a stack trace, on the request's thread for every request

- **Where.** `event/DecisionListeners.kt`: `deliver` (`log.error(failure) { "REQUIRED decision listener ... failed on request" }`)
  and `requiredFailedOnNonPermit` (`log.error { "REQUIRED decision listeners ... failed on request ... answered ..." }`),
  called from `stage/DeliverRequired.kt` inside the request's coroutine.
- **What is wrong.** The class's own KDoc states the rule for the BEST_EFFORT side: "The request's thread never logs
  (#1392): a drop only counts and signals a reporter, which logs on [dispatcher] ... at most once per [warnInterval]", and
  `warnInterval` also limits a BEST_EFFORT failure. The REQUIRED side has no such limit: while the audit store is down,
  every request that reaches a REQUIRED listener logs one ERROR line with a stack trace in `deliver`, and every
  non-permit logs a second one in `requiredFailedOnNonPermit`, on the thread that serves the request (a Netty event loop
  on WebFlux and the gateway). That is the defect #1392 fixed for the queues and R2B-10 records for Kafka, in the third
  place. Besides the log volume, a blocking appender stalls the loop. A non-permit also waits for the listener's whole
  `requiredTimeout` (1 s by default) because its delivery is "not held to" the deadline (`DeliverRequired` KDoc): a
  documented trade-off, but it means an audit outage turns each 401 or 403 into a request held a second.
- **Evidence.** Read of `DecisionListeners.deliver` / `requiredFailedOnNonPermit` and `DeliverRequired.run`. Not run.
- **Breaking after RC: no** (log lines and their rate).
- **Fix.** Count on the request path, log from the reporter that already exists (`DropReporter`, once per `warnInterval`
  per listener, with the number since the last line), keep the stack trace for the first. S.

## Read and considered sound

- **`SnapshotSource`**: the KDoc matches the structure read (families, bounds per kind, `seen` across eviction);
  `SnapshotFamilies.claim` never takes a kind below its bound and a removed slot is dropped by the next lookup (read
  only, no race analysis); `SuspendingSnapshotSource` keeps locals out of the continuation on the installed path.
- **`PeekedKey.touch()`** writes a plain `lastAccess` field in the object that holds the volatile `installed` and
  `removed` every request reads, so requests of one hot app on several cores share a cache line. `SnapshotPeekBenchmark`
  (`test/e2e`, 1 and 8 threads on one app) would show it; I read no result, so it is a note, not a finding.
- **`Pipeline`**: the stage chain is written once and read by `StageOrderTest`; the deadline wraps the stages, the
  REQUIRED listeners and the forwarded identity are outside it, as `DeliverRequired` says.
- **`DecisionListeners.publish`**: lock-free, bounded, never logs; the half-full wake-up and the linger are documented
  with their measurement.
- **`host/spring-boot`, lazy initialization**: handled on purpose. `StartupTask` marks the beans that nothing depends on
  (listeners' metrics, route-map review, gateway route apps) and `StartupTaskExclusion` keeps them out of
  `spring.main.lazy-initialization=true`.
- **`host/spring-boot`, "AOT"**: the gateway image's AOT is the JDK 25 AOT cache (class data; `platform/gateway-server/aot-train.sh`),
  not Spring AOT, so there are no `RuntimeHints` to miss; the starter has none and needs none for that.
- **Properties against `wiki/operations/configuration.md`**: of the 100 names in `configuration-metadata.json` (the
  pinned list), all but six appear in the page; the six are the nested local-cache keys and an actuator access key that
  the page documents as groups (`cosec.authentication.revocation.cache.*`). The defaults I compared (`max-apps` 1024,
  `max-tenants` 16384, `max-absent-apps` 256, `max-absent-tenants` 65536, `reload-concurrency` 16) agree with
  `SnapshotOptions`. Names in the page that the metadata lacks are group headings and the removed `key-prefix` keys,
  which the page says fail startup.
- **Defaults against the dev image**: `max-tenants: 16384` can hold about 490 MB of documents (the page's own figure,
  `configuration.md:192`, `deployment.md:380`) while the `dev` overlay's heap is 256 MiB (`k8s/base/deployment.yaml:59`).
  The bound is a count, not bytes, so the dev gateway can reach an out-of-memory before the bound; the page says the
  base is `dev` and to size the heap for the documents. This is UB-24 of the usability triage (DOCS), so not repeated.

---

# Continuation (taken over from the first session by the coordinator's message of 2026-11-20): ids continue at R2D-3

Read at `origin/main` 9343a9e6d (after #1757). Read-only, nothing run, no Gradle. Hand-over and "read / not read" are at the end of this file ("Hand-over, current").

## Group 1: the request path in `core/runtime`

**Questions: a path where an error, a timeout or a cancellation ends in a permit or in a response that is not the mapped one; a place where a request reads state another request wrote; allocation or work per request that grows with tenants or documents. Answer for the first two: none found.** Followed to the end, in the code: every stage of `Pipeline.runStages`; `decide`'s three exits; `DeliverRequired` for a permit and a non-permit; `callPort`/`withLazyTimeoutOrNull`/`resumeOffIo`; the authenticators; `PathNormalizer`; `CoSecParameters`; `HttpAttributes`; `StatusMapping`; `Outcome`/`RequestSecurity`; `IdentityGate`; `AuthorizationChecker`; `LocalRateLimiter`. Findings below are about the limiter and the cold path, not about a permit.

### R2D-3 (core/runtime, S3, breaking after RC: no, size S) Two keyed rules fill the whole local limiter, for every tenant and every platform rule: the page says the other rules keep admitting
- **Where.** `ratelimit/RateLimiter.kt` (`LocalRateLimiter`: `maximumBuckets` 100,000, `maximumBucketsPerRule` = half, `reserve`, `admit`); `wiki/policies/rate-limiting.md` "The local limiter is bounded".
- **What is wrong.** The bound is per rule id and in all, and a rule's counter id is `<kind>:<owner>/<id>` (`CompiledRateLimit.counterId`), so each tenant's own rules are separate rules with a share of their own, up to 50,000 each. A rule keyed on something its clients can vary (`http.header.<name>`, `environment.ip`, `request.attributes.*`: a tenant administrator writes both the rule and the key) fills its share, and **two such rules fill the 100,000**. From then on no rule can admit a new bucket: a platform rule with `strictFailure` answers 503 to every request whose bucket is new (for a per-IP rule, most of them), and a lenient one is skipped, so its limit is gone for every new key. Buckets are not evicted (`OBL-5`) and last one hour idle, so the flood holds the state. The page states "A rule whose `key` reads something a client can vary fills only its own share: the other rules keep admitting new buckets", which holds for one flooded rule, not two. A tenant's rules are changed without a second person (`rate-limiting.md`), and a tenant administrator is the customer in the target scenario.
- **Who is exposed.** Only the local limiter: the starter uses the Redis one when a `ReactiveStringRedisTemplate` exists, which the documents' store guarantees on the gateway. So: a host without Redis rate limiting (the page's "counted per instance"), tests, and a pipeline built by hand (`PipelineBuilder.rateLimiter` defaults to `LocalRateLimiter()`). With Redis the same flood costs Redis memory for the keys' TTL, bounded by the flooder's own request rate; I did not look at the Redis limiter's key expiry here.
- **Evidence.** `RateLimiter.kt` as above; the page; `PolicyCompiler.rateLimit` (`counterId(kind, owner, id)`); `ChangeRoutes`/`wiki` for who writes a tenant rule. Not run.
- **Fix.** A share per document (owner) inside the per-rule one (for example `maximumBucketsPerOwner`, default a fraction of the total), so one tenant cannot take more than its slice, and correct the page. A rule that cannot admit counts in a metric the operator alerts on (the log line is throttled to one per 10 s). S.

### R2D-4 (core/runtime, S3, breaking after RC: no, size M) A new replica answers 503 to a burst of cold tenants: the compile queue is 64 and a full queue fails the load, with no wait
- **Where.** `snapshot/SnapshotExecutors.newCompileExecutor` (`ArrayBlockingQueue(compileQueueCapacity=64)`, `AbortPolicy`, 2 threads), `KeyEffects` (`catch RejectedExecutionException → CompileRejected`), `KeyTransitions` (→ `onTransientFailure(rejected = true)`), `KeyLoads.onTransientFailure` (no installed entry: `finish(… transientFailure …, install = false)`), `KeyWaiter.Request` (a request gets that failed entry).
- **What is wrong.** A cold request whose compile is rejected gets a failed entry at once (503 `SourceUnavailable`); only a *reload of an installed* document is retried (`retryLater`). `wiki/operations/deployment.md#what-a-new-replica-costs` prices a cold tenant at about 0.6 ms with requests "spread evenly", and readiness waits for the platform document, the host's apps and the listed tenants only. A replica the autoscaler adds takes its share of traffic from the first moment, and at the target (tens of thousands of tenants) almost every request in its first seconds is for a tenant it has not seen: arrivals of distinct cold documents are the request rate, while the pool does about 2 / (compile time) per second and holds 64 behind it. At 0.3 ms a compile that is about 6,600 a second; at 5 ms (a document of a tenant with many bindings) it is 400 a second, and a burst of 100 distinct large tenants in the same few milliseconds already rejects. The excess requests are refused `503` for no reason of the client's, and each refused tenant retries on its next request. The page's "a full queue fails the load closed" is the whole account; the burst is not.
- **Evidence.** The five places above; `deployment.md:442-450` and `overview.md:344`. Arithmetic, not measured: `SaasScaleMeasurement`'s harness (`saas-scale-harness.kt.txt`) measures compile time per document; a replica start under load has not been measured.
- **Fix (one of).** A cold load that is rejected waits within `cold-load-timeout` and tries again (the machinery for it exists for reloads: `retryLater`); or a compile queue sized by the number of tenants held (`maxTenants`) rather than 64; or an optional background warm-up of the tenants a replica sees (the busiest N from Redis) that does not gate readiness. M (the first is the smallest). Add a burst case to the cold-start test.

### R2D-5 (core/runtime, S3, breaking after RC: no, size S) Only tenants listed in `fail-closed.tenants` stop deciding on old documents; at the target there is no way to ask for it for all
- **Where.** `snapshot/SnapshotOptions.failClosedTenants` / `SnapshotSource.checkFresh` (`tenantId !in options.failClosedTenants → return`); `wiki/operations/configuration.md` line "fail-closed.tenants".
- **What is wrong.** When a document cannot be reloaded, the installed one is kept (F6, `KeyLoads.onTransientFailure`) and served for as long as that lasts; only a listed tenant is refused after `max-lag` (30 min). The list is a property of tenant ids: for tens of thousands of tenants it is not a list anyone writes, so the control "after a store outage longer than X, decide nothing on what we cannot confirm" does not exist for a SaaS host as a whole. A change published while an instance cannot read Redis (a revocation of a permission, a new DENY) is not seen by it, and its tenants are served the old documents until Redis comes back. AGENTS.md's invariant lists the exceptions to "an unreadable document never permits" as two (rate limits without `strictFailure`, token revocation reads); this is a third, documented in `storage.md` but not in the list.
- **Evidence.** `SnapshotSource.checkFresh`, `SnapshotOptions`, `configuration.md` ("Other tenants keep deciding on what is installed"), AGENTS.md invariants table.
- **Fix.** Accept `*` (every tenant) in `fail-closed.tenants`, and a default `max-lag` for the platform-wide case that the operator chooses; add the exception to the invariants text. S. Not an S2: the choice (availability over freshness) is the documented one; the finding is that it cannot be reversed at scale.

## Group 2: `runtime/snapshot`

### R2D-6 (core/runtime + host/spring-boot, S2, breaking after RC: no, size S) A failed first read of the platform document is never retried: the instance stays unready until it is restarted
- **Where.** `snapshot/DocumentReadiness.start` (`source.load().whenComplete { source.prewarm(prewarm) }`, with `prewarm` = the keys **besides** the platform's), `snapshot/Prewarms.attempt` (retries its own keys with backoff), `snapshot/Passes.installedSlots` (`shared.installed != null`), `snapshot/KeyTransitions.changed` ("reload if this key is installed or loading"), `snapshot/KeyLoads.onTransientFailure` (no installed entry: `finish(…, install = false)`, no `retryLater`), `host/spring-boot/.../SnapshotReadiness.isReady` (`readiness.missing().isEmpty()`, no I/O), `CoSecSnapshotAutoConfiguration.cosecSnapshotSource` (`DocumentReadiness(...).start()` once).
- **What is wrong.** A cold read that **fails** (Redis refusing connections or answering `LOADING` while it starts, a DNS miss, an ACL not yet loaded, the 30 s `storeReadTimeout`) installs nothing (`SnapshotSourceFailureTest.f1ColdReadFailureFailsTheRequestAndIsNotCached`: "not cached"; `storage.md:363` "Nothing is installed; the next request tries again"). Every later retry the source has needs an installed entry (the periodic pass, the change notification, `onReset`) or is for the prewarm keys, which exclude the platform. The only thing that reads it again is **a request**, and readiness refuses requests while the platform document is not installed, so none comes. The readiness probe, the health indicator and the timer of `SnapshotReadiness` only read `missing()`, which does no I/O. The instance is `REFUSING_TRAFFIC` for good (liveness holds, so nothing restarts it), after Redis is back. A platform document that is *absent* or *does not compile* recovers: both are installed entries the passes revalidate. Only the **failed read** does not.
- **Input / path.** A rolling update or an autoscaler's new replica that starts while Redis is down or restarting (a failover is when new replicas are wanted): every new pod's first platform read fails; Redis returns; the pods never become ready; the rollout stalls on its `maxUnavailable`, or the autoscaler's capacity never arrives, until someone deletes the pods. The prewarmed app and tenant documents do not have the problem: `Prewarms` retries them (1 s doubling to 30 s).
- **Evidence.** The seven places above; `SnapshotSourceFailureTest` (the next *request* reads again); no test starts a source whose first platform read fails and asks readiness without a request (`DocumentReadinessPrewarmAlsoTest`, `SnapshotReadinessTest`, `PipelineBuilderTest` hold or release a read, none fails one). Not run.
- **Breaking after RC:** no.
- **Fix.** Prewarm the platform document as the others: `DocumentReadiness.start` hands `source.prewarm` the platform key too (`Prewarms` already retries with backoff), or `settled`'s `usableOf(sharedKey)` is wrapped in the same loop; a test with a store whose first read of the platform key throws, then recovers, asserting `missing()` empties with no request. S.

### R2D-8 (core/runtime + backends/cocache, S3, breaking after RC: no, size S) The documented acceptance of tokens while revocations cannot be read happens for a refused connection, not for a hang: a hang is a 503 for every authenticated request
- **Where.** `stage/Authenticate.run` (`callPort(authenticationTimeout) { authenticator.authenticate(credentials) }`, 1 s), `authn/TokenAuthenticator.authenticate` (`revocation.check(token)`, accepting on `RevocationUnavailableException`), `backends/cocache/CoCacheRevocationReader.read` (`store.async().lookup(key).await()`, no deadline of its own), `CacheLocks` (`REVOKED_TOKEN_CACHE_NAME, REVOKED_SUBJECT_CACHE_NAME`: `lock(READ_TIMEOUT)`, `constrain(READ_TIMEOUT) { COMMAND_TIMEOUT }`, which is "forever", i.e. the store's command timeout), `wiki/operations/storage.md` ("the revocation tables read for the store's whole command timeout (`cocache.redis.command-timeout`, 2 s by default): a read that times out is unavailable, which the pipeline's verification accepts") and the failure table ("Verifying a request's token, the pipeline **accepts** it, so an outage does not reject every authenticated request").
- **What is wrong.** The accept path needs the read to **end** with `RevocationUnavailableException`. A refused or reset connection does, at once. A Redis that stops answering (a partition, an overloaded or failing-over node whose connections hang: the usual shape of an outage that is not a clean stop) ends it only at the command timeout, **2 s**, and the whole authenticator call is cut at **1 s** (`cosec.pipeline.authentication-timeout`): `callPort` answers `Failed(TimeoutException)`, `Authenticate` makes it `AuthnResult.Unavailable`, and the request is **503**, as `acceptUnavailableRevocations=true` never got to say. The two settings are each documented and defaulted, and their order defeats the sentence that relies on both. The docs' rule "no layer may time it out sooner" is there to keep the read from ending early; nothing makes it end before the budget that matters. Each authenticated request makes two such reads one after the other (`isRevoked`, then `subjectRevokedBefore`), "a failed read is not cached", so during a hang every authenticated request holds a coroutine for the full second and leaves a Redis command pending for up to two: at 10,000 requests a second, about 20,000 pending commands on the client.
- **Not a permit.** The direction is the closed one: nobody is let in who should not be. It is the documented *availability* behaviour (PIP-7, AGENTS.md's second exception) that does not hold for the common failure shape, and the pile-up is a cost the page does not say.
- **Evidence.** The six places above; `CoCacheRevocationReaderTest` and `PipelineTest`'s PIP-7 case fail the read with an exception, not a hang. Not run.
- **Breaking after RC:** no (a new property and a changed default of what a hang does; a behaviour the page already promises).
- **Fix.** Give the check its own budget inside the authenticator's: `TokenAuthenticator` runs `revocation.check` under `withLazyTimeoutOrNull(revocationTimeout)` (`cosec.authentication.revocation.read-timeout`, default well under the authentication timeout, say 300 ms) and turns an overrun into `RevocationUnavailableException` (accept, or 503 with `fail-closed`), and state in the page that the budget is the authenticator's, not the store's. Add the hang case to `PipelineTest` (a reader that never answers). S.

### R2D-7 (host/spring-boot, S4, breaking after RC: no, size S) A `TokenVerifier` bean alone does not turn tokens on: `JwtSigning` decides, so an application with its own verifier and no `cosec.jwt.secret` rejects every token
- **Where.** `pipeline/CoSecPipelineAutoConfiguration.cosecAuthenticator` (`signing.ifAvailable?.let { … verifier.getIfAvailable { JwtTokenVerifier(it, …) } … } ?: NO_TOKEN`); `wiki/integration/authentication.md` ("`TokenAuthenticator` verifies the token with the `TokenVerifier` bean, CoSec's `JwtTokenVerifier` unless the application declares its own").
- **What is wrong.** The sentence reads as "declare a `TokenVerifier` and it is used". It is used only when a `JwtSigning` bean exists too (from `cosec.jwt.secret`, with `cosec.jwt.enabled` on); without one the authenticator is `NO_TOKEN`, and a request that carries a token is `token-invalid`. The KDoc says "the signing key decides only whether tokens are on", the page does not. An application whose tokens come from another issuer and are verified by its own verifier must still set a CoSec signing secret it never uses, and nothing says so. Fails closed (everyone is refused), so S4.
- **Fix.** Turn tokens on when either exists (a `TokenVerifier` bean or a `JwtSigning`), or say it on the page and name the property. S.

## Group 3: `host/spring-boot`

**Question: a configuration an operator can write that starts and is less safe than they think.** Beyond what the usability review recorded (UB-1 switches, UB-4 `max-trusted-index` default 1, UB-3, UB-24), the configurations I followed are checked at startup or fail closed; R2D-7 and R2D-8 are the exceptions that are about what a page promises. Nothing that permits.

### Sound, followed in the code (group 3)
- **Switches.** `CoSecSwitches` holds every `@ConditionalOnProperty` key and what it accepts; a value that would switch a feature off unseen fails startup. `cosec.enabled=false` still binds `cosec.pipeline.mode`, rejects `gateway` on a Spring Cloud Gateway host and strips a client's `CoSec-Identity` on a gateway (`CoSecIdentityGuardAutoConfiguration`).
- **Order of the filters.** The servlet filter is registered at `-9999`, right after the hidden-method filter and before everything that acts on the request, with a CORS filter just before it when the application declares a `CorsConfigurationSource`; the WebFlux filter's order is 1000 behind the reactive hidden-method filter; the control routes on a gateway host are authorized by a `PipelineWebFilter` limited to their base path (`ControlRoutesWebFilter`); a request under the control base path passes the pipeline before `ControlHttp`, which authorizes each action again, reads no body for a request without a principal and caps the body (`max-body-size`).
- **Which bean wins.** `@ConditionalOnMissingBean` on the authenticator, pipeline, checker, listeners, route map, rate limiter provider and filters: an application bean replaces the starter's wholesale (a replaced `Authenticator` means API keys and tokens are the application's concern: documented in `extending.md`).
- **Gateway mode.** `STA-7` (`only-gateway-can-reach=true`, exactly) and `STA-6` (no hidden-method filter, property, bean or registration) fail startup; the identity header is unsigned and says so in the failure text, the page and the startup line.
- **Client address.** `ClientIpReadings` decides from the server and the forward-headers strategy and fails startup for the combinations it cannot read safely (`framework`, Tomcat with an explicit index, Netty or Jetty with `0`, an unknown server); default `max-trusted-index` 1 is UB-4's.
- **Defaults against `configuration.md`.** The code defaults of `PipelineProperties`, `RuntimeAuthorizationProperties` (`timeout` 5 s, `authentication-timeout` 1 s, `membership.timeout` 500 ms, `attributes` 300 ms, `rate-limit` 500 ms, `allow-query-token` and `allow-cookie-token` false, `revalidate-interval` 10 s, `lag-warn` 60 s, `lag-escalate` 5 m, `cold-load-timeout` 2 s, `max-*`, `reload-concurrency`, `compile-threads` 2, `evictionCheck` FAIL, `fail-closed.max-lag` 30 m, shadow 10 %), `ControlProperties` (`approval.expire-after` 72 h, `four-eyes` false, `break-glass.max-duration` 4 h, `shadow.*`, `changes.expire-after` 7 d, `revisions.*`, `max-body-size` 10 MB) and `RevocationProperties` (`skew` 5 s, `fail-closed` false) are the page's. (The pinned metadata file has no defaults, so it cannot say.)
- **Control plane wiring.** `ControlPlaneCondition` (`cosec.control.enabled` read through the `Environment`, `true` or `false` only); the control plane runs by default wherever `cosec-control` and `cosec-cocache` are on the classpath, which the starter says in `ControlProperties`; the platform document's `Anyone` rules are what open it to anyone, and `ControlHttp` authorizes every action again.

## Group 4: `test/testkit`

**Questions: that what the kit lets a test put in place cannot be reached in production (`PIP-29`), and that a scenario's pass means what its page says.**
- **Reachability.** No `META-INF` resource and no Spring registration in `cosec-testkit`; every `project(":testkit")` in the build is `testImplementation` (`spring-boot`, `conformance`) and `:architecture` reads it for rules only, so nothing ships it. The kit's contexts are made by a real `Pipeline` over an authenticator and a host whose rule permits (`PermittingHost`), so the only way to a permitted context is `RequestSecurity` as the pipeline builds it; `CoSecTestBindings` (webflux and webmvc) refuse a context the pipeline did not make with the same `RequestSecurity.of(...)` test the adapters use (`PIP-27`), and `PermittedRequest` refuses credential headers. `forSubject("x", tenant = "(platform)")` makes a platform context with no credential, which is what the kit is for: it has no path into a host's beans.
- **What a pass means.** `EngineExpectations` compares only the fields `expect` names, whole for `by`, and refuses an `expect` without a `decision`, as `testing.md` says; a field the outcome lacks reads as missing and differs; `Deny` without `by` passes for any deny (documented, and the page's own example pins `by`). The page's list of what the kit does not run (rate limit rules, route map, external providers) is the code's.
- **No finding.** Not done: running the page's other samples (`PipelineFixture`, the two `Testing your application` pages) against the kit; they are compiled and run by `TestingYourWebFluxApplicationTest`, `TestingYourWebMvcApplicationTest` and `DocumentationExamplesTest`.

## Sound, followed in the code (groups 1 and 2, added)
- **`withLazyTimeoutOrNull`** returns the completed value or exception without a timer when the block does not suspend, arms the timer for what is left otherwise, cancels the block on expiry and waits for it (as `withTimeoutOrNull`); a negative remainder times out at once; `callPort` turns every failure but an external cancellation into `Answer.Failed`.
- **`resumeOffIo`** resumes on the context's `ResumeDispatcher` or its interceptor, never on the thread that completed the port's call, and starts the block in the caller's context so cancelling the caller cancels it.
- **`DecisionListeners`.** The wake-up protocol is sound: `parked` is volatile and set before the consumer checks the queue, the publisher offers before it reads `parked`, so one of them sees the other; `publish` is bounded by an atomic size that is incremented before the offer and given back on a drop; `close`/`drain`/`cancel` drop what is left and count it, and a stopped consumer starts no listener call.
- **`IdentityGate`.** One `CoSec-Identity` header (two is 400), size, version, expiry with skew, age (`max-age` 60 s) and future checks, tenant id validity, the scope resolved by `ScopeResolver` with the forwarded app, and the credential's cap and break-glass as the gateway named them; no header is anonymous (the service's own code decides).
- **`RequestSecurity`/`Outcome`.** `end()` is a volatile flag read by every check; the derived securities (`inApp`, `asPrincipal`) are only reached after the original's `inProgress` was checked; a permit always carries the `STRIP_IDENTITY` forward, and a gateway's `forwarding` overrides it with the identity or null.
- **`LocalRateLimiter`.** Bucket ids are scoped by `kind:owner/id`, so tenants cannot share or spend one another's counters; admission and the sweep are as its KDoc says; a bucket that cannot be admitted throws, and `strictFailure` decides (the bound is R2D-3's).
- **`SnapshotFamilies`.** Claims of an eviction never take a kind below its bound; `seen` outlives eviction and is bounded by the documents that exist; a document seen and then absent fails closed (`LOST`), a regression from a lower revision is installed with an `ERROR` line (the store is the truth).
- **`KeyLoads`/`KeyWaiter`.** A load's waiters each have their own future and timer; the cold timeout bounds the wait, not the load; a load that started before a recompile is followed by one in the new epoch and its waiters get the later one; `Absent` and `Failed` are installed entries that passes revalidate, a failed *cold* read is not (R2D-6).

---

# Hand-over, current (supersedes the first section of this file; written 2026-11-20 by the session that took part 4 over)

- **Findings: 8** (R2D-1..R2D-8). Index, ordered by severity, then breaking after RC (none of the eight breaks after RC):

| id | module | sev | size | one line |
|---|---|---|---|---|
| **R2D-6** | runtime + spring-boot | **S2** | S | a failed first read of the platform document is never retried: the instance stays unready until restarted |
| R2D-3 | runtime | S3 | S | two keyed rules fill the local rate limiter's 100,000 buckets for every tenant and platform rule |
| R2D-4 | runtime | S3 | M | a new replica answers 503 to a burst of cold tenants: the compile queue is 64 and a rejected cold load fails, with no wait |
| R2D-5 | runtime | S3 | S | only tenants listed in `fail-closed.tenants` stop deciding on old documents; no "all" at the target |
| R2D-8 | runtime + cocache | S3 | S | tokens are accepted while revocations cannot be read only for a refused connection; a hang is 503 for every authenticated request (2 s command timeout against the 1 s authentication timeout) |
| R2D-1 | runtime | S3 | S | composing a view builds and validates six snapshot keys per request (first session) |
| R2D-2 | runtime | S3 | S | a failing REQUIRED listener logs with a stack trace on the request's thread for every request (first session) |
| R2D-7 | spring-boot | S4 | S | a `TokenVerifier` bean alone does not turn tokens on: `JwtSigning` decides |

  Per module: **core/runtime 6** (R2D-1, 2, 3, 4, 5, 6 with its spring-boot half), **core/runtime + backends/cocache 1** (R2D-8), **host/spring-boot 1** (R2D-7), **test/testkit 0**. No S1. One S2, reported at once. R2D-6, R2D-8 and R2D-3 can each be a test first.
- **Read in full (this session):** `Pipeline`, `PipelineBuilder`, `PipelineOptions`, `PipelineSettings`, `AuthorizationChecker`; every file of `stage/` (`ReadRequest`, `RequestState`, `Authenticate`, `ResolveMembers`, `MembershipResolver`, `ResolveScope`, `LoadView`, `FetchAttributes`, `AttributeFetching`, `Evaluate`, `RunObligations`, `Respond`, `DeliverRequired`, `ForwardIdentity`, `PublishBestEffort`, `DecisionEvents`); `http/` (`PathNormalizer`, `PercentDecoder`, `CoSecParameters`, `HttpAttributes`, `HttpFacts`, `StatusMapping`, `Outcome`, `RequestSecurity`, `PipelineSecurityContext`); `authn/` (all five); `token/TokenRevocation`; `call/` (all); `identity/IdentityGate`; `attribute/` (`AttributeProviders`, `BlacklistAttributeProvider`, `FetchedAttributes`); `ratelimit/` (both); `event/DecisionListeners`; `snapshot/`: `SnapshotViews` + `ViewKey`, `DocumentSnapshotViews`, `SnapshotOptions`, `SnapshotFamilies`, `SnapshotExecutors`, `KeyLoads`, `KeyWaiter`, `DocumentReadiness`, `Prewarms`, `Passes`, `SnapshotChanges`, `SuspendingSnapshotSource`, and in part `SnapshotSource` (`load`, `loadAll`, `settled`, `prewarm`, `checkFresh`, `usableOf`), `KeyEffects` (`compileOnPool`), `KeyTransitions` (`changed`), `KeyRunner` (`usable`); `host/spring-boot`: `CoSecPipelineWebAutoConfiguration`, `CoSecPipelineAutoConfiguration`, `CoSecGatewayModeAutoConfiguration`, `GatewayModeTrust`, `CoSecIdentityGuardAutoConfiguration`, `ClientIp`, `CoSecSwitches`, `CoSecProperties`, `CoSecAutoConfiguration`, `CoSecControlRoutesAutoConfiguration`, `ControlRoutesWebFilter`, `ControlProperties`, `ControlPlaneCondition`, `SnapshotReadiness`, the bean in `CoSecSnapshotAutoConfiguration` that starts the source, the defaults of `PipelineProperties`; `test/testkit`: `PermittedRequest`, `PermittingHost`, `webflux/CoSecTestBindings`, `EngineExpectations`, `RequestFacts` (head), the `build.gradle.kts`, `testing.md`'s "Testing decisions".
- **Read in part:** `SnapshotSource`, `KeyEffects`, `KeyTransitions`, `KeyRunner` (as above); `PipelineProperties` (defaults by grep); `CoSecSnapshotAutoConfiguration` (lines 320-372).
- **Not read:** the key state machine's `KeyDrain`, `KeyStepBuilder`, `KeyStepPool`, `KeyLane`, `KeyCallers`, `KeyContext`, `KeyHandle`, `KeyEvent`, `DrainBudget`, `DrainHandOffs`, `HousekeepingTask`, `HousekeepingRefusals`, `SnapshotThreads`, `LagReports`, `PlatformCompiler`, `SnapshotSummary`, `SnapshotMetrics`, `EntryOrigin`, `SnapshotEntry`, `InstallReview`, `CompileReport`, `SnapshotSourceProbes`: so a lost wake-up, a bound exceeded in a race or a leak under eviction in the state machine itself is **not** reviewed (`SnapshotConsistencyPropertyTest`, `SourceModelOracleTest`, `KeyModelPropertyTest` hold it; I did not read what they cover); `audit/*`, `shadow/*`, `identity/IdentityIssuer`, `IdentityRefusals`, `http/` (`ApplicationPaths`, `CookieHeaders`, `ClientAddress`, `XForwardedRemoteIpResolver`, `ProblemEncoder`, `QueryParameters`, `Enforceable`, `HttpMethods`, `TraceContext`), `CheckMisuse`, `BlacklistSource`s; in `host/spring-boot`: `CoSecModuleCheckAutoConfiguration` (STA-1), `CoSecControlAutoConfiguration`, `CoSecSnapshotAutoConfiguration` beyond lines 320-372, `CoSecPipelineIntegrationAutoConfiguration`, `GatewayRouteApps`, `GatewayOwnHandlers`, `RouteEscapeReview`, `EvictionPolicyCheck`, `StartupSummary`, `UnknownProperties`, the `jwt/`, `authentication/`, `apikey/`, `audit/`, `micrometer/`, `ip2region/`, `cosid/`, `openapi/` auto-configurations, `PlatformDocumentWatch`; `test/testkit`: `PolicyTestKit`, `PipelineFixture`, `IdentityHeader`, `KitScenarios`, `EngineScenarios`, `ScenarioJson`, `DocumentSnapshotCompiler`, the MVC bindings, and the samples of `testing.md` other than "Testing decisions".
- **Nothing was run.** R2D-3, R2D-4 and R2D-8 are arithmetic on the code and the defaults (the page says 0.6 ms a cold tenant; the compile time of the target's largest documents is `SaasScaleMeasurement`'s to give); R2D-6 and R2D-8 each need a test that does not exist today.
