---
name: cosec-troubleshoot
description: Diagnose CoSec 8.0 failures - startup failures (unknown cosec.* keys, pipeline mode, Redis eviction check, missing TokenStore or revocation reader, route maps, actuator endpoints on a gateway), instances that stay not ready, services behind a gateway that refuse the forwarded identity, unexpected 400/401/403/429/503 problem responses, Indeterminate decisions, documents that do not compile or lag, rate limits, and control-plane errors - and explain which statement decided. Do not change behavior unless the user asks for a fix.
---

# CoSec Troubleshooting

The human-facing guide is https://cosec.ahoo.me/operations/troubleshooting; this skill adds the agent workflow.

Find the first incorrect step: startup, authentication, scope, snapshot (documents), evaluation, rate limiting, response.
Gather evidence before proposing a document or code change.

## Start with observable facts

Capture: method and path, status, the problem+json body (`type`, `title`, `status`, `requestId`, optional `traceId`)
and the `CoSec-Request-Id` header; the principal's tenant, id, roles and attached policies (token claims, never the
token itself); the token's `aud` or the API key's `app`, `CoSec-Space-Id` and the host's `cosec.authorization.scope.app-id`; the route map entry
(which action and resource the request gets); the revisions of the platform, app and tenant documents; startup and
request logs. Redact tokens, API keys and secrets.

Bodies never say which policy decided (so a caller cannot probe for DENYs). The audit log does:

```yaml
logging:
  level:
    me.ahoo.cosec.audit: DEBUG   # denies, 429 and 503 at WARN, errors at ERROR, allows at DEBUG
```

