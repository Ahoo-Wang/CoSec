# CoSec 8.0 RC deep review, round 2, area M: `cosec-migrate`

The second of the two deep reviews RC1 requires of the integrated system (`docs/roadmap.md` §4, RFC 0008 §3.9
prerequisite 6), for the one area round 1 did not read: `migration/migrate`, the command-line tool that turns 7.x data
into 8.0 policy documents, API keys and the control plane's store, and that is also the way back from a lock-out
(RFC 0007 §5.9). What it writes becomes the documents, bindings and credentials every 8.0 host decides with, so a
translation that turns a 7.x refusal into an 8.0 permit, widens a role, loses a tenant boundary or skips an entry in
silence is as serious here as in the engine.

| | |
|---|---|
| Commit reviewed | `origin/main` at `a12be660` (#1957) |
| Date | 2026-11-24 |
| Scope | `migration/migrate/src/main`: the package `me.ahoo.cosec.migrate` (60 files, 7,181 lines) and its copy of the 7.x model, `me.ahoo.cosec.migrate.v7` (91 files, 5,535 lines). 151 Kotlin files, 12,716 lines |
| Reviewer | one read-only session, not an author of the code reviewed, reading in order with no sub-reviewer. No code, test, rule or RFC was changed |
| Method | every file of `me.ahoo.cosec.migrate` read end to end, with two views: an attacker's (a malicious or malformed 7.x store or export, an operator led to run the tool on it) and an operator's (a run that stops, runs again, half succeeds, or whose output goes to production as it is). Each translation was held to the 7.x decoder it mirrors in `v7` (the decoders and matchers listed below were read end to end) and to the 8.0 side it writes for: the engine's attributes (`EvaluatorAttributes`), the `time` checker, the control store's `Revisions` and `ReferrerIndex`, RFC 0004 §8, RFC 0005 §7, RFC 0007 §5.8–§5.9, RFC 0009 §4.4, the `MIG`, `IDN`, `CTL` and `DOC` rules, `wiki/operations/upgrading.md` and `storage.md`. Tests were read only to see whether a property has a guard; no finding needed a probe |

**Severity** as in round 1. **S1**: a request permitted that should be denied, cross-tenant access, privilege
escalation or fail open, remotely and without privilege. **S2**: the same with a privileged position or a rare
condition; a denial of service a low-privileged party triggers cheaply; a credential leak. **S3**: weakened defence in
depth, a denial of service that needs privilege, behaviour that misleads an operator, a mismatch between the
specification and the code with no exploitable path. **S4**: hardening and documentation.

## Summary

No S1. The policy conversion holds up under both views. Every translation either says exactly what 7.x said or errs
in the safe direction for its statement:
- a DENY is never narrowed and never dropped (`MIG-1`);
- an ALLOW of every action becomes `http:*`, never `*` (`MIG-2`);
- conditions are read by direction, including through negations, with 7.x's empty-string reading of absent values put
  back where a predicate held on it;
- what cannot be said exactly is rejected by its location, and a run with any rejection writes no document.

The writers reach the store only through the control plane's own `Revisions`:
- `publish` never overwrites a document;
- `restore` replaces a head only by compare-and-set against the revision the operator confirmed;
- `bootstrap` refuses while an administrator is bound;
- all three refuse break-glass bindings;
- `repair-references` prunes an index entry only when the head that indexed it has moved past it.

*Status, after the fixes:* DR2-M-1, M-2 and M-9 are fixed by #1970. M-3 to M-6 and M-8 are fixed by #1972. M-7 is
for 8.1, and #1972 documents it. DR2-M-9 is a second S2, found by the coordinator's review of #1970 (below).

The first S2 is in the API key migration. In the default mode a key moves to a principal of its own. Anything that
restricted the key's 7.x principal by name then stops applying to it: a converted DENY on `subject.id`, or a DENY bound
to that principal. Yet the tool, `upgrading.md` and RFC 0009 §4.4 all say no key gains access.

The S3 is in the same command: a legacy key record whose members have the wrong type is read leniently. An attached
DENY policy can be dropped, and a record 7.x could not load can come out as a working key.

The S4s are hardening:
- an integrity check that only catches accidents;
- 7.x ids printed raw on the console;
- entries the Redis reader skips without a word;
- Redis credentials only on the command line;
- writes attributed to no operator;
- an existence check that is complete only with `--memberships`.

Nothing here affects a released line: `cosec-migrate` is new in 8.0.

| Id | Severity | Area | Finding | Status |
|---|---|---|---|---|
| DR2-M-1 | S2 | api-keys | An `own-principal` key escapes every DENY that names its 7.x principal (a converted DENY on `subject.id`, a DENY principal binding), while the tool says no key gains access | fixed by #1970 (`MIG-6`) |
| DR2-M-2 | S3 | api-keys | A legacy key record is read leniently: `policies`, `roles` or `attributes` of the wrong type read as empty, so an attached DENY is dropped, the attributes refusal is skipped, and a record 7.x could not load becomes a working key | fixed by #1970 (`MIG-6`) |
| DR2-M-3 | S4 | publish | The SHA-256s `publish --in` checks are in `report.json` of the same directory: they catch an accidental edit, not a writer of the directory, though the code says `publish` writes only what `convert` produced | words fixed by #1972 |
| DR2-M-4 | S4 | convert | 7.x ids, keys and permission names are printed raw in rejections, warnings and locations: a control character in 7.x data forges lines in the summary an operator reads before publishing | fixed by #1972 |
| DR2-M-5 | S4 | convert | The Redis reader drops, without a count or a warning, a value in CoCache 6's state `M` or `L` and every policy whose id begins with `history:` | fixed by #1972 |
| DR2-M-6 | S4 | all commands | Redis credentials are read only from the command line: the documented Job expands `$(REDIS_URI)` into `args`, so the password is in the process list of the node | fixed by #1972 (`env:` and `file:`) |
| DR2-M-7 | S4 | publish, bootstrap, restore | Every write is attributed to the fixed actor `cosec-migrate`: the way back from a lock-out, which bypasses approval, names no operator | 8.1; documented by #1972 |
| DR2-M-8 | S4 | api-keys | The check that an `own-principal` key's `apikey:<id>` is not an existing principal sees documents and `--memberships` only: without the export, a principal the provider already knows is not seen | said in the output by #1972 |
| DR2-M-9 | S2 | api-keys | An `own-principal` key escapes the application's blacklist, which blocks a principal by `<tenant>:<principal id>` and which the tool cannot see; found in the review of #1970 | fixed by #1970 (`MIG-6`, `--blacklist`) |

## Findings

### DR2-M-1 · S2 · An `own-principal` key escapes every DENY that names its 7.x principal

- **Location:** `ApiKeyMigration.ownPrincipal`, the mode every key takes unless `--memberships` shows it in place or
  `--grant-to-principal` names its principal. The key is rewritten to principal `apikey:<key id>`, uncapped.
  `ApiKeyMigration.lost` and `ConvertedApiKey.lost` list the old principal's principal and break-glass bindings as
  what the key "no longer reaches"; `PRINCIPAL_ID_CHANGES` adds the fixed sentence "conditions on subject.id".
- **Trigger:** either of two shapes, both ordinary:
  - **A converted DENY on the principal's id.** A 7.x policy denies by `context.principal.id`, for example
    `{"in": {"part": "context.principal.id", "value": ["mallory", "bob"]}}` on a DENY. `convert` translates it exactly,
    as `subject.id` in the DENY direction (`PartTranslation`). 7.x evaluated an API key request with the key's
    `principalId` as `context.principal.id` (RFC 0009 §4.4 calls it a label, but conditions read that label).
  - **A DENY bound to the principal.** An administrator adds an 8.0 principal binding of a DENY policy to `mallory`
    before `api-keys` runs.
- **Actual:**
  - After `api-keys`, a key of `mallory` acts as `apikey:k1`. `subject.id` is no longer `mallory`, so the DENY does not
    match, and the principal binding does not apply.
  - The request is decided by the key's roles and the policies bound to `apikey:k1`: permitted where 7.x (or the
    binding) denied.
  - The report calls the binding a loss.
  - The console says "until then the keys are refused or narrower, never wider", and `upgrading.md` says "No key gains
    access".
