# Usability review, group C: writing, testing, changing and migrating policy documents

Read-only review. Nothing was built or run: every message and behaviour below was read from the code that produces it,
and the message shapes were checked against strings existing tests in `core/document` and `core/model` assert
(`$.policies[0].statements[1].condition.eq[0]: unknown attribute \`nowhere\``, `unknown field …`, `[app] is required`). Where a claim
rests on a doc and not on code, it says so. Nothing in the worktree was changed.

## 1. Baseline

- Baseline: `origin/main` at `8207a42651def246b938bdb434b2cbe594872cfe` (the commit this session started on).
- At the end of the review `origin/main` was `dd4fc21419561539964fc84b0dd4278d1e30a275`.
  **#1657 had merged** (`dd4fc214`, 2026-11-18 23:43Z). **#1654 had not merged** (state OPEN). Its file list
  (wiki checks/resource-server pages, KDoc, skills) does not touch `wiki/policies/*` or any finding below, so none
  depends on it.
- Not covered: I did not run `cosec-migrate`, the control plane or the kit (shared machine); nothing about behaviour
  at run time is claimed without a code path. I read `cosec-openapi`'s generator only for its messages. I did not
  review the RFC 0007 lifecycle beyond the operations an author meets.

## 2. The five that matter most

1. **UC-1 (S1)** A platform `http:*` DENY or rate limit silently stops applying to every route a service later maps,
   and neither the scenario format, `simulate` nor any startup check can show it.
2. **UC-2 (S1)** `POST /documents/{key}:apply` with `MERGE` keeps every entry the file omits and the answer does not
   say so: removing a grant from the file does not revoke it.
3. **UC-3 (S1)** A DENY with `ignoreCase` on a non-ASCII literal compiles and publishes silently and misses other
   spellings; `cosec-migrate` refuses the identical shape.
4. **UC-4 (S2)** Before publishing, an author gets only counts after the shadow window closes. The 20 sampled requests
   per outcome the instances record are never shown, and `analyze` cannot take a candidate.
5. **UC-6 (S2)** A platform change that stops permitting the control API's paths publishes cleanly, and the only way
   back documented is to empty Redis.

Next in line: UC-7 and UC-8 (the policy test kit passes vacuously, and a failure does not say why).

Count: **S1 3, S2 8, S3 9, S4 2** (22 findings).

---

## 3. Findings

Format: who and what / what happens, with proof / recommendation / touches, breaking? / size / when.

### UC-1 (S1) An `http:` DENY or rate limit stops applying to a route the moment it is mapped, and no tool shows it

- **Who/what.** A platform author closes `/admin/**` for services they do not own; a service author adds a route map
  and checks the result with a scenario or `simulate`.
- **What happens.** Platform document:
  `{"id":"deny-admin","statements":[{"effect":"deny","action":"http:*","resource":"http:path:/admin/**"}]}` bound to
  `"*"`. A service later adds `{"method":"DELETE","path":"/admin/warehouses/{id}","action":"wms:warehouse.purge",
  "resource":"warehouse/{id}"}`. The request is now `wms:warehouse.purge` on `warehouse/7`; the DENY is never a
  candidate for it; the `admin` role's permission permits. The docs say so, thoroughly
  (`wiki/policies/language.md` "Where a request's action comes from", `wiki/scenarios/route-maps.md`, with a worked
  example under `wiki/examples/route-maps`). What no tool does:
  - The scenario format has `request.action`/`resource` only: `wiki/policies/testing.md` ("a request names its action
    and resource: no route map is applied"). A scenario `{"action":"http:DELETE","resource":{"type":"http","id":"/admin/warehouses/7"}}`
    passes with `Deny` while production decides `wms:warehouse.purge`. Only `PipelineFixture` takes `routes`
    (`test/testkit/.../PipelineFixture.kt`), and only from Kotlin.
  - `simulate`/`explain` take `action` and `resource` too (`ControlRequestJson.simulated`), not a method and path.
  - The host logs what it loaded (`host/spring-boot/.../pipeline/RouteMaps.kt`) and checks nothing against the
    documents. The one overlap check that exists is for the login routes (`LoginRouteDenials.kt`).
  - The condition language has no attribute for the request's method or path (the attribute table in
    `language.md`; `Attribute.Kind`), so "closed whether or not mapped" cannot be written once. RFC 0005 refused a
    second pattern language on purpose.
  - Nobody is told: the service team changes a route map, the platform team's DENY changes meaning.
- **Recommend.** (a) Tooling, no format change: when a host loads a route map, compare every `http:` DENY and rate
  limit rule of the documents it reads with every mapped route using the existing `PatternContainment` (the route map
  already uses it in `RouteMap.disjoint`), and log a WARN naming the statement and the route; offer the same list in
  `analyze`. Scenario format and `simulate`: a `request.http {method, path}` form decided through a route map the
  kit/control host loads (`routes/` beside the documents in the kit directory). (b) Needs the maintainer: additive
  `http.method` and `http.path` attributes (the normalized path, set for every HTTP request, mapped or not), so a
  platform DENY on action `*` with `prefix` can be written once. It is not a second pattern language, but it cannot
  capture `{var}`.
- **Touches.** (a) a startup message, a tool, the scenario format (additive). (b) the document format (additive).
  Neither breaks a stored document.
- **Size/when.** (a) M, before RC1 (the lint and the kit field); (b) M, 8.1.

### UC-2 (S1) `apply` with `MERGE` silently keeps what the file leaves out

- **Who/what.** An operator or sync job reconciles a document from a file: the roles page recommends "Periodic
  reconciliation: `apply` with `MERGE`" (`role-permissions.md`, "Syncing roles from an external source").