Each audit event names the deciding policy and statement, the binding it applied through, and the snapshot revisions.
To see why without reproducing traffic, use the control plane (needs `cosec-control` and an operator allowed to):
`POST /cosec/control/v1/explain` with
`{"request": {"tenant": "acme", "principal": "alice", "roles": ["editor"], "policies": [], "app": "order-service", "space": "", "action": "order:read", "resource": "order/42", "remoteIp": "10.0.0.1"}}`
returns the decision and its trace; `POST /analyze` with a `query` (`tenant`, `app`, `space`, `action`, `resource`)
lists the bindings that could permit or deny in that tenant (another tenant's principals and roles are left out). External attributes are not fetched there, so a statement reading one is
Indeterminate in an explanation.

## Interpret the status

| Status | Problem `type` slug | Meaning |
|---|---|---|
| 400 | `invalid-request` | The request is ambiguous: a path that does not normalize (a dot segment, a malformed escape, an empty segment before its end such as `//`, an encoded `/`, a `\` or `%5C`), two different values of one `CoSec-*` parameter (header vs query; a blank value beside a non-blank one counts, and only ASCII whitespace is blank), a route-map capture that cannot be a resource id segment, or a header sent with different values that a document of the request's view reads (any statement, policy condition, boundary or rate limit rule, whatever its effect; PIP-12): typically `X-Forwarded-For` from two proxies that each append a line; have them send one line, or read `environment.ip`. The engine never saw it. |
| 401 | `unauthorized` | Denied, and the caller is anonymous (no credentials, or an anonymous caller asking for a space). |
| 401 | `token-invalid`, `token-expired`, `token-revoked` | The credentials were rejected (an invalid API key is `token-invalid`); the request was evaluated as anonymous and denied. |
| 403 | `forbidden` | Denied for an authenticated principal: a DENY matched, or nothing allowed (both look the same on purpose). |
| 403 | `scope-rejected` | The credential declares an app other than the host's, or none (on a gateway the route's metadata `cosec-app-id`, else `cosec.authorization.scope.app-id`): issue the token for this app. Or the credential's app is not a valid app id (blank, `*`, `:` or a control character, over 128 characters), or `CoSec-Space-Id` names a space where the principal has no role. |
| 429 | `too-many-requests` | Permitted, but a rate limit rule's bucket is exhausted; `Retry-After` and `RateLimit-*` headers. |
| 503 | `unavailable` | No decision could be made (Indeterminate), the authenticator's dependency failed, or `cosec.pipeline.timeout` passed. Never retried by a `Retry-After`. |
| 500 | `internal-error` | An exception nothing modelled: a CoSec defect. Read the server log. |

A 401 always means "authenticate (again)"; a 403 means the identity is known and denied.

## Decision order

1. Built-in blacklist DENY (`cosec:blacklist`), then every DENY of every candidate: platform `*` bindings, tenant `*`
   bindings, the principal's attachments (`policies` claim) and principal bindings, role bindings of the app document
   then the tenant document (only roles held in the request's space), break-glass bindings. Any match denies.
2. No DENY matched, but a DENY errored, a needed document failed or is missing, a needed policy is missing or
   ambiguous, external attributes or memberships were unavailable: Indeterminate (503).
3. First matching ALLOW permits; an erroring ALLOW is no match. Nothing matched: implicit deny (401/403).
4. A permit is held to the tenant's platform boundary; then rate limits spend permits.

Full model: https://cosec.ahoo.me/guide/security-model.

## Startup failures

- **`cosec.authorization.snapshot.revalidate-interval (<value>) must be ...`**: the periodic revalidation is the only
  bound on a lost invalidation (SNP-6), so the interval must be at least 1 second, at most 5 minutes, less than
  `lag-warn`, and, with `fail-closed.tenants`, less than `fail-closed.max-lag`. Use a value within the bounds; never
  suggest turning revalidation off.
- **`CoSec does not read ... cosec.authorization.snapshot.key-prefix`** (or `cosec.authorization.cache.key-prefix`,
  `cosec.api-key.key-prefix`): one `cosec.key-prefix` replaced them (SNP-9), and they fail startup as unknown keys. Which
  families move, from where to where, is at https://cosec.ahoo.me/operations/upgrading#from-8-0-0-alpha-2. Have the token
  revocations and API keys copied to the new keys first (`COPY`, or `RENAME`, which keeps the expiry): a revocation
  left under the old prefix is no longer read, so the token it revoked would be accepted again. Then set
  `cosec.key-prefix` to the snapshot documents' prefix and remove the old properties; never suggest only renaming them.
- **A document does not compile on a path pattern** (`DOC-4`): over 2,048 characters, a glob segment of more than 16
  parts (consecutive `*` count once), or more than 2,048 glob units in one document (each glob segment's parts and the
  characters of its text, twice after a leading `**`). The control plane answers a
  `COMPILE` finding; on the data plane the stored document is installed as failed and its requests are 503.

- **`CoSec does not read <n> cosec.* properties, which would do nothing:`** followed by the keys: a typo or a key a
  release renamed or removed (for example `cosec.authentication.enabled`, now `cosec.authentication.login.enabled`).
  Rename or remove the key; the table is at https://cosec.ahoo.me/operations/upgrading#removed-and-renamed-properties.
  No property turns the check off. On Kubernetes, keys such as `cosec.gateway.service.host` or
  `cosec.gateway.port` (`does not read 8 cosec.* properties`, a crash loop) are service links
  (`COSEC_GATEWAY_SERVICE_HOST`, `COSEC_GATEWAY_PORT_80_TCP_ADDR`, ... for a Service named `cosec` or `cosec-*` in the
  namespace). 8.0.0-alpha.2 and later skip exactly those variables, only from the process environment; a mistyped
  `COSEC_*` variable or the same key in `application.yaml` still fails. On alpha.1, or to keep them out of the pod, set
  `enableServiceLinks: false` on the pod, as `k8s/` does.
- **`<what> needs <artifact> (me.ahoo.cosec:<artifact>) on the classpath: add it to the application's dependencies
  (STA-1).`**: a feature that is on needs a CoSec module that is missing. `A Spring MVC host` needs `cosec-webmvc`,
  `A WebFlux host` `cosec-webflux`, `A Spring Cloud Gateway host` `cosec-gateway` (checked even with
  `cosec.enabled=false`: its filter strips a client's `CoSec-Identity`), `cosec.api-key.store=redis` `cosec-cocache`,
  `cosec.audit.kafka.delivery=required` `cosec-kafka`, `cosec.cosid.generator` `cosec-cosid`. Add the named artifact next to `cosec-spring-boot`. Do not
  suggest turning the feature off unless the user does not want it.
- **`cosec.audit.kafka.delivery=required, but no Kafka audit listener was created`** (STA-1): `cosec-kafka` is there,
  but the listener is not: add a `KafkaOperations` bean (`spring-boot-starter-kafka` with `spring.kafka.*`), and keep
  `cosec.audit.enabled` and `cosec.audit.kafka.enabled` on; or use `best-effort`.
- **`cosec-cocache needs a ReactiveStringRedisTemplate bean`** (STA-1): add `spring-boot-data-redis` and configure
  Redis; without it no snapshot is read and rate limits would count per instance.
- **`cosec.pipeline.mode=gateway on a Spring Cloud Gateway host`**: a gateway always runs `enforce`; set `gateway` on
  the services behind it. A binding failure on `cosec.pipeline.mode` means a value other than `enforce` or `gateway`.
- **`Actuator endpoints [<ids>] are exposed over HTTP on the gateway's application port, where CoSec does not
  authorize them`** (STA-2): a gateway host serves actuator on its application port (`management.server.port` unset or
  equal to `server.port`) with an endpoint other than `health` exposed. Serve actuator on its own port behind a network
  policy (`management.server.port`), expose only `health`, or list the endpoints something else protects in
  `cosec.gateway.unprotected-endpoints`. **`cosec.gateway.unprotected-endpoints lists [gateway], which it never
  accepts`**: the `gateway` endpoint changes routes; serve it on a management port, or not at all.
  https://cosec.ahoo.me/operations/troubleshooting#startup-fails-on-an-exposed-actuator-endpoint
- **`cosec.control.audit.kafka.delivery=required, but no Kafka change listener was created`**: add `cosec-kafka`, a
  `KafkaOperations` bean and `cosec.control.audit.kafka.enabled=true`, or use `best-effort`.
- **`The control plane checks its approvers' revocations, but no revocation reader is available`**: enable CoSec's
  tokens with `cosec-cocache`, or declare a `RevocationReader` bean (`RevocationReader.NONE` only when no approver's
  credential is ever revoked). **`cosec.jwt.token-validity.refresh (...), how long a token revocation is kept, is
  shorter than cosec.control.approval.expire-after`**: raise the first or lower the second.
- **`Redis may evict CoSec's policy snapshot: maxmemory-policy is {...}, not noeviction`**: set
  `maxmemory-policy noeviction` on every node. **`Cannot tell whether Redis may evict CoSec's policy snapshot (INFO
  memory failed or is not allowed)`** or `(the CoCache backend ... is not one this check can read)`: a Redis user
  without `+info`, a Redis that denies `INFO`, or a wrapped CoCache backend. If the operator has set `noeviction`, set
  `cosec.authorization.snapshot.eviction-check=warn`; the instance then logs `!!! ... Starting anyway
  (eviction-check=warn)` at every start. Never suggest `warn` to silence a Redis that really evicts: an evicted app or
  tenant document reads as absent, and its DENYs silently stop applying.
- **`Cannot read maxmemory-policy (INFO memory) to check that the authoritative caches [...]`** or **`Authoritative
  caches [...] keep their only copy in Redis, but maxmemory-policy {...} can evict them`**: CoCache 8's own check of
  its `Store`s, which `eviction-check=warn` does not reach. Allow `INFO`, or, once `noeviction` is set, set
  `cocache.redis.assume-maxmemory-policy=noeviction`; `allkeys-*` is never accepted.
- **`Cache[<CoSec cache>] - ... locked by cosec`**: a `cocache.caches.[name].*` entry or a `CacheSpecCustomizer` sets a
  field CoSec locks on its caches (a store of record's `ttl` or `unknown-read`, the snapshot store's `local-max-age`,
  any `key-prefix`). Remove the setting or exclude CoSec's caches; never suggest `unlock` for these, whose constrained
  fields keep their values anyway. **`cocache.caches sets unknown keys [...]`**:
  CoCache's own check of a misspelled or inapplicable per-cache key; fix the key (`cocache.caches-validation: warn`
  only logs it). **`cocache.caches configures undeclared caches [...]`**: the name matches no cache, usually a typo;
  CoSec publishes every name it may create, so an entry for a CoSec cache this instance does not create starts. **`Cache[<name>] - its
  storage keys (...) overlap those of Cache[<other>]`**: an application cache and a CoSec cache would share Redis
  keys; give the application's cache another prefix or namespace (`cosec.key-prefix` moves every instance's data).
  **An `IllegalStateException` naming a channel pattern** such as
  `[&cosec:control:inv:*]`: the Redis ACL denies the cache's invalidation channel; allow `&<prefix>:*`.
