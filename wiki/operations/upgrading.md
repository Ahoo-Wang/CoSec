---
title: "Upgrading"
description: "How to upgrade a CoSec 7.x deployment to 8.0: what changes, and how to migrate the data, the code, the configuration and the clients."
---

# Upgrading

For teams moving a CoSec 7.x deployment to 8.0. The page starts with a checklist, then gives the details by area:
the data migration, what 8.0 decides differently, responses, authentication, storage, APIs, properties and
observability. This page is the single home of migration steps. Release notes for every version are on
[GitHub Releases](https://github.com/Ahoo-Wang/CoSec/releases).

## From 8.0.0-alpha.2 {#from-8-0-0-alpha-2}

8.0.0-alpha.2 is a preview with no compatibility promise. These changes since then, from the
[RC security review](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/security/8.0-rc-review.md), make the grant
analysis read the author's rights as enforcement does, so some changes that published alone now wait for approval
([Testing policies](../policies/testing.md#approval)). Nothing to migrate in the documents.

- **Token attributes of every type** (RC1-2, `GRT-5`). The analysis reads `subject.attributes.*` as the engine does,
  a boolean or a number as its text. An author whose ALLOW is conditioned on not being `suspended` no longer counts
  as holding it when the token says `"suspended": true`. In code, `Operator.attributes` and `Grantor.attributes` are
  `Map<String, Any>`, the token's claim as is, where alpha.2 kept only the strings.
- **Expiring bindings** (RC1-3, `GRT-3`). What an author holds through a binding with `expiresAt` covers only grants
  whose own binding expires no later; a grant without `expiresAt`, a later one, or making a policy attachable is an
  escalation with the shortfall `EXPIRY`.
- **Removing a tenant policy whose id the platform defines** (RC1-9, `GRT-4`) is analyzed as granting the platform's
  policy to the tenant's bindings and attachments of that id. An id both define grants the author nothing.
- **Every app document** (RC1-4, `GRT-6`). The analysis of a tenant change reads every app document the app list
  names (`<s>:control:apps:all`, [Storage](./storage.md#publishing-through-the-control-plane)), not only those the
  change names, so a DENY an app binds to one of the author's roles limits what the author may grant in every scope
  that reaches that app. An app document that cannot be read makes such a grant an escalation (`UNREADABLE`). The
  analysis trusts the app list only once it is complete, and an upgraded store's list is not: **until
  [`cosec-migrate repair-references`](#repair-the-back-reference-index) has run after the upgrade, every tenant grant
  over every app (a binding with app `*`, or an attachment, also one a condition narrows to apps the change does not
  name) needs an approval** (`UNREADABLE`), and the control plane logs a WARN that names the command, at most once a
  minute.
- **A platform policy cannot take a tenant's id** (RC1-9, `CTL-21`). A platform change that adds a policy whose id a
  tenant document defines or binds is refused (`V2`, `422`, naming the tenant documents). A tenant that defines the
  id itself no longer blocks the platform from removing its own policy of that id. The back-reference index learns
  only the ids a tenant adds after the upgrade; the ones its document already had enter it only through
  `repair-references`: **once every control-plane instance and every `cosec-migrate` binary runs this version, run
  [`cosec-migrate repair-references`](#repair-the-back-reference-index) once**, as a Kubernetes Job, with the Redis
  and key prefix of the control plane, and again after any downgrade. Until it has run, a platform policy
  that takes the id of a tenant's own policy from before the upgrade is not refused, only ambiguous in that tenant,
  which enforcement fails closed on.
- **Escalations** gain the reasons `UNRESOLVED` (a new binding of a policy no document defines, RC1-32),
  `UNREADABLE` (RC1-4) and `TENANT_BINDING` (each tenant binding an app document change widens, RC1-34, `APR-6`).
  `TENANT_BINDING` is informational: the app change needs its approval for `NOT_ANALYZED`, and the entry names only
  the binding and its tenant document. Code that matches every `UnknownReason` or `Shortfall` gains
  `UNRESOLVED`, `UNREADABLE` and `EXPIRY`.
- **Moved heads after a publish** (RC1-33). When another document a change was checked with, or the app list, moved
  before its write,
  the control plane records `HEADS_MOVED` or `REVIEW_NEEDED` after `PUBLISHED`, with the heads in a new `moved` member
  ([What a publish compares](./storage.md#what-a-publish-compares)). A change listener that handles every
  `ChangeEvent.Kind` gains the two.
- **The trust root is the platform's** (RC1-10, `APR-3`). Only a platform principal holding `cosec:approve` publishes
  its own changes unanalyzed; a tenant principal the platform gave `cosec:approve` now has its own changes analyzed,
  and approves others'. A tenant's boundary is the platform's: a tenant principal's `SetBoundary` is `403`, so is its
  `apply` with `REPLACE` or a rollback that would remove or change the boundary the platform set, and only a platform
  principal approves, rejects or abandons a change that sets one (`BDY-2`). An API key is never the trust root, even
  for a platform principal holding `cosec:approve`; nor is any credential named `api-key`, a forwarded identity's
  included. Under `four-eyes`, a platform principal's change needs another platform principal's approval.
- **`cosec:platform-admin` is bound only by the platform** (RC1-36, `TNT-2`): in the platform document, or the
  platform tenant's own, as `bootstrap` writes it. A tenant or app document that binds it no longer compiles; remove
  such a binding before upgrading.
- **Approvals are a person's, and checked again** (RC1-11, `APR-7`, `APR-8`). A principal that authenticated with an
  API key cannot approve (`403`). An approval records the approver's credential (`jti`, `iat`); revoking that token,
  or the approver's tokens, before the change publishes takes the approval back. An approver whose memberships come
  from the membership provider must be permitted both on the roles it held then and still holds and on what the
  provider answers now; the starter now gives the control plane the application's `MembershipProvider` (RC1-13). **An
  approval that records no credential id does not count**: every change approved before the upgrade goes back to
  `AWAITING_APPROVAL` at publish, and is approved again. In code, `AuthenticatedPrincipal` has `issuedAt` and `apiKey`.
- **The starter checks how approvals are revoked.** The control plane needs a revocation reader, as the pipeline
  does: a `RevocationReader` bean, or CoSec's tokens with `cosec-cocache`; startup fails without one (declare
  `RevocationReader.NONE` when no approver's credential is ever revoked). Startup also fails when
  `cosec.jwt.token-validity.refresh`, how long a revocation is kept, is shorter than
  `cosec.control.approval.expire-after`.
- **A busy staging slot can be taken** (RC1-12, `CTL-22`, `BRK-9`): by a break-glass revoke, and by a change that only
  narrows, staged by a holder of `cosec:skip-shadow` ([Taking a busy slot](../policies/testing.md#taking-a-busy-slot)).
  The change that held it becomes `CONFLICTED`. A publish retried after its answer was lost reports `PUBLISHED`.
- **`Anyone` in tenant `(0)`** (RC1-35, `GRT-7`): such a grant reaches unauthenticated callers, and needs an approval
  (`ANONYMOUS`). Code that matches every `UnknownReason` gains `ANONYMOUS`.
- **The default space in control-plane resources is `(default)`** (RC1-37, `SCP-9`), and space ids beginning with `(`
  are CoSec's: a request that declares one is `403` (`scope-rejected`), and a document binding in one does not compile.
  A control-plane policy written for `scope/<tenant>/<app>/-` now means the space named `-`; write
  `scope/<tenant>/<app>/(default)` for the default space. **A control-plane DENY on `scope/*/*/-` no longer applies to
  the default space**: rewrite it as `scope/*/*/(default)` before upgrading, or the grants it held back are allowed.
  A binding in a space whose id begins with `(` stops its document compiling, and a request in such a space is
  refused: before upgrading, look for `"space": "(` in every document, move such bindings to a space id of yours, and
  move the memberships of that space with them.
- **Break-glass** (RC1-27, RC1-29, RC1-30): `cosec-migrate` writes no break-glass binding
  ([Root becomes break-glass](#root-becomes-break-glass)); `BreakGlass.request` derives the binding's id and has no
  `binding` parameter; approving break-glass needs a REQUIRED change listener (`503` otherwise, `BRK-3`).
- **During a rolling upgrade**, an app document an older instance creates is not in the app list; newer instances
  find it when they start and at each reclamation pass (`cosec.control.revisions.reclaim-interval`, 1 hour by default),
  and until then may analyze a grant without that app's DENYs. Create no app through an older instance while you roll,
  or lower `reclaim-interval` for the duration. An older instance also still takes a tenant principal holding
  `cosec:approve` for the trust root, and accepts an API key's approval: approve and publish through upgraded
  instances only until the roll is done. An approval an older instance accepted names no credential, so an upgraded
  instance does not publish on it.

## From 8.0.0-alpha.1 {#from-8-0-0-alpha-1}

8.0.0-alpha.1 is a preview with no compatibility promise. These changes since then, from the
[RC security review](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/security/8.0-rc-review.md), break decisions,
code or configuration written against it. A 7.x deployment reads them in the sections below.

- **A role cap removes ALLOWs only** (RC1-1, `BND-9`, `IDN-2`). A capped credential (an API key with `roles`) still
  gets no grant from its principal's attached policies and principal bindings, but their DENYs now apply to it: a
  request its principal is denied is denied to every key of the principal, capped or not. In alpha.1 a cap dropped
  those DENYs too, so a capped key could do what its principal could not. Nothing to migrate; to see which requests
  change, `simulate` them with `principalBindings: false` ([Testing policies](../policies/testing.md)). The grant
  analysis counts a capped author's DENYs the same way.
- **Break-glass by provenance** (RC1-26, `BRK-8`). Only a token CoSec's own `JwtTokenVerifier` verified gets
  break-glass. A `TokenAuthenticator` around another `TokenVerifier` authenticates its tokens without break-glass, and
  names their credential after its `method` (`token` by default) rather than `jwt`. The method `jwt` is reserved:
  `AuthenticatedPrincipal` refuses a credential of that method from your code (`IllegalArgumentException`, so a `503`),
  and `TokenAuthenticator` refuses `method = "jwt"` for a verifier that is not CoSec's. Name your authenticator's
  method after its credential ([Extending](../architecture/extending.md#authenticators)).
- **A renamed API key header is a credential header** (RC1-21, `CND-5`). With `cosec.pipeline.api-key-header` set, a
  document that reads that header (a condition or a rate limit `key`) no longer compiles, in the control plane or on
  an instance, and the pipeline never reads it for a condition. Remove such references before upgrading. In code,
  `ConditionChecker`, `PolicyDocumentCompiler.forPlatform` and `of`, `PlatformCompiler`, `ChangeChecks`, `Simulator`
  and `HttpAttributes` take the header as a new parameter.
- **A `tenantId` claim that is not a string makes the token invalid** (RC1-22), where alpha.1 read it as the default
  tenant `(0)`. Issue the tenant as a string.
- **The tenant switch re-derives memberships** (RC1-23). `AbstractSwitchTenantAuthentication.switchTenant` answers a
  `TenantMembership`, the user's roles, policies and attributes in the target tenant (or
  `TenantMembership.fromProvider()`), instead of a principal, and `authenticate` builds the new principal from it
  alone. An implementation that wrapped `previousPrincipal` carried the previous tenant's roles into the new token
  ([Tenants](../integration/authentication.md#tenants)).
- **The JWT secret has a minimum length** (RC1-24): 32, 48 or 64 UTF-8 bytes for `hmac256`, `hmac384` or `hmac512`.
  A shorter `cosec.jwt.secret` fails startup; set a longer one on the issuer and every verifier, which makes the
  tokens issued with the old one invalid.
- **Social login signs only what the provider owns** (RC1-25). `DirectSocialUserPrincipalConverter` gives the
  principal two attributes, `cosec_oauth_provider` and `cosec_oauth_user_id`, instead of the provider's raw user info,
  which the user edits, and no longer writes into the `SocialUser` it converts. A token whose `attributes` name
  `apiKeyId`, which CoSec sets for API keys, is refused as `token-invalid`, and `JwtTokenConverter` does not issue
  one. To keep profile fields, declare your own `SocialUserPrincipalConverter` and keep only fields the user cannot
  change ([Social login](../integration/authentication.md#social-login)).

## 7.x to 8.0

8.0 upgrades only from 7.x. To upgrade from 6.x, first follow
[the upgrade guide on the `7.x` branch](https://github.com/Ahoo-Wang/CoSec/blob/7.x/wiki/operations/upgrading.md) to
7.x, including its data migration, then come back here. `cosec-migrate convert` refuses Redis data that is not in the
7.0 layout.

What is different, in short:

- **Policies are documents.** A platform document, one document per app and one per tenant replace 7.x's policies,
  global policy index, app permissions and role grants. Redis stays the store of record; each instance compiles the
  documents into a snapshot and decides without reading Redis per request. The format is in the
  [policy language](../policies/language.md).
- **One decision path.** The 7.x `Authorization` chain is gone. Each adapter (WebFlux, Spring MVC, Gateway) hands the
  request to one coroutine pipeline that authenticates, resolves the scope, decides and maps the outcome to a status.
- **Changes go through a control plane.** Writing, validating, simulating, shadowing, approving, explaining and rolling
  back policies are control-plane operations (`cosec-control`), not application code or local files.
- **Conditions are data.** A small condition language replaces the matcher factories, OGNL and SpEL. Nothing in a
  policy runs code.
- **Errors are `application/problem+json`.** A response no longer tells a client whether a DENY exists.
- **Java 21.** Every 8.0 module is compiled for Java 21, where 7.x was compiled for Java 17: an application builds
  and runs on JDK 21 or later. The `cosec-gateway-server` image is the exception: it runs on JDK 25, with an AOT cache
  ([Deployment](./deployment.md#the-image)).

### Upgrade checklist

1. **Be on 7.x data.** The Redis data must be in the 7.0 layout (schema marker `7`); a 6.x deployment upgrades to 7.x
   first.
2. **Find what has no 8.0 form.** Search policies and code for: `spel` and `ognl` conditions, custom
   `ActionMatcherFactory` or `ConditionMatcherFactory` types, `#{...}` path templates in actions, `regular` conditions,
   `ignoreCase` in DENY conditions, `BlockingAware`, implementations of `Authorization`, `Statement` or `Policy`,
   `CoSecAuthorizationCustomizer`, root (`root-enabled`), local shadow files and the `cosecExplain` endpoint.
   [Converting](#migrating-the-data) reports every policy that cannot be translated.
3. **Convert the data** with `cosec-migrate convert` and fix every rejection until the conversion is accepted. Read
   the semantic changes in `report.json` against [What 8.0 decides differently](#what-8-0-decides-differently).
4. **Update the dependencies and the code**: move the build and the runtime to JDK 21, replace the starter's
   capabilities with the modules themselves and move imports to the new packages
   ([Modules, artifacts and packages](#modules-artifacts-and-packages)), remove the uses of
   [removed APIs](#removed-and-replaced-apis) and register the 8.0 extension beans instead.
5. **Update the configuration**: rename or remove every [7.x key](#removed-and-renamed-properties), and decide on the
   new ones. A `cosec.*` key 8.0 does not read fails startup and names its replacement. Startup also fails in three
   common cases: no route map at `classpath:cosec-routes.json` on a WebFlux or Spring
   MVC host (set `cosec.authorization.routes.location` to a map, or empty), JWT without `cosec-cocache` and without a
   revocation store of your own ([Token revocation is always on](#token-revocation-is-always-on)), and a Redis whose
   eviction policy is not `noeviction` or cannot be read
   ([Startup fails when Redis's eviction policy cannot be read](#startup-fails-when-redis-s-eviction-policy-cannot-be-read)).
6. **Update the clients and the alerts**: error bodies, `reason` values and some status codes change
   ([Responses and status codes](#responses-and-status-codes)); audit fields, metric tags and spans change
   ([Observability changes](#observability-changes)).
7. **Publish** the converted documents with `cosec-migrate publish`, and, if you run the control plane, bind its first
   administrator with `cosec-migrate bootstrap`. Until the platform document is published, every 8.0 request is
   answered `503`, and readiness refuses traffic.
8. **Deploy 8.0**, then check decisions on the requests you rely on (the control plane's `simulate` and `explain`, see
   [Testing policies](../policies/testing.md)).
9. **Clean up later.** 8.0 never reads, changes or deletes the 7.x keys. Delete them once you no longer need to
   [roll back](#rollback).

### Migrating the data

`cosec-migrate` is a one-time command-line tool in the `migration/migrate/` module. No application depends on it, and the starter
never does. Its contract is the command line: its classes are not a library API, and may change in any release. Build it
from the CoSec sources of the version you deploy:

```bash
./gradlew :migrate:installDist
migration/migrate/build/install/cosec-migrate/bin/cosec-migrate convert ...
```

Its commands are below. Exit codes: `0` done, `1` the data was rejected or a write failed, `2` a usage error.
`publish`, `bootstrap` and `repair-references` take `--cluster` for a Redis Cluster: `--to-redis` then names seed
nodes, comma-separated (`redis://node-1:6379,redis://node-2:6379`). The other commands read Redis with clients that
do not, and refuse it.

| Command | What it does |
|---|---|
| `convert` | Reads 7.x data (Redis or exported files), translates it, and writes 8.0 documents and a report into a new directory. Writes nothing to Redis. |
| `publish` | Writes the documents of an accepted conversion to Redis as their first revisions, through the control plane's store. Never touches the 7.x keys. |
| `bootstrap` | Binds one principal of the platform tenant to `cosec:platform-admin`, so the control plane has someone who may write. |
| `partition plan` | Converts twice, with `--partition platform` and `by-owner`, and writes one plan per tenant to submit through the control plane. Writes nothing to Redis. |
| `repair-references` | Adds to the back-reference index what every document's head refers to and the index misses, and completes the app list. Only adds. |

#### Convert

```bash
cosec-migrate convert --from-redis redis://localhost:6379 [--key-prefix cosec] --out ./converted
cosec-migrate convert --from-files ./export --out ./converted
```

| Option | Meaning |
|---|---|
| `--from-redis <uri>` | Read 7.x data from this Redis: the policies, the global policy index, app permissions and role grants under `--key-prefix`, and the schema marker `<prefix>:meta:schema`, which must be `7`. Policy history (`<prefix>:policy:history:*`) is skipped. |
| `--from-files <dir>` | Read an export instead: `policies.json` (an array of 7.x policies, each with its `id`), `global-policies.json` (an array of the ids in the global policy index) and `app-role-permissions.json` (one entry per app: `{"appPermission": {...}, "grants": {"admin": ["*"], "auditor@s1": ["invoice.read"]}}`, or `{"appId": "...", "grants": {...}}` for an app without an app permission). The three files are required; `rate-limit-rules.json` is optional. Use this for 7.x local policy files: put their contents into `policies.json`. |
| `--key-prefix <p>` | The 7.x key prefix; default `cosec`. |
| `--out <dir>` | A directory that does not exist or is empty. `convert` never overwrites. |
| `--rate-limit-rules <file>` | The 7.x rate limit rules (`cosec.limiter.locations`): one rule object or an array of rules, each with its `id`. Merge several rule files into one array. |
| `--partition platform\|by-owner` | `platform` (default) puts every policy into the platform document, attachable by anyone, as in 7.x. `by-owner` puts each non-global policy into the document of the tenant its `tenantId` names (rule C5 below); a policy with DENY statements stays in the platform document, with a warning. |

The output directory:

| File | Content |
|---|---|
| `platform.json` | The platform document. |
| `apps/<appId>.json` | One app document per 7.x app permission: its permission catalog and role bindings. |
| `tenants/<tenantId>.json` | Tenant documents, with `--partition by-owner` only. |
| `provenance.json` | Where each policy and binding came from in the 7.x data. Only for an accepted conversion. |
| `report.json` | `accepted`, the input's SHA-256, counts, `rejections`, `warnings`, `semanticChanges`, and the SHA-256 of every file written. |

A rejected conversion writes `report.json` only. The console prints the same counts, warnings and rejections.

#### Rejections

Each rejection names its kind, the location in the 7.x data, a message and advice. Fix the 7.x data (or the export),
then convert again into a new directory.

| Kind | Cause | What to do |
|---|---|---|
| `SCHEMA_MARKER` | The Redis data is not in the 7.0 layout. | Upgrade to 7.x first (its data migration), then convert. |
| `GLOBAL_INDEX_WITHOUT_CONTENT` | The global policy index lists a policy that has no content. 7.x failed every request. | Store the policy, or remove its id from the index. |
| `GLOBAL_TYPE_NOT_INDEXED` | A policy of type `GLOBAL` is not in the global policy index. | Add it to the index, or change its type. |
| `UNDEFINED_PERMISSION` | A role grant names a permission its app does not define (rule C6). | Define the permission, or remove it from the grant. |
| `UNDECODABLE` | Not valid 7.x JSON, not decodable by 7.x's own model, or it needs a class missing from the classpath (an `ognl` condition, a custom matcher). | Fix the stored JSON. A custom matcher has no 8.0 form; see `CUSTOM_ACTION` and `CUSTOM_CONDITION`. |
| `RESERVED_POLICY_ID` | A policy id starts with `perm:`, `g:`, `r:` or `cosec:`, which generated ids use. | Rename the policy and every attachment that names it. |
| `INVALID_ID` | An id 8.0 cannot hold: `*` or empty as an app or space id, `:` in an app id, `@` in a role id. | Rename it. In 8.0 `*` means any app or space. |
| `DUPLICATE_ID` | The same policy twice in an export, or the same permission twice in one app. | Keep one. |
| `OWNER_TENANT` | `--partition by-owner`, and a policy whose `tenantId` names no tenant. | Set its `tenantId`, or convert with `--partition platform`. |
| `CONDITION_NOT_TRANSLATED` | A built-in 7.x condition with no exact translation, such as a `regular` that is not a literal, a literal prefix or a choice of literals, or a `path` condition outside a statement whose action is `all`. | Rewrite it with the conditions the message names (`eq`, `in`, `startsWith`, ...). |
| `EXPRESSION_CONDITION` | A `spel` or `ognl` condition. | Rewrite it as conditions of the [policy language](../policies/language.md). |
| `CUSTOM_CONDITION` | A condition of a custom `ConditionMatcherFactory`. | Move the value it reads into an `AttributeProvider` and write a condition on that attribute ([Extending](../architecture/extending.md)). |
| `ACTION_NOT_TRANSLATED` | A `#{...}` path template, a path `separator` other than `/`, an action array whose members have different methods, or a method that is not an HTTP token. | See [Path templates in actions](#path-templates-in-actions); split statements by method; remove methods no request can have. |
| `CUSTOM_ACTION` | An action of a custom `ActionMatcherFactory`. | Rewrite it as HTTP paths and methods, or as route-mapped actions. |
| `DOES_NOT_COMPILE` | A translated statement does not compile, such as a path pattern outside what 8.0 accepts. | See the message and the [policy language](../policies/language.md). |

Warnings do not stop a conversion. The current ones: a policy in the global policy index whose type is not `GLOBAL`
(converted as an attachable policy, since 7.x did not apply it to everyone), a tenant's policy with DENY statements kept
in the platform document under `by-owner`, and role grants of an app without an app permission (7.x ignored them, so
they are dropped).

#### Semantic changes in the report

`semanticChanges` lists, for each place in the 7.x data, the shape whose decision 8.0 makes differently by design.
`change` is one of the rule ids in [What 8.0 decides differently](#what-8-0-decides-differently) (`C1a`–`C6`,
`D1`–`D3`), or:

- `ASCII-CASE`: an ALLOW condition with `ignoreCase`. 8.0 folds ASCII letters only, so the ALLOW matches fewer
  spellings. In a DENY condition whose value has letters outside ASCII, the condition is rejected instead.
- `RATE-LIMIT`: a 7.x rate limit rule, translated into the platform document's rate limits
  ([Rate limit rules become obligations](#rate-limit-rules-become-obligations)).

Read each entry. Nothing in the report needs a change to the data, but each one can change a response.

#### Publish

```bash
cosec-migrate publish --to-redis redis://localhost:6379 [--cluster] [--key-prefix cosec] --in ./converted [--resume]
```

`publish` reads only an accepted conversion, and only the files `report.json` lists with the SHA-256 recorded there:
a file edited, missing or added since `convert` stops it. It then compiles every document, checks every app
document's references against the platform document, and refuses documents that hold a break-glass binding, writing
nothing (`bootstrap` refuses them too). It writes each document as revision 1 through the control
plane's store, so the document's revision history starts there, and only when the document does not exist yet: it
never overwrites 8.0 data.

Writes are one per document. If one fails, the message names the documents already written; run again with
`--resume`, which skips a document already published with the same content and refuses one with different content.
`--key-prefix` is the 8.0 snapshot prefix, the same as `cosec.authorization.snapshot.key-prefix`.

After `publish`, change policies through the control plane only. Running `convert` and `publish` again does not update
documents that exist.

#### Bootstrap the control plane

```bash
cosec-migrate bootstrap --to-redis redis://localhost:6379 [--cluster] [--key-prefix cosec] --platform-admin alice [--force]
```

The control plane authorizes its own writes with the policies in the snapshot, so with no administrator nobody may
write. `bootstrap` writes only the platform tenant's document, `Tenant((platform))`; the platform document itself
comes from `publish`, so publish first. `bootstrap` binds principal `alice` of the platform tenant to the built-in `cosec:platform-admin` policy, in the
platform tenant's document, and records it as a revision of kind `BOOTSTRAP`. It refuses when that document already
binds someone to `cosec:platform-admin`; `--force` is for the recovery where every one of them is lost. Anyone with
the Redis credentials can write anyway; `bootstrap` only leaves a record of it.

#### Repair the back-reference index

```bash
cosec-migrate repair-references --to-redis redis://localhost:6379 [--cluster] [--key-prefix cosec]
```

The control plane keeps an index of which documents refer to which policy, so that a platform change cannot remove a
policy a document still uses, nor add one whose id a tenant document uses ([Storage](./storage.md#redis-keys)).
`repair-references` reads the head of every document the store lists and adds whatever the index misses; it prints
how many documents it visited and how many references it added. It only adds, so it may run while the control plane
runs, and running it again adds nothing: an entry the index has stays, and an extra one blocks a removal at most. A
document whose head cannot be read is printed as `FAILED <doc>: <reason>`, the others are still visited, and the
command exits with `1`.

Run it once every control-plane instance and every `cosec-migrate` binary that writes to this Redis runs this
version, not during a rolling upgrade, and again after a downgrade. While an older instance runs, it decides with the
older analysis itself: what RC1-4 protects does not hold for the changes it checks, and the app documents it publishes
are not listed. Each new instance reconciles the list at startup, and every reclamation pass again: a complete list
that misses an app document with a head is marked incomplete
([Storage](./storage.md#publishing-through-the-control-plane)), so new instances send grants over every app back to
approval. The run of `repair-references` after the upgrade is complete is what ends that.

It then lists every app document among the documents in the app list and marks the list complete, printing
`The app list is complete: <n> app(s).`; until then the grant analysis takes every grant over every app as
unknown. If the list kept changing under it, it prints `FAILED the app list could not be completed` and exits with
`1`: run it again. The list it completes is as good as `<s>:control:list:documents`, which names every document the
control plane or `cosec-migrate` ever published, as long as that list has not been lost; a document written to Redis
another way is in neither.

#### Rollback

`publish` and `bootstrap` write only 8.0 keys (`<prefix>:snapshot:*` and `<prefix>:control:*`), and 8.0 never reads
or changes the 7.x keys. To roll back, deploy 7.x again: it reads the 7.x data as it was. Changes made through the 8.0
control plane after the switch are not in the 7.x data. Keep 7.x writers stopped from `convert` until you no longer
plan to roll back, or convert again before you publish: a 7.x write after `convert` is not in the converted
documents.

The 7.x policy history (`<prefix>:policy:history:*`) is neither converted nor deleted. Export it yourself if you need
it.

### What 8.0 decides differently

In 8.0 these are the rules; each has an id in the conformance suite. Without errors, missing data or the shapes below,
8.0 makes the same decisions as 7.x on converted data. The decision algorithm itself is described in the
[security model](../guide/security-model.md).

| Rule | 8.0 | 7.x | Affected 7.x shapes |
|---|---|---|---|
| **C1a** (`ALG-4`) | An ALLOW statement (or its policy condition) that fails counts as no match: another ALLOW may still permit, otherwise the implicit deny (`401`/`403`). | `500`. | An ALLOW whose condition could fail (an expression, a regular expression, a template, a custom matcher). The only rule that can let a request through that 7.x refused with `500`. |
| **C1b** (`ALG-1`) | A matching DENY decides (`403`/`401`) whatever errors, missing or unavailable data exist elsewhere. | `500` when the failure came first in layer order. | Every DENY statement. |
| **C1c** | A policy condition is evaluated when one of its statements is first visited; if it fails, its DENY statements fail and its ALLOW statements do not match. | Every policy condition of a layer evaluated first; a failure was `500` for the layer. | A policy-level `condition` other than `all`. |
| **C1d** (`BLK-1`) | A blacklisted principal, and a break-glass principal, still need the platform document; while it is unavailable the request is `503`. | Blacklist and root read no policy. | Root, the blacklist. |
| **C2** (`BRK-1`) | A matching DENY denies a break-glass principal too. | Root skipped every policy. | Root, together with any global DENY. |
| **C3** (`SCP-1`) | A declared space (`CoSec-Space-Id`, not empty) must be one where the principal has a role; otherwise the request is denied with `scope-rejected` (`403`), or `401` when anonymous. It never falls back to the default space. | Evaluated in the declared space: role permissions were empty there, policies applied as usual. | Role grants in a space other than the default one; clients that send a space. |
| **C4** (`SCP-2`) | With `cosec.authorization.scope.app-id` set, a request declaring another app is denied (`scope-rejected`); without a declaration the host's app applies. | The declared app always applied. | Hosts that serve one app. |
| **C5** (`BND-2`) | A principal attaches only attachable policies of its own tenant's document or of the platform. | Any principal could attach any policy. | Principal policies, with `--partition by-owner` only. The default partition keeps 7.x's behavior. |
| **C6** | A role grant that names a permission its app does not define is rejected by `convert`. | `500` on the request that reached it. | Grants with stale permission ids. |
| **D1** | `inRole: r` in an ALLOW matches only a role in effect in the requested space; `inRole: r@s` in an ALLOW matches only while the request is in space `s`; `inRole: r` in a DENY matches the role held in any space. | Literal role match, a bare role also matching `r@<requested space>`. | `inRole` conditions. A DENY never narrows and an ALLOW never widens. |
| **D2** (`CND-1`) | An absent attribute equals no value, not the empty string. | An absent part read as `""`. | `eq` against `#{principal.id}`, `#{principal.attributes['k']}` or `#{tenant.tenantId}`. In an ALLOW, an absent part no longer matches an empty attribute (narrower). In a DENY, `convert` keeps 7.x's reading by adding the 7.x cases. |
| **D3** | No regular expressions, so no regex timeouts. | A `RegexTimeoutException` was an implicit deny. | `regular` conditions; only literal, literal-prefix and choice-of-literals patterns translate. |

#### Anonymous requests that declare a space are 401

Rule `SCP-3`: an anonymous request with a non-empty `CoSec-Space-Id` (header or query) is answered `401`
`unauthorized`, even on a path everyone may call. An anonymous principal has no roles, so it is a member of no space
(C3). A request whose token was rejected is anonymous too, and gets `401` with the token's problem type. Clients that
send `CoSec-Space-Id` on public endpoints must stop sending it there.

#### Root becomes break-glass

`cosec.authorization.root-enabled` and the root principal are gone. The 8.0 counterpart is a break-glass binding:
bounded in time (at most 24 hours, `cosec.control.break-glass.max-duration`, default 4 hours), bound by every DENY
(C2), and allowed to permit only when a REQUIRED decision listener records the request (otherwise `503`, rule
`OBS-2`). Request one through the control plane when you need it, where two people take part. `cosec-migrate` writes
no break-glass binding: `convert` does not convert 7.x root, and `--root-enabled`, `--root-id`, `--root-expires-at` and
`publish --break-glass-max-duration` are gone (passing one is a usage error that says so).

#### Path templates in actions

A 7.x `path` action with a `#{...}` template, such as `/users/#{principal.id}/**`, is rejected by `convert`. In 8.0 a
route map names the resource and its id (`"path": "/users/{userId}"`, `"resource": "user/{userId}"`), and a condition
compares the resource attribute with the principal. See the [policy language](../policies/language.md). `#{...}`
templates inside `eq` conditions translate (D2).

#### Rate limit rules become obligations

7.x rate limit rule files (`cosec.limiter.*`) are gone. `convert --rate-limit-rules` translates the rules into the
platform document's rate limits. A rule applies to a request the policies permit, as in 7.x: the first exhausted rule
answers `429`, and denied requests spend nothing. A rule with `strictFailure` whose limiter fails now answers `503`
instead of `429` (R3). The Redis limiter uses the same keys (`cosec:rate-limiter:<rule>:<bucket>:<window>`) when a
`ReactiveStringRedisTemplate` bean exists; otherwise each instance counts alone. See
[Rate limiting](../policies/rate-limiting.md).

### Responses and status codes

#### Error bodies are problem+json

Every response that does not proceed has `Content-Type: application/problem+json`, a `CoSec-Request-Id` header, and
this body (members always in this order; `traceId` only when tracing is on):

```json
{"type":"https://cosec.ahoo.me/problems/forbidden","title":"Forbidden","status":403,"code":"FORBIDDEN","requestId":"..."}
```

The 7.x body `{"authorized":false,"reason":"..."}` with `application/json` is gone. Explicit and implicit denies are
no longer told apart in responses: both are `unauthorized` or `forbidden`, so a response never tells a caller that a
DENY exists. The audit event keeps the difference. A `400` now has a body and a `CoSec-Request-Id` header too; in 7.x
it had neither. The full status table is in [Web integration](../integration/web.md).

| 7.x status and `reason` | 8.0 status and `code` |
|---|---|
| `400`, no body | `400` `INVALID_REQUEST` |
| `401` `Explicit Deny` or `Implicit Deny` | `401` `UNAUTHORIZED` |
| `401` `Token Expired` | `401` `TOKEN_EXPIRED` |
| `401` `Token Invalid` (an invalid token or API key) | `401` `TOKEN_INVALID` |
| `401` `Token Invalid` (a revoked token) | `401` `TOKEN_REVOKED` |
| `403` `Explicit Deny` or `Implicit Deny` | `403` `FORBIDDEN`; `403` `SCOPE_REJECTED` when the declared app or space is refused (C3, C4) |
| `429` `Too Many Requests` | `429` `TOO_MANY_REQUESTS`, same `RateLimit-*` and `Retry-After` headers |
| `429` `Too Many Requests` from a `strictFailure` rule whose limiter failed | `503` `UNAVAILABLE` (R3) |
| `500` `Implicit Deny` (a decision could not be made) | `503` `UNAVAILABLE` (R1, R2) |
| `500` `Implicit Deny` (any other error) | `500` `INTERNAL_ERROR` |

A rejected credential is evaluated as anonymous, as in 7.x: a request the policies permit for anonymous callers is
still `200`; when it is denied, the problem names why the credential was rejected.

#### 503 replaces 500 when no decision can be made

| Rule | 8.0 | 7.x |
|---|---|---|
| **R1** (`PIP-2`) | No decision could be made (a document unavailable or not loaded in time, memberships unavailable, a blacklist that cannot be read, an external attribute that cannot be fetched): `503`. | `500`. |
| **R2** | The authenticator's own dependency (a revocation store or an API key store that cannot be read) failed: `503`. | `500`. |
| **R3** | A rate limit rule with `strictFailure` could not be counted: `503`. | `429`. |

`500` remains for an exception nothing models, a defect. A `503` never carries `Retry-After`.

#### Ambiguous CoSec parameters are 400

Rule `SCP-4`: when `CoSec-App-Id`, `CoSec-Space-Id` or `CoSec-Device-Id` arrives with two different non-blank values,
between the header and the query string or within either, the request is `400`. 7.x did not refuse such a request.

#### Spring MVC reads query parameters from the query string only {#spring-mvc-reads-query-parameters-from-the-query-string-only}

On Spring MVC, 7.x read a query parameter with `HttpServletRequest.getParameter`, which also returns the fields of an
`application/x-www-form-urlencoded` body. So for a form POST, the container parsed and consumed the body inside
CoSec's filter, and a body field counted as a query parameter. 8.0 reads the URL query string only, on every adapter,
and never touches the body
([Form POST body is empty after CoSec on Spring MVC](./troubleshooting.md#form-post-body-is-empty-after-cosec-on-spring-mvc)).
A form field no longer sets `CoSec-App-Id`, `CoSec-Space-Id` or `CoSec-Device-Id`, nor the `authorization` token
read with `cosec.pipeline.allow-query-token=true`. Clients that send these as form fields must send them as headers
or in the query string.

### Authentication

#### Token revocation is always on {#token-revocation-is-always-on}

`cosec.jwt.token-revocation.enabled` is gone. With JWT enabled, every token is checked for revocation, and startup
fails when nothing can answer that check: add `cosec-cocache`, which brings the Redis revocation store, or register a
`TokenStore` and a `RevocationReader` bean of your own. In 7.x, without `cosec-cocache` the starter used a store that
records nothing, so logout silently did nothing. A service that only issues tokens and never verifies them can set
`cosec.jwt.enabled=false` instead ([Logout and revocation](../integration/authentication.md#logout-and-revocation)).
Revocations written by 7.x stay in force: 8.0 reads them under the same keys (`<prefix>:token:revoked:*`). An error
the revocation check reports is `503`, never an accepted token (`PIP-7`).

#### Tokens and API keys

- Tokens issued by 7.x keep working: 8.0 reads the same claims (`roles`, `policies`, `attributes`, `tenantId`), and
  role claims as 7.x did (`role` in the default space, `role@space` in that space only, rule `MEM-3`).
- **A token with an `act` claim is now refused** as `token-invalid` (`IDN-4`). 7.x ignored the claim, so an RFC 8693
  delegation token was read as its `sub` and the actor got the subject's permissions. CoSec never issues such
  tokens; a verifier that accepts tokens from another issuer must not pass them on until delegation is supported
  ([Authentication](../integration/authentication.md#tokens)). A deny is audited with the reason code
  `DELEGATION_UNSUPPORTED`; on a permit the audit records `ALLOW`, and the reason appears only in the decision event's
  `subject.rejected`. Refreshing or revoking such a token fails too.
- **A `tenantId` claim that is not a string**, or an `attributes` claim that names `apiKeyId` (set by CoSec for API
  keys only), makes the token `token-invalid`; 7.x read such a tenant as none. **`cosec.jwt.secret`** must be at least
  32, 48 or 64 UTF-8 bytes for `hmac256`, `hmac384` or `hmac512`, or startup fails.
- **Break-glass, 7.x's root,** applies only to a token CoSec's own `JwtTokenVerifier` verified, never to a token of a
  custom `TokenVerifier` or to a principal another authenticator accepted (`BRK-8`).
- **Tenant switch and social login.** `AbstractSwitchTenantAuthentication.switchTenant` answers the user's
  `TenantMembership` in the target tenant instead of a principal, so the new token never carries the previous
  tenant's roles. `DirectSocialUserPrincipalConverter` signs only `cosec_oauth_provider` and `cosec_oauth_user_id`,
  not the provider's user info ([Authentication](../integration/authentication.md#tenants)).
- `cosec.api-key.header` is now `cosec.pipeline.api-key-header` (default `X-API-Key`), and no document may read the
  header it names (`CND-5`).
  `cosec.api-key.store-blocking` is gone: the Redis key reader never blocks. A request that carries an API key is
  judged by the key alone, even if it also carries a token (`PIP-8`).
- `cosec.authorization.allow-query-token` is now `cosec.pipeline.allow-query-token` (default `false`).
- Custom 7.x `SecurityContextParser` beans are not used. Register an `Authenticator` bean instead
  ([Authentication](../integration/authentication.md)).
- `cosec.authentication.enabled` is now `cosec.authentication.login.enabled`, and `cosec.authentication.social.*` is
  now `cosec.authentication.login.social.*`. The switch turns the login beans on or off (`TokenCompositeAuthentication`,
  your `Authentication` beans, social login); requests are authenticated by the pipeline whatever it says.
- A token refresh applies the same `cosec.authentication.revocation.skew` (default `5s`) as a request: a token issued
  up to the skew after a subject revocation can no longer be refreshed.

#### API keys narrow, and memberships decide {#api-keys-narrow-and-memberships-decide}

In 7.x an API key granted its own roles, policies and attributes. In 8.0 a key grants nothing: its memberships are its
principal's, from the `MembershipProvider`, narrowed to the key's `roles` (RFC 0009 §4,
[API keys](../integration/authentication.md#api-keys)). What breaks:

- **`MembershipProvider` answers `MembershipAnswer`** (`Known(memberships)` or `Unknown`), and so does
  `MembershipLoader.load`. A provider written for the 8.0 milestones, which answered `Memberships`, no longer
  compiles: decide what each principal it does not know is, `Unknown`, and stop answering empty memberships for it,
  which would keep that principal's API keys valid. `Memberships` has `active`, false for a deactivated principal.
  An unknown or deactivated principal's API keys, and its tokens marked `cosec.membership: provider`, are refused as
  `token-invalid` (`IDN-3`). [Extending](../architecture/extending.md#memberships) has the contract.
- **Stored keys.** The Redis format changes. A key stored before 8.0 is refused when it is read: a request with it is
  `503` and the log names `cosec-migrate api-keys`. Convert the keys before switching (below).
- **A provider is required.** `cosec.api-key.enabled=true` without a `MembershipProvider` bean fails startup
  (`IDN-6`). `cosec.api-key.enabled` is `true` or `false`; `on`, `yes` or `1` fail startup.
- **A cap narrows principal bindings and break-glass too.** A key with `roles` gets no grant from its principal's
  principal bindings and attached policies, though their DENYs still apply to it, `roles: []` only what `Anyone`
  bindings give, and no API key ever gets break-glass (`BND-9`, `BRK-8`).
- **Configured keys** (`cosec.api-key.keys[]`): `tenant-id` is required, and each key sets exactly one of `roles`
  (its cap; `[]` for no roles) and `uncapped: true`. `policies` and `attributes` are gone; a key that still sets them
  stops startup, and so does one that sets neither or both of `roles` and `uncapped`. A 7.x key held only the roles
  and policies it named; to keep a key with none, give it `roles: []`.
- **`ApiKey`** is a plain class `ApiKey(id, principalId, tenantId, roles, expiresAt, enabled)`: `policies`,
  `attributes` and `toPrincipal()` are gone, `tenantId` is required and `roles: Set<String>?` is the cap, with no
  default.

**Converting stored keys.** `cosec-migrate api-keys` reads every key under `--api-key-prefix` (default
`cosec:api-key`) and writes `api-keys-report.json` to `--out`. Export what each principal holds now, as a JSON array
of `{"tenant", "principal", "roles": ["role", "role@space", ...]}`, and pass it as `--memberships`:

```bash
cosec-migrate api-keys --redis redis://localhost:6379 --memberships ./memberships.json --out ./api-keys --dry-run
```

A key goes one of three ways (`keys[].mode`):

| Mode | When | The key afterwards |
|---|---|---|
| `in-place` | `--memberships` shows its principal holds every role the key named, and the key attached no policy | stays with its principal, capped to those roles |
| `own-principal` | otherwise, by default | moves to a workload principal `apikey:<key id>` in its tenant, uncapped; `memberships` lists the key's roles for that principal, `principalBindings` its attached policies bound to it by name |
| `granted` | only for the principals `--grant-to-principal <ids>` (or `all`) names | stays with its principal, capped to its roles and a role of its own (`--role-prefix`, default `api-key.`, plus the key id), which `roleBindings` binds to its attached policies; the principal is granted the union of its usable keys' caps, and `memberships[].newlyGained` says what it did not hold |

No key gains access, and no principal does unless named in `--grant-to-principal`. What a key loses is listed per
key: `lost`, its principal's principal and break-glass bindings, which a key no longer reaches; and for `own-principal`,
`changes`, what depends on the principal's id (the audit subject, conditions on `subject.id`, rate limit buckets keyed
by principal, the `sub` of a forwarded `CoSec-Identity`).

A key is rejected, and then the command exits `1` and writes no key, when it has attributes (a condition may read
them), malformed roles, an id with `@`, `*` or a blank, a role that starts with the role prefix, a per-key role a
document already binds, or an attached policy that does not resolve exactly: missing, not attachable, or defined by
both the tenant and the platform with different content. Such an attachment made every request of the key
indeterminate in 8.0, and dropping it could drop a DENY: fix the documents or the key first. 7.x did not keep key
ids unique, so a key is also rejected when another key of its tenant has the same id (both would become one principal,
or share one per-key role), and an `own-principal` key when its `apikey:<key id>` principal already exists: a document
binds it by name, or `--memberships` lists it. The key would gain what that principal holds. A `--memberships` file
that lists a principal twice is refused. A key without a tenant goes to the default tenant (`defaultTenant`).

An `own-principal` key with no roles is still listed in `memberships`, with `roles: []`. Its principal must exist in
your `MembershipProvider` all the same, with no roles: a principal the provider does not know is refused, so every
request with the key would be `401`.

1. Run with `--dry-run` and read the report.
2. Grant the `memberships` in your `MembershipProvider`'s source, and add the `principalBindings` and `roleBindings`
   through the control plane (a change request).
3. Run again without `--dry-run`. Each key is rewritten only while it still holds what was read, so a key revoked or
   disabled in between is never brought back: the report lists it under `changedSinceRead`, and the command exits `1`
   and asks to be run again. Keys already converted are left as they are, so the command can be repeated.

A key converted before step 2 is complete is refused or narrower, never wider.

#### Inject mode becomes identity propagation

7.x inject mode (`cosec.inject.enabled=true`) let a service behind the gateway parse the forwarded JWT without
verifying it. 8.0 has no mode that reads a token without verifying it. Instead the gateway forwards the identity it
verified, and the services behind it trust the gateway:

| Where | 7.x | 8.0 |
|---|---|---|
| Gateway | Forwarded the client's `Authorization` header. | Nothing to set: on a permit with a principal it sets `CoSec-Identity`, and it always strips the client's own. `cosec.gateway.identity.claims` names principal attributes to forward; route metadata `cosec-identity: false` opts a route out. The `Authorization` header is removed from forwarded requests unless `cosec.gateway.identity.forward-authorization=true` ([Gateway identity](./configuration.md#gateway-identity)). |
| Each service behind it | `cosec.inject.enabled=true` | `cosec.pipeline.mode=gateway` ([Services behind the gateway](../integration/web.md#services-behind-the-gateway)): no keys, policies, snapshot or Redis; set `cosec.jwt.enabled=false` unless it issues tokens. Only the gateway may be able to reach the service (network policy, a private network, mTLS); anyone else who can is whoever they claim. |
| Application code | Read the caller from `SecurityContext`. | The same. Attached policies are not forwarded, and the roles are those of the scope the gateway decided in. |

A credential that never expires, such as an API key without `expires-at`, is not forwarded: the gateway answers its
permits `500`. Give such keys an expiry, or opt their routes out.

A service behind a CoSec gateway that verifies tokens itself no longer receives the `Authorization` header. Either set
`cosec.gateway.identity.forward-authorization=true` on the gateway, or switch the service to
`cosec.pipeline.mode=gateway`. A token in a cookie is still forwarded.

### Storage and Redis

#### Redis keys

8.0 stores documents under `<prefix>:snapshot:*` and the control plane's data under `<prefix>:control:*`,
`<prefix>:candidate:*` and `<prefix>:shadow:*`. It does not read the 7.x keys `<prefix>:policy:*`,
`<prefix>:global:*`, `<prefix>:app:permission:*`, `<prefix>:app:role:permission:*` and `<prefix>:meta:schema`. A
tool that read or wrote those keys directly must use the control plane. Give only the control plane's Redis account
write access to `<prefix>:snapshot:*` and `<prefix>:control:*`. All keys: [Storage](./storage.md).

#### Startup fails when Redis's eviction policy cannot be read {#startup-fails-when-redis-s-eviction-policy-cannot-be-read}

At startup CoSec reads Redis's `maxmemory-policy` and refuses to start unless it is `noeviction`, because an evicted
platform document could silently drop its DENY statements. When `CONFIG GET` fails or is not allowed, as on some
managed Redis services, startup fails too, and also when the CoCache backend is a wrapper or a custom backend it
cannot read. If you have set `noeviction` yourself, set `cosec.authorization.snapshot.eviction-check=warn`: startup
goes on and logs a warning that starts with `!!!`.

#### Each instance decides from a snapshot

- **No per-request Redis reads.** Each instance loads the platform document, and the app and tenant documents its
  requests need, compiles them, and checks each loaded document with a conditional read every
  `cosec.authorization.snapshot.revalidate-interval` (default `10s`). Redis transfers a document only when it
  changed.
- **During a Redis outage, installed documents keep deciding** as last confirmed, so a platform DENY still holds. A
  request that needs a document not yet loaded waits up to `cold-load-timeout` (default `2s`) and is then `503`.
  In 7.x, requests started failing with `500` once a local copy expired (5 minutes by default). For a tenant listed in
  `cosec.authorization.snapshot.fail-closed.tenants`, a request whose documents were last confirmed longer ago than
  `fail-closed.max-lag` is `503` instead, unless a DENY matches.
- **Readiness waits for the snapshot.** The platform document, then `prewarm-apps` and `prewarm-tenants`, load while
  the application starts, and readiness refuses traffic until all of them are installed; liveness is not affected.
  The first request for an app that is not loaded compiles its whole document; list busy apps in `prewarm-apps`.
- **A missing platform document fails closed.** Every request CoSec has to decide is `503` until the platform document
  is published.
- **Without `cosec-cocache`** the starter uses an in-memory store that starts empty, so every request is `503` until
  the application puts documents into it. Local policy files are not loaded any more
  (`cosec.authorization.local-policy.*` is gone).
- A read failure surfaces as `SnapshotUnavailableException` (its cause the original exception) and a `503`, instead of
  7.x's `CacheReadException` and a `500`.

See [Storage](./storage.md) for publishing, rollback of documents and behavior when Redis is unavailable.

### Modules, artifacts and packages

**The starter is `cosec-spring-boot`, and it has no capabilities.** 7.x's `cosec-spring-boot-starter` offered each
integration as a Gradle capability (`webflux-support`, `cache-support`, ...). In 8.0 the artifact is
`me.ahoo.cosec:cosec-spring-boot`, and an application adds the modules it needs next to it, the same way with Gradle
and Maven. The starter wires every module it finds on the classpath.

```kotlin
// 7.x
implementation("me.ahoo.cosec:cosec-spring-boot-starter") {
    capabilities { requireCapability("me.ahoo.cosec:webflux-support") }
}
implementation("me.ahoo.cosec:cosec-spring-boot-starter") {
    capabilities { requireCapability("me.ahoo.cosec:cache-support") }
}
// 8.0
implementation("me.ahoo.cosec:cosec-spring-boot")
implementation("me.ahoo.cosec:cosec-webflux")
implementation("me.ahoo.cosec:cosec-cocache")
implementation("org.springframework.boot:spring-boot-data-redis")
implementation("me.ahoo.cocache:cocache-spring-boot-starter")
```

| 7.x capability | 8.0 module |
|---|---|
| `webflux-support`, `webmvc-support`, `gateway-support` | `cosec-webflux`, `cosec-webmvc`, `cosec-gateway` |
| `cache-support` | `cosec-cocache`, with `spring-boot-data-redis` and `cocache-spring-boot-starter` |
| `oauth-support` | `cosec-social` |
| `opentelemetry-support`, `ip2region-support` | `cosec-opentelemetry`, `cosec-ip2region` |
| `micrometer-support` | `cosec-micrometer`, with `micrometer-core` |
| `openapi-support` | `cosec-openapi`, with `spring-boot-starter-actuator` and `springdoc-openapi-starter-common` |
| `kafka-support` | `cosec-kafka`, with `spring-boot-starter-kafka` |
| (new in 8.0) | `cosec-control`, the control plane, with `cosec-cocache` |
| (new in 8.0) | `cosec-cosid`, CoSec's ids from CosId 5 (see below) |

The full list is in [Modules](../guide/introduction.md#modules). A module a turned-on feature needs is checked at
startup: a web host without its adapter module, a gateway without `cosec-gateway`, `cosec.api-key.store=redis` without
`cosec-cocache` or `cosec.audit.kafka.delivery=required` without `cosec-kafka` fails and names the artifact
([Startup fails on a missing module](./troubleshooting.md#startup-fails-on-a-missing-module)).

**`cosec-core` is gone.** Its code is split by what it is: the ports an application implements are in `cosec-spi`
(`me.ahoo.cosec.spi.*`); the documents, snapshots, token verification and the logging audit sink in `cosec-runtime`
(`me.ahoo.cosec.runtime.*`); login and token issuing in `cosec-login` (`me.ahoo.cosec.login`). The starter brings all
three. A library that only implements a port depends on `cosec-spi`. The packages that moved:

| 7.x package | 8.0 package (module) | Types |
|---|---|---|
| `me.ahoo.cosec.api.authentication`, `me.ahoo.cosec.authentication` | `me.ahoo.cosec.login` (`cosec-login`) | `Authentication`, `AuthenticationProvider`, `Credentials`, `CompositeAuthentication`, `DefaultAuthenticationProvider` |
| `me.ahoo.cosec.authentication.token` | `me.ahoo.cosec.login.token` (`cosec-login`) | `AbstractRefreshTokenAuthentication`, `AbstractSwitchTenantAuthentication` |
| `me.ahoo.cosec.token` | `me.ahoo.cosec.login.token` (`cosec-login`) | `TokenConverter`, `TokenCompositeAuthentication`, `RefreshTokenMismatchException` |
| `me.ahoo.cosec.token` | `me.ahoo.cosec.spi.token` (`cosec-spi`) | `TokenVerifier`, `TokenStore`, `TokenRevoker`, `RevocationReader`, `TokenVerificationException`, `TokenExpiredException`, `TokenRevokedException` |
| `me.ahoo.cosec.token` | `me.ahoo.cosec.runtime.token` (`cosec-runtime`) | `SimpleAccessToken`, `SimpleCompositeToken`, `SimpleTokenPrincipal`, `SimpleTokenTenantPrincipal`, `RevocableTokenVerifier` |
| `me.ahoo.cosec.principal` | `me.ahoo.cosec.api.principal` (`cosec-api`) | `SimplePrincipal`, `SimpleTenantPrincipal` |
| `me.ahoo.cosec.tenant` | `me.ahoo.cosec.api.tenant` (`cosec-api`) | `SimpleTenant` |
| `me.ahoo.cosec.context` | `me.ahoo.cosec.runtime.context` (`cosec-runtime`) | `SimpleSecurityContext` |
| `me.ahoo.cosec.context` | `me.ahoo.cosec.servlet` (`cosec-webmvc`) | `SecurityContextHolder` |
| `me.ahoo.cosec.context.request` | `me.ahoo.cosec.runtime.http` (`cosec-runtime`) | `RemoteIpResolver`, `XForwardedRemoteIpResolver` |
| `me.ahoo.cosec.api.audit` | `me.ahoo.cosec.spi.audit` (`cosec-spi`) | `AuditEventSink` |
| `me.ahoo.cosec.audit` | `me.ahoo.cosec.runtime.audit` (`cosec-runtime`) | `LoggingAuditEventSink`, `AuditEventData` |
| `me.ahoo.cosec.apikey` | `me.ahoo.cosec.spi.apikey` (`cosec-spi`) | `ApiKey`, `ApiKeyReader`, `ApiKeyStore`, `InMemoryApiKeyStore` |
| `me.ahoo.cosec.limiter` | `me.ahoo.cosec.spi.ratelimit` (`cosec-spi`) | `RateLimit`, `RateLimitResult`, `RateLimiter` ([below](#removed-and-replaced-apis)) |
| `me.ahoo.cosec` | `me.ahoo.cosec.model` (`cosec-model`) | `CoSecException` |
| `me.ahoo.cosec` | `me.ahoo.cosec.api` (`cosec-api`) | `Delegated` |

The 8.0 SPIs live in the same places: `MembershipProvider` and `MembershipClaims` in `me.ahoo.cosec.spi.membership`,
`SnapshotDocumentStore` and `InMemorySnapshotDocumentStore` in `me.ahoo.cosec.spi.snapshot`, `AttributeProvider` in
`me.ahoo.cosec.spi.attribute`, `Authenticator` in `me.ahoo.cosec.spi.authn`; `SnapshotSource` is in
`me.ahoo.cosec.runtime.snapshot`, the document codec and compiler in `me.ahoo.cosec.runtime.document` and route maps
in `me.ahoo.cosec.runtime.route` ([Extending](../architecture/extending.md#extension-points)).

**`CoSecJsonSerializer` is gone.** CoSec's Jackson mapper is internal to CoSec, and no public signature of the core modules
carries a Jackson type. Serialize your own types with your own mapper; read and write policy documents with
`PolicyDocumentCodec`. The mapper's configuration did not change, so documents, tokens and records already stored in
Redis or sent to Kafka read as before. A declaration marked `@InternalCoSecApi` is public only so CoSec's modules can
call each other: it has no compatibility promise and can change in any release, and the compiler refuses a call to it
without an explicit opt-in. Do not opt in from an application.

**`KafkaAuditEventSink` takes an `Executor`.** Its third parameter was a Reactor `Scheduler`; it is now a
`java.util.concurrent.Executor` (by default its own single thread with a bounded queue).

**CoSec no longer brings CosId.** In 7.x, `cosec-core`, `cosec-jwt` and `cosec-social` had `cosid-core` in their API,
and CoSec's platform, `cosec-dependencies`, imported `cosid-bom`. In 8.0 only `cosec-cosid` depends on CosId, and no
CoSec BOM imports it or sets its version, so an application can use any CosId version, or none, and declares it, and
its version, itself. CoSec makes its
ids through its own port, `IdGenerator` (`me.ahoo.cosec.spi.id`, in `cosec-spi`). Without a bean of yours they come
from `RandomIdGenerator`, random version 4 UUIDs. To keep CosId's ids ([Ids](../architecture/extending.md#ids)):

- **On CosId 5**, add `cosec-cosid`: the starter gives CoSec the ids of the generator `cosec.cosid.generator` names
  (CosId's `default` unless set). CosId 5 itself is a migration of its own: its [4.x to 5.0 guide](https://github.com/Ahoo-Wang/CosId/blob/main/documentation/docs/en/guide/migration/from-4x.md).
- **On CosId 4**, declare one bean:

```kotlin
// CosId 4: the StringIdGenerator the 7.x starter took
@Bean
fun cosecIdGenerator(ids: StringIdGenerator): IdGenerator = IdGenerator { ids.generateAsString() }
```

The OAuth `state` of social login no longer comes from that bean: it is always 128 random bits from a `SecureRandom`,
because the callback relies on it being unpredictable.

### Removed and replaced APIs

Code that uses any of these no longer compiles or is no longer called.

| 7.x | 8.0 |
|---|---|
| `Authorization`, `AuthorizeResult`, `SimpleAuthorization`, the decorator chain (`DecisionAuthorization`, tracing, auditing, metrics, rate limiting, shadow) | The pipeline (`cosec-runtime`). Observe decisions with a `DecisionListener` bean. |
| `SecurityPipeline`, `SecurityDecision`, `RequestParser`, `SecurityContextParser` | The pipeline; `Authenticator` beans for credentials. |
| `CoSecAuthorizationCustomizer` | Beans of `Authenticator`, `DecisionListener`, `AttributeProvider`, `MembershipProvider`, `RateLimiter`. |
| `Policy`, `Statement`, `PermissionVerifier`, `ActionMatcher`, `ConditionMatcher`, `AppPermission`, `AppRolePermission`, `RolePermission` and the rest of `cosec-api`'s policy and permission model | Documents ([policy language](../policies/language.md)). A `Statement` that overrode `verify` has no counterpart. |
| `ActionMatcherFactory`, `ConditionMatcherFactory`, `MatcherFactoryRegistry`, the built-in matchers, OGNL and SpEL | Built-in actions and conditions only. A value a custom condition read comes from an `AttributeProvider` ([Extending](../architecture/extending.md)). |
| `BlockingAware` | None: no condition runs code. Blocking lookups go into an `AttributeProvider`, which suspends. |
| `RootPrincipalMatcher`, `CoSecPrincipal.isRoot`, `root-enabled` | Break-glass bindings ([Root becomes break-glass](#root-becomes-break-glass)). |
| `BlacklistChecker` | A `BlacklistSource` behind an `AttributeProvider` bean (`BlacklistAttributeProvider`). |
| `RequestAttributesAppender`, `GatewayRouteRequestAttributesAppender` | `AttributeProvider`. |
| `PolicyRepository`, `AppRolePermissionRepository`, the Redis repositories and caches, `SnapshotPublisher`, `RedisSnapshotPublisher`, `PolicyHistory`, `RedisPolicyRevisionStore`, writes to `AppPermissionCache`/`RolePermissionCache` | The control plane (library or HTTP API) for writes; `cosec-migrate publish` for the first ones. |
| `LocalPolicyInitializer` and `init-repository`/`force-refresh` | `cosec-migrate convert` and `publish`, or the control plane's declarative `apply`. |
| `CoSecExplainEndpoint` (`cosecExplain` actuator), `AuthorizationExplainer`, `ExplainWarningCode` | The control plane's `explain` (authorized, and for any revision); see [Checks](../integration/checks.md). |
| Shadow mode (`ShadowAuthorization`, `ShadowPolicyProvider`, `cosec.shadow.*` files) | Shadow windows of control-plane changes; see [Testing policies](../policies/testing.md). |
| `AccessAnalyzer` | The control plane's `analyze`. |
| `PolicyTestKit` (`cosec-core`) | `PolicyTestKit` in the `cosec-testkit` module ([Policy test suites](#policy-test-suites)). |
| The 7.x request model of `cosec-api`: `Request`, `Attributes`, `AppIdCapable`, `DeviceIdCapable`, `RequestIdCapable` (with their `AppId`, `DeviceId`, `RequestId` aliases), `COSEC_EXTEND_KEY_PREFIX`, `SpaceIdCapable.SPACE_ID_KEY`; `RequestSecurityContexts` and `InvalidRequestPathException` (`cosec-core`) | None: adapters read `HttpFacts`, and the security context carries the checked `scope`. `SpaceIdCapable` and `SpaceId` remain for `SpacedRoleId`. |
| `InternalIds` (`cosec-api`) | None. |
| `me.ahoo.cosec.limiter.RateLimiter` and its `LocalRateLimiter`, `RedisRateLimiter` (`cosec-cocache`) | `me.ahoo.cosec.spi.ratelimit.RateLimiter`, a suspending SPI; the starter registers its `LocalRateLimiter` or `ReactiveRedisRateLimiter` ([Rate limiting](../policies/rate-limiting.md)). `RateLimit` and `RateLimitResult` remain. |
| `Jwts.toPrincipal(String)`, `Jwts.decode`, `Jwts.verifyTimeClaims` | None: `Jwts` is internal to `cosec-jwt`. Turn a token into a principal with `TokenVerifier.verify`, which checks the signature. |
| `PrincipalConverter`, `TokenVerifier.toPrincipal` | `TokenVerifier.verify`. |
| `AuthorizationChecker.check(...)`: `Mono<Boolean>` | `AuthorizationChecker.check(security, action, resource)`, a suspending function; `cosec-webflux` adds a `Mono<Boolean>` form and `cosec-webmvc` a blocking one ([Checks](../integration/checks.md)). |
| Filters `ReactiveAuthorizationFilter`, `AuthorizationFilter` (servlet), `AuthorizationGatewayFilter`, and their constructors taking an `Authorization` | `PipelineWebFilter`, `PipelineServletFilter`, `PipelineGatewayFilter`, created by the starter. |
| `ReactiveInjectSecurityContextWebFilter`, `InjectSecurityContextFilter` (inject mode) | `cosec.pipeline.mode=gateway` behind a CoSec gateway ([Inject mode becomes identity propagation](#inject-mode-becomes-identity-propagation)). The 8.0 filters put the security context on every permitted request. |
| Path variables left in the caller's security context after authorization | None. Read path variables from your web framework. |
| `cosecPolicyGenerator`, `cosecAppPermissionGenerator` actuator endpoints | `cosecServiceGenerator/routes` (a route map) and `cosecServiceGenerator/app` (an app document). |
| `StringIdGenerator`, `UuidGenerator` (CosId) in `JwtTokenConverter`, `JustAuthProvider` and the starter's beans; in the 8.0 previews also `PipelineComponents.idGenerator` and `IdentityGate` | `IdGenerator` (`cosec-spi`, [Ids](../architecture/extending.md#ids)), `RandomIdGenerator` by default. `JustAuthProvider` takes no id generator: it draws its OAuth `state` from `SecureRandom`. |
| `SocialUserConverter` (`cosec-social`) | None: it is internal to `cosec-social`. Turn a provider's user into your principal with a `SocialUserPrincipalConverter` bean ([Social login](../integration/authentication.md#social-login)). |

Still available: `SecurityContext` (now with `scope`, the validated app, space and roles; read it rather than the
`CoSec-*` headers, which are what the client declared), `getSecurityContext()` on `ServerWebExchange`, Reactor
`ContextView` and `HttpServletRequest`, `AuditEventSink`, `TokenStore`, and the `cosec-api` audit types, some of them
in new packages ([Modules, artifacts and packages](#modules-artifacts-and-packages)).

During the 8.0 previews an application could replace, with beans of its own, the parts that enforce the control plane's
authorization and approvals: `ControlAuthorizer`, `ChangeAuthority`, `Changes`, `ControlPlane` and `ControlHttp`. It no
longer can: a second bean of one of these types fails startup (for `ControlHttp`, where the control routes are served).
`ControlAuthorizer`, `DocumentConfirmation`, `PipelineControlAuthorizer`, `ChangeAuthority`, `Changes`,
`DefaultControlPlane`, `ControlHttp`, `ControlHttpRequest` and `ControlHttpResponse` are `@InternalCoSecApi`: call the
control plane through the `ControlPlane` bean or its HTTP API. `ControlStore`, `ChangeOptions`, the change listeners,
`Revisions`, `ControlSnapshot` and the maintenance beans stay replaceable.

#### Policy test suites

The 8.0 test kit is the `cosec-testkit` module. `PolicyTestKit.load(dir)` reads documents laid out as
`cosec-migrate convert` writes them, and `tests(dir)` returns a JUnit 5 dynamic test for each scenario file, for a
`@TestFactory` ([Testing policies](../policies/testing.md)). A 7.x suite's `policies`, `appPermissions` and role grants
become the converted documents; each of its `cases` becomes one scenario file:

| 7.x case | 8.0 scenario |
|---|---|
| `name` | `description` (and the file name) |
| `principal` | `credential`: `tenant`, `id`, `roles`, `policies`, `attributes` |
| `request` | `request`: `app`, `space`, the `action` and the `resource` (`type`, `id`) as a route map names them; the test kit does not map HTTP requests |
| `expect.result: ALLOW` | `"expect": {"decision": "Permit"}` |
| `expect.result: EXPLICIT_DENY` | `"expect": {"decision": "Deny", "by": {...}}`, `by` naming the deciding statement |
| `expect.result: IMPLICIT_DENY` | `"expect": {"decision": "Deny", "by": null}` |
| `policyId`, `statementName` | `by`: `policy`, `statement` (its index) and `name` |

Only the fields `expect` names are compared.

### Removed and renamed properties

Every 7.x `cosec.*` property not listed here keeps its name and meaning. Every 8.0 property and its default:
[Configuration](./configuration.md).

Every key below fails startup in 8.0, and the message names what replaced it (`UnknownProperties.RENAMED` in the
starter). So does any other `cosec.*` key 8.0 does not read; `cosec.unknown-properties=warn` only logs them
([Unknown properties](./configuration.md#unknown-properties)). A key ending in `.` stands for every key under it.

| 7.x | 8.0 |
|---|---|
| `cosec.authentication.enabled` | `cosec.authentication.login.enabled` |
| `cosec.authentication.social.` | `cosec.authentication.login.social.` |
| `cosec.authorization.allow-query-token` | `cosec.pipeline.allow-query-token` |
| `cosec.api-key.header` | `cosec.pipeline.api-key-header` |
| `cosec.authorization.enabled` | Removed: `cosec.enabled=false` switches CoSec off. |
| `cosec.authorization.local-policy.` | Removed: convert and publish the files with `cosec-migrate` ([Migrating the data](#migrating-the-data)). |
| `cosec.authorization.root-enabled` | Removed: root becomes break-glass ([Root becomes break-glass](#root-becomes-break-glass)). |
| `cosec.authorization.cache.enabled` | Removed: snapshot caching is `cosec.authorization.snapshot.*`. |
| `cosec.authorization.cache.policy.` | Removed: snapshot caching is `cosec.authorization.snapshot.*`. |
| `cosec.authorization.cache.role.` | Removed: snapshot caching is `cosec.authorization.snapshot.*`. |
| `cosec.authorization.cache.history.` | Removed. |
| `cosec.authorization.cache.migration.` | Removed. |
| `cosec.authorization.cache.token.concurrency-level` | Removed: ignored since CoCache 5. |
| `cosec.authorization.cache.token.expire-after-write` | `cosec.authorization.cache.token.local-max-age`, a duration (`30s`). |
| `cosec.authorization.cache.token.expire-unit` | Removed: the durations carry their unit. `expire-after-access` is a duration too; a bare number is still seconds. |
| `cosec.authorization.gateway.enabled` | Removed: the gateway filter comes with `cosec-gateway`. |
| `cosec.limiter.` | Removed: rate limit rules are in the platform document ([Rate limit rules become obligations](#rate-limit-rules-become-obligations)); a different limiter key prefix needs a `RateLimiter` bean of your own. |
| `cosec.shadow.` | Removed: shadow windows are control-plane operations; each instance's part is `cosec.authorization.shadow.*`. |
| `cosec.explain.enabled` | Removed: use the control plane's `explain`. |
| `cosec.inject.enabled` | Removed ([Inject mode becomes identity propagation](#inject-mode-becomes-identity-propagation)). |
| `cosec.api-key.store-blocking` | Removed. |
| `cosec.jwt.token-revocation.enabled` | Removed: revocation is always on ([Token revocation is always on](#token-revocation-is-always-on)). |

`cosec.authorization.cache.key-prefix` and `cosec.authorization.cache.token.*` remain.

New groups: `cosec.pipeline.*` (timeouts, query token, API key header, trace level, mode),
`cosec.gateway.identity.*`, `cosec.unknown-properties`, `cosec.authorization.scope.app-id`,
`cosec.authorization.{membership,attributes,rate-limit}.timeout`, `cosec.authorization.routes.location`,
`cosec.authorization.snapshot.*`, `cosec.authorization.shadow.*`, `cosec.authentication.revocation.skew`,
`cosec.audit.required.timeout`, `cosec.audit.kafka.delivery`, `cosec.observability.listener.*` and `cosec.control.*`.

Two defaults need a decision during the upgrade:

- `cosec.authorization.routes.location` defaults to `classpath:cosec-routes.json`. On a WebFlux or Spring MVC host a
  missing map fails startup; set the property empty to run without one, in which case every request is an action of
  the `http` namespace on its path. A gateway loads no route map.
- `cosec.authorization.scope.app-id` is unset. Set it on a host that serves one app (rule C4).

Two settings changed during the 8.0 previews:

- `cosec.control.http.base-path` names where the control API is served, without its version: the routes are under
  `{base-path}/v1`, so the default `/cosec/control` serves `/cosec/control/v1`. A value that ended in `/v1` drops it.
- The CoSec gateway server reads only `application.yaml`: it no longer starts a Spring Cloud bootstrap context. A
  deployment that configured it through `bootstrap.yaml`, such as Spring Cloud Config or Nacos, moves that into
  `application.yaml`, with `spring.config.import`.

### Observability changes

- **Audit events** are written for every outcome, including `400`, cancelled requests, authentication failures and
  errors, and `kind` says which (`DECIDED`, `BAD_REQUEST`, `AUTHENTICATION_UNAVAILABLE`, `CANCELLED`, `ERROR`). New
  fields: `snapshotVersion` (the revisions read), `binding` (the binding the deciding statement applied through, or
  `attached:<policy>`), `breakGlass`. New values: decision `INDETERMINATE`; reason codes `INDETERMINATE`,
  `SCOPE_REJECTED`, `RATE_LIMITER_UNAVAILABLE`, `TOKEN_REVOKED`, `BOUNDARY`. The source types keep their 7.x names:
  a binding of the platform or a tenant document is `GLOBAL_POLICY`, an attachment `PRINCIPAL_POLICY`, a role binding
  of an app document `ROLE_PERMISSION`, the built-in blacklist rule `BLACKLIST`. `elapsedNanos` now covers the whole
  pipeline (authentication, attributes, rate limits), not only the authorization. A 7.x `AuditEventSink` keeps working
  without changes.
- **Metrics**: the `cosec.authorization` timer covers the whole pipeline too. Its `source` tag is now `binding`,
  `attachment`, `builtin`, `boundary`, `scope` or `none` (7.x: `global_policy`, `principal_policy`,
  `role_permission`, `root`, `blacklist`, `none`, `unknown`); `result` adds `indeterminate`, `bad_request` and
  `cancelled`; `cause` is new, on `indeterminate` only. New counters: `cosec.listener.dropped`,
  `cosec.listener.failures`, `cosec.audit.required.failures`. Update dashboards and alerts.
- **Tracing**: the `cosec.authorize` span is created after the decision, from the decision event, so the I/O spans of
  an evaluation are no longer its children, and an audit event's `spanId` is the parent span's id.
- **Kafka audit** is best effort unless `cosec.audit.kafka.delivery=required`; a REQUIRED listener that fails turns
  a permit into `503`.
- **Control-plane changes** are events of their own (`ChangeListener`; logged by default, Kafka with
  `KafkaChangeListener`).
- **Logs**: the `SimpleAuthorization` debug logger is gone; decisions are in the audit log.
- **Threads**: on WebFlux, after a real suspension the downstream chain continues on a `Dispatchers.Default` thread,
  not `boundedElastic`. Code that relies on a thread name or a `ThreadLocal` must not.

See [Observability](./observability.md).

### RFC change lists

The RFCs behind 8.0 list known differences. Each item is covered above, or describes an intermediate state between
7.x and 8.0 that no released version has.

| Item | Where |
|---|---|
| RFC 0002 X1 (layer-by-layer reads) | Intermediate. 8.0 reads no policy per request ([Each instance decides from a snapshot](#each-instance-decides-from-a-snapshot)). |
| RFC 0002 X2 (`BlockingAware` thread switch) | Intermediate. `BlockingAware` is removed ([Removed and replaced APIs](#removed-and-replaced-apis)). |
| RFC 0002 X3 (path variables not left in the caller's context) | [Removed and replaced APIs](#removed-and-replaced-apis). |
| RFC 0002 X4 (custom matchers writing path variables) | Intermediate. Custom matchers are removed. |
| RFC 0002 X5 (`Statement.verify` overrides) | [Removed and replaced APIs](#removed-and-replaced-apis). |
| RFC 0002 X6 (explain output of the 7.x explainer) | Intermediate. The 7.x explainer is removed; `explain` is a control-plane operation. |
| RFC 0002 X7 (`SimpleAuthorization` debug log) | [Observability changes](#observability-changes). |
| RFC 0002 X8 (which throwables the engine catches) | Intermediate. No policy runs code in 8.0; an unmodelled exception is `500` `internal-error`. |
| RFC 0003 X1 (Redis outage) | [Each instance decides from a snapshot](#each-instance-decides-from-a-snapshot). |
| RFC 0003 X2 (I/O shape) | [Each instance decides from a snapshot](#each-instance-decides-from-a-snapshot). |
| RFC 0003 X3 (order of global policies) | When several statements of the same effect match, which one is recorded as deciding (audit `source`, `binding`) can differ from 7.x, whose order was undefined. The decision is the same. |
| RFC 0003 X4 (exception type) | [Each instance decides from a snapshot](#each-instance-decides-from-a-snapshot). |
| RFC 0003 X5 (index entry without content) | `GLOBAL_INDEX_WITHOUT_CONTENT` ([Rejections](#rejections)). |
| RFC 0003 X6 (cold start) | [Each instance decides from a snapshot](#each-instance-decides-from-a-snapshot). |
| RFC 0003 X7 (write API) | [Removed and replaced APIs](#removed-and-replaced-apis). |
| RFC 0003 X8 (first request of a new app) | [Each instance decides from a snapshot](#each-instance-decides-from-a-snapshot). |
| RFC 0004 C1a–C6 | [What 8.0 decides differently](#what-8-0-decides-differently). |
| RFC 0004 §13 X1 (audit source) | [Observability changes](#observability-changes): the source types keep their 7.x names, and `binding` is added. |
| RFC 0004 §13 X2 (three documents per request) | [Each instance decides from a snapshot](#each-instance-decides-from-a-snapshot). |
| RFC 0004 §13 X3 (explain by binding) | The control plane's `explain` ([Checks](../integration/checks.md)). |
| RFC 0004 §13 X4 (writes name a document) | [Removed and replaced APIs](#removed-and-replaced-apis). |
| RFC 0004 §13 X5 (new exceptions and causes) | [503 replaces 500 when no decision can be made](#_503-replaces-500-when-no-decision-can-be-made); [Troubleshooting](./troubleshooting.md). |
| RFC 0004 §13 X6 (`SecurityContext.scope`) | [Removed and replaced APIs](#removed-and-replaced-apis). |
| RFC 0005 D1–D3 | [What 8.0 decides differently](#what-8-0-decides-differently). |
| RFC 0006 R1–R3 | [503 replaces 500 when no decision can be made](#_503-replaces-500-when-no-decision-can-be-made). |
| RFC 0006 X1–X4 (audit, timing, tracing, metrics) | [Observability changes](#observability-changes). |
| RFC 0006 X5 (response body) | [Error bodies are problem+json](#error-bodies-are-problem-json). |
| RFC 0006 X6 (threads) | [Observability changes](#observability-changes). |
| RFC 0006 X7 (API) | [Removed and replaced APIs](#removed-and-replaced-apis). |
| RFC 0006 X8 (configuration) | [Removed and renamed properties](#removed-and-renamed-properties). The listener queues are `cosec.observability.listener.*` ([Configuration](./configuration.md#decision-listeners)). |
| RFC 0007 X1 (write API, policy history) | [Removed and replaced APIs](#removed-and-replaced-apis), [Rollback](#rollback). |
| RFC 0007 X2 (shadow) | [Removed and replaced APIs](#removed-and-replaced-apis), [Removed and renamed properties](#removed-and-renamed-properties). |
| RFC 0007 X3 (explain) | [Removed and replaced APIs](#removed-and-replaced-apis). |
| RFC 0007 X4 (document format) | Intermediate: 7.x has no documents. 8.0 reads only its own format; there is no 8.0 schema marker. |
| RFC 0007 X5 (configuration) | [Removed and renamed properties](#removed-and-renamed-properties). `cosec.policy.limits.*` never existed in 7.x; limits are in the platform document. |
| RFC 0007 X6 (generated JSON schema) | The `schema/` files describe the 8.0 formats ([policy language](../policies/language.md)). |
| RFC 0007 X7 (control-plane audit, `BOUNDARY`) | [Observability changes](#observability-changes). |
| RFC 0007 X8 (Redis namespaces) | [Redis keys](#redis-keys). |
