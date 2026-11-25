---
title: "Upgrading"
description: "What is promised when two 8.x versions run mixed, how to upgrade and roll back a running deployment in what order, what is not supported, and how to upgrade a CoSec 7.x deployment to 8.0: what changes, and how to migrate the data, the code, the configuration and the clients."
---

# Upgrading

For teams upgrading a running 8.0 deployment ([Upgrading a running deployment](#upgrading-a-running-deployment)), and
for teams moving a CoSec 7.x deployment to 8.0. The 7.x part starts with a checklist, then gives the details by area:
the data migration, what 8.0 decides differently, responses, authentication, storage, APIs, properties and
observability. This page is the single home of migration steps. Release notes for every version are on
[GitHub Releases](https://github.com/Ahoo-Wang/CoSec/releases).

A deployment that already runs an 8.0 preview (an `8.0.0-alpha` or `8.0.0-beta`) reads
[Upgrading between 8.0 previews](./upgrading-previews.md) instead of this page.

## Upgrading a running deployment {#upgrading-a-running-deployment}

For an operator moving a running 8.x deployment (a gateway, services behind it, a control plane and Redis) to another
version. This section says what is promised when two versions run side by side, how CoSec keeps that promise, in what
order to upgrade and roll back, what is not supported and what happens then, and how to see a mixed deployment. A move
from 7.x is the next section.

### What is promised {#what-is-promised}

From 8.0.0 GA, two adjacent minor versions of 8.x (8.n and 8.n+1) run mixed, whichever you upgrade first:

- a request that both versions, each run alone, admit is not refused while they run mixed;
- a request that both refuse is not permitted while they run mixed;
- a rollback of one minor version is supported, with the exceptions under [What is not supported](#what-is-not-supported).

Two versions further apart (8.n with 8.n+2) are not promised: finish one upgrade before you start the next. The 8.0
previews (the alphas, betas and release candidates of 8.0.0) promise nothing to each other: each preview's order of upgrade is in
[Upgrading between 8.0 previews](./upgrading-previews.md). A preview before 8.0.0-beta.9 refuses every identity
member it does not know with `400` `invalid-request`, so a service of such a preview behind a newer gateway refuses
what that gateway adds.

### How the promise is kept {#how-the-promise-is-kept}

| What crosses | Who writes, who reads | How an instance reads what a newer one wrote |
|---|---|---|
| The `CoSec-Identity` header, format `v: 1` | the gateway (and `HttpControlService`'s relay) writes, each service in `gateway` mode reads | It ignores a member it does not know, at any level, unless the header's `crit` lists it. A member `crit` lists that it does not know refuses the request with `503` `unavailable`, never read without it (`IDN-41`): once the service is upgraded the same request passes. A malformed `crit` is `400` `invalid-request`; a format version other than `v: 1` is `401` `token-invalid`. Every 8.x service reads a header up to 8 KB, and no 8.x version lowers that; CoSec's writers write at most 6 KB, so what a later version adds fits (`IDN-43`, [Services behind the gateway](../integration/web.md#services-behind-the-gateway)) |
| A record in Redis that carries `format` (sessions, the control plane's and the shadow windows' records) | the instance that wrote it, and every other that reads it | It ignores a member it does not know unless the record's `crit` lists it. A member `crit` lists that it does not know, or a later format, refuses that one record, which it neither reads nor rewrites: the call that read it is `503`, and every other record reads as before (`SNP-27`). Format numbers change only with a major version (`SNP-29`) ([When a reader does not know a record's format](./storage.md#record-formats)) |
| A policy document, `schema: 2` | the control plane and `cosec-migrate` write, every instance reads | Always strictly: it does not install a document it cannot compile (a member it does not know, a repeated key, a wrong type, a reserved character in a pattern), and the requests that need it fail closed: `503`, `DocumentInvalid` in the decision event, unless a DENY matches first. Each instance reports the document format level it reads (`formatLevel`, 1 in 8.0, [the readers of the store](../policies/testing.md#instances)) |
| An API key record, `format: 3` | `RedisApiKeyStore` writes, every instance that authenticates API keys reads | Strictly, as an input of authentication, like a document: a missing cap must never read as "not capped". A key of another format or with a member it does not know is never read without it: the request that presents it fails closed with `503`. A later 8.x release writes a new member only on a key that uses it, so a key that uses nothing new is the same record in both versions |
| A token | the issuer signs, every verifier reads | It reads the claims it knows ([the claims a client may read](../integration/clients.md#the-claims-a-client-may-read)) and ignores the rest; it refuses a token with an `act` claim, a refresh token where an access token belongs, or incomplete session claims |
| What two releases share on one Redis: how each key is derived, what the rate limit, session index and social count scripts store, the invalidation channels | every instance | Frozen within 8.x ([Redis keys](./storage.md#redis-keys), `SNP-26`): instances of two releases count a client once, see each other's revocations and read the same documents. A later 8.x release that counts or derives a key another way uses a new key space |

The rule behind the first two rows: a member that an older reader may ignore, or drop when it rewrites a record, is
one whose absence never widens what is decided or kept. A member whose absence would widen anything, a new
restriction, is listed in `crit`, so an older reader refuses rather than permits. CoSec's 8.0 writers write no `crit`. A
later 8.x release does not write a new header or record member that is a restriction until every instance that reads
the store runs a release that understands it, judged from the releases the instances report (below); until then it
writes what every reader understands, and no request is refused for it. A document is written by a person, not by
CoSec: a later release's control plane does not publish a document that needs a level some instance that reads the
store does not report.

### Order of an upgrade and a rollback {#order-of-an-upgrade-and-a-rollback}

Within the promise any order works. This one keeps the documents readable everywhere and leaves the control plane's
repairs for last:

1. **The data plane: services behind a gateway, the gateway, every other instance.** In any order among them.
2. **Then the control plane, and `cosec-migrate`**, and, once every control-plane instance and every `cosec-migrate`
   that writes to this Redis runs the new version, `cosec-migrate repair-references` once
   ([Repair the back-reference index](#repair-the-back-reference-index)) and `verify-chain` ([Verify the revision chains](#verify-the-revision-chains)).
3. **Publish a document that uses something the new version added only once every instance runs it**, which
   `GET /instances` shows (below). An instance that cannot compile the document does not install it, and fails the
   requests that need it closed; a shadow window that has such an instance reports `CANDIDATE_ERROR` and never closes
   clean, so the change is not published through it ([Testing policies](../policies/testing.md#trying-changes-in-shadow-mode)).
4. **Redis is upgraded as Redis is**, not by CoSec: CoSec's keys live under `cosec.key-prefix`, and a restart or
   failover is covered by [Redis requirements](./storage.md#redis-requirements).

**A rollback** of one minor version goes in the reverse order: the documents first, then the control plane and
`cosec-migrate`, then the data plane. Roll back any document that uses something only the newer version reads: `POST /documents/<doc>:rollback` makes a document what it was at an earlier revision, as a
change that is checked like any other ([Revisions, history and rollback](./storage.md#revisions-history-and-rollback)).
After a downgrade of the control plane or of `cosec-migrate`, run `repair-references` and `verify-chain` again.

### What is not supported {#what-is-not-supported}

Each of these fails closed: an instance refuses what it must understand and does not, and never permits for want of it.

- **An older instance that appears after newer records were written**: a rollback, scaling out with an older image, or
  an instance cut off from Redis for longer than its status lives (an hour, below). The newer side did not count it
  among the readers when it started writing. Such an instance refuses each header or record whose `crit` lists a
  member it does not know, and each record of a later format: the request or call is `503` `unavailable`, counted by
  `cosec.stored.refused{record, reason="later"}` for a stored record and by `cosec.identity.refused{problem="unavailable"}`
  for a header ([Observability](./observability.md#metrics)), and its log says a later version wrote it
  ([A record a later version wrote](./troubleshooting.md#a-record-a-later-version-wrote)). It passes once the instance
  is upgraded again. A process without `cosec-cocache` writes no status, so nothing sees it either: upgrade it before
  anything new is used, or it refuses the same way.
- **An API key that uses something a later release added** is refused with `503` by an instance that does not read
  it. Issue such keys only once every instance runs that release, and before a rollback disable or reissue them.
- **Rolling the data plane back below the document format level of a published document.** An instance that cannot
  compile a document fails the requests that need it (`503`, `DocumentInvalid`). Roll the documents back first, then
  the instances.
- **Clients that do not report a status**: `cosec-control-sdk`, `cosec-fetcher`, the consumers of the Kafka decision
  and change events, and an application that writes `CoSec-Identity` itself. No later release can see them before it
  writes something new, so they rely only on reading leniently: a client ignores the members of an answer it does not
  know ([Clients](../integration/clients.md)). `cosec-control-sdk` reads a document's entries strictly, so that writing
  back what it read never drops a member: an older SDK refuses an entry with a member a later control plane added.

### Seeing a mixed deployment {#seeing-a-mixed-deployment}

Every CoSec process with `cosec-cocache` writes a status with the CoSec release it runs, once as it starts and then
every 10 seconds, or every shadow report interval with shadowing on (`SNP-25`); its startup summary says `status=<instance> (release <v>)`. A status lives an hour after its last
write; an instance is live, for a shadow window and `propagation`, only with a status from the last minute.

- `GET /instances`, on the control API, lists every process that reported in the last hour with its release, and
  counts the releases ([The readers of the store](../policies/testing.md#instances)).
- `cosec.control.instances.releases`, a gauge on the control host, is how many releases those readers ran: above 1 for
  longer than a rolling upgrade takes is an instance of an old release still reading the store
  ([Observability](./observability.md#metrics)).

While two versions run, expect `503` with `DocumentInvalid` for a document an older instance cannot compile, `503`
`unavailable` for what an older instance must understand and does not (above), and `401` `token-invalid` from an
instance that holds another signing secret. Each ends when the last instance runs one version. Keep a mixed deployment
short, and try an upgrade on a copy of the documents with the new version's `simulate` and `explain` before it carries
traffic.

## 7.x to 8.0

8.0 upgrades from 7.x. From 6.x, first follow
[the upgrade guide on the `7.x` branch](https://github.com/Ahoo-Wang/CoSec/blob/7.x/wiki/operations/upgrading.md) to
7.x, including its data migration, then come back here. `cosec-migrate convert` refuses Redis data that is not in the
7.0 layout. From 4.x and 5.x, and from any version whose policies live in your own database, there is one path:
[From 4.x, 5.x and 6.x](#from-4-x-5-x-and-6-x).

**No CoSec data of your own?** An application with no policies and no Redis data of CoSec's to convert (a library built
on CoSec's API, or a service in 6.x inject mode that decides in its own code) has nothing to migrate. From 6.x, skip the
data sections ([Migrating the data](#migrating-the-data), [Rollback](#rollback)) and the 7.x guide's data steps; read
the code and configuration sections: [What 8.0 decides differently](#what-8-0-decides-differently), the
[authentication changes](#authentication), [Modules, artifacts and packages](#modules-artifacts-and-packages),
[Removed and replaced APIs](#removed-and-replaced-apis) and [Removed and renamed properties](#removed-and-renamed-properties).
They name 7.x's classes and keys, which 6.x mostly shares. A service that verifies tokens another service issues,
without policies of its own, is [a scenario of its own](../scenarios/resource-server.md).

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
   `CoSecAuthorizationCustomizer`, root (`root-enabled`), local shadow files and the `cosecExplain` endpoint, and
   [tenant and app ids with `/`](#tenant-and-app-ids-hold-no-slash).
   [Converting](#migrating-the-data) reports every policy that cannot be translated.
3. **Convert the data** with `cosec-migrate convert` and fix every rejection until the conversion is accepted. Read
   the semantic changes in `report.json` against [What 8.0 decides differently](#what-8-0-decides-differently).
4. **Update the dependencies and the code**: move the build and the runtime to JDK 21, replace the starter's
   capabilities with the modules themselves and move imports to the new packages
   ([Modules, artifacts and packages](#modules-artifacts-and-packages)), remove the uses of
   [removed APIs](#removed-and-replaced-apis) and register the 8.0 extension beans instead.
5. **Update the configuration**: rename or remove every [7.x key](#removed-and-renamed-properties), and decide on the
   new ones. A `cosec.*` key 8.0 does not read fails startup: the message lists every such key and links the
   [table of removed and renamed keys](#removed-and-renamed-properties), where you look up each replacement. Startup also fails in three
   common cases: a `classpath:cosec-routes.json` that `cosec.authorization.routes.location` does not name (set the
   property to it, or to empty), JWT without `cosec-cocache` and without a
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
never does. Its contract is the command line: its classes are not a library API, and may change in any release. Use the
tool of the version you deploy. Every [GitHub release](https://github.com/Ahoo-Wang/CoSec/releases) carries it as
`cosec-migrate-<version>.zip`, with `cosec-migrate-<version>.zip.sha256` beside it. It is not on Maven Central. It needs
Java 21 or later:

```bash
sha256sum -c cosec-migrate-8.0.0.zip.sha256   # macOS: shasum -a 256 -c
unzip cosec-migrate-8.0.0.zip
cosec-migrate-8.0.0/bin/cosec-migrate convert ...
```

To build it from the CoSec sources instead, run `./gradlew :migrate:installDist`; the tool is then
`migration/migrate/build/install/cosec-migrate/bin/cosec-migrate`.

Its commands are below. Exit codes: `0` done, `1` the data was rejected, a write failed, `verify-chain` found a
broken chain or `check-patterns` a pattern to rewrite, `2` a usage error.
`publish`, `bootstrap`, `repair-references`, `verify-chain` and `check-patterns` take `--cluster` for a Redis Cluster: `--to-redis` then names seed
nodes, comma-separated (`redis://node-1:6379,redis://node-2:6379`). The other commands read Redis with clients that
do not: `convert`, `partition plan` and `api-keys` ask the node whether it is part of a Redis Cluster and stop before
they read anything when it is, since a scan of one node sees one shard's keys (R2C-30). Each command takes only the options listed for it: another one, `publish --dry-run` for
example, is a usage error before anything is read or written, never ignored.

| Command | What it does |
|---|---|
| `convert` | Reads 7.x data (Redis or exported files), translates it, and writes 8.0 documents and a report into a new directory. Writes nothing to Redis. |
| `publish` | Writes the documents of an accepted conversion to Redis as their first revisions, through the control plane's store. Never touches the 7.x keys. |
| `bootstrap` | Binds one principal of the platform tenant to `cosec:platform-admin`, so the control plane has someone who may write. |
| `partition plan` | Converts twice, with `--partition platform` and `by-owner`, and writes one plan per tenant to submit through the control plane. Writes nothing to Redis. |
| `repair-references` | Adds to the back-reference index what every document's head refers to and the index misses, removes what no head has, and completes the app list. |
| `verify-chain` | Walks each document's revision chain and names a revision whose record is missing, the trace a write lost in a Redis failover leaves, and a revision index that does not follow the head's chain. Writes nothing. |
| `check-patterns` | Names every pattern of the stored heads that the next preview does not compile (`DOC-15`), with where it is and how to write it. Writes nothing. |

#### Convert

```bash
cosec-migrate convert --from-redis redis://localhost:6379 [--key-prefix cosec] --out ./converted
cosec-migrate convert --from-files ./export --out ./converted
```

| Option | Meaning |
|---|---|
| `--from-redis <uri>` | Read 7.x data from this Redis: the policies, the global policy index, app permissions and role grants under `--key-prefix`, and the schema marker `<prefix>:meta:schema`, which must be `7`. Policy history (`<prefix>:policy:history:*`) is skipped. |
| `--from-files <dir>` | Read an export instead: `policies.json` (an array of 7.x policies, each with its `id`), `global-policies.json` (an array of the ids in the global policy index) and `app-role-permissions.json` (one entry per app: `{"appPermission": {...}, "grants": {"admin": ["*"], "auditor@s1": ["invoice.read"]}}`, or `{"appId": "...", "grants": {...}}` for an app without an app permission; `grants` is an object, or absent for no grants, and anything else fails the read, naming `app-role-permissions.json[i].grants`). The three files are required; `rate-limit-rules.json` is optional. Use this for 7.x local policy files: put their contents into `policies.json`. |
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
| `report.json` | `accepted`, the input's SHA-256, counts, `rejections`, `warnings`, `semanticChanges`, the SHA-256 of every file written, and `documentIds`. |

File names are the URL-encoded ids. An id whose encoded name would be longer than 200 characters (30 Chinese
characters are 275) gets a bounded name instead: a prefix of the encoded id, `~` and the SHA-256 of the id. `~` is
never in an encoded name, and `report.json` records in `documentIds` which id each such file holds; `publish` checks
the name against that id. A write that fails removes the files it wrote, so the directory is as new as before and
`convert` can run again.

A rejected conversion writes `report.json` only. The console prints the same counts, warnings and rejections, and says
to remove the directory, or give another `--out`, before you convert again: `convert` writes into a new directory.

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
| `INVALID_ID` | An id 8.0 cannot hold: `*` or empty as an app or space id, `:` in an app id, `@` in a role id, or an unpaired UTF-16 surrogate in any id of a converted document (a JSON escape such as `"\uD800"`, `IDN-10`), located by the document and its JSON path. | Rename it. In 8.0 `*` means any app or space. |
| `SLASH_IN_ID` | A tenant id (a policy's `tenantId` under `--partition by-owner`) or an app id that holds `/`, which no 8.0 tenant or app id can ([below](#tenant-and-app-ids-hold-no-slash)). | Rename the tenant or app in the source system so that its id holds no `/`, then convert again. |
| `DUPLICATE_ID` | The same policy twice in an export, the same app twice in an export (one entry would replace the other, and its grants would be lost), the same permission twice in one app, or two app or tenant ids whose converted file names differ only in case (`Order` and `order`: one file on macOS and Windows). Ids that differ in more than their file names, such as `strasse` and `straße`, are not a collision. | Keep one, or rename. |
| `OWNER_TENANT` | `--partition by-owner`, and a policy whose `tenantId` names no tenant. | Set its `tenantId`, or convert with `--partition platform`. |
| `CONDITION_NOT_TRANSLATED` | A built-in 7.x condition with no exact translation, such as a `regular` that is not a literal, a literal prefix or a choice of literals, or a `path` condition outside a statement whose action is `all`. Also, on a part only 7.x filled (`request.attributes.*` but the gateway's `cosec.audit.routeId`, set by a `RequestAttributesAppender`, and `resource.*`), always absent in 8.0, so a condition on it is a constant there: one that holds on the absent value, in a DENY or an ALLOW (it would hold for every request, where 7.x held it only when the part was empty), and one that does not, in a DENY or negated in one (the DENY would never hold, or hold for every request). Only an ALLOW that can no longer hold translates, recorded as `NOT-IN-8.0`; a comparison against a template (`eq resource.owner #{principal.id}`) is one, since an absent value equals nothing. 7.x's ip2region `ipRegion` (`country|region|province|city|isp`) translates in one shape only, a country prefix (`startsWith "中国|"`, or a `regular` that says it), which becomes `ext.ip2region.country`; any other condition on it is a part only 7.x filled. And a condition that tells method HEAD from GET where a DENY needs it, since 8.0 decides a HEAD request as GET (`not eq GET` in a DENY included). | Rewrite it with the conditions the message names (`eq`, `in`, `startsWith`, ...); a value an appender set becomes an `ext.*` attribute of an `AttributeProvider` (`ipRegion`: `ext.ip2region.*`). |
| `EXPRESSION_CONDITION` | A `spel` or `ognl` condition. | Rewrite it as conditions of the [policy language](../policies/language.md). |
| `CUSTOM_CONDITION` | A condition of a custom `ConditionMatcherFactory`. | Move the value it reads into an `AttributeProvider` and write a condition on that attribute ([Extending](../architecture/extending.md)). |
| `ACTION_NOT_TRANSLATED` | A `#{...}` path template, a path `separator` other than `/`, an action array whose members have different methods, a method that is not an HTTP token, or a DENY on method HEAD without GET: 8.0 decides a HEAD request as GET (`PIP-18`), so it cannot deny HEAD apart from GET. | See [Path templates in actions](#path-templates-in-actions); split statements by method; remove methods no request can have; deny GET too, or remove HEAD. |
| `CUSTOM_ACTION` | An action of a custom `ActionMatcherFactory`. | Rewrite it as HTTP paths and methods, or as route-mapped actions. |
| `DOES_NOT_COMPILE` | A translated statement does not compile, such as a path pattern outside what 8.0 accepts; or a document does not compile as a whole, such as when each policy fits the budget of path patterns and all of them together exceed it. The rejection names the policy it can trace, else the document. | See the message and the [policy language](../policies/language.md). |

Warnings do not stop a conversion. The current ones: a policy in the global policy index whose type is not `GLOBAL`
(converted as an attachable policy, since 7.x did not apply it to everyone), a tenant's policy with DENY statements kept
in the platform document under `by-owner`, and role grants of an app without an app permission (7.x ignored them, so
they are dropped).

#### Tenant and app ids hold no slash {#tenant-and-app-ids-hold-no-slash}

8.0 takes a tenant id or an app id as one path segment (`SCP-13`), so neither may hold `/`; 7.x accepted one. The
control plane authorizes on resources such as `doc/tenant/<tenantId>/…`, where `acme/west` would read as tenant `acme`
and more of the path. Rename such a tenant or app in the system that owns its id (the issuer that writes `tenantId`
into tokens, the app's registration) before you upgrade:

- **To find them,** run `cosec-migrate convert` on a copy of the data: it refuses each one with `SLASH_IN_ID`, at the app
  (`app-role-permissions.json[<i>]`) or, under `--partition by-owner`, at the policy whose `tenantId` holds it. A tenant
  that only tokens name is not in the 7.x data: search the issuer's tenants for `/`.
- **`partition plan`** refuses such a tenant the same way and writes nothing; **`api-keys`** refuses a key of one.
- **Tokens** whose `tenantId` or `aud` holds `/` are answered `401 token-invalid` on every adapter, so such a principal
  signs in again once its tenant is renamed. Behind a gateway, a forwarded identity of such a tenant is
  `400 invalid-request`.

Other characters are kept, and file names still encode them: `acme%west` converts to `tenants/acme%25west.json`.

#### Semantic changes in the report

`semanticChanges` lists, for each place in the 7.x data, the shape whose decision 8.0 makes differently by design.
`change` is one of the rule ids in [What 8.0 decides differently](#what-8-0-decides-differently) (`C1a`–`C9`,
`D1`–`D3`), or:

- `ASCII-CASE`: an ALLOW condition with `ignoreCase`. 8.0 folds ASCII letters only, so the ALLOW matches fewer
  spellings. In a DENY condition whose value has letters outside ASCII, the condition is rejected instead.
- `RATE-LIMIT`: a 7.x rate limit rule, translated into the platform document's rate limits
  ([Rate limit rules become obligations](#rate-limit-rules-become-obligations)).
- `PIP-18`: once per conversion, that a HEAD request is decided as GET, so it is permitted, and denied, wherever GET
  is. And at a statement: method HEAD dropped from an ALLOW (beside GET it says nothing more and goes without a
  record), or an ALLOW on HEAD alone dropped, which narrows it.
- `IP2REGION`: at a statement, a country prefix of `ipRegion` read as `ext.ip2region.country`; once per conversion, that
  the platform document declares that attribute, so every host that enforces such statements needs `cosec-ip2region`
  (without it the attribute is unavailable and such a DENY is Indeterminate, `503`), and that an address no loaded
  database covers is unavailable too (`ATR-9`).
- `NOT-IN-8.0`: an ALLOW condition on a part 8.0 never sets (a request attribute an appender set, `resource.*`), or on
  method HEAD but not GET: the ALLOW matches fewer requests. In a DENY condition the same is rejected.

Read each entry. Nothing in the report needs a change to the data, but each one can change a response.

#### Change nothing in 7.x between convert and the cutover {#change-nothing-in-7-x-between-convert-and-the-cutover}

`convert` reads a 7.x instance that may still be serving: it scans each namespace for keys and reads the global policy
index in a call of its own, so a policy, a grant or an index entry written while it runs may or may not be in the
conversion, and a DENY added afterwards is not. Nothing in the report says so. **From the moment `convert` starts until
the traffic is on 8.0, stop writing policies, grants and the global policy index in 7.x.** To check that nothing
changed, run `convert` again into another directory just before `publish` and compare the `Input sha256` it prints and
records in `report.json` with the first run's: a different hash means the 7.x data changed, and the first conversion is not the
data you are cutting over. `publish` does not read the 7.x data (it reads the converted directory, or a file), so it cannot compare the hash itself.

#### Publish

```bash
cosec-migrate publish --to-redis redis://localhost:6379 [--cluster] [--key-prefix cosec] --in ./converted [--resume]
```

`publish` reads only an accepted conversion, and only the files `report.json` lists with the SHA-256 recorded there:
a file edited by accident, missing or added since `convert` stops it. The SHA-256s are in the same directory, so this
guards against accidents, not against whoever can write the directory: keep it where only the operator writes. A document you wrote yourself, rather than one `convert`
wrote, publishes with `--file <document.json> --document <platform|app:<id>|tenant:<id>>` in place of `--in`, with
the same checks. It then compiles every document, checks every app
document's references against the platform document, and refuses documents that hold a break-glass binding, writing
nothing (`bootstrap` refuses them too). It writes each document as revision 1 through the control
plane's store, so the document's revision history starts there, and only when the document does not exist yet: it
never overwrites 8.0 data.

Writes are one per document. If one fails, the message names the documents already written; run again with
`--resume`, which skips a document already published with the same content and refuses one with different content.
`--key-prefix` is the deployment's prefix, the same as [`cosec.key-prefix`](./configuration.md#key-prefix).
`publish` takes no `--dry-run`; to see what it would do, read `report.json`.

After `publish`, change policies through the control plane only. Running `convert` and `publish` again does not update
documents that exist.

#### Bootstrap the control plane

```bash
cosec-migrate bootstrap --to-redis redis://localhost:6379 [--cluster] [--key-prefix cosec] --platform-admin alice [--force]
```

The control plane authorizes its own writes with the policies in the snapshot, so with no administrator nobody may
write. `bootstrap` writes only the platform tenant's document, `Tenant((platform))`; the platform document itself
comes from `publish`, so publish first. `bootstrap` binds principal `alice` of the platform tenant to the built-in `cosec:platform-admin` policy, in the
platform tenant's document, and records it as a revision of kind `BOOTSTRAP`. Run again for the principal it already
bound, by a deployment job on every start or after a lost reply, it answers that the principal is already bound and
writes nothing; it refuses when that document binds another principal to `cosec:platform-admin`, and `--force` is for
the recovery where every one of them is lost. Either way it
adds its binding and keeps everything else that document holds: its policies, its other bindings, its rate limits and
its roles. Anyone with the Redis credentials can write anyway; `bootstrap` only leaves a record of it.

The document is compiled as the data plane compiles it, with the limits and the attributes the platform document
declares, so a recovery keeps the policies that use `ext.*` attributes. A platform document that cannot be read fails
the command.

#### Partition plan

```bash
cosec-migrate partition plan (--from-redis redis://localhost:6379 [--key-prefix cosec] | --from-files ./export) --out ./plan
```

After a `--partition platform` publish, `partition plan` moves each tenant's attachable policies to the tenant's own
document in steps, each an ordinary change with a shadow window. It converts the data twice, as `platform` and as
`by-owner`, and writes for each tenant a document holding an **exact copy** of the policies that tenant owns, to
`tenant-<encoded id>.json` in `--out` (the same encoding and bound as the converted files; the console names the tenant
of each file). It writes nothing to Redis, and nothing at all when a file of the plan exists already.

1. Submit each file with `apply` in merge mode. A tenant document may hold a policy of a platform policy's id only as
   an exact copy of it, so the change passes the checks; both layers then hold the same content, which the engine
   resolves as one policy, and the shadow window shows no difference.
2. While a tenant holds a copy, the platform's policy cannot be changed (`V2`, naming the tenant): changing it would
   leave one id with two contents, which the engine refuses. Neither can the copy be edited.
3. Remove the platform's policies with a platform change. Removing a policy a tenant holds a copy of is allowed; the
   tenant's bindings then resolve to the tenant's copy, which grants exactly what the platform's did.
4. The copies are now the tenant's own policies, and editable.

#### Repair the back-reference index

```bash
cosec-migrate repair-references --to-redis redis://localhost:6379 [--cluster] [--key-prefix cosec]
```

The control plane keeps an index of which documents refer to which policy, so that a platform change cannot remove a
policy a document still uses, nor add one whose id a tenant document uses ([Storage](./storage.md#redis-keys)).
On a store written before 8.0.0-beta.5 it first converts that index into sets, under a lease two runs share: each value
of the old format a head touches becomes a set, a set is never replaced, and the old document list
(`<s>:control:list:documents`) is copied into `<s>:control:documents:all` and removed last, once nothing failed; it
prints how many it converted and `The store is in the format of 8.0.0-beta.5.` (`MIG-4`). In the same run it adds
each change the old open-change list (`<s>:control:list:open-changes`) names and that is still open to the open-change
sets, and only once every one of them was read marks the sets kept and removes the old list (`CTL-54`); it prints how
many it listed and each id whose record does not read, and fails while one is left. Stop every control plane of an
earlier version first: it writes the old format.

It reads each document's head and its own revision index, and never a whole back-reference set to look for one member
(R2C-8): a document whose index already records its references costs those two reads, and one with no record, as a
converted document is, adds each of its references once. It prints a progress line every 1,000 documents and each
failure as it happens. One command at a time, each a round trip to Redis, measured in memory (`SaasScaleMeasurement`)
on tenants that each hold 20 role bindings to one app's permissions and one binding to a platform policy:

| Tenants | First run, after the conversion | A second run, that changes nothing | The conversion itself |
|---|---|---|---|
| 10,000 | about 260,000 commands (210,000 member writes, 20,000 head reads, 30,000 index reads and writes): about 80 MB | about 31,000 commands (20,000 head reads, 10,000 index reads, 1,201 set reads): about 70 MB | each of the 21 old values read once and written once as a set (about 7 MB), and each tenant's head read once more |
| 50,000 | about 1.3 million commands: about 390 MB | about 151,000 commands: about 350 MB | the same 21 values (about 35 MB), and each tenant's head once more |

At a round trip of 1 ms the first run at 50,000 tenants takes about 22 minutes, a second run about 3; at 0.2 ms, a
fifth of that. The run holds the lease `repair-references`, renewed every 1,000 documents, for 10 minutes past its last
renewal. A second run that finds it held says who holds it and since when, and exits with `1`; a run that died leaves
it for at most 10 minutes: run again then, or delete the key `<key-prefix>:control:lease:repair-references` once you
know no run is alive.

`repair-references` reads the head of every document the store lists and adds whatever its own record misses; it prints
how many documents it visited and how many references it added. Then it removes the references no head has, which a
publish indexed before a head write it lost, and prints how many: left there, one would block removing that policy for
good. A reference goes only once its document's head is at or above the revision that indexed it, so a publish still in
flight keeps its own, and a lost one stays until that document has published past it. So it may run while the control
plane runs, and running it again changes nothing. A
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
`1`: run it again. The list it completes is as good as `<s>:control:documents:all`, which names every document the
control plane or `cosec-migrate` ever published, as long as that list has not been lost; a document written to Redis
another way is in neither.

#### Verify the revision chains {#verify-the-revision-chains}

```bash
cosec-migrate verify-chain --to-redis redis://localhost:6379 [--cluster] [--key-prefix cosec] [--document platform|app:<id>|tenant:<id>] \
  [--prefix tenant:acme] [--limit 1000]
```

Each revision names its parent revision and that revision's digest, and the control plane keeps a record of every
revision under its digest ([Storage](./storage.md#revisions-history-and-rollback)). Redis replicates asynchronously,
so a failover can lose a record it had acknowledged. `verify-chain` walks the chain of every document the store
lists, or of the one `--document` names, or of those whose key starts with `--prefix`, at most `--limit` of them, from
its head through each parent down to the oldest revision the revision index still lists, and prints one line per
document as it verifies it, so a long run shows how far it got:

- `ok <doc>: intact down to revision <n>`, or `ok <doc>: no head` for a document that has none;
- `BROKEN <doc>: no record holds revision <n> (<digest>): ...` when the record of a revision the chain needs is
  missing or holds another revision;
- `INDEX <doc>: the revision index does not follow the head's chain: ...` when the document's revision index is behind
  the head by more than a publication in flight, or on another branch (a failover or a partial restore left it so,
  `CTL-35`): its last entry is not the head's, or an entry names another parent than the entry below it. **A finding:
  it fails the run.** Nothing is lost: the next read of the document's history rebuilds the index from the head's
  records, and `verify-chain` repairs nothing itself;
- `note <doc>: the revision index is one revision behind the head ...` when the index ends exactly at the head's
  parent: a publication that is settling (its head is written, its index entry not yet), or one whose index write
  failed. **Not a failure**; the next read adds the entry;
- `note <doc>: <n> revision index entries predate the parent field ...` for entries an older version wrote: they cannot
  be checked until the first read rewrites them. **Not a failure**;
- `FAILED <doc>: <reason>` when the document could not be read; the others are still verified.

Then `Verified <n> document(s): <b> broken, <f> could not be read.`, with `, <i> with a revision index that does not
follow the head's chain` before the full stop when there is one. It exits with `1` when any chain is broken, any index
differs, or a document could not be read. Revisions that [retention](./storage.md#revisions-history-and-rollback) reclaimed are not a
break: the walk stops below the oldest revision kept. It only reads, so it may run while the control plane runs, and
as often as you like.

A broken chain does not change what instances enforce, which is the head. It means Redis lost a write: the history
of that document misses the revision, and a change published around the failover may be missing from the head too.
Compare the head with what was published, and publish again what it lacks, as after a regression
([Troubleshooting](./troubleshooting.md#decisions-use-an-old-revision)).

#### Check the patterns before an upgrade {#check-the-patterns-before-an-upgrade}

```bash
cosec-migrate check-patterns --to-redis redis://localhost:6379 [--cluster] [--key-prefix cosec] \
  [--document platform|app:<id>|tenant:<id>] [--prefix tenant:acme]
```

Before upgrading from 8.0.0-beta.8, run it with the new release's `cosec-migrate` against the store of the version
you run. It reads the head of every document the store lists, or of the one `--document` names, or of those whose key
starts with `--prefix`, as that version stored it (only each document's current revision: the history is not read,
and a rollback to an older revision publishes it again, compiled then), and compiles each action and resource of its statements, rate limit
rules and catalog permissions with the new rules ([Reserved characters](../policies/language.md#reserved-characters),
`DOC-15`). It prints:

- `ok <doc>` for a document whose patterns all compile;
- `PATTERN <doc> <JSON path>: <why>; write <pattern>, in JSON "<pattern>"` for a pattern that holds a reserved
  character, read as the earlier preview read it (as text) and written escaped, as the new version needs it;
- `PATTERN <doc> <JSON path>: <why>; no escape writes it: ...` for a path pattern that matches no request path (a `\`,
  a `;`, a control character, a dot segment, a segment longer than 256 characters): it denied nothing before either,
  so name the path as the pipeline sees it;
- `FAILED <doc>: <reason>` when the head could not be read; the others are still checked.

Then `Checked <n> document(s): <p> pattern(s) to rewrite in <d> document(s), <f> could not be read.` It exits with `1`
when there is a pattern to rewrite or a document it could not read, so a deployment script can stop on it. Rewrite each
document through the control plane, and publish the escaped spelling only once every instance runs the new version
([Upgrading from 8.0.0-beta.8](./upgrading-previews.md#from-8-0-0-beta-8)). It only reads, so it may run while the
control plane runs.

#### Run it as a Kubernetes Job {#run-as-a-kubernetes-job}

In a cluster, run each command as a Job in the namespace that can reach Redis, so the tool needs no access from your
machine. An init container downloads the release's zip, checks its SHA-256 and unpacks it into a volume; the main
container runs the tool on a plain JRE. The Redis address, with its credentials, comes from a Secret into the
environment variable `REDIS_URI`, and the tool reads it from there: `--to-redis env:REDIS_URI` (any Redis URI option
takes `env:<VARIABLE>` or `file:<path>` in place of the URI). The password then stays off the command line, which the
node's process list shows; `--to-redis redis://...` still works where that does not matter:

```bash
kubectl -n cosec create secret generic cosec-migrate-redis --from-literal=uri='redis://:<password>@redis:6379'
```

```yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: cosec-repair-references
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 86400
  template:
    spec:
      restartPolicy: Never
      securityContext:
        runAsNonRoot: true
        runAsUser: 10001
        seccompProfile:
          type: RuntimeDefault
      volumes:
        - name: tool
          emptyDir: {}
      initContainers:
        - name: fetch
          image: eclipse-temurin:21-jdk # has curl and jar; the JRE image has no jar
          env:
            - name: VERSION
              value: 8.0.0 # the CoSec version you deploy
          volumeMounts:
            - name: tool
              mountPath: /tool
          command: ["sh", "-ec"]
          args:
            - |
              cd /tool
              base="https://github.com/Ahoo-Wang/CoSec/releases/download/v${VERSION}"
              curl -fsSLO "$base/cosec-migrate-${VERSION}.zip"
              curl -fsSLO "$base/cosec-migrate-${VERSION}.zip.sha256"
              sha256sum -c "cosec-migrate-${VERSION}.zip.sha256"
              jar xf "cosec-migrate-${VERSION}.zip"
              mv "cosec-migrate-${VERSION}" cosec-migrate
          securityContext:
            allowPrivilegeEscalation: false
            capabilities:
              drop: [ALL]
      containers:
        - name: migrate
          image: eclipse-temurin:21-jre
          env:
            - name: REDIS_URI
              valueFrom:
                secretKeyRef:
                  name: cosec-migrate-redis
                  key: uri
          volumeMounts:
            - name: tool
              mountPath: /tool
          # `jar` does not restore the executable bit, so the start script runs through `sh`.
          command: ["sh", "/tool/cosec-migrate/bin/cosec-migrate"]
          args: ["repair-references", "--to-redis", "env:REDIS_URI", "--key-prefix", "cosec"]
          securityContext:
            allowPrivilegeEscalation: false
            capabilities:
              drop: [ALL]
```

`kubectl -n cosec logs job/cosec-repair-references` shows what the tool printed, and the Job fails (exit code `1` or `2`)
when the command does. What it prints leaves out the credentials of the Redis URIs it was given, so the log holds no
password: a command it prints to run next, such as `publish ... --resume`, takes `env:REDIS_URI` again. Set `--key-prefix` to [`cosec.key-prefix`](./configuration.md#key-prefix) of your instances.
For `bootstrap` change `args` to `["bootstrap", "--to-redis", "env:REDIS_URI", "--platform-admin", "alice"]`. For a Redis
Cluster add `--cluster` to the commands that take it and write the seed nodes, comma-separated, in the Secret.

`convert`, `publish`, `partition plan` and `api-keys` read or write a directory: mount a PersistentVolumeClaim at `/data`
in the main container and give the paths as `--out /data/converted` and `--in /data/converted`. Run `convert` as one Job,
read `report.json` from the volume (a second pod mounting it, or `kubectl cp`), and run `publish` as another Job only
once the conversion is accepted: `publish` takes only files `report.json` lists, with the SHA-256 recorded there. What
`publish`, `bootstrap` and `restore` write is recorded as `cosec-migrate` in each revision's provenance: who ran the
Job is for your deployment to record (the Job, its namespace's audit log).

#### Rollback

`publish` and `bootstrap` write only 8.0 keys (`<prefix>:snapshot:*` and `<prefix>:control:*`), and 8.0 never reads
or changes the 7.x keys. To roll back, deploy 7.x again: it reads the 7.x data as it was. Changes made through the 8.0
control plane after the switch are not in the 7.x data. Keep 7.x writers stopped from `convert` until you no longer
plan to roll back, or convert again before you publish: a 7.x write after `convert` is not in the converted
documents.

The 7.x policy history (`<prefix>:policy:history:*`) is neither converted nor deleted. Export it yourself if you need
it.

### From 4.x, 5.x and 6.x {#from-4-x-5-x-and-6-x}

There is no separate migration tool for versions before 7.x. The supported path is `cosec-migrate convert --from-files`:
you get your policies into the three files it reads, and it converts them as it converts 7.x's. From 6.x with its data in
Redis, go through 7.x first, as the head of this section says. Everything else on this page that is about the data applies from there on.
An application with no CoSec data to convert skips all of it ([No CoSec data of your own?](#_7-x-to-8-0)).

**What the files are.** `convert --from-files <dir>` reads `policies.json` (an array of policies, each with its `id`),
`global-policies.json` (an array of the ids of the policies that apply to everyone) and `app-role-permissions.json`
(an array with one entry per app: its permission list as `appPermission`, and its role grants as `grants`). It decodes the
policy JSON that 7.x's own deserializers read, which is what 4.3 wrote (`{"all": {}}`, `{"path": {...}}` and
`{"authenticated": {}}` objects, a pattern string as a shorthand for a path). `--from-redis` reads only the 7.0 layout,
so it cannot read an older store. An application whose store of record is its own database writes the exporter itself:
serialize each policy the way your version did, and write the three files. A small export:

```json
[
  {
    "id": "orders-reader", "name": "Orders reader", "category": "order", "description": "Reads orders, never deletes them",
    "type": "custom", "tenantId": "(0)",
    "statements": [
      {
        "name": "read-orders", "effect": "allow",
        "action": { "path": { "pattern": "/orders/**", "method": "GET" } },
        "condition": { "authenticated": {} }
      },
      { "name": "no-deletes", "effect": "deny", "action": { "path": { "pattern": "/orders/*", "method": "DELETE" } } }
    ]
  }
]
```

as `policies.json`, with `[]` as `global-policies.json` and, for the app's permissions and the roles that hold them:

```json
[
  {
    "appPermission": {
      "id": "orders",
      "groups": [
        { "name": "order", "description": "order management",
          "permissions": [ { "id": "order.ship", "name": "Ship", "description": "Ship", "action": "/order/ship" } ] }
      ]
    },
    "grants": { "clerk": ["order.ship"] }
  }
]
```

as `app-role-permissions.json`. `cosec-migrate convert --from-files export --out converted` accepts both, with the
semantic changes `C1b` and `PIP-18`, and writes the platform document and the app document `orders`.

**What converts.** Each row was converted by `convert --from-files` as an ALLOW and as a DENY statement, and the report is
the authority for your own data: read it ([Rejections](#rejections), [Semantic changes in the report](#semantic-changes-in-the-report)).

| Construct | Result |
|---|---|
| Action `*` or `{"all": {}}` | An ALLOW becomes `http:*`, every HTTP request, and no control-plane or in-code action; a DENY stays `*` ([Every action is every HTTP request](#every-action-is-every-http-request)). |
| Action `{"all": {"method": "GET"}}`, a path pattern (a string, an array, `{"path": {"pattern", "method"}}`, `**`) | `http:<METHOD>` or `http:*` on `http:path:<pattern>`. |
| Conditions `all`, `authenticated`, `inTenant`, `in`, `startsWith`, `eq` on the method, `bool` with `and` and `or` | The same condition of the 8.0 language, or none for `all`. |
| `inRole` | `in` on `scope.roles` (ALLOW) or `subject.roles` (DENY), recorded as `D1`. |
| `eq` against a `#{principal.id}`-style reference | A comparison with the attribute, recorded as `D2`. |
| `regular` with a literal pattern | An ALLOW only, as `eq` with `ignoreCase`, recorded as `ASCII-CASE` and `D3`. |
| Every DENY | Converted as above, each recorded as `C1b`. |
| An app permission with path actions, and its role grants | An app document: the catalog, one `perm:<app>/<id>` policy for each permission, and a role binding for each grant (`role` or `role@space`). |
| The deployment's rate limit rules | `--rate-limit-rules`, as 7.x's files ([Convert](#convert)). |

**What needs a judgement.** Each is rejected with a message that says what to rewrite, and the data is fixed in the
export, then converted again: a path with a `#{...}` template (`ACTION_NOT_TRANSLATED`: [Path templates in actions](#path-templates-in-actions));
an action that is a composite of different methods (`ACTION_NOT_TRANSLATED`: split it by method); `eq` on the client's
address, `contains`, `endsWith`, a `regular` that is not a literal, `path` on a part other than `request.path`, and
`regular` in a DENY (`CONDITION_NOT_TRANSLATED`).

**What does not convert.** `spel` and `ognl` conditions (`EXPRESSION_CONDITION`), custom action and condition types
(`CUSTOM_ACTION`, `CUSTOM_CONDITION`), and 4.x's `rateLimiter` and `groupedRateLimiter` conditions: they are rejected as
`UNDECODABLE` with `CUSTOM_CONDITION`, because 7.x had no such condition, and their `UNDECODABLE` message says the input is not in the 7.x
format and points to this section. Rewrite them as [rate limit rules](../policies/rate-limiting.md). The policy history is not converted.

**What the tool cannot decide**, and you do, before you publish:

1. **What a `*` ALLOW should mean.** It becomes `http:*`: the same requests it permitted, and nothing of the control
   plane. If your 4.x policy used it for actions you check in code, name those actions.
2. **Where the role grants go.** `convert` writes every grant as a binding of the app document, which belongs to the
   platform and applies in every tenant; `--partition by-owner` moves policies to their tenants and not grants, which
   carry no tenant. An identity system whose roles belong to a tenant projects them as `roles` of the tenant documents
   through the control plane instead ([Roles from an external source](../scenarios/external-source.md)).
3. **What replaces the checks your application did when a policy was written.** `convert` is a command line, not a
   library. In 8.0 a document is checked by the control plane when a change is proposed, and a policy that does not
   compile is a `DRAFT` with findings ([Testing policies](../policies/testing.md#validating-policies)).

Then [publish](#publish) as for 7.x data. The application's own code moves as the rest of this page says: its imports and
removed APIs ([Removed and replaced APIs](#removed-and-replaced-apis)).

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
| **C7** (`MIG-2`) | A DENY of every action (`*`) also refuses checks in code and the control plane's actions (`cosec:*`) for the requests it matches. | Only HTTP requests were decided. | DENY statements on every action ([Every action is every HTTP request](#every-action-is-every-http-request)). |
| **C8** (`MIG-2`) | An ALLOW of every action is `http:*`, every HTTP request, and a [route map](../policies/language.md#route-maps) takes each route it maps out of it: a mapped route is its business action, which the converted policy does not grant. | The route was an HTTP request. | ALLOW statements on every action, which the report records at their action. After a route map is added, grant the mapped actions with policies of their own ([Every action is every HTTP request](#every-action-is-every-http-request)). |
| **C9** (`MIG-5`) | A DENY or a rate limit rule written for a request no route map maps stops applying to a route the route map later maps: an `http:` action or an `http:path:` resource, or a condition on `resource.id`, `action.verb` or `resource.var.*` (7.x's `request.path`, `request.method`, `request.path.var.*`). The route is its business action on its own resource, so the statement does not match it, and the route is no longer denied or limited. | The route was an HTTP request, and the statement matched it. | DENY statements and rate limit rules of that shape, which the report records at their statement. Before a route map is added, rewrite each for the mapped action ([Every action is every HTTP request](#every-action-is-every-http-request)). |
| **D1** | `inRole: r` in an ALLOW matches only a role in effect in the requested space; `inRole: r@s` in an ALLOW matches only while the request is in space `s`; `inRole: r` in a DENY matches the role held in any space. | Literal role match, a bare role also matching `r@<requested space>`. | `inRole` conditions. A DENY never narrows and an ALLOW never widens. |
| **D2** (`CND-1`) | An absent attribute equals no value, not the empty string. | An absent part read as `""`. | `eq` against `#{principal.id}`, `#{principal.attributes['k']}` or `#{tenant.tenantId}`. In an ALLOW, an absent part no longer matches an empty attribute (narrower). In a DENY, `convert` keeps 7.x's reading by adding the 7.x cases. |
| **D3** | No regular expressions, so no regex timeouts. | A `RegexTimeoutException` was an implicit deny. | `regular` conditions; only literal, literal-prefix and choice-of-literals patterns translate. |
| **D4** (`ALG-10`) | A statement with several path patterns matches when its condition holds under the variables of any matching pattern. | Only the first matching pattern's variables were read. | Path actions with several patterns and a condition on `request.path.var.*`: a DENY now denies what any of its patterns would, an ALLOW permits what any of them would. |

#### Anonymous requests that declare a space are 401

Rule `SCP-3`: an anonymous request with a non-empty `CoSec-Space-Id` header is answered `401`
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

#### Every action is every HTTP request

7.x decided HTTP requests and nothing else, so its every-action forms meant every HTTP request: the pattern `*`, `all`
without methods, a `path` array holding `*`, and an array or composite holding one of them. In 8.0, `*` also matches
the control plane's actions (`cosec:*`) and those of [checks in code](../integration/checks.md), which 7.x never
granted. So `convert` writes an ALLOW of every action as `"action": "http:*"` (`MIG-2`), and the decisions on HTTP
requests are those of 7.x.

A DENY of every action stays `"action": "*"`: it keeps refusing what it refused, now also through a check in code
or the control plane, and the report records it as C7. Where such a DENY can match the administrator's own requests,
it refuses them the control plane too.

A statement on `http:*` never matches a request a [route map](../policies/language.md#route-maps) maps. After you add
a route map, grant the actions it names with policies of their own. The report records each ALLOW of every action as
C8, at its action, so that a route that stops being allowed on the day the route map is added has a line to be found by.

The other direction is silent at run time, so the report is where it is found. A DENY or a rate limit rule written for a request
no route map maps, such as `{"effect": "deny", "action": {"path": {"pattern": "/admin/**"}}}` (an `http:` action with an
`http:path:` resource), or `"action": "*"` with a condition on `request.path` or `request.method` (now `resource.id`,
`action.verb`), stops applying to a route the route map maps: the request is the business action on its own resource, and the
route is let through where 7.x denied it, wherever an ALLOW for the mapped action exists. The report records each as C9, at its
statement. **Rewrite each for the mapped action before you add a route map**, or deny the route by its mapped action and
resource. A host with a route map also warns when it installs such a document (`RTE-5`). A condition on the path reads it as
received, percent-encoded, as 7.x read the raw request URI: `/%61dmin/x` is not `/admin/x`; a DENY on a path pattern decodes it.

#### Path templates in actions

A 7.x `path` action with a `#{...}` template, such as `/users/#{principal.id}/**`, is rejected by `convert`. In 8.0 a
route map names the resource and its id (`"path": "/users/{userId}"`, `"resource": "user/{userId}"`), and a condition
compares the resource attribute with the principal. See the [policy language](../policies/language.md). `#{...}`
templates inside `eq` conditions translate (D2).

#### Rate limit rules become obligations

7.x rate limit rule files (`cosec.limiter.*`) are gone. `convert --rate-limit-rules` translates the rules into the
platform document's rate limits. A rule applies to a request the policies permit, as in 7.x: the first exhausted rule
answers `429`, and denied requests spend nothing. A rule with `strictFailure` whose limiter fails now answers `503`
instead of `429` (R3). The Redis limiter counts when a `ReactiveStringRedisTemplate` bean exists, otherwise each
instance counts alone. Its keys are new, `<cosec.key-prefix>:rate-limiter:{<rule>:<bucket>}:<window>`, so that a
bucket's two windows share a Redis Cluster slot. Each version counts apart: while 7.x and 8.0 instances both serve,
and for up to two windows after the last 7.x one stops, a rule lets through up to twice its limit. See
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

Rule `SCP-4`: when `CoSec-Space-Id` or `CoSec-Device-Id` arrives with two different non-blank values,
between the header and the query string or within either, the request is `400`. 7.x did not refuse such a request.

#### Spring MVC reads query parameters from the query string only {#spring-mvc-reads-query-parameters-from-the-query-string-only}

On Spring MVC, 7.x read a query parameter with `HttpServletRequest.getParameter`, which also returns the fields of an
`application/x-www-form-urlencoded` body. So for a form POST, the container parsed and consumed the body inside
CoSec's filter, and a body field counted as a query parameter. 8.0 reads the URL query string only, on every adapter,
and never touches the body.
A form field no longer sets `CoSec-Space-Id` or `CoSec-Device-Id`, nor the `authorization` token
read with `cosec.pipeline.allow-query-token=true`. Clients that send these as form fields must send them as headers;
the token may also go in the query string with that property.

### Authentication

#### Token revocation is always on {#token-revocation-is-always-on}

`cosec.jwt.token-revocation.enabled` is gone. With JWT enabled, every token is checked for revocation, and startup
fails when nothing can answer that check: add `cosec-cocache`, which brings the Redis revocation store, or register all
three beans of your own: a `TokenStore`, a `RevocationReader` and a `TokenFamilyStore`. The failure names every missing
one at once. `RevocationReader.NONE` starts, with a `WARN`, and accepts every revoked token until it expires. In 7.x, without `cosec-cocache` the starter used a store that
records nothing, so logout silently did nothing. A service that issues no tokens and reads none can set
`cosec.jwt.enabled=false` instead ([Logout and revocation](../integration/authentication.md#logout-and-revocation)); one
that only issues tokens keeps it on, with the stores ([A service that only issues tokens](../integration/authentication.md#a-service-that-only-issues-tokens)).
Revocations written by 7.x stay in force: 8.0 reads them under the same keys (`<prefix>:token:revoked:*`). An error
the revocation check reports is `503`, never an accepted token (`PIP-7`).

#### Tokens and API keys

- Tokens issued by 7.x keep working: 8.0 reads the same claims (`roles`, `policies`, `attributes`, `tenantId`), and
  role claims as 7.x did (`role` in the default space, `role@space` in that space only, rule `MEM-3`).
- **Tokens issued by 6.x** carry what 6.1's `JwtTokenConverter` writes: `jti`, `sub`, `iat` and `exp`, and `roles`
  (`role`, or `role@space`), `policies`, `attributes` and a string `tenantId` when the principal has them. 8.0 verifies
  them when it holds the same `cosec.jwt.algorithm` and `cosec.jwt.secret` (of at least the length below), and requires
  `jti`, `sub` and `exp`, which 6.x always sets. What they lack matters: **no `aud`**, so the request has no app (on a host
  without `cosec.authorization.scope.app-id`, `scope.appId` is empty and no app document is read; with it, every such token
  is `403` `scope-rejected`, [Apps](../integration/authentication.md#apps)), and **no session claims** (`sid`,
  `auth_time`, `cosec.generation`), so an 8.0 service never refreshes a 6.x pair: the user signs in again at an 8.0
  issuer. A 6.x issuer revokes only with `cosec.jwt.token-revocation.enabled=true`, by token id, and whether an 8.0
  verifier reads what it writes is not established: do not rely on a 6.x issuer's logout reaching an 8.0 verifier, and
  keep access tokens short until the issuer is on 8.0 ([A service that verifies tokens another service issues](../scenarios/resource-server.md)).
- **A token with an `act` claim is now refused** as `token-invalid` (`IDN-4`). 7.x ignored the claim, so an RFC 8693
  delegation token was read as its `sub` and the actor got the subject's permissions. CoSec never issues such
  tokens; a verifier that accepts tokens from another issuer must not pass them on until delegation is supported
  ([Authentication](../integration/authentication.md#tokens)). A deny is audited with the reason code
  `DELEGATION_UNSUPPORTED`; on a permit the audit records `PERMIT`, and the reason appears only in the decision event's
  `subject.rejected`. Refreshing or revoking such a token fails too.
- **A `tenantId` claim that is empty or not a string**, or an `attributes` claim that names `apiKeyId` (set by CoSec for
  API keys only), makes the token `token-invalid`; 7.x read such a tenant as none. **`cosec.jwt.secret`** must be at least
  32, 48 or 64 UTF-8 bytes for `hmac256`, `hmac384` or `hmac512`, or startup fails.
- **Break-glass, 7.x's root,** applies only to a token CoSec's own `JwtTokenVerifier` verified, never to a token of a
  custom `TokenVerifier` or to a principal another authenticator accepted (`BRK-8`).
- **Tenant switch and social login.** A tenant switch (7.x's `AbstractSwitchTenantAuthentication`, now
  `SwitchTenantAuthentication` with a `TenantSwitch`, [From 8.0.0-alpha.4](./upgrading-previews.md#from-8-0-0-alpha-4)) answers the user's
  `TenantMembership` in the target tenant and app instead of a principal, so the new token never carries the previous
  tenant's roles, and it presents the session's token pair, as a refresh does: an access token alone does not switch
  ([Switching tenant and app](../integration/authentication.md#switching-tenant-and-app)). `DirectSocialUserPrincipalConverter` signs only `cosec_oauth_provider` and `cosec_oauth_user_id`,
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

**Converting stored keys.** `cosec-migrate api-keys` reads every key under `<--key-prefix>:api-key` (default
`cosec:api-key`), where the deployment reads them, and writes `api-keys-report.json` to `--out`. `--app <id>` says which
app the converted keys belong to ([Apps](../integration/authentication.md#apps)); 7.x keys declare none, so without it a
service with an app refuses them. Export what each principal holds now, as a JSON array
of `{"tenant", "principal", "roles": ["role", "role@space", ...]}`, each with the policies your membership provider
attaches to it as `"attachments": ["policy", ...]` when it attaches any, and pass it as `--memberships`. Export the
principals your application's blacklist blocks, as the JSON array of the values its `BlacklistSource` holds for them
(`["acme:mallory", ...]`, `<tenant>:<principal id>`), and pass it as `--blacklist`:

```bash
cosec-migrate api-keys --redis redis://localhost:6379 --memberships ./memberships.json --blacklist ./blacklist.json --out ./api-keys --dry-run
```

A key goes one of three ways (`keys[].mode`):

| Mode | When | The key afterwards |
|---|---|---|
| `in-place` | `--memberships` shows its principal holds every role the key named, and the key attached no policy | stays with its principal, capped to those roles |
| `own-principal` | otherwise, by default | moves to a workload principal `apikey:<key id>` in its tenant, uncapped; `memberships` lists the key's roles for that principal, `principalBindings` its attached policies bound to it by name |
| `granted` | only for the principals `--grant-to-principal <ids>` (or `all`) names | stays with its principal, capped to its roles and a role of its own (`--role-prefix`, default `api-key.`, plus the key id), which `roleBindings` binds to its attached policies; the principal is granted the union of its usable keys' caps, and `memberships[].newlyGained` says what it did not hold |

By the documents, memberships and blacklist given, no key gains access, and no principal does unless named in
`--grant-to-principal`. A key never moves to a principal of its own while a DENY, or the blacklist, names its principal
by its id (`MIG-6`), since `apikey:<key id>` would escape it:
- a condition, in a DENY or under a negation in an ALLOW, that compares `subject.id` with a value holding of the
  principal (`eq`, `in`, `prefix`), or with a value the documents do not settle (another attribute: then every key is
  refused this way);
- a principal binding of a policy that holds a DENY;
- a DENY the `--memberships` export lists among the principal's `attachments`;
- the principal's entry in the `--blacklist` export.

Such a key is refused, naming each DENY. Keep it on its principal: in place, or with `--grant-to-principal`. What a key
loses is listed per key: `lost`, its principal's principal and break-glass bindings that grant, which a capped key no
longer reaches; `stillDenied`, the DENYs bound to its principal, which a capped key stays under; and for
`own-principal`, `changes`, what depends on the principal's id (the audit subject, rate limit buckets keyed by
principal, the `sub` of a forwarded `CoSec-Identity`).

Without `--blacklist` the tool cannot see your blacklist, and says so. A key whose principal it blocks moves like any
other, and is no longer blocked: keep such a key in place, or block the value the report names for each key that moved
(`blockIfPrincipalBlocked`, `<tenant>:apikey:<key id>`) before the key is used.

A key is rejected, and then the command exits `1` and writes no key, when a DENY names its principal and it would move
(above), when its stored record has a member of another type than the format says (a `policies` that is no array:
read as empty, it would drop an attached DENY), when it has attributes (a condition may read them), malformed roles, an id with `@`, `*` or a blank, a role that starts with the role prefix, a per-key role a
document already binds, or an attached policy that does not resolve exactly: missing, not attachable, or defined by
both the tenant and the platform with different content. Such an attachment made every request of the key
indeterminate in 8.0, and dropping it could drop a DENY: fix the documents or the key first. 7.x did not keep key
ids unique, so a key is also rejected when another key of its tenant has the same id (both would become one principal,
or share one per-key role), and an `own-principal` key when its `apikey:<key id>` principal already exists: a document
binds it by name, or `--memberships` lists it. The key would gain what that principal holds. A `--memberships` file
that lists a principal twice, or has a `tenant`, `principal` or `roles` of the wrong type (`roles` is an array of strings; only `[]` means no roles), is refused. A key without a tenant goes to the default tenant (`defaultTenant`).

An `own-principal` key with no roles is still listed in `memberships`, with `roles: []`. Its principal must exist in
your `MembershipProvider` all the same, with no roles: a principal the provider does not know is refused, so every
request with the key would be `401`.

1. Run with `--dry-run` and read the report.
2. Grant the `memberships` in your `MembershipProvider`'s source, and add the `principalBindings` and `roleBindings`
   through the control plane (a change request).
3. Run again without `--dry-run`. Each key is rewritten only while it still holds what was read, so a key revoked or
   disabled in between is never brought back: the report lists it under `changedSinceRead`, and the command exits `1`
   and asks to be run again. Keys already converted are left as they are, so the command can be repeated. A key an 8.0
   pre-release stored in another format (`"format":2`) is neither converted nor read by 8.0: the command refuses the run,
   naming it as stored by a pre-release; issue a new key for it and delete the old one, then run again.

The converted key does not keep its old roles and policies: the report is the only place they are. It is written
(`status: planned`) before the first key is rewritten, replaced by the final one (`converted`) when the run is done, and
a run that finds a report which is not that of a dry run or of a refusal in `--out` stops without touching a key: a
repeat would find the keys converted and replace the plan with an empty one. Give a repeated run an `--out` of its own,
and move a report away only once its `memberships` and bindings are carried out. Two runs may share one `--out` only
when the first was a `--dry-run`. `--out` is created before anything is written, so one that cannot be written stops
the run first. `SCAN` may return a key twice; it is read once.

A key converted before step 2 is complete is refused or narrower, never wider.

#### Inject mode becomes identity propagation

7.x inject mode (`cosec.inject.enabled=true`) let a service behind the gateway parse the forwarded JWT without
verifying it. 8.0 has no mode that reads a token without verifying it. Instead the gateway forwards the identity it
verified, and the services behind it trust the gateway:

| Where | 7.x | 8.0 |
|---|---|---|
| Gateway | Forwarded the client's `Authorization` header. | On a permit with a principal it sets `CoSec-Identity` on a route to a registered service (`lb://`), and on any other route whose metadata says `cosec-identity: true`; it always strips the client's own. `cosec.gateway.identity.claims` names principal attributes to forward; route metadata `cosec-identity: false` keeps a route from receiving it. The `Authorization` header, the API key header, the `authorization` query parameter and the `authorization` cookie are removed from forwarded requests unless `cosec.gateway.identity.forward-authorization=true` ([Gateway identity](./configuration.md#gateway-identity)). |
| Each service behind it | `cosec.inject.enabled=true` | `cosec.pipeline.mode=gateway` ([Services behind the gateway](../integration/web.md#services-behind-the-gateway)): no keys, policies, snapshot or Redis; set `cosec.jwt.enabled=false` unless it issues tokens. Only the gateway may be able to reach the service (network policy, a private network, mTLS); anyone else who can is whoever they claim. |
| Application code | Read the caller from `SecurityContext`. | The same. Attached policies are not forwarded, and the roles are those of the scope the gateway decided in. |

A credential that never expires, such as an API key without `expires-at`, is not forwarded: the gateway answers its
permits `500`. Give such keys an expiry, or opt their routes out.

A service behind a CoSec gateway that verifies tokens itself no longer receives the `Authorization` header. Either set
`cosec.gateway.identity.forward-authorization=true` on the gateway and route to the service by `lb://` or with
`cosec-identity: true`, or switch the service to `cosec.pipeline.mode=gateway`. A token in the `authorization` cookie
is removed too, under the same switch.

#### Rolling out an inject-mode service behind an older gateway {#rolling-out-an-inject-mode-service-behind-an-older-gateway}

A service in 6.x or 7.x inject mode sits behind a gateway that forwards the client's `Authorization` header and sends no
`CoSec-Identity`. The service cannot go straight to `cosec.pipeline.mode=gateway` while that gateway is in front of it,
for two reasons that hold on `main`: a gateway-mode service reads no token, so a request without `CoSec-Identity`
proceeds as the anonymous principal `(0)` and every caller of the service is anonymous; and an older gateway does not
strip a `CoSec-Identity` header the client sent, which the service believes, so anyone who can reach the gateway is whoever
they claim ([Services behind the gateway](../integration/web.md#services-behind-the-gateway)). The order that works:

1. **The service moves to `enforce`, verifying the token the gateway forwards.** It needs the issuer's `cosec.jwt.secret`
   and `cosec.jwt.algorithm`, the issuer's revocations, and a platform document that permits its authenticated callers:
   [A service that verifies tokens another service issues](../scenarios/resource-server.md). The app of a request is the
   token's `aud` and no header: a `CoSec-App-Id` the gateway or the client sends is not read. Tokens of a 6.x or 7.x issuer
   carry no `aud`, so the request has no app until the issuer moves to 8.0
   ([Tokens and API keys](#tokens-and-api-keys)).
2. **The gateway moves to 8.0**, with `cosec.gateway.identity.forward-authorization=true`. Without it the 8.0 gateway
   removes the `Authorization` header, and the service in `enforce` mode, which still reads it, would see anonymous
   requests. The gateway then sends `CoSec-Identity` as well; an `enforce` service does not read it, and its adapter strips it from the request its handler sees.
3. **The service moves to `gateway` mode**, once the 8.0 gateway is the only way in: `cosec.pipeline.mode=gateway`, no
   revocation stores and no document, and a network policy that admits only the gateway. Then
   `forward-authorization` can go.

A service that stays in `enforce` mode is a finished state, not a step: it keeps verifying the token, with its own
document and revocation reader.

#### Clients send tokens and scope differently {#clients-send-tokens-and-scope-differently}

- The `authorization` cookie, which 7.x read on every request, is read only with
  `cosec.pipeline.allow-cookie-token=true`: a browser sends a cookie on its own, on a cross-site request too, and CoSec
  has no CSRF protection. Send the token in the `Authorization` header, or turn the property on and defend against CSRF
  yourself ([Authentication](../integration/authentication.md)).
- `CoSec-Space-Id` and `CoSec-Device-Id`, which 7.x also read from the query string, are read from
  headers only ([App and space](../integration/web.md#app-and-space)).

### Storage and Redis

#### Redis keys

8.0 stores documents under `<prefix>:snapshot:*` and the control plane's data under `<prefix>:control:*`,
`<prefix>:candidate:*` and `<prefix>:shadow:*`. It does not read the 7.x keys `<prefix>:policy:*`,
`<prefix>:global:*`, `<prefix>:app:permission:*`, `<prefix>:app:role:permission:*` and `<prefix>:meta:schema`. A
tool that read or wrote those keys directly must use the control plane. Give only the control plane's Redis account
write access to `<prefix>:snapshot:*` and `<prefix>:control:*`. All keys: [Storage](./storage.md).

#### Startup fails when Redis's eviction policy cannot be read {#startup-fails-when-redis-s-eviction-policy-cannot-be-read}

At startup CoSec reads Redis's `maxmemory-policy` and refuses to start unless it is `noeviction`, because an evicted
platform document could silently drop its DENY statements. It reads it from `INFO memory`. When `INFO` fails or is
not allowed, startup fails too, and also when the CoCache backend is a wrapper or a custom backend it
cannot read. If you have set `noeviction` yourself, set `cosec.authorization.snapshot.eviction-check=warn`: startup
goes on and logs a warning that starts with `!!!`. CoCache 8, which keeps CoSec's documents as `Store`s, checks the
policy too and is not told by that setting: also set `cocache.redis.assume-maxmemory-policy=noeviction`
([Redis requirements](./storage.md#redis-requirements)).

#### Each instance decides from a snapshot

- **No per-request Redis reads.** Each instance loads the platform document, and the app and tenant documents its
  requests need, compiles them, and checks each loaded document with a conditional read every
  `cosec.authorization.snapshot.revalidate-interval` (default `10s`). Redis transfers a document only when it
  changed.
- **During a Redis outage, installed documents keep deciding** as last confirmed, so a platform DENY still holds. A
  request that needs a document not yet loaded waits up to the cold-load timeout (`2s`) and is then `503`.
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
| `micrometer-support` | `cosec-micrometer`, which brings `micrometer-core` |
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
| `me.ahoo.cosec.api.authentication`, `me.ahoo.cosec.authentication` | `me.ahoo.cosec.login` (`cosec-login`) | `Authentication`, `Credentials` (now `LoginCredentials`), `CompositeAuthentication` |
| `me.ahoo.cosec.authentication.token` | `me.ahoo.cosec.login.token` (`cosec-login`) | `AbstractSwitchTenantAuthentication` is `SwitchTenantAuthentication` with a `TenantSwitch`; `SimpleRefreshTokenAuthentication` is `RefreshTokenAuthentication` (`AbstractRefreshTokenAuthentication` is removed) ([From 8.0.0-alpha.4](./upgrading-previews.md#from-8-0-0-alpha-4)) |
| `me.ahoo.cosec.token` | `me.ahoo.cosec.login.token` (`cosec-login`) | `TokenConverter`, `TokenCompositeAuthentication`, `RefreshTokenMismatchException` |
| `me.ahoo.cosec.token` | `me.ahoo.cosec.spi.token` (`cosec-spi`) | `TokenVerifier`, `TokenStore`, `TokenRevoker`, `RevocationReader`, `TokenVerificationException`, `TokenExpiredException`, `TokenRevokedException` |
| `me.ahoo.cosec.token` | `me.ahoo.cosec.login.token` (`cosec-login`) | `DefaultTokenRevoker` is `StoreTokenRevoker` ([From 8.0.0-alpha.4](./upgrading-previews.md#from-8-0-0-alpha-4)) |
| `me.ahoo.cosec.token` | `me.ahoo.cosec.api.token` (`cosec-api`) | `SimpleCompositeToken` is removed: `CompositeToken` is a final class (`RevocableTokenVerifier` is removed too: [From 8.0.0-alpha.4](./upgrading-previews.md#from-8-0-0-alpha-4)) |
| `me.ahoo.cosec.api.CoSec` | gone | Its values have names where they mean something: `CoSecPrincipal.ANONYMOUS_ID` (`me.ahoo.cosec.api.principal`), `TenantId.DEFAULT` (`me.ahoo.cosec.model`); the `cosec.` property prefix is written out ([From 8.0.0-alpha.4](./upgrading-previews.md#from-8-0-0-alpha-4)) |
| `me.ahoo.cosec.context` | `me.ahoo.cosec.webmvc` (`cosec-webmvc`) | `SecurityContextHolder` |
| `me.ahoo.cosec.context.request` | `me.ahoo.cosec.runtime.http` (`cosec-runtime`) | `RemoteIpResolver`, `XForwardedRemoteIpResolver` |
| `me.ahoo.cosec.api.audit` | `me.ahoo.cosec.spi.audit` (`cosec-spi`) | `AuditEventSink` |
| `me.ahoo.cosec.audit` | `me.ahoo.cosec.runtime.audit` (`cosec-runtime`) | `LoggingAuditEventSink`, `AuditEventData` |
| `me.ahoo.cosec.apikey` | `me.ahoo.cosec.spi.apikey` (`cosec-spi`) | `ApiKey`, `ApiKeyReader`, `InMemoryApiKeyStore` |
| `me.ahoo.cosec.limiter` | `me.ahoo.cosec.spi.ratelimit` (`cosec-spi`) | `RateLimit`, `RateLimitResult`, `RateLimiter` ([below](#removed-and-replaced-apis)) |
| `me.ahoo.cosec` | `me.ahoo.cosec.model` (`cosec-model`) | `CoSecException` |
| `me.ahoo.cosec.spring.boot.starter` and its sub-packages | `me.ahoo.cosec.spring.boot` and the same sub-packages (`cosec-spring-boot`) | The starter's classes, such as `jwt.JwtProperties`: `me.ahoo.cosec.spring.boot.starter.jwt.JwtProperties` is now `me.ahoo.cosec.spring.boot.jwt.JwtProperties`, and, like the other `*Properties` classes, `@InternalCoSecApi` ([From 8.0.0-alpha.4](./upgrading-previews.md#from-8-0-0-alpha-4)): read `cosec.jwt.*` with `@Value` or the `Environment`, and do not reuse its nested types |

The 8.0 SPIs live in the same places: `MembershipProvider` and `MembershipClaims` in `me.ahoo.cosec.spi.membership`,
`SnapshotDocumentStore` and `InMemorySnapshotDocumentStore` in `me.ahoo.cosec.spi.snapshot`, `AttributeProvider` in
`me.ahoo.cosec.spi.attribute`, `Authenticator` in `me.ahoo.cosec.spi.authn`; `SnapshotSource` is in
`me.ahoo.cosec.runtime.snapshot`, the document codec and compiler in `me.ahoo.cosec.document` (`cosec-document`) and
route maps in `me.ahoo.cosec.document.route` ([Extending](../architecture/extending.md#extension-points)).

**`CoSecJsonSerializer` is gone.** CoSec's Jackson mapper is internal to CoSec, and no public signature of the core modules
carries a Jackson type. Serialize your own types with your own mapper; read and write policy documents with
`PolicyDocumentCodec`. The mapper's configuration did not change, so documents, tokens and records already stored in
Redis or sent to Kafka read as before. A declaration marked `@InternalCoSecApi` is public only so CoSec's modules can
call each other: it has no compatibility promise and can change in any release, and the compiler refuses a call to it
without an explicit opt-in. Do not opt in from an application.

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
| `SecurityPipeline`, `SecurityDecision`, `RequestParser`, `SecurityContextParser`, `DefaultSecurityContextParser` | The pipeline; `Authenticator` beans for credentials. |
| `CoSecAuthorizationCustomizer` | Beans of `Authenticator`, `DecisionListener`, `AttributeProvider`, `MembershipProvider`, `RateLimiter`. |
| `Policy`, `Statement`, `PermissionVerifier`, `VerifyContext`, `RoleVerifyContext`, `VerifyResult`, `ActionMatcher`, `ConditionMatcher`, `AppPermission`, `AppRolePermission`, `RolePermission` and the rest of `cosec-api`'s policy and permission model | Documents ([policy language](../policies/language.md)). A `Statement` that overrode `verify` has no counterpart. The app a request was permitted in, which a `VerifyContext` carried, is `SecurityContext.scope.appId`, validated against the token's `aud` ([Apps](../integration/authentication.md#apps)). |
| `ActionMatcherFactory`, `ConditionMatcherFactory`, `MatcherFactoryRegistry`, the built-in matchers, OGNL and SpEL | Built-in actions and conditions only. A value a custom condition read comes from an `AttributeProvider` ([Extending](../architecture/extending.md)). |
| `AuthenticationProvider`, `DefaultAuthenticationProvider` | Declare `Authentication` beans: the starter registers them. |
| `AbstractRefreshTokenAuthentication` | `RefreshTokenAuthentication`, or an `Authentication<RefreshTokenCredentials>` of your own. |
| `ApiKeyStore` | `ApiKeyReader` to look keys up; the `InMemoryApiKeyStore` or `RedisApiKeyStore` bean to manage them. |
| `BlockingAware` | None: no condition runs code. Blocking lookups go into an `AttributeProvider`, which suspends. |
| `RootPrincipalMatcher`, `CoSecPrincipal.isRoot`, `root-enabled` | Break-glass bindings ([Root becomes break-glass](#root-becomes-break-glass)). |
| `CoSecPrincipal.anonymous` | `!CoSecPrincipal.authenticated`: `authenticated` is true when a credential proved the principal, and the pipeline states it, never reading it off the id. An anonymous principal's id is still `CoSecPrincipal.ANONYMOUS_ID`. |
| `BlacklistChecker` | A `BlacklistSource` behind an `AttributeProvider` bean (`BlacklistAttributeProvider`). |
| `RequestAttributesAppender`, `GatewayRouteRequestAttributesAppender` | `AttributeProvider`. |
| `SimpleSecurityContext`, the extension `CoSecPrincipal.tenant` | None: the pipeline makes the request's `SecurityContext`. In a test, mock or implement the interface; the tenant is `SecurityContext.tenantId`. |
| The principal and tenant wrappers of `cosec-core`: `SimplePrincipal`, `SimpleTenantPrincipal`, `TenantPrincipal`, `TokenPrincipal`, `TokenTenantPrincipal`, `SimpleTokenPrincipal`, `SimpleTokenTenantPrincipal`; `SimpleTenant`, `Tenant`, `TenantCapable` | What a token is issued for is a `TokenSubject` (`me.ahoo.cosec.login.token`): the principal's `id`, the `app` and `tenant` it is for, its claimed `roles` and `policies`, and `attributes`. An `Authentication` answers one, and `TokenConverter` turns it into tokens ([Authentication](../integration/authentication.md)). What a request reads is `CoSecPrincipal` (`SecurityContext.principal`), with its tenant in `SecurityContext.tenantId`. The tenant ids are `TenantId.DEFAULT` and `TenantId.PLATFORM` (`me.ahoo.cosec.model`). |
| `RoleCapable`, `PolicyCapable` (a principal's `roles` and `policies`) | The principal's memberships, `Memberships` (`me.ahoo.cosec.model`), from the token's claims or a `MembershipProvider`. The roles effective for a request are `SecurityContext.scope.roles`: the roles in the request's space, by their bare names (`editor`, not `editor@team-a`). The principal's roles in other spaces are not on the context, and neither are the policies attached to it: the attached policy ids of the token or of the membership provider are not exposed in 8.0. To branch on what an attached policy meant, check an action the policy permits ([Checks in code](../integration/checks.md)), or give the role the permission. |
| The `String` aliases `RoleId`, `PolicyId` and `PermissionId` of `cosec-api` | `RoleId` and `PolicyId` are value classes of `me.ahoo.cosec.model`, with the same names in another package: `RoleId("admin")`, and `.value` for the string. `PermissionId`: none. A permission is an action, granted by a policy or by a catalog entry ([Role permissions](../policies/role-permissions.md)). |
| `KafkaAuditEventSink` | `KafkaAuditListener`, a `DecisionListener` the starter registers when `cosec-kafka` is on the classpath ([Observability](./observability.md)). The record's key is the same, and its value is the audit event ([Observability changes](#observability-changes)). |
| `PolicyRepository`, `AppRolePermissionRepository`, the Redis repositories and caches, `SnapshotPublisher`, `RedisSnapshotPublisher`, `PolicyHistory`, `RedisPolicyRevisionStore`, writes to `AppPermissionCache`/`RolePermissionCache` | The control plane's HTTP API for writes, from a client of your own ([Roles from an external source](../scenarios/external-source.md)); `cosec-migrate publish` for the first ones. |
| `LocalPolicyInitializer` and `init-repository`/`force-refresh` | `cosec-migrate convert` and `publish`, or the control plane's declarative `apply`. |
| `CoSecExplainEndpoint` (`cosecExplain` actuator), `AuthorizationExplainer`, `ExplainWarningCode` | The control plane's `explain` (authorized, and for any revision); see [Checks](../integration/checks.md). |
| Shadow mode (`ShadowAuthorization`, `ShadowPolicyProvider`, `cosec.shadow.*` files) | Shadow windows of control-plane changes; see [Testing policies](../policies/testing.md). |
| `AccessAnalyzer` | The control plane's `analyze`. |
| `PolicyTestKit` (`cosec-core`) | `PolicyTestKit` in the `cosec-testkit` module ([Policy test suites](#policy-test-suites)). |
| The 7.x request model of `cosec-api`: `Request`, `Attributes`, `AppIdCapable`, `DeviceIdCapable`, `RequestIdCapable` (with their `AppId`, `DeviceId`, `RequestId` aliases), `COSEC_EXTEND_KEY_PREFIX`, `SpaceIdCapable.SPACE_ID_KEY`; `RequestSecurityContexts` and `InvalidRequestPathException` (`cosec-core`) | `SecurityContext.deviceId` and `SecurityContext.requestId` answer what `DeviceIdCapable` and `RequestIdCapable` gave (the client's `CoSec-Device-Id`, empty when it sent none; the id CoSec used in its audit records and problem responses). Adapters read `HttpFacts` and the security context carries the checked `scope`; there is no `Request` for an application. `SpaceIdCapable`, `SpacedRoleId` and the `SpaceId` alias are removed too: a space id is the value class `SpaceId` of `me.ahoo.cosec.model`, a request's space is `SecurityContext.scope.spaceId`, and a role in a space is written `role@space`. |
| `InternalIds` (`cosec-api`) | None. |
| `me.ahoo.cosec.limiter.RateLimiter` and its `LocalRateLimiter`, `RedisRateLimiter` (`cosec-cocache`) | `me.ahoo.cosec.spi.ratelimit.RateLimiter`, a suspending SPI; the starter registers its `LocalRateLimiter` or `ReactiveRedisRateLimiter` ([Rate limiting](../policies/rate-limiting.md)). `RateLimit` and `RateLimitResult` remain. |
| `Jwts.toPrincipal(String)`, `Jwts.decode`, `Jwts.verifyTimeClaims` | None: `Jwts` is internal to `cosec-jwt`. Turn a token into a principal with `TokenVerifier.verify`, which checks the signature, the expiry and the claims, then call `TokenRevocation.check` (`me.ahoo.cosec.runtime.token`, the starter's bean) on what it returns: `verify` alone accepts a revoked token. A revocation store that cannot be read throws `RevocationUnavailableException`, which your code decides on ([Verifying a token outside the pipeline](../integration/authentication.md#verifying-a-token-outside-the-pipeline)). |
| `PrincipalConverter`, `TokenVerifier.toPrincipal` | `TokenVerifier.verify`, then `TokenRevocation.check` on its result (the starter's bean), as above ([Verifying a token outside the pipeline](../integration/authentication.md#verifying-a-token-outside-the-pipeline)). |
| `Authentication.authenticate`, `TokenCompositeAuthentication.authenticateAsToken`, the social providers: `Mono<...>` | Suspending functions that return the value; a failed login throws ([Logging in](../integration/authentication.md#logging-in)). |
| `DefaultAuthenticationProvider` and `SocialProviderManager` as objects | Classes, one instance per application context: inject the `AuthenticationProvider` or `SocialProviderManager` bean. |
| `AuthorizationChecker.check(...)`: `Mono<Boolean>` | `AuthorizationChecker.check(security, action, resource)`, a suspending function; `cosec-webflux` adds a `Mono<Boolean>` form and `cosec-webmvc` a blocking one ([Checks](../integration/checks.md)). |
| Filters `ReactiveAuthorizationFilter`, `AuthorizationFilter` (servlet), `AuthorizationGatewayFilter`, and their constructors taking an `Authorization` | `PipelineWebFilter`, `PipelineServletFilter`, `PipelineGatewayFilter`, created by the starter. |
| `ReactiveInjectSecurityContextWebFilter`, `InjectSecurityContextFilter` (inject mode) | `cosec.pipeline.mode=gateway` behind a CoSec gateway ([Inject mode becomes identity propagation](#inject-mode-becomes-identity-propagation)). The 8.0 filters put the security context on every permitted request. |
| Path variables left in the caller's security context after authorization | None. Read path variables from your web framework. |
| `cosecPolicyGenerator`, `cosecAppPermissionGenerator` actuator endpoints | `cosecServiceGenerator/routes` (a route map) and `cosecServiceGenerator/app` (an app document). |
| `StringIdGenerator`, `UuidGenerator` (CosId) in `JwtTokenConverter`, `JustAuthProvider` and the starter's beans; in the 8.0 previews also `PipelineComponents.idGenerator` and `IdentityGate` | `IdGenerator` (`cosec-spi`, [Ids](../architecture/extending.md#ids)), `RandomIdGenerator` by default. `JustAuthProvider` takes no id generator: it draws its OAuth `state` from `SecureRandom`. |
| `SocialUserConverter` (`cosec-social`) | None: it is internal to `cosec-social`. Turn a provider's user into your principal with a `SocialUserPrincipalConverter` bean ([Social login](../integration/authentication.md#social-login)). |

Still available: `SecurityContext` (now with `scope`, the validated app, space and roles; read it rather than the
`CoSec-*` headers, which are what the client declared), the readers on `ServerWebExchange`, Reactor `ContextView` and
`HttpServletRequest`, now named `requireSecurityContext()` (throws) and `securityContextOrNull()` ([Web](../integration/web.md)), `AuditEventSink`, `TokenStore`, and the `cosec-api` audit types, some of them
in new packages ([Modules, artifacts and packages](#modules-artifacts-and-packages)).

During the 8.0 previews an application could replace, with beans of its own, the parts that enforce the control plane's
authorization and approvals: `ControlAuthorizer`, `ChangeAuthority`, `Changes`, `ControlPlane` and `ControlHttp`. It no
longer can: a second bean of one of these types fails startup (for `ControlHttp`, where the control routes are served).
`ControlAuthorizer`, `DocumentConfirmation`, `PipelineControlAuthorizer`, `ChangeAuthority`, `Changes`,
`DefaultControlPlane`, `ControlHttp`, `ControlHttpRequest` and `ControlHttpResponse` are `@InternalCoSecApi`, and
so are `ControlPlane` and the `Operator` every one of its methods takes: the control plane is not a library an application
calls, and an application writes through its HTTP API
([Roles from an external source](../scenarios/external-source.md)). `ControlStore`, `ChangeOptions`, the change listeners,
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

Every key below fails startup in 8.0, and the message links this table. So does any other `cosec.*` key 8.0 does not
read; no property turns the check off ([Unknown properties](./configuration.md#unknown-properties)). A key ending in
`.` stands for every key under it.

| 7.x | 8.0 |
|---|---|
| `cosec.authentication.enabled` | `cosec.authentication.login.enabled` |
| `cosec.authentication.social.` | `cosec.authentication.login.social.` |
| `cosec.authorization.allow-query-token` | `cosec.pipeline.allow-query-token` |
| `cosec.api-key.header` | `cosec.pipeline.api-key-header` |
| `cosec.authorization.enabled` | Removed, and its replacement is a document, not a switch: the service decides with a platform document, and until one is installed every request is `503` and readiness refuses traffic. `cosec.enabled=false` switches every CoSec bean off, login and token issuing included, so a service that logs users in and enforces nothing does not use it ([A service that only issues tokens](../integration/authentication.md#a-service-that-only-issues-tokens)). A service that verifies tokens another service issues and decides in its own code runs in `enforce` mode over a document that permits its authenticated callers, and must read the issuer's revocations ([A service that verifies tokens another service issues](../scenarios/resource-server.md)). |
| `cosec.authorization.local-policy.` | Removed: convert and publish the files with `cosec-migrate` ([Migrating the data](#migrating-the-data)). |
| `cosec.authorization.root-enabled` | Removed: root becomes break-glass ([Root becomes break-glass](#root-becomes-break-glass)). |
| `cosec.authorization.cache.enabled` | Removed: snapshot caching is `cosec.authorization.snapshot.*`. |
| `cosec.authorization.cache.policy.` | Removed: snapshot caching is `cosec.authorization.snapshot.*`. |
| `cosec.authorization.cache.role.` | Removed: snapshot caching is `cosec.authorization.snapshot.*`. |
| `cosec.authorization.cache.history.` | Removed. |
| `cosec.authorization.cache.migration.` | Removed. |
| `cosec.authorization.cache.token.concurrency-level` | Removed: ignored since CoCache 5. |
| `cosec.authorization.cache.token.expire-after-write` | `cosec.authentication.revocation.cache.local-max-age`, a duration (`30s`). |
| `cosec.authorization.cache.token.expire-unit` | Removed: the durations carry their unit. a bare number of seconds is still read as seconds. |
| `cosec.authorization.gateway.enabled` | Removed: the gateway filter comes with `cosec-gateway`. |
| `cosec.limiter.` | Removed: rate limit rules are in the platform document ([Rate limit rules become obligations](#rate-limit-rules-become-obligations)); a different limiter key prefix needs a `RateLimiter` bean of your own. |
| `cosec.shadow.` | Removed: shadow windows are control-plane operations; each instance's part is `cosec.authorization.shadow.*`. |
| `cosec.explain.enabled` | Removed: use the control plane's `explain`. |
| `cosec.inject.enabled` | Removed ([Inject mode becomes identity propagation](#inject-mode-becomes-identity-propagation)). |
| `cosec.api-key.store-blocking` | Removed. |
| `cosec.jwt.token-revocation.enabled` | Removed: revocation is always on ([Token revocation is always on](#token-revocation-is-always-on)). |
| `cosec.authorization.cache.key-prefix` | `cosec.key-prefix`, the prefix of every key: copy the token revocations first ([which families move](./upgrading-previews.md#from-8-0-0-alpha-2)). |
| `cosec.api-key.key-prefix` | `cosec.key-prefix`: copy the API keys first ([which families move](./upgrading-previews.md#from-8-0-0-alpha-2)). |
| `cosec.authorization.snapshot.key-prefix` (8.0 previews) | `cosec.key-prefix`: set it to this prefix ([which families move](./upgrading-previews.md#from-8-0-0-alpha-2)). |
| `cosec.unknown-properties` (8.0 previews) | Removed: an unknown key always fails startup. |

`cosec.authorization.cache.token.*` is now `cosec.authentication.revocation.cache.*`.

New groups: `cosec.pipeline.*` (timeouts, query and cookie tokens, API key header, trace level, mode),
`cosec.gateway.identity.*`, `cosec.authorization.scope.app-id`,
`cosec.authorization.{membership,attributes,rate-limit}.timeout`, `cosec.authorization.routes.location`,
`cosec.authorization.snapshot.*`, `cosec.authorization.shadow.*`, `cosec.authentication.revocation.*`,
`cosec.audit.kafka.delivery`, `cosec.observability.listener.*` and `cosec.control.*`.

Two defaults need a decision during the upgrade:

- `cosec.authorization.routes.location` is unset, and an unset host loads no route map: every request is an action of
  the `http` namespace on its path. A host with a map names it; one left at `classpath:cosec-routes.json` with the
  property unset fails startup. A gateway loads no route map.
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
  `SCOPE_REJECTED`, `RATE_LIMITER_UNAVAILABLE`, `TOKEN_REVOKED`, `BOUNDARY`. `authorization.source` says what decided
  in terms of the binding model: `via`, `document`, `policy` and `statement` replace the source `type`, `policy` and
  `role`, and `principal.policies` and `request.device.userAgent` are gone
  ([Audit events](./observability.md#audit-events)). `elapsedNanos` now covers the whole pipeline (authentication,
  attributes, rate limits), not only the authorization. An `AuditEventSink` still receives the flat record; one that
  reads those fields changes with them.
- **Metrics**: the `cosec.authorization` timer covers the whole pipeline too. Its `source` tag is now `binding`,
  `attachment`, `builtin`, `boundary`, `scope` or `none` (7.x: `global_policy`, `principal_policy`,
  `role_permission`, `root`, `blacklist`, `none`, `unknown`); `result` names what decided in the decision event's words (`permit`,
  `explicit_deny` for a DENY statement only, `scope_rejected`, `boundary`, `invalid_request`, `indeterminate`,
  `cancelled`; 7.x: `allow`, `explicit_deny` for every refusal); `cause` is new, on `indeterminate` only. New counters: `cosec.listener.dropped`,
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

**Next:** before the deployment carries traffic, work through the [hardening checklist](../guide/security-model.md#hardening-checklist).