- **`CoSec's Redis template [<bean>] reads from replicas (ReadFrom...)`**: the Lettuce client behind the template CoSec
  injects (the only `StringRedisTemplate` / `ReactiveStringRedisTemplate`, or the `@Primary` one), named in the message,
  reads from replicas (`spring.data.redis.lettuce.read-from`, or a `LettuceClientConfigurationBuilderCustomizer` that
  sets `ReadFrom`). Remove it or set `upstream` on that factory. Other data may read from replicas through a template
  and connection factory of its own that is not the one CoSec injects (not `@Primary`). Never suggest replica reads
  for CoSec: Redis is its store of record, and a lagging replica can hand back a revoked API key.
  https://cosec.ahoo.me/operations/troubleshooting#startup-fails-because-redis-is-read-from-replicas
- **`JWT is enabled, but no TokenStore records revoked tokens`** or **`Tokens are verified, but no revocation reader
  is available`**: revocation is always on. Add `cosec-cocache` (Redis token store), or register a `TokenStore` and a
  `RevocationReader` bean, or set `cosec.jwt.enabled=false` on a service that does not verify tokens.
- **`Property [cosec.jwt.secret] must not be blank`**: set the secret (same algorithm and secret as the issuer).
  **`Property [cosec.jwt.secret] has <n> bytes, and HMAC256 needs at least 32`**: the secret must be at least 32, 48 or
  64 UTF-8 bytes for `hmac256`, `hmac384`, `hmac512` (RFC 7518 §3.2); set a longer one on the issuer and every verifier.
- **`classpath:cosec-routes.json exists but cosec.authorization.routes.location is not set`**: set the property to it,
  or to empty to run without a route map.
- **`The route map [<location>] (cosec.authorization.routes.location) does not exist`**: add the route map, or correct
  the property. A `RouteMapException` names the location and route: malformed route, a map with no routes, a resource
  variable the path does not capture, or two routes for one method that may match the same request.