- **What happens.** The file no longer holds binding `contractor-reads` (a revocation). `Operations.diff`
  (`core/control/.../lifecycle/Operations.kt`) emits removes only under `if (mode == ApplyMode.REPLACE)`.
  `DocumentRoutes` answers with `changeResponse(plane.apply(...))`: the change lists the puts it made, nothing says
  that N entries of the head are not in the file and stay. The change is `CHECKED`, publishes, and the grant is live.
  The author believes it is revoked.
- **Recommend.** The answer to `:apply` carries `kept` (ids of policies, bindings, roles, rate limits the head has and
  the document omits, for MERGE) and, for REPLACE, `removes` as a list; and a `WARNING` finding (check `OPERATION`,
  path `$.bindings`) "2 bindings of the document are not in the file and stay; use REPLACE to remove them".
- **Touches.** Response and findings (additive), no format change. Not breaking.
- **Size/when.** S, before RC1.

### UC-3 (S1) A DENY with `ignoreCase` on a non-ASCII literal misses other spellings, silently

- **Who/what.** A policy author denies a name or value case-insensitively.
- **What happens.** `{"effect":"deny","action":"*","condition":{"eq":["subject.attributes.dept","Ärzte"],"ignoreCase":true}}`
  compiles: `ConditionChecker.eqString` folds the literal with `Ascii.lowercase` (A–Z only), so `ärzte` does not match and passes the DENY
  (`Ä` and `ä` are different characters to it). The docs say "ignoring ASCII case"
  (`language.md` operators table). The migration tool treats the same shape as unsafe and **refuses** it
  (`PartTranslation.caseFoldable`: "ignoreCase in the DENY direction … 8.0 only ASCII, so the DENY would stop matching
  some spellings; list them with in instead"), so a document converted from 7.x is held to a rule a hand-written one
  is not. Same for `prefix`. The ALLOW direction narrows (safe).
- **Recommend.** Compile error (core: `ConditionChecker`) for `ignoreCase` on a literal that has a case mapping beyond
  ASCII, in a DENY, a policy condition guarding a DENY and a rate limit rule's condition; message as the migration
  tool's. For ALLOW a warning finding is enough.
- **Touches.** Validation (stricter compile). Only a document that already has the shape stops compiling; do it
  before GA. **Needs the maintainer** to accept a stricter compile.
- **Size/when.** S, before RC1.

### UC-4 (S2) Before publishing, the author sees counts only, after the window closes; the samples are collected and never shown

- **Who/what.** Anyone about to publish a change who wants to know what it newly permits or denies.
- **What happens.**
  - A shadow window records, per instance, up to 20 samples per outcome with tenant, principal, app, space, action,
    resource, `enforcedBy`, `candidateBy` (`core/spi/.../shadow/ShadowReport.kt`: `ShadowSample`, `MAX_SAMPLES = 20`;
    `core/runtime/.../ShadowRecorder.kt`) and writes them to Redis. The control plane never reads them:
    `ShadowWindows.look` sums `counts` only, `ShadowSummary` is `counts, closedAt, insufficient, blocked`, and
    `ShadowStateCodec` writes nothing else. No route serves them (`ControlPlane` has no such method).
  - While the window is open `GET /changes/{id}` shows `window`, `startedAt` and `reporters`; `look` returns no
    summary until the window may close (`else -> null`), so a `WIDENED` seen on minute 2 is invisible until minute
    15 at the earliest.
  - At publish the publisher must send `ack` with the exact numbers (`{"WIDENED": 3}`) of requests they cannot look at.
  - `analyze` (access review) takes `tenant, app, space, action, resource` only (`ControlRequestJson.query`): it cannot
    be asked about a candidate, so "who would this change newly reach" has no answer except sampled traffic.
  - The only per-request tool is `simulate` with a candidate, which needs the author to guess the request.
- **Recommend.** `GET /changes/{id}/shadow`: the live summed counts and the samples per outcome, redacted by what the
  caller may read (tenant admins see their tenant's). `analyze` accepts `"candidate": "<change id>"` and answers the
  difference (`allows`/`denies` added and removed). Publish's `428` points to both.
- **Touches.** Control API, additive; privacy review for samples (they carry principals and resources, as audit events
  do). Not breaking.
- **Size/when.** M, before RC1 for the samples (the acknowledgement is the safety gate), `analyze` on a candidate 8.0.x.

### UC-5 (S2) `apply REPLACE` is last-writer-wins, and the document header it requires is ignored

- **Who/what.** A git-ops job or admin applies a whole document.
- **What happens.** `Proposals.apply` proposes `Operations.diff(key, head?.document, desired, mode)` on the head *at
  propose time*, with `base = head.revision`. The file's `revision` is required (`PolicyDocumentCodec.decode`:
  `[revision] is required`, positive) and then ignored; the request body has no `base` (`POST /changes` has one).
  A file built from revision 12 applied with `REPLACE` after a colleague published revision 13 removes the
  colleague's entries, each remove guarded by the digest of what the head holds *now*, so it succeeds. The
  tenant's own DENYs can be removed by a tenant admin without approval; the window is the only net, and `skip-shadow` skips it.
  Also: authors must invent `revision` and `publishedAt` for a document whose control plane writes its own
  (`language.md` "Header"); the test kit adds the header when it is missing (`DocumentSnapshotCompiler.withHeader`),
  so a file can be green in CI and `400` at `:apply`.
- **Recommend.** Optional `base` in the `:apply` body (409 on mismatch as `POST /changes` does), and the docs say that a
  git-ops job sends the revision it read. Make the header optional in `:apply` (the control plane writes it anyway).
- **Touches.** Control API (additive), request codec. Not breaking. **Needs the maintainer** only if the header is made optional.
- **Size/when.** S, before RC1.

### UC-6 (S2) A platform change can cut the control API off, nothing checks it, and the way back is "empty Redis"

- **Who/what.** A platform admin edits `platform.json` (the trust root, so unanalyzed).
- **What happens.** Every control request is an ordinary request first and must be permitted by the documents
  (`testing.md` "The control plane"). Remove or narrow the policy that permits the control paths (the quick start
  platform document has one, `control-api`), or `{"schema":2,"revision":1,"publishedAt":"…"}` as a whole document:
  `ChangeChecks.check` runs V1, V2, COMPILE, REFERENCES; none asks whether the author can still reach the API. It
  publishes. Every later call is 401/403 before the control plane is reached; rollback is an HTTP call. The quick
  start says it itself: "without that, the only way back is to empty Redis" (`wiki/guide/quick-start.md`, "Change a
  document later"), and `cosec-migrate publish` "never overwrites". No page in `troubleshooting.md` covers it. A shadow
  window sees only the sampled control requests, usually fewer than `min-samples`, and the trust root can skip it.
- **Recommend.** A `COMPILE`-like finding (check `V4`, ERROR): for a platform candidate, simulate the author's own
  credential (tenant, principal, roles) on `<method> <base-path>/documents/platform` against the candidate and refuse
  when it is not permitted ("this change would leave you unable to reach the control API at /cosec/control/v1"). Add a
  recovery path: `cosec-migrate publish --file … --document platform --replace-with-confirmation` or an operator
  runbook in `troubleshooting.md`.
- **Touches.** Validation; maybe a migrate flag. Not breaking.
- **Size/when.** M, before RC1.

### UC-7 (S2) The test kit can pass a scenario against documents that never loaded

- **Who/what.** A service team writes negative scenarios ("nobody deletes an order") in CI.
- **What happens.** `PolicyTestKit.load` reads `platform.json`, `apps/*.json`, `tenants/*.json` only; a missing
  `platform.json` is an empty platform document, a directory named `tenant/` is not read, a file `Acme.json` is the
  document of tenant `Acme`, not of `acme`, and a scenario naming a tenant with no document decides on an empty one (`PolicyTestKit.documents`,
  `documents(directory, subdirectory, ids)`: `if (!path.exists()) return emptyMap()`). Scenario fields default to the
  docs' example names: tenant `acme`, principal `alice`, action `order:read`, resource `order/1`
  (`testing.md` scenario table). `{"expect":{"decision":"Deny"}}` with the documents misplaced, or with the defaults
  left in an app that has no `order` service, is green. Pinning `by` avoids it, but nothing asks for it.
- **Recommend.** Fail (or warn, opt out with `"documents": "any"`) when a scenario's tenant or app has no document in
  the directory; require `action` and `resource` (no example defaults); `kit.load` fails when the directory has no
  `platform.json` and says how to opt in to "empty platform" for a test of its own.
- **Touches.** The scenario format and the kit's strictness; breaking only for tests that rely on defaults (`PolicyTestKit` is experimental).
- **Size/when.** S–M, before RC1 (the kit is `@ExperimentalCoSecApi`, so the change is free now).

### UC-8 (S2) A failing scenario says what was decided, not why; nothing reports statements no scenario exercised

- **What happens.** `EngineExpectations.check` fails with `name:\n  expected {…}\n  actual   {"decision":"Deny","by":null}`.
  The kit evaluates at every `TraceLevel` (`EngineScenarios`, ALG-8) so the trace exists, but the message carries none
  of it: not the candidates and what ruled each out, not `expired` bindings, not `unresolved`. The author must start a
  control plane to `explain`. And `by` in a scenario has another shape than `by` in `simulate`: scenario
  `via: {"binding": "g:no-delete", "document": "tenant"}` (`EngineExpectations.via`), control API
  `via: "no-delete", document: "platform"` (`ControlResponseJson.statement`): a decision copied from `explain` into
  `expect` does not match. No coverage: grep finds no statement-level coverage in `test/testkit`, so a DENY with a
  typo'd action (`order:delet`) survives a kit suite unless a scenario pins it.
- **Recommend.** Append to a failed assertion the trace as a table (statement, effect, outcome, binding), plus expired
  and unresolved; make `explain`'s `by` the kit's shape (or accept both); `PolicyTestKit.coverage()` listing statements
  and bindings that no scenario matched, usable as a build gate.
- **Touches.** Messages, the kit API (additive; Kotlin shape is group A's), `explain` shape (breaking for the one
  documented response; do it before GA).
- **Size/when.** M, before RC1 for the trace in the message; coverage 8.0.x.

### UC-9 (S2) `explain`/`simulate` drop what `EvaluationTrace` knows, and say nothing about why a binding was not a candidate

- **What happens.** `ControlResponseJson.trace` writes `redacted` and `candidates` only. `EvaluationTrace` also holds
  `expired` (bindings that would apply "had they not expired") and `unresolved` (causes), and `Cause.MissingPolicies`,
  `AmbiguousPolicy`, `DocumentInvalid` carry the policy ids, the document and the revision. `Redaction.decision`
  answers `cause = decision.cause::class.simpleName` ("MissingPolicies", "never its message", `checks.md`). Authors
  get `Deny` with an empty `candidates` for a grant that expired yesterday, and `Indeterminate`, `MissingPolicies`
  without naming the policy (the kit does name them: `EngineExpectations.cause`). A binding that was not a candidate
  because of the subject, the app or the space has no trace field at all.
- **Recommend.** Emit `expired` and `unresolved` (policy ids are the caller's own references or already visible to
  `cosec:explain` on that tenant; redact as `candidates`); add a `notCandidates` summary: "binding X skipped: app
  `order` ≠ `billing`", "role `clerk` held in space `team-a`, request in `""`".
- **Touches.** Control API response (additive); engine trace gets one more list (experimental type).
- **Size/when.** M, 8.0.x.

### UC-10 (S2) Statements that can never match compile and publish without a word

- **What happens.** `StatementIndex` states "A statement whose keys admit no value is in no partition: it can never
  match", computed by `IndexKeys.intersect` for `scope.tenant`, `scope.app`, `scope.space`, `resource.type` and
  `subject.tenant`. `{"and":[{"eq":["scope.space","a"]},{"eq":["scope.space","b"]}]}` is accepted and, in a DENY,
  denies nothing; the same for `"resource":"order/**"` with `{"eq":["resource.type","orders"]}`. `PolicyCompiler.statement`
  uses the keys and never reports an empty set. A mistyped action (`order:delet`) is also never caught, because nothing
  knows the real actions (catalogs "declare permission ids, not services, verbs or types"). A clean change is
  `CHECKED`, which reads as assurance.
- **Recommend.** WARNING finding `COMPILE` (path of the statement): "this statement can never match: its condition
  requires scope.space to be `a` and `b`" and "its resource type `order` and the condition's `resource.type` `orders`
  cannot both hold". Where a catalog or route map is known (app document, start-up), warn on an action whose service
  or verb appears nowhere else.
- **Touches.** Validation (warnings, not errors). Not breaking.
- **Size/when.** M, 8.0.x.

### UC-11 (S2) Validating documents in CI needs a Kotlin program, and the three checkers check different things

- **What happens.** `testing.md` "Policies in CI" is Kotlin (`PolicyDocumentCodec.decode`, `compiler.validate`,
  `checkReferences`). A policy repo of a non-JVM team has no command: `cosec-migrate` has `convert`, `publish`,
  `bootstrap`, `repair-references`, `verify-chain`, `partition plan`, `api-keys`; `publish` has no `--dry-run`
  (`Options`/`MigrateMain`: refused on purpose). `DocumentCompileException.errors` is `@InternalCoSecApi`, so a
  CI program can only print `message`. The three tools differ: the kit compiles but does not run `checkReferences` and
  adds a missing header; `cosec-migrate publish --file` compiles and checks references but needs Redis and the
  published platform document; the control plane additionally runs V2, grants and escalations. A set green in the kit
  can be refused at publish with `REFERENCES`.
- **Recommend.** `cosec-migrate check --platform platform.json [--app a.json …] [--tenant acme=t.json …]`: strict
  decode, compile against the platform's limits and attributes, `checkReferences`, no Redis, exit code 1 with every
  error and its path, plus the kit's header leniency flag. The kit's `load` runs `checkReferences` too.
- **Touches.** A new command (the migrate tool's contract is its command line); kit strictness.
- **Size/when.** M, 8.0.x.

### UC-12 (S3) Where an error is: first error only, operation errors without index, and `$` for compile errors raised by `require`

- **What happens.** (1) `DocumentNode.fail` throws at the first decode error: fix, retry, next. COMPILE reports all.
  (2) `POST /changes`: `ChangeRoutes` runs `decoded("operations") { ChangeRequestCodec.operation(key, op) }` for each
  operation without its index, and `OperationCodec.decode` reads the entry with root `$`:
  `[operations]: $.statements[0]: unknown field effct; expected …`: which operation, and `$` is the policy, not the
  body. `:apply` is better: `[document]: $.policies[0].statements[0]: …`. (3) Errors raised by the engine's
  `require` have path `$`: `Duplicate binding id b in the tenant document`, `Binding b of the platform document names
  policy p, which it does not define`, `Role binding … in the platform document`, `Binding … of the tenant document
  acme binds a subject of tenant globex`, `Duplicate rate limit rule …` (`CompiledDocument.init`, `boundEntry`,
  `requireReachable`; `PolicyDocumentCompiler.compile` wraps them as `CompileError("$", message)`). Only the first of them is reported.
- **Recommend.** Prefix operation errors with `operations[i].policy`; make `CompiledDocument`'s checks return
  `CompileError`s with `$.bindings[i]` paths and collect them (the document knows the index).
- **Touches.** Messages; not breaking. **Size/when.** M, 8.0.x.

### UC-13 (S3) Common mistakes whose message does not say what to write

All verified in code:
- `"resource": "*"` (the natural guess; `*` is right for `action`): `Invalid pattern [*] at 0: '*' is not allowed in a type; ** is only allowed last` (`TypedPatternParser.requireLiteral`). `"**"` gives the same. Say: "to match every resource leave `resource` out, or write `type/**`".
- Unknown operator: `…condition: unknown operator [equals]` (`ConditionJson.read`); list the eleven.
- Unknown attribute: `` unknown attribute `subject.idd` `` (`ConditionChecker.unresolved`): no list, no nearest name; same for a header that is not a token.
- Swapped operands: `{"in":["scope.roles","auditor"]}` reads as "set contains literal" with `auditor` as the *attribute*: `` unknown attribute `auditor` `` at `.in[1]`; write `["auditor","scope.roles"]`.
- A number literal: `a literal is a JSON string or boolean`; add "write "3", a string".
- `{"eq":["subject.authenticated","true"]}`: `a String does not compare with subject.authenticated (Bool)`; add "write true without quotes".
- Document key `tenant:a/b`: `That names no document: platform, app:<id> or tenant:<id>.` (`ControlRequestJson.documentKey` hides the rule that failed).
- `unknown field …; expected …` lists `onMissingConfirmed`, which the skill says never to write by hand.
- **Recommend.** Fix the messages; add "did you mean" for attribute paths and operators (edit distance over the catalog).
- **Touches.** Messages. **Size/when.** S, 8.0.x.

### UC-14 (S3) `diff` shows ids only, and `sections` is a boolean

- **What happens.** `DocumentDiff`: `EntryChanges(added, removed, changed: List<String>)` (ids) and
  `sections: Boolean` ("whether anything else differs": the catalog, limits, attributes, boundary or rate limits). A
  reviewer of revision 7 → 8 sees `changed: ["order-reader"]` and must fetch both revisions to see the statement that
  went from DENY to ALLOW; a changed rate limit is `sections: true`. (The change object itself holds the operations in
  full: the gap is the history view, and approvers who only open the revisions.)
- **Recommend.** For each changed entry a `before`/`after` digest plus a field-level summary ("statement 1 effect
  deny → allow"); `sections` as a list of names.
- **Touches.** Control API (additive). **Size/when.** M, 8.0.x.

### UC-15 (S3) Two forms for one thing, and the pages do not say which to prefer

- Permissions: policy `perm:<app>/<id>` + role binding, or a catalog entry with `action` + `roles`. `role-permissions.md`
  opens with the long form and offers the short one as "an entry can define its permission itself"; the skill and
  `scenarios/app.md` prefer the short one. Tenant document: a custom role, or a binding of `perm:` to `{role}`.
- `space` means different things in neighbouring objects: binding `""` = default only, `"*"` = any; tenant role: absent
  = any, `""` = default only; catalog group: `spaced` default `false` (permission applies in the default space only,
  by a generated `scope.space == ""`). A system role held as `clerk@team-a` for a non-`spaced` permission matches
  nothing, safely; only a tenant role with a space gets the warning (`RoleChecks.roleWarnings`).
- `resource`: a string, an object, or an array of either; `action`: string or array.
- **Recommend.** A "Choosing a form" paragraph at the top of `role-permissions.md`: short form unless the permission needs a condition, a DENY or several statements; tell `spaced` at the point of the first example. Extend the `RoleChecks` warning to system roles whose catalog has a non-`spaced` group in an app whose documents elsewhere use `@space` (a heuristic, optional).
- **Touches.** Docs. **Size/when.** S, before RC1.

### UC-16 (S3) The names in a decision are not the names the author wrote

- `by` shows `policy: perm:order/order.ship`, `statement: 0` (an index, `name` = the permission id), `via.binding:
  role:clerk@order:order.ship` for what the author wrote as role `clerk` with permission `order.ship`
  (`RoleExpansion.derivePolicies`/`binding`). A statement's index changes when statements are reordered; a scenario
  pinning `by.statement: 0` breaks silently on a harmless edit (it fails visibly, but on the wrong thing).
- The format says `allow`/`deny`, the decision `Permit`/`Deny`, the trace `ALLOW`/`DENY`, the scenario `Permit`.
- `decision.cause` is a class name (`MissingPolicies`); the metric tag is `missing_policies`; the troubleshooting table needs both.
- **Recommend.** Show derived items with their source: `via.binding: "role:clerk@order:order.ship"` plus
  `source: "$.roles[1].permissions[0]"`; let `by` match by `name` in scenarios (it can: names are set), and say in
  `testing.md` that `name` is the stable thing to pin. Add a "Names" table to `security-model.md` mapping the five spellings.
- **Touches.** Response additive, docs. **Size/when.** S, 8.0.x.

### UC-17 (S3) `convert` prints bare rule ids

`ConvertCommand.summarize` prints `semantic change C1b: 14 place(s)`: no meaning, no path to `report.json`, no pointer
to "What 8.0 decides differently". The reviewer who must read each entry (the upgrading page says "Read each entry") has
to find the page. **Recommend.** One sentence per id from `SemanticScan` (its `shape` strings exist), the first three
locations per id, and "see report.json and upgrading#semantic-changes-in-the-report". Messages only. S, 8.0.x.

### UC-18 (S3) What a refusal says when changes race

`[tenant:acme] is staged by change X` (409, `Staging.takeSlot`) gives no way out: wait for `expires-after`, abandon (who
may), rebase. `[key] is at revision N, not M` (`Proposals`) does not say "rebase your change". `change X is DRAFT, not one
of [CHECKED]` (`PublishRules.requireState`) does not say what moves it on. **Recommend.** Append the next step and who
may take it ("X is held by <author>, since <time>; they or a holder of cosec:publish can abandon it"). Messages. S, 8.0.x.

### UC-19 (S3) From a 403 in production to an `explain`/scenario takes hand-assembly

The decision event holds the revisions (`at`) but the author must rebuild `request` (tenant, principal, roles, space,
app, action, resource, IP, time) by hand in a JSON shape that is not the kit's (`credential`/`request` in a scenario,
flat `request` in simulate). **Recommend.** `POST /explain {"requestId": "…"}` (when the event sink keeps it) and a "copy as scenario" in docs/tools: one shared request shape. Tool; M; 8.1.

### UC-20 (S3) Rate limits: per-second maths and an invisible default bucket

`permitsPerSecond × windowSeconds` is the quota (`login-per-ip`: 0.1 × 60 = 6 a minute); "5 a minute" is 0.08333…. A
rule without `key` shares one bucket across everyone (`rate-limiting.md` "Keys" table) and is accepted without comment:
one client can exhaust the quota for all, and a keyless rule meant to be per user reads exactly like a deliberate global
cap. `subject.id` for anonymous callers is one bucket as well (documented).
**Recommend.** A WARNING finding for a rule without `key` ("one bucket for every caller; add `key` for one per caller"),
and a worked "5 per minute" in the docs. A quota written as a count and a window would be a format change: 8.1, **needs the maintainer**. S.

### UC-21 (S4) The app-id message omits the character it most often refuses

`EntryChecks.appIds` says an app id cannot be "blank, *, ':', a control character or an unpaired surrogate, or over 128
characters", but `ScopeResolver.isValidAppId` also refuses `/` (`SnapshotKey.App.isValidAppId` doc comment), the one an
operator is most likely to have. Add `/` to the message. Messages; S; 8.0.x.

### UC-22 (S4) Generated catalogs are not `spaced` and their ids are the operations'

`cosecServiceGenerator/app` writes one short-form permission per action with id = the action's verb (the part after
`:`, which is the `operationId` unless `x-cosec-action` is set), a group per first tag, no roles, and groups that are not
`spaced` (UC-15). Say in `role-permissions.md` that `operationId`/`x-cosec-action` should be set before generating, since
they become permission ids that roles then name. Docs; S; 8.0.x.

---

## 4. Appendix A: the twenty mistakes (and the ones I added)

"Where": decode (V1 400, strict reader), compile (COMPILE 422), validation (control plane V2/REFERENCES/OPERATION),
shadow, never. Messages are quoted as the code produces them; through the control plane the prefix is `[document]: `
(`:apply`) or `[operations]: ` (`POST /changes`, UC-12), or a finding `{check, path, message, status}`.

| # | Mistake | Caught where | Message as produced | Verdict |
|---|---|---|---|---|
| 1 | Typo in a field (`"effct"`) | decode | `$.policies[0].statements[0]: unknown field effct; expected name, effect, action, resource, condition, onMissing, onMissingConfirmed` | clear (lists valid names; no nearest; offers `onMissingConfirmed`) |
| 2 | Wrong type (`"revision":"3"`, `"effect":true`) | decode | `$.revision: expected an integer, found a string`; `$….effect: expected a string, found a boolean` | clear |
| 3 | Unknown operator (`{"equals":…}`) | decode | `$.policies[0].statements[0].condition: unknown operator [equals]` | unclear (no list; UC-13) |
| 3b | Two operators in one object | decode | `…condition: a condition has exactly one operator, found eq, in` | clear |
| 4 | Attribute that does not exist (`subject.idd`) | compile | `` $.policies[0].statements[0].condition.eq[0]: unknown attribute `subject.idd` `` | unclear (no list/suggestion) |
| 5 | Credential header | compile | `` …: `http.header.authorization` is a credential header, which no attribute reference may read `` | clear |
| 6a | Binding to a missing policy, app/tenant doc | validation (REFERENCES 422), `checkReferences` | `$.bindings[2].policy: [p] is defined neither here nor where this document may refer` / `…nor in the platform` | clear |
| 6b | Same, platform document | compile (`boundEntry`) | `$: Binding b of the platform document names policy p, which it does not define` | clear message, path `$` (UC-12) |
| 6c | Same, test kit | never until a scenario reaches it | `Indeterminate`, `MissingPolicies` | silent in the kit |
| 7a | Tenant role in a reserved space | compile | `$.roles[0].space: [(x)] is reserved: space ids beginning with [(] are CoSec's` | clear |
| 7b | Tenant role with a space and a non-`spaced` permission | validation, WARNING | `$.roles[0].permissions[1]: [p] applies only in the default space, so it never applies in [s]` | clear (tenant roles only; system role or binding: silent, UC-15) |
| 7c | Token role in a space the client did not declare / wrong space | data plane | 403 `scope-rejected`, no detail (by design) | unclear for the author, intended |
| 8 | Pattern that matches nothing (typo'd action, `order/*/items` vs `order/42`) | never | none | silent (UC-10, UC-8) |
| 8b | Malformed pattern | compile | `$…action[0]: Invalid pattern [order] at 0: an action is service:verb, service:* or *` | clear |
| 8c | `resource: "*"` | compile | `Invalid pattern [*] at 0: '*' is not allowed in a type; ** is only allowed last` | unclear (UC-13) |
| 9 | Pattern that matches far more (`action:"*"` without `resource`, `order/**`, `http:path:/api/**`) | never for the trust root; NOT_COVERED/ANONYMOUS escalations for others; shadow `WIDENED` | none for the author; `escalations[].reason` for others | silent for platform authors; clear for tenant authors |
| 9b | DENY on a bare type (`order`) | compile | `a DENY on the bare type [order] covers only the order with an empty id; write [order/**] for every order, and add an eq on resource.id with the empty string for the empty id alone` | clear (the model for the others) |
| 10 | ALLOW shadowed by a DENY | never (`explain`/`analyze` show it) | none | silent (by design) |
| 11 | Statement that can never match (`and` of two `scope.space` literals; `resource.type` against the pattern) | never | none | silent (UC-10) |
| 12a | Duplicate binding / policy / rate limit id | compile | `$: Duplicate binding id b in the tenant document` (first only) | clear message, path `$` |
| 12b | Duplicate role id | compile | `$.roles[1].id: role [r] is defined twice in this document` | clear |
| 13 | Wrong `schema` | decode | `$.schema: schema 3 is not this version's document format, 2` (stored: `[tenant:acme] has schema 3, not this version's document format 2.`) | clear |
| 14 | Document over the size limit | validation V2 400 | `$: the document is 8400000 bytes, more than 8388608` | clear |
| 15a | Rate limit without a key | never | none; one shared bucket | silent (UC-20) |
| 15b | Rate limit without `permitsPerSecond`, window 0 | decode | `$.rateLimits[0]: [permitsPerSecond] is required`; `…: windowSeconds must be between 1 and 86400 (a day), not 0.` | clear |
| 16a | Route map entry mapping nothing | never | none | silent |
| 16b | Empty route map | startup | `<location> $.routes: has no routes; a host without a route map sets no location for one` | clear |
| 16c | Two routes for one request | startup | `<loc>: POST /a/{y} and <loc>: POST /a/{x} may match the same request; a request has one action and one resource, so routes must not overlap` | clear |
| 16d | Resource variable not in the path | startup | `resource [order/{id}] names {id}, which path [/orders/{orderId}] does not capture` | clear |
| 17a | `/` in an app id of a binding | validation V2 400 | `$.bindings[0].app: binding [b] is in app [a/b], which cannot be an app id (blank, *, ':', a control character or an unpaired surrogate, or over 128 characters): no request is ever in it. Remove the binding.` (the list leaves out `/`, a defect) | clear but incomplete |
| 17b | `/` in a document key | API | `That names no document: platform, app:<id> or tenant:<id>.` | unclear (UC-13) |
| 18a | Empty document `{}` / zero bytes | decode | `$: [schema] is required` / `$: the document is empty` | clear |
| 18b | Header-only platform document | never | none; publishes; control API unreachable | silent (UC-6) |
| 19 | `in`/`eq` operands swapped | compile | `` unknown attribute `auditor` `` | unclear (UC-13) |
| 20 | `ignoreCase` on a non-ASCII literal in a DENY | never | none | silent (UC-3) |
| 21 | File omits an entry, `apply MERGE` | never | none | silent (UC-2) |
| 22 | `http:HEAD`, `http:get` | compile | `http:HEAD matches no request: a HEAD request is decided as GET; write http:GET (DOC-8, PIP-18)` | clear (the model) |
| 23 | `onMissing` on a statement reading no `ext.*` | compile | `…onMissing: onMissing applies to external attributes only, and the condition reads none` | clear |
| 24 | `resource.var.x` not captured by every pattern | compile | `…resource[1]: the condition reads resource.var.x, which [order/*] does not capture` | clear |
| 25 | `cosec:platform-admin` bound in a tenant | compile | `$.bindings[0].policy: [cosec:platform-admin] is bound only by the platform and the platform tenant` | clear |

## 5. Appendix B: the first policy set, and where I guessed

Small shop app (read from `wiki/scenarios/app.md`, `role-permissions.md`, `language.md`, `schema/`; checked by reading the
codecs, not compiled): `app:shop` with a `spaced` group, short-form permissions `order.read` (`shop:order.read`) and
`order.ship` (`shop:order.ship`, `resource: "order/{orderId}"`), system roles `viewer` (read) and `clerk` (read, ship);
a rate limit `{"id":"ship-per-user","action":"shop:order.ship","key":"subject.id","permitsPerSecond":0.5,"windowSeconds":60}`;
the platform document (login/health, `control-api`, a DENY cross-tenant guard) as in the app page; the object-level rule
as `order-of/{owner}/*` from `checks.md`. Where I had to guess:
1. Short or long form for permissions (UC-15); and that an entry needing a condition cannot be short.
2. `spaced` on a group: I left it out and a space-less app worked; I would not have known to add it for `team-a`.
3. The header (`revision`, `publishedAt`) I do not control but must write (UC-5).
4. The rate limit's maths (UC-20) and where it lives (platform, app or tenant): the app document's rules apply only
   when the request read the app document (`rate-limiting.md`); I found that out from the page, not from the schema.
5. The object-level rule needs two statements (an ALLOW on `order-of/…`, the DENY on the same) for a check in code;
   with only the DENY every check is refused and the answer is the implicit deny (no hint).
6. Names: the decision's `via.binding` for my role is `role:clerk@shop:order.ship` (UC-16).
7. "Tenant-scoped data": found at `guide/model.md` ("Row-level filtering: your queries"), not on the policy pages.

## 6. What is good, and must not be changed

- Strict reading with JSON paths and `unknown field …; expected …`; COMPILE reporting every error with its path, and
  the finding structure `{check, path, message, status, severity}` (V1/V2/COMPILE/REFERENCES/OPERATION/IF_MATCH).
- Messages that teach: the bare-type DENY, DOC-8 (`http:HEAD`), credential headers, ids with `/` ("Remove the binding").
- The kit's `expect` must name a `decision`; only named fields are compared; unknown fields are errors; scenarios are
  files that run in CI, and the wiki's samples are those files (`DocumentationExamplesTest`).
- Deny-first, absent-is-equal-to-nothing and `not` documented with the trap named; the `onMissing` model.
- The grant analysis (`NOT_COVERED`, `DENY_OVERLAP`, `ANONYMOUS`…), four-eyes, the exact-numbers `ack`, the staging
  slot and its preemption rules, `ifMatch` digests, rebase dropping approvals.
- `explain` at recorded revisions (`at`) and `simulate` with a candidate and `differs`.
- Migration: rejects rather than guesses, reports every rejection in one run, `report.json` with SHA-256, no
  `--dry-run` that does something else, `provenance.json`, `partition plan`; the semantic-change ids.
- Role expansion written once (`RoleExpansion.effective`) and read by everything.

## 7. For another group

Groups as renamed by the coordinator: A code surface, B starter/configuration/deployment, D model (concepts and their
names across code, documents, tokens, decision output and docs), E documentation. UC-1 to UC-22 stay mine (syntax,
messages, tools, short versus long form, UC-15, UC-16 as far as it is about what the tools print).

- **A:** `DocumentCompileException.errors` is `@InternalCoSecApi`, so a CI helper cannot read errors with paths, only the
  message (UC-11). `PolicyTestKit`: `evaluate` takes a string, `check` throws `AssertionError`: a trace-bearing return is
  the API shape for UC-8. Control-plane Kotlin types for in-process writers are on the 8.1 list.
- **B:** startup should WARN when a loaded route map overlaps an `http:` DENY or rate limit rule (the host side of
  UC-1); no switch exists for "refuse a platform change that cuts the control API" (UC-6).
- **D (model):** five spellings of one outcome: format `allow`/`deny`, decision `Permit`/`Deny`, trace `ALLOW`/`DENY`,
  metric tag `missing_policies` against cause `MissingPolicies` (UC-16, the tool side is mine). `space` means different
  things in neighbouring objects: binding `""` default only and `*` any; tenant role absent = any, `""` = default only;
  catalog group `spaced` default false. A statement is identified by `(policy, index)` while `name` is optional and not
  unique. `scope.roles` / `subject.roles` / `subject.scopedRoles` are confusable (documented, but three names). Whether
  an HTTP request is `http:<METHOD>` or a route's `service:verb` is a model fact that decides which statements apply
  (UC-1); a concept page should state it as a rule of the model.
- **E (docs):** `testing.md` is 700 lines mixing the control-plane workflow, the kit and `PipelineFixture`;
  `role-permissions.md` needs its "which form" paragraph (UC-15, mine to specify); the explain page shows `via` as a
  string while the kit docs show it as an object (UC-8); `language.md` operators should state "a literal is a JSON string
  or boolean" next to the numbers rule.

## 8. Triage table (one line per finding)

| id | severity | defect (≤15 words) | touches | breaking | size | when | needs maintainer |
|---|---|---|---|---|---|---|---|
| UC-1 | S1 | Mapped routes escape `http:` DENYs and rate limits; no tool or startup check shows it | tool, scenario format, startup message; (b) document format | no | M | before RC1 (a); 8.1 (b) | (a) no; (b) yes, new attributes |
| UC-2 | S1 | `apply MERGE` keeps entries the file omits and the answer is silent | control API response, findings | no | S | before RC1 | no |
| UC-3 | S1 | DENY `ignoreCase` on non-ASCII literal compiles and misses spellings | stricter compile | stricter compile | S | before RC1 | yes, stricter compile |
| UC-4 | S2 | Shadow samples never exposed, counts only at close, `analyze` ignores candidates | control API response and request | no | M | before RC1 (samples); 8.0.x | yes, privacy of samples |
| UC-5 | S2 | `apply REPLACE` is last-writer-wins; header required but ignored | control API request | no | S | before RC1 | yes, if header optional |
| UC-6 | S2 | Platform change can cut control API access; recovery is emptying Redis | validation, docs/runbook, migrate flag | no | M | before RC1 | no |
| UC-7 | S2 | Kit scenarios pass vacuously on missing documents or example defaults | scenario format, kit strictness | API (experimental kit) | S–M | before RC1 | no |
| UC-8 | S2 | Failed scenario shows no trace; no coverage; `by` shape differs from explain | message, tool, control API response | API (`explain` shape) | M | before RC1; coverage 8.0.x | yes, changes explain shape |
| UC-9 | S2 | explain/simulate drop expired bindings, unresolved causes and policy ids | control API response | no | M | 8.0.x | no |
| UC-10 | S2 | Statements that can never match compile and publish without warning | validation (warning) | no | M | 8.0.x | no |
| UC-11 | S2 | No validator outside Kotlin; kit, migrate and control plane check differently | tool (new command), kit strictness | no | M | 8.0.x | no |
| UC-12 | S3 | Decode reports first error; operation errors lack index; `$` paths for require errors | message | no | M | 8.0.x | no |
| UC-13 | S3 | Several common mistakes get messages that don't say what to write | message | no | S | 8.0.x | no |
| UC-14 | S3 | `diff` lists ids only; `sections` is a boolean | control API response | no | M | 8.0.x | no |
| UC-15 | S3 | Long and short permission forms, unstated preference; confusing `space`/`spaced` defaults | docs | no | S | before RC1 | no |
| UC-16 | S3 | Decision names (derived ids, indexes, spellings) differ from what authors wrote | control API response, docs | no | S | 8.0.x | no |
| UC-17 | S3 | `convert` prints bare semantic-change ids without meaning or pointer | message | no | S | 8.0.x | no |
| UC-18 | S3 | Race refusals (409) name the holder but not the next step | message | no | S | 8.0.x | no |
| UC-19 | S3 | Reproducing a prod deny needs hand-built explain request in another shape | tool, control API request | no | M | 8.1 | no |
| UC-20 | S3 | Rate-limit per-second maths; keyless rule silently shares one bucket | validation (warning), docs; format later | no | S | 8.0.x (warning); 8.1 | yes, for format part |
| UC-21 | S4 | App-id error message omits `/`, the most likely offender | message | no | S | 8.0.x | no |
| UC-22 | S4 | Generated catalog ids come from operations; not `spaced`; no guidance | docs | no | S | 8.0.x | no |
