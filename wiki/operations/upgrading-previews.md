---
title: "Upgrading Between 8.0 Previews"
description: "What changed from each 8.0 preview (alpha and beta) to the next, newest first, for a deployment that already runs one."
---

# Upgrading Between 8.0 Previews {#between-8-0-previews}

Only for a deployment that already runs an 8.0.0 preview: what changed from each preview to the next, newest first. A 7.x
deployment needs none of it: [Upgrading](./upgrading.md) is the whole upgrade. These notes are removed when 8.0 is released.

## Start here {#start-here}

Each section below is what changes when you leave that preview. Read the section of the version you run and every
section above it:

| You run | Read |
|---|---|
| `8.0.0-beta.7` | [From beta.7](#from-8-0-0-beta-7) |
| `8.0.0-beta.6` | [From beta.6](#from-8-0-0-beta-6), then the one above |
| `8.0.0-beta.5` | [From beta.5](#from-8-0-0-beta-5), then the two above |
| `8.0.0-beta.4` | [From beta.4](#from-8-0-0-beta-4), then the three above |
| `8.0.0-beta.3` | [From beta.3](#from-8-0-0-beta-3), then the four above |
| `8.0.0-beta.2` | [From beta.2](#from-8-0-0-beta-2), then the five above |
| `8.0.0-beta.1` | [From beta.1](#from-8-0-0-beta-1), then the six above |
| `8.0.0-alpha.4` | [From alpha.4](#from-8-0-0-alpha-4), then the seven above |
| `8.0.0-alpha.3`, `alpha.2`, `alpha.1` | [From alpha.3](#from-8-0-0-alpha-3), [alpha.2](#from-8-0-0-alpha-2) or [alpha.1](#from-8-0-0-alpha-1), then every section above it |

Which preview of the library may run behind which preview of the gateway image: see
[Upgrading a running deployment](./upgrading.md#upgrading-a-running-deployment).

## From 8.0.0-beta.7 {#from-8-0-0-beta-7}

- **A head lost in a failover is reported** (`CTL-86`, DR1-F-7). Affected: a change listener that handles every kind
  (one with an exhaustive `when` needs an `else`, as the kind's KDoc says), and Redis deployments that fail over. A new
  event kind, `HEAD_LOST`, and a counter, `cosec.control.revisions.lost`, name a revision whose head write was lost;
  set `min-replicas-to-write` on Redis ([Storage](./storage.md#redis-requirements)).

- **A document's revision records are bounded within `min-age` too** (`CTL-84`, DR1-F-6). Affected: a document that
  publishes large documents often. Beyond its `max-count` most recent revisions, a document keeps at most `max-count`
  times the document cap of records (400 MB) and 26,214 index entries; past either, the oldest go, younger than
  `min-age` or not, counted by `cosec.control.revisions.reclaimed-young`, with a `WARN`. Four meters are new:
  `cosec.control.revisions.records`, `.bytes`, `.reclaimed-young` and `cosec.control.changes.open`; sum the gauges
  across instances ([Observability](./observability.md)). Each revision index entry now names its record's size; an
  older control plane reads past it, and an entry it writes without one is sized from its record by the next pass.

- **A document takes no new change once it holds `cosec.control.revisions.max-count` open changes** (50 by default,
  `CTL-83`, DR1-E-2; counted before the write, so changes proposed at the same moment may all pass).
  Affected: a client that leaves many changes open on one document. A proposal, an `:apply` or a rollback past the
  limit first abandons its author's own oldest change whose checks failed, then is refused `409` with the new conflict
  `OPEN_LIMIT`, naming the oldest open change in `heldBy`; a typed client sees `ConflictKind.OPEN_LIMIT`. Abandon the
  changes you no longer need. A document already over the limit when you upgrade is not touched: its changes expire
  within `cosec.control.changes.expire-after` (7 days) and bring it back under the limit, and until then it takes no new
  change.
- **A document keeps at most `max-count` ended changes, and reclamation removes old ones** (`CTL-83`, DR1-E-2).
  Affected: a client that reads a change long after it ended. Ending a change removes the document's oldest ended
  changes past `cosec.control.revisions.max-count`, whatever their age, and the hourly reclamation removes those older
  than `cosec.control.revisions.min-age` whose change published no revision still retained. `GET /changes/{id}` of a
  removed change is `404`. Read who published a revision from the revision itself:
  `GET /documents/{key}/revisions/{revision}` names its change, author and approvers in `provenance`, and `explain`
  still decides on it. A `ControlStore` of your own implements `endedChanges` and `removeChange`. Changes that ended
  before the upgrade are not listed, so nothing removes them: delete their `<s>:control:change:<id>` keys yourself if
  you want them gone.
- **A control route that carries no document reads at most 64 KB** (DR1-F-3). Affected: a client that posts more than
  64 KB to `simulate`, `explain`, `analyze`, a step of a change, a rollback or break-glass. Such a body is now a `400`
  before it is parsed, where it was read and parsed up to 10 MB before anything was authorized. Proposals, `:apply` and
  their checks still read up to 10 MB. A simulated request larger than 64 KB has more headers and attributes than a real
  request can carry: trim it.
- **Breaking: a gateway host with handlers of its own does not start until they are listed** (`STA-13`). Affected: a
  Spring Cloud Gateway host in enforce mode whose application declares its own `@Controller`, `@RestController` or
  `RouterFunction` beans.
  - Those handlers are mapped before any gateway route, so CoSec, a filter of gateway routes, never decided them. Startup
    used to warn and go on.
  - Now it fails, naming each handler by bean name. Migrate by moving them to a service behind the gateway, where a route
    decides them, or by listing the ones something else protects in `cosec.gateway.unprotected-handlers`. Startup then
    names them in one INFO line.
  - A name with `*` is refused. Spring's, CoSec's and actuator handlers are not the application's and need no entry
    ([The gateway's own handlers](../integration/web.md#the-gateways-own-handlers)). A library's controller is: springdoc's
    `openApiResource` on a gateway host is refused like your own (turn it off with `springdoc.api-docs.enabled=false`, or
    list it).
- **An `explain` at a retained revision is cached like a head** (DR1-F-4). Affected: nothing to change. Repeating it
  compiles nothing, where each call compiled its documents again.
- **Session records are kept no longer than their refresh token, and 64 per principal** (`IDN-35`, DR1-G-2).
  Affected: every deployment that issues CoSec's tokens, and a `TokenFamilyStore` of your own.
  - A session record (`<s>:token:family:<sid>`) used to stay in Redis for the whole session lifetime (30 days), even
    when nobody refreshed it after a week. Each write now keeps it for the shorter of what is left of the session and
    `cosec.jwt.token-validity.refresh`.
  - A principal keeps the 64 sessions that began most recently in a tenant, counted in the new key
    `<s>:token:sessions:...`. A login or a switch past them deletes the earliest, whose next refresh is `401
    token-invalid`: that device signs in again.
  - Records written before keep their lifetime and are not counted. They leave Redis as they did.
  - **A `TokenFamilyStore` of your own** may keep a family until its `expiresAt` as before, which the contract now
    calls an upper bound. It should keep `TokenFamilyStore.SESSIONS_PER_PRINCIPAL` sessions per principal and tenant,
    as CoSec's do ([Extending](../architecture/extending.md)).
- **`analyze` shows a tenant's bindings only to a caller who may read its document** (`CTL-51`, DR1-F-8). Affected: a
  principal granted `cosec:analyze` on its tenant's document but not `cosec:read` (no built-in role is shaped so). It
  was answered with every matching binding of that document; it now gets none, and the document is counted in `hidden`.
  Grant `cosec:read` with `cosec:analyze`, as `cosec:tenant-viewer` does.
- **A token revocation outlives the token it names** (`IDN-37`). Affected: a deployment whose
  `cosec.jwt.token-validity.access` is longer than `cosec.jwt.token-validity.refresh`. A revocation was kept for the
  refresh token's lifetime, so an access token valid longer was accepted again once its record expired. It is now kept
  for the longer of the two, plus `cosec.authentication.revocation.skew` (an issuer's clock may run that far ahead), and
  startup logs the value. Records written before keep their lifetime. The control plane's check that a revocation
  outlives an approval (`cosec.control.approval.expire-after`) uses the same value, and its message changed: it now
  begins `A token revocation is kept for` ([Troubleshooting](./troubleshooting.md)).
- **`HttpControlService` relays a user's attributes** (`CTL-77`, DR1-F-1). Affected: an application that calls a
  control-plane host through `HttpControlService` as the user whose request it serves (`ControlCaller.of`), with a
  control policy that reads `subject.attributes`. Before, no attribute went with the call, so a DENY on one did not match
  over HTTP and the call was decided by the ALLOWs alone, where the in-process facade refused it. Now every attribute
  goes, each as the text the engine reads, and such a DENY refuses through both. The attributes now reach the host,
  behind the service port's network policy. A user whose attributes exceed the header (8 KB in all, 1,024 characters
  for one string) can no longer call through it: the call fails before it is sent, naming the attribute and its size.
  Upgrade the hosts before the callers if you can; a beta.7 host reads the attributes too
  ([Deployment](./deployment.md#the-control-plane-host)).
- **A change too large to analyse is one `TOO_LARGE` entry** (`CTL-49`, DR1-E-1). Affected: a tenant change whose
  granted ALLOWs come to more than 80,000 fragments (each action times each resource, for each binding that reaches
  them), and what reads a change's escalations. Such a change is counted before it is split: none of it is analysed,
  and it waits for approval with the one entry `change of tenant:<t>`. Before, it was split whole, one escalation each,
  and one statement of a thousand actions and a thousand resources could exhaust the control plane's heap. The grants an
  analysis reaches past its four million comparisons are one entry too, `<n> fragments of tenant:<t>`, where each was
  its own. An approval of such a change in beta.7 does not cover the new entry: approve it again.
- **A service calling the control plane as its own principal is recorded as `service`** (`ControlCaller.service`,
  `IDN-34`). Affected: what reads the records of control requests, and the order you upgrade in.
  - **Records.** The identity such a service states names its credential `service`, where beta.7 named it `api-key`.
    It is not an API key, and records now say so: `subject.method` in the decision events and audit of its requests to a
    control-plane host, and `origin.via.method` in the change log, are `service` (a call through the in-process
    `ControlService` stays `in-process`). Changes stored before keep `api-key`. A dashboard, alert or query that finds
    service callers by `api-key` must look for `service` too.
  - **What it may do does not change.** `service`, like `api-key`, is a workload's credential: it never approves and is
    never the trust root, and it has no session and no break-glass (`AuthenticatedPrincipal.workload`, new).
  - **Upgrade the control-plane hosts first, then the services that call them.** A beta.7 host does not know `service`
    and takes it for a person's credential. On its service port that changes nothing: approvals are not served there and
    nothing there is the trust root. But a service whose `cosec.control.service-url` names an operators' port of a beta.7
    host (one that believes `CoSec-Identity`) could approve changes, and be the trust root if it is a platform
    administrator holding `cosec:approve`, where beta.7's `api-key` could do neither. An upgraded caller of a beta.7
    host's service port is safe; point no service at an operators' port in any case. A beta.7 caller of an upgraded
    host keeps sending `api-key`, which the upgraded host still takes for a workload's.
- **Your own `Authenticator` that names its credential `service`** now names a workload's: its principals can no longer
  approve a change or be the trust root, as for `api-key`. Affected: only an authenticator that used that name. Rename
  its method if its principals are people; otherwise nothing to do
  ([Extending CoSec](../architecture/extending.md)).
- **A request whose server reports no peer address reads its client address as unavailable** (`PIP-17`, DR1-C-8).
  Affected: a host whose server reports no peer, Reactor Netty listening on a Unix domain socket for one, with a
  statement or the blacklist on `environment.ip`. Before, the address was empty, read as absent: a DENY on it did not
  match and the IP blacklist was not asked. It is now `unknown`, as a trusted proxy's `unknown` entry is: such a DENY,
  and the blacklist, answer `503`, alike behind the three adapters. Put a proxy that writes `X-Forwarded-For` in front
  of such a host and set the index to `1` or more ([Web integration](../integration/web.md)); a host whose server
  reports its peers is unchanged.
- **A rate limit key value longer than 256 characters names its bucket by its hash** (`OBL-6`, DR1-C-1). Affected:
  rules whose `key` reads values that long, nearly always a header a client chooses. Such a bucket is now
  `h:<sha-256>` where it was `k:<value>` ([Rate limiting](../policies/rate-limiting.md#keys)), so its count starts
  again once: on Redis the old key is no longer read and expires after two windows; the local limiter's counts are the
  instance's anyway. While a rolling upgrade has both versions serving, such a value counts in two buckets, one per
  version, so it gets at most twice its limit until the last instance is upgraded. Values of 256 characters or fewer
  keep their buckets and counts. Nothing to change.
- **The generated route map no longer depends on who fetched the API docs last** (`RTE-4`, DR1-C-2). Affected: a
  route map from `cosecServiceGenerator/routes` on a host whose OpenAPI document writes no `servers`. springdoc then
  puts a server of its own into the document it caches, from the last request for the API docs, query string
  included, and the routes were generated under its path. That server is now left out, and the routes sit at the root
  of the application. Generate the route map again if one was generated under a path such as `/v3/api-docs`, and
  write `servers` in the document when the service is served under a path the host does not remove
  ([Role permissions](../policies/role-permissions.md#generating-a-catalog-from-openapi)).
- **A cancelled `HttpControlService` call now ends its request on the host** (`CTL-82`, #1893). Affected: nothing to
  change. When the coroutine of a call through `HttpControlService` is cancelled, the call closes its HTTP exchange and
  the control-plane host (WebFlux) cancels the request. Before, the request stayed on the host until the call's own
  timeout (30 seconds by default) ended it. A control call has no deadline of its own: yours decides, a `withTimeout`
  around the call or the `timeout` of `HttpControlService`
  ([Changing policies from your code](../integration/checks.md#changing-policies-from-your-code)).
- **Memberships are held by each instance, never in Redis** (`MEM-5`, DR1-D-2). Affected: applications with a
  `MembershipLoader`. Before, every instance read and wrote a shared cache under `<s>:membership:*`, and read whatever
  was there as the loader's answer, so any data-plane Redis credential could grant roles through it. Now each instance
  keeps what its own loader answered, in its own memory, and nothing is read from Redis:
  - **Behaviour.** Where a second instance's miss was one Redis read, it is now a call of your loader: N instances make
    up to N loads of a principal per lifetime. Size your loader for it. `invalidate(tenant, principal)` still drops the
    principal on every instance, by Redis's invalidation channel (`<s>:inv:membership`), which needs no key permission.
  - **Configuration.** The lifetime is `cosec.authorization.membership.ttl` (one minute, as before). Any
    `cocache.caches.MembershipCache.*` setting, its `ttl` above all, fails startup, naming that property: move it.
    The memberships' memory is a 64th of the heap, on the startup line.
  - **Redis.** The `<s>:membership:*` keys are no longer read or written: delete them when every instance runs this
    version (`redis-cli --scan --pattern '<s>:membership:*' | xargs redis-cli del`), and take `~<s>:membership:*` out of
    the data plane's ACL ([Who may write which keys](./storage.md#who-may-write-which-keys)). While old and new instances
    run side by side, an old instance's `invalidate` still reaches the new ones (same channel, same frames).
- **The entry budgets count what roles and catalog short forms derive** (`CTL-40`, DR1-B-1). Affected: documents whose
  roles list many permissions, or app catalogs with many permissions that have an `action`. `maxRoleBindings` now counts
  a role's bindings (one per permission it lists, all of the catalog's for `"*"`) with the written ones, and its
  default is now 20,000 (was 10,000), so that 100 roles of 100 permissions fit with room to spare; and
  `maxPolicies` and `maxStatements` count one policy of one statement per catalog permission with an `action`. A change
  that would raise such a count over its budget, which beta.7 published, now stays a draft with a `V2` error; a stored
  document already over keeps being served and may change as long as it does not grow. To see whether one is: run `POST /changes:check` on
  any change to it ([Checking without proposing](../policies/testing.md#checking-without-proposing)), and a `V2` warning names each count over its budget and the limit
  ([Entry budgets](../policies/language.md#entry-budgets)); or count its derived bindings, roles' permissions summed:
  `jq '[.roles[]?.permissions | if type == "array" then length else 0 end] | add + ([.bindings[]? | select(.subject.role)] | length)'`
  (a `"*"` role adds the number of its app's catalog permissions). Raise the budgets in the platform's `limits` if the
  document needs them. Each 1,000 derived bindings spread over distinct permissions cost the check of a tenant
  administrator's change about 0.5 s, and each binding about 240 bytes on every instance that holds the document.
- **In the deny direction a trailing `/` does not count** (`PIP-35`, DR1-C-9). Affected: every deployment, in the
  direction of fewer permits. A DENY statement or a rate limit rule on `/orders/{id}` now also applies to
  `/orders/123/`, and one on `/orders/` to `/orders`; ALLOWs and the platform boundary still match the path as written.
  On a host with a route map, a request whose path ends with `/`, maps to no route and maps without it (`DELETE
  /orders/123/` for a route `/orders/{id}`) is answered `400` `invalid-request` where it used to be decided in the `http`
  namespace. A client that sends such paths to a mapped host should drop the trailing `/`
  ([Web integration](../integration/web.md)).
- **Social sign-in states are bounded** (DR1-D-9). Affected: deployments that serve social login. A deployment holds
  at most a 64th of the memory Redis reports in sign-ins in progress (512 bytes each; 100,000 when Redis does not
  report its memory), and a start past it is refused with a `SocialAuthenticationException` ("try again shortly")
  where it used to write two more keys. The process serving social login needs `INFO` on Redis, as `SNP-3` already
  asks; without it the fixed bound applies, with a WARN. `InMemorySocialStateStore` holds at most 10,000. Nothing to
  change, beyond rate limiting the start route at the edge ([Storage](./storage.md#redis-keys)).

## From 8.0.0-beta.6 {#from-8-0-0-beta-6}

Nothing to change: no property, format, status code, stored record or public signature that a beta.6 deployment uses
changed, and the gateway and control-plane images differ only by their version.

- **New in the test kit: `IdentityHeader.ONLY_GATEWAY_CAN_REACH_KEY`** (#1876). Affected: nothing. It is the key of
  `IdentityHeader.ONLY_GATEWAY_CAN_REACH` alone, for a test that starts the application with a launcher taking a
  property map: `mapOf(IdentityHeader.ONLY_GATEWAY_CAN_REACH_KEY to "true")`
  ([Testing your application](../policies/testing.md#testing-your-application)).

## From 8.0.0-beta.5 {#from-8-0-0-beta-5}

- **New: a host renders the body of a refusal** (`ProblemRenderer`, `PIP-34`, #1840). Affected: nothing unless you
  declare one. A host framework with its own problem catalog declares one `ProblemRenderer` bean, and every refusal
  behind the three adapters and the served login routes has its body; the status and the headers stay CoSec's, and a
  renderer that fails is replaced by CoSec's body (`cosec.problem.render.failed`). Two renderers fail startup. Keep
  CoSec's `type` and `code` in the body, or CoSec's TypeScript client cannot read those refusals
  ([Problem renderer](../architecture/extending.md#problem-renderer)).
- **CoSec is built on Spring Boot 4.2 and Spring Cloud 2026.0.** Affected: an application on Spring Boot 4.1, or with
  `cosec-gateway` on Spring Cloud 2025.1. CoSec's modules name the versions they are built on as minimums
  ([What CoSec needs from your build](../guide/introduction.md#what-cosec-needs-from-your-build)): upgrade the application
  to Spring Boot 4.2 (and Spring Cloud 2026.0.0 with `cosec-gateway`) as Spring Boot's 4.2 release notes describe; a
  mixed classpath of Spring Boot 4.1 and 4.2 modules is not supported.
  - **The forwarded-header strategy is `server.request.header.forwarded.strategy`** (`web-server`, `framework`, `none`),
    as Spring Boot 4.2 names it. CoSec reads it, and still reads the deprecated `server.forward-headers-strategy`
    (`native` is `web-server`), to decide how to read the client address; its startup messages name the new key
    ([Client IP behind proxies](../integration/web.md#client-ip-behind-proxies)). Setting both fails Spring Boot's
    startup.
  - Nothing to do for CORS: Spring MVC no longer declares a `CorsConfigurationSource` of its own (Spring Framework 7.1),
    so CoSec's CORS filter (`STA-5`) still runs exactly when the application declares one.
- **`cosec-cocache` needs CoCache 8.2.0** (its POM and module metadata name it as the minimum). Affected: an
  application that pins CoCache 8.1.x. On Spring Boot 4.2, whose Lettuce 7.8 CoCache 8.1.0 was not built for, a Redis
  Cluster connection rejects its first commands (`Currently not connected. Commands are rejected.`); 8.2.0 connects
  the primaries first. Two behaviours change with it:
  - The startup eviction check of CoCache's `Store`s retries a read that times out (up to five attempts), and its
    failure says why: Redis refused `INFO`, or did not answer
    ([Troubleshooting](./troubleshooting.md#startup-fails-on-the-eviction-check)). CoSec's own check (`SNP-3`) reads
    the same way at startup: a slow Redis is asked up to five times before the start fails, and the message names a
    timeout (`Redis did not answer INFO memory ...`) or a refusal (`Redis refused INFO ...`). The old reason
    `INFO memory failed or is not allowed` is gone: an alert on that text matches nothing now. The periodic re-check
    still reads once per interval, with the same reasons.
  - Stored content that decodes to `null` fails the read instead of reading as absent. CoSec writes no such content;
    an entry another writer left as a JSON `null` in a revocation table now fails the request as foreign content where
    it used to read as "not revoked".
- **A path segment is bounded once decoded** (`PIP-5`, #1853). Affected: clients whose path segments are long only
  percent-encoded, typically identifiers in a script other than Latin. Through beta.5 a segment over 256 characters as
  received was `400` `invalid-request`, so 29 CJK characters (261 encoded) were refused while 256 ASCII ones passed. A
  segment is now counted as the matchers see it, decoded, in UTF-16 units: such a request that was refused is now
  carried and decided. The whole path is still bounded at 2,048 characters as received, so one segment of CJK text
  holds at most 227. Every other refusal is unchanged, inside a long segment too: an encoded `/`, `;` or `\`, a control
  character, a malformed escape, a dot or empty segment. A typed id that the control plane simulates, explains or
  analyzes is still bounded as written, and so is a path for an ALLOW whose pattern sets `decodeAndParseSegments:
  false`: it reads the segments as received, so it matches no path with a segment longer than 256 characters as
  received, and such a request is decided by the other statements. Nothing to change unless you relied on the refusal.
- **New: the role class, `cosec:manage-roles`** (`CTL-79`). A holder's change of a tenant's document that only puts or
  removes roles no longer waits for a person on account of who grants; it records a `ROLE_CLASS` escalation, a new
  value of the closed set, which a client that maps escalation reasons reads ([The role class](../policies/testing.md#the-role-class)).
  Nothing changes for a principal that does not hold it. **A role of the control plane's own app
  (`cosec.control.app-id`) is never the class's**: a member of it holds control calls on its tenant's document, and who
  governs a tenant's policies is for a person to appoint. A sync that projects such roles keeps waiting for approval;
  project only roles of business apps through the class.
- **New: a service changes an app's document through the service port and `ControlService`** (`CTL-58`, `CTL-65`):
  `propose`, `check` and the change flow of an app document, which were a `404` there. Such a change is analyzed as on
  the operators' port: removing a role, a policy or a binding publishes alone, and anything else (a catalog, a role
  put) waits for a person. The platform document stays outside. Nothing changes for a service that does not hold
  `cosec:write` on `doc/app/...`.
- **No trust root through the service port or `ControlService`** (`CTL-81`). A service that stated a platform
  administrator there to publish a tenant's (or an app's) document unanalyzed now waits for a person: the change is
  analyzed whoever is stated, and a grant the analysis cannot prove is `AWAITING_APPROVAL`. Project roles into tenants
  through the [role class](../policies/testing.md#the-role-class) (`cosec:manage-roles`), or have a person approve on
  the operators' routes. The same principal on the operators' routes is unchanged.
- **An instance's status is split in two** (`CTL-74`, `CTL-75`). The status at `<s>:shadow:status:<instance>` is now
  small (liveness, `shadows`, the acknowledged windows) and has no `documents` member. The documents an instance holds
  are a record of their own, `<s>:shadow:resident:<instance>`, which only `propagation` reads
  ([Storage](./storage.md#redis-keys)). The `propagation` answer has a new member, `unknown`, always written.
  - **Upgrade in this order: every control-plane instance first, then the data plane.** A beta.6 control plane reads a
    beta.5 instance's status (the `documents` it carries is ignored) and counts that instance in `unknown`, since it
    writes no resident record, until it is upgraded.
  - **A beta.5 control plane cannot read a beta.6 status**: it requires `documents`. Under `CTL-73` it takes each
    beta.6 instance as live and acknowledging nothing, so every shadow window waits and closes as insufficient at
    `max-duration`. No window passes and no change publishes wrongly; finish upgrading the control plane and the windows
    close as usual.
- **A data-plane Redis user needs the resident keys.** If you copied the data-plane user of
  [Storage](./storage.md#redis-keys), add `~cosec:shadow:resident: ~cosec:shadow:resident:<instance>.*` (your prefix and
  instance id) beside its `shadow:status` keys; without them a beta.6 instance fails at startup with `NOPERM`.
- **SPI (experimental): `ShadowReportSink.write(ResidentDocuments)` and `ShadowReportReader.residents(instances)` are
  new and abstract; `InstanceStatus.documents` and `InstalledDocument` are removed.** A backend of your own stores a
  second record per instance, with a life of `ShadowReportReader.RESIDENT_TTL`, and answers `residents` with the records
  that read and, in `unreadable`, the instances whose record is missing or does not decode
  ([Extending](../architecture/extending.md)). There is no default: one that answered "holds nothing" would make
  `installed == holding` say that no instance is behind when it does not know.
- **The control plane's facade is a module of its own, `cosec-control-sdk`** (`CTL-65`). Affected: code that calls
  `ControlService`. `ControlService`, `ControlCaller` and `ControlRefusedException` keep their names and package
  (`me.ahoo.cosec.control.sdk`); `cosec-control` brings the module, so a build that has `cosec-control` changes nothing.
  Code that only calls a control-plane host needs `cosec-control-sdk` and its HTTP implementation, `cosec-control-http`
  (`cosec.control.service-url`), instead of writing `CoSec-Identity` itself.
  - `ControlService.changes` filters by `me.ahoo.cosec.control.sdk.ChangeState`, the wire's closed set, which keeps an
    unknown value's text (`isUnknown`), instead of the control plane's internal enum: pass `ChangeState.READY` of the
    new package, or `ChangeState.of("READY")`.
  - `ControlCaller.of(context)` no longer throws for a security context CoSec did not make: the call it is passed to
    does, with the same `IllegalArgumentException` (`PIP-29`).
- **Every stage's timeout is a share of `cosec.pipeline.timeout`, and ten keys are gone** (`PIP-33`, `OBS-10`; what can
  be computed is not configured). Affected: a configuration that sets one of them, and code that set a timeout in
  `Pipeline.builder`'s `options { }` or `listeners { }`. Startup fails listing the keys CoSec does not read, saying of
  each that it is no longer a property and what replaced it. Remove the key; if you moved the value, move
  `cosec.pipeline.timeout` (or the value named below) instead. At the default deadline of 5 s every value is the default
  it had, so a deployment that set none answers as before ([Computed, not configured](./configuration.md#computed-not-configured)).

  | Removed | Now |
  |---|---|
  | `cosec.pipeline.authentication-timeout` | 20 % of `cosec.pipeline.timeout` |
  | `cosec.authorization.membership.timeout` | 10 % of it, also the membership cache's fill |
  | `cosec.authorization.attributes.timeout` | 6 % of it, for an `AttributeProvider` that sets no timeout of its own |
  | `cosec.authorization.rate-limit.timeout` | 10 % of it |
  | `cosec.authentication.revocation.read-timeout` | 30 % of the authentication timeout: a token's two reads end inside it by construction, and the startup check that compared the two is gone |
  | `cosec.observability.listener.required-timeout` | 20 % of `cosec.pipeline.timeout` |
  | `cosec.control.audit.required-timeout` | `cosec.pipeline.timeout` itself, the bound of the control request's own authorization |
  | `cosec.observability.listener.drain-timeout` | a sixth of `spring.lifecycle.timeout-per-shutdown-phase`, for the change listeners and the Kafka listeners too |
  | `cosec.authorization.snapshot.local-max-age` | 3 × `cosec.authorization.snapshot.revalidate-interval` |
  | `cosec.authorization.membership.unknown-ttl` | a constant, 5 s |

  - `PipelineSettings` (in `options { }`) keeps `timeout` and loses `authenticationTimeout`, `membershipTimeout`,
    `attributeTimeout` and `rateLimitTimeout`; `ListenerSettings` (in `listeners { }`) loses `requiredTimeout` and
    `drainTimeout`, and with the next entry the class itself. Set `timeout` instead, or delete the line.
  - An `AttributeProvider` with a timeout of its own, and a `RevocationReader` of your own, keep their own bounds.
  - With a `cosec.pipeline.timeout` below 20 ms, the shares are floored at 1 ms, and a token's two revocation reads may no
    longer end inside the authentication timeout.
  - **A deployment with a `cosec.pipeline.timeout` below the default** also shortens the revocation read, the one stage
    that fails open: at `1s` each read gets 60 ms where it had 300 ms, and a read past it accepts the request's token
    unless `cosec.authentication.revocation.fail-closed=true`. Set `fail-closed`, or keep the default deadline.
- **Memory bounds are a share of the heap, and three keys are gone** (`STA-12`, `OBS-11`, `IDN-32`, `IDN-33`; what can
  be computed is not configured). Affected: a configuration that sets one of them, and code that called
  `Pipeline.builder`'s `listeners { }`. Startup fails listing the keys CoSec does not read, saying what replaced each.
  Remove the key; to hold more, give the instance more heap. At the production overlay's 2 GiB heap no bound is below
  the count it replaced; a smaller heap gets less, and the startup line says what each came to
  ([Computed, not configured](./configuration.md#computed-not-configured)).

  | Removed | Now | At 2 GiB | Too tight shows as |
  |---|---|---|---|
  | `cosec.authentication.revocation.cache.maximum-size` (500,000) | an 8th of the heap, counted at 290 + 230 bytes an entry of each table; CoSec locks the count | 516,222 per table | `cocache.misses` (`cocache_misses_total`) with the tag `cache=RevokedTokenCache` or `RevokedSubjectCache` |
  | `cosec.authentication.token-cache.max-entries` (16,384) | a 16th of the heap, each entry weighed by its token's length × 5 bytes | 128 MiB, more than 16,384 large tokens | `cosec.token.cache.evictions` |
  | `cosec.observability.listener.queue-capacity` (1,024) | a 64th of the heap, shared by the `BEST_EFFORT` listeners, counted at 1 KB an event, 8 KB with `cosec.pipeline.trace-level` `full` or `exhaustive` | 8,192 events each for four listeners, 1,024 traced | `cosec.listener.dropped` |

  - `ListenerSettings` and `PipelineBuilder.listeners { }` are removed: nothing about how the listeners run is set.
    Delete the block.
  - A `cocache.caches.[RevokedTokenCache].local-maximum-size` (or `RevokedSubjectCache`) now fails startup: the count
    is locked.
  - The change listeners' 1/32 of the heap is unchanged, and now in the same table.
- **The snapshot families' bounds are derived from a quarter of the heap, and four keys are gone** (`SNP-24`,
  `STA-12`). Affected: a configuration that sets one of them, and code that set them in `Pipeline.builder`'s
  `snapshots { }`. Startup fails listing the keys CoSec does not read, saying what replaced each. Remove the key; to hold
  more documents, give the instance more heap ([Computed, not configured](./configuration.md#computed-not-configured)).

  | Removed | Now |
  |---|---|
  | `cosec.authorization.snapshot.max-apps`, `max-tenants` | what each family holds plus the room left of a quarter of the heap, less the absent entries' part, counted at the typical weight of its resident documents (an estimate never below what one retains), recomputed each revalidation pass and before each eviction, never below 64 |
  | `cosec.authorization.snapshot.max-absent-apps`, `max-absent-tenants` | a fixed sixteenth of the documents' quarter at 512 bytes an entry: 256 app ids and 65,280 tenant ids at 2 GiB |

  - **A gateway that relied on 16,384 resident tenants at 2 GiB holds about 14,500** of the deployment page's typical
    tenants now (34.6 KB estimated each, 29.5 KB retained). Give it a 2.5 GiB heap (`-Xmx2560m`, about 18,200) to hold
    them all; `cosec.snapshot.bound` and the startup line say what it holds.
  - Where the control plane runs beside the pipeline, the two snapshot sources share the quarter, half each.
  - `SnapshotSettings` (in `snapshots { }`) loses `maxApps`, `maxTenants`, `maxAbsentApps` and `maxAbsentTenants`:
    delete the lines.
  - A small pod gets fewer documents and turns ready as before; a document heavier than the whole share is still served.

## From 8.0.0-beta.4 {#from-8-0-0-beta-4}

- **Twelve properties are computed and have no key** (`SNP-23`, `CTL-72`; the rule is that what can be computed is not
  configured). Affected: a configuration that sets one. Startup fails with the keys CoSec does not read, and says of each
  that it is no longer a property and what replaced it. Remove the key; where you had moved the value, move what it
  follows ([Computed, not configured](./configuration.md#computed-not-configured)). `snapshot.reload-concurrency` is the one
  that was in no release.

  | Removed | Now |
  |---|---|
  | `cosec.authorization.snapshot.lag-warn`, `lag-escalate` | 6 and 30 passes of `revalidate-interval`: set that to move them |
  | `cosec.authorization.snapshot.cold-load-timeout` | 40 % of `cosec.pipeline.timeout`: set that to move it |
  | `cosec.authorization.snapshot.compile-threads` | one per 2 cores, at least 1 and at most 4 |
  | `cosec.authorization.snapshot.reload-concurrency` | 16, a constant |
  | `cosec.authorization.shadow.report-interval` | a sixth of the status's life (10 s), and no oftener than a fifth of `cosec.control.shadow.min-duration` or a second |
  | `cosec.authentication.revocation.cache.initial-capacity`, `expire-after-access` | none: the cache sizes itself, and `local-max-age` expires every entry |
  | `cosec.control.revisions.orphan-age`, `reclaim-interval` | one reclaim pass, and 1 hour |
  | `cosec.control.shadow.check-interval` | a fifteenth of `cosec.control.shadow.min-duration`, between 250 ms and 1 minute |
  | `cosec.control.http.max-body-size` | the 8 MB document cap and 2 MB: 10 MB |

  The starter logs once at startup, in a line that begins `CoSec derived, not configured:`, what it derived and from
  what. A system test that shortened `report-interval` and `check-interval` now shortens `cosec.control.shadow.min-duration`
  alone, and both follow.
- **New: `GET /documents?kind=tenant|app` lists which tenant and app documents exist** (`CTL-68`), a page at a time, on
  the operators' routes, the service port and the facade (`ControlService.documents`). Who may list, and what a page says,
  is in [Listing documents](../policies/testing.md#listing-documents). Nothing to change.
- **The control API's listings page with opaque cursors, and a page may be short or empty before the end** (`CTL-55`,
  `CTL-68`, review round 3). Affected: clients of `GET /changes` and `GET /documents`.
  - `next` and `after` are tokens: pass `next` back as `after` unchanged, and read nothing out of it. A malformed token,
    another listing's, or one for a position the listing does not have is a `400`, never a listing from the start.
  - `GET /changes?state=` is decided before the store is read, and reads at most four pages' worth of changes per request:
    a page with a `next` may hold fewer entries than `limit`, or none. Ask again with `after` until `next` is absent
    ([Listing cursors](../policies/testing.md#listing-cursors)).
- **A change's answer carries how its author was identified** (`CTL-70`). Every reader gets `origin.asserted` and
  `origin.via.method`; a reader who holds `cosec:approve` on the change's document, or `cosec:read` on the platform's,
  also gets `via.credential`, `via.request` and `peer`. A change no request made has no `origin`. A client that does not
  know the member ignores it ([What a change answers](../policies/testing.md#what-a-change-answers)).
- **A change listener receives a change's events in the order they were written, through a queue of its own bounded by a
  share of the heap** (`CTL-66`). Affected: change listeners of your own, and consumers of the change stream.
  - Each listener has one queue and one consumer, so it no longer receives one change's events out of order.
  - A `REQUIRED` listener with no room, a failure or no answer within `cosec.control.audit.required-timeout` fails a gated
    step (`503`), as before. For any other step the step is stored, and the event that finds no room, or whose call fails,
    is lost: counted in `cosec.control.listener.lost` and logged at `ERROR` naming the change.
  - At shutdown the queues are delivered within `cosec.observability.listener.drain-timeout`
    ([Order and queues](./observability.md#change-event-order)).
- **CoSec's own token verifier answers a token it has verified from memory until the token expires** (`IDN-32`). On by
  default; the revocation and reserved-attribute checks still run on every request. `cosec.authentication.token-cache.enabled`
  and `max-entries` (`16384`) are new; an application's own `TokenVerifier` gets no cache
  ([The verified-token cache](../integration/authentication.md#the-verified-token-cache)). Nothing to change.
- **A Redis that hangs is answered as one that is down, for the revocation check** (`PIP-7`). Affected: a deployment that
  set `cosec.pipeline.authentication-timeout`. Each read of CoSec's revocation reader now has its own budget, the new
  `cosec.authentication.revocation.read-timeout` (`300ms`); past it the token is accepted (or refused, with
  `fail-closed`) and a refresh fails, where a hang used to make every authenticated request `503`. Startup fails unless
  twice the read timeout is less than the authentication timeout, naming both. An application's own `RevocationReader`
  bounds its own reads.
- **Data-plane instances read the staged list and compile a staged candidate on sight** (`CTL-47`, `CTL-12`, `OBS-8`).
  An instance samples only the requests that read a document the list names, reports `CANDIDATE_ERROR` for a candidate
  it cannot compile within a report interval of seeing it, whatever its traffic, and makes no decision event for the
  shadow listener when nothing was sampled. A list that cannot be read counts as no list, and every candidate is read by
  its key as before. Nothing to change; the Redis ACL line of `CTL-46` above is what an instance needs.
- **The control API's service port is the application's own server on another port** (`CTL-71`). It takes the
  application's TLS and client authentication, bind address, HTTP/2, limits and timeouts, and serves the control API's
  service profile only, no route of the application. If mutual TLS on the application port is what makes the statement of
  what can reach the host true, it holds on the service port too.
- **An app change that defines a system role id another app already defines carries a warning** (`ROL-12`): a
  `REFERENCES` finding naming the role id and the other apps, never a refusal.
- **The login beans back off for the application's own, and `min-samples: 0` is said at startup** (UB-15). An application
  that declares its own `CompositeAuthentication` (or the token composite) starts with it, where it failed with two
  primaries. With `cosec.control.shadow.min-samples=0`, startup logs a `WARN` that a window then closes on time alone.
- **New: the control plane has an image, `cosec-control`, and two named deployments** ([The control-plane
  host](./deployment.md#the-control-plane-host), [Choosing a Deployment](./topologies.md#the-control-plane)). Nothing to
  change for an application that embeds the control plane: it is the second deployment, unchanged. A deployment that
  wants the control plane as a process of its own runs the image beside the gateway, under the statement
  `cosec.pipeline.identity.only-trusted-callers-can-reach=true` (the image does not start without it) and the three
  network policies that make it true (`k8s/control/`).
- **An IAM service in `gateway` mode that now needs the control plane runs in `enforce` with
  `cosec.pipeline.caller=forwarded-identity` instead.** Affected: a service that was in `cosec.pipeline.mode=gateway`
  and adds `cosec-control`. `gateway` mode wires no documents, no Redis and no control plane, so the control plane cannot
  be added to it.
  - Change `cosec.pipeline.mode` to `enforce` and add `cosec.pipeline.caller=forwarded-identity`: the service still
    believes the identity the gateway forwards, and still states what can reach it. The statement is the same
    (`only-gateway-can-reach`, or `only-trusted-callers-can-reach` once a service port is on), and so is the refusal of a
    hidden-method filter.
  - It now needs `cosec-cocache`, `cosec-control` and a Redis user that writes the documents
    ([Who may write which keys](./storage.md#who-may-write-which-keys)).
  - Its own routes stay authorized at the gateway or not at all.
    Set `cosec.control.http.enabled=false` when its code reaches the control plane in process only
    ([Configuration](./configuration.md#control-plane)).
- **CoSec's own routes no longer answer on the management port** (`CTL-69`). Affected: a WebFlux host (or a gateway host with
  the control plane on) with a `management.server.port` of its own, whose control API (`/cosec/control/v1/...`) or login
  routes (`cosec.authentication.login.refresh.path`, `.logout.path`) a client reached there. Spring Boot's management server
  inherits the application's `RouterFunction`s, so they answered on the management port behind the pipeline; on a host that
  believes the identity it is stated (`cosec.pipeline.caller=forwarded-identity`, the control-plane host image) that was the
  whole control API for whoever could reach the port. They answer on the application's port (and the control API on its
  service port) only, and are a `404` elsewhere. Call them on the application's port. An application's own routes are
  unchanged.
- **New: the control plane's facade, `ControlService`, for a service that embeds the control plane** (`CTL-65`).
  - Calls are decided as the `ControlCaller` they state, and recorded as that caller's.
  - It reaches only the narrow set: tenant and app document reads, and a tenant's change flow.
  - Requests and answers are the control API's bodies and JSON.
  - `IdentityHeader` writes the `CoSec-Identity` header.
  - Nothing to change for an application that does not use them
    ([Changing policies from your code](../integration/checks.md#changing-policies-from-your-code)).
- **A shadow window passes only when every live instance acknowledges it** (design D2, `CTL-10`). Affected: shadow
  windows during and after the upgrade, and clients that read a change's `shadow`.
  - Each data-plane instance now lists, in the status it writes every report interval, the windows whose candidate it
    has compiled. A window with enough samples still waits until every instance with a status in the last minute lists
    it; at `cosec.control.shadow.max-duration` it closes insufficient as before.
  - **During a rolling upgrade**, instances of the previous preview send no acknowledgements, so a window opened then
    closes no earlier than the upgrade's end (their statuses expire within two minutes of their stopping), or at
    `max-duration` as insufficient. Upgrade the data plane before you stage changes you need closed soon.
  - `GET /changes/{id}` adds `shadow.acknowledged` and `shadow.waitingFor` while the window is open
    ([What a change answers](../policies/testing.md#what-a-change-answers)); `contract/control/change.json` pins them.
- **Skipping a shadow window and rebasing a change are recorded as change events** (`CTL-63`). Affected: change
  listeners, and consumers of the change log or the audit stream.
  - `ChangeEvent.Kind` gains `SHADOW_SKIPPED` and `REBASED`. A skip used to be recorded only in the provenance of the
    revision it published, so a skip whose change was abandoned left no record; now `:skip-shadow`, and a break-glass
    change's exempt window, write `SHADOW_SKIPPED` with the reason in the note, and `:rebase` writes `REBASED`.
  - A listener that handles every kind adds the two; one with an `else` branch, as the kind's documentation asks,
    needs nothing.
- **The control plane records how each step's operator was identified, and a service port can be added** (`CTL-57`,
  `CTL-58`, `CTL-59`). Affected: code compiled against `ChangeEvent`, `ChangeRequest` or `HttpFacts` (experimental), whose
  constructors gained a trailing parameter (`origin`, `origin`, `peerAddress`, each with a default): recompile. Change
  events written as JSON, and the stored change, gain an `origin` member (`asserted`, `via`, `peer`), which a reader
  that does not know it ignores. New and unset by default: `cosec.control.http.enabled` and `cosec.control.http.service-port`
  ([Configuration](./configuration.md#control-plane)). Nothing to change for a deployment that sets neither. Every
  control-plane instance runs the maintenance passes, kept apart by their leases, and no property turns them off.
- **The control API answers from a response model of its own; a refused scope says why under `reason`; propagation
  counts the instances that hold the document** (review round 2, `R2C-11`, `R2C-12`, `CTL-61`, `CTL-62`). Affected:
  clients of the control API.
  - A change answer (`GET /changes/{id}` and every route that answers a change) no longer holds `checkedWith`,
    `checkedApps` or its window's `shadow.reporters`: they were the store's bookkeeping, not part of the API. Every
    other member is as before ([What a change answers](../policies/testing.md#what-a-change-answers)).
  - `simulate` and `explain`: a `Rejected` decision carries `APP_MISMATCH` or `SPACE_NOT_MEMBER` under `reason`, no
    longer under `cause`. `cause` is an `Indeterminate` decision's only, with the values it had.
  - `GET /documents/{key}/revisions/{n}/propagation` adds `holding` (the recent instances that hold the document) and
    `asOf` (when the statuses were read; an answer may be up to 5 seconds old). `installed` and `recent` mean what
    they meant. No instance is behind when `installed` equals `holding`: most instances never hold a given tenant, so
    `installed` never reached `recent` for it. `holding: 0` means no instance holds the document, and the first
    request loads it at the published revision. A status read that fails is now `503`, not `500`.
  - `contract/control/` pins the members of these answers, the closed sets they write and the audit kind of each
    route; a release that changes one says so.
- **A host that takes its caller from the `CoSec-Identity` header is asked for the statement of what can reach it, however
  it got that gate** (`STA-6`, `STA-7`, `STA-9`). Affected: an application that declared its own `IdentityGate` bean
  beside `cosec.pipeline.mode=enforce`, which started without one. It now fails startup until
  `cosec.pipeline.identity.only-gateway-can-reach=true` (or `only-trusted-callers-can-reach=true`) says that nothing but the
  gateway, and the services you name, can reach it, and until its hidden-method filter is off. A host in `gateway` mode is
  unchanged.
- **New: `cosec.pipeline.caller=forwarded-identity`.** A host in `enforce` mode, with the snapshot and the control plane
  wired, can take its caller from the identity header instead of a credential: the shape of a control-plane host, or of
  an IAM service that embeds the control plane. Nothing to change for a host that does not set it
  ([Configuration](./configuration.md#pipeline)).
- **Every stored record carries its format, and a reader ignores what it does not know of a known format**
  (review round 2, `R2C-10`, `R2C-28`, `CTL-53`, `IDN-31`). Affected: every deployment, once.
  - **Stop the control plane, upgrade it, start it.** The previews before this one refuse the `format` member the new
    records carry, so instances of both cannot run side by side. From this release on, readers are tolerant, and a
    later release upgrades in place.
  - **Roll the login hosts together.** A login host that still runs the older preview cannot read a session a newer
    one wrote: a user whose refresh lands there signs in again.
  - **Data-plane instances** that still run the older preview skip a staged candidate the new control plane wrote, and
    report nothing for its window until they are upgraded. The window waits; it does not pass.
  - **Nothing to convert:** a record written before formats reads as format 1. The window reporters and the instance
    index are now objects (`{"format":1,"reporters":[...]}`), and their earlier forms still read.
  - A value of a stored record that does not decode is now always a format error. Before, some were an
    `IllegalArgumentException`, which the control API answered `400`.
- **The control API answers `503` whenever the control store cannot answer** (review round 2, `R2C-6`, `CTL-52`).
  Affected: clients and alerts of the control API, and a `ControlStore` of your own.
  - A Redis outage or timeout during a publish, a document read or a revision read used to answer `500`
    `internal-error`. It now answers `503` `unavailable`, which a client may retry, and is logged at `WARN`, not
    `ERROR`. A stored value that does not decode stays a `500`.
  - `ControlStore` names `me.ahoo.cosec.control.store.ControlStoreException` for "the store could not answer". A
    `ControlStore` of your own throws it for an outage; anything else it throws is a `500`.
- **An app change lists the tenants it reaches as one entry per policy; an escalation's reason is an enum** (review
  round 2, `R2C-14`, `R2C-5`, `CTL-48`). Affected: whoever reads a change's `escalations` (the control API's
  `GET /changes/{id}`, a `ChangeListener`, the audit JSON on Kafka), and code that builds or reads an `Escalation`.
  - `TENANT_BINDING`, one entry per tenant binding, is gone. An app change that widens a permission policy carries one
    `TENANT_BINDINGS` entry per policy: `tenants binding [<policy>] in app <app>`, with the statements, how many tenant
    documents bind it (an upper bound, from the back-reference index) and the first 20 of them. A consumer that counted
    `TENANT_BINDING` entries to tell how many tenants a change reaches reads the count in `detail` instead. At 50,000
    tenants the change record went from about 50 MB to a few KB, each audit event likewise, and no tenant document is
    read to check the change.
  - `Escalation.reason` is `Escalation.Reason`, an enum with the names the JSON always carried; `Escalation.TENANT_BINDING`
    and `Escalation.DETECTION_TIMED_OUT` are `Escalation.Reason.TENANT_BINDINGS` and `Escalation.Reason.DETECTION_TIMED_OUT`.
    A stored change with a name this version does not know reads it as `UNKNOWN`, which is never informational.
  - `ChangeEvent`, `Escalation`, `RevisionRef` and `MovedHead` are `@ExperimentalCoSecApi`: a `ChangeListener` of yours
    opts in, and they may change in a minor release until a release has used them.
- **The public shapes of the core modules are fixed before RC** (review round 2, `R2A-1` to `R2A-7`). Affected: code that
  compiles against `cosec-model`, `cosec-spi`, `cosec-api` or the engine. No document, property, stored value or wire
  format changes.
  - The decision values are plain final classes with `equals`, `hashCode` and `toString`, not data classes: `Decision.Permit`,
    `Deny`, `Indeterminate`, `Obligation.RateLimit`, `ViewVersion` (in `cosec-engine`) and every `Cause` but `NotLoaded`
    have no `copy` and no `componentN`, so a new field never breaks a caller. Read the properties (`decision.by`,
    `cause.error`) instead of destructuring a decision.
  - `me.ahoo.cosec.spi.ratelimit.RateLimit` and `RateLimiter.window` are gone. `RateLimitSpec` has `limitPerWindow` and
    `windowMillis`, and `limit.resultOf(allowed, estimate, nowMillis)` (import `me.ahoo.cosec.spi.ratelimit.resultOf`)
    builds the `RateLimitResult`: in a `RateLimiter` of yours, use `limit` where you used `RateLimiter.window(limit)`.
  - `AuthnResult.Authenticated(principal, method)` takes an `Identity.Method`, not a string: `Identity.Method.API_KEY`, or
    `Identity.Method("ldap")` for a credential of your own, any non-empty name. What the audit record and `CoSec-Identity`
    say for CoSec's own methods does not change (`jwt`, `api-key`).
  - `AuditEvent.kind` is an `AuditEventKind` enum with the five values it always had; the JSON a sink or a Kafka consumer
    reads is the same bytes. Read `authorization.reasonCode` for why a request ended as it did (`decision` is its coarser
    grouping, mapped in the KDoc of `AuditDecision`); `binding` is a label for people, `authorization.source.via` and
    `document` are the typed facts.
  - `SnapshotDocumentStore` is `@ExperimentalCoSecApi`: its shape follows its one backend, `cosec-cocache`, and a batched or
    store-wide conditional read is planned. An implementation of yours opts in and may have to change in a minor release.
  - `Resource.attributes` defaults to an empty map: nothing sets one today (`DOC-10`).
- **The control plane keeps a list of the staged candidates** (`CTL-46`), `<s>:candidate-staged:all`, which instances
  will read instead of a candidate key per document they serve. Affected:
  - a Redis ACL for a data-plane instance: add `%R~cosec:candidate-staged:*` to its keys
    ([Who may write which keys](./storage.md#who-may-write-which-keys));
  - a downgrade of the control plane to a preview before this one: delete `<s>:candidate-staged:all` first, or instances
    trust a list nobody keeps. Such a list can only cost shadow samples, never pass a window.
- **The back-references and the document list are sets: run `cosec-migrate repair-references` once** (`CTL-42`,
  `CTL-43`, `CTL-44`, `MIG-4`). Affected: every store a control plane of 8.0.0-beta.4 or earlier wrote. Data-plane
  instances are not: they read none of these keys.
  - A policy's back-references (`<s>:control:refs:<doc>:<policyId>`) were one value, rewritten whole by every tenant
    that added or dropped itself; they are now a set with a member per referrer and revision, and the document list
    moved from `<s>:control:list:documents` to the set `<s>:control:documents:all`. A tenant's publish now writes its
    own members only, whatever the number of tenants ([Storage](./storage.md#redis-keys)).
  - Stop every control plane of an earlier version, then run
    `cosec-migrate repair-references --to-redis <uri> [--cluster] [--key-prefix <p>]` once; the control plane of this
    version may run meanwhile. It converts each back-reference value a head touches, never replaces a set, and removes
    the old document list last, printing `The store is in the format of 8.0.0-beta.5.` A second run changes nothing
    ([Repair the back-reference index](./upgrading.md#repair-the-back-reference-index), which gives what it costs: at
    50,000 tenants about 1.3 million commands, some 22 minutes at a round trip of 1 ms).
  - Until it has run, the control plane starts, logs one `ERROR` naming the command, and its `cosecControlStoreFormat`
    health indicator is `DOWN` ([Observability](./observability.md#snapshot-health)). A change that touches a back-reference of
    the old format is refused (`503`, naming the command), never let through.
  - A value no head touches stays in the old format: it blocks nothing, and a later change that touches it fails
    closed, naming the command, until it runs again.
  - The two sets are never kept locally: `local-max-age` and `degraded-local-max-age` of `ReferenceIndexCache` and
    `ControlDocumentSetCache` are locked to 1 ns, and a `cocache.caches.*` entry that sets one fails startup
    ([Configuration](./configuration.md#cocache-properties-that-affect-cosec)).
- **The open changes are sets, and `GET /changes` lists them: stop the control plane and run `cosec-migrate
  repair-references`** (review round 2, `R2C-2`, `R2C-4`, `CTL-54`, `CTL-55`). Affected: every store a control plane
  of 8.0.0-beta.4 or earlier wrote, and a `ControlStore` of your own.
  - The open changes were one value, `<s>:control:list:open-changes`, rewritten by every change; they are now sets of
    record, one per document and one per state, plus a marker, `<s>:control:changes:kept`
    ([Storage](./storage.md#redis-keys)). Two changes no longer contend on one value, and a maintenance pass reads the
    changes of one document, or of one state, instead of every open change.
  - Stop every control plane, upgrade it, and run `cosec-migrate repair-references` (the same run as for the
    back-references, above): it lists each still-open change of the old list in the sets, then marks them kept and
    removes the old list, only once every one of them was read. It prints `Listed N open change(s) in the open-change
    sets.` and a `FAILED` line for each change whose record does not read, and fails while one is left: remove or fix
    that record and run it again. A store a control plane of this version created has no old list and is kept from the
    start.
  - Until the sets are kept, and whenever the old list is there again (an older control plane started on the store),
    the control plane reclaims no revision and logs a `WARN` naming the command, and `GET /changes` is `503`: the sets
    are not trusted to hold every open change, and a revision an open change rests on must not be reclaimed. A change
    that is not shadowing is now expired by the reclaim pass (`cosec.control.revisions.reclaim-interval`, hourly) or
    when it is read, no longer every `cosec.control.shadow.check-interval`.
  - `GET /changes?document=&state=&after=&limit=` lists the open changes the caller may read, as summaries
    ([Finding open changes](../policies/testing.md#finding-open-changes)).
  - `ControlStore.openChanges()` is gone. `ControlStore` has `openChangesKept()`, `keepOpenChanges()`,
    `openChanges(document)` and `changes(state, after, limit, documents)`, which answer `ListedChange` (`Readable` or
    `Unreadable`) and `ChangePage`, all `@ExperimentalCoSecApi`.
  - `ControlChangeSetCache` is a set of record: its `local-max-age` and `degraded-local-max-age` are locked to 1 ns, as
    the other sets' ([Configuration](./configuration.md#cocache-properties-that-affect-cosec)).
- **A shadow window whose reporter vanished waits, then closes insufficient; the shadow pass skips what it cannot read**
  (review round 2, `R2C-3`, `R2C-17`, `CTL-56`). Affected: changes in shadow mode, whoever reads a closed window's
  summary (`GET /changes/{id}`, a `ChangeListener`, the audit JSON on Kafka), and alerts on the control plane's log.
  - An instance that reported on a window and has no report now used to make every look at the window fail (`503` to
    `POST /changes/{id}:shadow`'s caller, a `WARN` from each shadow pass), and the window never closed. The window now
    waits for it and logs `Shadow window <w> of change <c> is waiting for [<instances>]`; at
    `cosec.control.shadow.max-duration` it closes *insufficient*, as a window with too few samples does, and its
    summary names the instances in `vanished`. It never passes on the others' reports: publishing needs the summary
    acknowledged ([Testing changes](../policies/testing.md#trying-changes-in-shadow-mode)).
  - `ShadowSummary` has `vanished`, empty unless instances vanished; the stored change and the audit event's `shadow`
    carry it as an array only when it is not empty.
  - One shadowing change the pass cannot read no longer stops it from looking at the others; a `WARN` names it at most
    once an hour. The pass looks first at the windows it looked at longest ago, and a pass its interval cuts short logs
    `looked at <k> of <n> windows`.
  - `ControlMaintenance` takes a `Clock` (last parameter, default the system clock).
  - `ShadowReportReader` has `readings(window)`, answering `ShadowReadings`: the reports that read and the instances
    whose listed report cannot be read, which the window treats as vanished. `CoCacheShadowReports` overrides it. A
    reader of your own may too ([Extending](../architecture/extending.md#control-store)); without it, `readings` reads
    `reports` and throws where it throws, as before.
- **Gateway mode states the network boundary it trusts, and the gateway forwards the method it decided** (`STA-7`,
  `STA-6`, `PIP-31`). Affected: every service in `cosec.pipeline.mode=gateway`, and every gateway.
  - A service in `gateway` mode believes any `CoSec-Identity` header, which carries no signature, so the network is its
    boundary. It now **fails to start** unless you write `cosec.pipeline.identity.only-gateway-can-reach: true`
    (`COSEC_PIPELINE_IDENTITY_ONLYGATEWAYCANREACH=true`), your statement that nothing but the gateway can reach it.
    Absent, empty, `false`, `1` and `on` fail alike, and the message says what to write. Make it true first (a
    `NetworkPolicy` that admits the gateway's pods only, mutual TLS, a private network;
    [`k8s/examples/gateway-mode-service-networkpolicy.yaml`](https://github.com/Ahoo-Wang/CoSec/blob/main/k8s/examples/gateway-mode-service-networkpolicy.yaml)).
    Once it is set, the `WARN` through beta.4 is one `INFO` line. A Spring Boot test that starts such a service states
    it too: the test is reached by its own client alone, so the statement is true there. From beta.6 the kit has the
    line: `@SpringBootTest(properties = [IdentityHeader.ONLY_GATEWAY_CAN_REACH])`, for tests only
    ([Testing your application](../policies/testing.md#testing-your-application)).
  - A service in `gateway` mode **fails to start** with a hidden-method filter:
    `spring.mvc.hiddenmethod.filter.enabled=true`, `spring.webflux.hiddenmethod.filter.enabled=true`, or a
    `HiddenHttpMethodFilter` bean of its own. The gateway decided the method of the request line, and a `_method` form
    field would run another. Remove it. A host that decides itself (`enforce`) is unaffected.
  - The gateway removes `X-HTTP-Method-Override`, `X-HTTP-Method` and `X-Method-Override`, in any letter case, from
    every request it forwards, on every route. Not configurable. A backend that honoured one of them for a request
    through the gateway no longer does: send the method in the request line.
- **A path holding an encoded `;` or control character is a `400`** (R1A-3, `PIP-30`). Affected: clients that send
  `%3B` or `%00` to `%1F` or `%7F`, in either case, anywhere in a path. Through beta.4 they were decided as text of a
  segment, which Spring reads alike, while a backend behind a gateway that decodes before it strips path parameters
  serves `/admin%3Bx/users` as `/admin/users`, past a `DENY` on `/admin/**`. They are now refused before
  authentication, mapped route or not, with the `400` `invalid-request` of any path the pipeline will not interpret,
  and the gateway refuses them before anything is forwarded. So is a raw control character, which the servers CoSec
  runs on already refuse themselves. A value that holds such a character goes in the query or the body, or has its `%`
  encoded again (`%253B`), which the pipeline decodes once, to the text `%3B`. `%25`, a trailing `.` and a trailing
  space are unchanged. To check: search access logs, or the audit records' `request.path`, for `%3B`, `%00` to `%1F`
  and `%7F` in request paths ([Troubleshooting](./troubleshooting.md#invalid-request)). There is no switch to accept
  them.
- **The diff of two revisions names the sections that differ** (`GET /documents/{key}/diff`, UC-14). Affected:
  scripts and tools that read its `sections`. Through beta.4 `sections` was a boolean, `true` when anything besides
  the policies, bindings and roles differed. It is now the list of those sections' names, of `catalog`, `boundary`,
  `limits`, `attributes` and `rateLimits` in that order, and `[]` when none differs. Read `sections.length > 0` (or
  its equivalent) where you read `sections`, or look for the section you care about
  ([Testing policies](../policies/testing.md)).
- **A refused forwarded identity is counted and says why, and the gateway no longer writes one a service would refuse**
  (#1690). Affected: services in `cosec.pipeline.mode=gateway`, their dashboards and log alerts, and code that calls
  `IdentityHeader.of` in tests.
  - Such a service logs the reason of each refused `CoSec-Identity` header at `WARN` on the logger
    `me.ahoo.cosec.runtime.identity.IdentityGate`, at most once a minute for each problem type (every one at `DEBUG`).
    Through beta.4 it logged them only at `DEBUG`. The response is unchanged: it never says why.
  - With `cosec-micrometer` and a `MeterRegistry` it counts every refusal in `cosec.identity.refused{problem}`
    (`invalid-request`, `token-invalid`, `token-expired`). Alert on any non-zero rate
    ([Observability](./observability.md#metrics)).
  - The gateway refuses to write an identity a service would refuse to read (an empty id, an unpaired UTF-16 surrogate,
    a time past year 9999, an empty claim name): the request fails with `500` and no header is forwarded, where through
    beta.4 the service behind answered `400`. CoSec's own gateway builds no such identity; a custom `Authenticator`
    could. `IdentityHeader.of` throws `IllegalArgumentException` for such an identity, as it did for one too large.
- **A gateway forwards the caller's credentials only when it accepted them** (R1A-7, `IDN-8`). Affected: gateways with
  `cosec.gateway.identity.forward-authorization=true`. Through beta.4 a request whose token was expired, revoked or
  invalid, which goes on as anonymous, still took its `Authorization` header, API key, query token and cookie to a
  route that receives the identity when a public route let it through. They now stay at the gateway: the backend sees
  an anonymous request. A backend that answered such a request by verifying the token itself now gets none.
- **The IP blacklist compares an address in its canonical form** (R1A-4). Affected: implementations of
  `BlacklistSource`, and callers of `InMemoryBlacklistSource.block`. `contains(BlacklistKind.IP, value)` is asked with
  `2001:db8::1` for any spelling of that address and `192.0.2.1` for `::ffff:192.0.2.1`: keep blocked addresses in
  that form ([Extending](../architecture/extending.md)). `InMemoryBlacklistSource` keeps them so and refuses a value
  that is no address with `IllegalArgumentException`.
- **The security context's readers are named for what they do** (UA-7). Affected: code that reads the
  `SecurityContext` from a Reactor context, an exchange, a servlet request or `SecurityContextHolder`. Every reader now
  comes as `requireSecurityContext()`, which throws an `IllegalStateException` saying what to do where CoSec did not
  handle the request, and `securityContextOrNull()`, which answers `null`. All are `@JvmStatic`. The old names are gone,
  with no aliases:

  | Through beta.4 | From beta.5 |
  |---|---|
  | `ContextView.getSecurityContext()` (threw `IllegalStateException("SecurityContext is null.")`) | `ContextView.requireSecurityContext()` |
  | `ContextView.getSecurityContextOrEmpty()` (answered `null`) | `ContextView.securityContextOrNull()` |
  | `ServerWebExchange.getSecurityContext()` (answered `null`) | `ServerWebExchange.securityContextOrNull()`, or `requireSecurityContext()` |
  | `HttpServletRequest.getSecurityContext()` (answered `null`) | `HttpServletRequest.securityContextOrNull()`, or `requireSecurityContext()` |
  | `SecurityContextHolder.context` (Java: `getContext()`) | `SecurityContextHolder.securityContextOrNull()` |
  | `SecurityContextHolder.requiredContext` (Java: `getRequiredContext()`), which threw `IllegalArgumentException` | `SecurityContextHolder.requireSecurityContext()`, which throws `IllegalStateException`: code that caught the other type no longer catches it |

  A handler's `SecurityContext` parameter is unchanged, and its message is now the request reader's
  ([Web](../integration/web.md)).

  A context bound that CoSec's pipeline did not make (one built by hand, or a mock) is still ignored (`PIP-27`), but
  no longer silently: `requireSecurityContext()` says so ("A SecurityContext is bound … that CoSec's pipeline did not
  make, and it is ignored"), and `securityContextOrNull()` logs that sentence at `WARN`, at most once a minute for each
  reader. A test that binds such a context sees a missing or anonymous caller, or a permit where its code lets an absent
  context through. Grep the tests for `SecurityContext.KEY`, `mockk<SecurityContext>` and your own implementations of
  `SecurityContext`, and bind a context the pipeline made instead ([Testing your application](../policies/testing.md#testing-your-application)).
- **A check that throws reports its own cause** (UA-16). Affected: code that reads the cause of a check's
  `Indeterminate` decision (`AuthorizationChecker.decide`). It is `Cause.CheckFailed(error)`, where through beta.4 it
  was `Cause.SourceUnavailable(PLATFORM, error)` whatever failed. A document the check could not read is still
  `SourceUnavailable`. A check reaches no decision listener, so no event, audit, metric or span changes
  ([Checks in code](../integration/checks.md)).
- **A pipeline that answers API keys needs a membership provider when it is built** (UA-12). Affected: applications
  that build a pipeline with `ApiKeyAuthenticator`, alone or in a `CompositeAuthenticator`, and no `memberships(...)`.
  `build()` fails with an `IllegalStateException` that says to add one. Through beta.4 it built, then answered `503` to
  every key. The starter already refused such a host at startup (`IDN-6`).
- **The test kit's entries are static for Java** (UA-13). Affected: code compiled against beta.4 that calls
  `PermittedRequest` or `IdentityHeader`. Their functions are now `@JvmStatic`, so the methods on `INSTANCE` are gone:
  recompile. Kotlin sources stay the same; Java drops `INSTANCE` (`PermittedRequest.withToken(...)`). `@JvmOverloads`
  and `@JvmStatic` are added to `PipelineFixture`'s companion (`load`, `of`, `unreadable`), `PipelineFixture.request`,
  the constructors of `PipelineFixture.Principal` and `TokenSubject`, and `TokenSubject.of`, which only adds.
- **The skill `cosec-custom-matcher` is `cosec-extending`** (UE-21). 8.0 has no matchers, and the skill is about
  extending CoSec with beans (`AttributeProvider`, `MembershipProvider`, `DecisionListener`, `RateLimiter`,
  `Authenticator`). The directory is `skills/cosec-extending`, its `name` is `cosec-extending`, and an agent invokes it as
  `$cosec-extending`. Affected: an agent configuration, a prompt or a plugin list that names `cosec-custom-matcher`;
  nothing in a running deployment changes.
- **The test bindings are the test kit's** (the Wow drill). Affected: tests that import `CoSecTestBindings`. They move
  out of the production jars into `cosec-testkit`, one for each stack:

  | Through beta.4 | From beta.5 |
  |---|---|
  | `me.ahoo.cosec.webflux.testing.CoSecTestBindings` (`cosec-webflux`) | `me.ahoo.cosec.testkit.webflux.CoSecTestBindings` (`cosec-testkit`) |
  | `me.ahoo.cosec.webmvc.testing.CoSecTestBindings` (`cosec-webmvc`) | `me.ahoo.cosec.testkit.webmvc.CoSecTestBindings` (`cosec-testkit`) |

  Change the import and add `cosec-testkit` as a test dependency if the build lacks it. Its functions are the same.
  The WebFlux one adds a suspending `withSecurityContext(context) { ... }` for coroutine code under test, and
  `PipelineFixture.of` and `load` take an `authenticator` of the application's own
  ([Testing your application](../policies/testing.md#testing-your-application)). The kit brings neither adapter; the
  bindings need the one of your web stack on the test's classpath, which a test of such a handler has.
- **A document whose ids hold a control character, or whose DENY matches a path undecoded, no longer compiles**
  (R1B-3, R1A-1; `DOC-13`, `DOC-14`). Affected: documents that hold either.
  - **Control characters in ids.** No id a document writes may hold a control character or an unpaired surrogate:
    policy ids and statement names, binding ids and subjects, roles, catalog permission ids, declared attributes,
    attribute paths and path variable names ([Ids and special values](../policies/language.md#ids-and-special-values)).
    Through beta.4 a policy id could hold a line break, which forged a log line on every host with a route map.
  - **DENYs that match the path as received.** A DENY statement or a rate limit rule may no longer set
    `decodeAndParseSegments` to `false`: it missed every percent-encoded spelling of its path (`/%61dmin/users` for
    `/admin/users`). Remove the option; an ALLOW keeps it ([HTTP paths](../policies/language.md#http-paths)).
    `cosec-migrate convert` rejects a 7.x DENY written so (`DOES_NOT_COMPILE`, at its statement) and writes no document.
  - **What happens to a stored document.** A host fails closed on it, as on any document it cannot compile: every
    request that needs the document answers `503`, the host logs `Snapshot document [<doc>] revision <n> does not
    compile` at `ERROR` with each error's JSON path and the id escaped, and the `cosecSnapshot` health indicator counts
    it in `invalid` ([Troubleshooting](./troubleshooting.md)). The control plane refuses a change that would publish one:
    a `COMPILE` finding at the id's or the statement's path, and the change stays a draft.
  - **Before upgrading the hosts**, check every published document with beta.5's compiler, as in
    [Policies in CI](../policies/testing.md#policies-in-ci) (`PolicyDocumentCompiler.validate`), and publish a revision
    that fixes each one it refuses: ids renamed, the option removed. A document that holds neither is unaffected.
  - **Then upgrade one host first** (or the control plane's host), and read its `cosecSnapshot` health indicator's
    `invalid` count and the `ERROR` line it logs per document before you upgrade the rest of the fleet. A document
    nobody fixed fails closed on every upgraded host: each request that needs it answers `503` there.

- **The control plane holds every change to entry budgets, and caps a change's operations and body** (R1B-4,
  `CTL-40`, `CTL-41`). Affected: control planes with documents over a default budget or proposing very large changes,
  and WebFlux hosts that raised `spring.codec.max-in-memory-size` for the control API. Before these budgets, a change of
  100,000 entries exhausted a 512 MB heap in the control plane.
  - **Entry budgets.** The platform's `limits` take `maxPolicies` (10,000), `maxStatements` (20,000),
    `maxRoleBindings` (10,000) and `maxAnyoneBindings` (1,000) per document
    ([Entry budgets](../policies/language.md#entry-budgets)). A change that would raise a count over its budget is a
    `V2` finding (`422`) naming the budget, the count and the limit, and stays a draft. A document already over a budget
    keeps being served by every host, may change, and may shrink; only a change that adds to an over-budget count is
    refused, and a check warns of each count still over. To keep a large document growing, raise the budget in the
    platform document first. `cosec-migrate convert` warns of a converted document over a default budget and writes it.
  - **Operations.** A change a client proposes carries at most `maxChangeOperations` (20,000) operations, also in the
    platform's `limits`; a larger one is a `400`. Propose it as several changes. `apply` and rollback are not held to it.
  - **Body.** `cosec.control.http.max-body-size` defaults to `10MB` instead of `16MB`, and now bounds the control API
    on WebFlux too, in place of `spring.codec.max-in-memory-size` (256 KB by default, which kept larger changes out of
    a WebFlux control plane). A larger body is a `400` `invalid-request`.
- **An instance holds the tenants it serves** (`SNP-7`, `SNP-14`). Affected: every instance that decides, the gateway
  above all.
  - `cosec.authorization.snapshot.max-tenants` defaults to 16,384 instead of 1,024. The ids an instance knows have no
    document now have a bound per family: `max-absent-apps` (256, as before) and `max-absent-tenants` (65,536, where
    256 was shared before). Both are properties, and `SnapshotSettings` has them.
  - With every place used, the tenant entries take about 520 MB of heap at 30 KB a tenant document of 5 roles of
    20 permissions and 20 bindings. Check your heap against
    [Sizing a SaaS gateway](./deployment.md#sizing-a-saas-gateway), or set the two properties back. The production
    overlay of `k8s/` now gives a gateway a 2 GiB heap and a 3200Mi limit, where it gave 1 GiB and 2Gi: room for every
    snapshot and revocation default at its bound.
  - `cosec.authorization.snapshot.reload-concurrency` is new, and a revalidation pass reads 16 documents at a time, not 4.
    The reads per interval are unchanged; a pass ends four times sooner, in a burst four times denser.
  - An install that takes its family over the cap evicts at most 64 entries of that kind on its own thread, and
    housekeeping the rest, where it evicted back to the bound.
- **A configured `cosec.authorization.shadow.instance-id` is a prefix.** The id is `<instance-id>.<suffix>`, with a
  suffix new at every start, so a restarted process never replaces the shadow reports of the one before it, which could
  erase a `CANDIDATE_ERROR` and unblock a window. Affected: a Redis ACL that names an instance's keys. Change
  `~cosec:shadow:report:*:pod-a` and `~cosec:shadow:status:pod-a` to `~cosec:shadow:report:*:pod-a.*` and
  `~cosec:shadow:status:pod-a.*` ([Who may write which keys](./storage.md#who-may-write-which-keys)); an instance with
  the old lines is denied its report and status writes, which it logs at `WARN`.
- **A host loads the apps it names at startup** (`SNP-20`). Its `cosec.authorization.scope.app-id` and, on a gateway, every
  route's `cosec-app-id` load with `prewarm-apps`, and readiness waits for them as for `prewarm-apps`: an instance whose
  Redis cannot answer for one stays not ready, retrying. `prewarm-apps` keeps working as before.
  `cosec.authorization.snapshot.prewarm-host-apps=false` restores loading those apps on their first request.
- **A host that serves the refresh route states how a refreshed token gets its memberships** (`STA-8`). Affected: every
  host that sets `cosec.authentication.login.refresh.path`, declares no `RefreshTokenAuthentication` of its own and no
  `MembershipRefresh<RefreshTokenCredentials>` bean. Through beta.4 such a host refreshed silently with
  `MembershipRefresh.CLAIMS`: a role removed from a member kept working until their next sign-in, up to
  `cosec.jwt.session.lifetime` (30 days), as long as they refreshed within the refresh token's lifetime. It now **fails
  to start**, and the message says what the choice costs. Choose one:
  - to keep what the host did, write `cosec.jwt.session.memberships-on-refresh: from-token`
    (`COSEC_JWT_SESSION_MEMBERSHIPSONREFRESH=from-token`). Any other value fails like an absent one. Startup logs one
    `INFO` line with the session lifetime it implies;
  - or declare a `MembershipRefresh<RefreshTokenCredentials>` bean that reads the member's roles and attached policies again, so a removal
    applies within `cosec.jwt.token-validity.access` (10 minutes).

  Either way `TokenRevoker.revokeSubject` ends a member's tokens at once
  ([How long a removed role lives](../integration/authentication.md#how-long-a-removed-role-lives)). A host that serves
  no refresh route, or builds its own `RefreshTokenAuthentication`, is unaffected.
- **The published JSON schemas have a new identity, and their files moved to `schema/8.0/`** (R2A-5). Affected:
  editors, CI checks and tools that load a CoSec schema by URL or by path, or that pinned one by its `$id`.
  - Until beta.4 each file's `$id`, and the base its cross-file `$ref`s resolve against, was the file on `main` at
    `raw.githubusercontent.com/Ahoo-Wang/CoSec/refs/heads/main/schema/<file>`, so an 8.0 file resolved against whatever
    `main` held later. It is now `https://cosec.ahoo.me/schema/8.0/<file>`: `8.0` is the release line (not the `schema: 2`
    of a document), the site serves the files, and the repository keeps them at `schema/8.0/<file>`.
  - An editor setting that still names the raw `main` URL gets a `404` once this reaches `main`: completion and
    validation stop, with the editor's "could not load schema" warning. Point it at
    `https://cosec.ahoo.me/schema/8.0/cosec-snapshot-document.schema.json`, or at the tag of the version you run
    (`.../refs/tags/<tag>/schema/8.0/<file>` from the first tag after beta.4; beta.4's own files stay at `schema/<file>`).
    A setting that names the beta.4 tag keeps working and keeps the `$id` it had.
  - Until 8.0.0 the site's `/schema/8.0/` holds what `main` holds for the 8.0 line, so it changes between previews; a
    tag is the immutable copy ([Policy language](../policies/language.md#json-schema)). A CI job that validates against
    `schema/<file>` in a checkout reads `schema/8.0/<file>`. No document stops compiling.
- **The public shapes of the login, JWT, WebMvc, gateway and metrics modules are fixed before RC** (R2B-1, R2B-2, R2B-4
  to R2B-6, R2B-10, R2B-11, R2B-12, R2B-16, R2B-24). Affected: applications that build a `TokenSubject`, a
  `JwtTokenVerifier` or a `JwtTokenConverter`, read the WebMvc holder, catch `RefreshTokenMismatchException`, or
  supply an `Algorithm` bean.
  - **`TokenSubject`: `tenant` now comes before `app`.** `TokenSubject("alice", "acme")` still compiles and now means
    the tenant `acme`, where it meant the app `acme`. Search for every call that passes a second positional argument and
    write it by name (`TokenSubject(id = "alice", app = "acme")`); the Java overloads are gone with `@JvmOverloads`.
    `toString()` no longer prints the attributes, only their number.
  - **`JwtSigning` replaces java-jwt's `Algorithm` in the public constructors.** `JwtTokenVerifier(JwtSigning, skew)` and
    `JwtTokenConverter(idGenerator, JwtSigning, ...)`, with `JwtSigning.hmac256(secret)`, `hmac384` and `hmac512`. The
    constructors that took an `Algorithm` are internal. The starter's `Algorithm` bean is now a `JwtSigning` bean built
    from the same properties (`cosec.jwt.secret`, `cosec.jwt.algorithm`), so nothing an operator writes changes; an
    application that declared its own `Algorithm` bean to replace it declares a `JwtSigning` instead. A signing key that
    is an RSA or EC key pair has no public entry yet.
  - **`RefreshTokenMismatchException` is `me.ahoo.cosec.spi.token.RefreshTokenMismatchException`.** Change the import. The
    verifier side of `cosec-jwt` no longer imports anything of `cosec-login`; the converter still does (`TokenConverter`,
    `TokenSubject`).
  - **`me.ahoo.cosec.webmvc.SecurityContextHolder` is `CoSecSecurityContextHolder`**, so that it is not taken for Spring
    Security's. `requireSecurityContext()` and `securityContextOrNull()` keep their names.
  - **Starter-only classes are internal:** the constructors of `IdentityStripFilter`, `IdentityHeadersFilter` and
    `OrderedCorsWebFilter`, and the filter-order and attribute constants of the filters. The starter registers them.
  - **`MetricsListener.bind(...)` and its constants moved to `CoSecMeters`** (internal: the starter registers them).
    `MetricsListener` keeps its name, `cosec.authorization` and the four tag names. A meter's name and tags are
    unchanged.
  - **A refresh strips `Bearer ` from the refresh token too**, as it did from the access token. The Kafka audit
    listener logs failed sends once every 10 seconds with a count; a closed sender says it is closed.
- **The social sign-in states are `cosec-spi`'s `SocialStateStore`, on Redis in `cosec-cocache`; sign-in starts are
  bounded; an unknown principal is remembered briefly** (R2B-3, R2B-23, R2C-18).
  - **`SocialStateStore` and `InMemorySocialStateStore` moved to `me.ahoo.cosec.spi.social`.** If you implement
    `SocialStateStore` or build the in-memory one, change the import: the interface is unchanged (`put` and `take`,
    both suspending). `RedisSocialStateStore` is now `me.ahoo.cosec.cocache.RedisSocialStateStore`, over the
    non-blocking client and `GETDEL`, internal to the starter (its constructor takes a `ReactiveStringRedisTemplate`
    and no longer a parallelism). The keys are the same, `<s>:oauth:binding:<state>`, so states in flight survive an
    upgrade.
  - **Social login needs `cosec-cocache`** (or a `SocialStateStore` bean of your own): startup fails naming the artifact
    without either (`STA-1`). `cosec-social` no longer brings `spring-data-redis` to your compile classpath; JustAuth's own
    state cache still blocks and needs a `StringRedisTemplate`, which your application has when it uses Redis.
  - **`authorize` refuses what it cannot take at once.** Each `JustAuthProvider` has at most 64 sign-in starts in flight,
    on a pool of its own beside the login's; one more throws a `SocialAuthenticationException` where it used to queue
    without bound.
  - **`cosec.authorization.membership.unknown-ttl`** (default `5s`, at most `1m`, `0s` for the old behaviour): an
    instance remembers that your `MembershipLoader` answered `Unknown` for a principal, so a removed member's token no
    longer reaches the loader on every request. A principal you add is known when that time ends on the other
    instances, and at once where `invalidate` ran. A failed load is never remembered.
- **The control API answers less and refuses more** (R2C-32, R2C-33, R2C-34).
  - **`simulate` and `explain` name a statement only to a caller who may read the document that *defines* its policy.**
    A tenant administrator whose tenant binds a platform policy, or an app's permission policy, no longer sees that
    policy's statements, in the decision or in the trace: the decision says `"redacted": true` and the trace counts them.
    Before, the document of the binding decided it, which a tenant administrator reads. A caller who may read the
    defining document (a platform operator) sees what they saw.
  - **A section operation with an `ifMatch` is a `400`.** `putCatalog`, `setLimits`, `setAttributes` and `setBoundary`
    replace a section, which has no digest of its own; they accepted an `ifMatch` and ignored it. The change's `base`,
    the digest of the document it was proposed on, guards the document. Remove the member. Each operation also takes
    only its own members now: `removePolicy` with a `policy`, for one, is an unknown member.
  - **A change is checked, analysed and approved on the document the data plane reads**: the candidate as written and
    read back, not as the operations built it. No change of outcome is expected: the two are the same unless the format
    codec fails to round-trip a member.
- **A cold load waits for a full compile pool; `fail-closed.tenants` takes `*`; the local rate limiter bounds each
  document's rules** (R2D-3, R2D-4, R2D-5). No document, property default, stored value or wire format changes.
  - A cold load that the full compile queue refuses no longer answers `503` at once. It compiles again every few
    milliseconds until `cold-load-timeout` (2 s), then fails closed as before. On a new replica, a burst of tenants
    queues instead of being refused ([What a new replica costs](./deployment.md#what-a-new-replica-costs)).
  - `cosec.authorization.snapshot.fail-closed.tenants: "*"` fails every tenant closed past `fail-closed.max-lag`. The
    default stays empty.
  - `LocalRateLimiter` holds at most a quarter of its buckets for the rules of one document, and counts each bucket it
    refuses in `cosec.ratelimit.local.refused`. Its constructor takes `maximumBucketsPerOwner` after
    `maximumBucketsPerRule`: code that calls it with named arguments compiles unchanged; code compiled against beta.4
    that constructs one must be compiled again.

- **A `TokenVerifier` bean turns token authentication on without a `cosec.jwt.secret`** (review round 2, `R2D-7`).
  Affected: applications that declare their own `TokenVerifier` and have no CoSec signing key (`cosec.jwt.enabled=false`).
  - Before, tokens were on only where a signing key existed: such an application's authenticator refused every request
    that carried a token (`token-invalid`), whatever its verifier said, and the application had to set a secret it never
    used. Now tokens are on when a `TokenVerifier` bean or a signing key exists. A verifier alone issues nothing: the
    login and refresh routes and the token converter still come with the key.
  - **A verifier alone needs a revocation reader**: a `RevocationReader` bean, or `cosec-cocache` on the issuer's Redis.
    Without one, startup fails with a message naming it, instead of every token being refused. `RevocationReader.NONE`
    accepts revoked tokens until they expire.
  - If a `TokenVerifier` bean was declared for another purpose and its tokens were meant to stay refused, remove the bean:
    with no verifier and no key, a request with a token is still `token-invalid`.

## From 8.0.0-beta.3 {#from-8-0-0-beta-3}

- **The policy test kit refuses a run that could pass for the wrong reason** (`PolicyTestKit`, experimental; UC-7).
  `load` fails without a `platform.json` (write an empty document there to test without one) and when the directory
  holds a file or directory the kit would not read. A scenario names its `credential` (`tenant` and `id`, or
  `"anonymous": true`) and its request's `action` and `resource`: the example defaults (`acme`, `alice`,
  `order:read`, `order/1`) are gone. Among tenant documents, a scenario decided in a tenant without one fails, and among
  app documents one in an app without one, unless it says `"withoutDocument": ["tenant"]` or `["app"]`
  ([Testing decisions](../policies/testing.md#testing-decisions)). Affected: scenarios that relied on the defaults or
  on a misplaced document; `PipelineFixture.load` reads a directory by the same rules. Name the fields, move the files.
- **The `cosec-gateway-server` image: no JMX agent, the actuator on a management port, and the image's defaults kept
  under a mounted file** (`UB-2`, `UB-21`). Affected: whoever runs the image. A service on one preview of the library
  behind a gateway image of another: [Upgrading a running deployment](./upgrading.md#upgrading-a-running-deployment).
  - Through beta.3 the start script started a JMX agent on port 5555 with no authentication, on every interface. It no
    longer starts one. To attach, set it in `JAVA_OPTS`, bound to loopback ([the image](./deployment.md#the-image)); the
    `-Dcom.sun.management.jmxremote.host=127.0.0.1` the Kubernetes base set is gone with it.
  - The actuator is served on the management port `8081` (`management.server.port`), no longer on `8080`:
    `/actuator/health/*` on 8080 is `404`. The probes are now `/livez` and `/readyz` on 8080 (the same groups, served on
    the gateway's own port, not decided by CoSec); `/actuator/health` and `/actuator/prometheus` are on 8081. Change a
    probe or health check of your own to `/livez` and `/readyz` (the image's `HEALTHCHECK` and the `k8s/` manifests
    did), and publish 8081, if you scrape it, to a monitoring network only.
  - The image has `cosec-micrometer` and a Prometheus registry (Micrometer 1.17.2, from Spring Boot's dependency
    management): `cosec.authorization`, `cosec.snapshot.*` and the others are on `/actuator/prometheus`.
  - The image's defaults are now in its jar, and a `config/application.yaml` you mount is read in addition to them,
    key by key; it used to replace them (Kafka audit, login and ip2region came back on). A file that set what it needs
    is unaffected; one that left the defaults out now gets them. A list such as `routes` is still replaced whole.
  - `k8s/base`: the demo CORS origin is a placeholder (`https://app.example.com`, no longer `*`; `max-age` is in
    seconds), the demo route and discovery list are marked, the container has a `management` port, and the production
    NetworkPolicy admits it from the `monitoring` namespace only.
- **A `cosec.*` switch whose value CoSec cannot read fails startup** (`UB-1`). Through beta.3 a value such as
  `cosec.enabled=1`, `on`, an empty string or ` true ` was true to Spring's binder and "off" to the condition that wires
  the beans, so CoSec, or the feature, was switched off without a word; an empty `cosec.pipeline.mode` wired no pipeline,
  and a Spring Cloud Gateway host started as an open proxy. `cosec.authorization.shadow.enabled=off` (or `0`) left
  shadowing on. Now the starter refuses, before any bean, every switch that is not `true` or `false` (any case, no
  surrounding spaces; `cosec.pipeline.mode` accepts `enforce` or `gateway`, `cosec.api-key.store` `memory` or `redis`),
  naming the key, the value found and the values accepted
  ([Startup fails on an unreadable switch](./troubleshooting.md#startup-fails-on-an-unreadable-switch)). Affected:
  a configuration, an environment variable or a Helm value that writes a switch another way. Write `true` or `false`,
  or remove the key to take its default. A switch written `false` still switches its feature off.
- **The `cosec.authorization` timer has a `problem` tag, and every series has the same tag keys** (`UB-8`). The new tag
  holds the slug of the problem the client was told (`unauthorized`, `token-expired`, `forbidden`, ...) or `none`, so a
  `401` tells apart from a `403` and one token problem from another
  ([Metrics](./observability.md#metrics)). The `cause` tag, which only `indeterminate` series had, is now on every
  series, `none` outside `indeterminate`: a Prometheus registry refuses meters of one name with different tag keys.
  Affected: a query that selects series by the absence of `cause` (`cause=""`) now selects `cause="none"`; a sum over
  the new tags is the old series.
- **One vocabulary for what decided: metrics, audit and the decision event's method** (UD-7, UD-11). Through beta.3
  each output named a decision its own way, and an alert on explicit denies also counted requests no DENY statement
  refused. Now every output takes the decision event's words ([What decided, in every output](./observability.md#what-decided-in-every-output)):

  | Output | Before (beta.3) | Now |
  |---|---|---|
  | `cosec.authorization` `result` | `allow` | `permit` |
  | | `explicit_deny` for a DENY statement, a scope rejection and a boundary refusal | `explicit_deny` (a DENY statement only), `scope_rejected`, `boundary` |
  | | `bad_request` | `invalid_request` |
  | Audit `authorization.decision` | `ALLOW` | `PERMIT` |
  | | `EXPLICIT_DENY` for a DENY statement, a scope rejection, a boundary refusal and a `400` | `EXPLICIT_DENY`, `SCOPE_REJECTED`, `BOUNDARY`, `INVALID_REQUEST` |
  | Audit `authorization.reasonCode` | `ALLOW`; `EXPLICIT_DENY` for a `400`; `TOKEN_INVALID` for a token not valid yet | `PERMIT`; `INVALID_REQUEST`; `TOKEN_NOT_YET_VALID` |
  | Decision event `subject.method` | `token` for every token, `apikey` | `jwt` (CoSec's own token), `api-key`, another verifier's name (`token` by default): what `CoSec-Identity` says |

  The timer changes together with the `problem` tag and the `cause` on every series (the entry above): migrate a
  dashboard once, for both. Affected: dashboards and alerts on `result="allow"`, on `explicit_deny` counts (they now
  count fewer requests: add `scope_rejected` and `boundary` to keep the old total), on `bad_request`; audit consumers
  (the log, Kafka records) matching `ALLOW` or expecting `EXPLICIT_DENY` for the three other kinds; decision listeners
  matching `subject.method` `token` for CoSec's token or `apikey`. `ApiKeyAuthenticator.METHOD` is `api-key`.
  `AuditDecision` and `AuditReasonCode` (`cosec-api`) lose `ALLOW`: recompile against this version.
- **New log lines** (nothing to change): one INFO summary of what CoSec is configured as, and one WARN when
  `cosec.enabled=false` ([Startup summary](./observability.md#startup-summary)); a WARN, repeated every `lag-warn`,
  while the platform document is absent, naming the key, the key prefix and the Redis it reads
  ([Instance is not ready](./troubleshooting.md#instance-is-not-ready)); a WARN on a Spring Cloud Gateway host that has
  controllers or functional routes of its own, which CoSec does not decide
  ([Web](../integration/web.md#the-gateways-own-handlers)); and the client-address line now says what the default
  `max-trusted-index` risks.
- **Mapped routes that escape `http:` statements are reported** (UC-1; nothing to change). A platform DENY or rate
  limit rule on `http:*` stops applying to a path once a route map maps it. A host with a route map now logs one WARN
  for each document revision it installs that holds such statements, naming them
  ([What CoSec warns about](../scenarios/route-maps.md#what-cosec-warns-about)); a review it could not run is counted
  in `cosec.snapshot.housekeeping.refused` under `task="install-review"`. The control plane, given the services' route
  maps in the new optional `cosec.control.analysis.route-maps`, adds a `ROUTES` warning to a change whose candidate
  holds them ([Validating policies](../policies/testing.md#validating-policies)).
- **`IdentityHeader` in `cosec-testkit`** (#1656, experimental; nothing to change) writes the `CoSec-Identity` header a
  gateway forwards, with CoSec's own encoder, for a test of a service behind a gateway or a local client: `of`,
  `forSubject` and `withToken`
  ([Testing your code against a real pipeline](../policies/testing.md#testing-your-code-against-a-real-pipeline)).
- **Security: a check of a request that has ended is denied** (`PIP-26`). Through beta.3, a `SecurityContext` kept
  after its request (in a field, a cache, a message to a consumer) went on permitting with
  `AuthorizationChecker`, on the memberships it was made with, after logout, a revoked token or a removed role. Now the
  adapter that bound the request ends it when the request completes, and every check of it afterwards is `false`
  (`decide` answers `Deny(null)`). A request in progress is answered as before, however long it runs: a streamed or
  server-sent-events body, an async servlet request. Affected: code that keeps a context and checks with it after the
  response was written. Check within the request instead; work that runs afterwards (a consumer, a scheduled job)
  carries its own authorization ([Checks in code](../integration/checks.md#how-a-check-relates-to-the-request-pipeline)).
  A context of a request your own transport handed to `Pipeline.evaluate`, and the test kit's, is ended only when you
  end it: close the `Outcome`, or call `PipelineFixture.end` / `PermittedRequest.end` (below).
- **Security: the readers of the security context answer only a context the pipeline made** (`PIP-27`).
  `exchange.getSecurityContext()`, `ContextView.getSecurityContextOrEmpty()` / `getSecurityContext()`,
  `request.getSecurityContext()`, the `SecurityContext` handler parameter and `SecurityContextHolder` returned whatever
  object sat under `SecurityContext.KEY`, so an application filter or a library that wrote its own was taken for an
  authenticated caller. Now anything else reads as no context: `null`, or the failure an unbound context is. Affected:
  code, test code above all, that binds a `SecurityContext` it made itself (a mock, an implementation of the
  interface) under that key. In a test, use a context the pipeline made and let the adapter's test entry put it in
  place ([Testing your application](../policies/testing.md#testing-your-application)): a handler test runs behind
  CoSec's own filter, `CoSecTestBindings.filter(fixture.pipeline)` (`cosec-webflux`, `cosec-webmvc`), with a fixture
  principal's `Authorization`; a unit test binds `PermittedRequest.forSubject(...)` (or `withIdentity`, `withToken`)
  with `CoSecTestBindings.withSecurityContext` or `bind`. A filter of your own that runs the pipeline binds
  `outcome.security!!.securityContext`.

  Such a test does not always fail loudly. The context it bound reads as no context, so the code under test sees a
  missing or anonymous caller (a `401`, a refused handler); and where your code lets an absent context through, for
  example as "a command with no request of its own", it gives a permit the test did not expect: a test that expects a
  denial sees an allow. Search your tests for `SecurityContext.KEY`, `mockk<SecurityContext>` and every class that
  implements `SecurityContext`.
- **Breaking: three shapes of a document that never did what they read no longer compile** (`DOC-9`, `DOC-10`,
  `DOC-11`). Each refuses a document whose statement already did not apply as written:
  - `ignoreCase` on `eq` or `prefix` with a literal holding a letter beyond ASCII (`Ärzte`), where missing a spelling
    lets a request through: in a DENY statement or a rate limit rule, an ALLOW under a `not`, or a policy condition
    guarding a DENY. The fold is ASCII only, so `ärzte` passed the DENY. Write every spelling in an `in` without
    `ignoreCase` ([Case folding](../policies/language.md#case-folding)). In an ALLOW it still compiles, with a warning.
  - `resource.attributes.<name>`, in a condition or a rate limit `key`: nothing sets it, so a DENY on it denied
    nothing. Put the fact in the resource id and read it as `resource.var.<name>`
    ([Object-level rules](../integration/checks.md#object-level-rules)). A test kit scenario that sets
    `request.resource.attributes` is refused too.
  - A `subject.id` literal beginning with `(` (`{"not": {"eq": ["subject.id", "(0)"]}}` matched every anonymous
    request) and a principal binding to an id beginning with `(` (it never applied). Write
    `{"eq": ["subject.authenticated", false]}` for anonymous requests.

  Affected: whoever stored such a document. After the upgrade an instance installs it as failed, and every request
  that reads it fails closed (`503`), so find them first. With beta.3 still running, read each head
  (`GET <control base>/documents/{key}`, [the control API](../policies/testing.md#the-control-plane)) or take the
  files you publish from, and search them:
  `grep -nE 'resource\.attributes\.|"subject\.id" *, *"\(|"principal" *: *"\(' <files>` finds the second and third
  shapes; for the first, `grep -n '"ignoreCase": *true' <files>` and look for a letter beyond ASCII beside it
  (`grep -nP '[^\x00-\x7F]'`). Or load the files with this version's `PolicyTestKit.load`, which compiles each document
  as an instance does and names the path of each error. `cosec-migrate convert` now refuses the same shapes from 7.x
  data: an ALLOW on a 7.x resource attribute is a statement that can never hold, which you remove.
- **Breaking: `:apply` with `REPLACE` checks the revision the file was read at.** Through beta.3 the header's
  `revision` was required and ignored, and a `REPLACE` removed whatever the head held that the file did not, including
  what was published after the file was read. Now a file whose `revision` is not the head's is a `409`. Affected: jobs
  that apply a file from git with a fixed or stale `revision`. Send the revision the job last read (`GET
  /documents/{key}`, its `revision`), merging what changed since ([Changes](../policies/testing.md#changes)). A
  document not published yet is not compared. `MERGE` is unchanged, and its change now names, in a `WARNING`, the
  entries it keeps because the file leaves them out.
- **A change that would lock its publisher out of the control plane needs an acknowledgement.** A change to the
  platform document, the platform tenant's or the control app's that would leave its operator unable to reach the
  control plane carries a `V4` warning, and its publish is a `422` unless the body says `"lockout": true`
  ([Publishing and rolling back](../policies/testing.md#publishing-and-rolling-back)). Affected: automation that
  publishes such a change on purpose; add the acknowledgement. The way back from a lock-out is the new
  `cosec-migrate restore`, not an emptied Redis ([Recovering from a lock-out](./storage.md#recovering-from-a-lock-out)).
- **Fixed: a lost invalidation is installed by the first pass after it, even past a load in flight.** The beta.3
  release notes listed it as a known issue: when a revalidation pass met a load of a document already in flight, it
  took that load's read as its confirmation, though the read could predate the change, so a change whose invalidation
  was lost could take two `revalidate-interval`s to install, and decisions used the previous revision meanwhile. A
  pass now joins only a load that started after it, and otherwise waits for one more load (`SNP-6`,
  [Storage](./storage.md#how-instances-read-documents)). Nothing to change; Redis may see one more conditional
  read of a document when a pass meets its load.
- **The `cosec-gateway-server` image turns ip2region off.** Its `config/application.yaml` now sets
  `cosec.ip2region.enabled: false`, as the Kubernetes base already did; through beta.3 the image left it on. A document
  that reads `ext.ip2region.*` no longer compiles on it: set `COSEC_IP2REGION_ENABLED=true` before you upgrade the
  image. Its Docker `HEALTHCHECK` now probes `/actuator/health/liveness`, so a gateway that waits for its platform
  document is no longer reported unhealthy.
- **`@ahoo-wang/cosec-react`: `logout` ends the session at the server.** `useSecurity` and `SecurityProvider` gain the
  `tokenManager` option (the `JwtTokenManager` of the same `TokenStorage`, with a `tokenRevoker`) and `logout()`, which
  calls the server's logout and then removes the pair here
  ([Building a client](../integration/clients.md#logout-and-switching)). `signOut` is unchanged: it removes the pair
  here only, and the session stays refreshable at the server until its refresh token expires. Nothing breaks; point
  the user's "Log out" at `logout`.
- **Security: whoever builds a `TokenAuthenticator` names its revocation reader** (`IDN-27`). Through beta.3,
  `TokenAuthenticator(verifier)` compiled and read revocations from `RevocationReader.NONE`: an authenticator an
  application built itself (a custom `Authenticator` bean, a pipeline outside Spring) accepted every revoked token until
  it expired, after logout or `revokeSubject`, and nothing warned. The parameter has no default any more, and neither
  has `PermittedRequest.withToken`'s (`cosec-testkit`). Affected: code that builds either without a reader; it no
  longer compiles. Pass the reader of the `TokenStore` your logout writes to (the starter's `RevocationReader` bean),
  or `RevocationReader.NONE` where nothing is ever revoked, such as most tests. Binary compatible; the starter is
  unchanged.
- **`RefreshTokenAuthentication(verifier, revocation, families)` names its `MembershipRefresh`** (`IDN-27`). The
  factory defaulted to `MembershipRefresh.CLAIMS`, which keeps the roles the old token claimed, while the KDoc of
  `MembershipRefresh` said the opposite. Its KDoc now says what happens, and the factory has no default. Affected: code
  that calls the factory with three arguments; it no longer compiles. Add `MembershipRefresh.CLAIMS` to keep today's
  behaviour (a removed role applies at the next sign-in or after `revokeSubject`), or a `MembershipRefresh` that looks
  the user up again. The starter's refresh route still uses `CLAIMS` unless you declare a `MembershipRefresh` bean. The
  synthetic `RefreshTokenAuthentication$default` method is gone from `cosec-login`: recompile against this version.
- **Security: `JwtTokenVerifier` and `JwtTokenConverter` refuse `Algorithm.none()`** (`IDN-28`). java-jwt's `none`
  accepts any token with an empty signature, so a verifier built on it took tokens anyone can write for CoSec's own,
  break-glass included. Both constructors now throw `IllegalArgumentException`. Affected: code, test code above all,
  that builds either on `Algorithm.none()`. Pass a signing algorithm, such as `Algorithm.HMAC256(secret)`.
- **`CoCacheTokenStore.revoke` refuses a token given for its id.** `TokenStore.revoke(tokenId, ttl)` takes a token's id
  (`VerifiedToken.id`, its `jti`); a logout that passed the access token there revoked nothing and wrote the token as a
  Redis key. Now a value shaped like a token (a JOSE compact serialization, with or without `Bearer `) throws
  `IllegalArgumentException`, which names `TokenRevoker`; no token id CoSec or another issuer gives is refused.
  Affected: a logout written against `TokenStore`, which never worked. Call `TokenRevoker.revoke(accessToken)`.
- **A pipeline built with `Pipeline.builder` counts rate limits** (UA-10). Through beta.3 a pipeline built without
  `rateLimiter(...)` had none: a rule with `strictFailure` answered every request it covered `503`, and every other
  rule was skipped. It now counts in a `LocalRateLimiter` of its own, as the starter's default does, and a request
  over a rule's limit is `429`. `PipelineFixture.of` and `load` count the same way and take a `rateLimiter`, so a test
  sees the `429` (pass a `LocalRateLimiter` with a clock of the test's to cross a window). Affected: a pipeline outside
  Spring, and tests, whose documents have rate limit rules: they are limited now. Give `rateLimiter(...)` a limiter
  shared across instances to limit a service as a whole. The starter is unchanged. `PipelineFixture.of` and `load`
  gained a parameter: recompile tests against this version.
- **A transport of your own can end its requests** (`PIP-26`). `Outcome` is `AutoCloseable`: close it when the
  response is complete (`pipeline.evaluate(facts).use { … }`), or from the response's completion callback when it
  outlives the handler, and every later check of its context is `false`
  ([Pipeline outside the adapters](../architecture/extending.md#pipeline-outside-the-adapters)). In tests,
  `PipelineFixture.end(context)` and `PermittedRequest.end(context)`. Nothing breaks: a transport that does not close
  it behaves as before.
- **A check refused for how it was called is logged** (UA-5). A context the pipeline did not make, a check after its
  request ended and a malformed action string still answer `false` (`Deny(null)`), and now log a `WARN` once a minute
  per kind, naming the request ([Troubleshooting](./troubleshooting.md#a-check-answers-false)). Nothing to change.
- **The request's `Principal` is CoSec's if and only if CoSec authenticated the request, on every stack** (`PIP-28`).
  Through beta.3, WebFlux and the gateway gave an anonymous caller the anonymous principal `(0)` as
  `exchange.getPrincipal()` and as a `java.security.Principal` handler parameter, so code that took "a principal is
  present" for "signed in" counted every anonymous caller as one user; Spring MVC set none at all. Now:
  - WebFlux and the gateway set none for an anonymous request. Affected: code that expects a principal for anonymous
    callers (an `exchange.principal.map { … }` that now completes empty, a non-null `Principal` parameter).
  - Spring MVC sets CoSec's principal for a signed-in request: `getUserPrincipal()` is it, `getRemoteUser()` its id,
    and a `Principal` handler parameter is no longer null. It replaces a principal the container or a filter in front
    (Spring Security) had set; on a request CoSec treats as anonymous, that one is left as it was. `isUserInRole` is
    `false` and `getAuthType()` `null` on a request CoSec authenticated. Affected: servlet applications that read
    `getUserPrincipal()` or `getRemoteUser()` (they now get CoSec's caller for signed-in requests) and ones that ask
    `isUserInRole` behind CoSec (ask CoSec through a check instead).

  Read the caller from the `SecurityContext` (`principal.authenticated`, `principal.id`), the same on all three
  ([Web](../integration/web.md)).
- **Fixed: a token from an issuer whose clock runs ahead is accepted** (#1666, `IDN-29`). Through beta.3,
  `JwtTokenVerifier` allowed no room for clocks: a token whose `iat` was even milliseconds later than the verifier's
  clock was `401 token-invalid`, so a deployment whose issuer and gateway do not share a clock saw intermittent `401`s
  in the last milliseconds of a second. A token's `iat` and `nbf` may now be up to
  `cosec.authentication.revocation.skew` (5 seconds) ahead; `exp` stays strict. A token beyond the skew is still
  `token-invalid` to the client, and the decision event names it `TOKEN_NOT_YET_VALID` (a new member of
  `AuthnResult.RejectReason`, a set that grows; a `when` over it already has an `else`); the log says
  `Token refused as not valid yet`. `JwtTokenVerifier(algorithm, issuerSkew)` takes the skew, the starter passes
  `cosec.authentication.revocation.skew`; a verifier you build yourself defaults to 5 seconds, so pass the value your
  `TokenAuthenticator`'s `revocationSkew` uses if you changed it. Nothing breaks.

- **What the host logs at startup.** Nothing breaks, but the log an operator reads changes. A `cosec.*` property CoSec
  does not read fails startup before any bean is created, so a configuration with removed keys hears of every one
  first. When tokens are verified against `RevocationReader.NONE`, the host logs a `WARN` that says a revoked token is
  accepted until it expires. When the token stores are missing, one startup message names every store that is
  missing, not the first.
- **Testing an application** (#1679, `PIP-29`, nothing to change). `PermittedRequest.forSubject(subject, tenant, app,
  space, roles)` makes a signed-in caller's context with no token and no documents, the same `PipelineFixture` makes;
  `PermittedRequest.withIdentity(header)` makes the one a service behind a gateway reads. `CoSecTestBindings`
  (`cosec-webflux`, `cosec-webmvc`, package `testing`) runs CoSec's own filter over a test pipeline, or binds a kit
  context in the Reactor context, the exchange, the request or `SecurityContextHolder`, refusing a context made by
  hand. `PipelineFixture.authorization(principal)` is a fixture principal's `Authorization` for a request a test builds
  ([Testing your application](../policies/testing.md#testing-your-application)).

## From 8.0.0-beta.2 {#from-8-0-0-beta-2}

- **Security: a revocation table keeps "not revoked" for 30 seconds at most.** Through 8.0.0-beta.2 the `absent-ttl`
  of `RevokedTokenCache` and `RevokedSubjectCache` was not locked, so `cocache.caches.RevokedTokenCache.absent-ttl`, a
  `cocache.defaults.store.absent-ttl` or a customizer could lengthen it, and an instance that missed an invalidation
  accepted a revoked token for that long. It is now CoSec's, 30 seconds: a `cocache.caches.[name].absent-ttl` for
  either table fails startup (remove it), and the global defaults and customizers no longer reach it.
- **Security: requests verify with the application's `TokenVerifier`.** Through 8.0.0-beta.2 the starter's pipeline
  verified request tokens with CoSec's own `JwtTokenVerifier`, even when the application declared a `TokenVerifier`
  bean, which refresh and logout did use. Now a request, a refresh and a logout verify with the same one (`IDN-25`).
  What changes depends on your verifier:
  - A verifier of another issuer's tokens now works on requests as it always did on refresh.
  - A verifier that wraps CoSec's own (`JwtTokenVerifier` and a check of yours) makes its tokens not CoSec's on every
    request: the principal's credential is `token`, not `jwt`, it has no session, so no
    [tenant switch](../integration/authentication.md#switching-tenant-and-app) from that request's session and no
    session forwarded by a gateway, and it never gets break-glass. Through beta.2 its requests had all three. Leave such
    a verifier out unless that is what you want.

  Nothing changes for an application without its own `TokenVerifier`.
- **Security: the served refresh route uses your `RefreshTokenAuthentication`.** Through 8.0.0-beta.2 the route built
  its own and used only a `MembershipRefresh<RefreshTokenCredentials>` bean, so a hook declared inside your
  `RefreshTokenAuthentication` bean (refusing a disabled user, say) did not apply to it. Now your bean serves the
  route. One over a credentials class of your own fails startup while `cosec.authentication.login.refresh.path` is
  set: serve that refresh from your own route and unset the path, or declare it over `RefreshTokenCredentials`.
- **A session store that fails or cannot do a call.** A store that fails in `TokenRevoker.revokeSubject` (Redis down,
  say) now throws `SessionStoreUnavailableException`, as `revoke` already did: answer `503` and revoke again. Code that
  caught the store's own exception around `revokeSubject` catches `SessionStoreUnavailableException` instead. A
  session store that throws `UnsupportedOperationException`, because it cannot do the call at all, now passes it
  through on every session-store call (login, refresh, tenant switch, logout and both revokes), where it used to leave
  as `SessionStoreUnavailableException`: it is configuration, not an outage. On the served refresh and logout routes
  that is `500` where it was `503`.

## From 8.0.0-beta.1 {#from-8-0-0-beta-1}

- **Before upgrading: add `+evalsha_ro +eval_ro` to every data-plane Redis user whose commands are restricted.** An
  instance that does not run the control plane now reads the documents and the candidates read-only, as `EVALSHA_RO`
  (Redis 7.0 or later, which CoCache 8 already requires). With a user that lists its commands, such as the one
  beta.1's [Who may write which keys](./storage.md#who-may-write-which-keys) showed (`-@all` and then `+evalsha +eval`
  among others), the upgraded instance fails at startup:
  `Read-only store of Keyspace(...): Redis denied the command [evalsha_ro]: allow [+evalsha_ro] ...`. Add the two
  commands first; a user without a command list (`+@all`) needs nothing. Narrowing the documents and the candidates to
  read permission (`%R~<s>:snapshot:* %R~<s>:candidate:*`) is optional, and the page shows it. A process that runs the
  control plane is unchanged: it still writes them.
- **`cosec.control.enabled` switches the control plane off on an instance** (an addition: nothing to migrate; the
  default, `true`, changes nothing). An image that carries `cosec-control` for both roles sets
  `COSEC_CONTROL_ENABLED=false` on the data-plane role: it runs no control plane, reads the documents and candidates
  read-only, and can use the read-only user of
  [Who may write which keys](./storage.md#who-may-write-which-keys). A value other than `true` or `false` fails startup.
- **The starter can serve the refresh and logout routes** (an addition: nothing changes until a path is set).
  `cosec.authentication.login.refresh.path` and `logout.path` serve them on Spring MVC and WebFlux, with the statuses
  and bodies of [Building a client](../integration/clients.md#what-your-application-serves). A route you wrote keeps
  working: unset the path and nothing is served. To move onto the served one, set the path your clients call, delete
  your handler and its exception mapping, and keep the statement that permits an anonymous `POST` of it.
- **A failure of the session store leaves `TokenFamilies` and `StoreTokenRevoker` as `SessionStoreUnavailableException`**
  (`cosec-login`), with the store's own as its cause, so that a store that cannot answer (`503`) is told apart from an
  error (`500`). Code that caught the store's own exception type around a refresh, a switch or a logout catches it
  instead. `StoreTokenRevoker.revokeSubject` is unchanged.
- **`MembershipRefresh` refuses with a `TokenVerificationException`** (`TokenRevokedException` when the membership is
  gone), a `401`; any other exception is an error, `500` on the served route, and the client keeps its pair. A hook
  that refused with another exception, such as `IllegalStateException`, throws `TokenRevokedException` instead.
- **The snapshot source runs each document key through its own runner** (nothing to migrate, no API or property
  change; decisions are unchanged). What an operator sees differs:
  - A request made while its document is loading, and taken only after that load ended, is served the installed
    document as a hit, where beta.1 joined it to the load as a cold one: the same document, but one fewer
    `cosec.snapshot.loads{result=cold}`.
  - A store read that answers "absent" after `close` ended its load is not reported: a closed instance no longer logs
    `Snapshot document [<doc>] disappeared from the store; failing closed.` or counts `cosec.snapshot.lost` for it. One
    abandoned at the store read timeout (30 seconds) that answers "absent" late is reported by the document's next
    load, not at once.
  - The bound on ids without a document holds without a gap
    ([How instances read documents](./storage.md#how-instances-read-documents)): an eviction pass skips an entry that
    is loading, and when that load finds the document still absent, the entry now asks for the bound itself. In beta.1
    the family stayed above that bound until some later install.
  - When the platform document changes and every document is recompiled, a document whose load was in flight may be
    loaded once more: at most one extra read of the store per document.
  - Each document held costs about 64 bytes more heap, and each cold load allocates about 1.2 KB more.
- **The snapshot source is metered** (an addition: nothing to migrate). With `cosec-micrometer` and a `MeterRegistry`,
  the data plane's snapshot source now registers the `cosec.snapshot.*` meters: 80 new series, whatever the number of
  documents, since no tag carries an app or tenant id. A request that finds its documents installed is not counted:
  only the loads a request had to wait for. [`cosec.metrics.enabled`](./configuration.md#feature-switches)`=false` turns them off with the
  rest of CoSec's metrics. The meters and what to alert on are in [Snapshot metrics](./observability.md#snapshot-metrics).
- **What the snapshot source's housekeeping thread refuses is dropped and counted** (`SNP-19`; nothing to migrate).
  That thread writes the source's log lines and runs its eviction passes; its queue holds 4,096 tasks. When the queue
  was full, beta.1 ran the refused task on the thread that offered it, often a request's, so a slow log appender slowed
  requests. Now the task is dropped and counted in `cosec.snapshot.housekeeping.refused{task}`, and the thread writes
  one `WARN` at most every 10 seconds, `Snapshot housekeeping since its last summary: refused <n> tasks, full or stopped
  …`, naming the last line dropped for each task without its stack; a closing instance writes what is left once. A
  meter that throws is counted in the same line instead of logged. Nothing to do, unless the summary appears: then use
  an asynchronous log appender ([Snapshot log lines are missing](./troubleshooting.md#snapshot-log-lines-are-missing)),
  and alert on any rise of the counter.

## From 8.0.0-alpha.4 {#from-8-0-0-alpha-4}

**If you published documents `cosec-migrate convert` wrote with any preview, check their ALLOW statements** (the first
entry below): a converted 7.x "every action" granted the control plane. 8.0.0-alpha.4 is a preview with no
compatibility promise. **Every token issued before this version declares no app, so a
service with an app refuses it** (below): plan a re-login, or have tokens expire first. API keys stored in format 2 fail
and have to be provisioned again. **Close or withdraw open shadow windows before you upgrade**: a candidate staged by
8.0.0-alpha.4 states its header beside its document, and is refused as unreadable, so its window is not compared.

- **The revision index entries gain a `parent`, and the first read of each document rewrites an index an alpha
  wrote** (`CTL-35`). An entry of `<s>:control:revisions:<doc>` carries the digest its document names as its parent,
  and a document's history is returned only when its last entry is the head and each entry names the one below it. An
  index written before has no `parent`: the first read of each document (a control API call, or the maintenance
  pass) walks the head's chain once, decoding each retained record, and rewrites the entries with it; no migration is
  needed, and nothing is lost. `RevisionEntry`'s constructor has a fourth parameter, `parent` (default `null`), for
  code that builds one. The same read repairs a head that moved back and a reused revision number, which used to
  read the old branch's document; see [Revisions, history and rollback](./storage.md#revisions-history-and-rollback).
- **Security: a tenant switch no longer issues from a revoked or ended session.** From 8.0.0-alpha.1 to
  8.0.0-alpha.4, a switch read no revocation itself: while the revocation store could not be read, which the request
  path then read as "not revoked", a logged-out token, or one of a revoked principal, could be switched into a new token
  that no revocation names. Sessions came after 8.0.0-alpha.4, and until this change a switch also started a live
  session from a token whose own session was revoked: by a pair presented again after a refresh (`IDN-16`), whose access
  tokens stay valid for up to `cosec.jwt.token-validity.access` (10 minutes), or by a logout. Whoever copied a pair and
  refreshed first could so hold a session until the user's would have ended, up to `cosec.jwt.session.lifetime`. That
  was in no preview. A switch now issues only from a live session and reads revocations strictly, in the one check a
  refresh makes too (`IDN-22`, `IDN-14`): from a dead session it answers `token-revoked` or `token-expired`, and 503
  while a store cannot be read. A token a preview's switch issued during an outage of the revocation store is not
  revoked by the revocation it escaped; if one may have been, [revoke the principal](../integration/authentication.md#revoking-every-token-of-a-principal).
- **Security: a converted 7.x "every action" no longer grants the control plane.** From 8.0.0-alpha.1 to
  8.0.0-alpha.4, `convert` wrote an ALLOW of every 7.x action as `"action": "*"`, which also matches every
  control-plane action (`cosec:*`) and every action of a check in code. A converted global policy such as "signed-in
  users may do anything" gave every authenticated principal every control-plane action on its own tenant's document,
  and a principal of `(platform)` on the platform's and the app documents. An attachable one did the same for every
  token that lists it. Such a principal could write and grant in that document, and another holding the same grant
  could approve it. `convert` now writes `"action": "http:*"`
  ([Every action is every HTTP request](./upgrading.md#every-action-is-every-http-request)); a DENY of every action stays `*`.
  If you published converted documents:
  1. Find the ALLOW statements on `*` in each document (`platform`, `app:<id>`, `tenant:<id>`). An administrator
     reads the head through the control API ([Revisions, history and rollback](./storage.md#revisions-history-and-rollback)):

     ```bash
     curl -s "$CONTROL/documents/platform" -H "$AUTH" \
       | jq -r '.policies[] | select(any(.statements[]; .effect == "allow" and ([.action] | flatten | index("*")))) | .id'
     ```

     The ones `convert` wrote are in the conversion's `platform.json`, `apps/` and `tenants/`, and its
     `provenance.json` names the 7.x policy of each. A statement on `*` you wrote yourself after the upgrade is yours
     to keep.
  2. Change each converted one to `http:*` through the control plane: a change that puts the policy again with
     `"action": "http:*"`. If a document has not changed since `publish`, `apply` the file a new `convert` writes for
     it instead; the change it proposes is exactly those statements.
- **Tenant and app ids hold no `/`** (`SCP-13`). An 8.0 preview accepted `acme/west` as a tenant id and `a/b` as an
  app id; this version refuses them at every entry, so a preview deployment that stored one must remove its records
  before upgrading. Find them with `GET /documents` on the preview: any key with `/` after `tenant:` or `app:`. The
  preview's own control plane cannot remove them, since it refuses such ids too. Stop the control plane, then, for each
  such document `<doc>` (`tenant:acme/west`), delete these Redis keys under `<s>` ([Redis keys](./storage.md#redis-keys)):
  - `<s>:snapshot:<doc>` (the head) and `<s>:candidate:<doc>` (a staged candidate);
  - `<s>:control:revisions:<doc>` and every `<s>:control:revision:<doc>:*` (the revision index and records);
  - every `<s>:control:refs:<doc>:*` (back-references to its policies);
  - `<s>:control:stage:<doc>` and `<s>:control:lease:reclaim:<doc>`;
  - every `<s>:control:change:<id>` whose `"key"` is `<doc>`, and its id in `<s>:control:list:open-changes`;
  - the entry `<doc>` in `<s>:control:list:documents`, and, for an app, its entry in `<s>:control:apps:all`;
  - every `<s>:api-key:<sha256>` whose `"tenantId"` or `"app"` holds `/`.

  Then delete each `<s>:control:refs:*` set that names `<doc>` among its referrers, and run
  [`repair-references`](./upgrading.md#repair-the-back-reference-index), which writes back what the remaining heads refer to. Until
  this is done, this version fails whole on each such record and names it (`SCP-10`): a back-reference set fails the
  publish over its policy, a change answers `503`, an open change stops every maintenance pass, and an API key fails
  its lookup (`503`). An instance status of a preview that installed such a document fails the shadow windows' statuses
  until it expires, two minutes after that instance stops (the time to live of its status key,
  `ShadowCacheSpecs.STATUS_TTL`). Rename the tenant or app and publish its documents under the
  new id; tokens of the old id are `401 token-invalid`
  ([Tenant and app ids hold no slash](./upgrading.md#tenant-and-app-ids-hold-no-slash)).
- **No CoSec module imports a platform, and `cosec-dependencies` is no longer published** (R11). Until now every library
  module's POM and Gradle module metadata imported `cosec-dependencies`, which imports the Spring Boot, Spring Cloud,
  CoCache, OpenTelemetry, springdoc and fluent-assert BOMs and manages jmh, mockk, archunit, blockhound,
  json-schema-validator and detekt-formatting, so adding one module raised those versions in the whole build of an
  application, `cosec-cocache`'s CoCache 8 over an application's CoCache 4 included. Now a module's metadata names the
  libraries it needs with the versions it was built with, as minimums, and nothing else; Maven no longer needs the import
  to resolve them. An application that imported `me.ahoo.cosec:cosec-dependencies` removes the import and manages the
  versions it wants itself ([what CoSec needs from your build](../guide/introduction.md#what-cosec-needs-from-your-build)).
  `cosec-bom` lists the CoSec modules an application can depend on, without `cosec-migrate`, and imports nothing.
  The POMs of `cosec-cocache` and `cosec-kafka` mark `cosec-control` `provided`: a Maven application that uses the
  control plane adds `cosec-control`, which they used to bring (with `cosec-runtime`) to run time.
- **Three packages follow their modules' names.** `me.ahoo.cosec.cache` (`cosec-cocache`) is now
  `me.ahoo.cosec.cocache`, `me.ahoo.cosec.servlet` (`cosec-webmvc`) is `me.ahoo.cosec.webmvc`, and
  `me.ahoo.cosec.spring.boot.starter` (`cosec-spring-boot`) is `me.ahoo.cosec.spring.boot`. Sub-packages keep their
  names under the new root; no class, artifact id, `cosec.*` property, bean name or Redis key changes. In an
  application, change the imports; the names of auto-configuration classes in `spring.autoconfigure.exclude` and in
  `@ConditionalOnClass(name = ...)` strings (`me.ahoo.cosec.spring.boot.starter.CoSecAutoConfiguration` is now
  `me.ahoo.cosec.spring.boot.CoSecAutoConfiguration`); and the logger names in its logging configuration
  (`logging.level.me.ahoo.cosec.cache` is now `logging.level.me.ahoo.cosec.cocache`).
- **The policy documents, route maps and the JSON formats are a module of their own, `cosec-document`.**
  `me.ahoo.cosec.runtime.document` is now `me.ahoo.cosec.document`, `me.ahoo.cosec.runtime.document.format` is
  `me.ahoo.cosec.document.format`, and `me.ahoo.cosec.runtime.route` is `me.ahoo.cosec.document.route`; the classes
  (`PolicyDocument`, `PolicyDocumentCodec`, `PolicyDocumentCompiler`, `DocumentCompileException`, `Actor`, `RouteMap`,
  `RouteMapCodec` and the rest) keep their names. No document, schema, `cosec.*` property, bean or Redis key changes.
  In an application, change the imports. `cosec-runtime` and the starter bring `cosec-document`, so no dependency
  changes; code that only reads or writes documents (a generator, a [CI check](../policies/testing.md#policies-in-ci))
  can depend on `me.ahoo.cosec:cosec-document` alone instead of `cosec-runtime`. `cosec-openapi` now depends on
  `cosec-document`, not `cosec-runtime`: an application that compiled against the pipeline through it adds
  `cosec-runtime`, or the starter, itself.
- **The shadow window ports and their values are in `cosec-spi`.** `CandidateStore`, `StoredCandidate`,
  `ShadowReportSink`, `ShadowReportReader`, `ShadowReport`, `ShadowSample`, `ShadowOutcome`, `InstanceStatus` and
  `InstalledDocument` move from `me.ahoo.cosec.runtime.shadow` to `me.ahoo.cosec.spi.shadow`, still experimental, with
  their names. A store of your own changes its imports and can depend on `cosec-spi` alone. `cosec-cocache` no longer
  depends on `cosec-runtime`: an application that compiled against the pipeline through `cosec-cocache` alone adds
  `cosec-runtime`, or the starter, itself. No stored format or Redis key changes.
- **Issuing tokens is `cosec-login`'s.** `TokenFamilies`, `DefaultTokenRevoker` and `SimpleCompositeToken` move from
  `me.ahoo.cosec.runtime.token` to `me.ahoo.cosec.login.token`, with their names, and two of them changed again:
  `DefaultTokenRevoker` is `StoreTokenRevoker` in the same package, and `SimpleCompositeToken` is gone, replaced by
  `CompositeToken` in `cosec-api` (both below); `me.ahoo.cosec.runtime.token` keeps
  what verifying a request's token needs (`TokenRevocation`, `CoSecTokenVerifier`). In an application, change the
  imports; the starter and `cosec-jwt` bring `cosec-login`, so no dependency changes.
- **The adapter contract is one package, `me.ahoo.cosec.runtime.http`.** `RequestGate`, `Outcome`, `Problem`,
  `ProblemType`, `ProblemEncoder` and `RequestSecurity` move from `me.ahoo.cosec.runtime`, and `TraceContext` from
  `me.ahoo.cosec.runtime.event`, beside `HttpFacts`, `Headers` and `Cookies`; the classes keep their names. In an
  application, change the imports: an adapter of your own, a handler that reads `RequestSecurity`, a test that reads
  an `Outcome`. No status code, header, body or property changes. Five loggers are named after the class that now
  logs on its own, so a logging configuration that sets their level uses the new name:

  | Before | After |
  |---|---|
  | `me.ahoo.cosec.runtime.FallbackIds` | `me.ahoo.cosec.runtime.call.FallbackIds` |
  | `me.ahoo.cosec.runtime.attribute.AttributeProviders` | `me.ahoo.cosec.runtime.attribute.FetchedAttributes` |
  | `me.ahoo.cosec.runtime.event.ShadowListener` | `me.ahoo.cosec.runtime.shadow.ShadowListener` |
  | `me.ahoo.cosec.runtime.shadow.Candidates` | `me.ahoo.cosec.runtime.shadow.CachingCandidateSource` |
  | `me.ahoo.cosec.runtime.shadow.ShadowRecorder` | `me.ahoo.cosec.runtime.shadow.ShadowReporter` |
- **The change log has a logger named on purpose.** `LoggingChangeListener` wrote the change log to the logger
  `me.ahoo.cosec.control.audit.ChangeAudit`, a name that came from the file it was in; it now writes to
  `me.ahoo.cosec.changelog` (`LoggingChangeListener.LOGGER_NAME`), as the decision audit writes to
  `me.ahoo.cosec.audit`: a name outside every package, so no class logger is a child of it. A logging configuration or a log shipper that routed or filtered the old name uses the new one
  ([Control-plane change log](./observability.md#control-plane-change-log)). The line itself is unchanged. The dispatcher's
  own warning, `Change listener [<name>] could not record <event>.`, is now logged by the logger of its own class,
  `me.ahoo.cosec.control.audit.ChangeListeners`.
- **A pipeline without Spring is built with `Pipeline.builder`.** `Pipeline.builder(authenticator, store)` compiles and
  installs the documents of a `SnapshotDocumentStore` as the starter does. `memberships`, `attributes`, `listener`,
  `rateLimiter`, `routes`, `ids`, `clock` and `forwardIdentity { … }` set the other parts; `options { … }`,
  `snapshots { … }` and `listeners { … }` set what the parameters of `PipelineOptions`, `SnapshotOptions` and
  `DecisionListeners` set, with the same names and defaults. `snapshots { prewarmApps = …; prewarmTenants = … }` names
  the documents loaded at build, as `cosec.authorization.snapshot.prewarm-apps` and `prewarm-tenants` do for the starter;
  `awaitDocuments()` waits until they have been read and answers those not installed, an empty set when ready. `close()`
  delivers the listeners' queued events, then closes the documents. A service behind a CoSec gateway gets its gate from
  `Pipeline.behindGateway { … }`. `Pipeline`'s constructor, `PipelineComponents` and `SuspendingSnapshotSource.of` are
  gone; `PipelineOptions`, `SnapshotOptions`, `SuspendingSnapshotSource`, `AttributeProviders`, `DecisionListeners`,
  `IdentityIssuer`, `IdentityGate` and `CoSecParameters.read` are now `@InternalCoSecApi`, which applications never opt in
  to. No status code, header, body or `cosec.*` property changes. One change in the safe direction for a pipeline
  assembled by hand: its documents now compile as the starter's do, so a condition or rate limit key on the API key
  header it reads (`options { apiKeyHeader = … }`) does not compile (`CND-5`). Such a document is installed as failed
  and its requests are a 503, where `SuspendingSnapshotSource.of(store)` installed it. A builder given two forms of one
  input (`listener` and `DecisionListeners`, for instance) refuses to build rather than drop one.

  ```kotlin
  val pipeline = Pipeline.builder(authenticator, store)
      .snapshots { prewarmTenants = setOf("acme") }
      .memberships(memberships)
      .build()
  check(pipeline.awaitDocuments().isEmpty()) // before the first request
  ```
- **Durations in public and SPI signatures are `java.time.Duration`.** `AttributeProvider.timeout` is a
  `java.time.Duration?`: a provider of your own writes `override val timeout: Duration? = Duration.ofMillis(200)` with
  `java.time.Duration`, where it wrote `200.milliseconds`. `CoCacheMembershipProvider.DEFAULT_FILL_TIMEOUT` and the
  `drainTimeout` of `KafkaAuditListener` and `KafkaChangeListener` take a `java.time.Duration` too; code that passed a
  `kotlin.time.Duration` converts it with `toJavaDuration()`. No property, default or behaviour changes.
- **`CompositeToken` is a final class in `cosec-api`, and `SimpleCompositeToken` is removed.** Build one with
  `CompositeToken(accessToken, refreshToken)`. It is equal by value and has no `copy` or `componentN`. Logging a token no
  longer prints it: its string is `CompositeToken(accessToken=***, refreshToken=***)`. The JSON a login or refresh
  endpoint answers is unchanged (`{"accessToken":…,"refreshToken":…}`). `RefreshTokenCredentials` declares `accessToken`
  and `refreshToken` itself and no longer is a `CompositeToken`: a credential an application implements does not
  inherit from a value. Your refresh credentials class overrides the two, as it did:

  ```kotlin
  data class RefreshCredentials(override val accessToken: String, override val refreshToken: String) :
      RefreshTokenCredentials
  ```

  Code that passed it where a `CompositeToken` is expected (`TokenRevoker.revoke`) builds one,
  `CompositeToken(credentials.accessToken, credentials.refreshToken)`.
- **`SimpleRefreshTokenAuthentication` is `RefreshTokenAuthentication`**, in the same package and with the same
  constructor: the only one there is needs no prefix. Rename the class where you declare its bean.
- **`DefaultTokenRevoker` is `StoreTokenRevoker`**: it revokes through the `TokenStore`, which the name now says.
  Same package (`me.ahoo.cosec.login.token`) and constructor; the starter's bean is still `cosecTokenRevoker`, and
  `TokenRevoker` is still the SPI. Rename the class where you construct it.
- **`api.CoSec` is removed.** Its values keep their spelling and gain names where they mean something: the anonymous
  principal is `CoSecPrincipal.ANONYMOUS_ID` and the default tenant `TenantId.DEFAULT`, two different ids both spelled
  `(0)`; the `cosec.*` properties and the default Redis key prefix (`cosec.key-prefix`, `cosec`) are as before, and so
  are the span attributes (`cosec.tenant_id`, …) and the OpenAPI scheme `cosec.BearerAuth`. Code that read
  `CoSec.COSEC`, `CoSec.COSEC_PREFIX` or `CoSec.DEFAULT` writes the value it meant. No key, property or attribute
  changes.
- **A tenant switch is `SwitchTenantAuthentication` with a `TenantSwitch`, and it can set the app.**
  `AbstractSwitchTenantAuthentication` is removed. Where you extended it and implemented
  `switchTenant(targetTenantId, previous)`, declare
  `SwitchTenantAuthentication(families, revocation, YourCredentials::class.java) { credentials -> … }` as a bean, with
  the starter's `TokenFamilies` and `TokenRevocation` beans: the switch gets your own credentials class
  (`credentials.targetTenantId`, `credentials.context`, and anything else it carries), and answers a `TenantMembership`
  as before. `SwitchTenantCredentials` gains `targetAppId`, the app the user asks for, null by default, and loses
  `accessToken`: the switch issues from the session of the request, which the `context` the pipeline made carries
  (`IDN-22`), so stop passing the token text (and parsing `Authorization` for it). `TenantMembership` gains `app`. The
  new token is for the app your switch answers, else the previous token's (`SCP-12`): a switch that answers none behaves
  as before, and a user never gets an app by naming it. `TokenSubject.authTime` is removed, and `TokenFamilies.rotate`
  and `TokenFamilies.startAt` (the former `start(…, authTime)`) are CoSec's own (`@InternalCoSecApi`): an issue from an
  earlier login goes through the check of a live session. See [Switching tenant and app](../integration/authentication.md#switching-tenant-and-app).
- **The forwarded identity carries the session and the issue time of CoSec's token** (`auth.session`: `sid`,
  `auth_time`, `generation`; `auth.iat`), so that a service behind the gateway knows when the user signed in and a
  tenant switch there issues from that session (`IDN-22`). The format stays version 1, and a service reads it strictly:
  one older than its gateway refuses the new members with `400`. **Upgrade the services behind a gateway before the
  gateway.** An identity without them (an API key's, or one an older gateway sent) reads as before and cannot be
  switched from. `Identity.Credential` gains `issuedAt` and `session`, both optional.
- **`SecurityContext` gains `requestId` and `deviceId`.** `requestId` is the id CoSec used for the request (the
  client's `CoSec-Request-Id`, or the one CoSec generated, which no header holds); `deviceId` the client's
  `CoSec-Device-Id`, empty when none, which CoSec checks for form only and which proves no device. Read them there
  instead of the headers. A `SecurityContext` of your own still compiles: both default to `""`. Behind a gateway the
  service now reads `CoSec-Device-Id` under the rule the gateway reads it with (one value, else `400`), so
  `environment.device` in a check in code sees it there too; before, it saw no device behind a gateway.
- **A refresh can re-read the user.** `RefreshTokenAuthentication` takes an optional `MembershipRefresh`, which
  answers what the user holds now; the class keeps the verification, the revocation check, the rotation and the session.
  Where you wrote an `Authentication` of your own to re-read the user on refresh, pass a `MembershipRefresh` instead.
  Without one, a refresh keeps the token's claims, as before. The hook is given the token and the refresh's
  credentials (`{ token, credentials -> … }`); to read facts of your own there, a device say, pass your credentials class
  too, `RefreshTokenAuthentication(verifier, revocation, families, YourCredentials::class.java) { token, yours -> … }`,
  as a tenant switch takes its own. `RefreshTokenAuthentication(verifier, revocation, families)` is written as it was.
- **`TokenCompositeAuthentication` is built with `TokenCompositeAuthentication.of(authentications, tokenConverter,
  families)`.** Its constructor took two types no application could build (`CompositeAuthentication`,
  `DefaultAuthenticationProvider`) and is now `@InternalCoSecApi`. The starter's bean is unchanged; a login assembled
  by hand, in a test say, passes its `Authentication`s to `of`.
- **An `http` action no request can have does not compile** (`DOC-8`). A statement or rate limit rule on `http:HEAD`
  (decided as `http:GET` since `PIP-18`) or on a verb with a lower-case letter (`http:get`) matched nothing, so a DENY on
  it denied nothing. Such a document is now refused: a stored one is installed as failed, which fails its requests
  closed (`503`), and the control plane refuses a change that makes one. Find them before upgrading, in the documents
  the control plane lists (`"http:HEAD"`, or an `"http:` action with a lower-case letter after the colon), and publish
  them again with `http:GET`, or the method in upper case.
  For a 7.x conversion, `cosec-migrate convert` reports once, whatever the statements say, that a HEAD request is now
  decided as GET (`PIP-18`: permitted wherever GET is, denied wherever GET is); it reads the country prefix of 7.x's
  `ipRegion` as `ext.ip2region.country`, which the platform document then declares (every host that enforces such
  statements needs `cosec-ip2region`); and a part only 7.x's request appenders filled (`ipRegion`, an application's own
  attributes) is always absent in 8.0, so a condition on it translates only where it narrows an ALLOW (recorded as
  `NOT-IN-8.0`); every other case is refused (`MIG-1`), since it would widen an ALLOW, deny every request in scope, or
  stop a DENY matching what 7.x denied.
- **`cosec-migrate cocache8` is removed.** It moved 8.0.0-alpha.2's data from CoCache 6 to CoCache 8
  ([Data written through CoCache 6](#data-written-through-cocache-6)): a deployment that still holds such data moves it
  with the `cosec-migrate` of 8.0.0-alpha.4 before upgrading. While `<p>:meta:cocache8` is there, `publish` refuses and
  writes nothing: the marker is left by an upgrade that stopped after it deleted the documents, which are in its export,
  not in Redis. Finish that upgrade with the 8.0.0-alpha.4 `cosec-migrate` (`publish --resume`, with `--in` on the export
  the message names), or check that every document the marker lists is published, then delete the key.
- **`cosec-migrate api-keys --app` works** (round 2 M3). The step could not be run since #1302, which added `--app` to
  the code and this page but not to the options the command takes: it refused `--app` as an option it does not take.
  Rerun it with `--app <id>` for keys a service with an app must accept.
- **The starter's startup checks run under lazy initialization** (`STA-3`). With `spring.main.lazy-initialization=true`
  no check ran: a Spring MVC host without `cosec-webmvc` started with no authorization, a Spring Cloud Gateway host in
  `gateway` mode forwarded every request unchecked, and Redis's eviction policy, replica reads, exposed actuator endpoints,
  API keys without a `MembershipProvider` and unknown `cosec.*` properties went unchecked. They now fail startup as they
  do without lazy initialization. A configuration that started only because they were skipped no longer does: fix what
  the message names.
- **A social login registration with an unknown field fails startup** (round 2 L4). A misspelt field under
  `cosec.authentication.login.social.registration.<name>` (`pcke`, `scope`) was ignored, leaving the provider configured
  otherwise than meant; the message now names the key. Rename or remove it.
- **`cosec.cosid.generator` that does nothing fails startup** (round 2 L7). Set without a `CosIdRuntime` bean, or beside
  an `IdGenerator` bean of the application's own, it was ignored and CoSec kept its random ids. Configure CosId, or
  remove the property.
- **Two in-memory API keys with one hash fail startup** (round 2 L5). The key authenticated as whichever entry came last
  in `cosec.api-key.keys`; keep one entry per key.
- **The control plane's authorization beans cannot be replaced, `@Primary` or not** (round 2 L6). A second
  `ControlAuthorizer`, `ChangeAuthority`, `Changes`, `ControlPlane` or `ControlHttp` bean marked `@Primary`, or named as
  an injection point's parameter, was injected in place of CoSec's; it now fails startup, as a plain second bean did.
- **A principal's attributes are read-only** (round 2 L3). `SecurityContext.principal.attributes` was the map the
  authenticator handed over: an application that cast it to a mutable map and changed it changed what every further
  `AuthorizationChecker` check of the request decided on. It is now an unmodifiable view, and a token's nested objects
  and arrays are read-only too: a change throws `UnsupportedOperationException`. Copy the map to change it. An
  authenticator of your own must not change the map it hands over afterwards.
- **The test kit reads a request as enforcement does** (round 2 M2, `PIP-15`). `PolicyTestKit` decided `http:HEAD`, a
  lower-case method and a path with a dot segment as written, so it confirmed a DENY on `http:HEAD` that the pipeline,
  which decides HEAD as GET, never applies. It now decides HEAD as GET and normalizes paths, and answers
  `{"decision": "InvalidRequest"}` where the pipeline answers `400`: rewrite such scenarios, and a statement on
  `http:HEAD` as one on `http:GET`. An `expect` must name a `decision` (an empty one passed whatever was decided), and a
  directory of shortened file names needs the `report.json` `cosec-migrate convert` wrote beside it. The experimental
  `evaluate(JsonNode)` and `check(String, JsonNode)` are gone: pass the scenario as text, `check(name, json)`.
- **A generated route map sits at the `servers` path, and its resources name every path variable** (round 2 L4). The
  `cosecServiceGenerator` endpoint dropped a server path (`/api/v1`), so its routes matched no request and every
  request was in the `http` namespace; and `/users/{id}/avatar/{size}` was the resource `user/{size}`, one resource for
  every user. Routes now start with the server path, without the context path, and the resource is `user/{id}/{size}`:
  generate the route map again, and rewrite the resources a policy names after a generated one. Servers with different
  paths, or a server variable in the path, fail generation.
- **ip2region: what it cannot locate is unavailable** (round 2 M5). An IPv6 client, with only the bundled IPv4
  database, had no country, so a geo-block DENY written as `country in [...]` let it through; it is now unavailable, so
  that DENY makes the request `503` (or lets it through with `onMissing: nomatch`). Load an IPv6 database with
  `cosec.ip2region.ipv6-location`. `Ip2RegionAttributeProvider` takes its databases as a list, each for the version its
  header names, instead of one database and a version.
- **A failing `IdGenerator` costs only its id** (`PIP-23`). One that threw (CosId's `ClockBackwardsException` after the
  clock went back) left the pipeline before any outcome: no problem body, no `CoSec-Request-Id`, no decision event, and
  each adapter answered its own way. A random id now stands in, with a WARN at most every ten seconds; `CosIdIdGenerator`
  does the same for a CosId failure. Configure CosId's clock regression policy to fail rather than wait.
- **A handler reads the security context from a `SecurityContext` parameter** (round 2 M4). The starter registers
  `SecurityContextArgumentResolver`. `SecurityContextHolder` is for blocking code on the request thread: a `suspend`
  handler that resumes on another request's thread read that request's context from it. With
  `io.micrometer:context-propagation` and `spring.reactor.context-propagation=auto`, Spring now restores the holder too;
  without them the holder is unbound in a `suspend` handler: `SecurityContextHolder.context` is `null` there, and
  `requiredContext` throws (`PIP-22`).
- **Applications only read the security context** (round 2 L5). `SecurityContextHolder.setContext` and `remove`,
  `ServletRequests.setSecurityContext`, `ServerWebExchanges.setSecurityContext`, and
  `ReactiveSecurityContexts.setSecurityContext` and `writeSecurityContext` are internal API: only the adapters bind the
  context. Code that bound a context of its own, which `AuthorizationChecker` already refused, no longer compiles.
- **The context path is removed segment by segment** (`PIP-20`). On Jetty, which writes the context path canonically
  while the URI keeps the client's form, `/%61pp/orders/1` with the context path `/app` was decided as `/app/orders/1`,
  past every statement on `/orders`; it is now `/orders/1`. A URI that does not start with the context path is `400`.
- **The Spring MVC filter runs at `-9999`**, right after Spring Boot's hidden-method filter (`-10000`), instead of
  `HIGHEST_PRECEDENCE + 100`. With `spring.mvc.hiddenmethod.filter.enabled`, a `POST` with `_method=DELETE` was
  authorized as a POST and executed as a DELETE; WebFlux already authorized the DELETE. A filter of yours that must run
  before CoSec, a CORS filter say, needs an order below `-9999`.
- **HEAD is decided as GET, and a method with a lower-case letter is a `400`** (`PIP-18`). Servers run the GET handler
  for HEAD, so a DENY on `http:GET` did not stop it: HEAD now takes a route map's HEAD route, or GET's, and unmapped it
  is `http:GET`; a statement on `http:HEAD` is refused (`DOC-8`, see above), since HEAD is decided as `http:GET` or as the
  action of the route map's HEAD route. A method such as `delete` was
  decided as `http:delete`, which a DENY on `http:DELETE` does not name, while backends that fold case served it as
  DELETE: it is now `400 invalid-request`. `AuthorizationChecker` and the control plane's simulate, explain and analyze
  read an `http` action the same way: `http:HEAD` as `http:GET`, and a lower-case verb is a deny, or `400`.
- **The client address is read once** (`PIP-16`). Where the server resolves forwarded headers itself
  (`server.forward-headers-strategy=native`, which Spring Boot also turns on by itself on Kubernetes and the other
  cloud platforms it detects), CoSec no longer reads `X-Forwarded-For` a second time: on Tomcat it takes the address the
  `RemoteIpValve` resolved, and on Reactor Netty and Jetty it reads the header and never the address the server
  forwarded. Before, on Tomcat, the two readings together took an entry the client wrote. Startup now fails under
  `framework`, with an explicit `cosec.authorization.remote-ip.max-trusted-index` on Tomcat under `native`, with
  `max-trusted-index: 0` on Reactor Netty or Jetty under `native`, and under `native` on a server CoSec cannot tell.
  Migration: the message names what to change ([Troubleshooting](./troubleshooting.md#startup-fails-on-the-client-address)).
  `XForwardedRemoteIpResolver` is now internal API.
- **Several `X-Forwarded-For` lines are one list, and an unreadable client address is unavailable** (`PIP-17`). A
  request with two header lines used the connection's peer, which let a client step around a block on its own address;
  the lines are now read in order. A trusted entry with a port, brackets or a zone is read as its address. One that is no
  address (`unknown`) made `environment.ip` absent, which a DENY read as no match; it is now unavailable: such a DENY is
  `503`, and the blacklist answers unavailable too.
- **A credential's tenant must be a tenant id** (`IDN-11`). A token whose `tenantId` is blank, `*`, holds a `:` or a
  control character, or is longer than 128 characters, or holds a `/`, is now `401 token-invalid` (it was `503`, or `500` for a blank
  one), and `JwtTokenConverter` does not issue it; an `ApiKey` with such a tenant cannot be built, so a stored one fails
  its lookup (`503`) and a configured one fails startup.
- **`SnapshotVersion` is removed** from `cosec-spi`: it named the two documents of 7.x. A decision event's
  `snapshotVersion` is a `ViewVersion` (`platform`, `app`, `tenant`), as before.
- **A rate limit window is at most a day** (`windowSeconds` up to `86400`), and `permitsPerSecond × windowSeconds`
  at most 2⁶³: a larger quota overflowed the limiters' arithmetic, and a longer window kept one Redis key per bucket
  value for two windows in a Redis that must not evict (round-2 review M3). Such a document no longer decodes;
  `RateLimit` refuses the same values. To migrate: express a daily or longer quota as a per-second rate over a day.
- **The Redis rate limiter keeps one key per bucket and counts by Redis's clock** (round-2 review L1). A bucket is now
  the hash `<cosec.key-prefix>:rate-limiter:<rule>:<bucket>`, not a key per window
  (`...:{<rule>:<bucket>}:<window>`), and the script takes the time from Redis, so instances whose clocks differ no
  longer count apart. Counters written by an earlier version are not read and expire on their own: a bucket starts
  from zero once, at the upgrade. `ReactiveRedisRateLimiter` no longer takes a `clock`.
- **A document holds at most 64 rate limit rules** (`DOC-6`). Every rule a permitted request matches costs one call
  to the limiter, so a document with more no longer compiles: a stored one is installed as failed (its requests are
  `503`), and the control plane refuses a change that would make one with `422`. To migrate: merge rules that differ
  only in what they count into one rule with a `key`.
- **A statement with several resource patterns means one statement per pattern** (`ALG-10`). When its condition reads
  a path variable and a request matches more than one of its patterns, the condition is now tested under each matching
  pattern's captures and the statement matches if any holds; before, only the first matching pattern's captures were
  read, so a DENY could miss a request one of its own patterns denied, and the order of the patterns changed the
  decision. A DENY written this way now denies at least what it did; an ALLOW may permit more. A rate limit rule keyed
  by a variable takes it from the first pattern under whose captures its condition holds. To check: search your
  documents for statements with several `resource` patterns whose `condition` reads `resource.var.*`.
- **A path with an empty segment, an encoded separator or a backslash is a `400`** (`PIP-14`). `//admin`, `/admin//x`,
  `/;x/admin`, `/admin%2Fx`, `/admin\x` and `/admin%5Cx` were decided as paths of their own, so a `DENY` under `/admin` did not match them, while a backend
  that merges slashes, decodes separators or reads `\` as `/` served them as `/admin/...`. A trailing `/` is unchanged: `/admin/` stays
  its own path. Requests that were served before and are refused now:
  - a `\` in a path, raw or encoded: no client is expected to send one, as browsers turn it into `/`;
  - a `/` encoded inside a path variable, such as `/files/a%2Fb.txt` or `/repos/group%2Fproject`: send the value in the
    query, or encode its `/` twice (`%252F`), which the pipeline decodes once, to the text `%2F`, and the application
    decodes again;
  - a URL whose parts were joined with a `/` each (`https://host/api/` + `/orders`): fix the client;
  - a path parameter alone in a segment before the last (`/;jsessionid=x/orders`): URL rewriting puts it at the end of
    the last segment (`/orders;jsessionid=x`), which is still accepted.

  There is no switch to accept them: a pattern cannot say what a backend will make of these paths, so deciding them
  would bring the bypass back.
- **A check refuses what enforcement refuses** (`PIP-15`). `AuthorizationChecker.check` and `decide` now read a path as
  the pipeline reads a request: one the pipeline answers `400` for is a deny, as is any resource over a request path's
  length bounds. Before, `/public/../admin` could be permitted by an `ALLOW` under `/public`.
- **Shadow windows count two more outcomes**: `BECAME_DENIED` and `OBLIGATIONS_CHANGED` were counted as `SAME`, and a
  publish must now acknowledge them. Finish or abandon open windows before a rolling upgrade: an instance of the
  previous version cannot read a report that holds them.
- **A social login's state is single use and bound to the browser that started it** (round-2 review M4). JustAuth only
  checked that a state existed: one state signed in any browser that opened a callback holding it, as often as it was
  opened within 3 minutes, which allowed login CSRF. `SocialAuthentication.authorizeUrl(provider)` becomes
  `authorize(provider)`, which answers a `SocialAuthorization` (`url`, `binding`); `SocialAuthenticationProvider` does
  the same. Set `authorization.setCookie(path)` on the browser before redirecting it, and pass the cookie's value to
  `JustAuthCredentials(provider, binding)`, which takes it as a second argument; `SocialCredentials` has a `binding`. The
  state is taken from Redis on the callback (`<s>:oauth:binding:<state>`, Redis 6.2 or later for `GETDEL`).
  `JustAuthProvider` takes a `SocialStateStore`. `ignore-check-state: true` on a registration fails startup.
- **Tokens and API keys declare their app** (batch R7b, RFC 0012 §3.5, `SCP-11`). A token's `aud` and an API key's `app`
  name the app the credential belongs to, and `ScopeResolver` compares them with the host's: a host with an app
  (`cosec.authorization.scope.app-id`, or a route's `cosec-app-id`) refuses a credential declaring another app, or none,
  with `403 scope-rejected`. Without a host app the credential's app applies, and without one either no app document is
  read. To migrate: set `TokenSubject.app` when you issue a token, one token per app. Set `app` on API keys
  (`ApiKey`, `cosec.api-key.keys[].app`). Stored keys move to format 3 with a required `app` field: format 2 fails
  (`API key format [2] is not 3.`), so re-provision them (`cosec-migrate api-keys --app <id>` sets the app on the keys it
  converts from 7.x). A token that operates the control plane is issued for `cosec.control.app-id` (default
  `cosec-control`); control-plane routes on a host whose own app differs answer `403`, so give them a host with no app,
  or that app.
- **`CoSec-App-Id` is gone.** A client no longer declares an app; the header is not read, and a request that sends it
  gets the credential's app. A gateway's forwarded identity still carries its app: a service behind it with an app
  refuses an identity naming another app, or none, with `400 invalid-request`. Read `SecurityContext.scope.appId` for the
  request's app.
- **`SecurityContext` is read-only**: `tenantId`, `principal`, a non-null `scope` and `forwardedIdentity`
  (`gatewayRequestId`, `credentialId`, which replace the attributes `cosec.identity.rid` and `cosec.identity.jti`), and
  `requestId` and `deviceId` (see above). The attribute map and its accessors are removed; `tenant.tenantId` becomes `tenantId`.
- **`CoSecPrincipal` is what an application reads of the request**: `id`, an explicit `authenticated`, `attributes`. The
  roles effective for the request are in `SecurityContext.scope.roles`; `principal.roles` and `principal.policies` are
  removed.
- **The token contracts are values.** `TokenVerifier.verify(accessToken: String)` and `refresh` return a `VerifiedToken`
  (`me.ahoo.cosec.spi.token`), with no type parameter; `TokenRevoker.revoke` takes the token string, or a `CompositeToken` (below). `TokenConverter`
  takes a `TokenSubject` (`me.ahoo.cosec.login.token`), and `Authentication<C>` answers one. Removed: the
  wrappers `SimplePrincipal`, `SimpleTenantPrincipal`, `TenantPrincipal`, `TokenPrincipal`, `TokenTenantPrincipal`,
  `SimpleTokenPrincipal`, `SimpleTokenTenantPrincipal`, `MembershipMarkedPrincipal`, `Delegated`, `findInDelegationChain`,
  `IssuedAtCapable`, `ExpiresAtCapable`, `MembershipMarkerCapable`, `AccessToken`, `RefreshToken`, `Token` and
  `TokenIdCapable`. Replace a custom `TokenVerifier` by building a `VerifiedToken`; its constructor refuses invalid claims,
  which `verify` lets through as the `TokenVerificationException` it already throws.
- **`RoleCapable`, `PolicyCapable`, `SpacedRoleId`, `RoleIdCapable`, `SpaceIdCapable`, `Tenant`, `TenantCapable`, `SimpleTenant`
  and `Named` are removed.** The claim names are constants of `MembershipClaims`, and the tenant ids `TenantId.DEFAULT`
  and `TenantId.PLATFORM`; `role@space` does not change. `SocialAuthenticationProvider` keeps its `name`.
- **A token whose subject begins with `(` is invalid** (`IDN-12`), as are tokens with no `sub` or `jti`: a token cannot
  name CoSec's own ids, the anonymous principal `(0)` above all.
- **`AttributeRequest` carries the `tenant`**, and `Subject.tenantId` is removed: a stage-A provider reads `request.tenant`.
- **`SwitchTenantCredentials` carries the `context`** (`SecurityContext`) of the request instead of a `principal`, and
  `switchTenant` receives it (`AbstractSwitchTenantAuthentication.switchTenant` is now the `TenantSwitch` hook of
  `SwitchTenantAuthentication`, see above). The new token keeps the previous token's app.
- **Login is suspending, and `cosec-login` no longer brings Reactor** (batch R7c). `Authentication.authenticate`,
  `TokenCompositeAuthentication.authenticateAsToken`, `AbstractSwitchTenantAuthentication.switchTenant` (now the
  `TenantSwitch` hook of `SwitchTenantAuthentication`, see above),
  `SocialAuthenticationProvider.authorizeUrl` and `authenticate`, `SocialAuthentication.authorizeUrl` and
  `SocialUserPrincipalConverter.convert` are `suspend` functions that return the value instead of a `Mono`. To migrate:
  make the override `suspend` and return the value; await a Reactor client with `awaitSingle()`; make the controller
  function `suspend`. A `Mono.empty()` that refused a login becomes an exception, since there is no empty answer. A module
  that compiled against Reactor through `cosec-login` declares it itself. `cosec-social` no longer brings Guava.
- **`DefaultAuthenticationProvider` and `SocialProviderManager` are classes**, and the starter assembles one of each per
  application context (#1222). Replace a call on the old singletons with the `AuthenticationProvider` or
  `SocialProviderManager` bean; `SocialAuthentication` takes the manager in its constructor.
- **`JustAuthProvider` runs on its own dispatcher**, at most 16 threads per provider, so social login no longer needs
  `Schedulers.boundedElastic()`.
- **Libraries export what their public classes implement** (`cocache`, `kafka`, `ip2region`, `micrometer`, `opentelemetry`):
  you may drop a dependency you added only to compile against them.

- **The public API is classified** (RFC 0010 C4). What an application needs stays public; everything else is
  `@InternalCoSecApi` (applications never opt in) or `internal`. Most of `cosec-engine`, the condition AST of
  `cosec-model`, `cosec-runtime`'s snapshot views, shadow recording and document model, the control plane's
  `ControlPlane`, grant and access analysis, and the starter's auto-configuration classes are internal now. If you
  compiled against one, use what the page you found it on documents instead: the checks and listeners are still there.
  In the test kit, only `PolicyTestKit`, `PipelineFixture`, `PermittedRequest`, `AttributeRequests`, `EngineOutcome` and
  their exceptions are public.
- **`PipelineComponents` is a plain class**, as is `SnapshotOptions`; both lose `copy` and the `componentN` functions.
  `PipelineComponents` no longer takes an `engine`. A host without Spring Boot is built with
  `Pipeline.builder(authenticator, store)` (`SuspendingSnapshotSource.of(store)`, which this bullet first named, is gone: see above).
- **`PolicyDocumentCompiler.compile` is internal.** A CI check calls `validate`; `forPlatform(platform)` is the
  overload for it.
- **A public `data class` is now a value that cannot grow** (RFC 0010 C4): `Identity`, `Identity.Credential`,
  `TokenSubject`, `SocialUser`, `ForwardedIdentity`, `Audit*Data`, `ShadowReport`, `ShadowSample`, `InstalledDocument`,
  `InstanceStatus` and the starter's `*Properties` lost `copy` and `componentN`. `Identity`, `Identity.Credential`,
  `TokenSubject` and the shadow records still compare by value; the others no longer define `equals`.
- **A refresh token is refused where an access token is expected, and an access token where a refresh token is**
  (`IDN-13`). `JwtTokenConverter` writes `cosec.token: "refresh"` on every refresh token; a token with that claim
  authenticates nothing, and a refresh reads only a token with it, whose `iat` is the access token's. Access tokens
  issued before this version keep working until they expire; their refresh tokens, which lack the claim, no longer
  refresh, so their users sign in again.
- **Revocation is one judgement, and the token contracts do no blocking I/O.** `TokenVerifier` is pure computation by
  contract. `RevocableTokenVerifier` is removed: the pipeline, refresh and logout all read revocations through
  `TokenRevocation` (`cosec-runtime`), over the `RevocationReader`. `TokenStore` keeps only its writes, `revoke` and
  `revokeSubject`, now `suspend`; its synchronous `isRevoked` and `subjectRevokedBefore` are gone (read them through
  `RevocationReader`), as is `TokenStore.NoOp`. `TokenRevoker.revoke` and `revokeSubject` are `suspend`: call them from a
  `suspend` handler instead of moving them off the event loop. `DefaultTokenRevoker` takes the `TokenRevocation`
  (it is now `StoreTokenRevoker` in `me.ahoo.cosec.login.token`, see above). `SimpleRefreshTokenAuthentication(tokenVerifier)`
  becomes `SimpleRefreshTokenAuthentication(tokenVerifier, tokenRevocation)` (now `RefreshTokenAuthentication`, see above)
  and no longer runs on a dispatcher of its own. The
  starter's `TokenVerifier` bean is the plain `JwtTokenVerifier`, and it adds a `TokenRevocation` bean. A `TokenStore` of
  your own implements the two writes as `suspend` functions, and its reads as a `RevocationReader` bean.
- **Refresh tokens belong to sessions** (RFC 0009, round-2 review M2). A login starts a session, a family of token pairs
  whose id, login time and generation both tokens carry (`sid`, `auth_time`, `cosec.generation`), kept in Redis under
  `<cosec.key-prefix>:token:family:<sid>` ([Sessions](../integration/authentication.md#sessions)). Only the current pair
  refreshes; an older one presented again ends the session; a session lasts `cosec.jwt.session.lifetime` (30 days) from
  its login; logout ends the whole session. What changes for an application:
  - **Every user signs in again once:** a pair issued before this version carries no session and is refused on
    refresh (`token-invalid`); its access token works until it expires.
  - **Issue tokens through `TokenCompositeAuthentication`**, which starts the session, or start one yourself with
    `TokenFamilies.start` and `TokenSubject.inSession`: a pair from `TokenConverter.toToken` alone never refreshes.
    `TokenCompositeAuthentication` and `SimpleRefreshTokenAuthentication` (now `RefreshTokenAuthentication`, see above)
    take a `TokenFamilies` (a starter bean).
  - **A client keeps only the newest pair:** presenting a pair after a newer one was issued ends the session, unless it
    happens within `cosec.jwt.session.rotation-leeway` (30 seconds) of that refresh: then it gets a pair of the newest
    generation, so a refresh whose response was lost can be retried.
  - **`TokenRevoker.revoke(compositeToken)`** logs out with an expired access token; `revoke(accessToken)` now ends the
    session too. A `TokenRevoker` of your own implements both.
  - **A tenant switch needs the session:** it was `SwitchTenantCredentials.accessToken`, and
    `AbstractSwitchTenantAuthentication` taking the `TokenVerifier`; it now issues from the session of the request
    (`SwitchTenantAuthentication`, above), and the new token keeps the login time.
  - **Without `cosec-cocache`, register a `TokenFamilyStore`**, or startup fails (`InMemoryTokenFamilyStore` for one
    instance). Redis holds one key per live session.
- **A revocation store that cannot be read is reported, never read as "not revoked"** (round-2 review H1, `IDN-14`).
  A `RevocationReader` throws `RevocationUnavailableException` when its store cannot be read; the revocation tables'
  `unknown-read` is locked to `throw`, so `cocache.caches.[RevokedTokenCache].unknown-read` fails startup. The pipeline
  still accepts a request's token then, as before; a refresh now fails instead of issuing a token, and the control plane
  publishes nothing on an approval whose approver's revocations cannot be read. A custom `RevocationReader` that
  answered "not revoked" on a failure throws `RevocationUnavailableException` instead. To refuse requests too, set
  `cosec.authentication.revocation.fail-closed: true`, which replaces `unknown-read: throw` on the revocation tables.
- **Instance statuses carry the document format level** (RFC 0010 C5): an instance reporting without `formatLevel` is
  refused by a control plane of this version, and a shadow window reading one fails until every instance has been
  upgraded (a control plane counts only the statuses written within the last minute, `ShadowReportReader.STATUS_TTL`; the key
  itself lives two).
- **The control plane refuses what used to fail at run time.** A platform change that turns a policy other documents
  refer to into a boundary, or a boundary they refer to into a policy, is `REFERENCES` (`422`, `CTL-30`). A binding that
  names a platform boundary is `REFERENCES` too: such a binding never granted, every request it applied to was `503`,
  and a document that holds one has to remove it before it publishes again. An id a change puts holds no `/` (`V2`,
  `422`): a rule ending in `*` did not reach such an entry. A permission policy's `perm:<app>/<id>` may; entries that
  already hold one can stay or be removed. An app change that removes a policy tenants bind, or one its short forms
  derive, is `REFERENCES` when it is checked: it used to reach `CHECKED` and fail only when it was published (`CTL-30`).
- **`ChangeEvent.Kind` loses `BOOTSTRAP` and `MIGRATE`.** No change event of those kinds was ever emitted: a bootstrap and a
  migration write their revisions through the store, and are recorded in the revision's `provenance.kind` (`BOOTSTRAP`,
  `MIGRATE`). A `when` over `ChangeEvent.Kind` that names them stops compiling; delete the branches, nothing was ever
  delivered to them.
- **A preflight is no longer seen by policies, rate limits or the audit when the application declares a
  `CorsConfigurationSource` bean** (`STA-5`, [CORS](../integration/web.md#cors)). The starter now registers a CORS
  filter before CoSec's on Spring MVC and WebFlux when the application declares the stack's `CorsConfigurationSource`
  and no `CorsFilter` or `CorsWebFilter` of its own: a preflight is answered by it from the source alone, so a statement
  on `http:OPTIONS`, a rate limit, the blacklist, the audit, a decision event or a metric never sees one; an origin the
  source does not allow gets a plain `403` before the pipeline, without a problem body or a `CoSec-Request-Id`; and a
  refusal now carries the CORS headers. `@CrossOrigin` and `addCorsMappings` no longer answer a preflight: put every
  origin, method and header in the bean. A statement written only to let a preflight through (`http:OPTIONS`) is not
  needed any more and can stay. An application without the bean, or with a filter of its own, is unchanged.
- **Removing a DENY from the platform or an app document needs a second person** (RFC 0007 §7.4.4, `CTL-31`). Until
  now, a platform principal without `cosec:approve` could publish alone a change that only removed entries. A removal
  that takes away a DENY, a policy that holds one or a binding whose policy holds one (also through a role removed or put
  with fewer permissions), now waits in `AWAITING_APPROVAL` with a `NOT_ANALYZED` escalation for each, as does the trust
  root's under `four-eyes` ([Approval](../policies/testing.md#approval)). Removing ALLOWs, a tenant's own DENYs and
  break-glass revokes are unchanged. Plan an approver for emergency removals of a platform DENY.
- **`cosec:grant` follows what a role derives** (RFC 0012 §7.2): a `putCatalog` or `putPolicy` that makes a `"*"` role
  derive a binding it did not needs `cosec:grant` on the role's scope, as a `putRole` does.
- **`cosec:breakglass` is enough for break-glass** (RFC 0007 §8): requesting one, publishing it once approved and
  revoking it no longer also need `cosec:publish`. No other change takes `cosec:breakglass` in its place.
- **A rollback carries `confirmations`**, as a proposal does, when it brings back a DENY with `onMissing: "nomatch"`.
  Until now such a rollback could not be confirmed at all.
- **`repair-references` also removes** what a publish indexed before a head write it lost, and prints
  `Removed <n> reference(s) no head refers to.`. [Repair the back-reference index](./upgrading.md#repair-the-back-reference-index).

- **The public API of `cosec-model`, `cosec-engine` and `cosec-spi` is classified for 8.0** (R9, RFC 0010 C4): what
  stays public is what 8.0 keeps stable. What an application notices:
  - `ApiKeyStore` is removed. Requests read keys through `ApiKeyReader`. Manage keys through the `InMemoryApiKeyStore`
    or the `RedisApiKeyStore` bean, whose methods do not change; inject the bean by its own type. The pipeline never read
    an `ApiKeyStore` bean of an application's own: to look keys up elsewhere, declare an `ApiKeyReader`.
  - The evaluation trace moved, and is experimental. `me.ahoo.cosec.engine.flat.FlatTrace` is
    `me.ahoo.cosec.engine.trace.EvaluationTrace`, with `CandidateTrace` and `CandidateOutcome` beside it. These types and
    `DecisionEvent.evaluationTrace` are `@ExperimentalCoSecApi` (opt in with `@OptIn(ExperimentalCoSecApi::class)`),
    and their constructors are internal. `ViewVersion` and `AttributeStatus` are in `me.ahoo.cosec.engine`, and stay
    stable. Audit records and their JSON do not change.
  - `RevocationUnavailableException` extends `CoSecException`, as `MembershipUnavailableException` does. A handler that
    answers every `CoSecException` (a `SecurityException`) with `401` now catches it too: handle it first, as the
    revocation store being unavailable.
  - `RateLimitResult` is no longer a `data class`: `copy` and `componentN` are gone, and equality is unchanged.
  - `SnapshotDocument.DOCUMENT_SCHEMA` is no longer a `const`, so compiled code reads the schema of the CoSec it runs
    with. A `const val` set from it no longer compiles: make it a `val`.
  - **A `SnapshotDocument` is made from its bytes** (`SNP-12`): its constructor is internal. Replace
    `SnapshotDocument(schema, revision, publishedAt, bytes)` with `SnapshotDocuments.read(bytes)` (`cosec-document`),
    which reads the header from the bytes and refuses bytes that are not a document. The stored bytes do not change.
  - Internal now (`@InternalCoSecApi`): `Environment`, the condition literals (`Literal`), `Attribute` and its `Kind`,
    `ScopeResolver`, `ScopeSubject`, `SubjectRevocation`, `IdentityFormatException`, `IdentityOverflowException` and
    `Identifiers.isUnpairedSurrogateAt`. The constructors of `ScopeResolution.Resolved` and `ScopeResolution.Rejected`
    are internal; `ScopeResolution.Rejected.Reason` stays.

- **The public API of `cosec-runtime` and the web adapters is classified for 8.0** (R9). With the starter nothing
  changes at runtime; in code:
  - Assembling a pipeline without Spring is experimental: `Pipeline.builder` and `PipelineBuilder`, `Pipeline.evaluate`,
    `RequestGate`, `HttpFacts`, `Headers`, `Cookies`, `Outcome`, `Problem`, `ProblemEncoder` and `RequestSecurity` are
    `@ExperimentalCoSecApi`: opt in with `@OptIn(ExperimentalCoSecApi::class)`. `PipelineOptions`, `SnapshotOptions`,
    `SuspendingSnapshotSource`, `AttributeProviders`, `DecisionListeners`, `IdentityIssuer`, `IdentityGate` and
    `CoSecParameters.read` are `@InternalCoSecApi` (no opt-in), and `Pipeline`'s constructor and `PipelineComponents` are
    `internal`; they were experimental first, and `Pipeline.builder` replaced them. A test that built a pipeline this way gets one from `PipelineFixture`
    (`cosec-testkit`). `Pipeline` itself, `AuthorizationChecker(pipeline)`, `ProblemType` and the `CoSecParameters`
    header names stay stable.
  - Internal now (`@InternalCoSecApi`): `IdentityCodec` (the `CoSec-Identity` format on
    [Web integration](../integration/web.md) is the contract), `RemoteIpResolver`, `PathNormalizer`, `HttpAttributes`,
    `PolicyDocument.attributes`, `PolicyDocumentCodec.encode`, `ATTRIBUTE_TYPES`, `WILDCARD` and `SCHEMA`,
    `PolicyDocumentCompiler`'s limits, `PERMISSION_PREFIX` and `permissionPrefix`, and the `AuthorizationChecker.check`
    and `decide` that take a `RequestSecurity` (those that take a `SecurityContext` and strings stay). So are the
    constructors of `PipelineWebFilter`, `PipelineServletFilter` and `PipelineGatewayFilter` (declare the starter's
    beans, never your own), `LoggingAuditEventSink(KLogger)` (the constructor without arguments stays) and
    `MetricsListener.bind(registry, listeners)`. The constructors of `RouteResolution.Mapped` and `Invalid` are
    internal: `RouteMap.resolve` answers them.
  - `InstanceStatus.FORMAT_LEVEL` is no longer a `const`.

- **The public API of `cosec-control` and `cosec-cocache` is classified for 8.0** (R9):
  - `ControlStore`, `InMemoryControlStore`, `CoCacheControlStore` and the records a store keeps (`ChangeRequest`,
    `ChangeRequestCodec`, `ChangeState`, `Base`, `Approval`, `CheckedApps`, `Finding`, `Severity`, `Operation`,
    `ShadowState`, `StageSlot`, `AppList`, `PendingRecord`, `RevisionEntry`, `RevisionIndex`, `StoredDocument`,
    `Versioned`, `HeadWrite`), with the shadow window ports and their reports (`CandidateStore`, `ShadowReportSink`,
    `ShadowReportReader`, `CoCacheShadowReports`, `InstanceStatus`, `InstalledDocument`, `ShadowReport`,
    `ShadowSample`, `StoredCandidate`, `ShadowOutcome`) and `ProvenanceKind`, are `@ExperimentalCoSecApi`: a store of your
    own opts in. `ChangeListener`, `ChangeEvent` and what it carries stay stable; `Escalation` and `Confirmation` are no
    longer `data class`es (no `copy`, no `componentN`; equality is unchanged).
  - The CoCache caches (`RevokedTokenCache`, `RevokedSubjectCache`, `MembershipCache`, `PolicySnapshotCache`) are
    `@InternalCoSecApi`, as are the constructors and factories of `cosec-cocache`'s classes that take CoCache or Spring
    Data types (`CoCacheTokenStore`, `CoCacheMembershipProvider`, `CoCacheTokenFamilyStore`, `RedisApiKeyReader`,
    `RedisApiKeyStore`, `ReactiveRedisRateLimiter`, `RedisSnapshotDocumentStore` and its digest helpers,
    `CoCacheControlStore.create`, `CoCacheShadowReports.create`) and the `DEFAULT_*_KEY_PREFIX` constants. Only
    `CoSecCacheSpecs`, internal already, could build those caches: the starter builds them, and an application declares
    its beans' types (`RedisApiKeyStore`, `CoCacheMembershipProvider`, ...) to use them. The cache names in
    `cocache.caches.[<CacheName>]` do not change.
- **`LocalRateLimiter` keeps every count that can still refuse a request, and a full one cannot count** (`OBL-5`,
  `OBL-6`). It dropped a bucket idle for an hour, so a rule with a longer window (up to a day) let a client back in
  after an hour's pause, and evicted buckets by size, so a flood of new keys on one rule reset the counts of every rule.
  Now a bucket is removed only once it has been idle for an hour and for two of its rule's windows, and never to make
  room. The limiter holds at most 100,000 buckets and 50,000 of one rule; a new bucket beyond either cannot be counted,
  so the rule's `strictFailure` decides: a strict rule answers `503` (`RateLimiterUnavailable`), a lenient one is
  skipped. Before, it was counted from zero. If a rule keyed on something a client can vary now answers `503`, key it on
  the principal or a trusted address instead ([The local limiter is
  bounded](../policies/rate-limiting.md#the-local-limiter-is-bounded)). `LocalRateLimiter`'s constructor takes a new second
  parameter, `maximumBucketsPerRule`, before `expireAfterAccess` and `clock`: calls by name are unchanged, positional
  ones change. `cosec-runtime` no longer brings Guava: an
  application that used it through CoSec declares it itself.

- **The public API of the starter, `cosec-login`, `cosec-social` and the web adapters is classified for 8.0** (R9):
  - **`cosec-login`'s `Credentials` is `LoginCredentials`, and `Authentication.supportCredentials` is
    `credentialsType`.** Two public `Credentials` in one product named different things: the pipeline's
    (`me.ahoo.cosec.spi.authn.Credentials`, a request's token or API key) keeps its name. An `Authentication` of your own
    changes like this:

    ```kotlin
    // before
    data class PasswordCredentials(val username: String, val password: String) : Credentials
    class PasswordAuthentication : Authentication<PasswordCredentials> {
        override val supportCredentials = PasswordCredentials::class.java
        // ...
    }
    // after
    data class PasswordCredentials(val username: String, val password: String) : LoginCredentials
    class PasswordAuthentication : Authentication<PasswordCredentials> {
        override val credentialsType = PasswordCredentials::class.java
        // ...
    }
    ```

  - `AbstractRefreshTokenAuthentication` is removed: `SimpleRefreshTokenAuthentication` (now `RefreshTokenAuthentication`,
    see above) implements `Authentication<RefreshTokenCredentials>` itself. `AuthenticationProvider`, `DefaultAuthenticationProvider` and
    `CompositeAuthentication`'s constructor are `@InternalCoSecApi`: the starter registers every `Authentication`
    bean.
  - `cosec-social`: `SocialProviderManager` and `SocialAuthenticationProvider` are `@InternalCoSecApi`, as are the
    constructors of `SocialAuthentication` and `JustAuthProvider` (which named JustAuth's `AuthRequest`).
    `JustAuthProvider.name()` is removed: read `name`.
  - The starter's `*Properties` classes and `EnabledCapable` are `@InternalCoSecApi`, and its `@ConditionalOn*`
    annotations are `internal`. The `cosec.*` keys are the contract and do not change. Read a value with
    `@Value("\${cosec.jwt.enabled}")` or the `Environment` instead of injecting a CoSec `*Properties` bean, and
    condition a bean of your own with `@ConditionalOnProperty("cosec.jwt.enabled")` instead of
    `@ConditionalOnJwtEnabled`.
  - `checkMono` (`cosec-webflux`) and `checkBlocking` (`cosec-webmvc`) are in Java classes named
    `AuthorizationCheckers`, no longer `PipelineWebFilterKt` and `PipelineServletFilterKt`; Kotlin callers do not
    notice. `SuspendingHandlerHolderInterceptor` is `@InternalCoSecApi`: the starter registers it.
- **`cosec-dependencies` no longer manages Guava's version**, and no CoSec module brings Guava. An application that
  takes Guava's version from CoSec's BOM declares it itself.

- **`ChangeState.SHADOWED` is removed.** No change was ever in that state: a closed shadow window is not a state, it
  is the change's `shadow` (`ShadowState.closed`). Code that names `ChangeState.SHADOWED` (a `when`, a filter on
  states, a test) stops compiling: delete the branch. A stored change request cannot carry it, and a `"state":"SHADOWED"`
  would not decode; no version wrote one. The states are `DRAFT`, `CHECKED`, `SHADOWING`, `AWAITING_APPROVAL`, `READY`,
  `CONFLICTED`, `PUBLISHED`, `REJECTED`, `ABANDONED` and `EXPIRED`. The change event kind `SHADOWED` ("the window
  closed") stays, with its log line.
- **A family of documents holds at most its bound plus a stated slack** (`SNP-14`). Under a burst of cold loads a family
  could hold about its `cosec.authorization.snapshot.max-apps` or `max-tenants` plus the 4,096 evictions the
  housekeeping thread had queued. It now holds at most twice the bound and never more than 4,096 above it, plus the
  documents being loaded at that moment; an install that finds its family over that evicts on the installing thread
  before it answers. Nothing needs configuring and no default changes; a memory budget made for the old worst case is
  safe. [The property](./configuration.md#snapshot) states the number.
- **`Retry-After` and `RateLimit-Reset` round up, and a report interval under a millisecond fails startup.** A `429`
  said `1` for a window with 1.75 s left, so a client that waited as told met the same window; both headers are now the
  remaining time in whole seconds, rounded up, at least `1` (`1 ms` and `1000 ms` are `1`, `1001 ms` is `2`).
  Nothing to do: clients wait up to a second longer, and `RateLimit-Remaining` and `-Limit` are unchanged. The starter
  now refuses `cosec.authorization.shadow.report-interval` below `1ms` (`cosec.authorization.shadow.report-interval is
  at least one millisecond, not …`), where it used to start a reporter loop that never suspended: set a value of `1ms`
  or more (the default is `10s`).
- **`cosec-migrate bootstrap` before this fix dropped the platform tenant's roles.** It rebuilt the platform tenant's
  document (`tenant:(platform)`) from its policies, bindings and rate limits only, so the revision of kind `BOOTSTRAP`
  it published had no `roles`. A migration that converted 7.x roles into that document and then ran `bootstrap` (with
  or without `--force`) lost them, and the members of those roles were denied afterwards: it fails closed, never
  open. It now keeps everything else the document holds. If you ran an earlier `bootstrap` after converting roles, check
  the document, with the control API as the administrator: find the revision whose `provenance.kind` is `BOOTSTRAP`
  (`GET /documents/tenant:(platform)/revisions`, then `GET …/revisions/<n>`), and read
  `GET /documents/tenant:(platform)/diff?from=<n-1>&to=<n>`: a non-empty `roles.removed` is what the bootstrap dropped.
  Put those roles back with a change (`putRole` operations with the definitions from revision `<n-1>`). A deployment
  that bootstrapped before converting roles, or whose platform tenant has no roles, has nothing to do.
- **`ControlStore` gains `stagedCandidate`, and `putCandidate` writes over a digest.** A candidate is written only over
  the one the writer read, so a late write by a change that was preempted cannot replace the candidate of the change
  that took the slot (#1235). `suspend fun stagedCandidate(key: SnapshotKey): Versioned<StoredCandidate>?` is new, and
  `putCandidate(key, expected: SnapshotDigest?, candidate: StoredCandidate): Boolean` takes the digest it expects
  (`null`: only if there is none) and answers `false` when the candidate changed in between. A `ControlStore` of your
  own implements both (`CoCacheControlStore` and `InMemoryControlStore` do); one whose write has an unknown outcome
  reads back instead of writing again. Nothing to do for the starter's Redis store.
- **The `cosec-migrate` command line is stricter and takes `--name=value`.** `--name value` and `--name=value` both
  work; a value that starts with `--` is a usage error (the next option, with its value forgotten, used to be taken as
  the value): write `--name=--value` for such a value. A flag given twice is `--flag is given twice.`, a flag given a
  value is `--flag takes no value.`, and a repeated or unknown option is a usage error. A
  script that relied on an option swallowing the next one fails now: fix its arguments.
- **A control API path parameter is percent-decoded once.** `GET /documents/tenant:acme%20west` used to read the key
  `tenant:acme%20west`, a document that is not the `tenant:acme west` the store and the bodies use; the path is now
  split into segments first and each parameter decoded once (`+` stays `+`, an encoded `/` is part of the value and
  never a second segment, `tenant%3Aacme` names `tenant:acme`, and the route's own words are matched as written). A
  client that percent-encoded ids twice, or sent an encoded id and relied on it reaching the control plane as written,
  names another document now: encode once.
- **An access review lists a break-glass binding.** `POST /analyze` counted a break-glass binding (its policy,
  `cosec:root`, is defined by no document) among the `opaque` ones. It is now an entry of `allows`, an allow of
  `cosec:root` from the engine's own statement, with `breakGlass: true`, `expiresAt` and `conditional: true`, since the
  review does not evaluate the window; `conditional` now also marks any binding that has a window. A client that
  parses the review sees fewer `opaque` and new `allows` of `cosec:root` in the platform tenant's review; a tool that
  treats every allow as permanent should read `conditional` and `expiresAt`.
- **A generated route map takes the server prefix per operation, and refuses what it cannot place.** The prefix comes
  from the operation's `servers`, else its path item's, else the document's, and a server URL is read by its components:
  `//example.test/v1`, `https://example.test:8443/v1` and `/v1` all give `/v1`. A document that generated a route map
  before may be refused now, naming the operation (`GET /orders: …`): servers of one operation with different paths, a
  server variable in the path, a server path that does not start with `/` (`v1`, `example.test:8080/v1`, `{baseUrl}/v1`:
  relative to where the document was fetched from, which the generator does not know) or a dot segment. Generate the
  route map again; give the refused document absolute server URLs or paths that start with `/`.
- **A stored document that does not compile is named, logged and counted.** `Cause` in `cosec-model` gains
  `DocumentInvalid(document, revision, error)`, so an application that matches `Cause` exhaustively with `when` stops
  compiling: add the branch. The decision is still `503` (the instance cannot decide); `SourceUnavailable` now means
  the store could not be read. The instance logs one `ERROR` line per revision, digest and inputs, and one `INFO` line
  when it compiles again; its health names how many documents do not compile and the first ten, with their revisions.
  Nothing to do beyond the `when`; alert on that log line where you alerted on silence.
- **A request never logs a dropped decision event.** When a BEST_EFFORT listener's queue is full or closed, the request
  only counts the drop; a reporter logs, off the request's thread, one `WARN` line per listener and interval
  (`Dropping decision events: the queue of listener [<name>] is full; <n> dropped since the last report.`, or `is
  closed`). A log alert that matched one line per dropped event now sees a count per interval.
- **`TraceContext` gains `traceState`.** The parent's W3C `tracestate`, opaque, `""` for none; the three-argument
  constructor stays. The `cosec.authorize` span inherits it, where it used to start from the default state. It reaches
  every `DecisionListener` in the event and is vendor data: do not log it. No audit record, Kafka record or metric tag
  carries it.
- **`cosec-testkit`'s signatures grow.** `PermittedRequest.withToken(token, verifier, revocations, headers)` takes the
  request's headers (default empty); a header that carries a credential (`Authorization`, `X-API-Key`,
  `CoSec-Identity`) is refused, and two different `CoSec-Device-Id` values fail the request as the pipeline does.
  `PipelineFixture.of` and `load` take `routes` and `listeners` (defaults: none), and `AttributeRequests` builds an
  `AttributeRequest` for a provider's tests. The kit's exception takes a cause, and refuses a field given twice in what
  it reads and two files that hold one document. Source compatible (defaults), binary incompatible: recompile your
  tests against this version.

## From 8.0.0-alpha.3 {#from-8-0-0-alpha-3}

8.0.0-alpha.3 is a preview with no compatibility promise. What it stored still reads, except its back-reference
sets, its approved changes and a document that spells a day of the week another way (below).

- **The audit record says what decided** (batch R7a, #1237). `authorization.source` is now
  `{ via, document, policy, statement }` ([Audit events](./observability.md#audit-events)): the old `type` recorded a
  tenant's role binding and a boundary as `GLOBAL_POLICY`. A boundary refusal, which had the source `NONE` and no
  `binding`, now has `via` `BOUNDARY`, the boundary as `policy`, and `binding` `boundary:<tenantId>`. Consumers of the
  audit log and of the Kafka topic change with it:

  | Before | Now |
  |---|---|
  | `source.type` `BLACKLIST` | `source.via` `BUILTIN` |
  | `source.type` `ROOT` | `source.via` `BINDING`, `source.policy` `cosec:root` (and `breakGlass: true` for a break-glass permit) |
  | `source.type` `ROLE_PERMISSION` | `source.via` `BINDING`, `source.document` `APP` |
  | `source.type` `PRINCIPAL_POLICY` | `source.via` `ATTACHMENT` |
  | `source.type` `GLOBAL_POLICY` | `source.via` `BINDING`, `source.document` `PLATFORM` or `TENANT` |
  | `source.policy.id` | `source.policy` |
  | `source.policy.statement` | `source.statement` |
  | `source.policy.type`, `source.role`, `principal.policies`, `request.device.userAgent` | gone: `type` only repeated `source.type`, the others were always empty |

  In code: `AuditSourceType`, `AuditPolicy`, `AuditRole`, `AuditPermission`, their data classes, `PolicyType` and
  `AuditReasonCode.REGEX_TIMEOUT` are gone, `AuditVia` and `AuditDocument` are new, and the attribute keys
  `AuditTrace.TRACE_ID_ATTRIBUTE_KEY` and `SPAN_ID_ATTRIBUTE_KEY`, which nothing read, are removed.
- **Control-plane scale** (batch E2b: #1214, #1217, #1218, #1219, and the stage window #1290 left open). An instance
  writes its status under its own key, `<s>:shadow:status:<instance>`, and is listed in `<s>:shadow:instances:all`, so a
  heartbeat writes one status however many instances there are; the old `<s>:shadow:status:instances` is not read
  and expires in two minutes. Until an instance runs this version it is not counted when a revision's propagation
  is read (read-only; publishing never waits for it): upgrade the data-plane instances together, and add
  `~<s>:shadow:instances:*` to the key patterns of an instance's Redis ACL ([Redis keys](./storage.md#redis-keys)).
  The decisions of one change are taken on one confirmation of the control documents, confirmed again at the end, so a
  large change no longer reads them twice per entry. In code: a `ControlStore` of your own returns the non-terminal
  change requests from `openChanges()` (a `List<ChangeRequest>`, each read once), and replaces `putCandidate(key,
  candidate)` with `stagedCandidate(key)` and `putCandidate(key, expected, candidate): Boolean`, a write over the
  candidate read ([Extending](../architecture/extending.md#control-store)). `CoCacheShadowReports` takes the list of
  instances as its fourth store. `SnapshotDigest.toString()` and the digests of entries are unchanged, only faster.
- **Shutdown waits for the Kafka audit** (batch R7a, #1250). `KafkaAuditListener` waits up to
  `cosec.observability.listener.drain-timeout` for the sends it still holds and the broker's answers, so the producer is
  closed after them. A shutdown can take that timeout twice, once for the listeners' queues and once here: check the
  pod's termination grace period.
- **The `authorization` cookie is read only on request** (batch R6, `PIP-13`). A request whose only credential is that
  cookie is now anonymous. A browser sends a cookie on its own, on a cross-site request too, so reading it by default
  made every deployment answer for CSRF. Send the token in the `Authorization` header, or set
  `cosec.pipeline.allow-cookie-token=true` and defend against CSRF yourself
  ([Authentication](../integration/authentication.md)). A gateway removes the cookie from what it forwards either way.
- **`CoSec-App-Id`, `CoSec-Space-Id` and `CoSec-Device-Id` are headers only** (batch R6, `SCP-4`). A query parameter of
  one of these names is no longer read: it sets nothing, and it no longer makes a request ambiguous. Clients that send
  them in the URL send headers instead ([App and space](../integration/web.md#app-and-space)).
- **An unknown `cosec.*` key always fails startup** (batch R6, `SNP-9`). `cosec.unknown-properties` and
  `UnknownPropertiesMode` are gone, and setting the property is now itself an unknown key: remove it. The message lists
  the keys and links [the table of removed and renamed keys](./upgrading.md#removed-and-renamed-properties) rather than naming a
  replacement for each.
- **A stored API key without `format` fails like any other wrong format** (batch R6): the message is
  `API key format [null] is not 2.`. Such a key was already refused; `cosec-migrate api-keys` still converts it.
- **Roles and short-form permissions** (RFC 0012). A catalog entry can define its permission with `action` and
  `resource`, and app and tenant documents can hold `roles` ([Role permissions](../policies/role-permissions.md#roles)).
  `schema` stays 2, and nothing already published changes meaning. A document with either is not readable by an older
  instance, whose strict reader refuses the unknown fields and fails it closed: **upgrade every data-plane instance
  before publishing such a document**. A change may no longer put a policy or binding id starting with `role:`; what
  already holds one stays readable. `GET /documents/{key}/diff` lists `roles`, and reports policies and bindings of the
  expanded document. `POST /analyze` no longer counts a tenant's binding of a readable app's permission as opaque. In
  code: `Operation` has `PutRole` and `RemoveRole`, and `DocumentDiff`, `CatalogPermission` and `PolicyDocument` gained
  fields.
- **Control-plane correctness** (audit batch E2a: #1205, #1206, #1207, #1210, #1226, #1227). A revision diff's
  `sections` compares the catalog, limits, attributes, boundary and rate limits by their content: a rule of the same id
  with another quota is a change. `POST /analyze` lists only the bindings that can apply in the tenant asked about, so a
  platform binding of another tenant's principal or role is no longer listed. An instance forgets a shadow window
  once its candidate is withdrawn and its last report is written. In code: a `ControlStore` of your own implements
  `withdrawCandidate(key, window)` (`putCandidate` takes a candidate, no longer `null`), `referrers` and
  `putReferrers` with each referrer's revision (`Map<SnapshotKey, Long>`), and keeps `RevisionIndex.reclaiming`;
  `releaseLease` must check and remove in one step ([Extending](../architecture/extending.md#control-store)).
  `ShadowRecorder.record` takes the window's document first, `forget` takes the samples its last report counted, and
  `ShadowReporter` takes the `CandidateStore` it reads ended windows from.
- **Upgrade the control-plane instances together, and rebuild the back-reference sets** (batch R4). A back-reference
  set (`<p>:control:refs:*`) reads only as an object of each referrer and its revision. The JSON array of document
  keys alpha.3 wrote is a format error: a publish that touches one fails, and `cosec-migrate repair-references` reads
  a set before it writes it, so it cannot get past one either. Stop the alpha.3 control-plane instances, delete those
  keys, run this version's `cosec-migrate repair-references`, which writes each set again from the heads with each
  referrer's revision, and only then start this version's control-plane instances. An alpha.3 control-plane instance
  cannot read a set written that way, nor a revision index that lists records left to delete, and fails closed on
  them: its publishes that touch one fail, and its reclamation of that document stops. Data-plane instances need no
  order: an alpha.3 instance only keeps writing the reports of ended shadow windows.
- **Stored approvals and confirmations have one shape** (batch R4). A stored approval always has `memberships`,
  `attributes`, `principalBindings`, `breakGlass`, `credentialId` and `issuedAt`, `null` where there is none, and one
  without them is a format error. Publish or abandon every open change that has an approval before you upgrade, or
  remove its record afterwards (`<p>:control:change:<id>`): while one is open, it answers `503` and every maintenance
  pass stops and reclaims nothing. A `deny-nomatch` confirmation's digest is always over
  `{statement, policyCondition}`, so one given before the upgrade for a policy without a condition no longer matches:
  its change is refused until the statement is confirmed again (`428`). A published document keeps its confirmation.
- **One spelling for a day of the week** (batch R5). `daysOfWeek` takes exactly `MON`, `TUE`, `WED`, `THU`, `FRI`, `SAT`
  and `SUN`, the names the schema and the [policy language](../policies/language.md) have always listed. `MONDAY`,
  `mon` and ` Fri ` no longer compile: a change that writes one is refused, and a stored document that holds one is
  installed as failed, so the requests that read it answer `503`. Publish such documents with the three-letter names
  before you upgrade. `cosec-migrate` now writes those names, and still reads any spelling 7.x accepted.
- **An empty `tenantId` claim makes the token invalid** (batch R5, `IDN-11`), where it used to mean the default tenant
  `(0)`: read as no tenant, it put the principal in the default tenant with the token's roles. The request is evaluated
  as anonymous, so a deny is `401 token-invalid`, and refreshing such a token fails. A token with no `tenantId` claim
  stays in the default tenant. The principal of a custom `TokenVerifier` is held to the same rule, and
  `JwtTokenConverter` throws `IllegalArgumentException` for a principal whose tenant id is empty: give it no tenant, or
  `SimpleTenant.DEFAULT`.
- **Rate limit counters have one form** (batch R5). Every rule counts under its document and its id: a platform rule
  under `platform/<id>` where it had its bare id, and app and tenant rules as before (`app:<appId>/<id>`,
  `tenant:<tenantId>/<id>`). A keyed bucket is `k:<value>`, no longer `g:<value>`. Rule ids in documents do not change.
  The Redis counters start again, so each rule's current window begins with its full quota, and while two versions
  serve they count apart. A `RateLimiter` of your own, and anything that reads `Cause.RateLimiterUnavailable.ruleId`
  or `Basis.RateLimited.ruleId`, sees the new names.

- **Three groups of properties are renamed.** An old key fails startup as unknown:

  | Old key | New key |
  |---|---|
  | `cosec.pipeline.gateway.max-age`, `cosec.pipeline.gateway.max-size` | `cosec.pipeline.identity.max-age`, `cosec.pipeline.identity.max-size` |
  | `cosec.audit.required.timeout` | `cosec.observability.listener.required-timeout` |
  | `cosec.authorization.cache.token.*` | `cosec.authentication.revocation.cache.*` |

- **Route maps are opt-in.** `cosec.authorization.routes.location` is unset by default, and a host without it loads no
  route map, so a WebFlux or Spring MVC service that has none needs no setting. A service that relies on
  `classpath:cosec-routes.json` being read by default fails startup with a message that says what to do: set the
  property to that location. A map with no routes no longer loads ([Route maps](../integration/web.md#route-maps)).
- **`cosec.jwt.enabled` defaults to `false` with `cosec.pipeline.mode=gateway`.** A service behind a gateway that issues
  tokens sets it to `true`; without it a `TokenConverter` is missing where one is injected. Nothing changes in `enforce` mode.
- **`cosec:tenant-admin` also holds `cosec:skip-shadow`, on its own tenant's document only** (`CTL-27`), which widens
  what a tenant administrator can do: it skips a window with a recorded reason, and a change that only narrows takes
  a busy slot on that document. Grant analysis and approval are unchanged. To keep the old behaviour, bind a policy of
  your own instead ([The control plane](../policies/testing.md#the-control-plane)).
- **`cosec-cocache` without a `CacheRuntime` fails startup**, naming `me.ahoo.cocache:cocache-spring-boot-starter`. That
  combination could not work: it ended in a misleading error or an instance that never became ready.
- **The `cosecServiceGenerator` endpoint reads what springdoc scanned**, and needs `springdoc-openapi-starter-webflux-api`
  or `-webmvc-api`. An `OpenAPI` bean alone no longer makes it generate; a scan that found no operation is an error
  rather than an empty document.
- **`AuthorizationChecker.decideAs` and `decide(security, app, action, resource)` are `@InternalCoSecApi`**: they are the
  control plane's, and an application never opts in. Check as the request's own principal with `check` and `decide`
  ([Checks in code](../integration/checks.md)).

## From 8.0.0-alpha.2 {#from-8-0-0-alpha-2}

8.0.0-alpha.2 is a preview with no compatibility promise. These changes since then, from the
[RC security review](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/security/8.0-rc-review.md), make the grant
analysis read the author's rights as enforcement does, so some changes that published alone now wait for approval
([Testing policies](../policies/testing.md#approval)). Nothing to migrate in the documents' content, but 8.0.0 stores its data through CoCache 8, which does not read what alpha.2 wrote through CoCache 6: move it with `cosec-migrate cocache8` of 8.0.0-alpha.4, the last tool that has it, every instance stopped ([Data written through CoCache 6](#data-written-through-cocache-6)). Those at the gateway and
the edge of tenant isolation (RC1-5, RC1-6, RC1-15 to RC1-18, RC1-48, RC1-49), from **Identity forwarding** on, change
what a gateway forwards and to which routes: read them before upgrading a gateway.

- **A store upgraded from 8.0.0-alpha.2 runs [`cosec-migrate repair-references`](./upgrading.md#repair-the-back-reference-index) once**, so that the list of app documents (`<s>:control:list:documents`) is complete ([Storage](./storage.md#publishing-through-the-control-plane)).
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
  [`cosec-migrate repair-references`](./upgrading.md#repair-the-back-reference-index) has run after the upgrade, every tenant grant
  over every app (a binding with app `*`, or an attachment, also one a condition narrows to apps the change does not
  name) needs an approval** (`UNREADABLE`), and the control plane logs a WARN that names the command, at most once a
  minute.
- **A platform policy cannot take a tenant's id** (RC1-9, `CTL-21`). A platform change that adds a policy whose id a
  tenant document defines or binds is refused (`V2`, `422`, naming the tenant documents). A tenant that defines the
  id itself no longer blocks the platform from removing its own policy of that id. The back-reference index learns
  only the ids a tenant adds after the upgrade; the ones its document already had enter it only through
  `repair-references`: **once every control-plane instance and every `cosec-migrate` binary runs this version, run
  [`cosec-migrate repair-references`](./upgrading.md#repair-the-back-reference-index) once**, as a Kubernetes Job, with the Redis
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
  `AWAITING_APPROVAL` at publish, and is approved again; a credential without an id cannot approve (`403`). The API's
  view of a change (`GET /changes/{id}` and the answers of its steps) no longer shows the approval's `memberships`,
  `attributes`, `credentialId` or `issuedAt`. In code, `AuthenticatedPrincipal` has `issuedAt` and `apiKey`.
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
  ([Root becomes break-glass](./upgrading.md#root-becomes-break-glass)); `BreakGlass.request` derives the binding's id and has no
  `binding` parameter; approving break-glass needs a REQUIRED change listener (`503` otherwise, `BRK-3`).
- **Revoking break-glass answers by its effect** (R4-1). `DELETE /break-glass/{bindingId}` answers `200` only once
  the binding no longer grants on the platform document's head, whoever removed it. A revoke that leaves it granting
  is now `409`, its `detail` naming the state of the caller's change (`CONFLICTED`, ...), where it was `200` with that
  change before: a client that read `200` as "revoked" without looking at `state` must handle the `409`. A binding id
  the platform document does not have is now `404`, and a binding of that id that is not a break-glass binding, live or
  expired, `409` (`binding [<id>] is not a break-glass binding`); neither proposes anything, where both answered `200`
  with a `DRAFT` change (`BRK-11`). A revoke preempted by another revoke, or meeting a moved head, revokes again, at most
  three more times.
- **Abandoning** (RC1-51, `BRK-10`). Only its author abandons a revoke of a break-glass binding that still grants:
  anyone else, the principal it revokes included, is `403`. Revoke the binding again instead, which publishes the
  stalled revoke or takes its slot. Every abandon is recorded as a new change event, `ABANDONED`; abandoning a
  break-glass change needs a REQUIRED change listener (`503` otherwise). A change listener that handles every
  `ChangeEvent.Kind` gains `ABANDONED`.
- **Identity forwarding is opt-in by scheme** (RC1-49, `IDN-7`). The gateway forwards `CoSec-Identity` only to a
  route to a registered service (`uri: lb://...`, `lb:ws://...`) or one whose metadata says `cosec-identity: true`;
  `false` still keeps it from any route, and any value but `true` reads as `false`. **A route to one of your services by
  `http://` or `https://` stops receiving the identity, and the service behind it sees anonymous requests**: before
  upgrading the gateway, add `cosec-identity: true` to the metadata of those routes, and of no route to a third party
  ([Forwarding the identity](../integration/web.md#forwarding-the-identity)). The request attribute
  `cosec.identity.forward` is now `true` or `false` on every routed request, where it was set only to `false`.
- **No credential goes downstream** (RC1-5, `IDN-8`). Besides `Authorization`, the gateway removes the API key header
  (`cosec.pipeline.api-key-header`, under whatever name it gives) and the `authorization` query parameter from every
  request it forwards, whether or not the gateway itself reads a token from the query (only with
  `cosec.pipeline.allow-query-token=true`, which is off by default). A service that reads them itself needs `cosec.gateway.identity.forward-authorization=true`;
  with it, they now go only to the routes that would receive the identity (`lb://`, or `cosec-identity: true`), so a
  route to a third party gets none.
- **The `authorization` cookie stays at the gateway too** (`IDN-8`). Under the same switch, the gateway removes it from
  each `Cookie` header it forwards, its name matched ignoring case, and keeps the other cookies; a header left with no
  cookie is not sent. A service that reads its token from that cookie needs `forward-authorization=true` and a route
  that would receive the identity.
- **A route names its app** (RC1-6, `SCP-2`). Route metadata `cosec-app-id` is the app of the route's requests, ahead
  of `cosec.authorization.scope.app-id`: on such a route a request that declares another app is `403`
  `scope-rejected` (`401` for an anonymous caller), and one that declares none gets the route's app, its role
  bindings and its DENYs. A route without it keeps the gateway's host app, or the client's. On a gateway that fronts
  several apps, give each route its app. A `cosec-app-id` that is not a string, or cannot be an app id, fails every
  request of its route: quote a numeric app id.
- **Actuator on a gateway** (RC1-48, `STA-2`). A gateway host that decides with policies no longer starts while an
  actuator endpoint other than `health` is exposed over HTTP on its application port (`management.server.port` unset,
  or the same as `server.port`): CoSec authorizes the gateway's routes, not those endpoints. Serve actuator on its own
  port behind a network policy, expose only `health`, or list in the new property
  `cosec.gateway.unprotected-endpoints` the endpoints something else protects; it never accepts `gateway`
  ([Actuator on a gateway](../integration/web.md#actuator-on-a-gateway)). `cosec-gateway-server` and `k8s/` expose only
  `health`.
- **A blank `CoSec-*` value beside another is ambiguous** (RC1-16, `SCP-4`): `?CoSec-Space-Id=` with the header
  `CoSec-Space-Id: s1` is now `400` `invalid-request`, where `s1` applied. Blank means ASCII whitespace only, so a value
  of Unicode spaces, such as `%E3%80%80`, is a value: alone, it is a space the principal must have a role in. Send each
  parameter once.
- **App ids refuse control characters** (RC1-15, `SCP-10`), as tenant ids do: a declared app with one is
  `403` `scope-rejected` (`401` for an anonymous caller), and a host app with one fails startup.
- **Ids refuse unpaired UTF-16 surrogates** (RC1-52, `IDN-10`). A JSON token or body can write one as an escape
  (`"beta\uD800"`), and a Redis key holds `?` in its place, so tenant `beta\uD800` read the documents of tenant
  `beta?`, and a principal's memberships and revocations, and a rate limit bucket, were shared the same way. Now a
  token that claims one in its `sub`, `tenantId`, `jti`, `roles` or `policies` is `401` `token-invalid`, and CoSec's
  own issuer refuses to sign one (login fails); a `CoSec-Identity` that names one is `400`; a control request that
  holds one anywhere in its path, query or body is `400`; an authenticator, an API key record or a membership
  provider that gives one fails the request with `503`; an app or tenant id with one is refused as one with a control
  character is. A rate limit `key` value with one counts in a bucket of its own (`e:` and the value, escaped). Ids
  with surrogate pairs, such as an emoji, are unchanged. Log lines and messages write such a surrogate as `\uXXXX`,
  never `?`. In code, `Identifiers` (`cosec-model`) holds the rule; `ApiKey` and `AuthenticatedPrincipal` refuse such
  ids when made, and `RevokedTokenCache.key`, `RevokedSubjectCache.key` and `MembershipCache.key` throw for them.
  Nothing to migrate; where a token issuer or a script could have sent such ids, what is stored under the id with
  `?` may have been written for the other.
- **The control plane takes only app ids** (`SCP-10`). An app id is not blank, not `*`, has no `:` and no control
  character, and has at most 128 characters; earlier builds took any app without `:`. A binding in any other app is
  now a `V2` finding (`400`) at `$.bindings[i].app`, wherever it is in the candidate. `analyze` answers `400` for such
  an app. **Before upgrading the control plane**, remove the tenant bindings in such apps, abandon the open changes of
  such app documents, and empty the app documents published under such ids (publish each with no policies and no
  bindings), which drops their back-references. Nothing can be done about them afterwards, and what is left behind is
  a format error that fails closed, never skipped: a document that keeps such a binding still reads, and the binding
  grants nothing, but neither a publish nor `repair-references` gets past it; an open change of such an app answers
  `503` and stops every maintenance pass until its record is removed; an app list entry makes every app a grant reaches
  unknown until you delete `<p>:control:apps:all` and run `cosec-migrate repair-references`; a back-reference naming
  such an app fails whatever reads that set until you delete the set and run `repair-references`; and an instance's
  status entry fails the read of the instance statuses until it expires, two minutes after that instance last wrote it.
- **Enforcing hosts drop a client's `CoSec-Identity`** (RC1-18, `IDN-9`). A WebFlux or Spring MVC host in `enforce`
  mode removes the header from the request its handler sees, so it never relays one a client wrote. Code that read it
  there read what any client could write; read the `SecurityContext`.
- **A tenant's grant of an app's permission to `Anyone` or a principal resolves** (RC1-17, `TNT-4`). A request in that
  app now reads the app's document whatever the principal's roles, so the binding's DENY or ALLOW applies, where
  every such request was `503`, and so do the app document's rate limit rules. `simulate`, `explain` and shadow
  windows read it by the same rule (`CTL-3`). In code, `CompiledDocument.tenant` takes the set `boundApps`, and
  `CompiledDocument.needsApp` answers it; a `ShadowListener` built by hand needs the installed views (`views`) to
  compare a tenant candidate that makes a request read an app document, and reports `CANDIDATE_ERROR` without them.
- **One key prefix** (RC1-46, `SNP-9`). [`cosec.key-prefix`](./configuration.md#key-prefix) replaces
  `cosec.authorization.snapshot.key-prefix`, `cosec.authorization.cache.key-prefix` and `cosec.api-key.key-prefix`.
  An environment that still sets one of them fails startup, as any key CoSec does not read does. Every key lives under it: API keys under `<p>:api-key`, rate
  limit counters under `<p>:rate-limiter` and the social login's OAuth states under `<p>:oauth:state`, which were fixed
  ([Redis keys](./storage.md#redis-keys)). With the defaults every key stays where it was. A deployment that set one
  of the three sets `cosec.key-prefix` to its snapshot prefix instead; **revocations and API keys kept under another
  prefix are no longer read**, so a token revoked there would be accepted again: before switching, copy each such key
  under the new prefix (`COPY`, or `RENAME`, which keeps its expiry). Memberships fill again on their own. With `<p>`
  the value of `cosec.key-prefix`, the families move:
  - snapshot documents, control-plane records, candidates and shadow reports: from the old
    `cosec.authorization.snapshot.key-prefix` (default `cosec`) to `<p>`;
  - token revocations and memberships: from the old `cosec.authorization.cache.key-prefix` (default `cosec`) to `<p>`,
    except the memberships of a membership cache the application made itself, which stay under its own prefix;
  - API keys: from the old `cosec.api-key.key-prefix` (default `cosec:api-key`) to `<p>:api-key`;
  - rate limit counters and the social login's OAuth states: from `cosec` to `<p>`.
  `cosec-migrate api-keys` no longer takes `--api-key-prefix`: it converts the keys under `<--key-prefix>:api-key`.
- **`revalidate-interval` cannot turn the periodic pass off** (RC1-50, `SNP-6`). The pass is the only bound on a
  change whose invalidation message was lost, so `cosec.authorization.snapshot.revalidate-interval` must be at least
  `1s` (each pass reads every installed document from Redis), at most `5m`, less than `lag-warn` and, with `fail-closed.tenants`, less than `fail-closed.max-lag`; anything else
  fails startup naming the property, and `SnapshotOptions` refuses the same. Replace a `0` with an interval within the
  bounds; a longer one lowers what revalidation costs Redis ([Storage](./storage.md#what-revalidation-costs-redis)).
- **Path patterns have cost bounds** (RC1-8, `DOC-4`). An `http:path:` pattern is at most 2,048 characters, a segment
  that mixes `*`, `?` or `{name}` with other text holds at most 16 parts (consecutive `*` count once), a document's
  such segments hold at most 2,048 glob units in all (their parts and the characters of their text, twice after a
  leading `**` and for a rate limit rule keyed by a captured variable), and a typed pattern is at most 2,058 characters
  ([Policy language](../policies/language.md#path-pattern-bounds)). The control plane answers a change over them with
  a `COMPILE` finding (`422`) at the pattern or at `$`. A document published before with such a pattern no longer
  compiles on an upgraded instance, and every request that reads it fails closed: rewrite it and publish before
  upgrading the data plane.
- **A policy is evaluated once per pass for each document that binds it** (`ALG-9`), however many of the document's
  bindings apply, and a policy claimed twice among the attachments is attached once. The decision and the statement
  and binding it reports do not change: the first binding in candidate order reports. A trace lists the later bindings
  with the first one's outcomes, without evaluating them again.
- **A repeated header that a document reads is `400`** (RC1-38, `PIP-12`). When a request sends a header with
  different values, and any statement, policy condition, boundary or rate limit key of the documents its view reads
  reads that header (`Origin` and `Referer` included), the pipeline answers `400` `invalid-request` before evaluating,
  where a DENY on it made the request `503` and an ALLOW on it did not match. Identical repeats are one value
  ([Web](../integration/web.md#responses-and-status-codes)).
- **A malformed `roles`, `policies` or `attributes` claim is an invalid token** (`PIP-6`): `401` `token-invalid`, evaluated
  as anonymous, where it was a `500`.
- **Duplicate keys are refused everywhere** (RC1-44, `DOC-5`). Every stored format (API keys, memberships, shadow
  reports, revocations) and every control-plane request body with a duplicate key fails to read: a stored value is
  `503`, a request body `400`. `cosec-migrate` refuses 7.x exports and files with duplicate keys too.
- **CoSec's caches keep their pins whoever builds them** (RC1-45, `SNP-3`). `createPolicySnapshotCache` applies the
  store-of-record policy, the snapshot's codec and no key filter, per-entry TTL or loader to any spec, and a
  `CacheSpecCustomizer` no longer changes a CoSec cache's codec, loaders, key filter or per-entry TTL.
- **A compilation that overflows the stack fails the document closed** (RC1-43, `SNP-8`), as any compile error does,
  instead of keeping the revision before it.
- **Rate limits count on a Redis Cluster** (#1229, `OBL-4`). The Redis limiter's counters move to
  `<p>:rate-limiter:{<rule>:<bucket>}:<window>`: the hash tag keeps the two windows of a bucket, which its script reads
  together, on one slot. On a cluster every call failed with `CROSSSLOT`, so a rule without `strictFailure` was skipped
  and limited nothing, and one with it made every permitted request `503`. On a single Redis only the keys change.
  **During a rolling upgrade, instances of each version count in their own keys**: while instances of both versions
  serve, and for up to two windows after the last old one stops, a rule lets through up to twice its limit. The old
  keys expire after two windows. A `cosec.key-prefix` whose first `{` is immediately followed by `}` (an empty hash
  tag) now fails startup.
- **A boundary's external attributes are fetched** (#1208, `ATR-8`). The attribute plan holds the attributes of the
  boundary that holds the request, its statements' and its policy condition's, so a stage-B provider is asked for
  them. A request such a boundary allows was `503` (`BoundaryUnavailable`) and now proceeds; providers may be asked for
  more attributes per request.
- **A check fetches the attributes it needs** (#1209, `ATR-8`). `AuthorizationChecker` asks the providers for every
  attribute its action and resource read, a stage-A provider included (without the scope, as stage A asks), where a
  stage-A attribute was unavailable (`AttributeUnavailable`) unless some stage-B provider was registered. A stage-A
  provider is now called by each check that needs one of its attributes.
- **Providers are asked for fewer attributes** (#1211, `ATR-8`). The attribute plan walks the statements the
  evaluation visits, those the statement index gives, instead of every statement whose action matches: a statement
  whose resource path cannot match the request plans nothing, nor does its policy condition. A stage-B provider is no
  longer called for a request that no statement reading its attribute can match. What a request is decided on does
  not change.
- **One revalidation pass at a time** (#1215, `SNP-6`). A periodic pass that outlasts `revalidate-interval` is no
  longer joined by another: the next starts as soon as it ends, so a slow Redis sees at most four revalidation reads
  from an instance at once. A change whose invalidation was lost is installed by the first pass that reaches its
  document, which can then take longer than one interval ([Storage](./storage.md#how-instances-read-documents)).
- **Identity claims of every standard number type** (#1228). A gateway forwards a claim that is a `Byte`, a `Short`, a
  `BigInteger` within a `Long`, a `Float`, or a `BigDecimal` that a `Double` holds exactly, where the request was
  `500`. A number the header cannot hold exactly is still refused with `500`, and so are `NaN` and the infinities,
  which JSON has no number for.
- **`simulate` and `explain` are decided and authorized on the tenant that decides the request** (RC1-40,
  `CTL-23`). A request without a `principal` is decided on `(0)`'s document, as enforcement decides it: it needs
  `cosec:simulate` or `cosec:explain` on `(0)`, where alpha.2 checked the tenant the request named, and one that names
  another tenant is `400`. Simulate an anonymous request with `"tenant": "(0)"`, by a principal that may simulate there.
- **`simulate` and `explain` refuse what enforcement refuses** (`CTL-3`, `PIP-12`): a request that sends a header its
  documents read with different values is `400`, where it was decided (Indeterminate for a DENY on it), and a
  `http` resource goes through the path normalization a request's path does: a path enforcement refuses (a dot segment,
  a malformed escape, over 2,048 characters or 256 per segment) is `400`, and path parameters are removed before
  deciding, where the path was decided as written; a typed id over those lengths is `400`. `analyze` takes its resource
  the same way.
- **The control API's `400` says less, and a stored value that does not decode is `500`** (RC1-39). A `400`'s `detail`
  is one of the control plane's own checks, or names the field that does not parse; it no longer carries a parser's or
  an exception's message (`No enum constant ...`, `For input string ...`, a JSON parser's), and a time that does not
  parse (`expiresAt`, `time`) is `400` where it was `500`. A stored document or record that does not decode is `500`
  without detail, where it was a `400` with the stored document's JSON path; the control host logs it with its key. A
  client that matched on `detail` text should match on the status and `code`.
- **A lone surrogate half in a path pattern does not compile** (review L4 of #1200): half of a pair, without the
  other, in the text of an `http:path:` pattern is a `COMPILE` finding, where it compiled and could match the middle of
  a pair, which Spring never matches. A document published before with one fails closed on an upgraded instance.
- **CoSec's Redis templates read from the primary** (RC1-47): a `LettuceConnectionFactory` that reads from replicas,
  by `spring.data.redis.lettuce.read-from` or a client customizer, fails startup
  ([Redis requirements](./storage.md#redis-requirements)). And a new health indicator, `cosecSnapshotEviction`, is
  `DOWN` while Redis may evict the snapshot, read every `revalidate-interval`; a policy it cannot read is `DOWN` under
  `eviction-check: fail`, and `UP` with `eviction: unknown` under `warn`. It is an alert: `cosecSnapshot` does not
  change, and only it belongs in the readiness group.
- **During a rolling upgrade**, an app document an older instance creates is not in the app list; newer instances
  find it when they start and at each reclamation pass (`cosec.control.revisions.reclaim-interval`, 1 hour by default),
  and until then may analyze a grant without that app's DENYs. Create no app through an older instance while you roll,
  or lower `reclaim-interval` for the duration. An older instance also still takes a tenant principal holding
  `cosec:approve` for the trust root, and accepts an API key's approval: approve and publish through upgraded
  instances only until the roll is done. An approval an older instance accepted names no credential, so an upgraded
  instance does not publish on it.
- **Public API nothing used is gone.** `KafkaAuditEventSink`: `KafkaAuditListener` is what the starter registers,
  and it writes the same key and value. `SimpleSecurityContext` and its extension `CoSecPrincipal.tenant`: the pipeline
  makes the request's `SecurityContext`, and a test mocks or implements the interface. Of the engine's request,
  `AuthzRequest.scope`, the class `me.ahoo.cosec.model.Scope`, and `Subject.roles`, `policies` and `root`, none of
  which the engine read: code that builds an `AuthzRequest` or a `Subject` drops those arguments. The engine's tenant,
  roles and attachments are in `EngineRequest.scope` and `EngineRequest.memberships`. `Subject.tenantId` stays, for a
  stage-A `AttributeProvider`, which runs before there is a scope.

### Data written through CoCache 6

8.0.0-alpha.2 kept its data in Redis through CoCache 6; 8.0.0 keeps it through CoCache 8, under the same keys, and
CoCache 8 does not read CoCache 6's format. What it finds in that format fails closed, as unreadable content: a request
that needs such a document is `503` (`SourceUnavailable`), a token or principal revoked there is `503` instead of
`401`, and the control API answers `500`. Nothing reads such a value as absent, so a revoked token is never accepted
again.

8.0.0's `cosec-migrate` no longer has the command that moves it: follow the steps below with the `cosec-migrate` of
8.0.0-alpha.4, before upgrading to 8.0.0 ([From 8.0.0-alpha.4](#from-8-0-0-alpha-4)).

| Data | Keys | On 8.0.0, before the move | `cosec-migrate cocache8` |
|---|---|---|---|
| Snapshot documents | `<p>:snapshot:*` | `503` for every request that reads one | Published again as revision 1 of each document (provenance `MIGRATE`, no parent: the parent's SHA-1 digest is none CoCache 8 knows) |
| Revocations | `<p>:token:revoked:*`, `<p>:token:subject:revoked:*` | `503` for the revoked token or principal | Written again, each with what remains of its lifetime |
| Control-plane records | `<p>:control:*` | The control API answers `500` | Deleted: the history starts again with the published documents |
| Candidates and shadow reports | `<p>:candidate:*`, `<p>:shadow:*` | The shadow window cannot report | Deleted; a change still in flight refuses the run (below) |
| Memberships | `<p>:membership:*` | Loaded again from the `MembershipLoader` | Deleted; they fill again |
| API keys, rate limit counters, OAuth states | `<p>:api-key:*`, `<p>:rate-limiter:*`, `<p>:oauth:state:*` | Unchanged: kept by Spring's Redis template, not CoCache | Nothing |

1. Publish or abandon every change in flight, and end every break-glass grant: the run refuses an open change (a
   draft included), a staging slot or a candidate unless told `--discard-pending`, and refuses a document that holds a
   break-glass binding, as `publish` does.
2. If alpha.2 set one of the key prefixes that `cosec.key-prefix` replaced, move those keys first ("One key prefix",
   above): the command reads one prefix.
3. Stop every CoSec instance, the control plane included. An alpha.2 instance and an 8.0.0 instance read neither each
   other's values nor each other's invalidations, whose channels are named after the key prefix in CoCache 8.
4. See what would move, writing nothing:
   `cosec-migrate cocache8 --redis redis://localhost:6379 --key-prefix cosec --out ./cocache8 --dry-run`
   (`--cluster` with the seed nodes of a Redis Cluster). It lists what it refuses and why: a change still open, a
   draft included, a document `publish` would refuse, a value it cannot read, or neither a document to move nor a
   platform document.
5. Run it without `--dry-run`. It writes the documents to `--out` first, in the layout `publish` reads, and a marker,
   `<p>:meta:cocache8`, then the revocations (a write over the CoCache 6 value, so none is ever missing), then deletes
   the other CoCache 6 values, then publishes the documents, and removes the marker. If it stops after the deletions,
   do not run `cocache8` again to finish: it cannot see the documents it deleted, and it refuses while the marker is
   there. Finish with the command it prints,
   `cosec-migrate publish --to-redis <uri> --key-prefix <p> [--cluster] --in ./cocache8 --resume`, which removes the
   marker once every document is published again: alpha.4's `publish`, since 8.0.0's refuses while the marker is
   there.
6. Start the 8.0.0 instances. Each document's revision starts again at 1; the history before the move is not carried
   over, so keep a copy of `<p>:control:revision:*` before step 5 if you need it.

Digests are SHA-256 now, where alpha.2's were CoCache 6's SHA-1: the control API's revision history, a change's base
and checks, and the events that name a digest write `SHA-256:<64 hex>`. A `SnapshotDocumentStore` of your own must
produce 32-byte SHA-256 digests, or every read fails closed ([Extending](../architecture/extending.md#snapshot-document-stores)).

## From 8.0.0-alpha.1 {#from-8-0-0-alpha-1}

8.0.0-alpha.1 is a preview with no compatibility promise. These changes since then, from the
[RC security review](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/security/8.0-rc-review.md), break decisions,
code or configuration written against it. A 7.x deployment reads them in the sections below.

- **The `cosec.gateway.*` variables Kubernetes sets from a Service** (`COSEC_GATEWAY_SERVICE_HOST` and the like) were refused at startup by 8.0.0-alpha.1 as `cosec.*` keys CoSec does not read. Since alpha.2 the check skips exactly these variables; `enableServiceLinks: false` in the manifests keeps them out anyway ([Deployment](./deployment.md#what-the-manifests-set)).
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