- **`cosec-cocache needs a CacheRuntime bean`**: add `me.ahoo.cocache:cocache-spring-boot-starter`.
- **`API keys are enabled, but no ApiKeyReader is available`**: `cosec.api-key.store=redis` needs a
  `ReactiveStringRedisTemplate`.
- **`cosec.api-key.enabled=true needs a MembershipProvider bean`** (IDN-6): a key's memberships are its principal's.
  Add `cosec-cocache` and a `MembershipLoader` bean (the starter then wires `CoCacheMembershipProvider`), or declare a
  `MembershipProvider`. Checked only in `cosec.pipeline.mode=enforce`, where keys are read.
- **`API key [<id>] needs exactly one of roles ... and uncapped: true`**: a configured key states its cap (`roles`,
  `[]` for none) or `uncapped: true`, never neither or both. **`cosec.api-key.enabled=[x] is neither true nor false`**:
  write `true` or `false`.
- **`API key [<id>] needs a tenant-id`**, or a binding error on `cosec.api-key.keys[n].policies`/`.attributes`: keys
  carry only a tenant, a principal and a role cap (`roles`) now; grant roles to the principal through the provider.
- **Shadow instance id**: `cosec.authorization.shadow.instance-id` may hold only letters, digits, `.`, `_`, `-`.
- **`Decision listener <name> is registered twice`** / **`External attribute <path> is declared twice`**: two beans
  share a listener name or an attribute path.

## Symptoms

### Every request is 503, or the instance is not ready

