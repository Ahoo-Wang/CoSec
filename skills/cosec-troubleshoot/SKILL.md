---
name: cosec-troubleshoot
description: Diagnose CoSec 8.0 failures - startup failures (unknown cosec.* keys, pipeline mode, Redis eviction check, missing TokenStore or revocation reader, route maps), instances that stay not ready, services behind a gateway that refuse the forwarded identity, unexpected 400/401/403/429/503 problem responses, Indeterminate decisions, documents that do not compile or lag, rate limits, and control-plane errors - and explain which statement decided. Do not change behavior unless the user asks for a fix.
---

# CoSec Troubleshooting

The human-facing guide is https://cosec.ahoo.me/operations/troubleshooting; this skill adds the agent workflow.

Find the first incorrect step: startup, authentication, scope, snapshot (documents), evaluation, rate limiting, response.
Gather evidence before proposing a document or code change.

## Start with observable facts

Capture: method and path, status, the problem+json body (`type`, `title`, `status`, `requestId`, optional `traceId`)
and the `CoSec-Request-Id` header; the principal's tenant, id, roles and attached policies (token claims, never the
token itself); `CoSec-App-Id` / `CoSec-Space-Id` and the host's `cosec.authorization.scope.app-id`; the route map entry
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
lists the bindings that could permit or deny. External attributes are not fetched there, so a statement reading one is
Indeterminate in an explanation.

## Interpret the status

| Status | Problem `type` slug | Meaning |
|---|---|---|
| 400 | `invalid-request` | The request is ambiguous: a path that does not normalize, two different values of one `CoSec-*` parameter (header vs query), or a route-map capture that cannot be a resource id segment. The engine never saw it. |
| 401 | `unauthorized` | Denied, and the caller is anonymous (no credentials, or an anonymous caller asking for a space). |
| 401 | `token-invalid`, `token-expired`, `token-revoked` | The credentials were rejected (an invalid API key is `token-invalid`); the request was evaluated as anonymous and denied. |
| 403 | `forbidden` | Denied for an authenticated principal: a DENY matched, or nothing allowed (both look the same on purpose). |
| 403 | `scope-rejected` | `CoSec-App-Id` differs from the host's app (or is not a valid app id), or `CoSec-Space-Id` names a space where the principal has no role. |
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

- **`CoSec does not read <n> cosec.* properties, which would do nothing:`** followed by `<key> -> <replacement>` lines:
  a typo or a 7.x key (for example `cosec.authentication.enabled -> cosec.authentication.login.enabled`,
  `cosec.authentication.social.* -> cosec.authentication.login.social.*`). Rename or remove the key; the 7.x table is
  at https://cosec.ahoo.me/operations/upgrading#removed-and-renamed-properties. `cosec.unknown-properties=warn` only
  logs the list; do not suggest it to hide a real typo. On Kubernetes, keys such as `cosec.gateway.service.host` or
  `cosec.gateway.port` (`does not read 8 cosec.* properties`, a crash loop) are service links
  (`COSEC_GATEWAY_SERVICE_HOST`, `COSEC_GATEWAY_PORT_80_TCP_ADDR`, ... for a Service named `cosec` or `cosec-*` in the
  namespace). 8.0.0-alpha.2 and later skip exactly those variables, only from the process environment; a mistyped
  `COSEC_*` variable or the same key in `application.yaml` still fails. On alpha.1, or to keep them out of the pod, set
  `enableServiceLinks: false` on the pod, as `k8s/` does.
