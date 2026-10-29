---
title: "可观测性"
description: "看清 CoSec 做出的每个决策以及文档的每次变更：决策监听器、审计事件、追踪、指标和日志。"
---

# 可观测性 {#observability}

面向需要知道 CoSec 决定了什么、为什么、以及谁改了规则的运维和安全团队。本页介绍观察每个请求的决策监听器，它们产生的
审计事件、span 和指标，如何查看实例是否跟上了文档，控制面的变更日志，以及日志。要看到一个决策检查过的每条语句（而
不只是做出决定的那条），通过控制面对它做 explain（[解释一个决策](../integration/checks.md)）。

## 决策监听器 {#decision-listeners}

经过管道的每个请求，无论结果如何，最后都产生一个决策事件：放行、拒绝、`429`、`400`、`503`、缺陷（`500`），或者调用方
已离开。决策监听器接收这些事件；审计日志、Kafka、指标、追踪和影子窗口都是监听器。监听器只做观察：它不能改变决策，唯一
的例外是失败的 `REQUIRED` 监听器会把放行变成 `503`。

| 投递方式 | 监听器何时运行 | 失败的后果 |
|---|---|---|
| `REQUIRED` | 在响应之前，与其他 `REQUIRED` 监听器一起运行，每个都在 [`cosec.audit.required.timeout`](./configuration.md#audit)（1 秒）之内，并且在 `cosec.pipeline.timeout` 剩余的时间之内。它看到的是到此为止决定的结果。 | 放行变成 `503` `unavailable`，请求不会继续。其他结果保持原状态；失败以 `ERROR` 记录并计数。 |
| `BEST_EFFORT`（默认） | 结果确定之后，在请求路径之外运行。每个监听器有自己容量为 [`cosec.observability.listener.queue-capacity`](./configuration.md)（1,024）个事件的队列和自己的消费者，所以慢的监听器绝不会让别的监听器丢事件。它看到的是最终结果。 | 对请求没有影响。队列满时丢弃事件；抛异常的监听器最多每 10 秒以 `WARN` 记录一次，两者都会计数。 |

starter 注册的监听器：

| 监听器（`name`） | 投递方式 | 存在条件 |
|---|---|---|
| `LoggingAuditListener`（`logging-audit`） | `BEST_EFFORT` | `cosec.audit.enabled`（默认 `true`） |
| `KafkaAuditListener`（`kafka-audit`） | [`cosec.audit.kafka.delivery`](./configuration.md#audit)，默认 `BEST_EFFORT` | `cosec-kafka`、一个 `KafkaOperations` bean、`cosec.audit.enabled` 和 `cosec.audit.kafka.enabled`（都默认 `true`） |
| `MetricsListener`（`metrics`） | `BEST_EFFORT` | `cosec-micrometer`、一个 `MeterRegistry` bean 和 `cosec.metrics.enabled`（默认 `true`） |
| `TracingListener`（`tracing`） | `BEST_EFFORT` | `cosec-opentelemetry` |
| `ShadowListener`（`shadow`） | `BEST_EFFORT` | `cosec-cocache` 和 `cosec.authorization.shadow.enabled`（默认 `true`） |

你自己的任何 `DecisionListener` bean 都会加入它们；名字必须唯一，否则启动失败（[扩展](../architecture/extending.md)）。
`AuditEventSink` bean（例如为 7.x 编写的）也会加入：starter 把它包装成以 bean 名命名的 `BEST_EFFORT` 监听器
（`AuditListener.of(sink, beanName)`），按它的 `@Order` 排在监听器之间。它总是 `BEST_EFFORT`，因为
`AuditEventSink.publish` 无法确认写入。同时是两者的 bean 只注册一次，作为它本身的 `DecisionListener`。

- **break-glass 需要 `REQUIRED` 监听器。** 通过 break-glass 绑定放行的请求是 `503`，除非至少注册了一个 `REQUIRED`
  监听器并且每一个都记录了它。开箱时没有任何监听器是 `REQUIRED`，所以 starter 在启动时记录
  `No REQUIRED decision listener is registered: break-glass bindings cannot permit (RFC 0006 §9.4.3).` 依赖
  break-glass 之前，设置 `cosec.audit.kafka.delivery=required`，或者注册你自己的 `REQUIRED` 监听器。
- **`REQUIRED` 监听器应按事件的 `id` 保持幂等**：超时的写入可能其实已经成功。
- **关闭时**，监听器停止接收事件，已在队列中的事件仍会继续投递，最多
  [`cosec.observability.listener.drain-timeout`](./configuration.md)（5 秒）。之后剩下的被丢弃，并以 `WARN` 记录
  `Decision listeners did not deliver every queued event within <timeout>; the rest is dropped.`
- **求值追踪。** `DecisionEvent` 带有 `evaluationTrace`，即引擎查看过的内容，深度由
  [`cosec.pipeline.trace-level`](./configuration.md#pipeline) 决定：`off`（默认，没有追踪，也没有开销）、`full`
  （决策到达的每一层的每条规则，包括决定性规则之后的规则）或 `exhaustive`（还包括决策没有到达的层）。在 `off` 时，以及
  请求没有到达引擎时，它为 null。只有你自己的监听器会读它：下面的审计事件、span 和指标都不包含它。
- **gateway 模式的服务**（`cosec.pipeline.mode=gateway`）不产生决策事件：它们不做决策，也不注册监听器。由前面的网关
  记录决策（[部署网关服务](./deployment.md#services-behind-the-gateway)）。

## 审计事件 {#audit-events}

审计监听器把每个决策事件映射为一个 `AuditEvent`。它保留响应体省略的内容：显式拒绝和隐式拒绝的区别、凭证被拒绝的
原因，以及请求为什么是 `503`。

| 字段 | 内容 |
|---|---|
| `eventId`、`timestamp`、`tenantId` | 事件本身；`tenantId` 是主体的租户 |
| `kind` | `DECIDED`、`BAD_REQUEST`（`400`）、`AUTHENTICATION_UNAVAILABLE`、`CANCELLED` 或 `ERROR`（`500`） |
| `principal` | `id`、`authenticated`、`roles`（解析出的作用域中的角色）；`policies` 总是空的 |
| `request` | `id`（即 `CoSec-Request-Id`）、`appId`、`spaceId`、`remoteIp`、`method`、`path`（规范化后）、`device.id` |
| `authorization.decision`、`authorization.reasonCode` | 见下表 |
| `authorization.reason` | 结果，`503` 时还有其原因，例如 `INDETERMINATE: SourceUnavailable` |
| `authorization.elapsedNanos` | 整个管道，从请求开始到得出结果 |
| `authorization.source` | `type`，以及做出决定的 `policy`（`id`、`type`、`statement.index`、`statement.name`） |
| `binding` | 做出决定的语句通过什么生效：绑定 id、`attached:<policyId>`、`builtin`（黑名单）或 `boundary:<tenantId>` |
| `snapshotVersion` | 决策读取的修订：`platform`，以及读取了时的 `app` 和 `tenant` |
| `breakGlass` | 放行是否来自 break-glass 绑定 |
| `trace` | 追踪生效时请求 span 的 `traceId` 和 `spanId` |

| 结果 | `decision` | `reasonCode` |
|---|---|---|
| 放行 | `ALLOW` | `ALLOW` |
| `429` | `TOO_MANY_REQUESTS` | `TOO_MANY_REQUESTS` |
| 匹配到 DENY | `EXPLICIT_DENY` | `EXPLICIT_DENY` |
| 作用域被拒绝 | `EXPLICIT_DENY` | `SCOPE_REJECTED` |
| 超出租户边界的放行 | `EXPLICIT_DENY` | `BOUNDARY` |
| 没有任何允许 | `IMPLICIT_DENY` | `IMPLICIT_DENY` |
| 凭证被拒绝的请求的任何拒绝 | 同上 | `TOKEN_EXPIRED`、`TOKEN_REVOKED` 或 `TOKEN_INVALID`（API key 也是）；带 `act` 声明的令牌为 `DELEGATION_UNSUPPORTED`，响应报告为 `token-invalid`。主体仍是匿名主体；已知时 `authorization.reason` 指明被拒绝的是谁的凭证（不能用的 key、成员关系提供者不认识或认为已停用的主体），与决策事件的 `subject.refused` 一致 |
| `400` | `EXPLICIT_DENY` | `EXPLICIT_DENY`（`kind` 为 `BAD_REQUEST`） |
| `503`，严格的限流规则无法计数 | `INDETERMINATE` | `RATE_LIMITER_UNAVAILABLE` |
| 其他任何 `503`，以及被取消的请求 | `INDETERMINATE` | `INDETERMINATE` |
| `500` | `ERROR` | `ERROR` |

`authorization.source.type` 说明做出决定的语句来自哪里：`BLACKLIST`（内置黑名单）、`ROOT`（`cosec:root` 的 break-glass
绑定）、`ROLE_PERMISSION`（应用文档中的绑定）、`PRINCIPAL_POLICY`（附加策略）、`GLOBAL_POLICY`（平台文档或租户文档中
的绑定，或边界），没有语句做出决定时为 `NONE`。

### 审计日志与 Kafka {#audit-log-and-kafka}

审计日志监听器把每个事件写成一行 JSON，写到 logger `me.ahoo.cosec.audit`：`ALLOW` 用 `DEBUG`，拒绝、
`TOO_MANY_REQUESTS` 和 `INDETERMINATE` 用 `WARN`，`ERROR` 用 `ERROR`。在默认的 `INFO` 级别下，只出现拒绝、`503` 和
错误。

Kafka 审计监听器把同样的 JSON 写到 [`cosec.audit.kafka.topic`](./configuration.md#audit)（`cosec-audit`），key 是
JSON 数组 `[tenantId, principalId]`。它与审计日志并存，而不是替代它；要改变这一点，设置 `cosec.audit.enabled=false`
并只注册你想要的。它从自己的线程发送，最多有 1,024 个发送在等待：

- `best-effort`：发送失败记录为 `Failed to publish audit event to Kafka topic [<topic>].`
- `required`：请求等待 broker 的确认。发送失败或超时，或发送队列已满（`The Kafka audit queue of [<topic>] is full.`），
  都会使放行变成 `503`。生产者请配置 `acks=all`。

## 追踪 {#tracing}

有 `cosec-opentelemetry` 时，每个决策事件变成一个名为 `cosec.authorize` 的 span（instrumentation `me.ahoo.cosec`，
kind 为 `INTERNAL`）。span 在决策之后根据请求的开始时间和管道的耗时生成，挂在适配器收到请求时的当前 span 之下；没有
当前 span 时它是根 span。因此追踪从不拖慢请求，也不会使请求失败。管道为请求发起的调用（Redis、成员关系提供者）是
`cosec.authorize` 的兄弟 span，挂在同一个父 span 下。tracer 来自 `GlobalOpenTelemetry`。

| 属性 | 值 |
|---|---|
| `cosec.tenant_id`、`user.id`、`cosec.request_id` | 主体的租户和 id，以及请求 id |
| `cosec.app_id`、`cosec.space_id`、`device.id` | 解析出的作用域和设备，非空时才有 |
| `cosec.authorize.result` | `PERMIT`、`TOO_MANY_REQUESTS`、`EXPLICIT_DENY`、`IMPLICIT_DENY`、`SCOPE_REJECTED`、`BOUNDARY`、`INDETERMINATE`、`AUTHENTICATOR_UNAVAILABLE`、`DEADLINE_EXCEEDED`、`INVALID_REQUEST`、`CANCELLED` 或 `ERROR` |
| `cosec.authorize.policy.id`、`cosec.authorize.statement.index`、`cosec.authorize.statement.name` | 做出决定的语句 |
| `cosec.binding.id` | 语句通过绑定生效时的绑定 |
| `cosec.snapshot.version` | 读取的修订，格式为 `<platform>/<app>/<tenant>`，未读取的文档写 `-` |

`500` 会把 span 的状态设为 `ERROR`。审计事件和 problem 响应体带有请求 span 的 trace id，把一个响应和它的追踪联系起来。

## 指标 {#metrics}

有 `cosec-micrometer` 和一个 `MeterRegistry`（例如来自 Spring Boot Actuator）时，每个决策事件都由计时器
`cosec.authorization` 记录，覆盖整个管道。每个标签的取值都是一个固定的小集合，因此无论有多少策略、应用和租户，时间序列
的数量都有界：

| 标签 | 取值 |
|---|---|
| `result` | `allow`、`too_many_requests`、`explicit_deny`（包括作用域拒绝和边界拒绝）、`implicit_deny`、`indeterminate`（每个 `503`）、`bad_request`、`cancelled`、`error` |
| `source` | `binding`、`attachment`、`builtin`、`boundary`、`scope`（作用域拒绝）、`none` |
| `cause` | 仅用于 `indeterminate`：`source_unavailable`、`missing_policies`、`membership`、`attribute`、`matcher`、`authenticator`、`required_listener`、`boundary`、`rate_limiter`、`deadline`、`unknown` |

监听器本身也被计数：

| 指标 | 标签 | 计数内容 |
|---|---|---|
| `cosec.listener.dropped` | `listener` | 队列满时丢弃的事件 |
| `cosec.listener.failures` | `listener` | 抛异常的监听器调用，以及超时的 `REQUIRED` 监听器调用 |
| `cosec.audit.required.failures` | 无 | 发生在它无法改变的决策（非放行）上的 `REQUIRED` 失败 |

按 `cause` 对 `result=indeterminate` 的速率、对 `explicit_deny` 以及对 `cosec.listener.dropped` 设置告警。
`indeterminate` 伴随 `cause=source_unavailable` 上升，说明文档读不到（[故障排查](./troubleshooting.md#unavailable)）。
CoCache 会在同一个 registry 中为 CoSec 的缓存记录它自己的指标（[CoCache 文档](https://cocache.ahoo.me)）。

## 快照健康状况 {#snapshot-health}

CoSec 不为实例持有的文档注册指标。要查看实例是否跟上了文档：

- **健康。** 有 Spring Boot Actuator 时，`cosecSnapshot` 健康指示器在平台文档和每个预热文档安装后为 `UP`，之前为
  `DOWN`，细节为 `platform`（`installed`、`failed`、`absent` 或 `loading`）和 `prewarmed`。就绪状态遵循同样的条件
  （[就绪](./deployment.md#readiness)）。
- **滞后日志。** 文档超过 `lag-warn` 未被 Redis 确认时，实例以 `WARN` 记录，超过 `lag-escalate` 时以 `ERROR` 记录
  （[实例如何读取文档](./storage.md#how-instances-read-documents)）。其他值得告警的日志：
  `Snapshot document [<doc>] disappeared from the store; failing closed.`、
  `Snapshot document [<doc>] regressed from revision <a> to <b>; ...`，以及
  `Snapshot prewarm failed for [<docs>]; retrying in <backoff>.`
- **传播情况。** 控制面的 `GET /documents/<doc>/revisions/<n>/propagation` 回答最近一分钟内报告过的实例中，有多少已经
  装入修订 `<n>` 或更新的修订。只有开启影子窗口的实例才会报告（[修订、历史与回滚](./storage.md#revisions-history-and-rollback)）。
- **每个决策。** 审计事件中的 `snapshotVersion` 和 span 上的 `cosec.snapshot.version` 指明决策读取的修订。

## 控制面变更日志 {#control-plane-change-log}

控制面为变更的每一步发出一个变更事件：`PROPOSED`、`CHECKED`、`SHADOW_STARTED`、`SHADOWED`、`APPROVED`、`REJECTED`、
`PUBLISHING`、`PUBLISHED`、`FAILED`、`BREAK_GLASS_REQUESTED`、`BREAK_GLASS_APPROVED`、`REVOKED`。事件指明变更、文档、
操作者、基准修订和候选修订、需要审批的内容、确认、影子窗口摘要和备注；它从不携带文档内容。变更监听器接收这些事件。

- **默认监听器**以 `INFO` 记录每个事件，格式为
  `Change <changeId> <kind> on <doc> by <principal>@<tenant>: base=..., candidate=..., escalations=<n>, confirmations=<n>`。
  变更监听器是累加的：每个 `ChangeListener` bean 都会注册，只有应用自己声明了 `LoggingChangeListener` bean 时才不再
  注册默认的日志监听器。
- **`PUBLISHING`、`BREAK_GLASS_REQUESTED` 和 `BREAK_GLASS_APPROVED` 是受控的：** 每个 `REQUIRED` 变更监听器都必须在
  [`cosec.control.audit.required-timeout`](./configuration.md#control-plane)（5 秒，所有监听器合计）内记录该事件，这一步才会生效，否则这一步不会发生，控制 API 返回 `503`。其他事件，以及发给 `BEST_EFFORT` 监听器的
  所有事件，都在之后投递；失败记录为 `Change listener [<name>] could not record <event>.` 修订记录是已发布内容的持久
  记录。
- **break-glass 需要 `REQUIRED` 变更监听器：** 没有时，创建或修改 break-glass 绑定得到 `503`
  （`break-glass needs a REQUIRED change listener, and none is configured`）。

有 `cosec-kafka` 和 `KafkaOperations` bean 时，starter 还会注册一个 `KafkaChangeListener`，除非
[`cosec.control.audit.kafka.enabled`](./configuration.md#control-plane) 为 `false`。它把每个事件写到
`cosec.control.audit.kafka.topic`（`cosec-control-audit`），key 是文档（`platform`、`app:order`、`tenant:acme`），因此
一个文档的事件保持有序。它的投递方式是 `cosec.control.audit.kafka.delivery`：默认 `best-effort`；`required` 让发布和
break-glass 等待 broker 的确认（把生产者配置为 `acks=all`）。设为 `delivery=required` 时，如果 Kafka 变更监听器无法
创建（没有 `cosec-kafka`、没有 `KafkaOperations` bean，或 `enabled=false`），启动失败，而不是在没有记录的情况下发布：
`cosec.control.audit.kafka.delivery=required, but no Kafka change listener was created: ...`。

## 日志 {#logs}

| Logger | 记录内容 |
|---|---|
| `me.ahoo.cosec.audit` | 审计日志（[审计日志与 Kafka](#audit-log-and-kafka)） |
| `me.ahoo.cosec.runtime` | 每个 `500` 都以 `ERROR` 记录 `Request [<requestId>] failed with an unmodelled exception.` 及堆栈；`REQUIRED` 监听器失败；被丢弃的事件；失败的属性提供者；无法计数的限流器 |
| `me.ahoo.cosec.runtime.snapshot` | 加载、滞后、丢失和回退的文档（[快照健康状况](#snapshot-health)） |
| `me.ahoo.cosec.spring.boot.starter` | 启动检查，例如 `!!!` 驱逐警告；应用没有声明 `BlacklistSource` 时以 `INFO` 记录的 `No BlacklistSource bean: requests are not checked against a blacklist (ext.blacklist.*).`（[黑名单](../guide/security-model.md#blacklist)）；gateway 模式服务的 `WARN`（[网关后面的服务](./deployment.md#services-behind-the-gateway)） |
| `me.ahoo.cosec.control` | 变更日志，以及每个控制面 `500` 的 `Control API <method> <path> failed (request <requestId>).` |

通过响应头 `CoSec-Request-Id` 把一个响应与它的日志、审计事件和 span 关联起来；它也是每个 problem 响应体中的
`requestId`。

**下一步：** 从症状找到原因，见[故障排查](./troubleshooting.md)。