- No platform document is published yet (the first one comes from `cosec-migrate publish`; `cosec-migrate bootstrap`
  writes only the `(platform)` tenant's document), or it was lost (evicted): an absent platform fails every request
  closed. Readiness refuses traffic until the platform document and every `prewarm-apps`/`prewarm-tenants` document is
  installed; the `cosecSnapshot` health indicator shows `platform` (`installed`, `failed`, `absent`, `loading`) and
  `prewarmed`, and only it belongs in the readiness group. Beside it, `cosecSnapshotEviction` is `DOWN` with an
  `eviction` detail while Redis may evict the snapshot (each instance reads `maxmemory-policy` every
  `revalidate-interval` on its own thread, and logs `ERROR` from `EvictionPolicyMonitor` when that starts): an alert,
  never a readiness signal (it also turns the aggregate `/actuator/health` DOWN, so load balancers poll the readiness
  group); requests are still decided, but set `noeviction` again before an app or tenant document is
  evicted. `Cannot tell whether Redis may evict` there is `eviction-check=fail` on a Redis that denies or times out
  `INFO`: allow `+info`, or set `warn` when the operator has set `noeviction`, under which it is `UP` with
  `eviction: unknown`. https://cosec.ahoo.me/operations/troubleshooting#cosecsnapshoteviction-is-down
- A document the request needs does not decode or compile: its new revision is installed as failed
  (`SnapshotUnavailableException: ... revision <n> cannot be compiled`, caused by a `DocumentCompileException` listing
  JSON paths), and every request that needs it fails closed; the previous revision is not kept. Compile the stored
  document yourself (see "Reproduce narrowly") to see the errors. Documents compile against the platform's `limits` and
  `attributes`, so a platform change can make an app or tenant document stop compiling.
- Redis is unreachable for a document not yet held locally (`cold-load-timeout`, default 2s), or the snapshot lags
  beyond `fail-closed.max-lag` for a tenant in `cosec.authorization.snapshot.fail-closed.tenants`.

### One principal, tenant or route gets 503

- **Memberships unavailable**: the token carries `cosec.membership: "provider"` and no `MembershipProvider` bean
  exists, or it failed or exceeded `cosec.authorization.membership.timeout`; or the token has the marker together with
  `roles`/`policies` claims, or an unknown marker.
- **Missing or ambiguous policy**: the token's `policies` claim names a policy that does not exist or is not
  `attachable`; a tenant binding names a platform policy that was removed; or a tenant defines a policy id the platform
  also defines.
- **External attribute unavailable**: a statement reads `ext.<provider>.<name>` with `onMissing: indeterminate`
  (default) and the provider failed, timed out (`cosec.authorization.attributes.timeout`) or is not registered on this
  instance. A blacklist source failure makes every request 503 unless a DENY matches.
- **Only IPv6 clients get 503, on a statement reading `ext.ip2region.*`**: the host has only the bundled IPv4 ip2region
  database, and an address no loaded database covers is unavailable, not absent (`ATR-9`). Confirm with the startup
  line `ip2region databases: [IPv4]. The IPv6 addresses are unavailable: ...` and the clients' addresses in the audit
  events. Fix: load an IPv6 database with `cosec.ip2region.ipv6-location`, or put `onMissing: nomatch` (with its
  control-plane confirmation) on a DENY where letting an unlocatable client past it is what the policy means.
- **A DENY's condition errored**, or the tenant's boundary statement errored. A DENY on `http.header.<name>` errors when
  the request sent that header more than once with different values (`Header [<name>] arrived with different
  values`); an ALLOW on it then does not match. One value repeated reads as that value.
- **Rate limiter cannot count** and the matching rule has `strictFailure: true` (on a Redis Cluster, `CROSSSLOT` in
  the log: see Rate limiting).
- **A REQUIRED decision listener failed or timed out** (`cosec.observability.listener.required-timeout`): it turns a permit into 503.
- **The authenticator's dependency failed** (for example the API key or revocation store); a revocation store that
  cannot be read lets the request's token through instead (the documented exception to fail closed), unless
  `cosec.authentication.revocation.fail-closed` is on. A refresh fails then (`RevocationUnavailableException`, `IDN-14`):
  answer it 503 and retry once Redis answers.
- **An id with an unpaired UTF-16 surrogate** (IDN-10, RC1-52): an authenticator gave a principal whose id, tenant,
  credential id or claimed roles hold one (`Principal [<id>] of tenant [<tenant>] holds an id with an unpaired UTF-16
  surrogate`), an API key record names one (`API key [<id>] holds an id ...`), or the `MembershipProvider` answered a
  space, role or policy id with one (`The membership provider answered an id with an unpaired UTF-16 surrogate`). A
  Redis key would hold `?` in its place, sharing another id's key, so the request fails closed. Fix the source: an id
  is Unicode text. Logs write such a surrogate as `\uD800`, never `?`: `beta\uD800` is not the tenant `beta?`.
- **Every request is 503 after upgrading from 8.0.0-alpha** (revoked tokens `503` instead of `401`, the control API
  `500`) and the log says `holds content this cache cannot read (format[1], this node writes format[2]`: the data
  alpha wrote through CoCache 6. Stop every instance and run `cosec-migrate cocache8` (dry run first). Never delete
  those keys by hand: a deleted revocation accepts its token again.
- **Every request with one API key is 503** and the log says `API key format [null] is not 2.` (`null`: the record has
  no `format`, as a key written by 7.x): convert
  the keys with `cosec-migrate api-keys` (dry run first; grant the report's memberships and role bindings, then run
  it again without `--dry-run`).

### Unexpected 401

- Did the request carry credentials? Tokens come from `Authorization`; the query parameter only with
  `cosec.pipeline.allow-query-token=true`, the `authorization` cookie only with
  `cosec.pipeline.allow-cookie-token=true`. API keys from `X-API-Key`
  (`cosec.pipeline.api-key-header`).
- `token-expired`, `token-revoked` (by token, or every token of the principal issued before a `revokeSubject`, with
  `cosec.authentication.revocation.skew`), `token-invalid` (wrong algorithm or secret: only HMAC256/384/512; or a
  delegation token, one with an `act` claim, which 8.0 refuses: audit `reasonCode` `DELEGATION_UNSUPPORTED`, IDN-4;
  or a claim CoSec refuses: `tenantId` empty or not a string, `roles` or `policies` not an array of strings, `attributes` not an
  object).
- `token-invalid` for an API key, or a token marked `cosec.membership: provider`, whose principal the
  `MembershipProvider` does not know (answers `MembershipAnswer.Unknown`) or knows as deactivated (`active = false`),
  IDN-3: the decision
  event's `subject.rejected` is `API_KEY_INVALID` or `TOKEN_INVALID`.
- An API key that is valid but holds fewer roles than expected: its `roles` cap keeps only the principal's roles it
  names, exactly by space; attached policies and principal bindings grant it nothing, but their DENYs still apply
  (IDN-2, BND-9), so a capped key is denied what its principal is denied. A key grants nothing its principal does not
  hold (IDN-1), and no API key ever gets break-glass (BRK-8). `roles: []` leaves only `Anyone`, less those DENYs.
- Break-glass that does not apply: only a token CoSec's own `JwtTokenVerifier` verified gets it (BRK-8), never a token
  of a custom `TokenVerifier`, even through `TokenAuthenticator`. An authenticator naming its method `jwt` makes every
  request `503` (`The authentication method [jwt] is CoSec's own token's`): that name is reserved.
- `token-invalid` for a token whose `tenantId` claim is empty or not a string, or whose `attributes` name `apiKeyId` (reserved
  for API keys).
- `token-invalid` for a token whose `sub`, `tenantId`, `jti`, `roles` or `policies` hold an unpaired UTF-16 surrogate,
  which JSON writes as an escape (`"beta\uD800"`), IDN-10: a Redis key would write it as `?` and read another id's
  documents, memberships and revocations. CoSec's own `JwtTokenConverter` refuses to sign one (login fails with
  `holds an unpaired UTF-16 surrogate`). Fix the issuer or the user store; surrogate pairs (emoji) are fine.
- Anonymous callers belong to tenant `(0)` and have no roles: a `subject: "*"` ALLOW must not require
  `subject.authenticated`.

### Unexpected 403 or missing allow

