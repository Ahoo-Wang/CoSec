---
title: "Observability"
description: "See every decision CoSec makes: audit events, traces, metrics and logs."
---

# Observability

For operators and security teams who need to know what CoSec decided and why. This page covers the audit event
emitted for every decision, the tracing span, the metrics and the logs, what each one tells you, and how to turn
them on. To see every rule a decision checked, not only the one that decided, use the
[explainer](#explaining-a-decision).

## The decision chain

The `Authorization` bean is a chain of decorators around `SimpleAuthorization`, from the outside in: tracing,
auditing, metrics, rate limiting, shadow mode. Each decorator sees the decision of the one inside it, so audit
events, metrics and spans all report the final decision, rate limiting included. Why the chain is ordered this way
is in [Authorization chain](../architecture/overview.md#authorization-chain).

| Decorator | Present when |
|---|---|
| `TracingAuthorization` | `opentelemetry-support` is on the classpath |
| `AuditingAuthorization` | `cosec.audit.enabled` (default `true`) |
| `MeteredAuthorization` | `micrometer-support`, a `MeterRegistry` bean, and `cosec.metrics.enabled` (default `true`) |
| `RateLimitingAuthorization` | `cosec.limiter.enabled` (default `true`), even when no rule file exists |
| `ShadowAuthorization` | `cosec.shadow.enabled=true` ([Shadow mode](../policies/testing.md#trying-changes-in-shadow-mode)) |

The layer of `SimpleAuthorization` that decided ([Evaluation order](../guide/security-model.md#evaluation-order)) is
reported as the decision's **source**.

None of the observing decorators changes the decision: an audit sink that fails is logged and ignored.

## Audit events

With `cosec.audit.enabled` (the default), every decision produces an `AuditEvent`, also when authorization fails
with an error.

| Field | Contents |
|---|---|
| `eventId`, `timestamp`, `tenantId` | the event |
| `principal` | `id`, `authenticated`, `roles`, `policies` |
| `request` | `id` (the `CoSec-Request-Id`), `appId`, `spaceId`, `remoteIp`, `method`, `path`, `routeId` (Spring Cloud Gateway), `device.id`, `device.userAgent` |
| `authorization.decision` | `ALLOW`, `EXPLICIT_DENY`, `IMPLICIT_DENY`, `TOO_MANY_REQUESTS` or `ERROR` |
| `authorization.reasonCode` | `ALLOW`, `EXPLICIT_DENY`, `IMPLICIT_DENY`, `TOKEN_EXPIRED`, `TOKEN_INVALID`, `TOO_MANY_REQUESTS`, `REGEX_TIMEOUT` or `ERROR` |
| `authorization.reason`, `authorization.elapsedNanos` | the reason text and the evaluation time |
| `authorization.source` | `type`: `BLACKLIST`, `ROOT`, `GLOBAL_POLICY`, `PRINCIPAL_POLICY`, `ROLE_PERMISSION`, `NONE` (nothing matched) or `UNKNOWN`; with the matched `policy` (`id`, `type`, `statement.index`, `statement.name`) or `role` (`id`, `permission.id`, `permission.name`) |
| `trace` | `traceId` and `spanId`, when tracing is active |

A request denied because its token was rejected carries `TOKEN_EXPIRED` or `TOKEN_INVALID`, so a burst of expired
tokens is told apart from a broken policy.

### Sinks

Events go to exactly one `AuditEventSink`:

| Sink | Used when | Where events go |
|---|---|---|
| your `AuditEventSink` bean | you declare one | anywhere you send them |
| `KafkaAuditEventSink` | `kafka-support`, a `KafkaTemplate` bean, `cosec.audit.kafka.enabled` (default `true`), and no sink of yours | the topic [`cosec.audit.kafka.topic`](./configuration.md#audit) (`cosec-audit`) |
| `LoggingAuditEventSink` | otherwise | the logger `me.ahoo.cosec.audit`, one JSON line per event |

The logging sink writes `ALLOW` at `DEBUG`, denials and `TOO_MANY_REQUESTS` at `WARN`, and `ERROR` at `ERROR`, so at
the default `INFO` level only denials and errors appear.

The Kafka sink keys each record by tenant and principal id (a JSON array `[tenantId, principalId]`) and the value is
the event as JSON. It sends from one background thread with a queue of 1,024 events, which keeps events in order and
never blocks a request. When the queue is full, events are dropped and a warning is logged once
(`Dropping audit events because the Kafka audit queue is full or shutting down.`); a failed send is logged too. On
shutdown it drains the queue for up to 10 seconds.

A custom sink must not block: it runs inline, on the thread that completes the decision. Its exceptions are logged
(`Failed to publish audit event.`) and never affect the decision.

## Tracing

With `opentelemetry-support`, each authorization is a span named `cosec.authorize` (instrumentation
`me.ahoo.cosec`), a child of the current request span:

| Attribute | Value |
|---|---|
| `user.id`, `user.roles` | principal id and roles |
| `cosec.tenant_id`, `cosec.app_id`, `cosec.space_id`, `device.id`, `cosec.request_id` | request context (empty values are left out) |
| `cosec.policies` | the principal's policies |
| `cosec.authorize.result` | `ALLOW`, `EXPLICIT_DENY` or `IMPLICIT_DENY` |
| `cosec.authorize.policy.id`, `cosec.authorize.statement.index`, `cosec.authorize.statement.name` | the matched policy statement |
| `cosec.authorize.role.id`, `cosec.authorize.permission.id` | the matched role permission |

With auditing on as well, audit events carry the span's `traceId` and `spanId`, which ties an event to its trace.

## Metrics

With `micrometer-support` and a `MeterRegistry` (for example from Spring Boot Actuator), every decision is recorded by
the timer `cosec.authorization` (`cosec.metrics.enabled`, default `true`). It has two tags, each with a small fixed
set of values, so the number of time series stays bounded however many policies and principals there are:

| Tag | Values |
|---|---|
| `result` | `allow`, `explicit_deny`, `implicit_deny`, `too_many_requests`, `error` |
| `source` | `global_policy`, `principal_policy`, `role_permission`, `root`, `blacklist`, `none`, `unknown` |

`error` always comes with `source=unknown`. The timer measures authorization only, not token parsing.

Alert on the rate of `explicit_deny` and `error`. A sudden rise usually means an attack, a broken policy, or a policy
store that cannot be read ([Troubleshooting](./troubleshooting.md#every-request-fails-with-500)).

CoCache records its own meters for CoSec's caches (for example `cocache.hits`) in the same registry; see
[CoCache's documentation](https://cocache.ahoo.me).

## Logs

```yaml
logging:
  level:
    me.ahoo.cosec.authorization.SimpleAuthorization: DEBUG   # the matched policy statement or role permission
    me.ahoo.cosec.audit: DEBUG                               # every audit event, allows included
```

Errors need no debug level: `me.ahoo.cosec.pipeline.SecurityPipeline` logs every error that turns a request into a
`500` at `ERROR`, with the stack trace (`Unexpected error during authorization of request [...] [...].`). Correlate
a response with its log lines and audit event through the `CoSec-Request-Id` header.

## Explaining a decision

Audit events, metrics and spans name the one rule that decided. The explainer goes further: it runs the real
authorization for a described principal, or for a real token or API key, and reports every statement and role
permission it checked, which matched, which missed on the action and which on the condition. Nothing is spent or
changed.

It is off by default (`cosec.explain.enabled`), because an explanation reveals your policy structure. With Spring
Boot Actuator it is also the `cosecExplain` endpoint, which goes through CoSec like any other request: expose it only
to administrators, and allow `POST /actuator/cosecExplain` only for them. The request, the response and how to call
it from code are in [Explaining a decision](../integration/checks.md#explaining-a-decision).

**Next:** go from a symptom to its cause, in [Troubleshooting](./troubleshooting.md).
