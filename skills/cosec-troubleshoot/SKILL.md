---
name: cosec-troubleshoot
description: Diagnose CoSec 8.0 failures - startup failures (Redis eviction check, missing TokenStore or revocation reader, route maps), unexpected 400/401/403/429/503 problem responses, Indeterminate decisions, documents that do not compile or lag, rate limits, and control-plane errors - and explain which statement decided. Do not change behavior unless the user asks for a fix.
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
To see why without reproducing traffic, use the control plane (needs `control-support` and an operator allowed to):
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

- **`Redis may evict CoSec's policy snapshot: maxmemory-policy is {...}, not noeviction`**: set
  `maxmemory-policy noeviction` on every node. **`Cannot tell whether Redis may evict CoSec's policy snapshot (CONFIG
  GET maxmemory-policy failed or is not allowed)`** or `(the CoCache backend ... is not one this check can read)`: a
  managed Redis that hides `CONFIG GET`, or a wrapped CoCache backend. If the operator has set `noeviction`, set
  `cosec.authorization.snapshot.eviction-check=warn`; the instance then logs `!!! ... Starting anyway
  (eviction-check=warn)` at every start. Never suggest `warn` to silence a Redis that really evicts: an evicted app or
  tenant document reads as absent, and its DENYs silently stop applying.
- **`JWT is enabled, but no TokenStore records revoked tokens`** or **`Tokens are verified, but no revocation reader
  is available`**: revocation is always on. Add `cache-support` (Redis token store), or register a `TokenStore` and a
  `RevocationReader` bean, or set `cosec.jwt.enabled=false` on a service that does not verify tokens.
- **`Property [cosec.jwt.secret] must not be blank`**: set the secret (same algorithm and secret as the issuer).
- **`The route map [classpath:cosec-routes.json] (cosec.authorization.routes.location) does not exist`**: add the route
  map, or set the property empty to run without one. A `RouteMapException` names the location and route: malformed
  route, a resource variable the path does not capture, or two routes for one method that may match the same request.
- **`API keys are enabled, but no ApiKeyReader is available`**: `cosec.api-key.store=redis` needs a
  `ReactiveStringRedisTemplate`.
- **Shadow instance id**: `cosec.authorization.shadow.instance-id` may hold only letters, digits, `.`, `_`, `-`.
- **`Decision listener <name> is registered twice`** / **`External attribute <path> is declared twice`**: two beans
  share a listener name or an attribute path.

## Symptoms

### Every request is 503

- No platform document is published yet (the first one comes from `cosec-migrate publish`), or it was lost (evicted):
  an absent platform fails every request closed.
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
- **A DENY's condition errored**, or the tenant's boundary statement errored.
- **Rate limiter cannot count** and the matching rule has `strictFailure: true`.
- **A REQUIRED decision listener failed or timed out** (`cosec.audit.required.timeout`): it turns a permit into 503.
- **The authenticator's dependency failed** (for example the API key or revocation store); a revocation read that
  fails answers "not revoked" instead (the documented exception to fail closed).

### Unexpected 401

- Did the request carry credentials? Tokens come from `Authorization`, then the `authorization` cookie; the query
  parameter only with `cosec.pipeline.allow-query-token=true`. API keys from `X-API-Key`
  (`cosec.pipeline.api-key-header`).
- `token-expired`, `token-revoked` (by token, or every token of the principal issued before a `revokeSubject`, with
  `cosec.authentication.revocation.skew`), `token-invalid` (wrong algorithm or secret: only HMAC256/384/512).
- Anonymous callers belong to tenant `(0)` and have no roles: a `subject: "*"` ALLOW must not require
  `subject.authenticated`.

### Unexpected 403 or missing allow

- Which action and resource did the request get? With a route map, a mapped request is `service:verb` on
  `type/id`; **`http:*` statements never apply to it**. Unmapped requests are `http:<METHOD>` on `http:path:<path>`.
- Is the policy bound in the right document, with the right app and space? `space: ""` is the default space only;
  roles count only in the request's space (`role@space` claims; unsuffixed roles in the default space).
- Is the binding expired (`expiresAt`)? Is the tenant held by a boundary that does not cover the request?
- Conditions: literals keep their JSON type (`true` vs `"true"`); `http.header.*`, `http.origin*`, `http.referer*` and
  `request.attributes.*` are always absent in the 8.0 pipeline today, so an ALLOW on them never matches.
- `scope-rejected`: the client's `CoSec-App-Id` or `CoSec-Space-Id` (see the table).

### Request allowed unexpectedly

- Find the deciding statement in the audit event or with `explain`. Any matching DENY would have won, so the expected
  DENY did not match: check its action namespace (`http:*` vs mapped `service:verb`), resource pattern, condition
  (an absent attribute makes `eq`/`in`/`prefix`/`cidr` false), binding scope, and `onMissing: nomatch` (lapses
  while its provider fails).
- Is the instance on the expected revision? Compare the audit event's snapshot versions with the published head.
- A break-glass binding permits as `cosec:root` until it expires; audit marks such permits.

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

## Reproduce narrowly

- Document problems: decode and compile the exact document
  (`PolicyDocumentCompiler.forPlatform(platform).compile(PolicyDocumentCodec.decode(kind, owner, bytes))`); the
  exception lists every error with its JSON path.
- Decisions: `simulate` / `explain` on the control plane, or an engine-level conformance scenario
  (`conformance/src/test/resources/scenarios/`) in a CoSec checkout.
- Transport mapping: `WebAdapterParityTest`, `PipelineTest` (`runtime`).

In this repository, run a focused class first, for example:

```bash
./gradlew :cosec-runtime:test --tests "me.ahoo.cosec.runtime.PipelineTest"
```

Use `$cosec-policy-author` to rewrite a confirmed-bad document and `$cosec-integration` to change confirmed-bad Spring
configuration. For diagnosis-only requests, report the root cause and evidence without modifying files.
