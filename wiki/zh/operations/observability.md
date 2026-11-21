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
| `REQUIRED` | 在响应之前，与其他 `REQUIRED` 监听器一起运行，每个都在 [`cosec.observability.listener.required-timeout`](./configuration.md#decision-listeners)（1 秒）之内，并且在 `cosec.pipeline.timeout` 剩余的时间之内。它看到的是到此为止决定的结果。 | 放行变成 `503` `unavailable`，请求不会继续。其他结果保持原状态；失败以 `ERROR` 记录并计数。 |
| `BEST_EFFORT`（默认） | 结果确定之后，在请求路径之外运行。每个监听器有自己容量为 [`cosec.observability.listener.queue-capacity`](./configuration.md)（1,024）个事件的队列和自己的消费者，所以慢的监听器绝不会让别的监听器丢事件。它看到的是最终结果。 | 对请求没有影响。队列满时丢弃事件并计数，请求从不等待与之相关的日志：由一个报告者在请求线程之外，最多每 10 秒以 `WARN` 记录一次 `Dropping decision events: the queue of listener [<name>] is full; <n> dropped since the last report.`。抛异常的监听器最多每 10 秒以 `WARN` 记录一次，并会计数。 |

### 每个输出中的“什么做出了决定” {#what-decided-in-every-output}

决策事件的 `outcome.reason` 说明什么做出了决定，每个输出都使用它的词（UD-7）：span 的 `cosec.authorize.result` 原样使用，指标的 `result` 标签用小写，审计的 `decision` 用大写。只有 `explicit_deny` 表示 DENY 语句拒绝了请求。

| `outcome.reason` / span | 指标 `result` | 审计 `decision` | 计数的是 |
|---|---|---|---|
| `PERMIT` | `permit` | `PERMIT` | 被放行的请求 |
| `TOO_MANY_REQUESTS` | `too_many_requests` | `TOO_MANY_REQUESTS` | 被耗尽的限流规则拦下的放行（`429`） |
| `EXPLICIT_DENY` | `explicit_deny` | `EXPLICIT_DENY` | 被 DENY 语句拒绝的请求 |
| `IMPLICIT_DENY` | `implicit_deny` | `IMPLICIT_DENY` | 没有任何允许的请求 |
| `SCOPE_REJECTED` | `scope_rejected` | `SCOPE_REJECTED` | 主体不能在其应用或空间中操作的请求（`403` `scope-rejected`） |
| `BOUNDARY` | `boundary` | `BOUNDARY` | 超出租户平台边界的放行（`403` `forbidden`） |
| `INVALID_REQUEST` | `invalid_request` | `INVALID_REQUEST` | 无法无歧义读取的请求（`400`） |
| `INDETERMINATE`、`AUTHENTICATOR_UNAVAILABLE`、`DEADLINE_EXCEEDED` | `indeterminate`（`cause` 说明是哪种） | `INDETERMINATE` | 无法做出决定的请求（`503`） |
| `ERROR` | `error` | `ERROR` | 缺陷（`500`） |
| `CANCELLED` | `cancelled` | 没有审计记录 | 离开的调用方 |

事件的 `subject.method` 用 `CoSec-Identity` 对同一凭证使用的词说明主体如何认证：CoSec 自己的令牌为 `jwt`，`api-key`，或其他校验器的令牌被赋予的名字（默认 `token`）；没有凭证的主体用它的认证器命名。

starter 注册的监听器：

| 监听器（`name`） | 投递方式 | 存在条件 |
|---|---|---|
| `LoggingAuditListener`（`logging-audit`） | `BEST_EFFORT` | `cosec.audit.enabled`（默认 `true`） |
| `KafkaAuditListener`（`kafka-audit`） | [`cosec.audit.kafka.delivery`](./configuration.md#audit)，默认 `BEST_EFFORT` | `cosec-kafka`、一个 `KafkaOperations` bean、`cosec.audit.enabled` 和 `cosec.audit.kafka.enabled`（都默认 `true`） |
| `MetricsListener`（`metrics`） | `BEST_EFFORT` | `cosec-micrometer`、一个 `MeterRegistry` bean（包括 Spring Boot 自动配置的那个）和 `cosec.metrics.enabled`（默认 `true`） |
| `TracingListener`（`tracing`） | `BEST_EFFORT` | `cosec-opentelemetry` |
| `ShadowListener`（`shadow`） | `BEST_EFFORT` | `cosec-cocache` 和 `cosec.authorization.shadow.enabled`（默认 `true`） |

你自己的任何 `DecisionListener` bean 都会加入它们；名字必须唯一，否则启动失败（[扩展](../architecture/extending.md)）。
`AuditEventSink` bean 也会加入：starter 把它包装成以 bean 名命名的 `BEST_EFFORT` 监听器
（`AuditListener.of(sink, beanName)`），按它的 `@Order` 排在监听器之间。它总是 `BEST_EFFORT`，因为
`AuditEventSink.publish` 无法确认写入。同时是两者的 bean 只注册一次，作为它本身的 `DecisionListener`。

- **影子监听器只看到它采样的请求**（`OBS-8`）。它只采样读取了[暂存列表](./storage.md#redis-keys)所列文档的请求，再按
  `cosec.authorization.shadow.sample-rate` 采样，其他事件都不会交给它。当它是唯一的 `BEST_EFFORT` 监听器时，它没有采样的
  请求根本不生成决策事件，因此没有开启影子窗口时，没有指标、追踪或审计监听器的主机在请求上不会为它做任何事。
  `REQUIRED` 监听器以及其他每个监听器都收到每一个事件。
- **break-glass 需要 `REQUIRED` 监听器。** 通过 break-glass 绑定放行的请求是 `503`，除非至少注册了一个 `REQUIRED`
  监听器并且每一个都记录了它。开箱时没有任何监听器是 `REQUIRED`，所以 starter 在启动时记录
  `No REQUIRED decision listener is registered: break-glass bindings cannot permit (RFC 0006 §9.4.3).` 依赖
  break-glass 之前，设置 `cosec.audit.kafka.delivery=required`，或者注册你自己的 `REQUIRED` 监听器。
- **`REQUIRED` 监听器应按事件的 `id` 保持幂等**：超时的写入可能其实已经成功。
- **关闭时**，监听器停止接收事件，已在队列中的事件仍会继续投递，最多
  [`cosec.observability.listener.drain-timeout`](./configuration.md)（5 秒）。之后不再开始任何监听器调用：进行中的调用不会被中断，剩下的事件被丢弃并计入 `cosec.listener.dropped`，并以 `WARN` 记录
  `Decision listeners did not deliver every queued event within <timeout>; <n> were dropped, and no listener call starts any more.`。随后 Kafka 审计监听器
  最多再用同样长的时间，等待它手中的发送以及 broker 对这些发送的应答，然后才关闭 producer。到达这个上限时，排队的发送
  被丢弃，进行中的发送被中断，并以 `WARN` 记录 `The Kafka audit queue of [<topic>] did not drain within <timeout>: <n> queued records are dropped and <n> sends are unanswered.`
- **求值追踪。** `DecisionEvent` 带有 `evaluationTrace`，即引擎查看过的内容，深度由
  [`cosec.pipeline.trace-level`](./configuration.md#pipeline) 决定：`off`（默认，没有追踪，也没有开销）、`full`
  （列出每个候选的每条语句及其被排除的原因；求值在没有追踪时停止的地方停止，但会完成作出决策的那一轮）或 `exhaustive`（决策已由 DENY 轮作出时，也会求值 ALLOW 轮）。在 `off` 时，以及
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
| `principal` | `id`、`authenticated`、`roles`（解析出的作用域中的角色） |
| `request` | `id`（即 `CoSec-Request-Id`）、`appId`、`spaceId`、`remoteIp`、`method`、`path`（规范化后）、`routeId`（请求经过的网关路由；其他适配器为 null）、`device.id` |
| `authorization.decision`、`authorization.reasonCode` | 见下表 |
| `authorization.reason` | 结果，`503` 时还有其原因，例如 `INDETERMINATE: SourceUnavailable` |
| `authorization.elapsedNanos` | 整个管道，从请求开始到得出结果 |
| `authorization.source` | 是什么做出了决定：`via`、`document`、`policy`（策略 id）和 `statement`（`index`、`name`）；见下面第二张表 |
| `binding` | 给人看的标签，不要解析它：做出决定的语句通过什么生效，绑定 id（角色派生的绑定为 `role:<roleId>@<app>:<permission>`）、`attached:<policyId>`、`builtin`（黑名单）或 `boundary:<tenantId>` |
| `snapshotVersion` | 决策读取的修订：`platform`，以及读取了时的 `app` 和 `tenant` |
| `breakGlass` | 放行是否来自 break-glass 绑定 |
| `trace` | 追踪生效时请求 span 的 `traceId` 和 `spanId` |

| 结果 | `decision` | `reasonCode` |
|---|---|---|
| 放行 | `PERMIT` | `PERMIT` |
| `429` | `TOO_MANY_REQUESTS` | `TOO_MANY_REQUESTS` |
| 匹配到 DENY | `EXPLICIT_DENY` | `EXPLICIT_DENY` |
| 作用域被拒绝 | `SCOPE_REJECTED` | `SCOPE_REJECTED` |
| 超出租户边界的放行 | `BOUNDARY` | `BOUNDARY` |
| 没有任何允许 | `IMPLICIT_DENY` | `IMPLICIT_DENY` |
| 凭证被拒绝的请求的任何拒绝 | 同上 | `TOKEN_EXPIRED`、`TOKEN_REVOKED` 或 `TOKEN_INVALID`（API key 也是）；带 `act` 声明的令牌为 `DELEGATION_UNSUPPORTED`；`iat` 或 `nbf` 比本实例时钟晚、超出余量的令牌为 `TOKEN_NOT_YET_VALID`（`IDN-29`），响应同样报告为 `token-invalid`。主体仍是匿名主体；已知时 `authorization.reason` 指明被拒绝的是谁的凭证（不能用的 key、成员关系提供者不认识或认为已停用的主体），与决策事件的 `subject.refused` 一致 |
| `400` | `INVALID_REQUEST` | `INVALID_REQUEST`（`kind` 为 `BAD_REQUEST`） |
| `503`，严格的限流规则无法计数 | `INDETERMINATE` | `RATE_LIMITER_UNAVAILABLE` |
| 其他任何 `503`，以及被取消的请求 | `INDETERMINATE` | `INDETERMINATE` |
| `500` | `ERROR` | `ERROR` |

`authorization.source.via` 说明做出决定的内容如何生效，`document` 说明它在哪个快照文档中：

| `via` | `document` | 含义 |
|---|---|---|
| `BINDING` | `PLATFORM`、`APP` 或 `TENANT` | 该文档中的绑定，包括角色派生的绑定；`binding` 是它的 id。break-glass 放行是 `cosec:root` 的绑定，并带 `breakGlass: true` |
| `ATTACHMENT` | `null` | 通过主体的成员关系附加的策略 |
| `BUILTIN` | `null` | 内置黑名单 |
| `BOUNDARY` | `PLATFORM` | 租户的平台边界拒绝了一次放行。`policy` 是该边界，`statement` 为 `null`，因为它没有语句匹配 |
| `NONE` | `null` | 没有语句做出决定：没有任何允许、作用域被拒绝，或没有得出决策。`policy` 和 `statement` 为 `null` |

### 审计日志与 Kafka {#audit-log-and-kafka}

审计日志监听器把每个事件写成一行 JSON，写到 logger `me.ahoo.cosec.audit`：`PERMIT` 用 `DEBUG`，拒绝（各种拒绝和 `INVALID_REQUEST`）、
`TOO_MANY_REQUESTS` 和 `INDETERMINATE` 用 `WARN`，`ERROR` 用 `ERROR`。在默认的 `INFO` 级别下，只出现拒绝、`503` 和
错误。

Kafka 审计监听器把同样的 JSON 写到 [`cosec.audit.kafka.topic`](./configuration.md#audit)（`cosec-audit`），key 是
JSON 数组 `[tenantId, principalId]`。它与审计日志并存，而不是替代它；要改变这一点，设置 `cosec.audit.enabled=false`
并只注册你想要的。它从自己的线程发送，最多有 1,024 个发送在等待：

- `best-effort`：发送失败最多每 10 秒记录一行，带上自上一行以来失败的数量：`Failed to publish audit event to Kafka topic [<topic>]: <n> failed since the last report.`（broker 宕机时每个间隔一行，而不是每个决策一行）
- `required`：请求等待 broker 的确认。发送失败或超时，或发送队列已满（`The Kafka audit queue of [<topic>] is full.`），
  都会使放行变成 `503`。生产者请配置 `acks=all`。

## 追踪 {#tracing}

有 `cosec-opentelemetry` 时，每个决策事件变成一个名为 `cosec.authorize` 的 span（instrumentation `me.ahoo.cosec`，
kind 为 `INTERNAL`）。span 在决策之后根据请求的开始时间和管道的耗时生成，挂在适配器收到请求时的当前 span 之下；没有
当前 span 时它是根 span。因此追踪从不拖慢请求，也不会使请求失败。管道为请求发起的调用（Redis、成员关系提供者）是
`cosec.authorize` 的兄弟 span，挂在同一个父 span 下。tracer 来自 `GlobalOpenTelemetry`。
该 span 像 SDK 自己的子 span 一样继承父 span 的 `tracestate`。事件的 trace 以 W3C 头部形式的不透明字符串携带它：它是厂商数据，CoSec 只把它写到该 span 的父上下文上，不写入审计记录、Kafka 记录、日志或指标标签，你自己的决策监听器也不应记录它。SDK 拒绝的值，或超过 512 个字符的值，视为没有 tracestate：span 仍会生成，父 span 不变。

| 属性 | 值 |
|---|---|
| `cosec.tenant_id`、`user.id`、`cosec.request_id` | 主体的租户和 id，以及请求 id |
| `cosec.app_id`、`cosec.space_id`、`device.id` | 解析出的作用域和设备，非空时才有 |
| `cosec.authorize.result` | `PERMIT`、`TOO_MANY_REQUESTS`、`EXPLICIT_DENY`、`IMPLICIT_DENY`、`SCOPE_REJECTED`、`BOUNDARY`、`INDETERMINATE`、`AUTHENTICATOR_UNAVAILABLE`、`DEADLINE_EXCEEDED`、`INVALID_REQUEST`、`CANCELLED` 或 `ERROR` |
| `cosec.authorize.policy.id`、`cosec.authorize.statement.index`、`cosec.authorize.statement.name` | 做出决定的语句 |
| `cosec.binding.id` | 语句通过绑定生效时的绑定 |
| `cosec.snapshot.version` | 读取的修订，格式为 `<platform>/<app>/<tenant>`，未读取的文档写 `-` |

`500` 会把 span 的状态设为 `ERROR`。审计事件和 problem 响应体带有请求 span 的 trace id，把一个响应和它的追踪联系起来。

## 启动摘要 {#startup-summary}

所有 bean 创建完毕后，starter 记录一行 INFO，说明 CoSec 被配置成什么样，例如 `CoSec 8.0.0-beta.4: mode=enforce, web=servlet-filter, jwt=on (JwtTokenVerifier), token-cache=on (max-entries=16384), revocation-reader=the CoCache token store's (CoCacheRevocationReader), login=on, api-keys=off, rate-limiter=ReactiveRedisRateLimiter, listeners=[tracing, logging-audit], route-map=12 routes`。每一项都是请求实际遇到的东西，按认证器解析的方式得出：吊销读取器是应用的 bean、CoCache 令牌存储的读取器，或 `RevocationReader.NONE`（这一行会明确写出，因为此时被吊销的令牌在过期前都会被接受）；没有令牌校验（`jwt=off`）时没有读取器可写。`token-cache` 说明请求令牌是否从[已校验令牌缓存](../integration/authentication.md#the-verified-token-cache)作答：配置关闭时，或使用你自己的 `TokenVerifier` Bean 时为 `off`。应用声明了 `Authenticator` bean 时，它会替换 starter 自带的（[扩展](../architecture/extending.md)）：这一行会写成 `authenticator=<类名> (replaces CoSec's: tokens, revocation and API keys are its own)`，并且不再列出 `jwt`、`revocation-reader` 和 `api-keys`，因为它们都没有生效。写成 `false` 的开关会以 `switched-off-by-configuration=[...]` 追加。名为 `LocalRateLimiter` 的限流器按实例计数：有多个实例时，限额按实例数倍增。`cosec.enabled=false` 时这一行换成一条 WARN：`CoSec is off ... nothing is protected by CoSec`（[故障排查](./troubleshooting.md#the-log-says-cosec-is-off)）。

## 指标 {#metrics}

有 `cosec-micrometer` 和一个 `MeterRegistry`（例如来自 Spring Boot Actuator）时，每个决策事件都由计时器
`cosec.authorization` 记录，覆盖整个管道。每个标签的取值都是一个固定的小集合，因此无论有多少策略、应用和租户，时间序列
的数量都有界：

| 标签 | 取值 |
|---|---|
| `result` | 什么做出了决定（[每个输出的词](#what-decided-in-every-output)）：`permit`、`too_many_requests`、`explicit_deny`（仅指 DENY 语句）、`implicit_deny`、`scope_rejected`、`boundary`、`invalid_request`（`400`）、`indeterminate`（每个 `503`）、`cancelled`、`error` |
| `source` | `binding`、`attachment`、`builtin`、`boundary`、`scope`（作用域拒绝）、`none` |
| `problem` | 客户端被告知的问题：`unauthorized`（`401`，匿名）、`token-invalid`、`token-expired`、`token-revoked`（`401`）、`forbidden`、`scope-rejected`（`403`）、`too-many-requests`、`invalid-request`、`unavailable`、`internal-error`，请求继续或被取消时为 `none` |
| `cause` | `none`，以及在 `indeterminate` 时：`source_unavailable`、`document_invalid`、`missing_policies`、`membership`、`attribute`、`matcher`、`authenticator`、`required_listener`、`boundary`、`rate_limiter`、`deadline`、`unknown` |

在 `cosec-gateway-server` 镜像上，注册表是 Prometheus 的，端点是管理端口（`8081`）上的 `/actuator/prometheus`，从不在网关的端口上（[镜像](./deployment.md#the-image)）。上面的标签值就是 CoSec 的全部标签；租户、主体、路径、令牌都不会成为标签。

监听器本身也被计数：

| 指标 | 标签 | 计数内容 |
|---|---|---|
| `cosec.listener.dropped` | `listener` | 队列满或已关闭时丢弃的事件，以及排空超时时丢弃的事件 |
| `cosec.listener.failures` | `listener` | 抛异常的监听器调用，以及超时的 `REQUIRED` 监听器调用 |
| `cosec.audit.required.failures` | 无 | 发生在它无法改变的决策（非放行）上的 `REQUIRED` 失败 |

吊销存储无法读取时，流水线未经吊销检查就接受的令牌也会计数（[已写明的例外](../guide/security-model.md#the-two-documented-exceptions)）：

| 指标 | 标签 | 计数 |
|---|---|---|
| `cosec.revocation.unchecked` | 无 | 吊销无法读取时被接受的令牌；每段这样的时间还会以 `WARN` 记录 `Token revocations cannot be read: tokens are accepted without a revocation check (<n> so far).`，最多每 10 秒一次 |

[已校验令牌缓存](../integration/authentication.md#the-verified-token-cache)开启时也会计数：

| 指标 | 标签 | 计数 |
|---|---|---|
| `cosec.token.cache.hits` | 无 | 从缓存作答的请求令牌 |
| `cosec.token.cache.misses` | 无 | 因没有可用条目而校验的请求令牌：首次出现、已被淘汰、被拒绝，或不在它有效的时刻内 |
| `cosec.token.cache.evictions` | 无 | 为不超过 `cosec.authentication.token-cache.max-entries` 而移除的条目；持续的速率说明上限小于同时在用的令牌数 |
| `cosec.token.cache.size` | 无 | 一个 gauge：持有的条目数 |

开启影子窗口时，影子监听器据以采样的暂存列表也会计量（`OBS-8`）：

| 指标 | 标签 | 计数 |
|---|---|---|
| `cosec.shadow.staged.age` | 无 | 一个 gauge，单位秒：本实例上次读取暂存列表距今多久，首次读取之前为 `NaN`。Redis 应答期间约为 `cosec.authorization.shadow.report-interval`（10 秒）；持续上升说明列表读不出来，实例按键读取每个候选，并记录一次 `The staged list cannot be read since it was at <instant>` |

使用本地限流器时（没有 Redis 限流，[限流](../policies/rate-limiting.md#the-local-limiter-is-bounded)），它无法接纳的新桶也会计数：

| 指标 | 标签 | 计数 |
|---|---|---|
| `cosec.ratelimit.local.refused` | `document`：`platform`、`app`、`tenant`、`other` | 因规则的、其文档的或总的上限已满而被拒绝的新桶：每一个都是严格规则回答 `503`、或宽松规则没有限制的请求；日志最多每 10 秒写一次 |

按 `cause` 对 `result=indeterminate` 的速率、对 `explicit_deny`（被 DENY 语句拒绝的请求）、对 `cosec.listener.dropped` 设置告警，并对 `cosec.revocation.unchecked` 的任何上升告警：它上升期间，被吊销的令牌会被接受。`cosec.shadow.staged.age` 超过三个上报间隔，
说明某个实例在按一份它已读不到的列表采样。
`indeterminate` 伴随 `cause=source_unavailable` 上升，说明文档读不到；伴随 `cause=document_invalid` 上升，说明某个已存储的文档在本实例上无法编译（[故障排查](./troubleshooting.md#unavailable)）。
CoCache 会在同一个 registry 中为 CoSec 的缓存记录它自己的指标（[CoCache 文档](https://cocache.ahoo.me)）。

网关模式（`cosec.pipeline.mode=gateway`）下的服务没有管道，不记录决策事件，因此上面的指标都没有。它对自己拒绝的转发身份计数：

| 指标 | 标签 | 计数 |
|---|---|---|
| `cosec.identity.refused` | `problem`：`invalid-request`、`token-invalid`、`token-expired` | 服务拒绝的每个 `CoSec-Identity` 头，包括日志没有打印的那些；原因以 `WARN` 记录，每种问题最多每分钟一次（[故障排查](./troubleshooting.md#a-service-behind-the-gateway-refuses-requests)），从不出现在响应中 |

在网关模式下，任何非零速率都意味着某一跳配置错误，或者有请求没有经过网关；请对它告警。

## 快照指标 {#snapshot-metrics}

在与计时器相同的条件下（`cosec-micrometer`、一个 `MeterRegistry`、`cosec.metrics.enabled`），starter 为数据平面的快照源注册指标：
即本实例读取、编译并持有的文档（[实例如何读取文档](./storage.md#how-instances-read-documents)）。控制平面自己的快照源不计量。
应用自己构建的 `Pipeline` 不会得到这些指标。没有任何标签携带应用或租户 id，因此无论文档有多少，都是 80 个序列。
`kind` 为 `platform`、`app` 或 `tenant`：

| 指标 | 类型 | 标签 | 含义 |
|---|---|---|---|
| `cosec.snapshot.loads` | counter | `kind`、`result` | 必须加载文档的请求：`cold`（请求等待它的加载）、`failed`（加载失败，请求按失败关闭判定）、`timeout`（加载超过了 `cold-load-timeout`）。文档已安装的请求不计数：那是每个请求都走的路径 |
| `cosec.snapshot.compile.duration` | timer | `kind`、`result` = `success`、`failure` | 编译一个文档；`failure` 是一个在本实例上无法编译的已存储文档 |
| `cosec.snapshot.installs` | counter | `kind` | 安装的文档：首次加载或新修订 |
| `cosec.snapshot.installed` | gauge | `kind`、`state` | 当前持有的条目，按状态：`present`、`invalid`（在此无法编译）、`pending`（其输入已变，等待重新编译）、`undecodable`、`absent`（没有文档，之前也没见过）、`lost`（之前见过的文档不见了）、`unreadable`。各状态之和等于实例持有的条目数 |
| `cosec.snapshot.lag` | gauge，秒 | `kind` | 持有的条目中（失败的除外）最久未被存储确认的时长 |
| `cosec.snapshot.lagging` | gauge | `kind`、`level` = `warn`、`escalate` | 超过 `lag-warn` 的条目（包括超过 `lag-escalate` 的）和超过 `lag-escalate` 的条目 |
| `cosec.snapshot.summary.age` | gauge，秒 | 无 | 距 `installed`、`lag` 和 `lagging` 所读的那一轮的时间 |
| `cosec.snapshot.lag.exceeded` | counter | `kind`、`level` | 实例每记录一次延迟日志 |
| `cosec.snapshot.regressions` | counter | `kind` | 存储返回了比已安装修订更旧的修订 |
| `cosec.snapshot.lost` | counter | `kind` | 之前见过的文档现在不存在；它的键失败关闭 |
| `cosec.snapshot.evictions` | counter | `kind`、`state` = `document`、`absent` | 应用或租户族在达到上限（`max-apps`、`max-tenants`、`max-absent-apps`、`max-absent-tenants`）时丢弃的条目 |
| `cosec.snapshot.handoffs.refused` | counter | `to` = `housekeeping`、`caller` | 编译池拒绝的工作：`caller` 表示由请求线程执行 |
| `cosec.snapshot.housekeeping.refused` | counter | `task` = `load-log`、`compile-log`、`install-review`、`effect-log`、`crash-log`、`transition-log`、`prewarm-log`、`pass-log`、`reset-log`、`evict`、`drop`、`lag-report` | 快照源的维护线程因队列（4,096）已满而拒绝的任务。除 `drop`（移除一个空条目，在原线程执行）外，每个都被丢弃。`*-log` 是一条没有写出的日志；`evict` 是族在上限以下的一次驱逐，下一次加载会再次请求它；`install-review` 是宿主对已安装文档的一次检查（[路由映射警告](../scenarios/route-maps.md#what-cosec-warns-about)），在之后的读取发现该修订仍在安装时再次提交 |

`installed`、`lag` 和 `lagging` 反映快照源上一轮延迟检查（每个 `revalidate-interval` 一次）时的状态，不在抓取时读取：
无论有多少文档，一次抓取只读一个字段。第一轮之前它们读数为零，此时 `summary.age` 为 `NaN`；`summary.age` 远大于
`revalidate-interval` 说明检查已经停止。

等待过文档的请求所占比例是 `loads{result=cold}` 除以决策数（`cosec.authorization` 的计数）；它上升的原因看 `evictions`。

| 告警条件 | 原因 |
|---|---|
| `lost` 有任何上升 | 某个文档从存储中消失了，它的键失败关闭 |
| `regressions` 有任何上升 | 存储丢失了它已确认的写入：检查最近一次故障转移 |
| `lag.exceeded{level=escalate}` 有任何上升 | 实例依据一个存储已有 `lag-escalate` 之久未确认的文档做决策 |
| `installed{state=invalid}` 大于零 | 某个已存储文档在本实例上无法编译（版本比控制平面旧？） |
| `handoffs.refused{to=caller}` 持续有速率 | 编译池饱和，请求线程在编译 |
| `housekeeping.refused` 有任何上升 | 实例在丢弃自己的日志：日志 appender 跟不上负载（[快照日志缺失](./troubleshooting.md#snapshot-log-lines-are-missing)） |
| `evictions` 持续有速率，且 `loads{result=cold}` 上升 | 族对流量来说太小：调大 `max-apps` / `max-tenants`，`state=absent` 时调大 `max-absent-tenants`（[为 SaaS 网关定容量](./deployment.md#sizing-a-saas-gateway)） |

## 快照健康状况 {#snapshot-health}

上面的指标负责计数；下面的健康指示器和日志回答实例是否跟上了文档：

- **健康。** 有 Spring Boot Actuator 时，`cosecSnapshot` 健康指示器在平台文档和每个预热文档安装后为 `UP`，之前为
  `DOWN`，细节为 `platform`（`installed`；已存储但无法编译时为 `invalid`；`failed`、`absent` 或 `loading`）、`prewarmed`、`missing`，以及在有文档无法编译时的 `invalid`（无法编译的文档数）和 `invalidFirst`（其中的前十个及其存储的修订，平台文档在前，其余按键排序，如 `["tenant:acme@7"]`），从不包含编译器的消息。就绪状态遵循同样的条件
  （[就绪](./deployment.md#readiness)）。
- **驱逐。** 在 Redis 上，Redis 可能驱逐快照时 `cosecSnapshotEviction` 健康指示器为 `DOWN`，并带说明原因的 `eviction`
  细节。它报告实例最近一次读到的结果：每个 `revalidate-interval` 在自己的线程上读取的每个节点的 `maxmemory-policy`，
  探针从不读取。读不到策略时，`eviction-check: fail` 下为 `DOWN`，`warn` 下为 `UP`，详情 `eviction: unknown`。它是告警，
  不是就绪信号：不要把它放进就绪组，否则 Redis 的一次改动会让所有实例同时退出轮转，而请求仍在照常决策
  （[Redis 要求](./storage.md#redis-requirements)）。与每个指示器一样，它也会让聚合的 `/actuator/health` 变为 `DOWN`：让负载均衡器
  和平台的健康检查探测就绪组，或者把它排除在它们探测的组之外（`management.endpoint.health.group.<group>.exclude=cosecSnapshotEviction`）。
- **控制存储的格式。** 在基于 CoCache 运行控制面的地方，存储仍是 8.0.0-beta.5 之前的格式时，`cosecControlStoreFormat` 健康指示器为
  `DOWN`，`format` 详情写明 `cosec-migrate repair-references`；该命令运行之后为 `UP`，`format: 8.0.0-beta.5`。它报告实例上一次读到的结果：
  每分钟在自己的线程上读一次，从不由探测触发；读不到时为 `UP`，`format: unknown`。实例发现旧格式时还会记录一条 `ERROR`。它和
  `cosecSnapshotEviction` 一样是告警：数据面仍在决定每个请求，所以不要把它放进就绪组（[升级](./upgrading-previews.md#from-8-0-0-beta-4)）。`cosec-control` 镜像是例外：它不决定数据面的任何请求，并把这个指示器放进它的就绪组，让未转换的存储使宿主处于未就绪且有名字（[控制面宿主](./deployment.md#control-plane-store)）。
- **登录路由。** starter 提供刷新或登出路由时，`cosecLoginRoutes` 健康指示器总是 `UP`，以每个路由的属性为详情：`not refused`，或在文档拒绝匿名 `POST` 它之后为 `denied since <time>` 及是哪种拒绝（没有语句允许它、某条 DENY 语句匹配它、或租户的平台边界不覆盖它），直到之后有请求被允许。同样的拒绝每个路由每分钟记录一条 `WARN`，点名属性和路径。两者都来自真实请求的决策，所以从不出错，也都不会让实例变为未就绪（[构建客户端](../integration/clients.md#what-your-application-serves)）。
- **滞后日志。** 文档超过 `lag-warn` 未被 Redis 确认时，实例以 `WARN` 记录，超过 `lag-escalate` 时以 `ERROR` 记录
  （[实例如何读取文档](./storage.md#how-instances-read-documents)）。其他值得告警的日志：
  `Snapshot document [<doc>] disappeared from the store; failing closed.`、
  `Snapshot document [<doc>] regressed from revision <a> to <b>; ...`，以及
  `Snapshot prewarm failed for [<docs>]; retrying in <backoff>.`
- **维护摘要。** 快照源在一个维护线程上写日志、执行驱逐，所以慢的 appender 从不占住请求线程。该线程的队列满时，或快照源关闭、该线程已停止后，
  任务被丢弃并计数，而不是在提交它的线程上执行；关闭期间，快照源自己的线程仍就地执行自己的任务。最多每 10 秒一次，且只在有任务被丢弃
  或有指标抛出异常时，该线程记录一条 `WARN`；关闭中的快照源在关闭它的线程上把剩下的写出一次：
  `Snapshot housekeeping since its last summary: refused <n> tasks, full or stopped (each dropped; drop runs in place):
  load-log 3 (last: "<text>"), …; meter failures <n> (last: "<text>").` 每个 `last` 是该任务最后被丢弃的那条日志的文本，
  带异常的类名和消息、不带堆栈，截断到 256 个字符。抛出异常的指标从不单独记录日志：它在这里计数，所以无论绑定了什么注册表，摘要都会写出。
- **传播情况。** 控制面的 `GET /documents/<doc>/revisions/<n>/propagation` 回答最近一分钟内报告过的实例中，有多少已经
  装入修订 `<n>` 或更新的修订。实例只有在能够评估某个修订时才算装入了它：读到了该修订却编译失败的实例仍会报告，所以计入被统计的实例，
  但不算已装入。只有开启影子窗口的实例才会报告（[修订、历史与回滚](./storage.md#revisions-history-and-rollback)）。
- **每个决策。** 审计事件中的 `snapshotVersion` 和 span 上的 `cosec.snapshot.version` 指明决策读取的修订。

## 控制面变更日志 {#control-plane-change-log}

控制面为变更的每一步发出一个变更事件：`PROPOSED`、`CHECKED`、`SHADOW_STARTED`、`SHADOWED`、`APPROVED`、`REJECTED`、
`PUBLISHING`、`PUBLISHED`、`FAILED`、`BREAK_GLASS_REQUESTED`、`BREAK_GLASS_APPROVED`、`REVOKED`、`HEADS_MOVED`、`REVIEW_NEEDED`、`ABANDONED`、`SHADOW_SKIPPED`、`REBASED`。每个改变变更记录的路由都会在改变时写一条（`CTL-63`）。事件指明变更、文档、
操作者、基准修订和候选修订、需要审批的内容、确认、影子窗口摘要和备注；它从不携带文档内容。变更监听器接收这些事件。事件还带有该步骤操作者的**来源**（`origin`，`CTL-57`），写成事件 JSON 时是：`asserted`（宿主相信了调用方声明的身份，而不是自己校验凭证）、`via`（凭证的 `method`、作为 `credential` 的凭证 id 和作为 `request` 的请求 id，从不含凭证的值）和 `peer`（请求所用连接的地址，从 Reactor Netty 的 channel 读取，位于任何转发头处理之下，所以调用方不能像声明客户端地址那样声明它；读不到的宿主，即 Reactor Netty 之外的，不记录它）。经控制面门面在宿主自身进程内作出的步骤（[在代码中修改策略](../integration/checks.md#changing-policies-from-your-code)）记为 `via.method: in-process`，有凭证时带凭证 id，没有 `peer`，因为没有连接承载它。没有请求驱动的步骤（例如维护任务）没有来源；存储的变更记录其作者的来源，有来源时日志行以 `origin=...` 结尾。

- **默认监听器**以 `INFO` 把每个事件记录到 logger `me.ahoo.cosec.changelog`，格式为
  `Change <changeId> <kind> on <doc> by <principal>@<tenant>: base=..., candidate=..., escalations=<n>, confirmations=<n>`。
  变更监听器是累加的：每个 `ChangeListener` bean 都会注册，只有应用自己声明了 `LoggingChangeListener` bean 时才不再
  注册默认的日志监听器。变更日志以 `INFO` 写出：把 `me.ahoo.cosec` 设得高于 `INFO` 的配置，要按名字把 `me.ahoo.cosec.changelog` 设为 `INFO`，与任何你要保留的记录一样。
- **`PUBLISHING`、`BREAK_GLASS_REQUESTED` 和 `BREAK_GLASS_APPROVED` 是受控的：** 每个 `REQUIRED` 变更监听器都必须在
  [`cosec.control.audit.required-timeout`](./configuration.md#control-plane)（5 秒，所有监听器合计）内记录该事件，这一步才会生效，否则这一步不会发生，控制 API 返回 `503`。其他事件，以及发给 `BEST_EFFORT` 监听器的
  所有事件，都在之后投递；失败记录为 `Change listener [<name>] could not record <event>.` 修订记录是已发布内容的持久
  记录。受控事件说明这一步即将发生，而不是已经发生：它之后的检查仍可能拒绝这一步（变更在此期间变化了时返回 `409`），
  `BREAK_GLASS_APPROVED` 或 `ABANDONED` 之后都可能如此。发生了什么以变更的状态为准，发布则另以修订历史为准。
- **备注是自由文本**，其中一部分出自变更的作者（提议、申请或吊销的原因）：某一步是谁做的，看事件的 `operator`，不要看备注。
- **break-glass 需要 `REQUIRED` 变更监听器：** 没有时，创建或修改 break-glass 绑定得到 `503`
  （`break-glass needs a REQUIRED change listener, and none is configured`）。
- **跳过窗口时写 `SHADOW_SKIPPED`**，备注写明原因（`shadow window skipped: <reason>; <note>`）：由 `:skip-shadow` 跳过（操作者持有
  `cosec:skip-shadow`），或由没有窗口的 break-glass 变更跳过。无论变更之后是否发布都会写；变更发布后，修订的来源也会记下这次跳过。被拒绝的跳过不写任何事件。
- **变更被 rebase 到文档当前修订并重新检查时写 `REBASED`。**
- **`ABANDONED` 指明是谁放弃了变更，并在备注中写明变更的作者**（`author <tenant>/<principal>: <note>`）。放弃
  break-glass 变更像 `PUBLISHING` 一样受控：它需要一个 `REQUIRED` 变更监听器（没有时返回 `503`），由它在变更存为已放弃之前
  记下。放弃其他变更在之后记录。
- **再次吊销而发布的停住的吊销，其 `REVOKED`** 指明再次吊销的人，备注加上其原因
  （`<first reason>; published again by <tenant>/<principal>: <reason>`），它的 `PUBLISHING` 也是如此。
- **`PUBLISHED` 之后可能跟着 `HEADS_MOVED` 或 `REVIEW_NEEDED`：** 变更检查时读过的其他文档在它写入之前变化了
  （[一次发布比较什么](./storage.md#what-a-publish-compares)）。事件列出变化的头（`moved`：文档，app 列表记为 `list:apps`；
  检查时的摘要、现在的摘要、能否读到），带上授出分析在当前的头上发现的升级项，并在备注中说明含义。`HEADS_MOVED` 无需处理；
  `REVIEW_NEEDED` 表示分析发现了发布时未经审批的升级项、分析无法运行，或比较没有按时完成（这时 `moved` 为空，唯一的升级项原因为 `DETECTION_TIMED_OUT`）：应当有人复核
  这次发布，控制面还会以 `WARN` 记录 `Change <changeId> published on [<doc>]: <note>; moved [...].`。发布的客户端断开连接时
  它们也会被记录。它们和所有不受控的事件一样尽力投递：发布已经发生。
- **事件中的升级项**是 `subject`、`reason` 与 `detail`（[测试变更](../policies/testing.md)）。`TENANT_BINDINGS` 项代表一条被放宽的
  权限策略触及的所有租户：每条策略一项（`tenants binding [<policy>] in app <app>`），语句、数量和前 20 个租户文档在 `detail` 中，
  因此无论有多少租户，事件都很小。它仅供参考，从不算作需要审批的内容（`CTL-48`）。`reason` 是 `Escalation.Reason` 的名称之一。

有 `cosec-kafka` 和 `KafkaOperations` bean 时，starter 还会注册一个 `KafkaChangeListener`，除非
[`cosec.control.audit.kafka.enabled`](./configuration.md#control-plane) 为 `false`。它把每个事件写到
`cosec.control.audit.kafka.topic`（`cosec-control-audit`），key 是文档（`platform`、`app:order`、`tenant:acme`），因此
一个文档的事件保持有序。它的投递方式是 `cosec.control.audit.kafka.delivery`：默认 `best-effort`；`required` 让发布和
break-glass 等待 broker 的确认（把生产者配置为 `acks=all`）。设为 `delivery=required` 时，如果 Kafka 变更监听器无法
创建（没有 `cosec-kafka`、没有 `KafkaOperations` bean，或 `enabled=false`），启动失败，而不是在没有记录的情况下发布：
`cosec.control.audit.kafka.delivery=required, but no Kafka change listener was created: ...`。

### 顺序与队列 {#change-event-order}

监听器**按本实例写入的顺序**接收一个变更的事件（`CTL-66`）：每个监听器有一个队列和一个消费者，一个步骤的事件排在它之前各步骤的事件之后。跨实例没有顺序可言（在一台控制面主机上提议、在另一台上发布，是两个进程）：消费方按每个事件的 `time` 和变更的记录排序。Kafka 变更监听器按文档作为 key，在其分区内保持这一顺序。

- **受控的步骤**（`PUBLISHING`、break-glass）在 `cosec.control.audit.required-timeout` 内等待每个 `REQUIRED` 监听器记录它的事件，排在此前已入队的事件之后；否则返回 `503`，其事件此后也不会被记录。
- **REQUIRED 监听器的队列已满**时，其他步骤的事件最多等待同样的时长以腾出空间：该步骤已经存储，因此从不为此失败。等不到空间的事件**丢失**：计入 `cosec.control.listener.lost`，并以 `ERROR` 记录（`Change event <kind> of change <id> on <doc> was not handed to REQUIRED change listener [<name>]: ...`）。
- **REQUIRED 监听器的调用失败**同样使其他步骤的事件丢失：调用抛出异常，或在超时内没有应答。它计入同一个 `cosec.control.listener.lost`，并连同失败原因以 `ERROR` 记录（`Change event <kind> of change <id> on <doc> was not recorded by REQUIRED change listener [<name>]: <failure>.`），然后交付下一个事件。队列已满和调用失败是 REQUIRED 监听器错过事件的两种途径：**在 `cosec.control.listener.lost` 大于零时告警。**
- **BEST_EFFORT 监听器的队列已满时丢弃新来的事件**，所以到达的是一个有序的前缀：消费方看到的是缺口，从不是乱序。丢弃计入 `cosec.control.listener.dropped`，并以 `WARN` 记录，每个 required timeout 至多一次。
- **上限是推导出来的，不是配置的。** 所有队列合计至多占用堆的三十二分之一，由各监听器平分，每个事件按它所持有的内容计量。启动时一条 `INFO` 说明结果（`Change listeners [...]: their queues hold at most 64.0 MB (the heap's 2048.0 MB / 32), 32.0 MB each`）。健康的接收端即使经历涉及数千租户的批量变更，队列也几乎为空。
- **停止应答的监听器只会被报告，从不被切断。** 在有事件等待、且它超过 required timeout 没有交付任何事件期间，一条 `WARN` 写明它、队列深度和最早事件的等待时长，每个 required timeout 至多一次。它恢复应答时，按顺序交付其份额内保留的一切。`REQUIRED` 监听器在超时内没有应答的调用视为失败，并交付下一个事件。
- **关闭时监听器先排空队列**，时限是决策监听器的 `cosec.observability.listener.drain-timeout`。它们不再接收事件，交付已入队的事件；剩下的按监听器计数并记录（`Change listener [<name>] never records <n> change events: they were still queued when the drain of <timeout> ended.`，`REQUIRED` 监听器为 `ERROR`，其他为 `WARN`），之后才停止。排空期间到来的受控步骤在生效前以 `503` 拒绝，与 `REQUIRED` 监听器无法记录它时相同。其他步骤已经存储：其事件以同样方式记录，从不入队后丢失。

指标，均带 `listener` 标签：

| 指标 | 含义 |
|---|---|
| `cosec.control.listener.queued`、`cosec.control.listener.queued.bytes` | 已入队的事件数，以及它们在该监听器份额中所占的大小 |
| `cosec.control.listener.oldest.age` | 最早入队的事件已等待多久（秒）：停止的接收端最先在这里显现 |
| `cosec.control.listener.dropped` | 因队列已满而丢弃的 BEST_EFFORT 事件 |
| `cosec.control.listener.lost` | REQUIRED 监听器错过的已存储步骤的事件，因队列已满或调用失败：任何一个都应告警 |

## 日志 {#logs}

| Logger | 记录内容 |
|---|---|
| `me.ahoo.cosec.audit` | 审计日志（[审计日志与 Kafka](#audit-log-and-kafka)） |
| `me.ahoo.cosec.runtime` | 每个 `500` 都以 `ERROR` 记录 `Request [<requestId>] failed with an unmodelled exception.` 及堆栈；`REQUIRED` 监听器失败；被丢弃的事件；失败的属性提供者（以 `WARN` 记录 `<provider> failed to fetch [...]`，每个提供者最多每 10 秒一次：每个决策都带有原因）；无法计数的限流器；影子窗口的暂存列表读不出来时，以 `WARN` 记录 `The staged list cannot be read since it was at <instant>: candidates are read by their keys until it can.`，每次故障一次（`CTL-47`，[存储](./storage.md#redis-keys)） |
| `me.ahoo.cosec.runtime.snapshot` | 加载、滞后、丢失和回退的文档（[快照健康状况](#snapshot-health)） |
| `me.ahoo.cosec.spring.boot` | 启动检查，例如 `!!!` 驱逐警告；应用没有声明 `BlacklistSource` 时以 `INFO` 记录的 `No BlacklistSource bean: requests are not checked against a blacklist (ext.blacklist.*).`（[黑名单](../guide/security-model.md#blacklist)）；以 `INFO` 记录的 `ip2region databases: [<versions>].`，并指出没有数据库的 IP 版本，其地址是取不到；gateway 模式服务的 `WARN`（[网关后面的服务](./deployment.md#services-behind-the-gateway)） |
| `me.ahoo.cosec.changelog` | 变更日志：每个变更事件一行 `INFO`（[控制面变更日志](#control-plane-change-log)）；像路由 `me.ahoo.cosec.audit` 那样路由它 |
| `me.ahoo.cosec.control` | 每个控制面 `500` 的 `Control API <method> <path> failed (request <requestId>).`；以及由写它的类的 logger 以 `WARN` 记录的 `Change listener [<name>] could not record <event>.` |

通过响应头 `CoSec-Request-Id` 把一个响应与它的日志、审计事件和 span 关联起来；它也是每个 problem 响应体中的
`requestId`。

**下一步：** 从症状找到原因，见[故障排查](./troubleshooting.md)。
