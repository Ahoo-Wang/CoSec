# CoSec 8.0 RC deep review, round 1, area B: the formats

The first round of the two deep reviews RC1 requires of the integrated system (`docs/roadmap.md` §4, RFC 0008 §3.9
prerequisite 6), for area B: `core/document`, `core/spi`, `api/api` and `api/annotations`. A second session re-reads it
after the fixes. Area A ([`round-1-a-decision-core.md`](round-1-a-decision-core.md)) covers the engine these documents
compile into; the earlier threat-model review is [`../8.0-rc-review.md`](../8.0-rc-review.md), whose ids (`RC1-*`) are
cited where a fix of it was re-checked.

| | |
|---|---|
| Commit reviewed | `origin/main` at `fb1f561c` (#1906); the area is unchanged at `184b4f92` (#1907), on which this file is committed |
| Date | 2026-11-23 |
| Scope | every main source file of `core/document` (59 files, 6,309 lines), `core/spi` (52 files, 3,303 lines), `api/api` (4 files, 395 lines) and `api/annotations` (3 files, 94 lines); `schema/8.0/` through its descriptors |
| Reviewer | one read-only session, not an author of the code reviewed; it changed no code, test, rule or RFC |
| Method | as for area A: the trust boundary first, then every file read end to end against RFC 0004 §7, RFC 0005, RFC 0009 §5, RFC 0012 and the `DOC`, `ROL`, `RTE`, `ACT`, `TNT` and `MEM` rules, with an attacker's and an operator's view. Tests, scenarios and mutant headers were read only to decide whether a property has a guard. Each finding was checked against the code; the one arithmetic claim was reproduced in `jshell` |

**Severity** (this review's scale). **S1**: a request permitted that should be denied, cross-tenant access, privilege
escalation or fail open, remotely and without privilege. **S2**: the same with a privileged position or a rare
condition; a denial of service a low-privileged party triggers cheaply; a credential leak. **S3**: weakened defence in
depth, a denial of service that needs privilege, behaviour that misleads an operator, a mismatch between the
specification and the code with no exploitable path. **S4**: hardening and documentation.

## Summary

No S1 or S2 finding. The codecs are strict where it matters (duplicate keys, unknown members, exact JSON types, the
schema number, no data binding anywhere: every reader walks a tree), a document compiles whole or not at all, the
permission namespace keeps tenants and apps apart, `cosec:platform-admin` cannot be bound outside the platform and the
platform tenant, and short forms reach the engine only through `RoleExpansion`. One S3: the entry budgets that bound a
tenant administrator's change count only what a document writes by hand, so roles and catalog short forms derive past
them. The rest is S4: an arithmetic overflow that escapes the codec's error type, an unbounded in-memory store, and the
two registry inconsistencies the coordinator listed.

| Id | Severity | Area | Finding | Status |
|---|---|---|---|---|
| DR1-B-1 | S3 | Documents, budgets | The entry budgets (`CTL-40`) count only hand-written entries: a tenant's custom roles derive up to 50,000 role bindings (five times `maxRoleBindings`) from any number of roles, and an app's catalog `action` entries derive policies no budget counts | open |
| DR1-B-2 | S4 | Codecs | A binding's `expiresAt` far beyond year 9999 makes `toEpochMilli` throw `ArithmeticException`, which escapes the codec's `SnapshotFormatException`: the control API answers 500 and logs an error for a malformed client field | open |
| DR1-B-3 | S4 | SPI | `InMemorySocialStateStore` keeps every state an anonymous caller makes start a sign-in for its lifetime, with no bound on their number | open |
| DR1-B-4 | S4 | Rule registry | `DOC-1` still says "a request that reaches a Broken entry", and `SnapshotCompiler`'s KDoc still asks for `Broken` entries, while RFC 0003 and `PolicyDocumentCompiler` compile a document whole or not at all | open |
| DR1-B-5 | S4 | Rule registry | `ALG-10` cites `PIP-5` and "RFC 0005 §6.1"; RFC 0005 §6.2, where the rule is, cites `PIP-14`; neither rule's statement names the refusal it relies on (a malformed percent-escape), which only `PIP-5`'s guard tests | open |

## Files read

Every main source file of the area, end to end: 118 files, 10,101 lines. **Not read: none.** The format descriptors
(`document/format/`) were read for what they say a document is; whether they agree with the codecs is
`SchemaConformanceTest`'s, which was not re-run.

**`api/annotations/src/main/kotlin/me/ahoo/cosec/annotations/`**: 3 files, 94 lines.

- `CoSecSpi.kt` (27)
- `ExperimentalCoSecApi.kt` (34)
- `InternalCoSecApi.kt` (33)

**`api/api/src/main/kotlin/me/ahoo/cosec/api/audit/`**: 1 files, 235 lines.

- `AuditEvent.kt` (235)

**`api/api/src/main/kotlin/me/ahoo/cosec/api/context/`**: 1 files, 89 lines.

- `SecurityContext.kt` (89)

**`api/api/src/main/kotlin/me/ahoo/cosec/api/principal/`**: 1 files, 43 lines.

- `CoSecPrincipal.kt` (43)

**`api/api/src/main/kotlin/me/ahoo/cosec/api/token/`**: 1 files, 28 lines.

- `CompositeToken.kt` (28)

**`core/document/src/main/kotlin/me/ahoo/cosec/document/`**: 27 files, 3098 lines.

- `Actor.kt` (34)
- `BindingCodec.kt` (119)
- `Catalog.kt` (51)
- `CatalogCodec.kt` (70)
- `ConditionJson.kt` (163)
- `DocumentBudgets.kt` (88)
- `DocumentChecks.kt` (217)
- `DocumentCompileException.kt` (39)
- `DocumentIds.kt` (115)
- `DocumentJson.kt` (43)
- `DocumentNode.kt` (115)
- `Expansion.kt` (39)
- `JavaZones.kt` (82)
- `PlatformSettingsCodec.kt` (140)
- `PolicyCodec.kt` (49)
- `PolicyDefinition.kt` (32)
- `PolicyDocument.kt` (149)
- `PolicyDocumentCodec.kt` (233)
- `PolicyDocumentCompiler.kt` (318)
- `Provenance.kt` (89)
- `ProvenanceCodec.kt` (69)
- `RateLimitCodec.kt` (72)
- `RoleCodec.kt` (82)
- `RoleDefinition.kt` (60)
- `RoleExpansion.kt` (324)
- `SnapshotDocuments.kt` (173)
- `StatementCodec.kt` (133)

**`core/document/src/main/kotlin/me/ahoo/cosec/document/format/`**: 16 files, 1722 lines.

- `BindingFormats.kt` (128)
- `CatalogFormats.kt` (73)
- `ConditionFormats.kt` (109)
- `DocumentFormats.kt` (265)
- `FormatParts.kt` (34)
- `FormatPatterns.kt` (121)
- `FormatType.kt` (146)
- `JsonSchemaWriter.kt` (209)
- `PlatformFormats.kt` (70)
- `PolicyDocumentFormats.kt` (109)
- `PolicyFormats.kt` (128)
- `ProblemTypes.kt` (54)
- `ProvenanceFormats.kt` (78)
- `RateLimitFormats.kt` (92)
- `RoleFormats.kt` (70)
- `SchemaFile.kt` (36)

**`core/document/src/main/kotlin/me/ahoo/cosec/document/identity/`**: 5 files, 529 lines.

- `IdentityCodec.kt` (318)
- `IdentityHeader.kt` (44)
- `QuotedText.kt` (40)
- `Unencodable.kt` (48)
- `WrittenIdentity.kt` (79)

**`core/document/src/main/kotlin/me/ahoo/cosec/document/json/`**: 3 files, 129 lines.

- `CoSecJson.kt` (36)
- `CoSecModule.kt` (41)
- `StoredFormat.kt` (52)

**`core/document/src/main/kotlin/me/ahoo/cosec/document/route/`**: 7 files, 562 lines.

- `Route.kt` (25)
- `RouteEscape.kt` (53)
- `RouteMap.kt` (302)
- `RouteMapCodec.kt` (105)
- `RouteMapDocument.kt` (21)
- `RouteMapException.kt` (22)
- `RouteResolution.kt` (34)

**`core/document/src/main/kotlin/me/ahoo/cosec/document/shadow/`**: 1 files, 269 lines.

- `ShadowCodec.kt` (269)

**`core/spi/src/main/kotlin/me/ahoo/cosec/spi/apikey/`**: 3 files, 161 lines.

- `ApiKey.kt` (90)
- `ApiKeys.kt` (40)
- `InMemoryApiKeyStore.kt` (31)

**`core/spi/src/main/kotlin/me/ahoo/cosec/spi/attribute/`**: 1 files, 84 lines.

- `AttributeProvider.kt` (84)

**`core/spi/src/main/kotlin/me/ahoo/cosec/spi/audit/`**: 1 files, 25 lines.

- `AuditEventSink.kt` (25)

**`core/spi/src/main/kotlin/me/ahoo/cosec/spi/authn/`**: 1 files, 345 lines.

- `Authenticator.kt` (345)

**`core/spi/src/main/kotlin/me/ahoo/cosec/spi/id/`**: 2 files, 74 lines.

- `IdGenerator.kt` (35)
- `RandomIdGenerator.kt` (39)

**`core/spi/src/main/kotlin/me/ahoo/cosec/spi/identity/`**: 1 files, 206 lines.

- `Identity.kt` (206)

**`core/spi/src/main/kotlin/me/ahoo/cosec/spi/membership/`**: 5 files, 240 lines.

- `InMemoryMembershipProvider.kt` (46)
- `MembershipAnswer.kt` (32)
- `MembershipClaims.kt` (89)
- `MembershipProvider.kt` (50)
- `MembershipUnavailableException.kt` (23)

**`core/spi/src/main/kotlin/me/ahoo/cosec/spi/ratelimit/`**: 3 files, 114 lines.

- `RateLimitResult.kt` (49)
- `RateLimitSpecs.kt` (36)
- `RateLimiter.kt` (29)

**`core/spi/src/main/kotlin/me/ahoo/cosec/spi/shadow/`**: 9 files, 572 lines.

- `CandidateStore.kt` (35)
- `InMemoryShadowReports.kt` (58)
- `InstanceStatus.kt` (69)
- `ResidentDocuments.kt` (35)
- `ShadowReport.kt` (148)
- `ShadowReportReader.kt` (116)
- `ShadowReportSink.kt` (36)
- `StagedCandidates.kt` (42)
- `StoredCandidate.kt` (33)

**`core/spi/src/main/kotlin/me/ahoo/cosec/spi/snapshot/`**: 9 files, 603 lines.

- `InMemorySnapshotDocumentStore.kt` (120)
- `SnapshotCompiler.kt` (29)
- `SnapshotDocument.kt` (138)
- `SnapshotDocumentStore.kt` (87)
- `SnapshotFormatException.kt` (22)
- `SnapshotKey.kt` (100)
- `SnapshotKeyText.kt` (66)
- `SnapshotStaleException.kt` (21)
- `SnapshotUnavailableException.kt` (20)

**`core/spi/src/main/kotlin/me/ahoo/cosec/spi/social/`**: 2 files, 99 lines.

- `InMemorySocialStateStore.kt` (65)
- `SocialStateStore.kt` (34)

**`core/spi/src/main/kotlin/me/ahoo/cosec/spi/token/`**: 15 files, 780 lines.

- `DelegationUnsupportedException.kt` (22)
- `InMemoryTokenFamilyStore.kt` (106)
- `RefreshTokenMismatchException.kt` (25)
- `RevocationReader.kt` (92)
- `RevocationUnavailableException.kt` (26)
- `TokenExpiredException.kt` (25)
- `TokenFamilyStore.kt` (82)
- `TokenNotYetValidException.kt` (25)
- `TokenRevokedException.kt` (26)
- `TokenRevoker.kt` (67)
- `TokenSession.kt` (40)
- `TokenStore.kt` (59)
- `TokenVerificationException.kt` (32)
- `TokenVerifier.kt` (50)
- `VerifiedToken.kt` (103)

## Trust boundary

What this area reads, from whom, and what is verified before it is believed:

| Input | Written by | Verified here | Then |
|---|---|---|---|
| A stored platform, app or tenant document (`SnapshotDocument` bytes) | the control plane on publish; `cosec-migrate`; anyone with write access to Redis | `SnapshotDocuments.read` (UTF-8, no BOM, one object, unique keys, header types, `schema: 2`, revision ≥ 1); `PolicyDocumentCodec.decode` (strict tree: unknown members, wrong JSON types, duplicates); `PolicyDocumentCompiler.compile` (whole document) | the engine (area A); a document that does not compile is installed as failed (area D) |
| A document or entry in a control request | an authenticated caller of the control API: a tenant administrator for their tenant, a platform principal for any | the same codec and compiler, then the control plane's checks (`TNT-3`, the grant analysis, budgets) | stored as above |
| A route map (`cosec-routes.json`) | the service's build (`cosec-openapi`) or its operator | `RouteMapCodec` (strict) and `RouteMap.of` (all or nothing, overlapping routes refused) | turns `http:<METHOD>` on a path into `service:verb` on a typed resource |
| The `CoSec-Identity` header | the gateway; anyone who can reach a service behind it (unsigned, `RC1-19`) | `IdentityCodec.decode`: size, base64url without padding, strict JSON, every member's type, times bounded to year 9999, no unpaired surrogate | `IdentityGate` (area C) re-resolves the scope |
| A verified token (`VerifiedToken`), an API key (`ApiKey`), a provider's memberships | a `TokenVerifier`, an API key store, a `MembershipProvider` | the value classes refuse empty, reserved (token subject), non-owner (tenant, app) and ill-formed (unpaired surrogate) ids; `MembershipClaims.parse` splits `role@space` and keeps malformed entries apart | area C |
| Shadow reports, instance statuses, candidates (`ShadowCodec`) | every instance; the control plane | strict tree, format number, instance-id grammar, non-negative counts, a candidate's header only from its document | the control plane's window decisions (out of scope) |

Attackers considered: a tenant administrator writing their own tenant's document (the main one for this area); an
app's owner writing an app document; a platform operator; a caller forging `CoSec-Identity` behind the gateway; someone
with write access to Redis; an anonymous caller (route maps, the social state store); a third-party authenticator or
verifier an application plugs in.

## Findings

### DR1-B-1 · S3 · Short forms derive past the entry budgets

- **Location:** `DocumentBudgets.Entry.count` (`core/document/.../DocumentBudgets.kt`), which counts
  `document.policies` and `document.bindings` as written; `RoleExpansion.expand` and `RoleExpansion.grants`
  (`RoleExpansion.kt`); the control plane holds candidates to the budgets in `EntryBudgets.check` on the written
  document.
- **Trigger (tenant administrator):** a tenant document with, say, 5,000 custom roles of one app, each listing 10
  permissions. It writes no role binding by hand, so `maxRoleBindings` (10,000) counts zero; `RoleExpansion` derives
  50,000 role bindings, exactly the compiler's `roleGrantLimit`; the document is about 0.5 MB, well within the 8 MiB
  document cap. Nothing counts roles themselves.
- **Trigger (app owner):** an app document whose catalog holds 100,000 entries with `action` (about 60 bytes each,
  within 8 MiB). Each derives a `perm:<app>/<id>` policy with one statement; `maxPolicies` (10,000) and `maxStatements`
  (20,000) count none of them, and no other bound applies to a catalog.
- **Actual:** the budgets exist to bound what checking a change costs the control plane, the grant analysis of a tenant
  administrator's change above all (`CTL-40`: "a change of 100,000 entries took over a minute and exhausted a 512 MB
  heap"), and the document's resident size on every instance (`SNP-24` weighs by statements and bindings). A short form
  reaches five times the role-binding budget, and a catalog has no bound but the document cap.
- **Expected:** `CTL-40` says the budgets bound "the policies, statements, bindings to roles and bindings to Anyone one
  document holds"; a reader takes "holds" to include what the document grants through its roles, since everything that
  reads what a document grants reads `RoleExpansion.effective` (`ROL-2`). `DocumentBudgets.Entry` says the opposite on
  purpose ("a role's short form is not written out"), so this is a gap in the bound, not a slip in the code.
- **Basis:** `CTL-40`, `ROL-8`, RFC 0012 §6.3 (the 50,000 limit was sized against an 8 MiB document of hand-written
  bindings, about 50,000, not against the budgets that came after it).
- **Reproduced:** no; by the code. How long the analysis of 50,000 derived bindings takes was not measured.
- **Fix direction:** count the effective document: `Entry.count` over `RoleExpansion.effective` (or over the
  `Expansion`, which already knows what it derived), so derived bindings count against `maxRoleBindings` and derived
  policies and statements against theirs; or lower `roleGrantLimit` to the role-binding budget and add a catalog bound.
  Either keeps a stored document over a budget served, as `CTL-40` requires. Regression: a `CTL` scenario (or
  `EntryBudgetsTest` case) where a change adding roles that derive more than `maxRoleBindings` is refused with the
  budget named, and one for a catalog over `maxPolicies`; a mutant that counts written entries only.

### DR1-B-2 · S4 · An `expiresAt` beyond `Long` milliseconds escapes the codec's error type

- **Location:** `BindingCodec.decode` (`node.field("expiresAt")?.instant()?.toEpochMilli()`), `DocumentNode.instant`.
- **Trigger:** a binding with `"expiresAt": "+1000000000-12-31T23:59:59Z"` (or any instant past about year
  292,000,000), sent in a control request (`PutBinding`, `apply`) or written to Redis.
- **Actual:** `Instant.parse` accepts it; `toEpochMilli()` throws `ArithmeticException` (reproduced in `jshell`), which
  `BindingCodec`'s `catch (IllegalArgumentException)` does not catch, so `PolicyDocumentCodec.decode` throws it instead of
  a `SnapshotFormatException`. In the control API the `decoded(...)` wrapper turns only `SnapshotFormatException` into a
  400, so `ControlHttp` answers 500 and logs the request at ERROR as a defect (`catch (e: Exception)`). On the data plane a
  stored one reaches whatever the snapshot source does with an unexpected exception from a compile (area D checks that it
  fails closed).
- **Expected:** a client field that does not parse is a 400 naming the field (RC1-39, `CTL` rules on 400s); a stored
  value that does not decode is a `SnapshotFormatException` (`SnapshotDocumentStore`'s contract). The schema's
  `date-time` already refuses a year past 9999.
- **Basis:** RC1-39; `DOC-3` ("a value of the wrong JSON type" fails to decode, and the requests that need it fail closed).
- **Reproduced:** the overflow in `jshell`; the propagation by the code.
- **Fix direction:** `DocumentNode.instant` (or an `epochMillis()` beside it) refuses an instant outside the years the
  schema allows, as `IdentityCodec.seconds` bounds its times to year 9999, with a `SnapshotFormatException` naming the
  path. Regression: a codec test with that `expiresAt` expecting `SnapshotFormatException`, and a control API test
  expecting 400.

### DR1-B-3 · S4 · `InMemorySocialStateStore` has no bound on how many states it holds

- **Location:** `core/spi/.../social/InMemorySocialStateStore.kt` (`put`).
- **Trigger:** an anonymous caller starts social sign-ins in a loop on a single instance that uses this store (its KDoc
  offers it "for a single instance that issues and receives every callback"). Each start puts a state for 180 s
  (`SocialAuthorization.STATE_LIFETIME_SECONDS`).
- **Actual:** the sweep drops only expired states, so the store holds every state of the last 180 s: memory grows with
  the attacker's request rate. The class bounds the cost of a sweep (its KDoc: "anyone can start a sign-in, so put must
  stay cheap"), not the memory. The starter wires `RedisSocialStateStore`, never this one, so only an application that
  chose it is exposed.
- **Basis:** RFC 0006 §7 (no unbounded growth an anonymous caller drives), the KDoc's own threat.
- **Reproduced:** no; by the code.
- **Fix direction:** a capacity (fixed, or a `HeapBudget` share as `STA-12` requires of memory held for performance:
  here it is held for correctness, so a fixed bound that refuses new states, failing the sign-in start, is the safe
  direction), or say in the KDoc and `extending.md` that it is for tests only. The same growth, bounded by Redis's
  memory and `noeviction`, applies to `RedisSocialStateStore`; area D looks at it.

### DR1-B-4 · S4 · `DOC-1` and `SnapshotCompiler` still describe `Broken` entries (coordinator's candidate)

- **Location:** `test/conformance/src/test/resources/rules/DOC.json` (`DOC-1`: "a request that reaches a Broken entry on
  the data plane fails closed (F4, F5)"); `core/spi/.../snapshot/SnapshotCompiler.kt` (KDoc: "An entry that fails to
  compile should be recorded in the result (RFC 0003 §3.4 `Broken`) so that only the evaluations reading it fail").
- **Actual:** `PolicyDocumentCompiler.compile` throws on any error and the document is installed as failed, so every
  request that needs the document fails closed, whatever it reaches. RFC 0003 (consolidated) says so: a document
  compiles whole or fails whole (`DOC-1`, F4), and the `SnapshotCompiler` contract still *allows* per-entry failures,
  which CoSec's compiler does not use. `DOC-1`'s statement and the KDoc's "should" describe the 7.x and stage-3 semantics.
- **Risk:** none on the data plane (failing the whole document is stricter); a reader of the registry or of the SPI is
  told a per-entry semantics that does not exist, and an implementer of `SnapshotCompiler` is encouraged towards it.
- **Basis:** RFC 0003 §3.4 (consolidated), `DOC-1`.
- **Fix direction (coordinator's, the registry and RFCs are not mine to edit):** restate `DOC-1` as "a document with a
  dangling or cross-tenant reference does not compile, and every request that needs it fails closed"; make the KDoc say
  "may" and that CoSec's own compiler fails the whole document. `DOC-1` has scenarios and four `doc-1-*` mutants; its
  statement changes, its guards need not.

### DR1-B-5 · S4 · `ALG-10`, `PIP-5` and `PIP-14` cross-references disagree (coordinator's candidate)

- **Location:** `ALG.json` (`ALG-10`: source "RFC 0005 §6.1", statement "The pipeline answers such a path 400 before
  authorization (PIP-5)"); `docs/rfc/0005-condition-ast.md` §6.2 ("这样的路径在进入授权前已被 400 拒绝（`PIP-14`）"); `PIP.json`.
- **Actual:** the paragraph `ALG-10` comes from is in RFC 0005 §6.2, not §6.1. The path it is about is one that "does not
  decode", a malformed percent-escape (`%zz`, `%2`, a trailing `%`). `PIP-14` is about empty segments, encoded
  separators and backslashes, so the RFC's citation is wrong. `PIP-5`'s statement names dot segments and lengths, not
  malformed escapes; only its guard `PathNormalizerTest#ambiguousPathsAreRejected` tests them (`/a/%zz/b`, `/a/%2`,
  `/a/%`). So the refusal `ALG-10` relies on is guarded but stated by no rule.
- **Risk:** none today; a change to `PathNormalizer` that let a malformed escape through would break `ALG-10`'s premise
  while every rule's statement still read as true.
- **Fix direction (coordinator's):** `ALG-10`'s source becomes §6.2; RFC 0005 §6.2 cites the rule that refuses malformed
  escapes; `PIP-5`'s statement (or a new `PIP` rule) names malformed percent-escapes, with the test above as its guard.

## Threats checked and found sound

**Codecs and stored formats.**

- *A document means less than it says.* `PolicyDocumentCodec` and every sub-codec read a tree with
  `STRICT_DUPLICATE_DETECTION`, refuse unknown members per document kind (`catalog` only in app documents, `boundaries`,
  `limits`, `attributes` only in the platform's), exact JSON types (`DocumentNode`), `schema` other than 2, a revision
  below 1; `*` is `Match.Any` and never an id (`DOC-2`, `DOC-3`, `DOC-5`, `SchemaConformanceTest`). No CoSec code data-binds
  a stored or exchanged value (`readValue`, `treeToValue` and `convertValue` appear nowhere in main code), so a Jackson
  module on the classpath (`CoSecJson.mapper` calls `findAndAddModules`) cannot change what a record means, and a
  resolved `Scope` is never deserialized (`CoSecModule`).
- *A header that contradicts the body.* `SnapshotDocuments.read` reads the header from the body with a streaming pass
  that still walks the whole document (duplicates, trailing content); the constructor that takes a header is internal
  (`SNP-12`). `ShadowCodec` refuses a candidate that states a header beside its document.
- *Forging log lines or keys through ids.* Every id a document writes is checked for control characters and unpaired
  surrogates (`DocumentIds`, `DOC-13`); compile and format errors are `Identifiers.printable`; route escapes are escaped
  and cut (`RouteEscape.shown`, `DOC-12`); shadow and identity refusals quote at most 64 characters, escaped
  (`SnapshotKeyText`, `QuotedText`).

**Who may bind what (`TNT`, `ROL`).**

- *A tenant reaches another tenant's or app's permissions.* `perm:` policies are defined only by their app's document,
  never attachable; a tenant binds one only scoped to exactly that app (`DocumentChecks.permissionError`); a tenant
  document's subjects are its own tenant's (`BoundEntries.requireReachable`, `BND-8`); a role a tenant defines derives
  bindings of its own tenant's role, scoped to the role's app (`RoleExpansion.binding`) (`TNT-2`, `TNT-4`, `ROL-11`, mutants
  `tnt-*`, `rol-11-*`).
- *A tenant makes itself the trust root.* `cosec:platform-admin` is bound only in the platform document and the platform
  tenant's (`DocumentChecks.checkBindings`, RC1-36, `tnt-2-tenant-binds-platform-admin`). Roles cannot derive a binding of
  a built-in policy: a derived binding always names `perm:<app>/<permission>`.
- *A short form grants through a path nothing analyzed.* Stored documents keep roles and catalog actions; the compiler,
  and per its KDoc every reader of grants, reads `RoleExpansion.effective`; derived binding ids are `role:<role>@<app>:<permission>`
  with `@` refused in role ids and `:`, `/` in app ids, `role:` reserved and collisions refused (`ROL-1`, `ROL-2`, `ROL-7`,
  mutants `rol-*`). A catalog `action` without `spaced` applies only in the default space (`ROL-6`).
- *Boundaries.* Only in the platform document, ALLOW only, at most 64 statements, never bound (also by a derived binding:
  the engine refuses any binding of a boundary id) nor attached; the platform tenant names none (`BDY-1`,
  `DocumentChecks.checkBoundaries`).
- *`nomatch` without a confirmation.* The codec reads `onMissingConfirmed`, the engine's compiler requires it on every
  `nomatch` DENY in any document (`ATR-6`).

**Route maps (`RTE`, `ACT`).**

- *A route map that answers one request two ways, or loads partly.* Strict codec, no empty map, every route compiled,
  `*` is no method, routes of one method pairwise provably disjoint, all or nothing (`RTE-1`, `RTE-3`).
- *A captured value changes the resource's shape.* A capture holding `/` (a decoded `%2F`) or empty is `Invalid`, 400
  (`RTE-2`); a `{*name}` capture cannot become an id segment; values are values, never patterns (`ACT-1`). HEAD falls back
  to GET's route (`PIP-18`). Unmapped requests keep `http:` (`ACT-2`); the http DENYs a route escapes are reported, not
  silently applied (`UC-1`, `RTE-5`).

**Identity and credentials (`IDN` as far as this area holds them).**

- *A forged or garbled `CoSec-Identity` read as anonymous or as more than it says.* `IdentityCodec.decode` refuses
  oversize, padding, non-base64url, unknown or mistyped members, duplicates, empty ids, unpaired surrogates, a missing
  `auth.exp`, times past year 9999, `iat` after `exp`, a session on a method other than `jwt`, `capped` other than
  `true`; the writer refuses what the reader would (`WrittenIdentity`). That the header is unsigned is RC1-19,
  accepted.
- *A token or key names a reserved, empty or ill-formed id.* `VerifiedToken` refuses an empty or reserved subject, an
  empty tenant or one that cannot own a document, an app that cannot be one, and unpaired surrogates; `ApiKey` the same
  for its ids; `AuthenticatedPrincipal`'s public constructors check well-formedness (`IDN-10`, `IDN-11`, `IDN-12`).
- *Another authenticator passes for CoSec's token.* `AuthenticatedPrincipal` ties `breakGlass` to method `jwt` and only
  the internal constructor makes one; the public constructors refuse `jwt` (`BRK-8`, RC1-26). `workload` is
  `api-key` (by provenance or name) or `service`; whether every place that tells people from workloads reads it is
  area C's check.
- *Memberships fail open.* `MembershipProvider` must throw on failure, `Unknown` is not "no roles", `MembershipSource`
  makes a marker with claims or an unknown marker `Unusable` (`MEM-1`, `MEM-2`); a cap only narrows (`IDN-2`).
- *Revocation fails open.* `RevocationReader` throws `RevocationUnavailableException` rather than answer "not
  revoked"; the one documented exception is the caller's (`IDN-14`); `SubjectRevocation.revokes` takes a token without
  `iat` as revoked and rounds towards revoking (`MEM-4`).

**SPI contracts.** Every data-plane port suspends and must not block; failures are thrown, never values left out
(`AttributeProvider`: a value left out is absent, a failure makes every asked attribute unavailable); `SnapshotDocumentStore`
puts a `SnapshotFormatException` in the cause chain of an undecodable value so the source can tell it from an outage; the
in-memory snapshot store notifies after the write is readable and never lets a subscriber undo it.

## Coordinator's candidates

- **`DOC-1` still says "Broken entry":** confirmed, **S4** (DR1-B-4), and `SnapshotCompiler`'s KDoc says the same.
- **`ALG-10` cites `PIP-5`, RFC 0005 §6.2 cites `PIP-14`:** confirmed, **S4** (DR1-B-5); neither rule's statement names the
  malformed escape, and `ALG-10`'s source section is off by one.
- **Misspelt DENY actions (shared with area A):** graded in area A, DR1-A-2 (S4). The document side adds nothing: the
  catalog's `action` entries are permissions, not a vocabulary the compiler could check against, and the route maps the
  control plane is given are the other half of the warning proposed there.
