# CoSec 8.0 RC deep review, round 1, area A: the decision core

The first round of the two deep reviews RC1 requires of the integrated system (`docs/roadmap.md` §4, RFC 0008 §3.9
prerequisite 6), for area A: `core/model` and `core/engine`. A second session re-reads it after the fixes. The earlier
threat-model review and its fixes are in [`../8.0-rc-review.md`](../8.0-rc-review.md); its ids (`RC1-*`) are cited
where a fix of it was re-checked.

| | |
|---|---|
| Commit reviewed | `origin/main` at `69b94a2b` (#1901); `core/model` and `core/engine` are unchanged at `fb1f561c` (#1906), on which this file is committed |
| Date | 2026-11-23 |
| Scope | every main source file of `core/model` (37 files, 4,014 lines) and `core/engine` (55 files, 6,546 lines) |
| Reviewer | one read-only session, not an author of the code reviewed; it changed no code, test, rule or RFC |
| Method | the trust boundary first, then every file read end to end against RFC 0002, RFC 0004 §6, RFC 0005 and the `ALG`, `BND`, `BRK`, `BLK`, `BDY`, `CND`, `IDX`, `ATR` and `OBL` rules (`test/conformance/src/test/resources/rules/`), with two views: an attacker's (what lets a request through, what crosses a tenant, what fails open, what a low-privileged party can make expensive) and an operator's (what a mistaken document or setting silently does). Tests, scenarios and mutants were read only to decide whether a property has a guard. Each finding was checked against the code; none needed a probe test to confirm |

**Severity** (this review's scale). **S1**: a request permitted that should be denied, cross-tenant access, privilege
escalation or fail open, remotely and without privilege. **S2**: the same with a privileged position or a rare
condition; a denial of service a low-privileged party triggers cheaply; a credential leak. **S3**: weakened defence in
depth, a denial of service that needs privilege, behaviour that misleads an operator, a mismatch between the
specification and the code with no exploitable path. **S4**: hardening and documentation.

## Summary

No S1, S2 or S3 finding. The engine decides as RFC 0004 §6.1 says on every path read: deny-first holds across the three
documents, attachments and break-glass, every failure the engine can see is a DENY error or an unresolved input and
so never a Permit, a cap removes ALLOWs only, the boundary only narrows, and the candidate index never leaves out a
statement that can match. The two findings are the coordinator's candidates, both S4: one is a hot-path property kept
only by benchmark numbers, the other an authoring hazard the specification accepts on purpose.

| Id | Severity | Area | Finding | Status |
|---|---|---|---|---|
| DR1-A-1 | S4 | Engine, tracing | That an untraced evaluation (`TraceLevel.OFF`) builds no trace object and allocates nothing beyond what `Evaluator` documents is kept by JMH allocation numbers only; no test fails when it breaks | open |
| DR1-A-2 | S4 | Engine, documents | A DENY (or a rate limit rule) whose action or resource type is misspelt compiles and silently matches nothing, and nothing warns that no route, catalog entry or check produces that action | open |

## Files read

Every main source file of the area, end to end: 92 files, 10,560 lines. **Not read: none.** Tests, conformance
scenarios and mutant headers were consulted where a verdict below names them; they are not part of the scope.

**`model/src/main/kotlin/me/ahoo/cosec/model/`**: 6 files, 458 lines.

- `AuthzRequest.kt` (70)
- `Binding.kt` (111)
- `CoSecException.kt` (23)
- `Effect.kt` (19)
- `Ids.kt` (196)
- `Memberships.kt` (39)

**`model/src/main/kotlin/me/ahoo/cosec/model/scope/`**: 5 files, 221 lines.

- `HostScope.kt` (33)
- `Scope.kt` (31)
- `ScopeResolution.kt` (26)
- `ScopeResolver.kt` (94)
- `ScopeSubject.kt` (37)

**`model/src/main/kotlin/me/ahoo/cosec/model/condition/`**: 22 files, 2922 lines.

- `Ascii.kt` (61)
- `Attribute.kt` (215)
- `CheckResult.kt` (28)
- `CheckedCondition.kt` (393)
- `Cidr.kt` (114)
- `Condition.kt` (102)
- `ConditionChecker.kt` (399)
- `ConditionContainment.kt` (302)
- `ConditionError.kt` (22)
- `ConditionInput.kt` (53)
- `ConditionLimits.kt` (45)
- `ConditionLints.kt` (201)
- `ConditionNormalizer.kt` (59)
- `DaysOfWeek.kt` (33)
- `HttpNames.kt` (38)
- `IndexKeys.kt` (128)
- `IpAddress.kt` (153)
- `IpRanges.kt` (117)
- `IsoTime.kt` (172)
- `Moment.kt` (38)
- `Time.kt` (134)
- `TimeChecker.kt` (115)

**`model/src/main/kotlin/me/ahoo/cosec/model/decision/`**: 4 files, 413 lines.

- `BindingRef.kt` (36)
- `Decision.kt` (308)
- `DocumentKind.kt` (24)
- `StatementRef.kt` (45)

**`engine/src/main/kotlin/me/ahoo/cosec/engine/`**: 40 files, 4417 lines.

- `AbsentAttributes.kt` (36)
- `AttributePlan.kt` (38)
- `AttributePlanner.kt` (104)
- `AttributeStatus.kt` (17)
- `BoundEntries.kt` (153)
- `BuiltinPolicies.kt` (123)
- `CandidateOrder.kt` (246)
- `CandidateVisitor.kt` (41)
- `CoSecEngine.kt` (47)
- `CompileError.kt` (22)
- `CompileException.kt` (32)
- `CompiledDocument.kt` (377)
- `CompiledPolicy.kt` (143)
- `CompiledRateLimit.kt` (102)
- `CompiledStatement.kt` (125)
- `ConditionAttributes.kt` (56)
- `Engine.kt` (76)
- `Evaluator.kt` (399)
- `EvaluatorAttributes.kt` (191)
- `EvaluatorFailures.kt` (93)
- `EvaluatorGuards.kt` (124)
- `EvaluatorObligations.kt` (124)
- `EvaluatorSteps.kt` (215)
- `ExternalValues.kt` (88)
- `HeadersRead.kt` (49)
- `HttpStatements.kt` (71)
- `IndexLookup.kt` (36)
- `IndexOptions.kt` (70)
- `OnMissing.kt` (40)
- `OnMissingConfirmation.kt` (28)
- `PolicyCompiler.kt` (397)
- `RateLimitSource.kt` (34)
- `ResourceSource.kt` (26)
- `SnapshotView.kt` (102)
- `StatementIndex.kt` (271)
- `StatementSource.kt` (39)
- `SubjectAttributes.kt` (32)
- `TraceLevel.kt` (31)
- `TracingEvaluator.kt` (188)
- `ViewVersion.kt` (31)

**`engine/src/main/kotlin/me/ahoo/cosec/engine/trace/`**: 1 files, 94 lines.

- `EvaluationTrace.kt` (94)

**`engine/src/main/kotlin/me/ahoo/cosec/engine/pattern/`**: 14 files, 2035 lines.

- `ActionPattern.kt` (85)
- `EvaluationScratch.kt` (38)
- `GlobToken.kt` (68)
- `HttpPath.kt` (137)
- `HttpPathPattern.kt` (397)
- `HttpPathPatternParser.kt` (198)
- `InvalidPatternException.kt` (26)
- `PatternContainment.kt` (362)
- `PercentDecoding.kt` (102)
- `ResourceContainment.kt` (43)
- `ResourcePattern.kt` (294)
- `ScratchSource.kt` (19)
- `SegmentGlob.kt` (233)
- `VariableNames.kt` (33)

## Trust boundary

The engine is a pure function: it reads no clock, does no I/O and calls no user code except the host's
`ConditionInput` reads. What it is given, and who vouches for it:

| Input | Comes from | Verified before the engine | What the engine assumes |
|---|---|---|---|
| `Scope` (tenant, app, space, roles) | `ScopeResolver`, from the credential, the host app and the client's `CoSec-Space-Id` | tenant from the verified credential only; app checked against the host's; space only with a role there (`SCP-*`, `TNT-1`) | nothing more: `SnapshotView.checkScope` refuses an app or tenant document of another scope |
| `Memberships` (roles, attachments) | token claims or a `MembershipProvider` (area C) | parsed by `MembershipClaims` (no `@` in a role id), or the provider's answer | attachments resolve only to `attachable` policies; a cap (`principalBindings = false`) removes ALLOW candidates only |
| `Subject` (`id`, `authenticated`, `attributes`, `principalBindings`, `breakGlass`) | the authenticator (area C) | `breakGlass` only for CoSec's own verifier (`BRK-8`) | anonymous ⇒ no principal, attachment or break-glass candidate (`BND-4`) |
| `AuthzRequest.action`, `resource` | the adapter, the route map, or a host check | the path normalized and bounded by `PathNormalizer` (area C, `PIP-5`, `PIP-14`) | a path that does not decode fails the statements that read it; the index then rules out nothing by path |
| `time` | the pipeline's clock | — | binding expiry and `environment.time` read it |
| host attributes (`http.*`, `request.attributes.*`) | `HttpAttributes` (area C) | repeated headers with different values answered 400 (`PIP-12`) | a read that throws errs the statement |
| `ExternalValues` | the attribute plan's providers | type-checked against the declaration (`ExternalValues.fits`) | unavailable or unplanned ⇒ `AttributeMissing`, decided by `onMissing` |
| documents (`CompiledDocument`) | the snapshot compiler (area B, D), from Redis | compiled; tenant and app documents checked by `PolicyDocumentCompiler` | a document is written by a tenant administrator (tenant), an app's owner (app) or the platform; break-glass, root and the control policies are the platform's |

Attackers considered: an anonymous caller; an authenticated tenant user (who controls the path, the method, headers,
`CoSec-Space-Id`, and through a token only what the issuer signed); a tenant administrator (who writes their own
tenant's document through the control plane, so controls its policies, bindings, conditions, patterns and rate limit
rules within the compiler's bounds); a platform operator; a compromised service behind the gateway; someone with write
access to Redis (who can install any document that compiles: out of the engine's reach by design, area D).

## Findings

### DR1-A-1 · S4 · Untraced evaluation allocation is kept only by benchmarks

- **Location:** `core/engine/src/main/kotlin/me/ahoo/cosec/engine/CoSecEngine.kt` (`evaluate`), `Evaluator.kt` and the
  `Evaluator*.kt` extension files; `TracingEvaluator.kt`.
- **What holds today:** `CoSecEngine.evaluate` at `TraceLevel.OFF` builds a plain `Evaluator`, which has no trace
  state; `TracingEvaluator` overrides only `candidate`, `repeated`, `boundaryStep`, `catches` and `expired`, so no trace
  code runs untraced. `Evaluator`'s KDoc lists what an untraced evaluation may allocate.
- **Trigger:** a change that moves a trace check or a trace record into `Evaluator` (say a `CandidateTrace` built per
  statement and dropped at `OFF`), or that makes `evaluate` build `TracingEvaluator` at every level.
- **Actual:** every engine test still passes: `TraceLevelTest`, `EvaluationTraceTest` and the `ALG-8` property compare
  decisions, not allocation; only `EngineAuthorizationBenchmark`'s `gc.alloc.rate.norm` would show it, and the JMH
  smoke run in `check` asserts nothing.
- **Expected:** the hot-path invariant (`AGENTS.md`, "Hot-path changes come with a JMH before/after") is a review
  rule; nothing fails the build. Not a security property: `ALG-8` (the decision does not depend on the trace level) is
  guarded, and `ALG-7` (nothing is evaluated after the deciding statement, untraced) is guarded by `EngineTest`.
- **Basis:** RFC 0002 §5.1 (the decision never depends on the trace), `ALG-7`, `Evaluator`'s KDoc.
- **Reproduced:** no; by the code and a search of `core/engine/src/test` (no allocation measurement outside
  `HttpPathPatternTest`'s capture test).
- **Fix direction:** a structural test is enough and is not flaky: an `EnginePurityTest`-style scan that the classes
  of `Evaluator` and its extension files reference no `me.ahoo.cosec.engine.trace` type and no `TracingEvaluator`
  member, plus an engine test that `evaluate(…, OFF)` returns `trace == null` and never constructs a
  `TracingEvaluator` (a constructor counter behind a test seam). A per-thread allocation assertion
  (`com.sun.management.ThreadMXBean.getThreadAllocatedBytes` around a warmed evaluation) would catch more but depends
  on the JIT; if added, keep it out of `check` like `propertyStress`.

### DR1-A-2 · S4 · A misspelt DENY action or resource type matches nothing, silently

- **Location:** `PolicyCompiler.statement` (actions through `ActionPattern.parse`, resources through
  `ResourcePattern.parse`); `ActionPattern.matches`; the control plane's analysis (`RouteFindings` is the only
  vocabulary check).
- **Trigger:** a tenant administrator writes `{"effect": "deny", "action": ["order:exprot"], "resource": ["order/**"]}`
  meaning `order:export`, or `["ordr/**"]` for `order/**`, beside an ALLOW `order:*`.
- **Actual:** the statement compiles (only the syntax is checked), the control plane publishes it without a finding,
  and every `order:export` request stays permitted. `DOC-8` refuses the one case the system can know is dead
  (`http:HEAD`, a lower-case HTTP verb); `RouteFindings` (`UC-1`) warns only for `http:`-shaped statements a mapped
  route escapes.
- **Expected:** as specified, nothing more: RFC 0005 §5.1 decides that the compiler checks syntax only and the control
  plane does not validate action names or resource types against a catalog, because a catalog declares permission
  ids, not services, verbs or types (RFC 0004 §4.9, RFC 0012 §4). So this is an authoring hazard, not a deviation: the
  same class of "a hole its author believes closed" that `DOC-8`, `DOC-9` and `DOC-14` refuse where the system can tell.
- **Risk:** no attacker can cause it; a mistaken DENY in a tenant's own document weakens only that tenant, one in the
  platform or an app document weakens every tenant. Allow-side typos fail closed. The real exposure is a DENY written
  to carve an exception out of a wide ALLOW (`order:*`, `*`), which is the common shape of a restriction.
- **Basis:** RFC 0005 §5.1; `DOC-8` for the precedent.
- **Reproduced:** no; by the code (`ActionPattern.parse` accepts any non-empty name without `:`, `*`, whitespace or
  control characters, and nothing compares it with a vocabulary).
- **Fix direction (feasible checks):** a control-plane warning, never a compile error (the specification is right that
  the data plane cannot know the vocabulary): for each DENY statement and rate limit rule of a candidate, compare every
  action pattern other than `*` and `http:*` and every typed resource type with what the control plane already knows
  produces requests: `http:<METHOD>`, the business actions and resource types of the route maps it is given
  (`cosec.control.analysis.route-maps`, already read for `UC-1`), and the short-form `action` of the catalog entries of
  the apps in the documents (RFC 0012). A pattern none of them names gets a `VOCABULARY` warning naming the nearest
  known action (edit distance), with "without route maps it says so", as `RouteFindings` does. In-code checks
  (`AuthorizationChecker`) name actions the control plane cannot see, so the warning must stay a warning. A regression
  test: a candidate with `order:exprot` beside a route map that maps `order:export` gets the warning; the same with the
  right spelling gets none.

## Threats checked and found sound

Each line names what was read and why the threat does not hold, with the rule and the tests that guard it.

**Deny-first and decision combination.**

- *A matching DENY loses to an ALLOW, an error, or an unresolved input.* `Evaluator.evaluate` runs the DENY pass over
  every candidate before the ALLOW pass; `denyPass` returns a match before looking at errors or `unresolved()`
  (`ALG-1`, `EnginePropertyTest` P1', mutants `alg-1-*`). The blacklist is the first DENY candidate and needs only the
  platform document (`BLK-1`, `blk-1-*`).
- *An erroring DENY or an unread input falls through to the ALLOW pass.* The first DENY error (statement or policy
  condition) and then `view.unresolved`, `notLoaded`, missing and ambiguous policies make the decision `Indeterminate`
  before any ALLOW is looked at (`ALG-2`, `ALG-3`, mutants `alg-2-failed-deny-ignored`, `alg-3-unresolved-ignored`).
  Missing and ambiguous ids are recorded in the DENY pass, whose candidates are a superset of the ALLOW pass's.
- *An erroring ALLOW permits.* `erred` keeps an ALLOW error only as `skipped` when an external attribute is missing
  (then `Indeterminate`, never Permit); any other error is no match (`ALG-4`, `ATR-2`, `atr-2-*`).
- *The candidate order changes the decision.* The decision depends on the set of candidates; `ALG-9`'s per-document
  marks skip only a policy already evaluated in the same pass, whose result depends only on the request and the policy
  (scope and expiry are checked per binding before the mark), so the set of results is unchanged (`ALG-6`, `ALG-9`,
  `RepeatedBindingsTest`, `alg-9-*`).
- *The trace level changes the decision.* `TracingEvaluator` decides through the same `step`, `decide` and `erred`;
  after the decision it only records, and its wider `catches` applies only then (`ALG-8`, `alg-8-*`). The untraced path
  skips the rest of a policy after a guard miss or failure; the traced path evaluates them and gets the cached guard
  result, so both decide alike.

**Candidates, bindings and scope.**

- *A binding of another tenant, app or space applies.* `CandidateOrder.inScope` compares app, space and role tenant
  exactly; principal and break-glass lookups are keyed by the scope's tenant; `SnapshotView.checkScope` refuses a
  foreign tenant or app document (`BND-5`, `BND-6`, `TNT-*`, mutants `bnd-5-*`, `bnd-6-*`). A binding no request can
  reach does not compile (`BoundEntries.requireReachable`, `BND-3`, `BND-8`).
- *A capped credential escapes its principal's DENYs (RC1-1).* `CandidateOrder.principal` drops attachments and
  principal bindings in the ALLOW pass only (`BND-9`, `bnd-9-capped-principal-denies-skipped`).
- *A tenant replaces a platform policy by defining the same id.* An id both define resolves only with the same content,
  and then to the platform's copy; otherwise it is ambiguous, `Indeterminate` unless a DENY matches; this covers
  attachments and tenant bindings, and an app document's bindings never see a tenant's policy (`BND-7`,
  `SameContentTest`, `bnd-7-*`). A tenant binding of an id it does not define resolves to the platform's policy first,
  then only to the policy of the app its binding is scoped to.
- *An attachment reaches a policy that is not attachable, or another tenant's.* Attachments look up the scope's tenant
  document and the platform only, and require `attachable` (`BND-2`, `bnd-2-*`). Root and the blacklist are not in the
  index, so no attachment reaches them.
- *Break-glass for the wrong principal or credential.* Break-glass bindings are platform-only, expiring, bind a
  principal of `(platform)`, are looked up by the scope's tenant and the principal, only when `subject.breakGlass`, and
  come last (`BRK-1`, `BRK-2`, `BRK-4`, `BRK-8`, mutants `brk-*`). Root can be bound only by a break-glass binding.
- *The platform boundary widens a decision.* `withinBoundary` sees only a Permit; a missing boundary policy is
  `Indeterminate`; a boundary policy holds only ALLOWs and cannot be bound or attached (`BDY-1`,
  `bdy-1-boundary-permits-a-deny`). The boundary is checked before obligations, so a request it denies spends no
  permit.
- *A document that was not loaded is skipped.* `notLoaded` requires the tenant document always and the app document
  whenever roles or a tenant binding of the app's permissions could reach it (RC1-17); tested by `ALG-3`'s scenarios.

**Conditions and attributes.**

- *A condition reads a credential.* Every attribute reference, condition operand and rate limit key resolves through
  `ConditionChecker.attribute`, which refuses `authorization`, `proxy-authorization`, `cookie`, `set-cookie`,
  `x-api-key`, `cosec-identity` and the host's renamed API key header, folding names in ASCII only, and refuses
  `resource.attributes.*` (`CND-5`, `DOC-10`, `cnd-5-*`).
- *Absent values or three-valued logic open a DENY.* Absent is equal to nothing and `exists` is false; evaluation is
  two-valued; a missing external attribute is an error, decided by `onMissing` (`CND-1`, `CND-3`, `ATR-1`). A
  `nomatch` DENY also lapses when its policy condition's attribute is missing and when `environment.ip` is unreadable:
  both are specified (`PIP-17`; RFC 0007 §9.5's implementation note puts the policy condition in the confirmed digest).
- *A host or provider value of the wrong type reads as absent.* `ExternalValues.read` treats a value of another type
  as missing, never absent; an `environment.ip` that does not parse is missing unless empty (`PIP-17`).
- *A hostile condition exhausts the compiler or the evaluator.* The checker bounds nodes, depth, `in` values, CIDR
  blocks, literal bytes and total cost before building anything; evaluation visits each node once (`CND-2`,
  `cnd-2-*`). Policy conditions run at most once per evaluation, found by identity, and a scan turns into a map past 32
  so many conditions do not cost their square (`ALG-7`, #1212).
- *Case folding makes two names meet, or a fold matches less than it reads.* Folding is ASCII-only everywhere
  (`Ascii`, `HttpNames`, `GlobToken`, `StatementIndex.foldAscii`); an `ignoreCase` literal with letters beyond ASCII
  is refused where a miss lets a request through (`DOC-9`); `subject.id` literals with the reserved prefix are refused
  (`DOC-11`).
- *Containment used by the grant analysis proves too much.* `ConditionContainment` normalizes, then proves only by
  sound rules: finite values evaluated one by one against a condition that reads only that attribute, prefix and CIDR
  inclusion, period-only `time`, symmetric attribute comparison; `not` only by contraposition. `PatternContainment`
  compares literals by exact text, never across case or decoding options, never a leading `**`, and treats a final `*`
  as contained only in a final `*`; `disjoint` compares only literals before the first rest, and never across
  case-insensitive patterns. Both answer UNKNOWN otherwise (`ANA-*`, mutants `ana-*`).

**Patterns and the index.**

- *A path matches a pattern differently from Spring.* `HttpPath` splits as `PathContainer`; segments decode as
  `uriDecode` with parameters stripped and still checked; captures are decoded values; `{name:regex}`, the empty
  pattern and empty-matching segments do not compile (`HttpPathPatternParityTest` in `cosec-runtime`). A DENY cannot
  use `decodeAndParseSegments: false` (`DOC-14`); a bare type cannot be denied (review S3 of #1040).
- *The index leaves out a statement that matches.* It rules out only by action, by index keys of attributes the engine
  answers itself (never the host), and by the case-sensitive literal prefix of decoded patterns; a path that does not
  decode includes every `http` leaf (`IDX-1`, `IndexPropertyTest` I1 and I2, whose generator includes
  case-insensitive and undecoded patterns; mutants `idx-1-*`). `TracingEvaluator.skipped` re-checks I1 on every traced
  evaluation.
- *A tenant's pattern costs every request (RC1-8).* Glob segments are decided by a table in tokens × length steps,
  bounded per segment (16 tokens), per document (2,048 units) and by the 256-character segment bound; a policy is
  evaluated once per document that binds it (`DOC-4`, `ALG-9`, `GlobBoundBenchmark`).

**Obligations.**

- *A denied request spends a permit, or a rule is skipped on error.* Obligations are computed only for a Permit within
  the boundary; a rule whose evaluation fails for any reason other than a missing external attribute is strict (503);
  `nomatch` on a rule compiles only in the platform document (`OBL-1`, `ATR-7`, `RateLimitObligationTest`).
- *One document's rules count in another's buckets.* Counter ids are `platform/<id>`, `app:<app>/<id>`,
  `tenant:<tenant>/<id>`; rule ids hold no `/` or `:`, owners no `/` or `:` (`OBL-2`, `Identifiers.isDocumentOwner`); a
  key value with an unpaired surrogate gets an escaped bucket of its own (RC1-52).

## Coordinator's candidates

- **`OFF` builds no trace object, guarded only by JMH:** confirmed, **S4** (DR1-A-1). The decision's independence from
  the trace level is guarded (`ALG-8`); what is unguarded is the allocation and speed property, and a structural test
  can guard it without flakiness.
- **A misspelt DENY action matches nothing, silently:** confirmed, **S4** (DR1-A-2). RFC 0005 §5.1 decides it, so it is
  not a deviation; a control-plane `VOCABULARY` warning over route maps and catalog actions is feasible and reuses what
  `UC-1` already reads. It also concerns area B (the document compiler and the catalog), where it is not repeated.
