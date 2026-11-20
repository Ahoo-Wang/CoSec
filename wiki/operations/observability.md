---
title: "Observability"
description: "See every decision CoSec makes and every change to the documents: decision listeners, audit events, traces, metrics and logs."
---

# Observability

For operators and security teams who need to know what CoSec decided, why, and who changed the rules. This page
covers the decision listeners that observe every request, the audit events, spans and metrics they produce, how to see
whether instances are current with the documents, the control plane's change log, and the logs. To see every
statement a decision checked, not only the one that decided, explain it through the control plane
([Explaining a decision](../integration/checks.md)).

## Decision listeners

Every request through the pipeline ends in one decision event, whatever its outcome: a permit, a deny, a `429`, a
`400`, a `503`, a defect (`500`), or a caller that went away. Decision listeners receive these events; the audit log,
Kafka, metrics, tracing and shadow windows are all listeners. A listener observes only: it cannot change a decision,
except that a `REQUIRED` one that fails turns a permit into `503`.

| Delivery | When the listener runs | What a failure does |
|---|---|---|
| `REQUIRED` | Before the response, together with the other `REQUIRED` listeners, each within [`cosec.observability.listener.required-timeout`](./configuration.md#decision-listeners) (1 second) and within what is left of `cosec.pipeline.timeout`. It sees the outcome as decided so far. | A permit becomes `503` `unavailable`; the request does not proceed. Any other outcome keeps its status; the failure is logged at `ERROR` and counted. |
| `BEST_EFFORT` (the default) | After the response is decided, off the request path. Each listener has its own queue of [`cosec.observability.listener.queue-capacity`](./configuration.md) events (1,024) and its own consumer, so a slow listener never costs another its events. It sees the final outcome. | Nothing for the request. A full queue drops the event and counts it, and the request never waits on a log line for it: a reporter logs `Dropping decision events: the queue of listener [<name>] is full; <n> dropped since the last report.` at `WARN`, at most every 10 seconds, off the request's thread. A listener that throws is logged at `WARN` at most every 10 seconds, and counted. |

### What decided, in every output {#what-decided-in-every-output}

The decision event's `outcome.reason` names what decided, and every output uses its words (UD-7): the span's
`cosec.authorize.result` as they are, the metrics' `result` tag in lower case, audit's `decision` in upper case. Only
`explicit_deny` means a DENY statement refused the request.

| `outcome.reason` / span | Metrics `result` | Audit `decision` | Counts |
|---|---|---|---|
| `PERMIT` | `permit` | `PERMIT` | permitted requests |
| `TOO_MANY_REQUESTS` | `too_many_requests` | `TOO_MANY_REQUESTS` | permits an exhausted rate limit stopped (`429`) |
| `EXPLICIT_DENY` | `explicit_deny` | `EXPLICIT_DENY` | requests a DENY statement refused |
| `IMPLICIT_DENY` | `implicit_deny` | `IMPLICIT_DENY` | requests nothing allowed |
| `SCOPE_REJECTED` | `scope_rejected` | `SCOPE_REJECTED` | requests whose app or space the principal may not act in (`403` `scope-rejected`) |
| `BOUNDARY` | `boundary` | `BOUNDARY` | permits outside the tenant's platform boundary (`403` `forbidden`) |
| `INVALID_REQUEST` | `invalid_request` | `INVALID_REQUEST` | requests that could not be read without ambiguity (`400`) |
| `INDETERMINATE`, `AUTHENTICATOR_UNAVAILABLE`, `DEADLINE_EXCEEDED` | `indeterminate` (`cause` says which) | `INDETERMINATE` | requests no decision could be made for (`503`) |
| `ERROR` | `error` | `ERROR` | defects (`500`) |
| `CANCELLED` | `cancelled` | no audit record | callers that went away |

The event's `subject.method` names how the principal authenticated in the words `CoSec-Identity` uses for the same
credential: `jwt` for CoSec's own token, `api-key`, or the name another verifier's tokens are given (`token` by
default); a principal with no credential is named by its authenticator.

The listeners the starter registers:

| Listener (`name`) | Delivery | Present when |
|---|---|---|
| `LoggingAuditListener` (`logging-audit`) | `BEST_EFFORT` | `cosec.audit.enabled` (default `true`) |
| `KafkaAuditListener` (`kafka-audit`) | [`cosec.audit.kafka.delivery`](./configuration.md#audit), `BEST_EFFORT` by default | `cosec-kafka`, a `KafkaOperations` bean, `cosec.audit.enabled` and `cosec.audit.kafka.enabled` (both default `true`) |
| `MetricsListener` (`metrics`) | `BEST_EFFORT` | `cosec-micrometer`, a `MeterRegistry` bean (Spring Boot's own auto-configured one included) and `cosec.metrics.enabled` (default `true`) |
| `TracingListener` (`tracing`) | `BEST_EFFORT` | `cosec-opentelemetry` |
| `ShadowListener` (`shadow`) | `BEST_EFFORT` | `cosec-cocache` and `cosec.authorization.shadow.enabled` (default `true`) |

Any `DecisionListener` bean of yours joins them; names must be unique, or startup fails
([Extending](../architecture/extending.md)). An `AuditEventSink` bean joins them too: the
starter wraps it as a `BEST_EFFORT` listener named after the bean (`AuditListener.of(sink, beanName)`), in its
`@Order` among the listeners. It is always `BEST_EFFORT`, because `AuditEventSink.publish` cannot acknowledge a write.
A bean that is both is registered once, as the `DecisionListener` it is.

- **Break-glass needs a `REQUIRED` listener.** A request permitted through a break-glass binding is `503` unless at
  least one `REQUIRED` listener is registered and every one of them records it. Out of the box no listener is
  `REQUIRED`, so the starter logs at startup
  `No REQUIRED decision listener is registered: break-glass bindings cannot permit (RFC 0006 §9.4.3).` Set
  `cosec.audit.kafka.delivery=required`, or register a `REQUIRED` listener of your own, before you rely on break-glass.
- **A `REQUIRED` listener should be idempotent** by the event's `id`: a write that timed out may still have succeeded.
- **On shutdown**, the listeners stop taking events, and the events already queued are still delivered for up to
  [`cosec.observability.listener.drain-timeout`](./configuration.md) (5 seconds). Then no listener call starts any
  more: a call in progress is not interrupted, and what is left is dropped and counted in `cosec.listener.dropped`, with
  `Decision listeners did not deliver every queued event within <timeout>; <n> were dropped, and no listener call starts any more.`
  at `WARN`. The Kafka
  audit listener then gets up to the same timeout for the sends it still holds and the broker's answers to them, before
  the producer is closed. At that bound its queued sends are dropped and a send in progress is interrupted, with
  `The Kafka audit queue of [<topic>] did not drain within <timeout>: <n> queued records are dropped and <n> sends are unanswered.` at `WARN`.
- **The evaluation trace.** A `DecisionEvent` carries `evaluationTrace`, what the engine looked at, to the depth of
  [`cosec.pipeline.trace-level`](./configuration.md#pipeline): `off` (the default, no trace and no cost), `full`
  (every statement of every candidate is listed, with what ruled it out; evaluation stops where it would without a
  trace, except that it finishes the pass that decided) or `exhaustive` (also evaluates the ALLOW pass when the DENY
  pass decided). It is null at `off` and when the request did not reach the engine. Only your own
  listeners read it: the audit events, spans and metrics below do not include it.
- **Services in gateway mode** (`cosec.pipeline.mode=gateway`) emit no decision events: they decide nothing and
  register no listeners. The gateway in front records the decision
  ([Deploying the gateway server](./deployment.md#services-behind-the-gateway)).

## Audit events

The audit listeners map each decision event to an `AuditEvent`. It keeps what the response body leaves out: explicit
and implicit denies apart, why a credential was rejected, and why a request was `503`.

| Field | Contents |
|---|---|
| `eventId`, `timestamp`, `tenantId` | the event; `tenantId` is the principal's tenant |
| `kind` | `DECIDED`, `BAD_REQUEST` (`400`), `AUTHENTICATION_UNAVAILABLE`, `CANCELLED` or `ERROR` (`500`) |
| `principal` | `id`, `authenticated`, `roles` (the roles of the resolved scope) |
| `request` | `id` (the `CoSec-Request-Id`), `appId`, `spaceId`, `remoteIp`, `method`, `path` (normalized), `routeId` (the gateway route the request came through; null on other adapters), `device.id` |
| `authorization.decision`, `authorization.reasonCode` | see the table below |
| `authorization.reason` | the outcome, and for a `503` its cause, such as `INDETERMINATE: SourceUnavailable` |
| `authorization.elapsedNanos` | the whole pipeline, from the start of the request to its outcome |
| `authorization.source` | what decided: `via`, `document`, `policy` (its id) and `statement` (`index`, `name`); see the second table below |
| `binding` | how the deciding statement applied: a binding id (a role's derived binding is `role:<roleId>@<app>:<permission>`), `attached:<policyId>`, `builtin` (the blacklist) or `boundary:<tenantId>` |
| `snapshotVersion` | the revisions the decision read: `platform`, and `app` and `tenant` when it read them |
| `breakGlass` | whether the permit came through a break-glass binding |
| `trace` | `traceId` and `spanId` of the request's span, when tracing is active |

| Outcome | `decision` | `reasonCode` |
|---|---|---|
| permit | `PERMIT` | `PERMIT` |
| `429` | `TOO_MANY_REQUESTS` | `TOO_MANY_REQUESTS` |
| a DENY matched | `EXPLICIT_DENY` | `EXPLICIT_DENY` |
| scope rejected | `SCOPE_REJECTED` | `SCOPE_REJECTED` |
| a permit outside the tenant's boundary | `BOUNDARY` | `BOUNDARY` |
| nothing allowed | `IMPLICIT_DENY` | `IMPLICIT_DENY` |
| any deny of a request whose credential was rejected | as above | `TOKEN_EXPIRED`, `TOKEN_REVOKED` or `TOKEN_INVALID` (also for an API key); `DELEGATION_UNSUPPORTED` for a token with an `act` claim, which the response reports as `token-invalid`. `TOKEN_NOT_YET_VALID` for a token whose `iat` or `nbf` is later than this instance's clock beyond the skew (`IDN-29`), which the response reports as `token-invalid` too. The principal stays anonymous; `authorization.reason` names whose credential was refused when that is known (a key that does not work, a principal the membership provider does not know or knows as deactivated), as the decision event's `subject.refused` does |
| `400` | `INVALID_REQUEST` | `INVALID_REQUEST` (with `kind` `BAD_REQUEST`) |
| `503`, a strict rate-limit rule that could not be counted | `INDETERMINATE` | `RATE_LIMITER_UNAVAILABLE` |
| any other `503`, and a cancelled request | `INDETERMINATE` | `INDETERMINATE` |
| `500` | `ERROR` | `ERROR` |

`authorization.source.via` says how what decided came to apply, and `document` which snapshot document holds it:

| `via` | `document` | Meaning |
|---|---|---|
| `BINDING` | `PLATFORM`, `APP` or `TENANT` | a binding of that document, a role's derived binding included; `binding` is its id. A break-glass permit is a binding of `cosec:root`, with `breakGlass: true` |
| `ATTACHMENT` | `null` | a policy attached through the principal's memberships |
| `BUILTIN` | `null` | the built-in blacklist |
| `BOUNDARY` | `PLATFORM` | the tenant's platform boundary refused a permit. `policy` is the boundary and `statement` is `null`, since none of its statements matched |
| `NONE` | `null` | no statement decided: nothing allowed the request, its scope was rejected, or no decision was made. `policy` and `statement` are `null` |

### Audit log and Kafka

The audit log listener writes each event as one JSON line to the logger `me.ahoo.cosec.audit`: `PERMIT` at `DEBUG`,
denials (the deny kinds and `INVALID_REQUEST`), `TOO_MANY_REQUESTS` and `INDETERMINATE` at `WARN`, `ERROR` at `ERROR`. At the default `INFO` level, only
denials, `503`s and errors appear.

The Kafka audit listener writes the same JSON to [`cosec.audit.kafka.topic`](./configuration.md#audit)
(`cosec-audit`), keyed by `[tenantId, principalId]` as a JSON array. It runs alongside the audit log, not instead of
it; set `cosec.audit.enabled=false` and register only what you want to change that. It sends from a thread of its own
with at most 1,024 sends waiting:

- `best-effort`: a failed send is logged as `Failed to publish audit event to Kafka topic [<topic>].`
- `required`: the request waits for the broker's acknowledgement. A failed or late send, or a full send queue
  (`The Kafka audit queue of [<topic>] is full.`), makes a permit `503`. Configure the producer with `acks=all`.

## Tracing

With `cosec-opentelemetry`, each decision event becomes a span named `cosec.authorize` (instrumentation
`me.ahoo.cosec`, kind `INTERNAL`). The span is made after the decision, from the request's start time and the
pipeline's duration, under the span that was current when the adapter received the request; without one, it is a root
span. Tracing therefore never delays or fails a request. Calls the pipeline makes for the request (Redis, a membership
provider) are siblings of `cosec.authorize`, under the same parent. The tracer comes from `GlobalOpenTelemetry`.
The span inherits the parent's `tracestate` as the SDK's own child spans do. The event's trace carries it as an opaque
string in its W3C header form: vendor data that CoSec writes onto the span's parent and nowhere else (not into audit
records, Kafka records, logs or metric tags), and that a decision listener of yours must not log. A value the SDK
refuses, or one longer than 512 characters, is no tracestate: the span is still made, under the same parent.

| Attribute | Value |
|---|---|
| `cosec.tenant_id`, `user.id`, `cosec.request_id` | the principal's tenant and id, and the request id |
| `cosec.app_id`, `cosec.space_id`, `device.id` | the resolved scope and the device, when not empty |
| `cosec.authorize.result` | `PERMIT`, `TOO_MANY_REQUESTS`, `EXPLICIT_DENY`, `IMPLICIT_DENY`, `SCOPE_REJECTED`, `BOUNDARY`, `INDETERMINATE`, `AUTHENTICATOR_UNAVAILABLE`, `DEADLINE_EXCEEDED`, `INVALID_REQUEST`, `CANCELLED` or `ERROR` |
| `cosec.authorize.policy.id`, `cosec.authorize.statement.index`, `cosec.authorize.statement.name` | the deciding statement |
| `cosec.binding.id` | the binding it applied through, when it was a binding |
| `cosec.snapshot.version` | the revisions read, as `<platform>/<app>/<tenant>`, `-` for a document not read |

A `500` sets the span's status to `ERROR`. Audit events and problem bodies carry the trace id of the request's span,
which ties a response to its trace.

## Startup summary

Once every bean exists, the starter logs one INFO line of what CoSec is configured as, for example
`CoSec 8.0.0-beta.4: mode=enforce, web=servlet-filter, jwt=on (JwtTokenVerifier), revocation-reader=the CoCache token store's (CoCacheRevocationReader), login=on, api-keys=off, rate-limiter=ReactiveRedisRateLimiter, listeners=[tracing, logging-audit], route-map=12 routes`.
Each entry is what a request meets, resolved as the authenticator resolves it: the revocation reader is the application's bean, the CoCache token store's, or `RevocationReader.NONE`, which the line spells out because revoked tokens are then accepted until they expire; with no token verification (`jwt=off`) there is no reader to name.
A switch written `false` is added as `switched-off-by-configuration=[...]`. A rate limiter named `LocalRateLimiter` counts
per instance: with several instances the limit is multiplied by their number. When `cosec.enabled=false` the line is
one WARN instead: `CoSec is off ... nothing is protected by CoSec`
([troubleshooting](./troubleshooting.md#the-log-says-cosec-is-off)).

## Metrics

With `cosec-micrometer` and a `MeterRegistry` (for example from Spring Boot Actuator), every decision event is
recorded by the timer `cosec.authorization`, over the whole pipeline. Every tag has a small fixed set of values, so the
number of time series stays bounded however many policies, apps and tenants there are:

| Tag | Values |
|---|---|
| `result` | what decided ([every output's words](#what-decided-in-every-output)): `permit`, `too_many_requests`, `explicit_deny` (a DENY statement, and only that), `implicit_deny`, `scope_rejected`, `boundary`, `invalid_request` (`400`), `indeterminate` (every `503`), `cancelled`, `error` |
| `source` | `binding`, `attachment`, `builtin`, `boundary`, `scope` (a scope rejection), `none` |
| `problem` | what the client was told: `unauthorized` (`401`, anonymous), `token-invalid`, `token-expired`, `token-revoked` (`401`), `forbidden`, `scope-rejected` (`403`), `too-many-requests`, `invalid-request`, `unavailable`, `internal-error`, or `none` when the request proceeds or was cancelled |
| `cause` | `none`, and on `indeterminate`: `source_unavailable`, `document_invalid`, `missing_policies`, `membership`, `attribute`, `matcher`, `authenticator`, `required_listener`, `boundary`, `rate_limiter`, `deadline`, `unknown` |

On the `cosec-gateway-server` image the registry is Prometheus's and the endpoint is `/actuator/prometheus` on the
management port (`8081`), never on the gateway's port ([the image](./deployment.md#the-image)). The tag values above
are the whole of CoSec's; no tenant, principal, path or token is ever a tag.

The listeners themselves are counted:

| Meter | Tag | Counts |
|---|---|---|
| `cosec.listener.dropped` | `listener` | events a full or closed queue dropped, or a drain that timed out |
| `cosec.listener.failures` | `listener` | listener calls that threw or, for `REQUIRED` ones, timed out |
| `cosec.audit.required.failures` | none | `REQUIRED` failures on decisions they could not change (not permits) |

So are the tokens the pipeline accepted without a revocation check, the revocation store unreadable
([the documented exception](../guide/security-model.md#the-two-documented-exceptions)):

| Meter | Tag | Counts |
|---|---|---|
| `cosec.revocation.unchecked` | none | tokens accepted while their revocations could not be read; each such stretch also logs `Token revocations cannot be read: tokens are accepted without a revocation check (<n> so far).` at `WARN`, at most every 10 seconds |

Alert on the rate of `result=indeterminate` by `cause`, on `explicit_deny` (requests a DENY statement refused), on `cosec.listener.dropped`, and on any
rise of `cosec.revocation.unchecked`: while it rises, a revoked token is accepted. A rise of
`indeterminate` with `cause=source_unavailable` means documents cannot be read, and with `cause=document_invalid` that a
stored document does not compile on this instance ([Troubleshooting](./troubleshooting.md#unavailable)). CoCache records its own meters for CoSec's caches in the same
registry ([CoCache's documentation](https://cocache.ahoo.me)).

A service in gateway mode (`cosec.pipeline.mode=gateway`) has no pipeline and records no decision events, so none of the
meters above. It counts the forwarded identities it refuses:

| Meter | Tag | Counts |
|---|---|---|
| `cosec.identity.refused` | `problem`: `invalid-request`, `token-invalid`, `token-expired` | every `CoSec-Identity` header the service refused, including those its log does not print; the reason is logged at `WARN`, at most once a minute for each problem ([Troubleshooting](./troubleshooting.md#a-service-behind-the-gateway-refuses-requests)), never in the response |

In gateway mode any non-zero rate means a hop that is misconfigured or a request that did not come through the gateway;
alert on it.

## Snapshot metrics

Under the same conditions as the timer (`cosec-micrometer`, a `MeterRegistry`, `cosec.metrics.enabled`), the starter
meters the data plane's snapshot source: the documents this instance reads, compiles and holds
([How instances read documents](./storage.md#how-instances-read-documents)). The control plane's own source is not
metered. A `Pipeline` an application builds itself gets none of these meters. No tag carries an app or tenant id, so
there are 80 series whatever the number of documents. `kind` is `platform`, `app` or `tenant`:

| Meter | Type | Tags | What it says |
|---|---|---|---|
| `cosec.snapshot.loads` | counter | `kind`, `result` | a request that had to load a document: `cold` (the request waited for its load), `failed` (the load failed, and the request is decided fail-closed), `timeout` (the load outlasted `cold-load-timeout`). A request that finds its documents installed is not counted: that is every request's path |
| `cosec.snapshot.compile.duration` | timer | `kind`, `result` = `success`, `failure` | compiling a document; `failure` is a stored document that does not compile on this instance |
| `cosec.snapshot.installs` | counter | `kind` | documents installed: a first load or a new revision |
| `cosec.snapshot.installed` | gauge | `kind`, `state` | the entries held now, by state: `present`, `invalid` (does not compile here), `pending` (its inputs changed and it waits for its compile again), `undecodable`, `absent` (no document, none seen before), `lost` (a document seen before is gone), `unreadable`. The states add up to what the instance holds |
| `cosec.snapshot.lag` | gauge, seconds | `kind` | the oldest confirmation by the store among the entries held, failed ones aside |
| `cosec.snapshot.lagging` | gauge | `kind`, `level` = `warn`, `escalate` | entries past `lag-warn` (those past `lag-escalate` included) and past `lag-escalate` |
| `cosec.snapshot.summary.age` | gauge, seconds | none | the time since the pass that `installed`, `lag` and `lagging` read |
| `cosec.snapshot.lag.exceeded` | counter | `kind`, `level` | each lag the instance logs |
| `cosec.snapshot.regressions` | counter | `kind` | the store answered an older revision than the one installed |
| `cosec.snapshot.lost` | counter | `kind` | a document seen before is absent now; its key fails closed |
| `cosec.snapshot.evictions` | counter | `kind`, `state` = `document`, `absent` | entries an app or tenant family dropped at its bound (`max-apps`, `max-tenants`, `max-absent`) |
| `cosec.snapshot.handoffs.refused` | counter | `to` = `housekeeping`, `caller` | work the compile pool refused: `caller` means a request's thread ran it |
| `cosec.snapshot.housekeeping.refused` | counter | `task` = `load-log`, `compile-log`, `install-review`, `effect-log`, `crash-log`, `transition-log`, `prewarm-log`, `pass-log`, `reset-log`, `evict`, `drop`, `lag-report` | tasks the source's housekeeping thread refused because its queue (4,096) was full. Each was dropped, except `drop` (an empty entry's removal), which ran in place. A `*-log` is a log line that was not written; `evict` an eviction pass under the family's cap, which the next load asks for again; `install-review` a host's review of an installed document ([the route map warning](../scenarios/route-maps.md#what-cosec-warns-about)), offered again when a later read finds that revision still installed |

`installed`, `lag` and `lagging` are as of the source's last lag pass, run every `revalidate-interval`, not read on a
scrape: a scrape costs a read of one field however many documents there are. They read zero before the first pass,
when `summary.age` is `NaN`; a `summary.age` far above `revalidate-interval` means the passes have stopped.

The share of requests that waited for a document is `loads{result=cold}` over the decisions (the count of
`cosec.authorization`); `evictions` says why it rises.

| Alert on | Because |
|---|---|
| any rise of `lost` | a document vanished from the store, and its key fails closed |
| any rise of `regressions` | the store lost a write it acknowledged: check the last failover |
| any rise of `lag.exceeded{level=escalate}` | the instance decides on a document the store has not confirmed for `lag-escalate` |
| `installed{state=invalid}` above zero | a stored document does not compile on this instance (an older version than the control plane?) |
| a sustained rate of `handoffs.refused{to=caller}` | the compile pool is saturated, and requests' threads compile |
| any rise of `housekeeping.refused` | the instance is dropping its own log lines: a log appender too slow for the load ([Snapshot log lines are missing](./troubleshooting.md#snapshot-log-lines-are-missing)) |
| a sustained rate of `evictions`, with `loads{result=cold}` rising | the families are too small for the traffic: raise `max-apps` / `max-tenants` |

## Snapshot health

The meters above count; the health indicators and logs below answer whether an instance is current:

- **Health.** With Spring Boot Actuator, the `cosecSnapshot` health indicator is `UP` once the platform document and
  every prewarmed document are installed, `DOWN` before, with the details `platform` (`installed`, `invalid` when it is
  stored and does not compile, `failed`, `absent` or `loading`), `prewarmed`, `missing`, and, when any document does
  not compile, `invalid`, how many do not, and `invalidFirst`, the first ten of them with their stored revisions,
  the platform first and then by key (`["tenant:acme@7"]`), never the compiler's message. Readiness follows the same condition
  ([Readiness](./deployment.md#readiness)).
- **Eviction.** On Redis, the `cosecSnapshotEviction` health indicator is `DOWN` while Redis may evict the snapshot,
  with an `eviction` detail that says why. It reports what the instance read last: every node's `maxmemory-policy`,
  read every `revalidate-interval` on a thread of its own, never by a probe. A policy it cannot read is `DOWN` under
  `eviction-check: fail`, and `UP` with `eviction: unknown` under `warn`. It is an alert, not a readiness signal: keep
  it out of the readiness group, where one change to Redis would take every instance out of rotation while requests
  are still decided ([Redis requirements](./storage.md#redis-requirements)). It also turns the aggregate
  `/actuator/health` `DOWN`, as every indicator does: point load balancers and platform health checks at the readiness
  group, or leave it out of the group they poll (`management.endpoint.health.group.<group>.exclude=cosecSnapshotEviction`).
- **Control store format.** Where the control plane runs over CoCache, the `cosecControlStoreFormat` health indicator
  is `DOWN` while its store is in the format before 8.0.0-beta.5, with a `format` detail naming
  `cosec-migrate repair-references`, and `UP` with `format: 8.0.0-beta.5` once it has run. It reports what the instance
  read last, every minute on a thread of its own, never by a probe; one it cannot read is `UP` with `format: unknown`.
  The instance also logs one `ERROR` when it finds the old format. An alert like `cosecSnapshotEviction`: the data
  plane still decides every request, so keep it out of the readiness group
  ([Upgrading](./upgrading-previews.md#from-8-0-0-beta-4)).
- **Login routes.** When the starter serves the refresh or logout route, the `cosecLoginRoutes` health indicator is
  always `UP`, with each route's property as a detail: `not refused`, or `denied since <time>` and which refusal it was
  (no statement permits it, a DENY statement matches it, or the tenant's platform boundary does not cover it) once a
  document refused an anonymous `POST` of it, until a later request is permitted. The same refusal logs one `WARN` per route per minute,
  naming the property and the path. Both come from the decisions of real requests, so they are never wrong, and neither
  makes the instance unready ([Building a client](../integration/clients.md#what-your-application-serves)).
- **Lag logs.** An instance logs a document not confirmed by Redis for longer than `lag-warn` at `WARN`, and longer
  than `lag-escalate` at `ERROR` ([How instances read documents](./storage.md#how-instances-read-documents)). Other
  lines to alert on: `Snapshot document [<doc>] disappeared from the store; failing closed.`,
  `Snapshot document [<doc>] regressed from revision <a> to <b>; ...`, and
  `Snapshot prewarm failed for [<docs>]; retrying in <backoff>.`
- **Housekeeping summary.** The source writes its log lines and runs its eviction passes on one housekeeping thread, so
  a slow appender never holds a request's thread. When that thread's queue is full, or once it has stopped as the
  source closes, a task is dropped and counted instead of run on the thread that offered it; the source's own threads
  still run theirs while it closes. At most every 10 seconds, and only when something was dropped or a meter threw, the
  thread logs one `WARN`, and a closing source writes what is left once, on the thread that closes it:
  `Snapshot housekeeping since its last summary: refused <n> tasks, full or stopped (each dropped; drop runs in place):
  load-log 3 (last: "<text>"), …; meter failures <n> (last: "<text>").` Each `last` is the text of the last line of
  that task that was dropped, with its exception's class and message but no stack, cut at 256 characters. A meter that
  throws is never logged on its own: it is counted here, so the summary is written whatever registry is bound.
- **Propagation.** The control plane's
  `GET /documents/<doc>/revisions/<n>/propagation` answers how many of the instances that reported in the last minute
  have revision `<n>` or later installed. An instance counts a revision as installed only while it can evaluate it: one
  that read the revision but could not compile it still reports, so it is among those counted, but it has not installed
  it. Instances report only with shadow windows on
  ([Revisions, history and rollback](./storage.md#revisions-history-and-rollback)).
- **Per decision.** `snapshotVersion` in the audit event and `cosec.snapshot.version` on the span name the revisions a
  decision read.

## Control-plane change log

The control plane emits a change event for every step of a change: `PROPOSED`, `CHECKED`, `SHADOW_STARTED`,
`SHADOWED`, `APPROVED`, `REJECTED`, `PUBLISHING`, `PUBLISHED`, `FAILED`, `BREAK_GLASS_REQUESTED`,
`BREAK_GLASS_APPROVED`, `REVOKED`, `HEADS_MOVED`, `REVIEW_NEEDED`, `ABANDONED`. An event names the change, the document, the operator, the base and candidate
revisions, what needed approval, the confirmations, the shadow summary and the note; it never carries a document's
content. Change listeners receive them.

- **The default listener** logs each event at `INFO` to the logger `me.ahoo.cosec.changelog`, as
  `Change <changeId> <kind> on <doc> by <principal>@<tenant>: base=..., candidate=..., escalations=<n>, confirmations=<n>`.
  Change listeners add up: every `ChangeListener` bean is registered, and the log listener is left out only when the
  application declares a `LoggingChangeListener` bean of its own. The change log is written at `INFO`: a configuration
  that sets `me.ahoo.cosec` above `INFO` sets `me.ahoo.cosec.changelog` to `INFO` by name, as for any record you keep.
- **`PUBLISHING`, `BREAK_GLASS_REQUESTED` and `BREAK_GLASS_APPROVED` are gated:** every `REQUIRED` change listener must
  record the event within [`cosec.control.audit.required-timeout`](./configuration.md#control-plane) (5 seconds, for
  all of them together) before the step takes effect, or the step does not happen and the control API
  answers `503`. Every other event, and every event to a `BEST_EFFORT` listener, is delivered afterwards; a failure is
  logged as `Change listener [<name>] could not record <event>.` The revision record is the durable record of what was
  published. A gated event says the step was about to happen, not that it did: a check after it can still refuse the
  step (a `409` when the change moved meanwhile), as after a `BREAK_GLASS_APPROVED` or an `ABANDONED`. The change's
  state, and for a publish the revision history, say what happened.
- **A note is free text**, part of it the change's author's (the reason of a proposal, a request or a revoke): read who
  did a step from the event's `operator`, never from its note.
- **Break-glass needs a `REQUIRED` change listener:** without one, creating or changing a break-glass binding answers
  `503` (`break-glass needs a REQUIRED change listener, and none is configured`).
- **`ABANDONED` names who abandoned a change and, in its note, its author** (`author <tenant>/<principal>: <note>`).
  Abandoning a break-glass change is gated like `PUBLISHING`: it needs a `REQUIRED` change listener (`503` without
  one), which records it before the change is stored as abandoned. Abandoning any other change is recorded afterwards.
- **`REVOKED` of a stalled revoke published again** names the revoker who revoked again, and its note adds their
  reason (`<first reason>; published again by <tenant>/<principal>: <reason>`), as does its `PUBLISHING`.
- **`HEADS_MOVED` and `REVIEW_NEEDED` follow a `PUBLISHED`** when another document the change was checked with moved
  before its write landed ([What a publish compares](./storage.md#what-a-publish-compares)). Each names the heads that
  moved (`moved`: the document, or `list:apps` for the app list, the digest it was checked with, the digest it has
  now, and whether it could be read), carries the escalations the grant analysis finds on the current heads, and says
  in its note what that means. `HEADS_MOVED` needs nothing; `REVIEW_NEEDED` means the analysis found an escalation the
  publish did not have approved, could not run, or the comparison did not finish in time (`moved` is then empty, and
  the one escalation has the reason `DETECTION_TIMED_OUT`): a
  person should review the published change, and the control plane also logs
  `Change <changeId> published on [<doc>]: <note>; moved [...].` at `WARN`. They are recorded even when the client
  that published disconnects. Like every event that is not gated, they are best effort: the publish has already
  happened.
- **Escalations** in an event are `subject`, `reason` and `detail` ([Testing changes](../policies/testing.md)). A
  `TENANT_BINDING` one names only the binding and its tenant document (`binding <id> of [tenant:<t>]`), with the
  policy and the statements in `detail`; whom the binding binds, and in which space, is the tenant's and is left out.
  It is informational: it never counts as what needed approval.

With `cosec-kafka` and a `KafkaOperations` bean, the starter also registers a `KafkaChangeListener`, unless
[`cosec.control.audit.kafka.enabled`](./configuration.md#control-plane) is `false`. It writes each event to
`cosec.control.audit.kafka.topic` (`cosec-control-audit`), keyed by the document (`platform`, `app:order`,
`tenant:acme`), so a document's events stay in order. Its delivery is `cosec.control.audit.kafka.delivery`:
`best-effort` by default, `required` to gate publishing and break-glass on the broker's acknowledgement (configure the
producer with `acks=all`). With `delivery=required`, startup fails when the Kafka change listener cannot be created
(no `cosec-kafka`, no `KafkaOperations` bean, or `enabled=false`), rather than publishing without the record:
`cosec.control.audit.kafka.delivery=required, but no Kafka change listener was created: ...`.

## Logs

| Logger | What it logs |
|---|---|
| `me.ahoo.cosec.audit` | the audit log ([Audit log and Kafka](#audit-log-and-kafka)) |
| `me.ahoo.cosec.runtime` | `Request [<requestId>] failed with an unmodelled exception.` at `ERROR` with the stack trace, for every `500`; `REQUIRED` listener failures; dropped events; attribute providers that fail (`<provider> failed to fetch [...]` at `WARN`, at most every 10 seconds per provider: each decision carries the cause); rate limiters that cannot count |
| `me.ahoo.cosec.runtime.snapshot` | loading, lag, lost and regressed documents ([Snapshot health](#snapshot-health)) |
| `me.ahoo.cosec.spring.boot` | the startup checks, such as the `!!!` eviction warning; `No BlacklistSource bean: requests are not checked against a blacklist (ext.blacklist.*).` at `INFO` when the application declares no `BlacklistSource` ([Blacklist](../guide/security-model.md#blacklist)); `ip2region databases: [<versions>].` at `INFO`, naming the IP versions without a database, whose addresses are unavailable; the `WARN` of a service in gateway mode ([Services behind the gateway](./deployment.md#services-behind-the-gateway)) |
| `me.ahoo.cosec.changelog` | the change log: one `INFO` line per change event ([Control-plane change log](#control-plane-change-log)); route it as you route `me.ahoo.cosec.audit` |
| `me.ahoo.cosec.control` | `Control API <method> <path> failed (request <requestId>).` for every control-plane `500`; and, under the logger of the class that writes it, `Change listener [<name>] could not record <event>.` at `WARN` |

Correlate a response with its log lines, audit event and span through the `CoSec-Request-Id` response header, which is
also the `requestId` of every problem body.

**Next:** go from a symptom to its cause, in [Troubleshooting](./troubleshooting.md).
