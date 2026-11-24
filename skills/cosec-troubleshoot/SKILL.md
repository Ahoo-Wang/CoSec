---
name: cosec-troubleshoot
description: Diagnose CoSec 8.0 failures - startup failures (unknown cosec.* keys, pipeline mode, Redis eviction check, missing TokenStore or revocation reader, route maps, actuator endpoints on a gateway), instances that stay not ready, services behind a gateway that refuse the forwarded identity, unexpected 400/401/403/429/503 problem responses, Indeterminate decisions, documents that do not compile or lag, rate limits, control-plane errors, and what a browser client sees (@ahoo-wang/cosec-fetcher, @ahoo-wang/cosec-react: the npm placeholder, refreshes that end the session, refused tenant switches, CORS) - and explain which statement decided. Do not change behavior unless the user asks for a fix.
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

- **A refresh, logout or switch is 503 during a rolling upgrade, logging `a later CoSec wrote the session record`**
  (`SNP-27`): a newer instance wrote a session member this older one must understand (its `crit`); the record is
  refused, never rewritten, and the session kept. Upgrade the instance. `cosec.stored.refused{reason="later"}` counts
  these; `reason="malformed"` means a broken record, not a version. The control API answers such a record of the
  control plane or a shadow window `503` without detail, logging `a later CoSec wrote a stored record`.

## Interpret the status