- Which action and resource did the request get? With a route map, a mapped request is `service:verb` on
  `type/id`; **`http:*` statements and `http:` rate limit rules never apply to it**. Unmapped requests are
  `http:<METHOD>` on `http:path:<path>`, and `service:verb` statements never apply to them. On a gateway every request is
  `http:`: a permission written only on `service:verb` (an OpenAPI-generated app document) permits nothing there
  (https://cosec.ahoo.me/scenarios/gateway).
- A role that should grant does not: roles are read from the document as expanded bindings (`role:<roleId>@<app>:<permission>`),
  so check, in order: the token's `aud` is the host's app (`scope-rejected` otherwise); the role is in the token as
  `role` or, in a space, `role@space`; a custom role (tenant document) names the request's app and, if it names a `space`,
  the request's; a permission in a group that is not `spaced` applies in the default space only (a role held in a named
  space and a non-spaced permission never meet); the permission is in the catalog with an `action` (an entry without one
  grants nothing); a `role:` binding id collision or more than 50,000 derived bindings fails the document at `$.roles`.
- Is the policy bound in the right document, with the right app and space? `space: ""` is the default space only;
  roles count only in the request's space (`role@space` claims; unsuffixed roles in the default space).
- Is the binding expired (`expiresAt`)? Is the tenant held by a boundary that does not cover the request?
- Conditions: literals keep their JSON type (`true` vs `"true"`). `http.header.<name>`, `http.origin`, `http.referer`
  and `request.attributes.<name>` come from the request; `http.origin.host` and `http.referer.host` are lower case and
  absent when the value is not a URI with a host. The gateway's route id is `request.attributes.cosec.audit.routeId`.
- A condition or rate limit `key` on a credential header (`authorization`, `proxy-authorization`, `cookie`,
  `set-cookie`, `x-api-key`, `cosec-identity`) does not compile:
  `` `<path>` is a credential header, which no attribute reference may read ``. A header name that is not an ASCII
  token (RFC 9110) does not compile either: `` `<path>` is not a header name ``.
- `scope-rejected`: the credential's app, or the client's `CoSec-Space-Id` (see the table).

### Request allowed unexpectedly

