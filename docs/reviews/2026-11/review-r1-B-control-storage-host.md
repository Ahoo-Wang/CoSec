# Review round 1, stream B: control plane, storage, host (v8.0.0-beta.4, dd9b151d)

## Hand-over
- Done: control-plane entry and authorization (ControlHttp, ControlRoutes, PipelineControlAuthorizer, ChangeAuthority,
  TenantIsolation, ApprovalRules, Approvals, Publishing, Staging.skipShadow, Narrowing, BreakGlassRules,
  DecisionQueries), cocache specs and revocation stores, RedisSnapshotDocumentStore, SNP-6 diff (#1648), gateway
  image diff (#1674), starter: control routes, gateway mode, identity guard, endpoint check, readiness; migrate
  publish/restore; OTel/micrometer listeners; TS client token storage, trust, logout.
- Also done: k8s overlays, kafka listeners, cosid, api-key store, rule guards CTL-9 and CTL-13 (mutants run locally).
- Status: finished for this round. Remaining gaps are under "Not covered".

## Findings

### R1B-1 (S2): CTL-9's guard does not guard the publisher's re-authorization of every target
- **What happens.** `Publishing.publish` re-authorizes the publisher on every target of the change
  (`candidates.authorize(publisher, change)` -> `ChangeAuthority.authorize`: `cosec:write` per entry, `cosec:grant` per
  binding scope and derived role binding, `cosec:breakglass` where it touches one). The code is correct today. But with
  that call removed, every test of `:control` (515) and every scenario of `:conformance` (791) still passes. A publisher
  holding `cosec:publish` but not `cosec:write`/`cosec:grant` on the targets would then publish another person's
  change (or one whose author lost the rights after proposing), and nothing would fail. That is a control-plane write
  without authorization (an S1 property), guarded by nothing.
- **Path.** `core/control/.../lifecycle/Publishing.kt` `publish` -> `CandidateChecks.authorize` ->
  `ChangeAuthority.authorize`. The guard named in `CTL.json`,
  `ControlAuthorizationTest#publishing checks the publisher again on the current heads`, denies alice `cosec:*`, so the
  first line of `publish` (`authority.require(publisher, key, cosec:publish)`) already refuses her. The per-target
  check is never the thing that refuses. No mutant in `test/conformance/mutants/` names CTL-9.
- **Confirmed.** A local mutant (that one line commented out), then
  `:control:test --rerun` (515 tests, 0 failures) and `:conformance:test --rerun` (791 tests; the only failure was
  `MutationCheckTest`, because the edit changed lines another mutant patches). Reverted afterwards.
- **What would refute it.** A test outside `:control` and `:conformance` that kills it. I did not run `:spring-boot`
  (no Redis here). Its `ControlApprovalStarterTest` publishes as platform admins, who hold every right.
- **Smallest fix.** Add a test where bob holds `cosec:publish` (and `cosec:read`) on the tenant document but not
  `cosec:write`/`cosec:grant`, and alice holds all of them. Alice proposes a grant; the window closes; bob's publish is
  403 and the head does not move. Second case: alice loses `cosec:grant` after proposing, and her own publish is 403. Add
  it to CTL-9's guards with a mutant `ctl-9-publisher-targets-not-rechecked`.

### R1B-2 (S2): CTL-13's guard does not guard that skipping a window needs `cosec:skip-shadow`
- **What happens.** `Staging.skipShadow` requires `cosec:skip-shadow` for any change that is not exempt break-glass.
  Replace that action with `cosec:publish` and every test of `:control` and `:conformance` still passes. Anyone who may
  publish could then skip the shadow window, the step that shows what a change widens on live traffic before it
  publishes (item 1 of the brief: a shadow step that can be skipped).
- **Path.** `core/control/.../lifecycle/Staging.kt` `skipShadow`. CTL-13's guard,
  `ShadowWindowTest#break-glass has no window, and skipping one needs a reason that is recorded`, checks the reason. The
  CTL-27 tests check that a tenant admin (who holds both actions) skips only its own tenant's window. No test has an
  operator holding `cosec:publish` without `cosec:skip-shadow` try to skip. That is exactly `cosec:tenant-viewer` plus a
  custom publish grant, or any custom operator role.
- **Confirmed.** A local mutant (`ControlActions.SKIP_SHADOW` -> `ControlActions.PUBLISH` in `skipShadow`), then
  `:control:test --rerun` (515, 0 failures) and `:conformance:test --rerun` (791, 0 failures). Reverted.
- **What would refute it.** A test in `:spring-boot` or `:e2e` that catches it (not run). `ControlApprovalStarterTest`
  skips as a platform admin, so it would not.
- **Smallest fix.** Add a test where an operator holds `cosec:publish` but not `cosec:skip-shadow`. Skipping is 403, the
  change stays `CHECKED`, and nothing is recorded. Add it to CTL-13's guards with a mutant `ctl-13-skip-needs-publish-only`.

### R1B-3 (S3): a tenant administrator forges log lines on every host with a route map (#1684)
- **What happens.** Policy ids (and rate-limit rule ids) are not checked for control characters by the codec or the
  compiler: `PolicyId` is an unchecked value class, and `PolicyDocumentCompiler` checks only the `perm:` prefix.
  #1684's `RouteEscapeReview.installed` writes `RouteEscape.toString()`, whose `statement` is
  `HttpStatement.label` = `"DENY ${policy.id}#${index}"` or `"rate limit ${rule.id}"`, raw into a WARN line. A tenant
  administrator can publish a DENY `http:` statement (which narrows, so it needs no approval) under the policy id
  `"x\n<timestamp> ERROR ..."`. Every host with a route map that installs that revision then writes a forged log line.
  This is RC1-15's own rule ("an id reaches plain-text log lines, which it must not forge"), which `Identifiers.printable`,
  `SnapshotKeyText.printable` and `ShadowCodec` follow elsewhere.
- **Path.** `core/engine/.../HttpStatements.kt` (`label`) -> `core/document/.../route/RouteMap.escapes` ->
  `RouteEscape.toString` -> `host/spring-boot/.../pipeline/RouteEscapeReview.installed` (`log.warn`, `log.debug`).
- **Confirmed.** A throwaway test in `:document` (deleted afterwards): a tenant document whose DENY policy id holds `\n`
  compiles with `PolicyDocumentCompiler`, and `RouteMap.escapes(...).joinToString()` holds the line break as written. I
  did not run the starter's review itself; it logs that string unchanged.
- **What would refute it.** A layer between decode and install that refuses or escapes control characters in policy ids.
  I found none in the codec, the engine compiler or the document compiler. The control plane compiles with the same
  compiler.
- **Smallest fix.** Refuse control characters (and unpaired surrogates) in policy, binding, rate-limit rule and role ids
  at compile time, as app and tenant ids already are (`ScopeResolver.isValidAppId`), so it holds for every log line, not
  only this one. Or, at least, print the labels through `Identifiers.printable` in `RouteEscape.toString`. Rule: an
  IDN/DOC rule "no id a document defines holds a control character", guarded by a `PolicyDocumentCompilerTest` case
  and a mutant that drops the check.

### R1B-4 (S3): nothing bounds how many entries a tenant document holds, or how many operations a change carries
- **What happens.** The control API takes bodies up to `cosec.control.http.max-body-size` (16 MB by default). A
  document's bounds are: principal bindings (`principalBindingLimit`), role-derived bindings (50,000), path glob units
  (2,048, `DOC-4`), rate limit rules (64) and condition limits. Nothing bounds the number of policies, statements,
  literal resource patterns or bindings to roles or `Anyone`. A tenant administrator can propose a change of roughly
  100k `putBinding`/`putPolicy` operations on its own document. `ChangeAuthority.authorize` then takes two engine
  decisions per binding (`cosec:write`, `cosec:grant`) and the grant analysis runs over every fragment. That happens at
  propose, at every rebase and again at publish, all on the control plane. Once published, every host compiles and
  indexes the document (`compile-threads`, default 2, shared with every other tenant's compiles) and keeps it in memory.
  One tenant's administrator can slow the control plane, and every host's compile queue, for all tenants.
- **Path.** `api/ControlRoutes` -> `ChangeRoutes`/`DocumentRoutes` (`:apply`) -> `Proposals.propose` ->
  `CandidateChecks.authorize`/`checked` -> `ChangeAuthority.authorize`, `EscalationAnalysis`; on hosts,
  `SnapshotSource` -> `PlatformCompiler` -> `PolicyDocumentCompiler.compile`.
- **Confirmed.** By reading only: no load test was run. I found no count limit in `PolicyDocumentCompiler`,
  `DocumentChecks`, `CompiledDocument` or the control routes beyond those listed.
- **What would refute it.** A count limit I missed, or measurements showing 100k entries compile and analyze in
  negligible time.
- **Smallest fix.** A per-document entry budget in the platform's `limits` (policies + statements + bindings), enforced
  by the compiler on both planes (DOC rule, `CompiledDocumentTest` guard). A per-change operation cap in the control
  plane (CTL rule). A smaller default `max-body-size`.

### R1B-5 (S3): gateway mode trusts an unsigned `CoSec-Identity` and only logs a warning
- **What happens.** `cosec.pipeline.mode=gateway` wires `IdentityGate`, which believes any `CoSec-Identity` header
  (base64url JSON, no signature) within its size and age bounds. The boundary is only a WARN at startup
  (`CoSecGatewayModeAutoConfiguration`) and an optional example `NetworkPolicy` (`k8s/examples/`). Any pod that can
  reach such a service, or an SSRF in anything that can, is whoever it claims, the platform administrator included.
  This is documented (`wiki/operations/deployment.md#services-behind-the-gateway`), so it is not a bug. But it is the
  one place where a single network mistake hands over every tenant, and the gateway already holds a secret
  (`cosec.jwt.secret`, hmac256 in the shipped config) that could authenticate the header.
- **Path.** `host/spring-boot/.../pipeline/CoSecGatewayModeAutoConfiguration` -> `core/runtime/.../identity/IdentityGate`;
  issuer `IdentityIssuer` at the gateway.
- **Confirmed.** By reading.
- **Smallest fix.** Have the issuer sign the header (an HMAC over the encoded identity and `rid`, keyed by a dedicated
  secret), and have `IdentityGate` refuse an unsigned or mis-signed one. Until then, require an explicit acknowledgement
  property (for example `cosec.pipeline.identity.trust-network=true`) for gateway mode to start, so the boundary is
  stated in configuration, not only in a log line (STA rule, startup test).

### R1B-6 (S4): notes
- `k8s/overlays/dev`: Redis with no password and no NetworkPolicy, as a Service any pod in the cluster reaches. Whoever
  reaches it writes the documents and decides every request. It is labelled "for trying out", but its README could
  say so in those words. `k8s/overlays/production` sets a Redis password from a Secret but no TLS
  (`SPRING_DATA_REDIS_SSL_ENABLED`), so documents, revocations and refresh families cross the network in clear.
- `cosec:shadow-read` is declared (`ControlActions.SHADOW_READ`) and granted by `cosec:tenant-admin` and
  `cosec:tenant-viewer`, but no route or check uses it. The shadow samples (tenant, principal, app, space, action,
  resource) are written to Redis (`cosec:shadow:report:*`) and never served. Either serve them under `shadow-read`
  (scoped to the change's tenant) or drop the action and the samples. Today they are request metadata kept in Redis
  for no reader.
- `ShadowOptions` accepts `min-samples=0` with `min-duration=0`: a window that closes at its first check with no
  sample, without a warning at startup. Only the host's configuration can do it, but the startup summary could say
  that the shadow step is effectively off.
- The CTL-9 and CTL-13 gaps (R1B-1, R1B-2) suggest checking the other CTL rules without a mutant the same way: CTL-1,
  4, 5, 7, 15, 16, 17, 18, 34; SNP-1; STA-4; OBS-1. I probed four other control checks with local mutants; each was
  killed: `cosec:approve` at approve (APR-1 scenario), the tenant-isolation hard rule (TNT-3), `cosec:grant` on
  role-derived bindings, and the platform-only boundary (BDY-2). The break-glass REQUIRED-listener check (OBS-2) was
  killed by `BreakGlassAndShadowTest`.

## Looked at and found sound
- Control API entry: no permitted principal -> 401 before the body is read; every action re-decided as the operator
  in app `cosec-control` on confirmed documents (batch re-confirmed after, retried <= 4, else 503).
- Tenant isolation hard rule before every policy check; resource segments refuse `/`; tenant/app ids refuse `*`, `:`, `/`.
- Built-in tenant policies scoped by `resource.var.t == subject.tenant`.
- Approvals: author never approves, API keys never approve, credential id required, approval bound to candidate digest,
  expiry, coverage of escalation subjects, approver re-decided at publish as recorded (memberships, revocations).
- Publish: re-authorizes publisher, re-runs checks and analysis on current heads, conditional head write on base digest
  and slot; `afterHead` non-cancellable.
- Narrowing whitelist (slot preemption) only admits ALLOW-binding removal, unattachable ALLOW policy, new DENY-only
  policy/binding; anything shadowed by another document's id does not count.
- Break-glass: only by BREAK_GLASS change carrying nothing else; expiry bounded; policies granted by a live binding frozen.
- simulate/explain authorized on the tenant the scope resolves to; candidate simulation re-checks the change's key.
- Revocation tables: unknown read throws, never "not revoked"; subject key length-prefixed; jti/compact-token guard.
- Readiness: refuses until platform (and prewarmed) documents are installed; does not flap afterwards.
- Gateway image: actuator only on management port 8081 (health, prometheus), probes /livez,/readyz on 8080, gateway
  endpoint access none; STA-2 check covers the application port.
- cosec-migrate publish/publishOne/restore compile against the published platform, refuse break-glass, never overwrite
  (publish) / CAS on the confirmed head (restore).
- Micrometer tags are fixed enumerations; OTel span carries ids but no credentials.
- TS client: credentials only to same-origin/baseURL by default; logout removes the pair whatever the server answers.
- Kafka change and audit listeners: REQUIRED waits for the broker's answer; the starter fails startup when
  `delivery=required` and the listener is missing; change events carry no approver attributes or credentials.
- API keys in Redis are stored by SHA-256 hash under the deployment's prefix; strict codec; a stored record of another
  format is refused, never read as uncapped.
- Change records shown over the API keep only the approver, time, candidate and coverage of an approval; the
  approver's memberships, attributes and credential id stay in the store.
- Revision retention never deletes a head or a revision the chain or an index still lists.
- `IdGenerator` (CosId included) makes ids only; tokens are signed, so predictable jti, family or change ids grant
  nothing.
- Startup summary and Redis location print no credentials.

## Not covered
- No test needing Redis was run (none on localhost:6379): `:cocache` and `:spring-boot` are reasoned from code. STA-4
  (management port) and the starter's control wiring were not exercised.
- The snapshot source's key state machine (`KeyTransitions`, `KeyLoads`, `KeyDrain`, about 6.5k lines) was read at
  the level of the SNP-6 diff (#1648) only. Its own model and property tests are its guard. I found nothing, but I did
  not try to break it.
- Grant analysis internals (`grant/*`, `EscalationAnalysis`, `GrantVerdicts`), `HistoryRepair`, `ReferrerIndex`,
  `MovedHeads`, `Preemption`, `BreakGlass` lifecycle details and `CoCacheControlStore` conditional writes: skimmed,
  not traced.
- `edge/openapi` (route-map generation), `migration/migrate` convert/api-keys commands and the 7.x readers: not read
  beyond publish and restore.
- `test/system-*` and the system-test workflow: not read.
- Rule guards: of the families CTL/SNP/STA/DOC/OBS, only CTL-9, CTL-13 and the probes listed under R1B-6 were checked.