| Status | Problem `type` slug | Meaning |
|---|---|---|
| 400 | `invalid-request` | The request is ambiguous: a path that does not normalize (a dot segment, a malformed escape, an empty segment before its end such as `//`, an encoded `/`, a `\` or `%5C`, an encoded `;` or a control character, encoded such as `%3B`, `%00` or `%0A` or raw, refused from 8.0.0-beta.5 where clients sent them before: find the path in the audit record's `request.path` or the access log, then send the value in the query, or encode its `%` again), two different values of one `CoSec-*` parameter (header vs query; a blank value beside a non-blank one counts, and only ASCII whitespace is blank), a route-map capture that cannot be a resource id segment, or a header sent with different values that a document of the request's view reads (any statement, policy condition, boundary or rate limit rule, whatever its effect; PIP-12): typically `X-Forwarded-For` from two proxies that each append a line; have them send one line, or read `environment.ip`. The engine never saw it. |
| 401 | `unauthorized` | Denied, and the caller is anonymous (no credentials, or an anonymous caller asking for a space). |
| 401 | `token-invalid`, `token-expired`, `token-revoked` | The credentials were rejected (an invalid API key is `token-invalid`); the request was evaluated as anonymous and denied. |
| 403 | `forbidden` | Denied for an authenticated principal: a DENY matched, or nothing allowed (both look the same on purpose). |
| 403 | `scope-rejected` | The credential declares an app other than the host's, or none (on a gateway the route's metadata `cosec-app-id`, else `cosec.authorization.scope.app-id`): issue the token for this app. Or the credential's app is not a valid app id (blank, `*`, `:` or a control character, over 128 characters), or `CoSec-Space-Id` names a space where the principal has no role. |
| 429 | `too-many-requests` | Permitted, but a rate limit rule's bucket is exhausted; `Retry-After` and `RateLimit-*` headers. |
| 503 | `unavailable` | No decision could be made (Indeterminate), the authenticator's dependency failed, or `cosec.pipeline.timeout` passed. Never retried by a `Retry-After`. |
| 500 | `internal-error` | An exception nothing modelled: a CoSec defect. Read the server log. |
| 5xx without a `type` | none | Not CoSec's: its refusals are always `application/problem+json` with a `type` under `cosec.ahoo.me/problems/`. See "A permitted request answers 500 from the gateway" below. |

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

- **`PIP-34: 2 ProblemRenderer beans of the application, [...]`**: a refusal has one body, so the starter takes one
  `ProblemRenderer`. Keep one, or one that delegates.
  https://cosec.ahoo.me/operations/troubleshooting#startup-fails-on-two-problem-renderers
- **A gateway restarts once right after `kubectl apply`**, and `kubectl logs --previous` ends with
  `RedisConnectionException: Unable to connect to redis/...`: it started before Redis was reachable (the overlay is
  applied whole). Startup fails closed without the store of record, and the kubelet restarts it until Redis answers: no
  fix. A count that keeps growing is Redis unreachable (address, credentials, network policy). To avoid the one restart,
  start it after Redis is ready (https://cocache.ahoo.me/operations/).
  https://cosec.ahoo.me/operations/troubleshooting#a-gateway-restarts-once-right-after-it-is-deployed
- **`cosec.authorization.snapshot.revalidate-interval (<value>) must be ...`**: the periodic revalidation is the only
  bound on a lost invalidation (SNP-6), so the interval must be at least 1 second, at most 5 minutes, and,
  with `fail-closed.tenants`, less than `fail-closed.max-lag`. Use a value within the bounds; never
  suggest turning revalidation off.
- **`cosec.key-prefix [<p>] holds ...: a key prefix is letters, digits, '_', '.' and '-' (cosec), or ... ({cosec})`**
  (SNP-9): a prefix that could nest inside another deployment's. A trailing `:` names the same keys: remove it. A `:`
  inside it: move the keys to an allowed prefix (stop the writers, copy with their TTL, switch). Braces are a hash tag
  only around the whole prefix.
- **`CoSec does not read ... cosec.authorization.snapshot.key-prefix`** (or `cosec.authorization.cache.key-prefix`,
  `cosec.api-key.key-prefix`): one `cosec.key-prefix` replaced them (SNP-9), and they fail startup as unknown keys. Which
  families move, from where to where, is at https://cosec.ahoo.me/operations/upgrading-previews#from-8-0-0-alpha-2. Have the token
  revocations and API keys copied to the new keys first (`COPY`, or `RENAME`, which keeps the expiry): a revocation
  left under the old prefix is no longer read, so the token it revoked would be accepted again. Then set
  `cosec.key-prefix` to the snapshot documents' prefix and remove the old properties; never suggest only renaming them.
- **A document or a route map does not compile on a reserved character** (`DOC-15`): `'<c>' is reserved for a future
  pattern syntax; write \<c> for the character`, at the position given. One of `[ ] ( ) | ! ^ $ \` is unescaped in a
  path, typed resource or action pattern; write it escaped (doubled backslash in JSON). Patterns that match no request
  path (a `\`, `;`, control character, dot segment, a segment over 256 characters) are refused the same way. After an
  upgrade from 8.0.0-beta.8 such a stored document is installed as failed (`503` for what reads it): before upgrading,
  `cosec-migrate check-patterns --to-redis <uri>` names each such pattern and how to write it.
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
- **`CoSec cannot read <n> cosec.* switch(es); a value it cannot read would switch the feature off without a word:`**
  followed by `<key>=[<value>]: write true or false` (or `enforce or gateway`, `memory or redis`): a switch such as
  `cosec.enabled`, `cosec.jwt.enabled`, another `*.enabled`, `cosec.pipeline.mode` or `cosec.api-key.store` was written
  `1`, `on`, `yes`, an empty string (a Helm or Compose template that left the variable empty) or `true` with spaces.
  Write `true` or `false` (the enum's value), or remove the key to take its default. Never suggest `cosec.enabled=false`
  to get past it.
- **`CoSec is off: cosec.enabled=false.` (WARN, not a failure):** nothing is protected by CoSec on that application;
  ask whether that is intended.
- **`This is a Spring Cloud Gateway host: CoSec decides the requests of gateway routes only. ... would be answered with
  no CoSec decision: <beans> ... (STA-13)` (startup fails):** the application's own controllers, functional routes or
  handler mappings (a WebSocket endpoint's) are outside CoSec; move them behind the gateway, or list the ones something else protects, by bean name, in
  `cosec.gateway.unprotected-handlers` (never a pattern). Never list a handler just to start.
- **`No platform document under [<prefix>:snapshot:platform] in Redis <host:port/db>: this instance is not ready ...`
  (WARN, repeated every 6 revalidation intervals):** the instance waits for its platform document. Check, in this order, that
  something was published (`cosec-migrate publish --key-prefix <prefix>`), that `cosec.key-prefix` is the publisher's, and
  that the Redis database and host are the publisher's.
- **`<what> needs <artifact> (me.ahoo.cosec:<artifact>) on the classpath: add it to the application's dependencies
  (STA-1).`**: a feature that is on needs a CoSec module that is missing. `A Spring MVC host` needs `cosec-webmvc`,
  `A WebFlux host` `cosec-webflux`, `A Spring Cloud Gateway host` `cosec-gateway` (checked even with
  `cosec.enabled=false`: its filter strips a client's `CoSec-Identity`), `cosec.api-key.store=redis` `cosec-cocache`,
  `cosec.audit.kafka.delivery=required` `cosec-kafka`, `cosec.cosid.generator` `cosec-cosid`. Add the named artifact next to `cosec-spring-boot`. Do not
  suggest turning the feature off unless the user does not want it.
- **`CoSec is on (cosec.enabled), but this web host registers none of its gates ...`** (STA-1): the adapter's module
  is there but no CoSec filter is registered, so every request would pass undecided. Usually the application excluded
  `CoSecPipelineWebAutoConfiguration`, or declared a filter of another type under the adapter's bean name. Keep the
  auto-configuration, or register CoSec's filter itself; never suggest turning CoSec off unless the user wants no gate.
  On a Spring Cloud Gateway host in `cosec.pipeline.mode=gateway` the message says that a gateway host decides only in
  enforce mode: remove the mode (it is for services behind the gateway).
- **`cosec.control.enabled=true, but cosec.pipeline.mode=gateway`** (STA-1): a service behind a gateway wires no
  control plane. Run the control plane on an `enforce` host, or remove the key.
- **`cosec.audit.kafka.delivery=required, but no Kafka audit listener was created`** (STA-1): `cosec-kafka` is there,
  but the listener is not: add a `KafkaOperations` bean (`spring-boot-starter-kafka` with `spring.kafka.*`), and keep
  `cosec.audit.enabled` and `cosec.audit.kafka.enabled` on; or use `best-effort`.
- **`STA-5: <n> CorsConfigurationSource beans of the application`**: the starter's CORS filter takes one source. Keep one,
  or declare a `CorsFilter` bean of your own.
- **`cosec.audit.kafka.delivery=[<value>] is neither required nor best-effort.`**: write one of the two.
- **A message that names `server.request.header.forwarded.strategy` (or the deprecated `server.forward-headers-strategy`), `RemoteIpValve` or `max-trusted-index`**: the host cannot say
  which party writes the client address. `framework`, or a `ForwardedHeaderFilter` bean that rewrites the request: use
  `web-server` and configure the proxies on the server, or `none` and set `cosec.authorization.remote-ip.max-trusted-index`;
  Tomcat with `web-server`: remove `max-trusted-index`; Reactor Netty or Jetty with `web-server` and index `0`: set the number of
  proxies, or `none`.
  https://cosec.ahoo.me/operations/troubleshooting#startup-fails-on-the-client-address
- **`Social sign-in, whose states are kept in Redis ..., needs cosec-cocache`** (STA-1): add `cosec-cocache`, declare
  your own `SocialStateStore` bean, or set `cosec.authentication.login.social.enabled=false`.
- **Sign-in refused with "too many sign-ins in progress: try again shortly", and a WARN `Refused <n> [<provider>]
  sign-in start(s)`**: the deployment holds its bound of sign-ins in progress (a 64th of Redis's memory at 512 bytes a
  sign-in, or 100,000 when Redis reports none). Starting a sign-in is anonymous: rate limit the start route at the edge.
  https://cosec.ahoo.me/operations/troubleshooting#social-sign-in-is-refused-too-many-sign-ins-in-progress
- **`cosec-cocache needs a ReactiveStringRedisTemplate bean`** (STA-1): add `spring-boot-data-redis` and configure
  Redis; without it no snapshot is read and rate limits would count per instance.
- **`cosec.pipeline.mode=gateway: this service believes any CoSec-Identity header it receives ... State it once it is
  true: cosec.pipeline.identity.only-gateway-can-reach=true`** (`STA-7`): a service in gateway mode starts only when the
  operator states that nothing but the gateway can reach it. `It is [<value>]` names a value that is not `true`. Make the
  network true (NetworkPolicy, mTLS, private network) and write the line; never suggest writing it just to start.
- **The same message beginning `cosec.pipeline.caller=forwarded-identity:` (or `the web gate of this host is an
  IdentityGate bean of the application`)** (`STA-9`): a host that decides with policies and takes its caller from the
  identity, the `cosec-control` image or an IAM service that embeds the control plane, believes the header the same way.
  The key to write depends on who reaches it: `only-gateway-can-reach=true` when only the gateway does,
  `only-trusted-callers-can-reach=true` when services call it directly (the image has a service port by default, so the
  second; the gateway's key is refused beside a service port). The network first (a NetworkPolicy per port, as in
  `k8s/control/`), then the line. On Kubernetes the overlay writes the line, never `k8s/control/` itself.
- **The Job `cosec-control-boundary-check` fails** (`k8s/control/boundary-check.yaml`): `FAIL reached the control host at
  <address>:<port>` means a pod no policy admits reached it, so the network plugin does not enforce the policies and the
  overlay's boundary statement is false; anyone in the cluster can state any principal. Take the statement out of the
  overlay (the host stops) until the policies are enforced; never delete the Job to make it pass. `FAIL could not tell`
  means the host never listened within ten minutes, or the plugin refuses instead of dropping: check by hand. Delete the
  Job before every apply (its pod template cannot change, and a finished one does not run again), or the verdict is the
  last apply's.
- **`cosec-control-server runs the control plane; cosec.control.enabled=false ...`, or `cosec-control-server verifies no
  credential and issues no token ... cosec.jwt.enabled=true`** (also `cosec.api-key.enabled`,
  `cosec.authentication.login.enabled`; `STA-10`): the `cosec-control` image refuses them. Remove the key; a control plane
  that verifies tokens or signs people in is an application of your own.
- **`cosec.pipeline.mode=gateway: spring.mvc.hiddenmethod.filter.enabled=true` (or the WebFlux property, or `a
  HiddenHttpMethodFilter bean, [name]`)** (`STA-6`): the gateway decided the request line's method and the service
  would run the one `_method` names. Remove the property or the bean.
- **`cosec.authentication.login.refresh.path=... serves the refresh route, and nothing says how a refreshed token gets its
  roles ... cosec.jwt.session.memberships-on-refresh=from-token`** (`STA-8`): the starter serves the refresh and needs a
  choice. Ask which the operator wants: a `MembershipRefresh<RefreshTokenCredentials>` bean that reads the member's roles
  again (a removal applies within `cosec.jwt.token-validity.access`), or the property, which keeps the token's roles (a
  removal applies at the next sign-in, `cosec.jwt.session.lifetime` at the latest). `revokeSubject` ends tokens at once in
  both. `It is [<value>]` names a value that is not `from-token`. Never write the property just to start.
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
  credential is ever revoked). **`A token revocation is kept for ...: the longer of cosec.jwt.token-validity.access
  (...) and cosec.jwt.token-validity.refresh (...), plus cosec.authentication.revocation.skew (...), shorter than
  cosec.control.approval.expire-after`**: raise a token validity or lower the approvals' expiry.
- **`Redis may evict CoSec's policy snapshot: maxmemory-policy is {...}, not noeviction`**: set
  `maxmemory-policy noeviction` on every node. **`Cannot tell whether Redis may evict CoSec's policy snapshot (Redis
  refused INFO ...)`** or `(the CoCache backend ... is not one this check can read)`: a Redis user without `+info`, a
  Redis that denies `INFO`, or a wrapped CoCache backend. `(Redis did not answer INFO memory in 5 attempts ...)` is not
  a permission: each read timed out or lost its connection though the check retried as CoCache's does; Redis is
  unreachable, or the process was starved of CPU while it started. If the operator has set `noeviction`, set
  `cosec.authorization.snapshot.eviction-check=warn`; the instance then logs `!!! ... Starting anyway
  (eviction-check=warn)` at every start. Never suggest `warn` to silence a Redis that really evicts: an evicted app or
  tenant document reads as absent, and its DENYs silently stop applying.
- **`Cannot read maxmemory-policy (INFO memory) to check that the authoritative caches [...]`** or **`Authoritative
  caches [...] keep their only copy in Redis, but maxmemory-policy {...} can evict them`**: CoCache 8's own check of
  its `Store`s, which `eviction-check=warn` does not reach. Since CoCache 8.2.0 the message says why: `Redis refused
  INFO`: allow `INFO`, or, once `noeviction` is set, set `cocache.redis.assume-maxmemory-policy=noeviction`; `Redis did
  not answer in 5 attempts`: a timeout or a connection failure after retries, not a permission: Redis unreachable, or
  the process starved of CPU while it starts. `allkeys-*` is never accepted.
- **`cosec.authentication.login.refresh.path is set, but the RefreshTokenAuthentication bean refreshes <class> ... (IDN-25)`**:
  the application's `RefreshTokenAuthentication` takes credentials of its own class, which the served refresh route
  cannot present; serve that refresh from the application's own route and unset the path, or declare it over
  `RefreshTokenCredentials`.
- **`API key format [<n>] is not 3.`** (a request with that key is `503`): `null` is a 7.x key, convert it with
  `cosec-migrate api-keys`; `2` is a key an 8.0 pre-release stored, which nothing converts: issue a new key, delete
  the old one. **`[<key>] was stored by an 8.0 pre-release in format <n>`** from `cosec-migrate api-keys`: the same key;
  the run is refused until it is gone. **`its principal [<p>] is named by a DENY or the blacklist, which a principal of its
  own ... would escape (MIG-6)`**: a DENY reads the key's principal by `subject.id` or is bound to it, or `--blacklist`
  blocks it; keep the key on its principal (in place with `--memberships`, or `--grant-to-principal <p>`). Without
  `--blacklist`, block each moved key's `blockIfPrincipalBlocked` value if its principal is blocked. **`[<key>] is not an API key: its <member> is not
  ...`**: the stored record has a member of the wrong type; fix it at the source or delete the key.
- **`Cache[<CoSec cache>] - ... locked by cosec`**: a `cocache.caches.[name].*` entry or a `CacheSpecCustomizer` sets a
  field CoSec locks on its caches (a store of record's `ttl` or `unknown-read`, the snapshot store's `local-max-age`,
  any `key-prefix`). Remove the setting or exclude CoSec's caches; never suggest `unlock` for these, whose constrained
  fields keep their values anyway. **`cocache.caches sets unknown keys [...]`**:
  CoCache's own check of a misspelled or inapplicable per-cache key; fix the key (`cocache.caches-validation: warn`
  only logs it). **`cocache.caches configures undeclared caches [...]`**: the name matches no cache, usually a typo;
  CoSec publishes every name it may create, so an entry for a CoSec cache this instance does not create starts. **`Cache[<name>] - its
  storage keys (...) overlap those of Cache[<other>]`**: an application cache and a CoSec cache would share Redis
  keys; give the application's cache another prefix or namespace (`cosec.key-prefix` moves every instance's data).
- **`Read-only store of Keyspace(...): Redis denied the command [evalsha_ro]`**, **`allow the key pattern [%R~...]`** or
  **`allow the channel pattern [&...]`**: an instance without the control plane (no `cosec-control`, or `cosec.control.enabled=false`) reads the
  documents and the candidates read-only (SNP-18), and its Redis user lacks `+evalsha_ro +eval_ro`, read permission on `<s>:snapshot:*` or
  `<s>:candidate:*`, or the channels `&<s>:*`; add what the message names (8.0.0-beta.1's example user lacks the two
  commands). **`Redis denied publishing to the invalidation channel[<s>:inv:snapshot]`**: a process with `cosec-control`
  writes the documents but was given a read-only data-plane user; give it a user that writes `<s>:*`, or, on a
  data-plane instance whose image carries the control plane, set `cosec.control.enabled=false`.
  **`cosec.control.enabled must be true or false, not [...]`**: the key is read strictly; write `true` or `false`.
  **`cosec.control.enabled=true needs cosec-control`** (or `cosec-cocache`): add the artifact, or remove the key. **`CoSec reads
  <cache> read-only in this process ... (SNP-18)`**: a `cocache.caches.*.read-only` entry or a customizer tries to change
  what the classpath decides; remove it.
  **An `IllegalStateException` naming a channel pattern** such as
  `[&cosec:control:inv:*]`: the Redis ACL denies the cache's invalidation channel; allow `&<prefix>:*`.
- **`CoSec's Redis template [<bean>] reads from replicas (ReadFrom...)`**: the Lettuce client behind the template CoSec
  injects (the only `StringRedisTemplate` / `ReactiveStringRedisTemplate`, or the `@Primary` one), named in the message,
  reads from replicas (`spring.data.redis.lettuce.read-from`, or a `LettuceClientConfigurationBuilderCustomizer` that
  sets `ReadFrom`). Remove it or set `upstream` on that factory. Other data may read from replicas through a template
  and connection factory of its own that is not the one CoSec injects (not `@Primary`). Never suggest replica reads
  for CoSec: Redis is its store of record, and a lagging replica can hand back a revoked API key.
  https://cosec.ahoo.me/operations/troubleshooting#startup-fails-because-redis-is-read-from-replicas
- **`CoSec's tokens are on, but <stores> are missing`**: revocation is always on, and the message names every missing
  store of the three (`TokenStore`, `RevocationReader`, `TokenFamilyStore`). Add `cosec-cocache` (all three on Redis),
  or register a bean of each missing one, or set `cosec.jwt.enabled=false` on a service that does not verify tokens.
  **`A TokenVerifier bean turns token authentication on ... no RevocationReader is available`**: a service with its own
  `TokenVerifier` and no `cosec.jwt.secret` has tokens on and needs only the reader: `cosec-cocache` on the issuer's Redis,
  a `RevocationReader` bean, or `RevocationReader.NONE` where no one revokes a token.
  **`Tokens are verified against RevocationReader.NONE`** (`WARN`): revoked tokens are accepted until they expire;
  right only where no one revokes a token. A service verifying an issuer's tokens reads the issuer's revocations
  (`cosec-cocache` on the issuer's Redis, same `cosec.key-prefix`; https://cosec.ahoo.me/scenarios/resource-server).
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
  On the `cosec-gateway-server` image the message ends `The cosec-gateway-server image ships none, so as shipped it cannot use API
  keys`: the image has no `MembershipProvider` bean, so API keys need an image derived from it that adds one.
  Add `cosec-cocache` and a `MembershipLoader` bean (the starter then wires `CoCacheMembershipProvider`), or declare a
  `MembershipProvider`. Checked only in `cosec.pipeline.mode=enforce`, where keys are read.
- **`API key [<id>] needs exactly one of roles ... and uncapped: true`**: a configured key states its cap (`roles`,
  `[]` for none) or `uncapped: true`, never neither or both. **`cosec.api-key.enabled=[x] is neither true nor false`**:
  write `true` or `false`.
- **`API key [<id>] needs a tenant-id`**, or a binding error on `cosec.api-key.keys[n].policies`/`.attributes`: keys
  carry only a tenant, a principal and a role cap (`roles`) now; grant roles to the principal through the provider.
- **`Extended TCP keepalive options could not be applied. Native transports (io_uring or epoll) ...`** (macOS only):
  Lettuce chose its kqueue transport, which cannot apply the keepalive options it is asked for. Run with
  `-Dio.lettuce.core.kqueue=false` (NIO); Linux and the gateway image are unaffected. Never suggest dropping the
  keepalive options. https://cosec.ahoo.me/operations/troubleshooting#startup-fails-on-macos-with-extended-tcp-keepalive-options
- **Shadow instance id**: `cosec.authorization.shadow.instance-id` may hold only letters, digits, `.`, `_`, `-`.
- **`Decision listener <name> is registered twice`** / **`External attribute <path> is declared twice`**: two beans
  share a listener name or an attribute path.

## Symptoms

### A check in code is false though the documents permit it

- `decide` answers `Deny(null)` before reading any document when the context's request has ended (`PIP-26`: kept
  after the response was written; check within the request, which a streamed body or an async servlet request still
  is), when the pipeline did not make the context (`PIP-27`: the readers return none for a hand-made or mocked one; in
  tests use `PipelineFixture.securityContext` or `PermittedRequest`), or when the action has no `:` or names what
  enforcement refuses (`PIP-15`).
  https://cosec.ahoo.me/operations/troubleshooting#a-check-answers-false

### Every request is 503, or the instance is not ready

The `cosec-control` image is also unready while its control store is in the format before 8.0.0-beta.5 (`cosecControlStoreFormat`
is in its readiness group, with a `format` detail and one `ERROR` at startup): run `cosec-migrate repair-references`
with the host stopped. Its probes are on the management port 8081, never on 8080, where the pipeline refuses a kubelet.

- No platform document is published yet (the first one comes from `cosec-migrate publish`; `cosec-migrate bootstrap`
  writes only the `(platform)` tenant's document), or it was lost (evicted): an absent platform fails every request
  closed. Readiness refuses traffic until the platform document and every `prewarm-apps`/`prewarm-tenants` document is
  installed; the `cosecSnapshot` health indicator shows `platform` (`installed`, `invalid`, `failed`, `absent`,
  `loading`), `prewarmed` and `invalid`, and only it belongs in the readiness group. Beside it, `cosecSnapshotEviction` is `DOWN` with an
  `eviction` detail while Redis may evict the snapshot (each instance reads `maxmemory-policy` every
  `revalidate-interval` on its own thread, and logs `ERROR` from `EvictionPolicyMonitor` when that starts): an alert,
  never a readiness signal (it also turns the aggregate `/actuator/health` DOWN, so load balancers poll the readiness
  group); requests are still decided, but set `noeviction` again before an app or tenant document is
  evicted. `Cannot tell whether Redis may evict` there is `eviction-check=fail` on a Redis that denies or times out
  `INFO`: allow `+info`, or set `warn` when the operator has set `noeviction`, under which it is `UP` with
  `eviction: unknown`. https://cosec.ahoo.me/operations/troubleshooting#cosecsnapshoteviction-is-down
- A document the request needs does not compile: its new revision is installed as failed, the decision's cause is
  `DocumentInvalid` at that revision (metric `cause=document_invalid`, health `invalid` (the count) and
  `invalidFirst: ["<doc>@<n>", …]` (the first ten)), and every request that needs it fails closed; the previous revision is not kept. The log says it once per revision at `ERROR`:
  `Snapshot document [<doc>] revision <n> does not compile against ...`, caused by a `DocumentCompileException` listing
  JSON paths. Usually a document a newer CoSec wrote during a rolling upgrade, or a direct write to the store. Compile the stored
  document yourself (see "Reproduce narrowly") to see the errors. Documents compile against the platform's `limits` and
  `attributes`, so a platform change can make an app or tenant document stop compiling.
- A compilation does not end (R2D-11): `Compiling snapshot document [<doc>] revision <n> did not end within <budget>`
  at `ERROR`. The load fails at the next revalidation tick (cold: 503; reload: the previous revision is kept); the
  revision is not compiled again while that compilation runs, each such revision holding one compile thread. Usually
  a revision written to Redis outside the control plane: publish a corrected one.
  https://cosec.ahoo.me/operations/troubleshooting#a-compilation-does-not-end
- Redis is unreachable for a document not yet held locally (the cold-load timeout, 2s: 40 % of `cosec.pipeline.timeout`), or the snapshot lags
  beyond `fail-closed.max-lag` for a tenant in `cosec.authorization.snapshot.fail-closed.tenants`.

### One principal, tenant or route gets 503

- **Memberships unavailable**: the token carries `cosec.membership: "provider"` and no `MembershipProvider` bean
  exists, or it failed or exceeded the membership timeout (10 % of `cosec.pipeline.timeout`); or the token has the marker together with
  `roles`/`policies` claims, or an unknown marker.
- **Missing or ambiguous policy**: the token's `policies` claim names a policy that does not exist or is not
  `attachable`; a tenant binding names a platform policy that was removed; or a tenant defines a policy id the platform
  also defines.
- **External attribute unavailable**: a statement reads `ext.<provider>.<name>` with `onMissing: indeterminate`
  (default) and the provider failed, timed out (the attribute timeout, 6 % of `cosec.pipeline.timeout`) or is not registered on this
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
  the log: see Rate limiting; without Redis, a full `LocalRateLimiter`: see Rate limiting).
- **A REQUIRED decision listener failed or timed out** (20 % of `cosec.pipeline.timeout`): it turns a permit into 503.
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
  alpha wrote through CoCache 6. Stop every instance and move it with the `cosec-migrate` of 8.0.0-alpha.4 (dry run
  first): 8.0.0's no longer has the command. Never delete those keys by hand: a deleted revocation accepts its token
  again.
- **`cosec-migrate publish` refuses** with `Refused: [<p>:meta:cocache8] marks a CoCache 6 to 8 upgrade`: an
  8.0.0-alpha upgrade stopped after it deleted the documents, which are in its export. Finish it with the 8.0.0-alpha.4
  `cosec-migrate` (`publish --resume`, `--in` on the export the message names), or check every document the marker
  lists is published and delete the key.
- **Every request with one API key is 503** and the log says `API key format [null] is not 2.` (`null`: the record has
  no `format`, as a key written by 7.x): convert
  the keys with `cosec-migrate api-keys` (dry run first; grant the report's memberships and role bindings, then run
  it again without `--dry-run`).

### Unexpected 401

- Did the request carry credentials? Tokens come from `Authorization`; the query parameter only with
  `cosec.pipeline.allow-query-token=true`, the `authorization` cookie only with
  `cosec.pipeline.allow-cookie-token=true`. API keys from `X-API-Key`
  (`cosec.pipeline.api-key-header`).
- Every refresh `401 token-expired` (or `unauthorized`) and clients signed out at expiry: no statement permits an
  anonymous `POST` of the refresh route (the expired token reads as anonymous, PIP-6). The starter's served route logs
  `WARN ... refresh.path=<path>: no statement permits an anonymous POST of it` and shows `denied since` on the
  `cosecLoginRoutes` health indicator; permit `http:POST` on the path to `*`.
- `token-expired`, `token-revoked` (by token, or every token of the principal issued before a `revokeSubject`, with
  `cosec.authentication.revocation.skew`), `token-invalid` (wrong algorithm or secret: only HMAC256/384/512; or a
  delegation token, one with an `act` claim, which 8.0 refuses: audit `reasonCode` `DELEGATION_UNSUPPORTED`, IDN-4;
  or, intermittently at second boundaries, an issuer's clock ahead by more than `cosec.authentication.revocation.skew`:
  log `Token refused as not valid yet`, decision event `subject.rejected` `TOKEN_NOT_YET_VALID`, IDN-29;
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
- `token-invalid` for a token whose `sub` is white space only (IDN-38): a principal id is never blank. CoSec's own
  `JwtTokenConverter` refuses to sign one (login fails with `A token has a subject that is not blank`); an
  `Authenticator` of your own that makes a blank `PrincipalId` throws, so the request is `503`. An API key record whose
  `principalId` is blank does not load (`503`), and a document binding a blank principal does not compile. Ids are not
  trimmed: `" alice "` is its own id, and a binding of `alice` does not match it.
- `token-invalid` for a token whose `tenantId` or `aud` holds `/` (`acme/west`, SCP-13): a tenant or app id is one path
  segment, so it names neither `acme/west` nor `acme`. Behind a gateway, such a forwarded identity is `400
  invalid-request`; a preview's document of such a tenant is left out of `GET /documents` with a warning. Rename the
  tenant or app; `cosec-migrate convert` lists 7.x ones as `SLASH_IN_ID`.
- Control-plane `503` logged `... does not read: ...` naming an id with `/`: a record an 8.0 preview stored under
  such an id fails whole (SCP-10); remove that id's Redis keys as `upgrading.md` (from 8.0.0-alpha.4) lists them.
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

### A permitted request answers 500 from the gateway

The body is Spring Boot's default error object (`timestamp`, `path`, `status`, `error`, `requestId`), with no `type`, so it is
not a CoSec problem. The gateway log has `500 Server Error for HTTP GET "<path>"` at `ERROR` from
`AbstractErrorWebExceptionHandler`, followed by `java.net.UnknownHostException: Failed to resolve '<host>'`: the host of
the route's `uri` does not resolve (a Kubernetes Service missing from the gateway's namespace; the demo route
`cosec-auth` of `k8s/` points at one it does not create). CoSec permitted the request, so nothing in the documents caused
it: fix the route's `uri`, or create the Service or the DNS name.
https://cosec.ahoo.me/operations/troubleshooting#a-permitted-request-answers-500-with-the-gateways-own-error-body

### A refusal has CoSec's body though a `ProblemRenderer` is declared

`WARN` `Request [<id>]: the ProblemRenderer [<class>] failed with <exception>, so the <status> <slug> refusal is
answered with CoSec's own body (PIP-34)`, and `cosec.problem.render.failed` rising: the application's renderer threw or
returned no bytes, and CoSec answered with its own body. The request was refused anyway, with CoSec's status: nothing
was let through. Fix the renderer (a default branch for every `ProblemType`, a later one included). No such line and
never the renderer's body: it is not wired (not a bean, or not the only one).
https://cosec.ahoo.me/operations/troubleshooting#a-refusal-is-answered-with-cosecs-body-though-a-renderer-is-declared

### Request allowed unexpectedly

- Find the deciding statement in the audit event or with `explain`. Any matching DENY would have won, so the expected
  DENY did not match: check its action namespace (`http:*` vs mapped `service:verb`: a platform DENY on `http:*` stops
  covering a path the moment a route map maps it, and a host with a route map logs `... mapped route(s) escape its
  http: statements ...` at WARN for such a document, https://cosec.ahoo.me/scenarios/route-maps), resource pattern, condition
  (an absent attribute makes `eq`/`in`/`prefix`/`cidr` false), binding scope, and `onMissing: nomatch` (lapses
  while its provider fails).
- Is the instance on the expected revision? Compare the audit event's snapshot versions with the published head.
- A break-glass binding permits as `cosec:root` until it expires; audit marks such permits.

### In the browser: `@ahoo-wang/cosec-fetcher` and `@ahoo-wang/cosec-react`

Capture the installed versions (`npm ls @ahoo-wang/cosec-fetcher @ahoo-wang/cosec-react @ahoo-wang/fetcher-cosec`),
the server's CoSec version, the error's `name` with its `problem` and `status` (never its `exchange`, which holds the
`Authorization` header), and the order of the calls on the wire (`<path> <status>`). Wiring: `$cosec-client`.

- **The install warns `deprecated ... Placeholder. Install the beta` and the import does not resolve**: installed
  without a tag before 8.0 GA, which gets `latest`, a `0.0.0` placeholder with no code. Install the server's line:
  `@beta`, `@rc`, or the exact version.
- **The client is `8.0.0-beta.n` and the server a release candidate (or the other way)**: a tag stays on its line,
  so `@beta` still installs the last beta once RCs are out. Install `@rc` or the server's exact version; the client is
  tested against the `contract/` of its own version.
- **The old `@ahoo-wang/fetcher-cosec` (or `@ahoo-wang/fetcher-react`'s security hooks) against an 8.0 server**: it
  speaks CoSec 7's contract. What shows: `403 scope-rejected` on every request of a host with an app though the client
  sets `appId` (8.0 reads no app header; the app is the token's `aud`); users signed out when the refresh route
  answers `429` or `503` (the revocations cannot be read, `IDN-14`; a refresh of a switched session that reaches an
  8.0.0-beta.7 instance mid-upgrade, `IDN-36`), since it ends the session on any failed refresh; a refresh after every
  `token-revoked` or `token-invalid`. Move to `@ahoo-wang/cosec-fetcher` and `@ahoo-wang/cosec-react`.
- **Every session ends at its first access-token expiry**, the request failing with `RefreshTokenError` whose
  `problem` is `token-expired` (or `unauthorized`): the refresh route is closed to anonymous callers (see "Unexpected
  401"). If the session's lifetime really is over, the same error comes at `cosec.jwt.session.lifetime`.
- **A refresh is answered `401 token-revoked` and the user is signed out**: one pair was refreshed twice, which ends
  the session on every tab and device (`IDN-16`). Two holders that refresh on their own: the pair in `sessionStorage` or per-tab memory with
  several tabs, two processes with one pair, a storage declared `sharedAcrossTabs: true` that the tabs do not share,
  or a page without `navigator.locks` (not a secure context). Or a retry later than the server's leeway:
  `rotationLeeway` set above `cosec.jwt.session.rotation-leeway`. The audit records the pair presented again.
- **A refresh before every request**: the access token is expired by the device's clock as soon as it is stored, so the
  device clock is ahead of the server's by more than the access token's validity, or `TokenStorage`'s `earlyPeriod`
  (seconds) is not shorter than that validity. Refresh calls that call themselves: a custom `TokenRefresher` on the
  configured `Fetcher` that does not set `IGNORE_REFRESH_TOKEN_ATTRIBUTE_KEY`; use `CoSecTokenRefresher`.
- **Requests fail with `RefreshUnavailableError` and the user stays signed in**: the refresh had no verdict, and the
  pair is kept. Its `problem`/`status`: `429` (the route is not called again for that pair until its `Retry-After`, at
  most 60 seconds), `503` (the session or revocation store; retry later), `400 invalid-request` on every request (a
  custom refresher that sends more than the exact pair, or not as JSON), no status (a network error: see CORS below).
- **A call to another origin gets no token, and its `401` neither refreshes nor signs out**: `isTrusted` defaults to
  `sameOriginTrust` (the absolute `baseURL`'s origin and the page's). Name the origin in an `isTrusted` predicate if it
  must get the token. A `401 unauthorized` from CoSec with a token stored is usually this: the request went bare.
- **A network error (`TypeError: Failed to fetch`) where a `401`, `403` or `429` was expected**, or
  `retryAfterSeconds` answering `undefined` on a `429`: a cross-origin refusal without CORS headers, or headers not
  exposed. The server needs a `CorsConfigurationSource` bean (the starter then puts the CORS filter before CoSec;
  without it a preflight is decided as an anonymous `http:OPTIONS`) allowing `Authorization`, `CoSec-Device-Id`,
  `CoSec-Request-Id`, `CoSec-Space-Id` and exposing `CoSec-Request-Id`, `RateLimit-*`, `Retry-After`. The client sends
  no cookie and reads none: a cookie is never the cause.
- **`403 scope-rejected` on every spaced request after the user picked a space**: the space kept in `SpaceIdStorage` is
  one where the principal has no role, and the client keeps sending it by design (`onScopeRejected` is told; it never
  falls back to another space). The application clears or changes the stored space. On every request: the token is for
  another app, or none (see the status table).
- **`NonReplayableRequestError`**: the access token was refreshed after a `401`, and the request's body was a stream
  that cannot be sent twice. Send it again with a new body.
- **A tenant switch is refused**: `switchTenant` rejects with `TenantSwitchError`. `sessionEnded: true` (a `401`, the
  pair removed, `onUnauthorized` called): `token-revoked` when the session was logged out or revoked, or is a session a
  switch started whose login's session has ended (`IDN-36`), or the pair presented was older than the session's current
  one, which revokes the session (a switch of the application's own that did not take the lock refreshes take);
  `token-invalid` when `Authorization` and the body were not one pair (the same cause), or the credential has no session
  (an API key, a token of a `TokenVerifier` of the application's). An application route that refuses a non-member with
  `401` ends the session too: it must refuse with another status. A `token-expired` is not on this list: the client
  refreshes once and switches again (wire: switch `401`, refresh, switch), so a switch that ends in `RefreshTokenError`
  is a session whose lifetime is over (or ended), and `TenantSwitchError` `token-expired` with `sessionEnded: false`
  after a refresh that succeeded is a route that refuses the fresh access token: check the server's clock and the
  route's own answer.
  `sessionEnded: false` keeps the pair: `503` while the revocation or session store cannot be read (call again), no
  `status` (network error or timeout: calling again is safe, the switch does not rotate the pair), or the application's
  own refusal (its `TenantSwitch`). A switch request without the pair in its body fails against an endpoint of
  8.0.0-beta.8 or later, which reads it (`IDN-22`). `No tenantSwitcher: ...`: the configurer has no
  `CoSecTenantSwitcher`.
- **`logout()` rejects with `LogoutUnconfirmedError`**: the pair is gone here, the server did not confirm (`503`,
  `500`, network error), or the client has no `tokenRevoker` (`No tokenRevoker: ...`) or, in React, the hook has no
  `tokenManager` (`No tokenManager: ...`).
- **React: `useSecurityContext must be used within a SecurityProvider`**: the component is outside the provider.
  **`useSecurity: the tokenManager option manages another TokenStorage than the hook's`**: two `TokenStorage`
  instances (a second one created, or two copies of `@ahoo-wang/cosec-fetcher` installed); pass
  `cosec.tokenStorage` and `cosec.tokenManager` of one `CoSecConfigurer`. **`RouteGuard` shows its fallback though a
  pair is stored**: its access token expired (`authenticated` is false); `RefreshableRouteGuard` refreshes first.

### Service behind a gateway (`cosec.pipeline.mode=gateway`)

The service reads the caller from `CoSec-Identity`, which only the CoSec gateway sets (it strips the client's own). No
header: anonymous. Otherwise never anonymous. A bare 400/401 says no more by design; the reason is the service's log
line `the forwarded identity is refused (<status> <problem>), <reason>` (logger `...identity.IdentityGate`, WARN at
most once a minute per problem type, DEBUG every time). Gateway mode records no decision events or audit; every refusal
is counted in `cosec.identity.refused{problem}` (with cosec-micrometer), where any non-zero rate is a misconfigured hop
or a request that bypassed the gateway:

- 400 `invalid-request`: not base64url JSON of format v1, a missing member or a malformed `crit`, an id with an unpaired
  surrogate (IDN-10), a blank `sub`, `tenant` or `rid` (IDN-38), over
  `cosec.pipeline.identity.max-size` (8KB), sent more than once, or a scope that does not resolve here.
- 431, or a 400 without CoSec's problem body: the web server refused the headers before CoSec read them
  (`server.max-http-request-header-size`). On a host that reads the identity it is derived, 16 KB at the defaults,
  unless set; a value set below that is named at startup at WARN with both numbers (IDN-43).
- 503 `unavailable`: a member the header's `crit` names that the service does not know (IDN-41): a later CoSec
  version wrote it and this service is older; counted as `cosec.identity.refused{problem=unavailable}`, never a store
  outage. Upgrade the service.
- 401 `token-invalid`: unsupported version, no `auth.exp`, or issued in the future (beyond
  `cosec.authentication.revocation.skew`).
- 401 `token-expired`: the credential expired, or the identity is older than `cosec.pipeline.identity.max-age` (60s)
  plus the skew. Check clocks. In a test without a gateway: a header (`IdentityHeader`) made once and reused; make one
  per request.
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
  6 revalidation intervals, 60s) and `... is stale` (ERROR, over 30, 5m). `Reloading snapshot document [<key>] failed;
  keeping revision <n>` means Redis could not be read: the installed revision stays until a read succeeds.
  `Snapshot document [<key>] regressed from revision <a> to <b>` means the store lost a write (for example a
  failover); `cosec-migrate verify-chain` names every document whose revision chain lost a record.
- The control plane's `GET /documents/{key}/revisions/{rev}/propagation` reports, of the recently reporting instances (every CoSec process with `cosec-cocache` reports, `SNP-25`; `recent`), how many hold the document (`holding`) and how many of those have installed it (`installed`), as read at `asOf` (at most 5 seconds ago). No instance is behind when `installed` equals `holding`; `holding: 0` means no instance holds the document, and the first request loads it at the published revision.

### Snapshot log lines missing / housekeeping summary

- `Snapshot housekeeping since its last summary: refused <n> tasks, full or stopped …` (WARN, at most every 10s) with
  `cosec.snapshot.housekeeping.refused{task}` rising: the source's one housekeeping thread (queue 4,096) is behind, so
  log lines (`*-log`), eviction passes under the cap (`evict`) and lag reports are dropped and counted instead of run
  on a request's thread (SNP-19); `drop` runs in place. Decisions are unaffected. Usual cause: a synchronous log
  appender that is slow; use an async appender. `meter failures` in the same line is a registry meter that threw.

### Probes answer 401 or 503, or pods restart before the first publish

A readiness or liveness probe that answers CoSec's problem+json (`503` before the first platform document, `401` after it)
or pods that restart in a loop on a new deployment: to CoSec a probe is a request like any other (`STA-4`), on the
application port of an application and on a WebFlux application's management port. Readiness over HTTP needs one
statement permitting `GET` on `/actuator/health/readiness` and `/actuator/health/liveness` for anyone (Spring Boot 4 serves the probes on every platform); a
liveness or startup probe must not depend on the platform
document: on WebMvc point it at `management.server.port` (outside CoSec), on WebFlux use a `tcpSocket` probe on the
application port. https://cosec.ahoo.me/operations/troubleshooting#probes-answer-401-or-503

### Rate limiting

- Without a `ReactiveStringRedisTemplate`, the limiter is per JVM: N instances allow N times the limit.
- Denied requests never spend permits. A rule that never applies has a non-matching action (namespace), resource or
  condition. Rules count per document: an app's or tenant's rule has its own buckets.
- A limiter failure skips the rule unless `strictFailure: true` (then 503, not 429).
- The per-JVM `LocalRateLimiter` holds at most 100,000 buckets, 50,000 of one rule (OBL-6), and never evicts one to
  make room. When full, a new key cannot be counted: `The local rate limiter holds its maximum of buckets (rule [<id>]:
  ...)` in the stack trace of `Rate limiter unavailable`, and the rule's `strictFailure` decides (503 or skipped);
  existing buckets keep counting. Typically the rule's `key` reads something a client varies: key it on the principal
  or a trusted address. A bucket is removed only after an hour idle and two of its rule's windows (OBL-5).
- A `key` value with an unpaired UTF-16 surrogate counts in a bucket of its own, `e:` and the value escaped (`\uD800`,
  `\` doubled), never in the bucket of the value with `?` in its place (IDN-10). Other values count in `k:<value>`.
- On a Redis Cluster, `CROSSSLOT Keys in request don't hash to the same slot` in that log line means a version whose
  counters span two keys without a hash tag (8.0.0-alpha.2 or earlier): every rule is skipped, or with `strictFailure`
  every permit is 503. Upgrade; a bucket is one key, `<cosec.key-prefix>:rate-limiter:<rule>:<bucket>`. A `cosec.key-prefix` with
  its own hash tag (`{cosec}`) puts every counter on one slot.

### Control-plane errors

A first platform document that does not let authenticated callers reach `/cosec/control/v1/**` leaves the control plane
unreachable (403 from the pipeline, before any control check) and `cosec-migrate publish` never overwrites: add the
`control-api` policy of https://cosec.ahoo.me/guide/quick-start to the platform document. Once a platform document
exists, never empty Redis: a later change that closed the control API (every control call 401/403) is undone with
`cosec-migrate restore --to-redis <uri> --document platform --revision <last good>`, which prints the plan, then
`--confirm <head revision>` writes the kept revision as the next one through the control plane's writer
(https://cosec.ahoo.me/operations/storage#recovering-from-a-lock-out). The control plane refuses such a change with 422
(`V4`, `... could not reach the control plane: ...`) unless the publisher sends `"lockout": true`. A control token
is issued for tenant `(platform)` (or the tenant) and app `cosec.control.app-id`; a token for another app is
`scope-rejected` on a host with an app.

An `:apply` with `REPLACE` answers 409 `REPLACE removes what the file leaves out, and the file was read at revision <m>`
when the file's header `revision` is not the head's: re-read the head, merge, set `revision` to the head's. An `:apply`
with `MERGE` never removes: its `OPERATION` WARNING at `$.policies`/`$.bindings`/`$.roles`/`$.rateLimits` names what
it kept.

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
`spring.codec.max-in-memory-size`; on WebMvc a body over 10MB (the document cap and 2MB) is 400. Without a platform document the control API itself answers 503; publish the
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
older instance, or a credential without an id), so approve again. A 403 on `:approve` with `a workload's credential (an API key, a service) cannot approve`
in the log: approvals are a person's, so approve with a token; a workload's credential (an API key, or a service's own
identity, whose credential is named `service`, IDN-34) is never the trust root either. A change a
service makes through `ControlService` or the service port that waits for a person (`NOT_ANALYZED` on an app's catalog,
`CROSS_TENANT` on a tenant's grant) although it states a platform administrator: no one is the trust root there
(`CTL-81`); give a role projection the role class (`cosec:manage-roles`), or have a person approve on the operators'
routes. A role change of such a service that waits with no `ROLE_CLASS`: the principal lacks `cosec:manage-roles` on
the tenant's document, or the role is of the control plane's own app or has no app, never the class's. A tenant
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

When a proposal is `409` with `conflict: OPEN_LIMIT`, the document holds `cosec.control.revisions.max-count` open
changes (`CTL-83`); `heldBy` names the oldest. The author's own oldest failed draft was already abandoned for room:
abandon, publish or let expire the open changes still there.

When `GET /changes/{id}` is `404` for a change that ended, the bound removed it (`CTL-83`): a document keeps its latest
`max-count` ended changes, and reclamation removes older ones past `min-age` whose revision is gone. Read who published
a revision from `GET /documents/{key}/revisions/{revision}` (`provenance`), and the rest from the audit events.

When `cosec.control.revisions.reclaimed-young` counts, or a `WARN` says records younger than `min-age` were reclaimed,
a document's records weighed more than it may retain (`max-count` × the document cap in all, its `max-count` most
recent included, or 26,214 index entries beyond them, `CTL-84`): find the client publishing large documents in a loop and rate-limit it per
tenant. The `max-count` most recent revisions stay, so a rollback to a recent one still works.

When a change event `HEAD_LOST` arrives, or `cosec.control.revisions.lost` counts, a published revision's head write was
lost in a Redis failover (`CTL-86`): the head is back at the event's base and the change is not in effect. Publish it
again or roll back, and set `min-replicas-to-write` on Redis.

When a tenant change waits for approval with `TOO_LARGE`, the grant analysis spent its four million comparisons
before it reached those grants (`CTL-49`): split the change; what counts is what it adds or rewrites, times what its
author holds. One entry `change of tenant:<t>` means it grants more than 80,000 fragments (actions × resources × bindings
of its granted ALLOWs), and none was analysed: spread the statement's actions or resources, or its bindings, over
several changes.

When every tenant grant over every app (a binding with app `*`, an attachment, also one whose condition narrows it to
apps the change does not name) waits for approval as `UNREADABLE`
after an upgrade, and the control plane logs `The app list is missing or incomplete`, the app list is not complete:
run `cosec-migrate repair-references --to-redis <uri> [--cluster] [--key-prefix <p>]` once, after every control-plane
instance and `cosec-migrate` runs the new version. The same after the list was lost, and after the reclamation pass
logs `The app list misses the app documents [...]; it is incomplete again` (an older instance published an app).

When a change or a publish fails with `The back-reference set of [<doc>] policy [<id>] is of the format before
8.0.0-beta.5 (...). Convert the store first`, the control plane logged `The control store was
written before 8.0.0-beta.5` at startup, or `cosecControlStoreFormat` is `DOWN`, the store still holds the
back-references of 8.0.0-beta.4 or earlier, one value per policy, which this version refuses rather than reads as empty
(`CTL-42`). Any other failure of a set, a timeout or a failover, comes through as what it is, without that advice. Stop every control plane of an earlier version and run `cosec-migrate repair-references` once: it converts
them into sets, never replaces a set, and removes the old document list last (`MIG-4`). Data-plane instances are not
affected.

When `GET /changes` answers `503` and the reclaim pass logs `The open-change sets are not kept` at `WARN`, revisions
are not reclaimed: the store still holds the open-change list of 8.0.0-beta.4 or earlier, or an older control plane
wrote it again, and the open-change sets are not trusted until `cosec-migrate repair-references` lists every change of
it and removes it (`CTL-54`). Stop every older control plane and run the command; a `FAILED change [<id>] does not read`
line names a change record to remove or fix before the next run.

A shadow window that does not close while the control plane logs `Shadow window <w> of change <c> is waiting for
[<instances>]` lost the report of an instance that had reported on it (stopped, scaled down, a Redis failover). Its
evidence is missing, so the window waits for it (`CTL-56`); an instance that still serves reports again within its
report interval. Otherwise the window closes insufficient at `cosec.control.shadow.max-duration`, naming the instance in
`vanished`: publish with the summary acknowledged, or abandon and stage again.

A shadow window that does not close while the control plane logs `Shadow window <w> of change <c> is waiting for the
acknowledgement of [<instances>]` (and `GET /changes/{id}` names them in `shadow.waitingFor`) waits for live instances
that do not acknowledge it (`CTL-10`): an older version (finish the upgrade; its status is no longer live a minute after
it is gone) or an instance that cannot read the staged list (`The staged list cannot be read` in its log, a rising
`cosec.shadow.staged.age`), or one whose status does not decode (`CTL-73`: the control host logs `The status of instance
[<instance>] does not read`, with why, and `cosec.control.shadow.status.unreadable` counts it; a later format is a
newer version mid-upgrade, anything else a corrupt value or a crash-looping instance). A live instance has a status
from the last minute; one cut off from Redis longer is not waited for, and a host with shadowing off writes none. With
`waitingFor` empty it waits for samples: too little traffic on its documents; it closes
insufficient at `max-duration`.

`propagation` answering `unknown` above 0 means some live instances have no resident record anyone can read
(`CTL-75`), so "no instance is behind" cannot be said yet: instances still on 8.0.0-beta.5 (expected mid-upgrade, no
log line), an instance whose resident write keeps failing (`Writing the resident documents failed` at `WARN` on it,
usually a Redis user without the `shadow:resident` keys), or a record that does not decode (`The resident documents of
instance [<instance>] do not read` at `WARN` on the control host: a newer version, or a corrupt value).

A shadow window that stays blocked has a `CANDIDATE_ERROR`: one instance cannot compile the candidate, usually because
it reads an `ext.*` attribute whose provider runs on the control host but not on that instance. Providers and their
declared types must match on every instance and the control host; the change is never published while blocked.

### Startup fails on two control services

- `This instance runs the control plane, and cosec.control.service-url names a control-plane host as well` (`STA-11`):
  one `ControlService` per application. Remove `cosec.control.service-url` to embed, or set `cosec.control.enabled=false`
  to call the host. `cosec.control.service-url needs cosec-control-http`: add `me.ahoo.cosec:cosec-control-http`.
- `The control API's client follows redirects` (`CTL-77`): `HttpControlService` was given an `HttpClient` that follows
  redirects, which would resend `CoSec-Identity` where one points; build it with `followRedirects(NEVER)`. `The control
  plane's answer is over 20971520 bytes`: something other than a control-plane host answered at the service URL.
  https://cosec.ahoo.me/operations/troubleshooting#startup-fails-on-two-control-services

### An instance the control plane cannot see

- `Writing the instance status of instance [<id>] failed: the control plane cannot see this instance among the readers
  of the store ... Allow its Redis user to write ~<s>:shadow:status:<id>, ~<s>:shadow:resident:<id> and
  ~<s>:shadow:instances:*` (`ERROR` once, then `WARN` at most once a minute; `cosec.instance.status.failed` counts;
  `cosecInstanceStatus` shows `failingSince`): every CoSec process with `cosec-cocache` reports its status (`SNP-25`),
  a control-plane host and an IAM service behind a gateway included. The instance keeps serving, but a publish will not
  wait for it: give its Redis user write permission on those keys. `GET /instances` lists who the control plane sees,
  with each one's release; `cosec.control.instances.releases` above 1 is an instance of another release still reading
  the store (within the last hour).

### The typed control client does not read a document

- `ControlClient` throws `SnapshotFormatException` (`a <entry> has members this version does not know`): the control
  host is newer than the application's `cosec-control-sdk` and the document holds a member only it knows. The client
  refuses rather than drop it (an `:apply` of what it read would remove the member, `CTL-76`). Upgrade
  `cosec-control-sdk`; meanwhile read the document through `ControlService` as JSON. An unknown closed-set value (a
  state, a conflict) is not this error: it reads as unknown, with its text.
  https://cosec.ahoo.me/operations/troubleshooting#the-typed-control-client-does-not-read-a-document

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

Use `$cosec-policy-author` to rewrite a confirmed-bad document, `$cosec-integration` to change confirmed-bad Spring
configuration, and `$cosec-client` to change confirmed-bad client wiring. For diagnosis-only requests, report the root cause and evidence without modifying files.

When a control call is `503` with `Retry-After: 1` and `cosec.control.work.refused` counts, it is not the store: the
control plane's work budget (an eighth of the heap) was full of large documents parsed or compiled at once (`CTL-85`).
Retry; if steady, give the host more heap or spread out large `POST /changes` and `:apply` calls.
