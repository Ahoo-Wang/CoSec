# Review round 2, part 1: the stable core (`api/annotations`, `api/api`, `core/model`, `core/engine`, `core/spi`, `core/document`)

Base: `origin/main` at `63473ab81` (#1731), read 2026-11-20. Read-only: no Gradle, no mutants, no benchmarks, nothing
changed, nothing pushed. Every finding below was checked in the code at that commit; where a check was a read only, it
says so.

## Hand-over (kept current)

- **Status: done.** 15 findings, **no S1 or S2**. S3: 5. S4: 10.
- **Breaking after RC: 7** (R2A-1 to R2A-7). The other 8 are fixes with no public-signature, format, property or
  authorization change.
- Per module (by the module that holds the code): `api/annotations` 0, `api/api` 1, `core/model` 4, `core/engine` 1
  (the stale comments, which also touch model, spi and document), `core/spi` 4, `core/document` 4, and 1 in the
  conformance rule files (R2A-10).
- The code of these modules is in good shape: the hot path has been worked on with measurements, the ledgers are
  honest, the guards are dense (534 mutants). What follows is what is left, mostly about **how the public surface is
  shaped** (the only part that cannot be fixed after RC) and a handful of comments that stopped being true.
- Not read, and why, in the last section.

### Where the review stands (for resuming; written at the coordinator's request, nothing is half-written)

- **Done (every file of the module read for its public surface, KDocs and structure):** `api/annotations`, `api/api`.
- **Done for the public surface, ledgers, KDocs and the request-path code; not done for the long tail:** `core/model`,
  `core/engine`, `core/spi`, `core/document`. All 15 findings are verified; nothing in the file is a draft.
- **Untouched:** nothing; no module was skipped. What is untouched is inside modules (the "Not read" list in section D).
- **What to read next, in this order:**
  1. `core/engine`: `TracingEvaluator`, `AttributePlanner`, `EvaluatorGuards`/`Obligations`/`Failures`, `SegmentGlob`,
     `PatternContainment`, `HttpPathPatternParser`, `BuiltinPolicies` (the duplication and trace-vs-untraced questions
     are still open there).
  2. `core/document`: `ConditionJson`, the `*Codec` files, `DocumentChecks`, `DocumentIds`, `DocumentBudgets`, `format/*`
     descriptors, `ShadowCodec`, `RouteMapCodec`.
  3. `core/model`: `IpAddress`, `IsoTime`, `Time`, `TimeChecker`.
  4. `core/spi`: `InMemory*`, `shadow/*`.
  5. Tests: read the bodies of the guards of rules `SCP-7`, `CND-2`, `BND-8`, `DOC-1`, `DOC-3` (no mutant, no named
     guard) and of `ATR-3`/`ATR-4`, to see whether they would pass with the rule broken.
- **Nothing else is pending.** The worktree is detached at `63473ab81` and clean.

Severity: S1 wrong permit or fail-open, S2 a guard that does not guard / a defect that needs a precondition, S3 a
cost the maintainer would not choose knowingly, S4 a cleanup. "Breaking after RC" means: fixing it after RC1 changes a
line of a `api/<project>.api` dump, the document format, a `cosec.*` property or authorization semantics. Size: S under
a day, M a few days, L more.

---

## A. Breaking if fixed after RC (decide before RC1)

### R2A-1 (S3, M) `core/model`: the decision values are public data classes, so their shape is frozen

- **Where.** `decision/Decision.kt` (`Permit`, `Deny`, `Indeterminate`, every `Cause.*`, `Obligation.RateLimit`,
  `RateLimitSpec`), `decision/BindingRef.kt`, `decision/StatementRef.kt`; `engine/ViewVersion.kt`.
- **What is wrong.** They are `data class`es, so each dump line carries a constructor, `componentN`, `copy` and
  `copy$default` (`core/model/api/model.api` has 46 `copy` lines, `core/engine/api/engine.api` 2, `core/spi/api/spi.api`
  6). A field added later, which the code already expects (`Cause`'s own KDoc: "a minor release may add a member, such
  as the failure of a new port"; `ViewVersion` has three documents and `DecisionEvent` carries it), changes four
  signatures per class. The rest of the public surface already follows the other rule, written down in RFC 0010 C4 and
  in three KDocs: `RateLimitResult`, `VerifiedToken` and `ApiKey` are plain classes "so that a field can be added
  later without breaking callers". Applications read these values (`when (decision)`, destructuring), they never make
  them.
- **Evidence.** `Decision$Permit` in `model.api`: `<init>`, `component1/2`, `copy`, `copy$default`. The two rules side by
  side: `spi/ratelimit/RateLimitResult.kt` (KDoc) and `model/decision/Decision.kt`.
- **Breaking after RC: yes.** Turning a data class into a plain class removes `copy` and `componentN`; adding a
  constructor parameter changes the constructor. Before RC both are free.
- **Fix.** For the ones that can grow (`Permit`, `Deny`, `Indeterminate`, `Obligation.RateLimit`, `ViewVersion`,
  `SourceUnavailable`, `DocumentInvalid`, `MatcherFailed`): plain final classes with `equals`/`hashCode`/`toString`;
  the leaf-like ones (`NotLoaded`, `Attached`) can stay. Compile-driven: I found no `copy` or `componentN` call on a
  decision value in main code (the one `copy(` in `control/api/DecisionQueries.kt:134` is on a `SimulatedRequest`), so
  the cost is mostly tests; M.

### R2A-2 (S3, M) `core/spi`: `RateLimit` is `RateLimitSpec` written a second time

- **Where.** `spi/ratelimit/RateLimit.kt` against `model/decision/Decision.kt` (`RateLimitSpec`); bridged by
  `RateLimiter.window` (`spi/ratelimit/RateLimiter.kt:32`).
- **What is wrong.** Both are "permits per second and a window in seconds" with the same three `require`s (positive,
  `1..MAX_WINDOW_SECONDS`, `fitsInLong`) and different messages for the same violation. `RateLimit` adds
  `limitPerWindow`, `windowMillis` and `resultOf`; the only reason it exists is that those three live next to the
  limiter. It is a public `data class`, against the rule in R2A-1, and `RateLimiter.window` is public API whose one job
  is converting one into the other. The 7.x mirror in `migrate` imports it too.
- **Evidence.** Callers of `RateLimiter.window`: `runtime/ratelimit/RateLimiter.kt:111`,
  `backends/cocache/.../ReactiveRedisRateLimiter.kt:44`. Validation text: `RateLimit.kt` init vs `RateLimitSpec` init.
- **Breaking after RC: yes** (a public class and a public companion function go).
- **Fix.** Put `limitPerWindow`, `windowMillis`, `resultOf` on `RateLimitSpec` (or as extensions in `spi`), delete
  `RateLimit` and `window`, point the three callers at the spec. M (runtime, cocache, migrate's mirror).

### R2A-3 (S3, M) `api/api`: the audit record says the outcome several times, and types it with strings

- **Where.** `api/audit/AuditEvent.kt`; written by `core/runtime/.../audit/AuditEvents.kt`.
- **What is wrong.**
  1. `AuditAuthorization.decision` (`AuditDecision`, 9 values) and `reasonCode` (`AuditReasonCode`, 16 values) are a 1:1
     table for nine values (`AuditEvents.kt:128-136` writes the table) plus seven extra reason codes; `reason` is a
     third, a string. A consumer has three fields to pick from for "what happened", and the KDoc of `AuditDecision` says
     it is "one vocabulary for events, metrics and audit" while runtime has a third enum, `DecisionEvent.Reason`.
  2. `AuditEvent.kind` is a `String` (`"DECIDED"`, `"BAD_REQUEST"`, ...), default `"DECIDED"`, and runtime holds the typed
     `DecisionEvent.Kind` and writes `kind.name` (`AuditEvents.kt:77`).
  3. `AuditEvent.binding` is a string with a prefix grammar (`attached:<policy>`, `builtin`, `boundary:<tenant>`, or a
     binding id; `AuditEvents.kt:186-196`) beside `source.via` and `source.document`, which already say which of those
     it is. A binding whose id is literally `builtin` reads as the built-in.
  `AuditDocument` and `AuditVia` mirror `DocumentKind` and `BindingRef` because `cosec-api` cannot see `cosec-model`;
  that mirror is by design and not part of this finding.
- **Breaking after RC: yes for removing or retyping** a member of a public interface that consumers (Kafka sinks,
  `AuditEventSink`s) implement against or parse. Adding a typed twin (`kind` as an enum, a typed `binding`) is additive;
  it is the string that cannot be taken back.
- **Fix.** Decide now which of `decision`/`reasonCode` is the field and document the other as derived, or drop one;
  make `kind` an enum in `cosec-api` (the five values are closed); give `binding` a real shape or say it is an opaque
  label. If the wire format of the audit JSON is what matters (it is, for Kafka), freeze the JSON and keep the Kotlin
  free. M.

### R2A-4 (S3, S) `core/spi`: `AuthnResult.Authenticated.method` is a string, and its KDoc names a value nobody writes

- **Where.** `spi/authn/Authenticator.kt:61` (KDoc) and `:62`; `runtime/authn/ApiKeyAuthenticator.kt:86`
  (`METHOD = "api-key"`); `spi/identity/Identity.kt:168-169` (`Method.JWT`, `Method.API_KEY`).
- **What is wrong.** The same fact has two representations: `AuthenticatedPrincipal.credential.method` is the typed
  `Identity.Method`, `AuthnResult.Authenticated.method` is a `String` "such as `jwt` or `apikey`". The real value is
  `api-key`; an authenticator author who follows the KDoc writes `"apikey"` and the audit then holds two spellings for
  one method. A reader guesses wrong, as the brief asks to look for.
- **Breaking after RC: yes** for the type (a constructor of a class every custom `Authenticator` calls); the KDoc alone
  is not.
- **Fix.** Fix the KDoc now (S, free). Decide the type now: `Authenticated(principal, method: Identity.Method)`, or
  take the method from `principal.credential` and keep the string only for a principal without one.

### R2A-5 (S3, S) `core/document`: the published schemas name `main` as their identity

- **Where.** `format/DocumentFormats.kt:43` (`BASE_URI = ".../refs/heads/main/schema/"`); every `$id` in `schema/*.json`.
- **What is wrong.** The `$id` of each file, and the base every cross-file `$ref` resolves against
  (`definitions.schema.json` from the binding and catalog schemas), is the file on `main`. A tool that resolves
  references online gets the *next* release's definitions for an 8.0 file the day `main` moves, and the files stay
  mutually consistent only while nobody changes `main`. The wiki page that cites it is UE-25 of the usability triage
  (DOCS); the `$id` itself is not in that finding.
- **Breaking after RC: yes, as an identity.** No document stops compiling, but a changed `$id` is a different schema to
  anything that cached or pinned by it; after RC1 the `main` URL is already in editors' settings.
- **Fix.** Derive `BASE_URI` from the release (a tag URL) or serve `https://cosec.ahoo.me/schema/<n>/...`; `generateSchemas`
  and `verifySchemas` already regenerate and gate. S.

### R2A-6 (S4, S) `core/model`: `Resource.attributes` is a public constructor parameter that nothing fills

- **Where.** `model/AuthzRequest.kt:56` (`class Resource(val type, val id, val attributes: Map<String, String>)`).
- **What is wrong.** `Resource` is public because `AttributeRequest` (`@CoSecSpi`) exposes it. Every producer passes
  `emptyMap()`: `AuthorizationChecker.kt:105-111`, `IdentityGate.kt:224`, `ReadRequest.kt:57`, `RouteMap.kt:154`,
  `ControlRequestJson.kt:105-114`, `ControlResources.kt:45,57`, the kit's `EngineScenarios.kt:281,291`; `Enforceable.kt:43`
  only passes it through. DOC-10 (#1665) made the attribute unreachable from any document, and the checker refuses it.
  What is left: a KDoc that still says "`attributes` the request's `resource.*` attributes" (`AuthzRequest.kt:56`), a
  branch in `Evaluator` (`EvaluatorAttributes.kt:125`), an entry in `IndexOptions.INDEXABLE` that can never be an index
  attribute now (`IndexOptions.kt:64`), and a parameter every caller must write.
- **Breaking after RC: yes** to remove the parameter (public constructor, public getter).
- **Fix.** The DOC-10 decision keeps the attribute kind "for a later producer", so the cheap, non-breaking half is: a
  default `= emptyMap()`, and a KDoc that says "always empty today". Removing it is the breaking half, and a question
  for the maintainer: is there a producer planned for 8.x? S.

### R2A-7 (S4, S) `core/spi`: `SnapshotDocumentStore` is stable, future-based, and the only backend is CoCache

- **Where.** `spi/snapshot/SnapshotDocumentStore.kt`; listed as an extension point in `wiki/architecture/extending.md:39`.
- **What is wrong.** Every other data-plane SPI is `suspend` (AGENTS.md convention, `Authenticator`, `RateLimiter`,
  `MembershipProvider`, `TokenStore`); this one returns `CompletableFuture` and carries `SnapshotChangeListener`,
  `localCopyMaxAge` (a CoCache `localMaxAge` concept, named in its KDoc) and digest-based conditional reads. It is
  `@CoSecSpi` with no `@ExperimentalCoSecApi`, while the builder that takes it (`PipelineBuilder.kt:49`, the settings
  blocks) is experimental and `ControlStore`, which the same documents' writes go through, is experimental too. A third
  party who implements it today commits the shape to GA although no second implementation has tested it.
- **Evidence.** `@ExperimentalCoSecApi` appears on the builder, `ControlStore`, `CandidateStore`, the shadow ports; not on
  this one (`grep` over main sources).
- **Breaking after RC: yes** to change the signature to `suspend` or to remove `localCopyMaxAge`; adding the experimental
  marker is a warning, not a break, so it can be done later, but then it is a promise withdrawn.
- **Fix.** Either mark the port `@ExperimentalCoSecApi` for 8.0 (S, free now) or decide the shape now. I would mark it:
  the maintainer decided on 2026-11-19 to change the storage formats in beta.5 (the reverse-reference index and the
  document inventory), which suggests the store side is still moving.

---

## B. Not breaking (fix at any time)

### R2A-8 (S4, S) `core/model`: `ConditionChecker.isCredentialHeader` is dead, and wrong beside its twin

- **Where.** `model/condition/ConditionChecker.kt:369` (companion function).
- **What is wrong.** No caller in main or test code (a name search over the repository finds only the declaration). It
  tests `CREDENTIAL_HEADERS` only, while the instance method `isCredential` (`:55`) also refuses the headers the host
  gave (`cosec.pipeline.api-key-header`, RC1-21). CND-5 therefore has two definitions in one file, and the unused one is
  the one that misses the renamed API key header. A caller who finds it by completion gets the weaker answer. (The kit's
  `PermittedRequest.kt:198` has a third list of credential headers, with three of the six; it serves another purpose, to
  stop a test faking a credential, and is outside these modules.)
- **Breaking after RC: no** (`@InternalCoSecApi`, left out of the dump).
- **Fix.** Delete it. S.

### R2A-9 (S4, S) comments that are no longer true

All checked against the code; none changes behaviour.

| Where | What it says | What is true |
|---|---|---|
| `engine/CompiledDocument.kt:37` | the snapshot compiler is `PolicyDocumentCompiler` "in `cosec-runtime`" | it is in `cosec-document` (A1, #1369) |
| `model/condition/Condition.kt:22` | "the document parser in the host builds it" | `ConditionJson` in `cosec-document` |
| `model/condition/ConditionInput.kt:21` | "the host builds one per request from `AuthzRequest`" | the engine's `Evaluator` is the input |
| `model/AuthzRequest.kt:56` | `Resource.attributes` are "the request's `resource.*` attributes" | nothing sets them (R2A-6, DOC-10) |
| `spi/authn/Authenticator.kt:61` | method "such as `jwt` or `apikey`" | `api-key` (R2A-4) |
| `engine/Evaluator.kt:60` | "(see `Evaluator` for why)", inside `Evaluator`'s own KDoc | the sentence moved from `TracingEvaluator`; the reason is not written anywhere |
| `engine/SnapshotView.kt:51-52` | two stacked KDocs: "whether a policy of these documents reads an external attribute" sits on `limitsRates` | it belongs to `readsExternal` (`:56`); `limitsRates` has its own |
| `document/identity/IdentityCodec.kt:74-75` | two stacked KDocs on `MAX_SECONDS` | one is left over |

- **Fix.** One docs-only commit. S. A detekt rule for two adjacent KDoc blocks would have caught two of them (a regex
  `\*/\s*\n\s*/\*\*` over main sources finds exactly those two in these modules).

### R2A-10 (S4, S) conformance: rule `ATR-3` contradicts `ATR-6`, and they share their guards

- **Where.** `test/conformance/src/test/resources/rules/ATR.json`.
- **What is wrong.** ATR-3: "A DENY may say `onMissing: nomatch` only in the platform document ... elsewhere the document
  does not compile." ATR-6: such a DENY compiles **in any document** with the control plane's confirmation. The code
  (`PolicyCompiler.checkOnMissing`; `OnMissing.kt` KDoc: "in any document ... `ATR-6`") follows ATR-6; ATR-3 is the
  pre-confirmation rule (RFC 0007 N6), and both list the same guards (`engine:ExternalAttributesTest`,
  `document:ExternalAttributeDocumentTest`), so the guard of ATR-3 passes with ATR-3's text false. ATR-3 and ATR-4 also
  have no mutant (the others of ATR do), and both guards are whole classes.
- **Breaking after RC: no.**
- **Fix.** Reword ATR-3 to what is left of it (a DENY without the confirmation does not compile, in any document), or fold
  it into ATR-6 and keep ATR-7 for the rate limit rules. Add one mutant for it (`atr-6-unconfirmed-nomatch-deny-compiles`
  exists and probably kills it, so the cheap fix may be only the text). S.
- **Related, from the same pass.** Rules in these families with no mutant: `ACT-2`, `ATR-3`, `ATR-4`, `BND-3`, `BND-8`,
  `CND-2`, `CND-4`, `DOC-1`, `DOC-2`, `DOC-3`, `OBL-1`, `SCP-3`, `SCP-5`, `SCP-7`, `SCP-8`, `RTE-1`, `RTE-2`, `RTE-3`. I
  did not check which of them a neighbour's mutant already kills; `SCP-7`, `CND-2`, `BND-8`, `DOC-1` and `DOC-3` have
  `guards: []` and rest on scenario files alone.

### R2A-11 (S4, S) `core/document`: the problem vocabulary is written in two places

- **Where.** `format/DocumentFormats.kt:80-91` (`problemTypes`, `PROBLEM_TYPE_PREFIX`, and the `UPPER_SNAKE` rule at `:109`)
  and `core/runtime/.../http/ProblemType.kt:24-43` (the enum, `TYPE_PREFIX`, `code = name`).
- **What is wrong.** Ten rows and a URI prefix are written twice, and `ProblemSchemaTest` (runtime) keeps them equal;
  its own KDoc says so (`DocumentFormats.kt:27,77`). `runtime` depends on `document`, never the other way, so the schema
  side cannot read the enum, but the enum can be checked against, or built over, the rows in `document`. A new problem
  type (8.1) is a three-place change (enum, rows, wiki), and the guard only catches the first two.
- **Breaking after RC: no** (`ProblemType` keeps its API).
- **Fix.** Make `DocumentFormats.problemTypes` the one table and have `ProblemType` assert against it at class init, or
  put the table in `document` and generate the enum's rows from it. S.

### R2A-12 (S4, S) `core/document`: `PolicyDocument` refuses `budgets` and says it refused `limits and attributes`

- **Where.** `document/PolicyDocument.kt:97-99`.
- **What is wrong.** The `require` is `(limits == null && attributes == null && budgets == null) || kind == PLATFORM`; the
  message is "Only the platform document has limits and attributes." An app document with `budgets` set gets a message
  that does not name `budgets` (CTL-40/41's field), so the author looks for `limits` and finds it absent. (The decoder
  may refuse earlier with a path; I checked the constructor only.)
- **Breaking after RC: no.**
- **Fix.** Name the three members in the message. S.

### R2A-13 (S4, S) `core/model`: the tenant id rule is called `isValidAppId`

- **Where.** `model/scope/ScopeResolver.kt` (`isValidAppId`, `MAX_APP_ID_LENGTH`), `spi/snapshot/SnapshotKey.kt`
  (`Tenant.isValidTenantId = ScopeResolver.isValidAppId`, `App.isValidAppId = ScopeResolver.isValidAppId`).
- **What is wrong.** One rule (not blank, not `*`, no `:` or `/`, no control character, no unpaired surrogate, at most 128
  characters) has three names, and the one it lives under names only one of its two uses: the KDoc of `isValidAppId`
  says "the rule of a tenant id", and `MAX_APP_ID_LENGTH` bounds tenant ids. `ApiKey`, `VerifiedToken` and `SnapshotKey`
  each call the one that is not about what they validate. The rule is "an id that names a stored document and a key
  segment".
- **Breaking after RC: no** for `ScopeResolver` (`@InternalCoSecApi`); `SnapshotKey.Tenant.isValidTenantId` and
  `App.isValidAppId` are public, so renaming those is, keep them as they are.
- **Fix.** Add `Identifiers.isDocumentOwner(id)` (or similar) as the one definition, let `isValidAppId` and
  `isValidTenantId` delegate, and move the constant. S.

### R2A-14 (S4, S) `core/spi`: every API-key request builds a `MessageDigest`

- **Where.** `spi/apikey/ApiKeys.kt` `hash`; called per request by `runtime/authn/ApiKeyAuthenticator.kt:53`.
- **What is wrong.** `MessageDigest.getInstance("SHA-256")` per call, plus `toByteArray()` and the hex string. The
  neighbouring comment shows the cost was looked at ("a `String.format` per byte cost every request") and the hex step
  was fixed; the instance step was not. I have no number: the benchmark that would show it is `PipelineBenchmark`'s
  `apikey` scenario against its `role` one (its header comment says to compare them).
- **Breaking after RC: no.**
- **Fix.** A `ThreadLocal<MessageDigest>` or `clone()` of a prototype; measure with that pair of scenarios first. S.

### R2A-15 (S4, M) `core/document`: a route map is a linear scan with a path parse of its own

- **Where.** `route/RouteMap.kt` `resolveExactly` (`HttpPath.parse(path, true)`, then `for (compiled in candidates)`).
- **What is wrong.** A request parses its path here, scans every route of its method in load order until one matches
  (each attempt a `match` that allocates its captures only after the literal segments agree, which is good), and, when
  it matches no route, the engine parses the same path again for its `http:path:` patterns (`ResourceTarget`). A service
  with a few hundred routes pays the scan per request; the engine already has a path tree for exactly this
  (`StatementIndex`'s `PathNode`, the leading literal segments). `RouteMap.of` also compares every pair of routes
  (`O(n²)`, each pair `disjoint(...)` splitting two strings), once at load.
- **Evidence.** `PipelineBenchmark`'s `routed` scenario has one route and says "no other scenario resolves a route": it
  prices a hit among one candidate, so it cannot show the scan or the double parse. A scenario with a few hundred routes
  of one method, and one unmapped request, would. I did not measure it.
- **Breaking after RC: no** (internal structure of a public class).
- **Fix.** Index routes by their leading literal segments like `StatementIndex.PathNode`, and hand the parsed `HttpPath`
  to the pipeline for reuse. M. Worth doing only when the benchmark shows it; the target deployment decides at the
  gateway, where route maps are not loaded (web.md), so this is a per-service cost, not the SaaS gateway's.

---

## C. Read and considered sound

- **`api/annotations`** (3 files): the three markers and their KDocs match what `SpiMarkerTest`/`PublicSignatures` enforce
  (`@InternalCoSecApi` error-level and left out of the dump, `@ExperimentalCoSecApi` warning-level and in it, `@CoSecSpi`
  plain). Nothing to change.
- **`api/api`**: `CompositeToken` (no token in `toString`), `CoSecPrincipal`, `SecurityContext` and
  `DEFAULT_TENANT_ID` (held equal to `TenantId.DEFAULT` by `DefaultTenantIdTest`). Apart from R2A-3, undocumented enum
  values (`TOO_MANY_REQUESTS`, `ERROR`, ...) are the only gap and cost nothing.
- **`core/model`**: `Ids.kt` (value classes only at API edges, as its KDoc says; the surrogate and control-character
  rules in one object), `ScopeResolver` and the `Scope` constructor rule, `Memberships`, `Binding`/`Match`,
  `CheckedCondition` (total, no allocation in `test`, checked by its own limits), `ConditionChecker` (path-bounded
  errors; node and depth counters; every attribute resolved through `attribute`, CND-5), `ConditionLints` (direction
  logic), `IndexKeys`, `IpRanges`/`CidrBlock` (sorted disjoint ranges, binary search), `HttpNames`, `Ascii`. The
  `ConditionChecker.kt` 403-line ledger entry is the least bad choice: one function per operator of a closed AST. It
  could drop under 400 by moving `utf8Length`/`name` to a companion file, but that is not worth a review.
- **`core/engine`**: `Evaluator` and `EvaluatorSteps` (deny-first and the boundary in one place, state as `@JvmField`
  with the reason in the KDoc, errors as `Exception` only), `CandidateOrder` (documented order matches the code, no
  allocation in a visit), `StatementIndex` (the lookup merges sorted position arrays), `CompiledDocument` (a hash lookup
  per subject; budgets with their reasons: `MAX_RATE_LIMIT_RULES`, `MAX_GLOB_UNITS`, `MAX_BOUNDARY_STATEMENTS`),
  `PolicyCompiler` (refuses what would match less than it says, DOC-8/9/11/14, with messages that say what to write),
  `HttpPath`/`PercentDecoding` (Spring's behavior, compared by `HttpPathPatternParityTest`, which exists in
  `cosec-runtime` as the KDoc says), `ResourcePattern`, `CompiledRateLimit` (bucket escaping). The three
  `@Suppress("TooManyFunctions"/...)` of `Evaluator` and `CheckedCondition.Run` carry a reason that holds.
  The engine's public surface is three types plus the trace (R2A-1 covers `ViewVersion`), which is the right size.
- **`core/spi`**: `Authenticator`, `AuthenticatedPrincipal` (the provenance flags are unforgeable by construction; the
  two public constructors refuse `JWT`), `VerifiedToken` (every rule of a token's claims in the constructor),
  `TokenStore`/`TokenRevoker`/`RevocationReader` (KDocs state who decides what a failure means), `MembershipProvider`
  and `MembershipAnswer` (`Unknown` is not "no roles"), `ApiKey`, `TokenFamilyStore` (conditional writes), `IdGenerator`,
  `AuditEventSink` (BEST_EFFORT only, said so in three places).
- **`core/document`**: `PolicyDocument` (one `copy`, held by `PolicyDocumentAtTest`), `PolicyDocumentCompiler` (compiles
  whole or not at all), `RoleExpansion` (the one reader of what a document grants), `SnapshotDocuments` (streaming
  header read, strict duplicate detection), `IdentityCodec` (strict, bounded, a header written is a header read),
  `RouteMap` (all-or-nothing load, overlap refused, escapes reported with printable ids).
- **Ledgers.** `SourceSizeLedger`: one entry in my modules (`ConditionChecker.kt`), see above. `TypeLedger`: every entry in
  my modules is a family of the four kinds, none is debt. `PackageLedger`: only `document` has a table in my modules,
  and its edges (`root -> json`, `format -> root`, `shadow -> json, root`) are the least bad. `DetektSuppressionTest`
  `RECORDED` has no suppression of the three structure rules in `model`, `engine` (apart from `Evaluator`),
  `spi`, `document` beyond the ones above and `Identity.copy` / `PolicyDocument` (`LongParameterList`, "a copy takes
  each field", true).
- **Duplication checked and left.** `AuditDocument`/`AuditVia` against `DocumentKind`/`BindingRef` (layering, see R2A-3);
  the `\uXXXX` escape written in `Identifiers.printable`, `CompiledRateLimit.keyedBucket` and `CheckedCondition.quote`
  (three spellings, each two lines, with different case and context; not worth a shared helper).
- **Derived role bindings and memory.** The compiled tenant document of the SaaS measurement is 29.7 KB for a 3.5 KB
  document (`saas-scale-data-plane.md`, section 1; I did not re-measure), mostly the bindings that `RoleExpansion` derives
  from `roles`. A compact role-to-permission structure would be smaller and faster to walk, but it is a redesign of
  `CompiledDocument`/`CandidateOrder` without a defect behind it, so it is not a finding. The `role-heavy` scenario of
  `EngineAuthorizationBenchmark` (12 roles of 30 permissions plus a 120-permission grant) is the one that would price it.

## D. Not read, or read only in part

- `core/model`: `IpAddress`, `IsoTime`, `Time`, `TimeChecker`, `ConditionContainment` beyond its contract (soundness of
  containment is the control plane's grant check, and property A2 guards it).
- `core/engine`: `TracingEvaluator`, `AttributePlanner`, `EvaluatorGuards`/`Obligations`/`Failures`, `SegmentGlob`,
  `PatternContainment`, `HttpPathPatternParser`, most of `HttpPathPattern`, `BuiltinPolicies`.
- `core/spi`: `InMemory*` classes, `shadow/*`, `SnapshotDocument` equality beyond the KDoc, the token exception classes.
- `core/document`: `ConditionJson`, every `*Codec` except `PolicyDocumentCodec`'s header and `IdentityCodec`,
  `DocumentChecks`, `DocumentIds`, `DocumentBudgets`, `JavaZones`, `format/*` descriptors (only `DocumentFormats`),
  `ShadowCodec`, `RouteMapCodec`.
- Tests: I read rule statements and guard names (`rules/*.json`, the mutant directory listing), not the test bodies; so
  "a guard that passes with the rule broken" is established for ATR-3 only (R2A-10), by its text.
- Not run: any Gradle task, so every "no caller" claim is a text search over `*.kt`, and every ABI claim is a read of the
  `api/*.api` dumps.