- **Expected:** RFC 0009 §4.4, "不扩大任何凭据的权限" (no credential's permissions are widened). `MIG-1`: a translation
  never lets through a request 7.x refused. For the `in-place` and `granted` modes the code already keeps the
  principal's DENYs in force (a capped key keeps them, RC1-1).
- **Why S2:** a request 7.x denied is permitted, by default, for the key's holder. It needs a DENY that names a key's
  principal, which is a rare condition, so it is not S1. The report's generic "conditions on subject.id" line is easy
  to read as an audit detail, not as a lifted restriction.
- **Reproduced:** no; by the code and the engine's `subject.id` (the authenticated principal's id).
- **Fix direction:**
  - Before planning, collect every principal a DENY names:
    - in a DENY statement's condition (or a negated one in an ALLOW, read in the DENY direction), a literal compared
      with `subject.id`, by `eq`, `in` or `prefix`;
    - the subject of a principal binding whose policy holds a DENY statement.
  - Refuse `own-principal` for a key whose principal is among them, with advice to keep it in place (capped) or to name
    the new principal in those DENYs first.
  - In the report, list DENY bindings apart from the grants a key loses.
- **Regression guard:** an `ApiKeyMigrationTest` case with a document whose DENY reads `subject.id` of a key's
  principal, asserting a rejection, and a mutant that skips the collection.

