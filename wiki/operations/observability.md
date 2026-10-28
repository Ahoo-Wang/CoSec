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
| `REQUIRED` | Before the response, together with the other `REQUIRED` listeners, each within [`cosec.audit.required.timeout`](./configuration.md#audit) (1 second) and within what is left of `cosec.pipeline.timeout`. It sees the outcome as decided so far. | A permit becomes `503` `unavailable`; the request does not proceed. Any other outcome keeps its status; the failure is logged at `ERROR` and counted. |
| `BEST_EFFORT` (the default) | After the response is decided, off the request path. Each listener has its own queue of 1,024 events and its own consumer, so a slow listener never costs another its events. It sees the final outcome. | Nothing for the request. A full queue drops the event; a listener that throws is logged at `WARN` at most every 10 seconds, and both are counted. |

The listeners the starter registers:

| Listener (`name`) | Delivery | Present when |
|---|---|---|
| `LoggingAuditListener` (`logging-audit`) | `BEST_EFFORT` | `cosec.audit.enabled` (default `true`) |
| `KafkaAuditListener` (`kafka-audit`) | [`cosec.audit.kafka.delivery`](./configuration.md#audit), `BEST_EFFORT` by default | `kafka-support`, a `KafkaOperations` bean, `cosec.audit.enabled` and `cosec.audit.kafka.enabled` (both default `true`) |
| `MetricsListener` (`metrics`) | `BEST_EFFORT` | `micrometer-support`, a `MeterRegistry` bean and `cosec.metrics.enabled` (default `true`) |
| `TracingListener` (`tracing`) | `BEST_EFFORT` | `opentelemetry-support` |
| `ShadowListener` (`shadow`) | `BEST_EFFORT` | `cache-support` and `cosec.authorization.shadow.enabled` (default `true`) |

Any `DecisionListener` bean of yours joins them; names must be unique, or startup fails
([Extending](../architecture/extending.md)). An `AuditEventSink` written for 7.x is not picked up on its own: register
`AuditListener.of(sink, "my-audit")` as a `DecisionListener` bean. It is always `BEST_EFFORT`, because
`AuditEventSink.publish` cannot acknowledge a write.

- **Break-glass needs a `REQUIRED` listener.** A request permitted through a break-glass binding is `503` unless at
  least one `REQUIRED` listener is registered and every one of them records it. Out of the box no listener is
  `REQUIRED`, so the starter logs at startup
  `No REQUIRED decision listener is registered: break-glass bindings cannot permit (RFC 0006 §9.4.3).` Set
  `cosec.audit.kafka.delivery=required`, or register a `REQUIRED` listener of your own, before you rely on break-glass.
- **A `REQUIRED` listener should be idempotent** by the event's `id`: a write that timed out may still have succeeded.
- **On shutdown**, events still queued for `BEST_EFFORT` listeners are dropped.

## Audit events

The audit listeners map each decision event to an `AuditEvent`. It keeps what the response body leaves out: explicit
and implicit denies apart, why a credential was rejected, and why a request was `503`.

| Field | Contents |
|---|---|
| `eventId`, `timestamp`, `tenantId` | the event; `tenantId` is the principal's tenant |
| `kind` | `DECIDED`, `BAD_REQUEST` (`400`), `AUTHENTICATION_UNAVAILABLE`, `CANCELLED` or `ERROR` (`500`) |
| `principal` | `id`, `authenticated`, `roles` (the roles of the resolved scope); `policies` is always empty |
| `request` | `id` (the `CoSec-Request-Id`), `appId`, `spaceId`, `remoteIp`, `method`, `path` (normalized), `device.id` |
| `authorization.decision`, `authorization.reasonCode` | see the table below |
| `authorization.reason` | the outcome, and for a `503` its cause, such as `INDETERMINATE: SourceUnavailable` |
| `authorization.elapsedNanos` | the whole pipeline, from the start of the request to its outcome |
| `authorization.source` | `type`, and the deciding `policy` (`id`, `type`, `statement.index`, `statement.name`) |
| `binding` | how the deciding statement applied: a binding id, `attached:<policyId>`, `builtin` (the blacklist) or `boundary:<tenantId>` |
| `snapshotVersion` | the revisions the decision read: `platform`, and `app` and `tenant` when it read them |
| `breakGlass` | whether the permit came through a break-glass binding |
| `trace` | `traceId` and `spanId` of the request's span, when tracing is active |

| Outcome | `decision` | `reasonCode` |
|---|---|---|
| permit | `ALLOW` | `ALLOW` |
| `429` | `TOO_MANY_REQUESTS` | `TOO_MANY_REQUESTS` |
| a DENY matched | `EXPLICIT_DENY` | `EXPLICIT_DENY` |
| scope rejected | `EXPLICIT_DENY` | `SCOPE_REJECTED` |
| a permit outside the tenant's boundary | `EXPLICIT_DENY` | `BOUNDARY` |
| nothing allowed | `IMPLICIT_DENY` | `IMPLICIT_DENY` |
| any deny of a request whose credential was rejected | as above | `TOKEN_EXPIRED`, `TOKEN_REVOKED` or `TOKEN_INVALID` (also for an API key) |
| `400` | `EXPLICIT_DENY` | `EXPLICIT_DENY` (with `kind` `BAD_REQUEST`) |
| `503`, a strict rate-limit rule that could not be counted | `INDETERMINATE` | `RATE_LIMITER_UNAVAILABLE` |
| any other `503`, and a cancelled request | `INDETERMINATE` | `INDETERMINATE` |
| `500` | `ERROR` | `ERROR` |

`authorization.source.type` says where the deciding statement came from: `BLACKLIST` (the built-in blacklist),
`ROOT` (a break-glass binding of `cosec:root`), `ROLE_PERMISSION` (a binding of an app document),
`PRINCIPAL_POLICY` (an attached policy), `GLOBAL_POLICY` (a binding of the platform or a tenant document, or a
boundary), or `NONE` when no statement decided.

### Audit log and Kafka

The audit log listener writes each event as one JSON line to the logger `me.ahoo.cosec.audit`: `ALLOW` at `DEBUG`,
denials, `TOO_MANY_REQUESTS` and `INDETERMINATE` at `WARN`, `ERROR` at `ERROR`. At the default `INFO` level, only
denials, `503`s and errors appear.

The Kafka audit listener writes the same JSON to [`cosec.audit.kafka.topic`](./configuration.md#audit)
(`cosec-audit`), keyed by `[tenantId, principalId]` as a JSON array. It runs alongside the audit log, not instead of
it; set `cosec.audit.enabled=false` and register only what you want to change that. It sends from a thread of its own
with at most 1,024 sends waiting:

- `best-effort`: a failed send is logged as `Failed to publish audit event to Kafka topic [<topic>].`
- `required`: the request waits for the broker's acknowledgement. A failed or late send, or a full send queue
  (`The Kafka audit queue of [<topic>] is full.`), makes a permit `503`. Configure the producer with `acks=all`.

## Tracing

With `opentelemetry-support`, each decision event becomes a span named `cosec.authorize` (instrumentation
`me.ahoo.cosec`, kind `INTERNAL`). The span is made after the decision, from the request's start time and the
pipeline's duration, under the span that was current when the adapter received the request; without one, it is a root
span. Tracing therefore never delays or fails a request. Calls the pipeline makes for the request (Redis, a membership
provider) are siblings of `cosec.authorize`, under the same parent. The tracer comes from `GlobalOpenTelemetry`.

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

## Metrics

With `micrometer-support` and a `MeterRegistry` (for example from Spring Boot Actuator), every decision event is
recorded by the timer `cosec.authorization`, over the whole pipeline. Every tag has a small fixed set of values, so the
number of time series stays bounded however many policies, apps and tenants there are:

| Tag | Values |
|---|---|
| `result` | `allow`, `too_many_requests`, `explicit_deny` (scope rejections and boundary denies included), `implicit_deny`, `indeterminate` (every `503`), `bad_request`, `cancelled`, `error` |
| `source` | `binding`, `attachment`, `builtin`, `boundary`, `scope` (a scope rejection), `none` |
| `cause` | on `indeterminate` only: `source_unavailable`, `missing_policies`, `membership`, `attribute`, `matcher`, `authenticator`, `required_listener`, `boundary`, `rate_limiter`, `deadline`, `unknown` |

The listeners themselves are counted:

| Meter | Tag | Counts |
|---|---|---|
| `cosec.listener.dropped` | `listener` | events a full queue dropped |
| `cosec.listener.failures` | `listener` | listener calls that threw or, for `REQUIRED` ones, timed out |
| `cosec.audit.required.failures` | none | `REQUIRED` failures on decisions they could not change (not permits) |

Alert on the rate of `result=indeterminate` by `cause`, on `explicit_deny`, and on `cosec.listener.dropped`. A rise of
`indeterminate` with `cause=source_unavailable` means documents cannot be read
([Troubleshooting](./troubleshooting.md#unavailable)). CoCache records its own meters for CoSec's caches in the same
registry ([CoCache's documentation](https://cocache.ahoo.me)).

## Snapshot health

CoSec registers no meters for the documents an instance holds. To see whether instances are current:

- **Lag logs.** An instance logs a document not confirmed by Redis for longer than `lag-warn` at `WARN`, and longer
  than `lag-escalate` at `ERROR` ([How instances read documents](./storage.md#how-instances-read-documents)). Other
  lines to alert on: `Snapshot document [<doc>] disappeared from the store; failing closed.`,
  `Snapshot document [<doc>] regressed from revision <a> to <b>; ...`, and
  `Snapshot prewarm failed for [<docs>]; retrying in <backoff>.`
- **Propagation.** The control plane's
  `GET /documents/<doc>/revisions/<n>/propagation` answers how many of the instances that reported in the last minute
  have revision `<n>` or later installed. Instances report only with shadow windows on
  ([Revisions, history and rollback](./storage.md#revisions-history-and-rollback)).
- **Per decision.** `snapshotVersion` in the audit event and `cosec.snapshot.version` on the span name the revisions a
  decision read.

## Control-plane change log

The control plane emits a change event for every step of a change: `PROPOSED`, `CHECKED`, `SHADOW_STARTED`,
`SHADOWED`, `APPROVED`, `REJECTED`, `PUBLISHING`, `PUBLISHED`, `FAILED`, `BREAK_GLASS_REQUESTED`,
`BREAK_GLASS_APPROVED`, `REVOKED`. An event names the change, the document, the operator, the base and candidate
revisions, what needed approval, the confirmations, the shadow summary and the note; it never carries a document's
content. Change listeners receive them.

- **The default listener** logs each event at `INFO`, as
  `Change <changeId> <kind> on <doc> by <principal>@<tenant>: base=..., candidate=..., escalations=<n>, confirmations=<n>`.
  It is registered only while the application registers no `ChangeListener` of its own.
- **`PUBLISHING`, `BREAK_GLASS_REQUESTED` and `BREAK_GLASS_APPROVED` are gated:** every `REQUIRED` change listener must
  record the event within 5 seconds before the step takes effect, or the step does not happen and the control API
  answers `503`. Every other event, and every event to a `BEST_EFFORT` listener, is delivered afterwards; a failure is
  logged as `Change listener [<name>] could not record <event>.` The revision record is the durable record of what was
  published.
- **Break-glass needs a `REQUIRED` change listener:** without one, creating or changing a break-glass binding answers
  `503` (`break-glass needs a REQUIRED change listener, and none is configured`).

To keep the change log in Kafka, register a `KafkaChangeListener`; the starter does not create one. It writes each
event to its topic (`cosec-control-audit` by default), keyed by the document (`platform`, `app:order`,
`tenant:acme`), so a document's events stay in order:

```kotlin
@Bean
fun cosecKafkaChangeListener(kafka: KafkaOperations<String, String>): KafkaChangeListener =
    KafkaChangeListener(kafka, delivery = Delivery.REQUIRED)
```

Registering it replaces the default log listener; register a `LoggingChangeListener` bean too to keep both.

## Logs

| Logger | What it logs |
|---|---|
| `me.ahoo.cosec.audit` | the audit log ([Audit log and Kafka](#audit-log-and-kafka)) |
| `me.ahoo.cosec.runtime` | `Request [<requestId>] failed with an unmodelled exception.` at `ERROR` with the stack trace, for every `500`; `REQUIRED` listener failures; dropped events; attribute providers that fail; rate limiters that cannot count |
| `me.ahoo.cosec.snapshot` | loading, lag, lost and regressed documents ([Snapshot health](#snapshot-health)) |
| `me.ahoo.cosec.spring.boot.starter` | the startup checks, such as the `!!!` eviction warning |
| `me.ahoo.cosec.control` | the change log, and `Control API <method> <path> failed (request <requestId>).` for every control-plane `500` |

Correlate a response with its log lines, audit event and span through the `CoSec-Request-Id` response header, which is
also the `requestId` of every problem body.

**Next:** go from a symptom to its cause, in [Troubleshooting](./troubleshooting.md).