- Find the deciding statement in the audit event or with `explain`. Any matching DENY would have won, so the expected
  DENY did not match: check its action namespace (`http:*` vs mapped `service:verb`: a platform DENY on `http:*` stops
  covering a path the moment a route map maps it, https://cosec.ahoo.me/scenarios/route-maps), resource pattern, condition
  (an absent attribute makes `eq`/`in`/`prefix`/`cidr` false), binding scope, and `onMissing: nomatch` (lapses
  while its provider fails).
- Is the instance on the expected revision? Compare the audit event's snapshot versions with the published head.
- A break-glass binding permits as `cosec:root` until it expires; audit marks such permits.

### Service behind a gateway (`cosec.pipeline.mode=gateway`)

The service reads the caller from `CoSec-Identity`, which only the CoSec gateway sets (it strips the client's own). No
header: anonymous. Otherwise never anonymous:

- 400 `invalid-request`: not base64url JSON of format v1, unknown or missing members, an id with an unpaired
  surrogate (IDN-10), over
  `cosec.pipeline.identity.max-size` (8KB), sent more than once, or a scope that does not resolve here.
- 401 `token-invalid`: unsupported version, no `auth.exp`, or issued in the future (beyond
  `cosec.authentication.revocation.skew`).
- 401 `token-expired`: the credential expired, or the identity is older than `cosec.pipeline.identity.max-age` (60s)
  plus the skew. Check clocks.
- On the gateway, a 500 with `Request [<id>]: the identity cannot be forwarded.`: the credential never expires (an API
  key without `expires-at`), or the identity is too large or a `cosec.gateway.identity.claims` value is not a string
  (at most 1024 characters), number or boolean.
- Every request anonymous behind the gateway: the gateway forwards the identity only to a `lb://` route or one whose
  metadata says `cosec-identity: true`; an `http(s)://` route without it, or with `cosec-identity: false`, gets none.
  Mark the route `cosec-identity: true` if it leads to your own service.
- A backend that verifies tokens itself gets no credentials (`Authorization`, the API key header, the `authorization`
  query parameter, the `authorization` cookie) unless `cosec.gateway.identity.forward-authorization=true`, and then
  only on a route that would receive the identity. JWT is off by default on a gateway-mode service; one that issues
  tokens sets `cosec.jwt.enabled=true`.
- On the gateway, a 500 on every request of one route: its metadata `cosec-app-id` is not a valid app id, or not a
  string (a number, list or map: quote it, one app).

### Change not visible / snapshot lag

- Instances revalidate every `cosec.authorization.snapshot.revalidate-interval` (10s; it cannot be turned off, SNP-6)
  and serve local copies up to `local-max-age` (30s). Every instance and the control plane need the same Redis and
  `cosec.key-prefix`. Logs: `Snapshot document [<key>] revision <n> has not been confirmed for <lag>` (WARN, over
  `lag-warn`, 60s) and `... is stale` (ERROR, over `lag-escalate`, 5m). `Reloading snapshot document [<key>] failed;
  keeping revision <n>` means Redis could not be read: the installed revision stays until a read succeeds.
  `Snapshot document [<key>] regressed from revision <a> to <b>` means the store lost a write (for example a
  failover).
- The control plane's `GET /documents/{key}/revisions/{rev}/propagation` reports how many of the recently reporting instances (those with `cosec.authorization.shadow.enabled`) have installed it.

### Rate limiting

- Without a `ReactiveStringRedisTemplate`, the limiter is per JVM: N instances allow N times the limit.
- Denied requests never spend permits. A rule that never applies has a non-matching action (namespace), resource or
  condition. Rules count per document: an app's or tenant's rule has its own buckets.
- A limiter failure skips the rule unless `strictFailure: true` (then 503, not 429).
- A `key` value with an unpaired UTF-16 surrogate counts in a bucket of its own, `e:` and the value escaped (`\uD800`,
  `\` doubled), never in the bucket of the value with `?` in its place (IDN-10). Other values count in `k:<value>`.
- On a Redis Cluster, `CROSSSLOT Keys in request don't hash to the same slot` in that log line means a version whose
  counters span two keys without a hash tag (8.0.0-alpha.2 or earlier): every rule is skipped, or with `strictFailure`
  every permit is 503. Upgrade; a bucket is one key, `<cosec.key-prefix>:rate-limiter:<rule>:<bucket>`. A `cosec.key-prefix` with
  its own hash tag (`{cosec}`) puts every counter on one slot.

### Control-plane errors

A first platform document that does not let authenticated callers reach `/cosec/control/v1/**` leaves the control plane
unreachable (403 from the pipeline, before any control check) and `cosec-migrate publish` never overwrites: add the
`control-api` policy of https://cosec.ahoo.me/guide/quick-start to the platform document, which is only possible through
the control plane, so a deployment without it must be re-published into an empty Redis or key prefix. A control token
is issued for tenant `(platform)` (or the tenant) and app `cosec.control.app-id`; a token for another app is
`scope-rejected` on a host with an app.

A change refused with `REFERENCES` or `V2` (422) names what it met: `[<id>] is referred to by [<doc>]` for a `<doc>` that
does not refer to it is a publish of `<doc>` that indexed the reference and lost its head write (run `cosec-migrate
repair-references`); `changes between a policy and a boundary` needs `<doc>` moved off it first; `a boundary is not bound`
is a binding of a platform boundary; `an entry's id has no [/]` needs the entry renamed.

A platform or app change that only removes entries and waits in `AWAITING_APPROVAL` with `NOT_ANALYZED`
`policy|binding <id> of <doc>` removes a DENY (`CTL-31`): a second platform principal holding `cosec:approve` approves it.

Problem bodies of the control API: 401 no authenticated caller; 403 the operator lacks the `cosec:*` action on that
document (bound through `cosec:platform-admin`, `cosec:tenant-admin`, `cosec:tenant-viewer` in app `cosec-control`);
404 no such document, change or route; 409 the change's `base` revision is stale, the document is staged by another
change, or the change is in another state; 410 the revision is no longer kept; 412 an operation's `ifMatch` digest
does not match; 422 the candidate does not compile, refers to a missing policy, or uses a reserved id prefix
(`cosec:`, `g:`, `r:`); 428 a DENY with `onMissing: nomatch` needs a confirmation; 503 the decision or store could not
be read; 500 a stored document or record does not decode (a newer version wrote it during a rolling upgrade: `a stored
value does not decode` at `ERROR`, the exception naming the key), else a defect. A 400's `detail` is one of the control
plane's own checks or names the field that does not parse, never an exception's message; a 400 without `detail` is
logged at `INFO` (`Control API <method> <path> (request <requestId>) is 400: <reason>`). `simulate` and `explain` are
authorized on the tenant that decides the request, `(0)` for one without a `principal` (an anonymous request that
names another tenant is 400), and refuse with 400 a header their documents read sent with different values, as
enforcement does, and an `http` path enforcement refuses (a dot segment, a malformed escape, over 2,048 characters or
256 per segment; path parameters are removed as there) or a typed id over those lengths. Any control request whose
path, query or body holds a string with an unpaired UTF-16 surrogate (a JSON escape such as `"\uD800"`) is 400
before its route reads it (`The request holds a string with an unpaired UTF-16 surrogate`, IDN-10). A 429 on the
control API is the pipeline's: a platform rate limit rule on its paths (per tenant with `key: "scope.tenant"`). A
request without a permitted credential is 401 before any of its body is read. Large bodies on WebFlux need
`spring.codec.max-in-memory-size`; on WebMvc a body over `cosec.control.http.max-body-size` is 400. Without a platform document the control API itself answers 503; publish the
first one with `cosec-migrate publish`. A 401, 403, 404, 500 or 503 carries no `detail`: read why in the control
host's log, `Control API <method> <path> by <tenant>/<principal> is <status> (request <requestId>): <reason>` at
`INFO` from `me.ahoo.cosec.control.api`, matching the problem's `requestId`.

A platform change with the `V2` finding `[<id>] is a policy id [tenant:...] already use` adds an id a tenant document
defines or binds; rename the platform policy, or have the tenant remove its own first. A tenant change with the `V2`
finding `[<id>] is a platform policy's id, and this policy is not an exact copy of it` has the id of a platform policy
and different content: a tenant may hold only an exact copy of it (the partition flow), so rename it or make it
identical. While a copy exists the platform's policy cannot be changed (`[<id>] is copied by [tenant:...]`). A
`REVIEW_NEEDED` change event
after a publish means another document the change was checked with moved before the write and the analysis now finds
an escalation nobody approved; the change stays published and needs a person's review.

An approval that no longer counts at publish sends the change back to `AWAITING_APPROVAL` with an `APPROVAL` finding:
the approver's token was revoked since, the provider no longer gives the approver its role or now binds it a DENY, a
tenant principal approved a boundary change or (under four-eyes) a platform principal's change, or the approval names
no credential (`the approval does not say what credential its approver approved with`: from before the upgrade, an
older instance, or a credential without an id), so approve again. A 403 on `:approve` with `an API key cannot approve`
in the log: approvals are a person's, so approve with a token; an API key is never the trust root either. A tenant
principal's `SetBoundary`, its `REPLACE` or rollback that would drop the platform's boundary, and its reject or abandon
of a boundary change are 403: a tenant's boundary is the platform's. A 409 on staging means another change holds the
document's slot; only a break-glass revoke of a live binding, or a change that only narrows by a holder of
`cosec:skip-shadow`, takes it, only another revoke of a live binding takes a revoke of a live binding's slot, and a
holder that cannot be marked `CONFLICTED` keeps it (503). A revoke that answered 503 (its REQUIRED listener down) stays
READY in the slot: revoke the binding again once the listener answers, which publishes it. Only its author abandons a
revoke of a live binding (403 for anyone else, the principal it revokes included). `DELETE /break-glass/{bindingId}`
answers 409 `binding [<id>] still grants: change <id> is <state>` when the revoke did not take effect: revoke again if
it is `CONFLICTED`, read its `findings` if it is `DRAFT`. It answers 404 when the platform document has no binding of
that id: check the id; a 404 on a retry means the binding was removed (the revision history says by which revision).
409 `binding [<id>] is not a break-glass binding`: an ordinary binding, live or expired; remove it with a change. 409
`[platform] is at revision <n>, not <m>`: another write, perhaps another revoke of the same binding, moved the platform
document as the revoke was proposed; check whether the binding is still there (retry, or `GET /documents/platform`). A publish retried after its answer was lost reports
`PUBLISHED`, and so does a holder whose answer was lost when another change comes for its slot. A 403 on `:approve`
with `this one names none` in the log: approve with a credential that has an id. A 503 on publish with `the revocations
of the approver could not be read`: the revocation store failed; the change keeps its approval. `FAILED` events with
`another change took the staging slot before the head write`, `the document moved before the head write` (also a
bootstrap, a migration or an older instance, which may have been publishing the same change) or `... was published
over the same base first, by <id>` are publishes that crossed: rebase the change that lost. Blocking a principal is not
a document change: use the blacklist's `BlacklistSource`, which waits for no slot.

When every tenant grant over every app (a binding with app `*`, an attachment, also one whose condition narrows it to
apps the change does not name) waits for approval as `UNREADABLE`
after an upgrade, and the control plane logs `The app list is missing or incomplete`, the app list is not complete:
run `cosec-migrate repair-references --to-redis <uri> [--cluster] [--key-prefix <p>]` once, after every control-plane
instance and `cosec-migrate` runs the new version. The same after the list was lost, and after the reclamation pass
logs `The app list misses the app documents [...]; it is incomplete again` (an older instance published an app).

A shadow window that stays blocked has a `CANDIDATE_ERROR`: one instance cannot compile the candidate, usually because
it reads an `ext.*` attribute whose provider runs on the control host but not on that instance. Providers and their
declared types must match on every instance and the control host; the change is never published while blocked.

## Reproduce narrowly

- Document problems: decode and compile the exact document
  (`PolicyDocumentCompiler.forPlatform(platform).validate(PolicyDocumentCodec.decode(kind, owner, bytes))`); the
  exception lists every error with its JSON path.
- Decisions: `simulate` / `explain` on the control plane, or an engine-level conformance scenario
  (`test/conformance/src/test/resources/scenarios/`) in a CoSec checkout.
- Transport mapping: `WebAdapterParityTest`, `PipelineTest` (`runtime`).

In this repository, run a focused class first, for example:

```bash
./gradlew :runtime:test --tests "me.ahoo.cosec.runtime.PipelineTest"
```

Use `$cosec-policy-author` to rewrite a confirmed-bad document and `$cosec-integration` to change confirmed-bad Spring
configuration. For diagnosis-only requests, report the root cause and evidence without modifying files.