### DR2-M-9 · S2 · An `own-principal` key escapes the application's blacklist

Found by the coordinator in the review of #1970, the fix of DR2-M-1: the same hole, by another door.

- **Location:** `ApiKeyMigration` (the `own-principal` mode), and the blacklist the pipeline reads through
  `BlacklistAttributeProvider`. `BlacklistKind.PRINCIPAL` blocks the value `<tenant>:<principal id>`
  (`BlacklistKind.principal`), and the built-in blacklist rule is the first candidate of every decision.
- **Trigger:** the application's `BlacklistSource` blocks `acme:mallory`, and `mallory` has a 7.x API key.
- **Actual:** in 7.x the key's request carried its `principalId`, so the entry blocked the key. After `api-keys`, the
  key acts as `apikey:k1`. The entry still says `acme:mallory`, so the key is no longer blocked. The entries live in the
  application's source, not in a document, so the DR2-M-1 fix (which reads the documents) cannot see them, and the
  console still says "never wider".
- **Expected:** as DR2-M-1: no key gains access. Where the tool cannot know, it does not say it does.
- **Why S2:** as DR2-M-1. A request the blacklist refused is permitted, for the holder of a blocked principal's key, by
  default.
- **Fix (#1970):**
  - `--blacklist`, an export of the blocked principals' values, `["acme:mallory", ...]` (`BlacklistExport`). A key
    whose principal is in it is refused for `own-principal` with the same message as DR2-M-1 and the same two ways out.
  - Without the export, the console and `upgrading.md` say that no key is wider *by the documents, memberships and
    blacklist given*. The output says the blacklist was not seen, and the report names, for each key that moved, the
    value to block (`blockIfPrincipalBlocked`, `<tenant>:apikey:<key id>`).
  - `MIG-6` covers it, with a test each way and the mutant `mig-6-blacklist-not-read`.

### DR2-M-2 · S3 · A legacy key record is read leniently

- **Location:** `LegacyApiKey.parse`. It refuses a record that is not an object or that has an unknown member, but
  reads each member with helpers that never check its type:
  - `strings()` is `values().map { it.asString() }`, so a member that is not an array reads as empty;
  - `attributes` is read with `properties()`, so a member that is not an object reads as empty;
  - `text()` is `asString()`, so a number reads as its text.
- **Trigger:** a 7.x key record with `"policies": "deny-exports"` (a string, not an array), `"roles": "admin"`, or
  `"attributes": "x"`, written by hand, by another tool, or by an attacker with write access to the 7.x store.
- **Actual:**
  - `policies` reads as none. The key is converted without its attachment, so if `deny-exports` holds a DENY the key
    loses it.
  - `attributes` reads as none, so the refusal of keys with attributes (a condition may read them) is skipped.
  - A record 7.x's own decoder rejected, so a key that never worked, comes out as a working 8.0 key.
  - A malformed `expiresAt` throws `DateTimeParseException`, which `StoredApiKeys.read` does not catch (it catches
    `IllegalArgumentException`), so the whole run stops instead of naming the key (fail closed).
- **Expected:** what the rest of the tool does with 7.x data: refused when it is not what the format says, never read
  as something else (`ExportDirectory`, `CoSec7xReader`, `LegacyApiKey`'s own unknown-member check). The rejection of
  unresolvable attachments exists exactly because "dropping it could drop a DENY".
- **Why S3:** it needs a malformed record in the 7.x store, and its consequence is a key wider than its record says.
- **Reproduced:** no; by the code.
- **Fix direction:** check each member's type (string, array of strings, object of strings, boolean, ISO instant) and
  put a record that fails into `unreadable`, as a non-key is today. Catch the date's parse failure there too.
- **Regression guard:** `ApiKeyMigrationTest` (or a `LegacyApiKey` test) cases with each member of the wrong type,
  asserting `unreadable`; a mutant that reverts `strings()` to the lenient read.

### DR2-M-3 · S4 · `publish --in`'s integrity check catches accidents only

- **Location:** `ConvertedDirectory.read` checks each document against `outputSha256` in `report.json`, which sits in the
  same directory. Its KDoc ends "so `publish` writes only what `convert` produced".
- **Actual:** whoever can change a document can change `report.json` too, and `publish` then writes the edited document.
  An edit must still compile and hold no break-glass binding, and `publish` never overwrites a document. `publish --file`
  publishes a hand-written document anyway.
- **Expected:** a statement that matches what is checked.
- **Fix direction:** say in the KDoc and in `upgrading.md` that the check catches an accidental change, not a writer of
  the directory. Optionally print the SHA-256 of `report.json` at the end of `convert` and accept it as
  `publish --report-sha256`, so the operator carries the value out of band.

### DR2-M-4 · S4 · 7.x ids are printed raw

- **Location:** most messages and locations of `Converter`, and `ConvertCommand.summarize`, which prints them. Policy
  ids, app ids, grant keys, permission ids and Redis keys go in as read (`"policy [$id] is listed more than once"`,
  `"permission [$permissionId] is defined more than once"`, `"grant [${grantKey(grant)}] …"`, the `location` of every
  entry). A few places use `Identifiers.printable`.
- **Trigger:** a 7.x id holding a line break, written as `"\n"` in JSON.
- **Actual:** the summary shows forged lines, for example a fake `Converted. Input sha256 …` among the `REJECT`
  lines. The id itself is rejected later (`DOC-13`, `INVALID_ID`), but the operator reads the console to decide whether
  to publish.
- **Expected:** RC1-15, as the rest of CoSec does: an id printed in a line goes through `Identifiers.printable`.
- **Fix direction:** escape at the one place lines are printed (`ConvertCommand.summarize`, `ApiKeysCommand.summarize`):
  `location` and `message` through `Identifiers.printable`.

### DR2-M-5 · S4 · The Redis reader drops entries in silence

- **Location:**
  - `CoCache6Format.scalar` answers null for a key whose value is in state `M` (a cached absence) or `L` (a load
    lease), and `CoSec7xReader.source` drops it.
  - `CoSec7xReader.keys` drops every policy key whose id begins with `history:` (`V7CacheSpecs.RESERVED_POLICY_ID_PREFIX`).
- **Actual:** none of these is counted or reported. Each fails closed later:
  - a global policy the index names but that was dropped is rejected (`GLOBAL_INDEX_WITHOUT_CONTENT`);
  - an attachment of a dropped policy is indeterminate in 8.0;
  - a dropped grant narrows.
  
  Still, a real 7.x policy called `history:x`, or a value caught mid-load because 7.x was still running, disappears
  from the conversion without a line.
- **Fix direction:** count and warn: "skipped [key]: a cached absence / a load in progress / the policy history". When a
  lease is seen, add "convert from a store no 7.x instance writes".

### DR2-M-6 · S4 · Redis credentials only on the command line

- **Location:** `Options` (`--to-redis`, `--from-redis`, `--redis`), and `upgrading.md`'s Job, which puts the URI in a
  Secret and expands it with `args: [..., "$(REDIS_URI)"]` ("the tool reads its Redis only from its options").
- **Actual:** `RedisUris` keeps the password out of everything the tool prints, but the expanded `args` are the
  process's command line, visible to anyone who can list processes on the node, and to agents that record command
  lines.
- **Fix direction:** read the URI from an environment variable or a file when the option is not given (for example
  `--to-redis-env REDIS_URI`, or `COSEC_MIGRATE_REDIS_URI`), and use it in the documented Job.

### DR2-M-7 · S4 · Writes name no operator

- **Location:** `DocumentPublisher.MIGRATE_ACTOR`, used by `publish`, `bootstrap` and `restore`. The provenance names
  `(platform)`/`cosec-migrate`, with the change text `migrate`, `bootstrap` or `restored from … by cosec-migrate restore`.
- **Actual:** these are the writes that bypass the change flow by design (RFC 0007 §5.9: the way back from a
  lock-out). `storage.md` says the control plane's listeners never hear of them, so the revision record is their only
  audit, and it does not say who ran the command.
- **Fix direction:** accept `--by <name>` (defaulting to the OS user) and write it into the provenance's change text,
  bounded and printable, so the one record of a restore says who restored.

### DR2-M-8 · S4 · `apikey:<id>`'s existence check needs `--memberships`

- **Location:** `ApiKeyMigration.ownPrincipal` refuses the move when `apikey:<key id>` is bound in a document or listed
  in `--memberships`. Without `--memberships`, only documents are looked at.
- **Actual:** a principal the membership provider already knows under that name (left by an earlier run, or made by
  hand) is not seen, and the key would act with that principal's roles. `upgrading.md` describes the check
  accurately, but does not say that it is incomplete without the export.
- **Fix direction:** require `--memberships` when a key would take `own-principal`, or say plainly that without it the
  check covers documents only.

## Checked, and held

What the review looked for and found guarded, so that round 3 need not look again:

- **Direction of every translation:**
  - actions: `MatcherTranslator`. An ALLOW of everything is `http:*`, a DENY stays `*`. Templates, custom types, a
    non-`/` separator and mixed methods are rejected. Methods follow 7.x's literal comparison.
  - statements: `StatementTranslator`. A DENY left with no action is rejected, never dropped. HEAD follows `PIP-18`.
  - conditions: `ConditionTranslator`, `PartTranslation`.
    - `inRole` by direction: `subject.roles` holds bare roles of every space, so a DENY is never narrower.
    - absence put back for 7.x's `""`.
    - case folding beyond ASCII refused in the DENY direction.
    - parts 8.0 never sets are refused where they matter.
    - templates: 7.x's lone `#{…}` evaluates to null for an absent attribute, and `"".equals(null)` is false, so the
      DENY reading is exact.
  - `time`: `TimeTranslation`. The same default zone, midnight crossing and start day as 8.0's checker.
  - regular expressions: `LiteralRegex`. Only literals, choices and prefixes; a wider prefix only for a DENY without
    case-mapped characters.
  - rate limits: read in the DENY direction.
- **What 7.x decodes and what is translated agree:**
  - `effect` is decoded as `Effect.valueOf(text.uppercase())` and translated by a case-insensitive comparison with
    `deny`, so a number, padding or another spelling is rejected by the decoder.
  - an action object with more than one member, which 7.x reads by its first member, is rejected by the translator.
  - `negate` and path options are read by the same rules on both sides.
- **No silent skip in the policy path:**
  - duplicate ids, a global index entry without content, a `GLOBAL` type outside the index, undefined permissions and
    an app id the file system would fold are rejected;
  - grants of an app without an app permission are dropped with a warning (7.x ignored them);
  - a `--partition by-owner` policy holding a DENY stays in the platform document.
- **No unsafe evaluation:** decoding a hostile 7.x policy parses SpEL and OGNL and builds path patterns. Nothing in
  `cosec-migrate` evaluates a matcher.
- **Writers through the control plane's store:**
  - `publish` compiles every document against the platform's limits, checks references, refuses break-glass, and
    writes each document only where none exists. With `--resume` it skips only identical documents.
  - `restore` compiles, refuses break-glass, and writes by compare-and-set against the confirmed head.
  - `bootstrap` refuses while any `cosec:platform-admin` binding exists unless `--force`, and keeps the document's
    other entries (`MIG-3`).
  - `repair-references` runs under a renewed lease, prunes an entry only when its referrer's head is at or above the
    revision that indexed it (an in-flight publish keeps its entry, `MIG-4`), converts only values CoCache reads in
    the old format, and reports every document it could not repair.
  - `verify-chain` only reads.
- **The API key rewrite** is a compare-and-set of exactly what was read, after the report is on disk, and a report
  that holds a plan whose keys were rewritten is never overwritten. A refused or dry run writes no key.
- **Clusters:** every command that scans refuses a Redis Cluster rather than read one shard.

## Files read

**`me.ahoo.cosec.migrate`**, every file end to end (60 files, 7,181 lines): `ApiKeyMigration.kt` (243),
`ApiKeyMigrationPlan.kt` (122), `ApiKeysCommand.kt` (212), `Bootstrap.kt` (125), `BootstrapCommand.kt` (47),
`ChainVerification.kt` (61), `CoCache6Format.kt` (81), `CoSec7xReader.kt` (155), `ConditionTranslator.kt` (223),
`ConversionReport.kt` (112), `ConvertCommand.kt` (78), `ConvertedApiKey.kt` (32), `ConvertedBudgets.kt` (45),
`ConvertedDirectory.kt` (288), `ConvertedRateLimit.kt` (40), `Converter.kt` (918), `DocumentIds.kt` (37),
`DocumentPublisher.kt` (180), `DocumentRestorer.kt` (121), `DocumentsExistException.kt` (31), `ExportDirectory.kt` (123),
`LegacyApiKey.kt` (103), `LegacyConversion.kt` (110), `LegacyReferences.kt` (97), `LiteralRegex.kt` (113),
`MatcherTranslator.kt` (239), `MembershipsExport.kt` (58), `MigrateMain.kt` (155), `Options.kt` (95),
`PartTranslation.kt` (500), `PartitionPlan.kt` (90), `PartitionPlanCommand.kt` (56), `PrincipalBinding.kt` (24),
`PrincipalRoles.kt` (31), `PublishCommand.kt` (183), `PublishException.kt` (32), `PublishResult.kt` (23),
`PublishedDocuments.kt` (38), `RateLimitTranslation.kt` (86), `RedisAccess.kt` (129), `RedisUris.kt` (57),
`ReferenceRepair.kt` (226), `RepairReferencesCommand.kt` (64), `RepairResult.kt` (44), `RestoreCommand.kt` (78),
`RestoreException.kt` (17), `RoleBinding.kt` (25), `SemanticScan.kt` (189), `Source7x.kt` (80),
`StatementTranslator.kt` (326), `StoreConversion.kt` (82), `StoredApiKeys.kt` (73), `StoredDocuments.kt` (78),
`TimeTranslation.kt` (64), `Translated.kt` (56), `UnmappedVocabulary.kt` (67), `Unreachable.kt` (61),
`UsageException.kt` (21), `VerifiedChain.kt` (37), `VerifyChainCommand.kt` (100).

**`me.ahoo.cosec.migrate.v7`**, read end to end where a translation depends on what 7.x decodes or matches (33 files):
`api/configuration/Configuration.kt`, `api/policy/Effect.kt`, `authorization/RoleScope.kt`, `limiter/RateLimitRule.kt`,
`policy/Expression.kt`, `policy/action/AbstractActionMatcher.kt`, `AllActionMatcher.kt`, `PathActionMatcher.kt`,
`PathPatternParsers.kt`, `policy/condition/AbstractConditionMatcher.kt`, `AllConditionMatcher.kt`,
`BoolConditionMatcher.kt`, `OgnlConditionMatcher.kt`, `SpelConditionMatcher.kt`, `TimeConditionMatcher.kt`,
`context/AuthenticatedConditionMatcher.kt`, `InRoleConditionMatcher.kt`, `InTenantConditionMatcher.kt`,
`part/BoundedRegex.kt`, `EqConditionMatcher.kt`, `InConditionMatcher.kt`, `PartConditionMatcher.kt`,
`PartExtractor.kt`, `RegularConditionMatcher.kt`, `StartsWithConditionMatcher.kt`,
`serialization/AbstractJsonStatementSerializer.kt`, `JsonActionMatcherSerializer.kt`,
`JsonAppPermissionSerializer.kt`, `JsonEffectSerializer.kt`, `JsonPermissionSerializer.kt`,
`JsonPolicySerializer.kt`, `JsonRateLimitRuleSerializer.kt`, `V7JsonSerializer.kt`. **In part** (5 files): the members
named of `cache/V7CacheSpecs.kt`, `configuration/JsonConfiguration.kt`, `serialization/CoSecModule.kt` and
`api/context/request/Request.kt`, and the access rules of `policy/condition/OgnlSandbox.kt`.

**Not read end to end** (the other 53 files of `v7`): the interfaces and data holders of `api/` (principal, tenant, permission,
policy, context, authorization), `permission/*Data.kt`, `policy/PolicyData.kt`, `StatementData.kt`, `BlockingAware.kt`,
`MatcherFactoryRegistry.kt`, `PathVariableScope.kt`, `cache/AppSpacedRoleId.kt`, the factory providers,
`CompositeActionMatcher.kt`, `part/ContainsConditionMatcher.kt`, `EndsWithConditionMatcher.kt`,
`PathConditionMatcher.kt`, and the serializers `JsonConditionMatcherSerializer.kt`, `JsonConfigurationSerializer.kt`,
`JsonPermissionGroupSerializer.kt`, `JsonPolicyTypeSerializer.kt`, `JsonResources.kt`, `JsonStatementSerializer.kt`.
They decode or hold what the files above read, and the translators never rely on them beyond decoding: `contains`,
`endsWith` (but on hosts) and `path` (but on `request.path`) conditions are rejected by the translator whatever 7.x
reads.

Tests were not reviewed as a scope; they were read to decide whether a property has a guard.
