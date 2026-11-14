---
title: "Upgrading"
description: "How to upgrade a CoSec 7.x deployment to 8.0: what changes, and how to migrate the data, the code, the configuration and the clients."
---

# Upgrading

For teams moving a CoSec 7.x deployment to 8.0. The page starts with a checklist, then gives the details by area:
the data migration, what 8.0 decides differently, responses, authentication, storage, APIs, properties and
observability. This page is the single home of migration steps. Release notes for every version are on
[GitHub Releases](https://github.com/Ahoo-Wang/CoSec/releases).

## From 8.0.0-alpha.4 {#from-8-0-0-alpha-4}

8.0.0-alpha.4 is a preview with no compatibility promise. **Every token issued before this version declares no app, so a
service with an app refuses it** (below): plan a re-login, or have tokens expire first. API keys stored in format 2 fail
and have to be provisioned again.

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
  without them the holder is unbound in a `suspend` handler, so reading it there throws (`PIP-22`).
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
  is `http:GET`; a statement on `http:HEAD` matches nothing unless a route map maps HEAD. A method such as `delete` was
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
  control character, or is longer than 128 characters is now `401 token-invalid` (it was `503`, or `500` for a blank
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
  (`gatewayRequestId`, `credentialId`, which replace the attributes `cosec.identity.rid` and `cosec.identity.jti`). The
  attribute map and its accessors are removed; `tenant.tenantId` becomes `tenantId`.
- **`CoSecPrincipal` is what an application reads of the request**: `id`, an explicit `authenticated`, `attributes`. The
  roles effective for the request are in `SecurityContext.scope.roles`; `principal.roles` and `principal.policies` are
  removed.
- **The token contracts are values.** `TokenVerifier.verify(accessToken: String)` and `refresh` return a `VerifiedToken`
  (`me.ahoo.cosec.spi.token`), with no type parameter; `TokenRevoker.revoke` takes the token string. `TokenConverter`
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
  `switchTenant` receives it. The new token keeps the previous token's app.
- **Login is suspending, and `cosec-login` no longer brings Reactor** (batch R7c). `Authentication.authenticate`,
  `TokenCompositeAuthentication.authenticateAsToken`, `AbstractSwitchTenantAuthentication.switchTenant`,
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
  In the test kit, only `PolicyTestKit`, `PipelineFixture`, `EngineOutcome` and their exceptions are public.
- **`PipelineComponents` is a plain class**, as is `SnapshotOptions`; both lose `copy` and the `componentN` functions.
  `PipelineComponents` no longer takes an `engine`. A host without Spring Boot gets its snapshots from
  `SuspendingSnapshotSource.of(store)`.
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
  `suspend` handler instead of moving them off the event loop. `DefaultTokenRevoker` moves to
  `me.ahoo.cosec.runtime.token` and takes the `TokenRevocation`. `SimpleRefreshTokenAuthentication(tokenVerifier)` becomes
  `SimpleRefreshTokenAuthentication(tokenVerifier, tokenRevocation)` and no longer runs on a dispatcher of its own. The
  starter's `TokenVerifier` bean is the plain `JwtTokenVerifier`, and it adds a `TokenRevocation` bean. A `TokenStore` of
  your own implements the two writes as `suspend` functions, and its reads as a `RevocationReader` bean.
- **A revocation store that cannot be read is reported, never read as "not revoked"** (round-2 review H1, `IDN-14`).
  A `RevocationReader` throws `RevocationUnavailableException` when its store cannot be read; the revocation tables'
  `unknown-read` is locked to `throw`, so `cocache.caches.[RevokedTokenCache].unknown-read` fails startup. The pipeline
  still accepts a request's token then, as before; a refresh now fails instead of issuing a token, and the control plane
  publishes nothing on an approval whose approver's revocations cannot be read. A custom `RevocationReader` that
  answered "not revoked" on a failure throws `RevocationUnavailableException` instead. To refuse requests too, set
  `cosec.authentication.revocation.fail-closed: true`, which replaces `unknown-read: throw` on the revocation tables.
- **Instance statuses carry the document format level** (RFC 0010 C5): an instance reporting without `formatLevel` is
  refused by a control plane of this version, and a shadow window reading one fails until every instance has been
  upgraded (a status expires after a minute).
- **The control plane refuses what used to fail at run time.** A platform change that turns a policy other documents
  refer to into a boundary, or a boundary they refer to into a policy, is `REFERENCES` (`422`, `CTL-30`). A binding that
  names a platform boundary is `REFERENCES` too: such a binding never granted, every request it applied to was `503`,
  and a document that holds one has to remove it before it publishes again. An id a change puts holds no `/` (`V2`,
  `422`): a rule ending in `*` did not reach such an entry. A permission policy's `perm:<app>/<id>` may; entries that
  already hold one can stay or be removed.
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
  `Removed <n> reference(s) no head refers to.`. [Repair the back-reference index](#repair-the-back-reference-index).

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
  the keys and links [the table of removed and renamed keys](#removed-and-renamed-properties) rather than naming a
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
([Testing policies](../policies/testing.md#approval)). Nothing to migrate in the documents' content, but 8.0.0 stores its data through CoCache 8, which does not read what alpha.2 wrote through CoCache 6: move it with `cosec-migrate cocache8`, every instance stopped ([Data written through CoCache 6](#data-written-through-cocache-6)). Those at the gateway and
the edge of tenant isolation (RC1-5, RC1-6, RC1-15 to RC1-18, RC1-48, RC1-49), from **Identity forwarding** on, change
what a gateway forwards and to which routes: read them before upgrading a gateway.

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
  ([Root becomes break-glass](#root-becomes-break-glass)); `BreakGlass.request` derives the binding's id and has no
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
  roles and attachments are in `FlatRequest.scope` and `FlatRequest.memberships`. `Subject.tenantId` stays, for a
  stage-A `AttributeProvider`, which runs before there is a scope.

### Data written through CoCache 6

8.0.0-alpha.2 kept its data in Redis through CoCache 6; 8.0.0 keeps it through CoCache 8, under the same keys, and
CoCache 8 does not read CoCache 6's format. What it finds in that format fails closed, as unreadable content: a request
that needs such a document is `503` (`SourceUnavailable`), a token or principal revoked there is `503` instead of
`401`, and the control API answers `500`. Nothing reads such a value as absent, so a revoked token is never accepted
again.

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
   marker once every document is published again.
6. Start the 8.0.0 instances. Each document's revision starts again at 1; the history before the move is not carried
   over, so keep a copy of `<p>:control:revision:*` before step 5 if you need it.

Digests are SHA-256 now, where alpha.2's were CoCache 6's SHA-1: the control API's revision history, a change's base
and checks, and the events that name a digest write `SHA-256:<64 hex>`. A `SnapshotDocumentStore` of your own must
produce 32-byte SHA-256 digests, or every read fails closed ([Extending](../architecture/extending.md#snapshot-document-stores)).

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
never does. Its contract is the command line: its classes are not a library API, and may change in any release. Build it
from the CoSec sources of the version you deploy:

```bash
./gradlew :migrate:installDist
migration/migrate/build/install/cosec-migrate/bin/cosec-migrate convert ...
```

Its commands are below. Exit codes: `0` done, `1` the data was rejected or a write failed, `2` a usage error.
`publish`, `bootstrap` and `repair-references` take `--cluster` for a Redis Cluster: `--to-redis` then names seed
nodes, comma-separated (`redis://node-1:6379,redis://node-2:6379`). The other commands read Redis with clients that
do not, and refuse it. Each command takes only the options listed for it: another one, `publish --dry-run` for
example, is a usage error before anything is read or written, never ignored.

| Command | What it does |
|---|---|
| `convert` | Reads 7.x data (Redis or exported files), translates it, and writes 8.0 documents and a report into a new directory. Writes nothing to Redis. |
| `publish` | Writes the documents of an accepted conversion to Redis as their first revisions, through the control plane's store. Never touches the 7.x keys. |
| `bootstrap` | Binds one principal of the platform tenant to `cosec:platform-admin`, so the control plane has someone who may write. |
| `partition plan` | Converts twice, with `--partition platform` and `by-owner`, and writes one plan per tenant to submit through the control plane. Writes nothing to Redis. |
| `repair-references` | Adds to the back-reference index what every document's head refers to and the index misses, removes what no head has, and completes the app list. |

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
| `INVALID_ID` | An id 8.0 cannot hold: `*` or empty as an app or space id, `:` in an app id, `@` in a role id, or an unpaired UTF-16 surrogate in any id of a converted document (a JSON escape such as `"\uD800"`, `IDN-10`), located by the document and its JSON path. | Rename it. In 8.0 `*` means any app or space. |
| `DUPLICATE_ID` | The same policy twice in an export, the same app twice in an export (one entry would replace the other, and its grants would be lost), the same permission twice in one app, or two app or tenant ids whose converted file names differ only in case (`Order` and `order`: one file on macOS and Windows). Ids that differ in more than their file names, such as `strasse` and `straße`, are not a collision. | Keep one, or rename. |
| `OWNER_TENANT` | `--partition by-owner`, and a policy whose `tenantId` names no tenant. | Set its `tenantId`, or convert with `--partition platform`. |
| `CONDITION_NOT_TRANSLATED` | A built-in 7.x condition with no exact translation, such as a `regular` that is not a literal, a literal prefix or a choice of literals, or a `path` condition outside a statement whose action is `all`. | Rewrite it with the conditions the message names (`eq`, `in`, `startsWith`, ...). |
| `EXPRESSION_CONDITION` | A `spel` or `ognl` condition. | Rewrite it as conditions of the [policy language](../policies/language.md). |
| `CUSTOM_CONDITION` | A condition of a custom `ConditionMatcherFactory`. | Move the value it reads into an `AttributeProvider` and write a condition on that attribute ([Extending](../architecture/extending.md)). |
| `ACTION_NOT_TRANSLATED` | A `#{...}` path template, a path `separator` other than `/`, an action array whose members have different methods, or a method that is not an HTTP token. | See [Path templates in actions](#path-templates-in-actions); split statements by method; remove methods no request can have. |
| `CUSTOM_ACTION` | An action of a custom `ActionMatcherFactory`. | Rewrite it as HTTP paths and methods, or as route-mapped actions. |
| `DOES_NOT_COMPILE` | A translated statement does not compile, such as a path pattern outside what 8.0 accepts; or a document does not compile as a whole, such as when each policy fits the budget of path patterns and all of them together exceed it. The rejection names the policy it can trace, else the document. | See the message and the [policy language](../policies/language.md). |

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
platform tenant's document, and records it as a revision of kind `BOOTSTRAP`. It refuses when that document already
binds someone to `cosec:platform-admin`; `--force` is for the recovery where every one of them is lost. Anyone with
the Redis credentials can write anyway; `bootstrap` only leaves a record of it.

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
`repair-references` reads the head of every document the store lists and adds whatever the index misses; it prints
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
- **A `tenantId` claim that is empty or not a string**, or an `attributes` claim that names `apiKeyId` (set by CoSec for
  API keys only), makes the token `token-invalid`; 7.x read such a tenant as none. **`cosec.jwt.secret`** must be at least
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

**Converting stored keys.** `cosec-migrate api-keys` reads every key under `<--key-prefix>:api-key` (default
`cosec:api-key`), where the deployment reads them, and writes `api-keys-report.json` to `--out`. `--app <id>` says which
app the converted keys belong to ([Apps](../integration/authentication.md#apps)); 7.x keys declare none, so without it a
service with an app refuses them. Export what each principal holds now, as a JSON array
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
| `me.ahoo.cosec.token` | `me.ahoo.cosec.runtime.token` (`cosec-runtime`) | `SimpleCompositeToken` (`RevocableTokenVerifier` is removed: [From 8.0.0-alpha.4](#from-8-0-0-alpha-4)) |
| `me.ahoo.cosec.context` | `me.ahoo.cosec.servlet` (`cosec-webmvc`) | `SecurityContextHolder` |
| `me.ahoo.cosec.context.request` | `me.ahoo.cosec.runtime.http` (`cosec-runtime`) | `RemoteIpResolver`, `XForwardedRemoteIpResolver` |
| `me.ahoo.cosec.api.audit` | `me.ahoo.cosec.spi.audit` (`cosec-spi`) | `AuditEventSink` |
| `me.ahoo.cosec.audit` | `me.ahoo.cosec.runtime.audit` (`cosec-runtime`) | `LoggingAuditEventSink`, `AuditEventData` |
| `me.ahoo.cosec.apikey` | `me.ahoo.cosec.spi.apikey` (`cosec-spi`) | `ApiKey`, `ApiKeyReader`, `ApiKeyStore`, `InMemoryApiKeyStore` |
| `me.ahoo.cosec.limiter` | `me.ahoo.cosec.spi.ratelimit` (`cosec-spi`) | `RateLimit`, `RateLimitResult`, `RateLimiter` ([below](#removed-and-replaced-apis)) |
| `me.ahoo.cosec` | `me.ahoo.cosec.model` (`cosec-model`) | `CoSecException` |

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
| `SimpleSecurityContext`, the extension `CoSecPrincipal.tenant` | None: the pipeline makes the request's `SecurityContext`. In a test, mock or implement the interface; the tenant is `SecurityContext.tenantId`. |
| `KafkaAuditEventSink` | `KafkaAuditListener`, a `DecisionListener` the starter registers when `cosec-kafka` is on the classpath ([Observability](./observability.md)). The record's key is the same, and its value is the audit event ([Observability changes](#observability-changes)). |
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

Every key below fails startup in 8.0, and the message links this table. So does any other `cosec.*` key 8.0 does not
read; no property turns the check off ([Unknown properties](./configuration.md#unknown-properties)). A key ending in
`.` stands for every key under it.

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
| `cosec.authorization.cache.token.expire-after-write` | `cosec.authentication.revocation.cache.local-max-age`, a duration (`30s`). |
| `cosec.authorization.cache.token.expire-unit` | Removed: the durations carry their unit. `expire-after-access` is a duration too; a bare number is still seconds. |
| `cosec.authorization.gateway.enabled` | Removed: the gateway filter comes with `cosec-gateway`. |
| `cosec.limiter.` | Removed: rate limit rules are in the platform document ([Rate limit rules become obligations](#rate-limit-rules-become-obligations)); a different limiter key prefix needs a `RateLimiter` bean of your own. |
| `cosec.shadow.` | Removed: shadow windows are control-plane operations; each instance's part is `cosec.authorization.shadow.*`. |
| `cosec.explain.enabled` | Removed: use the control plane's `explain`. |
| `cosec.inject.enabled` | Removed ([Inject mode becomes identity propagation](#inject-mode-becomes-identity-propagation)). |
| `cosec.api-key.store-blocking` | Removed. |
| `cosec.jwt.token-revocation.enabled` | Removed: revocation is always on ([Token revocation is always on](#token-revocation-is-always-on)). |
| `cosec.authorization.cache.key-prefix` | `cosec.key-prefix`, the prefix of every key: copy the token revocations first ([which families move](#from-8-0-0-alpha-2)). |
| `cosec.api-key.key-prefix` | `cosec.key-prefix`: copy the API keys first ([which families move](#from-8-0-0-alpha-2)). |
| `cosec.authorization.snapshot.key-prefix` (8.0 previews) | `cosec.key-prefix`: set it to this prefix ([which families move](#from-8-0-0-alpha-2)). |
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