- **`<what> needs <artifact> (me.ahoo.cosec:<artifact>) on the classpath: add it to the application's dependencies
  (STA-1).`**: a feature that is on needs a CoSec module that is missing. `A Spring MVC host` needs `cosec-webmvc`,
  `A WebFlux host` `cosec-webflux`, `A Spring Cloud Gateway host` `cosec-gateway` (checked even with
  `cosec.enabled=false`: its filter strips a client's `CoSec-Identity`), `cosec.api-key.store=redis` `cosec-cocache`,
  `cosec.audit.kafka.delivery=required` `cosec-kafka`. Add the named artifact next to `cosec-spring-boot`; 8.0 has no
  starter capabilities (`requireCapability("me.ahoo.cosec:...-support")` from 7.x no longer resolves). Do not suggest
  turning the feature off unless the user does not want it.
- **`cosec.audit.kafka.delivery=required, but no Kafka audit listener was created`** (STA-1): `cosec-kafka` is there,
  but the listener is not: add a `KafkaOperations` bean (`spring-boot-starter-kafka` with `spring.kafka.*`), and keep
  `cosec.audit.enabled` and `cosec.audit.kafka.enabled` on; or use `best-effort`.
- **`cosec-cocache needs a ReactiveStringRedisTemplate bean`** (STA-1): add `spring-boot-data-redis` and configure
  Redis; without it no snapshot is read and rate limits would count per instance.
- **`cosec.pipeline.mode=gateway on a Spring Cloud Gateway host`**: a gateway always runs `enforce`; set `gateway` on
  the services behind it. A binding failure on `cosec.pipeline.mode` means a value other than `enforce` or `gateway`.
- **`cosec.control.audit.kafka.delivery=required, but no Kafka change listener was created`**: add `cosec-kafka`, a
  `KafkaOperations` bean and `cosec.control.audit.kafka.enabled=true`, or use `best-effort`.
- **`Redis may evict CoSec's policy snapshot: maxmemory-policy is {...}, not noeviction`**: set
  `maxmemory-policy noeviction` on every node. **`Cannot tell whether Redis may evict CoSec's policy snapshot (CONFIG
  GET maxmemory-policy failed or is not allowed)`** or `(the CoCache backend ... is not one this check can read)`: a
  managed Redis that hides `CONFIG GET`, or a wrapped CoCache backend. If the operator has set `noeviction`, set
  `cosec.authorization.snapshot.eviction-check=warn`; the instance then logs `!!! ... Starting anyway
  (eviction-check=warn)` at every start. Never suggest `warn` to silence a Redis that really evicts: an evicted app or
  tenant document reads as absent, and its DENYs silently stop applying.
- **`JWT is enabled, but no TokenStore records revoked tokens`** or **`Tokens are verified, but no revocation reader
  is available`**: revocation is always on. Add `cosec-cocache` (Redis token store), or register a `TokenStore` and a
  `RevocationReader` bean, or set `cosec.jwt.enabled=false` on a service that does not verify tokens.
- **`Property [cosec.jwt.secret] must not be blank`**: set the secret (same algorithm and secret as the issuer).
  **`Property [cosec.jwt.secret] has <n> bytes, and HMAC256 needs at least 32`**: the secret must be at least 32, 48 or
  64 UTF-8 bytes for `hmac256`, `hmac384`, `hmac512` (RFC 7518 §3.2); set a longer one on the issuer and every verifier.
- **`The route map [classpath:cosec-routes.json] (cosec.authorization.routes.location) does not exist`**: add the route
  map, or set the property empty to run without one. A `RouteMapException` names the location and route: malformed
  route, a resource variable the path does not capture, or two routes for one method that may match the same request.
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
  `prewarmed`.
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
- **A DENY's condition errored**, or the tenant's boundary statement errored. A DENY on `http.header.<name>` errors when
  the request sent that header more than once with different values (`Header [<name>] arrived with different
  values`); an ALLOW on it then does not match. One value repeated reads as that value.
- **Rate limiter cannot count** and the matching rule has `strictFailure: true`.
- **A REQUIRED decision listener failed or timed out** (`cosec.audit.required.timeout`): it turns a permit into 503.
- **The authenticator's dependency failed** (for example the API key or revocation store); a revocation read that
  fails answers "not revoked" instead (the documented exception to fail closed).
- **Every request with one API key is 503** and the log says the key `is stored in the format before 8.0`: convert
  the keys with `cosec-migrate api-keys` (dry run first; grant the report's memberships and role bindings, then run
  it again without `--dry-run`).

### Unexpected 401

- Did the request carry credentials? Tokens come from `Authorization`, then the `authorization` cookie; the query
  parameter only with `cosec.pipeline.allow-query-token=true`. API keys from `X-API-Key`
  (`cosec.pipeline.api-key-header`).
- `token-expired`, `token-revoked` (by token, or every token of the principal issued before a `revokeSubject`, with
  `cosec.authentication.revocation.skew`), `token-invalid` (wrong algorithm or secret: only HMAC256/384/512; or a
  delegation token, one with an `act` claim, which 8.0 refuses: audit `reasonCode` `DELEGATION_UNSUPPORTED`, IDN-4).
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
- `token-invalid` for a token whose `tenantId` claim is not a string, or whose `attributes` name `apiKeyId` (reserved
  for API keys).
- Anonymous callers belong to tenant `(0)` and have no roles: a `subject: "*"` ALLOW must not require
  `subject.authenticated`.

### Unexpected 403 or missing allow

- Which action and resource did the request get? With a route map, a mapped request is `service:verb` on
  `type/id`; **`http:*` statements never apply to it**. Unmapped requests are `http:<METHOD>` on `http:path:<path>`.
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
- `scope-rejected`: the client's `CoSec-App-Id` or `CoSec-Space-Id` (see the table).

### Request allowed unexpectedly

- Find the deciding statement in the audit event or with `explain`. Any matching DENY would have won, so the expected
  DENY did not match: check its action namespace (`http:*` vs mapped `service:verb`), resource pattern, condition
  (an absent attribute makes `eq`/`in`/`prefix`/`cidr` false), binding scope, and `onMissing: nomatch` (lapses
  while its provider fails).
- Is the instance on the expected revision? Compare the audit event's snapshot versions with the published head.
- A break-glass binding permits as `cosec:root` until it expires; audit marks such permits.

### Service behind a gateway (`cosec.pipeline.mode=gateway`)

The service reads the caller from `CoSec-Identity`, which only the CoSec gateway sets (it strips the client's own). No
header: anonymous. Otherwise never anonymous:

- 400 `invalid-request`: not base64url JSON of format v1, unknown or missing members, over
  `cosec.pipeline.gateway.max-size` (8KB), sent more than once, or a scope that does not resolve here.
- 401 `token-invalid`: unsupported version, no `auth.exp`, or issued in the future (beyond
  `cosec.authentication.revocation.skew`).
- 401 `token-expired`: the credential expired, or the identity is older than `cosec.pipeline.gateway.max-age` (60s)
  plus the skew. Check clocks.
- On the gateway, a 500 with `Request [<id>]: the identity cannot be forwarded.`: the credential never expires (an API
  key without `expires-at`), or the identity is too large or a `cosec.gateway.identity.claims` value is not a string
  (at most 1024 characters), number or boolean. Route metadata `cosec-identity: false` opts a route out.
- A backend that verifies tokens itself gets no `Authorization` header unless
  `cosec.gateway.identity.forward-authorization=true`. A gateway-mode service that issues no tokens should set
  `cosec.jwt.enabled=false`, or startup asks for a JWT secret and a token store.

### Change not visible / snapshot lag

- Instances revalidate every `cosec.authorization.snapshot.revalidate-interval` (10s) and serve local copies up to
  `local-max-age` (30s). Logs: `Snapshot document [<key>] revision <n> has not been confirmed for <lag>` (WARN, over
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

### Control-plane errors

Problem bodies of the control API: 401 no authenticated caller; 403 the operator lacks the `cosec:*` action on that
document (bound through `cosec:platform-admin`, `cosec:tenant-admin`, `cosec:tenant-viewer` in app `cosec-control`);
404 no such document, change or route; 409 the change's `base` revision is stale, the document is staged by another
change, or the change is in another state; 410 the revision is no longer kept; 412 an operation's `ifMatch` digest
does not match; 422 the candidate does not compile, refers to a missing policy, or uses a reserved id prefix
(`cosec:`, `g:`, `r:`); 428 a DENY with `onMissing: nomatch` needs a confirmation; 503 the decision or store could not
be read. Large bodies on WebFlux need `spring.codec.max-in-memory-size`; on WebMvc a body over
`cosec.control.http.max-body-size` is 400. Without a platform document the control API itself answers 503; publish the
first one with `cosec-migrate publish`.

A shadow window that stays blocked has a `CANDIDATE_ERROR`: one instance cannot compile the candidate, usually because
it reads an `ext.*` attribute whose provider runs on the control host but not on that instance. Providers and their
declared types must match on every instance and the control host; the change is never published while blocked.

## Reproduce narrowly

- Document problems: decode and compile the exact document
  (`PolicyDocumentCompiler.forPlatform(platform).compile(PolicyDocumentCodec.decode(kind, owner, bytes))`); the
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
