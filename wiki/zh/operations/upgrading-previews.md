---
title: "8.0 预览版之间的升级"
description: "每个 8.0 预览版（alpha 与 beta）到下一个之间的变化，新的在前，面向已在运行预览版的部署。"
---

# 8.0 预览版之间的升级 {#between-8-0-previews}

只适用于已在运行 8.0.0 预览版的部署：每个预览版到下一个之间的变化，新的在前。7.x 部署不需要这一部分，[升级](./upgrading.md)就是完整的升级。8.0 正式发布时这些说明将删除。

## 从哪里开始 {#start-here}

下面每一节写的是离开那个预览版时会变什么。读你所运行版本的那一节，以及它上面的每一节：

| 你运行的版本 | 阅读 |
|---|---|
| `8.0.0-beta.3` | [从 beta.3 升级](#from-8-0-0-beta-3) |
| `8.0.0-beta.2` | [从 beta.2 升级](#from-8-0-0-beta-2)，然后是上面那一节 |
| `8.0.0-beta.1` | [从 beta.1 升级](#from-8-0-0-beta-1)，然后是上面两节 |
| `8.0.0-alpha.4` | [从 alpha.4 升级](#from-8-0-0-alpha-4)，然后是上面三节 |
| `8.0.0-alpha.3`、`alpha.2`、`alpha.1` | [从 alpha.3](#from-8-0-0-alpha-3)、[alpha.2](#from-8-0-0-alpha-2) 或 [alpha.1](#from-8-0-0-alpha-1) 升级，然后是它上面的每一节 |

## 从 8.0.0-beta.3 升级 {#from-8-0-0-beta-3}

- **`cosec-gateway-server` 镜像：没有 JMX agent，actuator 在管理端口，镜像默认值在挂载文件之下依然保留**（`UB-2`、`UB-21`）。受影响的是运行该镜像的人。
  - 直到 beta.3，启动脚本会在 5555 端口、所有网卡上启动一个不需要认证的 JMX agent。现在不再启动。要附加，在 `JAVA_OPTS` 里设置并绑定到回环地址（[镜像](./deployment.md#the-image)）；Kubernetes base 设置的 `-Dcom.sun.management.jmxremote.host=127.0.0.1` 随之取消。
  - actuator 现在由管理端口 `8081`（`management.server.port`）提供，不再在 `8080` 上：8080 上的 `/actuator/health/*` 是 `404`。探针现在是 8080 上的 `/livez` 和 `/readyz`（同样的分组，由网关自己的端口提供，不经 CoSec 决策）；`/actuator/health` 和 `/actuator/prometheus` 在 8081 上。把你自己的探针或健康检查改为 `/livez` 和 `/readyz`（镜像的 `HEALTHCHECK` 和 `k8s/` 清单已经改了），如果要抓取 8081，只把它发布给监控网络。
  - 镜像带有 `cosec-micrometer` 和 Prometheus 注册表（Micrometer 1.17.2，来自 Spring Boot 的依赖管理）：`cosec.authorization`、`cosec.snapshot.*` 等在 `/actuator/prometheus` 上。
  - 镜像的默认值现在在它的 jar 里，你挂载的 `config/application.yaml` 被追加读取并逐 key 覆盖；以前它会整体替换默认值（Kafka 审计、登录和 ip2region 会重新开启）。设置了所需内容的文件不受影响；漏掉默认值的文件现在会得到它们。`routes` 这样的列表仍然整体替换。
  - `k8s/base`：演示用的 CORS 来源改为占位符（`https://app.example.com`，不再是 `*`；`max-age` 的单位是秒），演示路由和发现列表已标注，容器有了 `management` 端口，production 的 NetworkPolicy 只放行来自 `monitoring` 命名空间的该端口。
- **CoSec 无法读取值的 `cosec.*` 开关使启动失败**（`UB-1`）。直到 beta.3，`cosec.enabled=1`、`on`、空字符串或 ` true ` 这样的值对 Spring 的绑定器是 true，对装配 bean 的条件却是"关闭"，于是 CoSec 或该功能被悄悄关闭；空的 `cosec.pipeline.mode` 不装配任何管道，Spring Cloud Gateway 宿主会成为开放代理。`cosec.authorization.shadow.enabled=off`（或 `0`）还会让影子评估保持开启。现在 starter 在创建任何 bean 之前拒绝每个不是 `true` 或 `false` 的开关（不区分大小写，两侧不能有空格；`cosec.pipeline.mode` 接受 `enforce` 或 `gateway`，`cosec.api-key.store` 接受 `memory` 或 `redis`），信息指明 key、读到的值和可接受的值（[开关无法读取导致启动失败](./troubleshooting.md#startup-fails-on-an-unreadable-switch)）。受影响的是用其他写法写开关的配置、环境变量或 Helm 值。写成 `true` 或 `false`，或删掉这个 key 取默认值。写成 `false` 的开关仍会关闭它的功能。
- **`cosec.authorization` 计时器新增 `problem` 标签，所有序列的标签 key 相同**（`UB-8`）。新标签是客户端被告知的问题的 slug（`unauthorized`、`token-expired`、`forbidden` 等）或 `none`，因此可以区分 `401` 与 `403`，以及不同的令牌问题（[指标](./observability.md#metrics)）。原先只有 `indeterminate` 序列才有的 `cause` 标签现在出现在每个序列上，`indeterminate` 之外为 `none`：Prometheus 注册表拒绝同名但标签 key 不同的计量器。受影响的是按"没有 `cause`"选取序列的查询（`cause=""`），它现在应选 `cause="none"`；对新标签求和得到原来的序列。
- **“什么做出了决定”只有一套词：指标、审计和决策事件的认证方式**（UD-7、UD-11）。直到 beta.3，每个输出各自命名决定，对显式拒绝的告警还会计入并非被 DENY 语句拒绝的请求。现在每个输出都使用决策事件的词（[每个输出中的“什么做出了决定”](./observability.md#what-decided-in-every-output)）：

  | 输出 | 之前（beta.3） | 现在 |
  |---|---|---|
  | `cosec.authorization` 的 `result` | `allow` | `permit` |
  | | DENY 语句、作用域拒绝和边界拒绝都是 `explicit_deny` | `explicit_deny`（仅 DENY 语句）、`scope_rejected`、`boundary` |
  | | `bad_request` | `invalid_request` |
  | 审计 `authorization.decision` | `ALLOW` | `PERMIT` |
  | | DENY 语句、作用域拒绝、边界拒绝和 `400` 都是 `EXPLICIT_DENY` | `EXPLICIT_DENY`、`SCOPE_REJECTED`、`BOUNDARY`、`INVALID_REQUEST` |
  | 审计 `authorization.reasonCode` | `ALLOW`；`400` 为 `EXPLICIT_DENY`；尚未生效的令牌为 `TOKEN_INVALID` | `PERMIT`；`INVALID_REQUEST`；`TOKEN_NOT_YET_VALID` |
  | 决策事件 `subject.method` | 每个令牌都是 `token`，API key 为 `apikey` | `jwt`（CoSec 自己的令牌）、`api-key`、其他校验器的名字（默认 `token`）：与 `CoSec-Identity` 一致 |

  计时器的这项变化与上一条的 `problem` 标签和每个序列上的 `cause` 一起发生：仪表盘只需为两者迁移一次。受影响的是：基于 `result="allow"`、`explicit_deny` 计数（现在计入的请求更少：加上 `scope_rejected` 和 `boundary` 才是原来的总数）或 `bad_request` 的仪表盘和告警；匹配 `ALLOW`、或期望其他三种拒绝为 `EXPLICIT_DENY` 的审计消费者（日志、Kafka 记录）；对 CoSec 的令牌匹配 `subject.method` 为 `token`、或匹配 `apikey` 的决策监听器。`ApiKeyAuthenticator.METHOD` 为 `api-key`。`AuditDecision` 和 `AuditReasonCode`（`cosec-api`）去掉了 `ALLOW`：请针对这个版本重新编译。
- **新的日志行**（无需改动）：一行 INFO 摘要说明 CoSec 被配置成什么样，`cosec.enabled=false` 时一条 WARN（[启动摘要](./observability.md#startup-summary)）；平台文档缺失期间每隔 `lag-warn` 重复的一条 WARN，指明读取的 key、key 前缀和 Redis（[实例未就绪](./troubleshooting.md#instance-is-not-ready)）；Spring Cloud Gateway 宿主上有自己控制器或函数式路由、而 CoSec 不决策它们时的一条 WARN（[网关自己的处理器](../integration/web.md#the-gateways-own-handlers)）；以及客户端地址那一行现在会说明默认 `max-trusted-index` 的风险。
- **安全：对已结束请求的检查被拒绝**（`PIP-26`）。直到 beta.3，在请求之后保留的 `SecurityContext`（存在字段、缓存或发给消费者的消息里）仍能通过 `AuthorizationChecker` 放行，依据的是创建它时的成员关系，即使已经登出、令牌已被吊销或角色已被移除。现在绑定请求的适配器在请求完成时结束它，之后对它的每次检查都为 `false`（`decide` 返回 `Deny(null)`）。进行中的请求照旧作答，无论持续多久：流式或服务器发送事件（SSE）响应体、异步 Servlet 请求。受影响的是保留上下文并在响应写出之后用它检查的代码。改为在请求内检查；之后运行的工作（消费者、定时作业）自己携带授权（[代码中的权限检查](../integration/checks.md#how-a-check-relates-to-the-request-pipeline)）。你自己的传输交给 `Pipeline.evaluate` 的请求以及测试工具包的请求，其上下文只在你结束它时结束：关闭 `Outcome`，或调用 `PipelineFixture.end` / `PermittedRequest.end`（见下文）。
- **安全：安全上下文的读取入口只返回管道构造的上下文**（`PIP-27`）。`exchange.getSecurityContext()`、`ContextView.getSecurityContextOrEmpty()` / `getSecurityContext()`、`request.getSecurityContext()`、`SecurityContext` 处理器参数和 `SecurityContextHolder` 以前返回 `SecurityContext.KEY` 下的任何对象，所以应用的过滤器或某个库自己写入的上下文会被当作已认证的调用者。现在其他任何东西都读作没有上下文：`null`，或未绑定上下文时的那种失败。受影响的是在这个 key 下绑定自己构造的 `SecurityContext`（mock、该接口的实现）的代码，尤其是测试代码。改为绑定管道放行的请求的上下文：测试中用 `cosec-testkit` 的 `PipelineFixture.securityContext(fixture.request(...))` 或 `PermittedRequest`（[测试策略](../policies/testing.md)）；自己运行管道的过滤器绑定 `outcome.security!!.securityContext`。
- **破坏性变更：三种从未按字面生效的文档写法不再能编译**（`DOC-9`、`DOC-10`、`DOC-11`）。每一条拒绝的文档，其语句本来就没有按所写的方式生效：
  - 在漏掉一种写法就会放过请求的地方，对 `eq` 或 `prefix` 加 `ignoreCase`、而字面量含有 ASCII 以外的字母（`Ärzte`）：DENY 语句或限流规则、`not` 之下的 ALLOW、守护 DENY 的策略条件。折叠只限 ASCII，所以 `ärzte` 会从 DENY 漏过。改为在不带 `ignoreCase` 的 `in` 里写出每种写法（[大小写折叠](../policies/language.md#case-folding)）。在 ALLOW 里仍能编译，但会给出警告。
  - 条件或限流 `key` 里的 `resource.attributes.<name>`：没有任何地方设置它，对它的 DENY 什么也没拒绝。把事实写进资源 id，用 `resource.var.<name>` 读取（[对象级规则](../integration/checks.md#object-level-rules)）。设置 `request.resource.attributes` 的测试工具场景也会被拒绝。
  - 以 `(` 开头的 `subject.id` 字面量（`{"not": {"eq": ["subject.id", "(0)"]}}` 匹配每一个匿名请求），以及主体 id 以 `(` 开头的主体绑定（它从不生效）。匿名请求写 `{"eq": ["subject.authenticated", false]}`。

  受影响的是存有这类文档的部署。升级之后，实例把这样的文档装为失败，每个读取它的请求都失败即拒绝（`503`），所以要先找出来。在 beta.3 仍在运行时，读取每个头修订（`GET <控制面前缀>/documents/{key}`，[控制 API](../policies/testing.md#the-control-plane)），或取你发布所用的文件，搜索它们：`grep -nE 'resource\.attributes\.|"subject\.id" *, *"\(|"principal" *: *"\(' <文件>` 能找出第二、三种写法；第一种用 `grep -n '"ignoreCase": *true' <文件>`，再看旁边有没有 ASCII 以外的字母（`grep -nP '[^\x00-\x7F]'`）。也可以用本版本的 `PolicyTestKit.load` 加载这些文件，它像实例一样编译每个文档，并给出每个错误的路径。`cosec-migrate convert` 现在也拒绝 7.x 数据里的同样写法：基于 7.x 资源属性的 ALLOW 是一条永远不会成立的语句，需要你删掉。
- **破坏性变更：`:apply` 的 `REPLACE` 检查文件读取时的修订。** 直到 beta.3，文件头的 `revision` 必填却被忽略，`REPLACE` 会删除头修订中文件没有的一切，包括文件读取之后才发布的内容。现在 `revision` 不是头修订的文件返回 `409`。受影响的是从 git 应用文件、`revision` 固定或过时的作业：发送作业上次读取的修订（`GET /documents/{key}` 的 `revision`），并合并之后的变化（[变更](../policies/testing.md#changes)）。尚未发布的文档不比较。`MERGE` 不变，它的变更现在会在 `WARNING` 中点名因文件没有列出而保留的条目。
- **会把发布者锁在控制面之外的变更需要确认。** 对平台文档、平台租户的文档或控制应用的文档的变更，如果会让其操作者到达不了控制面，就带有 `V4` 警告，它的发布返回 `422`，除非请求体写明 `"lockout": true`（[发布与回滚](../policies/testing.md#publishing-and-rolling-back)）。受影响的是有意发布这类变更的自动化：加上这一确认。被锁在外面之后的出路是新增的 `cosec-migrate restore`，而不是清空 Redis（[从被锁在外面中恢复](./storage.md#recovering-from-a-lock-out)）。
- **已修复：丢失的失效消息由其后的第一轮装上，即使碰到在途的加载。** beta.3 的发布说明把它列为已知问题：重新确认的一轮碰到某文档已在途的加载时，把那次加载的读当作自己的确认，而那次读可能早于变化，所以失效消息丢失的变化最多要两个 `revalidate-interval` 才装上，其间的决策使用上一修订。现在一轮只加入在它之后开始的加载，否则再等一次加载（`SNP-6`，[存储](./storage.md#how-instances-read-documents)）。无需任何改动；一轮碰到某文档的加载时，Redis 可能多收到一次该文档的条件读。
- **`cosec-gateway-server` 镜像关闭了 ip2region。** 它的 `config/application.yaml` 现在设置 `cosec.ip2region.enabled: false`，与 Kubernetes 的 base 一致；直到 beta.3，镜像一直开启它。读取 `ext.ip2region.*` 的文档在它上面不再能编译：升级镜像之前设置 `COSEC_IP2REGION_ENABLED=true`。它的 Docker `HEALTHCHECK` 现在探测 `/actuator/health/liveness`，因此等待平台文档的网关不再被报告为不健康。
- **`@ahoo-wang/cosec-react`：`logout` 在服务端结束会话。** `useSecurity` 和 `SecurityProvider` 新增 `tokenManager` 选项（同一个 `TokenStorage` 的 `JwtTokenManager`，带 `tokenRevoker`）和 `logout()`：它调用服务端的登出，然后删除这里的令牌对（[构建客户端](../integration/clients.md#logout-and-switching)）。`signOut` 不变：它只删除这里的令牌对，会话在服务端仍可刷新，直到其刷新令牌过期。没有任何破坏性变化；把用户的"退出"改为调用 `logout`。
- **安全：构造 `TokenAuthenticator` 的人要指明吊销读取器**（`IDN-27`）。直到 beta.3，`TokenAuthenticator(verifier)` 可以编译，并从 `RevocationReader.NONE` 读取吊销：应用自己构造的认证器（自定义的 `Authenticator` Bean、Spring 之外的管道）在登出或 `revokeSubject` 之后仍接受每个被吊销的令牌，直到它过期，而且没有任何警告。现在这个参数没有默认值，`PermittedRequest.withToken`（`cosec-testkit`）的也没有。受影响的是不传读取器就构造二者的代码：它不再能编译。传入你的登出写入的那个 `TokenStore` 的读取器（starter 的 `RevocationReader` Bean），或者在从不吊销的地方（例如大多数测试）传 `RevocationReader.NONE`。二进制兼容；starter 不变。
- **`RefreshTokenAuthentication(verifier, revocation, families)` 要指明 `MembershipRefresh`**（`IDN-27`）。这个工厂函数默认使用 `MembershipRefresh.CLAIMS`，它保留旧令牌声明的角色，而 `MembershipRefresh` 的 KDoc 说的恰好相反。现在它的 KDoc 说明实际行为，工厂函数也没有默认值。受影响的是用三个参数调用工厂函数的代码：它不再能编译。加上 `MembershipRefresh.CLAIMS` 以保持现在的行为（移除的角色在下次登录或 `revokeSubject` 之后生效），或者传入重新查询用户的 `MembershipRefresh`。除非你声明 `MembershipRefresh` Bean，starter 的刷新路由仍使用 `CLAIMS`。`cosec-login` 中的合成方法 `RefreshTokenAuthentication$default` 已移除：请针对这个版本重新编译。
- **安全：`JwtTokenVerifier` 和 `JwtTokenConverter` 拒绝 `Algorithm.none()`**（`IDN-28`）。java-jwt 的 `none` 接受任何签名为空的令牌，所以基于它的校验器会把任何人都能写出的令牌当作 CoSec 自己的令牌，包括应急访问（break-glass）。现在两个构造函数都抛出 `IllegalArgumentException`。受影响的是基于 `Algorithm.none()` 构造二者的代码，尤其是测试代码。请传入签名算法，例如 `Algorithm.HMAC256(secret)`。
- **`CoCacheTokenStore.revoke` 拒绝把令牌当作令牌 id 传入。** `TokenStore.revoke(tokenId, ttl)` 接收令牌的 id（`VerifiedToken.id`，即 `jti`）；把访问令牌传到这里的登出什么也没吊销，还把令牌写成了 Redis 的 key。现在形如令牌的值（JOSE 紧凑序列化，带或不带 `Bearer `）会抛出 `IllegalArgumentException`，消息指向 `TokenRevoker`；CoSec 或其他签发方给出的令牌 id 都不会被拒绝。受影响的是基于 `TokenStore` 编写的登出，它从未生效过。请调用 `TokenRevoker.revoke(accessToken)`。
- **用 `Pipeline.builder` 构建的管道会计数限流**（UA-10）。直到 beta.3，没有调用 `rateLimiter(...)` 构建的管道没有限流器：带 `strictFailure` 的规则对它覆盖的每个请求都返回 `503`，其他规则都被跳过。现在它在自己的 `LocalRateLimiter` 中计数，与 starter 的默认一样，超出规则限制的请求为 `429`。`PipelineFixture.of` 和 `load` 也这样计数，并接受 `rateLimiter` 参数，所以测试能看到 `429`（传入使用测试自己时钟的 `LocalRateLimiter` 可以跨越窗口）。受影响的是 Spring 之外的管道以及测试，其文档带有限流规则：现在它们会被限流。要对整个服务限流，给 `rateLimiter(...)` 一个跨实例共享的限流器。starter 不变。`PipelineFixture.of` 和 `load` 增加了一个参数：请针对这个版本重新编译测试。
- **你自己的传输可以结束它的请求**（`PIP-26`）。`Outcome` 实现了 `AutoCloseable`：在响应完成时关闭它（`pipeline.evaluate(facts).use { … }`），或者在响应比处理器活得更久时从响应的完成回调里关闭，之后对其上下文的每次检查都为 `false`（[管道在适配器之外](../architecture/extending.md#pipeline-outside-the-adapters)）。测试中用 `PipelineFixture.end(context)` 和 `PermittedRequest.end(context)`。没有破坏性变化：不关闭它的传输行为照旧。
- **因调用方式而被拒绝的检查会被记录**（UA-5）。管道没有构造的上下文、请求结束之后的检查以及格式错误的动作字符串仍然返回 `false`（`Deny(null)`），现在每种每分钟记录一次 `WARN`，指出请求（[故障排查](./troubleshooting.md#a-check-answers-false)）。无需任何改动。
- **在每个栈上，当且仅当 CoSec 认证了请求时，请求的 `Principal` 才是 CoSec 的**（`PIP-28`）。直到 beta.3，WebFlux 和网关把匿名主体 `(0)` 作为 `exchange.getPrincipal()` 和 `java.security.Principal` 处理器参数交给匿名调用方，所以把"存在 principal"当作"已登录"的代码会把所有匿名调用方当作同一个用户；Spring MVC 则完全不设置。现在：WebFlux 和网关不再为匿名请求设置它，受影响的是期望匿名调用方也有 principal 的代码（现在以空完成的 `exchange.principal.map { … }`、非空的 `Principal` 参数）。Spring MVC 为已登录的请求设置 CoSec 的主体：`getUserPrincipal()` 是它，`getRemoteUser()` 是它的 id，`Principal` 处理器参数不再为 null。它替换容器或前面的过滤器（Spring Security）已设置的 principal；在 CoSec 视为匿名的请求上，那个 principal 保持原样。对 CoSec 认证了的请求，`isUserInRole` 为 `false`，`getAuthType()` 为 `null`。受影响的是读取 `getUserPrincipal()` 或 `getRemoteUser()` 的 Servlet 应用（已登录的请求现在得到 CoSec 的调用方），以及在 CoSec 后面询问 `isUserInRole` 的应用（改为通过检查询问 CoSec）。从 `SecurityContext` 读取调用方（`principal.authenticated`、`principal.id`），它在三者上相同（[Web](../integration/web.md)）。
- **已修复：时钟偏快的签发方签发的令牌被接受**（#1666，`IDN-29`）。直到 beta.3，`JwtTokenVerifier` 不给时钟任何余量：`iat` 只要比校验方时钟晚几毫秒，令牌就是 `401 token-invalid`，所以签发方与网关不共用时钟的部署会在某一秒的最后几毫秒看到偶发的 `401`。现在令牌的 `iat` 和 `nbf` 可以比本实例时钟晚至多 `cosec.authentication.revocation.skew`（5 秒）；`exp` 仍然严格。超出余量的令牌对客户端仍是 `token-invalid`，决策事件将其记为 `TOKEN_NOT_YET_VALID`（`AuthnResult.RejectReason` 的新成员，这是一个会增长的集合，对它的 `when` 本应已有 `else`）；日志写着 `Token refused as not valid yet`。`JwtTokenVerifier(algorithm, issuerSkew)` 接受这个余量，starter 传入 `cosec.authentication.revocation.skew`；你自己构造的校验器默认 5 秒，如果你改过 `TokenAuthenticator` 的 `revocationSkew`，请传入同一个值。没有破坏性变化。
- **宿主在启动时记录的内容。** 不会有什么坏掉，但运维人员读到的日志变了。CoSec 不读取的 `cosec.*` 属性会在创建任何 Bean 之前让启动失败，所以带有已移除键的配置会先听到每一个。令牌按 `RevocationReader.NONE` 校验时，宿主记录一条 `WARN`，说明被吊销的令牌在过期之前仍会被接受。令牌存储缺失时，一条启动信息会列出每个缺失的存储，而不只是第一个。

## 从 8.0.0-beta.2 升级 {#from-8-0-0-beta-2}

- **安全：吊销表最多把“未吊销”保留 30 秒。** 直到 8.0.0-beta.2，`RevokedTokenCache` 和 `RevokedSubjectCache` 的 `absent-ttl` 没有锁定，所以 `cocache.caches.RevokedTokenCache.absent-ttl`、`cocache.defaults.store.absent-ttl` 或定制器都能延长它，错过失效消息的实例会在那么长时间里接受已吊销的令牌。现在它由 CoSec 决定，为 30 秒：为任一吊销表设置 `cocache.caches.[name].absent-ttl` 会使启动失败（请删除它），全局默认值和定制器也不再影响它。
- **安全：请求用应用的 `TokenVerifier` 校验。** 直到 8.0.0-beta.2，starter 的管道一直用 CoSec 自己的 `JwtTokenVerifier` 校验请求的令牌，即使应用声明了 `TokenVerifier` Bean（刷新和登出确实用了它）。现在请求、刷新和登出用同一个校验器（`IDN-25`）。变化取决于你的校验器：
  - 校验其他签发者令牌的校验器，现在在请求上也像一直以来在刷新时一样生效。
  - 包装 CoSec 自己校验器的校验器（`JwtTokenVerifier` 加上你自己的检查）让它的令牌在每个请求上都不再是 CoSec 的：主体的凭证是 `token` 而不是 `jwt`，没有会话，因此不能从该请求的会话[切换租户](../integration/authentication.md#switching-tenant-and-app)，网关也不转发会话，并且永远没有 break-glass。直到 beta.2，它的请求三者都有。除非这正是你想要的，否则不要声明这样的校验器。

  没有自己 `TokenVerifier` 的应用不受影响。
- **安全：提供的刷新路由使用你的 `RefreshTokenAuthentication`。** 直到 8.0.0-beta.2，该路由自己构建刷新认证，只使用 `MembershipRefresh<RefreshTokenCredentials>` Bean，所以声明在你的 `RefreshTokenAuthentication` Bean 中的钩子（例如拒绝已停用的用户）对它不起作用。现在由你的 Bean 提供这个路由。基于你自己凭证类的 Bean 在设置了 `cosec.authentication.login.refresh.path` 时会使启动失败：请从你自己的路由提供那个刷新并取消该路径，或者基于 `RefreshTokenCredentials` 声明它。
- **会话存储失败或无法执行某个调用。** `TokenRevoker.revokeSubject` 中存储失败（例如 Redis 宕机）时，现在抛出 `SessionStoreUnavailableException`，与 `revoke` 一直以来的做法相同：返回 `503` 并再次吊销。原来在 `revokeSubject` 外捕获存储自身异常的代码，改为捕获 `SessionStoreUnavailableException`。会话存储因为根本无法执行某个调用而抛出 `UnsupportedOperationException` 时，现在在每次会话存储调用（登录、刷新、切换租户、登出和两种吊销）中都原样抛出，而以前会变成 `SessionStoreUnavailableException`：这是配置问题，不是故障。在 starter 提供的刷新和登出路由上，这意味着 `500`，以前是 `503`。

## 从 8.0.0-beta.1 升级 {#from-8-0-0-beta-1}

- **升级前：给每个限制了命令的数据面 Redis 用户加上 `+evalsha_ro +eval_ro`。** 不运行控制面的实例现在以只读方式读取文档和候选，即 `EVALSHA_RO`（Redis 7.0 或更高版本，CoCache 8 本已要求）。用一个列出命令的用户（例如 beta.1 的[谁可以写哪些 key](./storage.md#who-may-write-which-keys) 给出的那个：先 `-@all`，再在其他命令中给出 `+evalsha +eval`），升级后的实例会在启动时失败：`Read-only store of Keyspace(...): Redis denied the command [evalsha_ro]: allow [+evalsha_ro] ...`。请先加上这两个命令；没有命令列表的用户（`+@all`）不需要任何改动。把文档和候选收窄为读权限（`%R~<s>:snapshot:* %R~<s>:candidate:*`）是可选的，页面上给出了写法。运行控制面的进程不变：它仍然写入它们。
- **`cosec.control.enabled` 可以在实例上关闭控制面**（新增：无需迁移；默认值 `true` 不改变任何东西）。两种角色共用一个带 `cosec-control` 镜像的部署，在数据面角色上设置 `COSEC_CONTROL_ENABLED=false`：它不运行控制面，以只读方式读取文档和候选，可以使用[谁可以写哪些 key](./storage.md#who-may-write-which-keys) 中的只读用户。`true` 或 `false` 以外的值会使启动失败。
- **starter 可以提供刷新和登出路由**（新增：在设置路径之前什么都不变）。`cosec.authentication.login.refresh.path` 和 `logout.path` 在 Spring MVC 和 WebFlux 上提供它们，状态码和响应体见[构建客户端](../integration/clients.md#what-your-application-serves)。你自己写的路由照常工作：不设置路径就不提供任何路由。要迁移到提供的路由，设置客户端调用的路径，删除你的处理器及其异常映射，并保留允许匿名 `POST` 它的语句。
- **会话存储的故障以 `SessionStoreUnavailableException`（`cosec-login`）离开 `TokenFamilies` 和 `StoreTokenRevoker`**，存储自身的异常作为其 cause，从而把无法应答的存储（`503`）与错误（`500`）区分开。在刷新、切换或登出周围捕获存储自身异常类型的代码，改为捕获它。`StoreTokenRevoker.revokeSubject` 不变。
- **`MembershipRefresh` 以 `TokenVerificationException` 拒绝**（成员关系已不存在时用 `TokenRevokedException`），这是 `401`；其他任何异常都是错误，在提供的路由上是 `500`，客户端保留令牌对。以其他异常（例如 `IllegalStateException`）拒绝的钩子，改为抛出 `TokenRevokedException`。
- **快照源让每个文档的键经由各自的运行器处理**（无需迁移，API 与属性都不变；决策不变）。运维能看到的不同：
  - 在文档加载期间发出、在该加载结束之后才被取出的请求，得到已安装的文档，算作命中；beta.1 把它并入那次加载，算作冷加载：文档相同，但 `cosec.snapshot.loads{result=cold}` 少计一次。
  - `close` 结束了某次加载之后才答复"不存在"的存储读取不再上报：已关闭的实例不会因此记录 `Snapshot document [<doc>] disappeared from the store; failing closed.`，也不计入 `cosec.snapshot.lost`。在存储读取超时（30 秒）时被放弃、之后才答复"不存在"的读取，由该文档的下一次加载上报，而不是立即上报。
  - 不存在文档的 id 的上限不再有空档（[实例如何读取文档](./storage.md#how-instances-read-documents)）：驱逐会跳过正在加载的条目，当这次加载发现文档仍不存在时，该条目现在自己请求回到上限。beta.1 中，族会一直高于这个上限，直到之后某次安装。
  - 平台文档变化、所有文档重新编译时，有在途加载的文档可能多加载一次：每个文档至多多读一次存储。
  - 每个持有的文档多占约 64 字节堆内存，每次冷加载多分配约 1.2 KB。
- **快照源有了指标**（新增：无需迁移）。有 `cosec-micrometer` 和 `MeterRegistry` 时，数据面的快照源现在注册 `cosec.snapshot.*` 指标：新增 80 个序列，与文档数量无关，因为没有任何标签携带应用或租户 id。找到已安装文档的请求不计数：只计请求不得不等待的加载。[`cosec.metrics.enabled`](./configuration.md#feature-switches)`=false` 会连同 CoSec 的其他指标一起关闭它们。各指标及告警条件见[快照指标](./observability.md#snapshot-metrics)。
- **快照源的维护线程拒绝的任务被丢弃并计数**（`SNP-19`；无需迁移）。该线程写快照源的日志、执行驱逐，队列容纳 4,096 个任务。队列满时，beta.1 在提交任务的线程（常常是请求线程）上执行被拒绝的任务，于是慢的日志 appender 会拖慢请求。现在任务被丢弃并计入 `cosec.snapshot.housekeeping.refused{task}`，该线程最多每 10 秒记录一条 `WARN`：`Snapshot housekeeping since its last summary: refused <n> tasks, full or stopped …`，列出每种任务最后被丢弃的那条日志，不带堆栈；关闭中的实例把剩下的写出一次。抛出异常的指标在同一行里计数，而不单独记录日志。无需任何操作，除非出现这条摘要：那时改用异步日志 appender（[快照日志缺失](./troubleshooting.md#snapshot-log-lines-are-missing)），并对该计数器的任何上升告警。

## 从 8.0.0-alpha.4 升级 {#from-8-0-0-alpha-4}

**如果你发布过任一预览版的 `cosec-migrate convert` 写出的文档，请检查其中的 ALLOW 语句**（下面第一条）：转换得到的 7.x"所有动作"授予了控制面。
8.0.0-alpha.4 是预览版，没有兼容性承诺。**在此版本之前签发的令牌都没有声明应用，配置了应用的服务会拒绝它们**（见下）：请安排重新登录，或等待令牌过期。以 format 2 存储的 API key 会失败，需要重新发放。**升级前请关闭或撤回打开的影子窗口**：8.0.0-alpha.4 暂存的候选在文档旁另写了头部，会被当作无法读取而拒绝，于是它的窗口不被比较。

- **修订索引的条目增加了 `parent`，每个文档第一次读取时会重写 alpha 写下的索引**（`CTL-35`）。`<s>:control:revisions:<doc>` 的每个条目带着其文档所声明的父修订的摘要；只有当最后一个条目是头、且每个条目都指向它下面那个条目时，才会返回文档的历史。此前写下的索引没有 `parent`：每个文档第一次读取（一次控制 API 调用，或维护任务）会沿头的链走一遍，解码每条保留的记录，并用它重写条目；不需要迁移，也不会丢失任何东西。`RevisionEntry` 的构造函数多了第四个参数 `parent`（默认 `null`），供自己构造它的代码使用。同一次读取还会修复头退回、修订号被重用的情形——此前这会读到旧分支的文档；见[修订、历史与回滚](./storage.md#revisions-history-and-rollback)。
- **安全：租户切换不再从已吊销或已结束的会话签发。** 从 8.0.0-alpha.1 到 8.0.0-alpha.4，切换自己不读取吊销记录：吊销存储无法读取时（请求路径此时把它读作"未吊销"），已登出的令牌，或被吊销主体的令牌，可以被切换成一个没有任何吊销记录指名的新令牌。会话是在 8.0.0-alpha.4 之后才有的；在这次修改之前，切换还会从一个自身会话已被吊销的令牌开始一个有效的会话：会话被刷新之后再次出示的令牌对吊销（`IDN-16`，其访问令牌在 `cosec.jwt.token-validity.access`（10 分钟）之内仍然有效），或被登出吊销。复制了令牌对并抢先刷新的人因此可以持有一个会话，直到用户的会话本该结束时为止，最长 `cosec.jwt.session.lifetime`。这一点不在任何预览版中。现在切换只从有效的会话签发，并严格读取吊销记录，与刷新共用同一个检查（`IDN-22`、`IDN-14`）：从已失效的会话切换回答 `token-revoked` 或 `token-expired`，存储无法读取时回答 503。预览版的切换在吊销存储故障期间签发的令牌，不会被它躲过的那条吊销记录吊销；如果可能存在这样的令牌，请[吊销该主体](../integration/authentication.md#revoking-every-token-of-a-principal)。
- **安全：转换得到的 7.x"所有动作"不再授予控制面。** 从 8.0.0-alpha.1 到 8.0.0-alpha.4，`convert` 把针对所有 7.x 动作的 ALLOW 写成
  `"action": "*"`，它还匹配每一个控制面动作（`cosec:*`）和代码中检查的每一个动作。像"登录用户可以做任何事"这样的全局策略经转换后，给了每个已认证主体
  对其所在租户文档的全部控制面动作，`(platform)` 的主体则得到对平台文档和应用文档的全部控制面动作。可附加的策略对列出它的每个令牌也是如此。这样的主体
  可以在该文档中写入和授权，持有同样授权的另一个主体可以批准。`convert` 现在写 `"action": "http:*"`
  （[所有动作即所有 HTTP 请求](./upgrading.md#every-action-is-every-http-request)）；针对所有动作的 DENY 仍是 `*`。如果你发布过转换得到的文档：
  1. 找出每个文档（`platform`、`app:<id>`、`tenant:<id>`）中 `*` 上的 ALLOW 语句。管理员通过控制 API 读取 head
     （[修订、历史与回滚](./storage.md#revisions-history-and-rollback)）：

     ```bash
     curl -s "$CONTROL/documents/platform" -H "$AUTH" \
       | jq -r '.policies[] | select(any(.statements[]; .effect == "allow" and ([.action] | flatten | index("*")))) | .id'
     ```

     `convert` 写出的那些在这次转换的 `platform.json`、`apps/` 和 `tenants/` 中，它的 `provenance.json` 给出每一条对应的 7.x 策略。升级之后
     你自己写的 `*` 上的语句由你决定是否保留。
  2. 通过控制面把每一条转换得到的语句改为 `http:*`：提交一个变更，以 `"action": "http:*"` 重新写入该策略。如果某个文档自 `publish` 以来没有变过，
     也可以对它 `apply` 新一次 `convert` 写出的文件，它提出的变更正好是这些语句。
- **租户和应用 id 不含 `/`**（`SCP-13`）。8.0 预览版接受 `acme/west` 这样的租户 id 和 `a/b` 这样的应用 id；本版本在每个入口都拒绝它们，
  所以存储过这种 id 的预览版部署必须在升级前删除它们的记录。在预览版上用 `GET /documents` 查找：`tenant:` 或 `app:` 之后含 `/` 的键。
  预览版自己的控制面无法删除它们，因为它同样拒绝这种 id。先停止控制面，然后对每个这样的文档 `<doc>`（`tenant:acme/west`）删除 `<s>` 下的这些
  Redis 键（[Redis 键](./storage.md#redis-keys)）：
  - `<s>:snapshot:<doc>`（头）和 `<s>:candidate:<doc>`（暂存的候选）；
  - `<s>:control:revisions:<doc>` 以及每个 `<s>:control:revision:<doc>:*`（修订索引与修订记录）；
  - 每个 `<s>:control:refs:<doc>:*`（指向它的策略的反向引用）；
  - `<s>:control:stage:<doc>` 和 `<s>:control:lease:reclaim:<doc>`；
  - 每个 `"key"` 为 `<doc>` 的 `<s>:control:change:<id>`，以及 `<s>:control:list:open-changes` 中它的 id；
  - `<s>:control:list:documents` 中的条目 `<doc>`；应用还要删除 `<s>:control:apps:all` 中它的条目；
  - 每个 `"tenantId"` 或 `"app"` 含 `/` 的 `<s>:api-key:<sha256>`。

  然后删除每个在引用方中列出 `<doc>` 的 `<s>:control:refs:*` 集合，再运行 [`repair-references`](./upgrading.md#repair-the-back-reference-index)，它会写回其余的头所引用的内容。
  完成之前，本版本在每条这样的记录上整体失败并指名它（`SCP-10`）：反向引用集合让发布其策略失败，变更回答 `503`，未完成的变更让每轮维护停止，
  API key 查找失败（`503`）。安装过这种文档的预览版实例，其实例状态会让影子窗口的状态读取失败，直到它过期，即该实例停止两分钟之后（状态 key 的存活时间，`ShadowCacheSpecs.STATUS_TTL`）。
  给租户或应用改名，并以新 id 发布它的文档；旧 id 的令牌是 `401 token-invalid`（[租户和应用 id 不含斜杠](./upgrading.md#tenant-and-app-ids-hold-no-slash)）。
- **没有任何 CoSec 模块导入 platform，`cosec-dependencies` 不再发布**（R11）。此前每个库模块的 POM 和 Gradle 模块元数据都导入 `cosec-dependencies`，后者导入 Spring Boot、Spring Cloud、CoCache、OpenTelemetry、springdoc 和 fluent-assert 的 BOM，并管理 jmh、mockk、archunit、blockhound、json-schema-validator 和 detekt-formatting 的版本，所以加入一个模块就会提升应用整个构建中的这些版本，包括 `cosec-cocache` 的 CoCache 8 盖过应用自己的 CoCache 4。现在模块的元数据只写明它需要的库及其构建时的版本（作为最低版本），别无其他；Maven 也不再需要那个导入来解析它们。导入过 `me.ahoo.cosec:cosec-dependencies` 的应用请去掉这个导入，自己管理想要的版本（[CoSec 对构建的要求](../guide/introduction.md#what-cosec-needs-from-your-build)）。`cosec-bom` 列出应用可以依赖的 CoSec 模块（不含 `cosec-migrate`），不导入任何东西。`cosec-cocache` 和 `cosec-kafka` 的 POM 把 `cosec-control` 标为 `provided`：使用控制面的 Maven 应用自己添加 `cosec-control`，此前它们会把它（连同 `cosec-runtime`）带到运行时。
- **三个包名与其模块名对齐。** `me.ahoo.cosec.cache`（`cosec-cocache`）现在是 `me.ahoo.cosec.cocache`，`me.ahoo.cosec.servlet`（`cosec-webmvc`）是 `me.ahoo.cosec.webmvc`，`me.ahoo.cosec.spring.boot.starter`（`cosec-spring-boot`）是 `me.ahoo.cosec.spring.boot`。子包在新根之下保持原名；没有类名、artifact id、`cosec.*` 属性、bean 名或 Redis key 改变。在应用中需要修改：import；`spring.autoconfigure.exclude` 与 `@ConditionalOnClass(name = ...)` 字符串中的自动配置类名（`me.ahoo.cosec.spring.boot.starter.CoSecAutoConfiguration` 现在是 `me.ahoo.cosec.spring.boot.CoSecAutoConfiguration`）；以及日志配置中的 logger 名（`logging.level.me.ahoo.cosec.cache` 现在是 `logging.level.me.ahoo.cosec.cocache`）。
- **策略文档、路由映射和 JSON 格式成为独立模块 `cosec-document`。** `me.ahoo.cosec.runtime.document` 现在是 `me.ahoo.cosec.document`，`me.ahoo.cosec.runtime.document.format` 是 `me.ahoo.cosec.document.format`，`me.ahoo.cosec.runtime.route` 是 `me.ahoo.cosec.document.route`；类（`PolicyDocument`、`PolicyDocumentCodec`、`PolicyDocumentCompiler`、`DocumentCompileException`、`Actor`、`RouteMap`、`RouteMapCodec` 等）保持原名。没有文档、schema、`cosec.*` 属性、bean 或 Redis key 改变。在应用中修改 import 即可。`cosec-runtime` 和 starter 会带上 `cosec-document`，所以依赖不变；只读写文档的代码（生成器、[CI 检查](../policies/testing.md#policies-in-ci)）可以只依赖 `me.ahoo.cosec:cosec-document`，不必依赖 `cosec-runtime`。`cosec-openapi` 现在依赖 `cosec-document` 而不是 `cosec-runtime`：经由它编译依赖管道的应用需要自己添加 `cosec-runtime` 或 starter。
- **影子窗口端口及其值移到 `cosec-spi`。** `CandidateStore`、`StoredCandidate`、`ShadowReportSink`、`ShadowReportReader`、`ShadowReport`、`ShadowSample`、`ShadowOutcome`、`InstanceStatus` 和 `InstalledDocument` 从 `me.ahoo.cosec.runtime.shadow` 移到 `me.ahoo.cosec.spi.shadow`，仍是实验性 API，名称不变。你自己的存储修改 import 即可，并且可以只依赖 `cosec-spi`。`cosec-cocache` 不再依赖 `cosec-runtime`：只经由 `cosec-cocache` 编译依赖管道的应用需要自己添加 `cosec-runtime` 或 starter。存储格式和 Redis key 都不变。
- **签发令牌归 `cosec-login`。** `TokenFamilies`、`DefaultTokenRevoker` 和 `SimpleCompositeToken` 从 `me.ahoo.cosec.runtime.token` 移到 `me.ahoo.cosec.login.token`，名称不变，其中两个后来又变了：`DefaultTokenRevoker` 是同一个包里的 `StoreTokenRevoker`，`SimpleCompositeToken` 已不存在，由 `cosec-api` 中的 `CompositeToken` 取代（均见下）；`me.ahoo.cosec.runtime.token` 保留校验请求令牌所需的部分（`TokenRevocation`、`CoSecTokenVerifier`）。在应用中修改 import 即可；starter 和 `cosec-jwt` 会带上 `cosec-login`，所以依赖不变。
- **适配器契约集中到一个包 `me.ahoo.cosec.runtime.http`。** `RequestGate`、`Outcome`、`Problem`、`ProblemType`、`ProblemEncoder` 和 `RequestSecurity` 从 `me.ahoo.cosec.runtime` 移入，`TraceContext` 从 `me.ahoo.cosec.runtime.event` 移入，与 `HttpFacts`、`Headers`、`Cookies` 放在一起；类名不变。在应用中修改 import 即可：你自己的适配器、读取 `RequestSecurity` 的处理器、读取 `Outcome` 的测试。状态码、响应头、响应体和属性都不变。有五个 logger 改为以现在独立记录日志的类命名，按 logger 名设置级别的日志配置改用新名字：

  | 之前 | 之后 |
  |---|---|
  | `me.ahoo.cosec.runtime.FallbackIds` | `me.ahoo.cosec.runtime.call.FallbackIds` |
  | `me.ahoo.cosec.runtime.attribute.AttributeProviders` | `me.ahoo.cosec.runtime.attribute.FetchedAttributes` |
  | `me.ahoo.cosec.runtime.event.ShadowListener` | `me.ahoo.cosec.runtime.shadow.ShadowListener` |
  | `me.ahoo.cosec.runtime.shadow.Candidates` | `me.ahoo.cosec.runtime.shadow.CachingCandidateSource` |
  | `me.ahoo.cosec.runtime.shadow.ShadowRecorder` | `me.ahoo.cosec.runtime.shadow.ShadowReporter` |
- **变更日志有一个有意取定的 logger 名。** `LoggingChangeListener` 原来把变更日志写到 logger `me.ahoo.cosec.control.audit.ChangeAudit`，这个名字来自它所在的文件；现在写到 `me.ahoo.cosec.changelog`（`LoggingChangeListener.LOGGER_NAME`），与决策审计写到 `me.ahoo.cosec.audit` 一样：这个名字在所有包之外，任何类的 logger 都不是它的子 logger。按旧名路由或过滤的日志配置或日志采集器改用新名（[控制面变更日志](./observability.md#control-plane-change-log)）。日志行本身不变。分发器自己的警告 `Change listener [<name>] could not record <event>.` 现在由它自己那个类的 logger `me.ahoo.cosec.control.audit.ChangeListeners` 记录。
- **不使用 Spring 时用 `Pipeline.builder` 构建管道。** `Pipeline.builder(authenticator, store)` 像 starter 一样编译并装载 `SnapshotDocumentStore` 中的文档。`memberships`、`attributes`、`listener`、`rateLimiter`、`routes`、`ids`、`clock` 和 `forwardIdentity { … }` 设置其余部分；`options { … }`、`snapshots { … }` 和 `listeners { … }` 设置原来由 `PipelineOptions`、`SnapshotOptions` 和 `DecisionListeners` 的参数设置的内容，名称和默认值不变。`snapshots { prewarmApps = …; prewarmTenants = … }` 指定构建时装载的文档，与 starter 的 `cosec.authorization.snapshot.prewarm-apps` 和 `prewarm-tenants` 相同；`awaitDocuments()` 等到它们都已读取，返回尚未装载的那些，就绪时为空集合。`close()` 先投递监听器队列中的事件，再关闭文档。位于 CoSec 网关之后的服务用 `Pipeline.behindGateway { … }` 获得它的 gate。`Pipeline` 的构造器、`PipelineComponents` 和 `SuspendingSnapshotSource.of` 已删除；`PipelineOptions`、`SnapshotOptions`、`SuspendingSnapshotSource`、`AttributeProviders`、`DecisionListeners`、`IdentityIssuer`、`IdentityGate` 和 `CoSecParameters.read` 现在是 `@InternalCoSecApi`，应用从不选择使用它们。状态码、响应头、响应体和 `cosec.*` 属性都不变。对手工组装的管道有一处朝安全方向的变化：它的文档现在像 starter 的一样编译，所以读取它所读 API key 请求头（`options { apiKeyHeader = … }`）的条件或限流规则 key 不能编译（`CND-5`）。这样的文档被装载为失败，其请求是 503；此前 `SuspendingSnapshotSource.of(store)` 会装载它。给同一项输入两种形式的构建器（例如 `listener` 与 `DecisionListeners`）拒绝构建，而不是丢掉其中一个。

  ```kotlin
  val pipeline = Pipeline.builder(authenticator, store)
      .snapshots { prewarmTenants = setOf("acme") }
      .memberships(memberships)
      .build()
  check(pipeline.awaitDocuments().isEmpty()) // 在第一个请求之前
  ```
- **公共与 SPI 签名中的时长都是 `java.time.Duration`。** `AttributeProvider.timeout` 是 `java.time.Duration?`：自己的提供方原来写 `200.milliseconds` 的地方，改为使用 `java.time.Duration` 的 `override val timeout: Duration? = Duration.ofMillis(200)`。`CoCacheMembershipProvider.DEFAULT_FILL_TIMEOUT`，以及 `KafkaAuditListener` 和 `KafkaChangeListener` 的 `drainTimeout`，同样使用 `java.time.Duration`；原来传入 `kotlin.time.Duration` 的代码用 `toJavaDuration()` 转换。属性、默认值和行为都不变。
- **`CompositeToken` 是 `cosec-api` 中的 final 类，`SimpleCompositeToken` 已删除。** 用 `CompositeToken(accessToken, refreshToken)` 构造。它按值相等，没有 `copy` 或 `componentN`。记录令牌的日志不再打印令牌：它的字符串是 `CompositeToken(accessToken=***, refreshToken=***)`。登录或刷新端点返回的 JSON 不变（`{"accessToken":…,"refreshToken":…}`）。`RefreshTokenCredentials` 自己声明 `accessToken` 和 `refreshToken`，不再是 `CompositeToken`：应用实现的凭证不继承一个值。你的刷新凭证类像以前一样覆盖这两个属性：

  ```kotlin
  data class RefreshCredentials(override val accessToken: String, override val refreshToken: String) :
      RefreshTokenCredentials
  ```

  在需要 `CompositeToken` 的地方（`TokenRevoker.revoke`）传入它的代码，要构造一个：`CompositeToken(credentials.accessToken, credentials.refreshToken)`。
- **`SimpleRefreshTokenAuthentication` 更名为 `RefreshTokenAuthentication`**，包和构造参数不变：唯一的实现不需要前缀。在声明它的 bean 的地方改类名。
- **`DefaultTokenRevoker` 更名为 `StoreTokenRevoker`**：它经由 `TokenStore` 吊销，名字现在说出了这一点。包（`me.ahoo.cosec.login.token`）和构造参数不变；starter 的 bean 仍是 `cosecTokenRevoker`，`TokenRevoker` 仍是 SPI。在构造它的地方改类名。
- **`api.CoSec` 已删除。** 它的值拼写不变，在有含义的地方各有名字：匿名主体是 `CoSecPrincipal.ANONYMOUS_ID`，默认租户是 `TenantId.DEFAULT`，两个都拼作 `(0)` 的不同 id；`cosec.*` 属性和 Redis key 的默认前缀（`cosec.key-prefix`，`cosec`）不变，span 属性（`cosec.tenant_id` 等）和 OpenAPI 方案 `cosec.BearerAuth` 也不变。读取 `CoSec.COSEC`、`CoSec.COSEC_PREFIX` 或 `CoSec.DEFAULT` 的代码，改为写出它所指的值。key、属性和 span 属性都不变。
- **租户切换是带 `TenantSwitch` 的 `SwitchTenantAuthentication`，它可以设定应用。** `AbstractSwitchTenantAuthentication` 已删除。原来继承它并实现 `switchTenant(targetTenantId, previous)` 的地方，改为用 starter 的 `TokenFamilies` 和 `TokenRevocation` bean，把 `SwitchTenantAuthentication(families, revocation, YourCredentials::class.java) { credentials -> … }` 声明为 bean：切换拿到的是你自己的凭证类（`credentials.targetTenantId`、`credentials.context`，以及它携带的其他内容），像以前一样回答 `TenantMembership`。`SwitchTenantCredentials` 新增 `targetAppId`（用户请求的应用，默认为 null），并删除了 `accessToken`：切换从请求的会话签发，管道构造的 `context` 携带这个会话（`IDN-22`），所以不要再传令牌文本（也不要为此解析 `Authorization`）。`TenantMembership` 新增 `app`。新令牌属于你的切换回答的应用，否则属于先前令牌的应用（`SCP-12`）：不回答应用的切换行为不变，用户也永远不能靠指名得到一个应用。`TokenSubject.authTime` 已删除，`TokenFamilies.rotate` 和 `TokenFamilies.startAt`（原来的 `start(…, authTime)`）归 CoSec 自己使用（`@InternalCoSecApi`）：从先前的登录签发，必须经过有效会话的检查。见[切换租户和应用](../integration/authentication.md#switching-tenant-and-app)。
- **转发的身份携带 CoSec 令牌的会话和签发时间**（`auth.session`：`sid`、`auth_time`、`generation`；`auth.iat`），使网关之后的服务知道用户何时登录，并让那里的租户切换从这个会话签发（`IDN-22`）。格式仍是版本 1，服务严格读取它：比网关旧的服务会以 `400` 拒绝这些新成员。**请先升级网关之后的服务，再升级网关。** 没有这些成员的身份（API key 的，或更早的网关发送的）照旧读取，但不能从它切换。`Identity.Credential` 新增可选的 `issuedAt` 和 `session`。
- **`SecurityContext` 新增 `requestId` 和 `deviceId`。** `requestId` 是 CoSec 为该请求使用的 id（客户端的 `CoSec-Request-Id`，或 CoSec 生成的、任何请求头都没有的 id）；`deviceId` 是客户端的 `CoSec-Device-Id`，未声明时为空，CoSec 只检查其形式，它不能证明是哪台设备。请从上下文读取它们，而不是读请求头。你自己的 `SecurityContext` 实现照样能编译：两者默认都是 `""`。在网关之后，服务现在按网关读取 `CoSec-Device-Id` 的同一规则读取它（只能有一个值，否则 `400`），因此代码中检查的 `environment.device` 在那里也能看到它；以前在网关之后看不到设备。
- **刷新可以重新读取用户。** `RefreshTokenAuthentication` 接受一个可选的 `MembershipRefresh`，回答用户现在拥有什么；校验、吊销检查、轮换和会话仍由这个类完成。原来为在刷新时重新读取用户而编写自己的 `Authentication` 的地方，改为传入 `MembershipRefresh`。不传时，刷新照旧保留令牌的声明。钩子会拿到令牌和刷新的凭证（`{ token, credentials -> … }`）；要在那里读取你自己的内容（例如设备），把你的凭证类也传进去：`RefreshTokenAuthentication(verifier, revocation, families, YourCredentials::class.java) { token, yours -> … }`，与租户切换接受自己的凭证类一样。`RefreshTokenAuthentication(verifier, revocation, families)` 的写法不变。
- **`TokenCompositeAuthentication` 用 `TokenCompositeAuthentication.of(authentications, tokenConverter, families)` 构建。** 它的构造器需要两个应用无法构建的类型（`CompositeAuthentication`、`DefaultAuthenticationProvider`），现在是 `@InternalCoSecApi`。starter 的 bean 不变；手工组装的登录（例如在测试中）把它的 `Authentication` 传给 `of`。
- **任何请求都不可能有的 `http` 动作不能编译**（`DOC-8`）。`http:HEAD`（自 `PIP-18` 起按 `http:GET` 判定）或动词含小写字母（`http:get`）的语句或限流规则不匹配任何请求，这样的 DENY 什么也拒绝不了。现在这样的文档被拒绝：已存储的会被装载为失败，其请求失败即拒绝（`503`），控制面也拒绝会产生这种文档的变更。升级之前，请在控制面列出的文档中查找它们（`"http:HEAD"`，或冒号后含小写字母的 `"http:` 动作），改为 `http:GET` 或大写的方法后重新发布。对 7.x 的转换，`cosec-migrate convert` 不论语句如何都会报告一次：HEAD 请求现在按 GET 决策（`PIP-18`：GET 被放行处放行，GET 被拒绝处拒绝）；它把 7.x 的 `ipRegion` 的国家前缀读作 `ext.ip2region.country`，平台文档随后声明它（执行这类语句的每个宿主都需要 `cosec-ip2region`）；只有 7.x 的请求附加器填充过的部分（`ipRegion`、应用自己的属性）在 8.0 中始终缺失，所以对它的条件只在收窄 ALLOW 时才翻译（记为 `NOT-IN-8.0`），其余情况都被拒绝（`MIG-1`），因为它会放宽 ALLOW、拒绝范围内的每个请求，或使 DENY 不再匹配 7.x 拒绝过的内容。
- **`cosec-migrate cocache8` 已删除。** 它把 8.0.0-alpha.2 的数据从 CoCache 6 迁移到 CoCache 8（[经 CoCache 6 写入的数据](#data-written-through-cocache-6)）：仍保存这种数据的部署，请在升级之前用 8.0.0-alpha.4 的 `cosec-migrate` 迁移。`<p>:meta:cocache8` 存在时，`publish` 拒绝执行，不写入任何内容：这个标记由一次在删除文档之后中断的升级留下，那些文档在它的导出目录里，不在 Redis 中。请用 8.0.0-alpha.4 的 `cosec-migrate` 完成那次升级（`publish --resume`，`--in` 指向消息给出的导出目录），或者确认标记列出的每份文档都已发布，再删除这个 key。
- **`cosec-migrate api-keys --app` 可用了**（第 2 轮 M3）。自 #1302 起这一步无法运行：#1302 把 `--app` 加进了代码和本页，却没有加进该命令接受的选项，命令因此把 `--app` 当作它不接受的选项而拒绝。对配置了应用的服务必须接受的 key，请带上 `--app <id>` 重新运行。
- **starter 的启动检查在延迟初始化下也会运行**（`STA-3`）。设置 `spring.main.lazy-initialization=true` 时，此前没有任何检查运行：缺少 `cosec-webmvc` 的 Spring MVC 宿主在完全没有授权的情况下启动，`gateway` 模式的 Spring Cloud Gateway 宿主不经检查地转发每个请求，Redis 的逐出策略、从副本读取、暴露的 actuator 端点、没有 `MembershipProvider` 的 API key 和未知的 `cosec.*` 属性都不受检查。现在它们与不使用延迟初始化时一样使启动失败。只因检查被跳过才能启动的配置不再能启动：按消息修正。
- **社交登录注册中的未知字段使启动失败**（第 2 轮 L4）。此前 `cosec.authentication.login.social.registration.<name>` 下拼错的字段（`pcke`、`scope`）被忽略，提供者的配置与作者的本意不同；现在消息会指出这个键。请改名或删除。
- **不起作用的 `cosec.cosid.generator` 使启动失败**（第 2 轮 L7）。此前在没有 `CosIdRuntime` bean、或者应用自己有 `IdGenerator` bean 时设置它，它会被忽略，CoSec 仍使用随机 id。请配置 CosId，或删除该属性。
- **两个哈希相同的内存 API key 使启动失败**（第 2 轮 L5）。此前该 key 以 `cosec.api-key.keys` 中最后一项认证；每个 key 只保留一项。
- **控制面的授权 bean 不可替换，无论是否 `@Primary`**（第 2 轮 L6）。此前标记 `@Primary`、或按注入点参数名命名的第二个 `ControlAuthorizer`、`ChangeAuthority`、`Changes`、`ControlPlane` 或 `ControlHttp` bean 会取代 CoSec 的 bean 被注入；现在它与普通的第二个 bean 一样使启动失败。
- **主体的属性只读**（第 2 轮 L3）。此前 `SecurityContext.principal.attributes` 就是认证器交出的那个映射：应用把它强转为可变映射并修改，就改变了该请求之后每一次 `AuthorizationChecker` 检查所依据的内容。现在它是不可修改的视图，令牌中嵌套的对象和数组也是只读的：修改会抛出 `UnsupportedOperationException`。要修改请先复制。你自己的认证器交出映射后不得再修改它。
- **测试工具按执行的方式读取请求**（第 2 轮 M2，`PIP-15`）。此前 `PolicyTestKit` 按原样判定 `http:HEAD`、含小写字母的方法和带点段的路径，所以会确认一条 `http:HEAD` 上的 DENY，而管道把 HEAD 按 GET 判定，从不应用它。现在它把 HEAD 按 GET 判定、规范化路径，并在管道回答 `400` 时给出 `{"decision": "InvalidRequest"}`：请改写这样的场景，并把 `http:HEAD` 上的语句改写为 `http:GET` 上的。`expect` 必须写出 `decision`（此前空的 `expect` 无论决策如何都会通过）；文件名被缩短的目录需要 `cosec-migrate convert` 在旁边写下的 `report.json`。实验性的 `evaluate(JsonNode)` 和 `check(String, JsonNode)` 已删除：以文本传入场景，用 `check(name, json)`。
- **生成的路由映射位于 `servers` 路径之下，资源包含路径的每个变量**（第 2 轮 L4）。`cosecServiceGenerator` 端点丢掉了服务器路径（`/api/v1`），生成的路由匹配不到任何请求，所有请求都落在 `http` 命名空间；`/users/{id}/avatar/{size}` 的资源是 `user/{size}`，所有用户共用一个资源。现在路由以服务器路径开头（不含上下文路径），资源是 `user/{id}/{size}`：重新生成路由映射，并改写策略中按生成结果写的资源。多个服务器路径不同，或路径含服务器变量时，生成失败。
- **ip2region：无法定位的是取不到**（第 2 轮 M5）。只有随附的 IPv4 数据库时，IPv6 客户端没有国家，写成 `country in [...]` 的地域封锁 DENY 会放行它；现在它是取不到，这条 DENY 使请求返回 `503`（或者用 `onMissing: nomatch` 放行）。用 `cosec.ip2region.ipv6-location` 加载 IPv6 数据库。`Ip2RegionAttributeProvider` 改为接收数据库列表，每个数据库的版本取自其文件头，不再接收单个数据库和版本。
- **失败的 `IdGenerator` 只损失它自己的 id**（`PIP-23`）。此前抛出异常的生成器（时钟回拨后 CosId 的 `ClockBackwardsException`）会在得出任何结果之前离开管道：没有问题体，没有 `CoSec-Request-Id`，没有决策事件，各适配器各自应答。现在以随机 id 代替，并至多每十秒记录一次 WARN；`CosIdIdGenerator` 对 CosId 的失败也是如此。请把 CosId 的时钟回拨策略配置为失败而不是等待。
- **处理器从 `SecurityContext` 参数读取安全上下文**（第 2 轮 M4）。starter 注册了 `SecurityContextArgumentResolver`。`SecurityContextHolder` 只用于请求线程上的阻塞代码：在另一个请求的线程上恢复的 `suspend` 处理器，此前会从它读到那个请求的上下文。有 `io.micrometer:context-propagation` 并设置 `spring.reactor.context-propagation=auto` 时，Spring 现在也会恢复持有者；否则在 `suspend` 处理器中持有者是未绑定的：在那里 `SecurityContextHolder.context` 为 `null`，`requiredContext` 会抛出异常（`PIP-22`）。
- **应用只读取安全上下文**（第 2 轮 L5）。`SecurityContextHolder.setContext` 和 `remove`、`ServletRequests.setSecurityContext`、`ServerWebExchanges.setSecurityContext`，以及 `ReactiveSecurityContexts.setSecurityContext` 和 `writeSecurityContext` 都是内部 API：只有适配器绑定上下文。自行绑定上下文的代码（`AuthorizationChecker` 本来就拒绝这样的上下文）不再能编译。
- **context path 按段去掉**（`PIP-20`）。Jetty 以规范形式给出 context path，而 URI 保留客户端的写法：context path 为 `/app` 时，`/%61pp/orders/1` 此前按 `/app/orders/1` 判定，绕过了 `/orders` 上的所有语句；现在是 `/orders/1`。不以 context path 开头的 URI 是 `400`。
- **Spring MVC 过滤器的顺序改为 `-9999`**，紧跟在 Spring Boot 的隐藏方法过滤器（`-10000`）之后，而不再是 `HIGHEST_PRECEDENCE + 100`。打开 `spring.mvc.hiddenmethod.filter.enabled` 时，带 `_method=DELETE` 的 `POST` 此前按 POST 授权、按 DELETE 执行；WebFlux 一直授权的是 DELETE。必须排在 CoSec 之前的过滤器（例如 CORS 过滤器）需要低于 `-9999` 的顺序。
- **HEAD 按 GET 判定，含小写字母的方法是 `400`**（`PIP-18`）。服务器用 GET 的处理器处理 HEAD，所以 `http:GET` 上的 DENY 拦不住它：现在 HEAD 使用路由映射的 HEAD 路由，没有时使用 GET 的路由，未映射时是 `http:GET`；`http:HEAD` 上的语句会被拒绝（`DOC-8`，见上），因为 HEAD 按 `http:GET` 或路由映射的 HEAD 路由的动作判定。`delete` 这样的方法此前按 `http:delete` 判定，`http:DELETE` 上的 DENY 不会命中，而会折叠大小写的后端把它当作 DELETE 处理：它现在是 `400 invalid-request`。`AuthorizationChecker` 以及控制面的 simulate、explain、analyze 按同样的方式读取 `http` 动作：`http:HEAD` 按 `http:GET`，小写动词为拒绝或 `400`。
- **客户端地址只读取一次**（`PIP-16`）。服务器自己解析转发头时（`server.forward-headers-strategy=native`，Spring Boot 在 Kubernetes 以及它识别出的其他云平台上也会自行开启），CoSec 不再第二次读取 `X-Forwarded-For`：在 Tomcat 上采用 `RemoteIpValve` 解析出的地址，在 Reactor Netty 和 Jetty 上读取该请求头而绝不使用服务器转发来的地址。此前在 Tomcat 上，两次读取叠加会取到客户端写的条目。现在以下情形启动失败：`framework`；`native` 下的 Tomcat 显式设置了 `cosec.authorization.remote-ip.max-trusted-index`；`native` 下的 Reactor Netty 或 Jetty 设置了 `max-trusted-index: 0`；CoSec 无法判断的服务器上的 `native`。迁移方法：按消息修改（[故障排查](./troubleshooting.md#startup-fails-on-the-client-address)）。`XForwardedRemoteIpResolver` 现在是内部 API。
- **多行 `X-Forwarded-For` 视为一个列表，读不出的客户端地址是“取不到”**（`PIP-17`）。此前带两行该请求头的请求使用连接的对端，客户端可借此绕开针对它自己地址的封禁；现在按顺序读取这些行。带端口、方括号或 zone 的可信条目按其中的地址读取。不是地址的条目（`unknown`）此前使 `environment.ip` 缺失，DENY 视为不匹配；现在它是取不到：这样的 DENY 返回 `503`，黑名单也回答取不到。
- **凭证的租户必须是租户 id**（`IDN-11`）。`tenantId` 为空白、`*`、含 `:` 或控制字符、含 `/`、或超过 128 个字符的令牌现在是 `401 token-invalid`（此前为 `503`，空白时为 `500`），`JwtTokenConverter` 也不签发；租户如此的 `ApiKey` 无法构造，所以已存储的会查找失败（`503`），配置的会启动失败。
- **`cosec-spi` 的 `SnapshotVersion` 已删除**：它表示 7.x 的两份文档。决策事件的 `snapshotVersion` 仍是 `ViewVersion`（`platform`、`app`、`tenant`）。
- **限流窗口至多一天**（`windowSeconds` 至多 `86400`），`permitsPerSecond × windowSeconds` 至多 2⁶³：更大的配额会使限流器的算术溢出，更长的窗口会在一个不得逐出的 Redis 中为每个桶值保留一个 key 两个窗口（第二轮复审 M3）。这样的文档不再能解码；`RateLimit` 拒绝同样的取值。迁移方式：把按天或更长的配额写成一天窗口内的每秒速率。
- **Redis 限流器每个桶一个 key，并按 Redis 的时钟计数**（第二轮复审 L1）。一个桶现在是 hash `<cosec.key-prefix>:rate-limiter:<rule>:<bucket>`，而不是每个窗口一个 key（`...:{<rule>:<bucket>}:<window>`）；脚本从 Redis 取时间，时钟不一致的实例不再各自计数。旧版本写下的计数器不会被读取，会自行过期：升级时每个桶从零重新计数一次。`ReactiveRedisRateLimiter` 不再接收 `clock`。
- **一份文档至多 64 条限流规则**（`DOC-6`）。被放行的请求每匹配一条规则就调用一次限流器，所以超过的文档不再能编译：已存储的这种文档被装载为失败（其请求返回 `503`），控制面以 `422` 拒绝会产生这种文档的变更。迁移方法：把只在计数对象上不同的规则合并为一条带 `key` 的规则。
- **有多个资源模式的语句，含义等于每个模式各一条语句**（`ALG-10`）。条件读取路径变量、且请求匹配其中多个模式时，现在依次用每个匹配模式的捕获值判断条件，任一成立即命中；此前只读第一个匹配模式的捕获值，所以 DENY 可能漏掉它自己的某个模式会拒绝的请求，模式顺序也会改变决策。这样写的 DENY 现在拒绝的不少于以前；ALLOW 可能放行更多。按变量做 key 的限流规则，取第一个使条件成立的模式的值。检查方法：在文档中查找有多个 `resource` 模式且 `condition` 读取 `resource.var.*` 的语句。
- **含空段、编码分隔符或反斜杠的路径答 `400`**（`PIP-14`）。`//admin`、`/admin//x`、`/;x/admin`、`/admin%2Fx`、`/admin\x` 和 `/admin%5Cx` 以前被当作独立的路径决定，所以 `/admin` 之下的 `DENY` 匹配不到它们，而会合并斜杠、解码分隔符或把 `\` 当作 `/` 的后端却按 `/admin/...` 提供服务。末尾的 `/` 不变：`/admin/` 仍是一条独立的路径。以前能得到服务、现在被拒绝的请求：
  - 路径中的 `\`，无论是否编码：客户端本不应发送它，浏览器会把它变成 `/`；
  - 路径变量中编码的 `/`，例如 `/files/a%2Fb.txt` 或 `/repos/group%2Fproject`：把值放进查询参数，或者对其中的 `/` 编码两次（`%252F`），管道解码一次得到文本 `%2F`，由应用再解码一次；
  - 用各自带 `/` 的两段拼接出的 URL（`https://host/api/` + `/orders`）：修正客户端；
  - 最后一段之前单独成段的路径参数（`/;jsessionid=x/orders`）：URL 重写会把它放在最后一段的末尾（`/orders;jsessionid=x`），这种写法仍被接受。

  没有开关可以接受它们：模式无法表达后端会把这些路径当成什么，决定它们就会让绕过重新出现。
- **检查拒绝执行会拒绝的内容**（`PIP-15`）。`AuthorizationChecker.check` 和 `decide` 现在按管道读取请求的方式读取路径：管道答 `400` 的路径是拒绝，超过请求路径长度上限的资源也是拒绝。以前 `/public` 之下的 `ALLOW` 可能放行 `/public/../admin`。
- **影子窗口多统计两种结果**：`BECAME_DENIED` 和 `OBLIGATIONS_CHANGED` 以前被计为 `SAME`，现在发布时必须确认。滚动升级之前请结束或放弃未关闭的窗口：上一版本的实例读不了包含它们的报告。
- **社交登录的 state 只能用一次，并绑定到发起登录的浏览器**（第二轮复审 M4）。JustAuth 只检查 state 是否存在：一个 state 能让任何打开带它的回调的浏览器登录，在 3 分钟内打开几次就登录几次，这使登录 CSRF 成为可能。`SocialAuthentication.authorizeUrl(provider)` 改为 `authorize(provider)`，返回 `SocialAuthorization`（`url`、`binding`）；`SocialAuthenticationProvider` 同样如此。重定向浏览器之前，先在浏览器上设置 `authorization.setCookie(path)`，并把 Cookie 的值传给 `JustAuthCredentials(provider, binding)` 的第二个参数；`SocialCredentials` 增加了 `binding`。回调时 state 从 Redis 中被取走（`<s>:oauth:binding:<state>`，`GETDEL` 需要 Redis 6.2 或更高版本）。`JustAuthProvider` 接收一个 `SocialStateStore`。注册上设置 `ignore-check-state: true` 会让启动失败。
- **令牌和 API key 声明它们的应用**（批次 R7b、RFC 0012 §3.5、`SCP-11`）。令牌的 `aud` 和 API key 的 `app` 指明凭证属于哪个应用，`ScopeResolver` 把它们与宿主的应用比较：配置了应用的宿主（`cosec.authorization.scope.app-id` 或路由的 `cosec-app-id`）拒绝声明其他应用或未声明应用的凭证，返回 `403 scope-rejected`。没有宿主应用时取凭证的应用，两者都没有时不读取应用文档。迁移方法：签发令牌时设置 `TokenSubject.app`，每个应用一个令牌；为 API key 设置 `app`（`ApiKey`、`cosec.api-key.keys[].app`）。存储的 key 升级为带必填 `app` 字段的 format 3：format 2 会失败（`API key format [2] is not 3.`），请重新发放（`cosec-migrate api-keys --app <id>` 会为它从 7.x 转换的 key 设置应用）。操作控制面的令牌发给 `cosec.control.app-id`（默认 `cosec-control`）；宿主应用与之不同的宿主上的控制面路由返回 `403`，请把它们放在没有应用或就是该应用的宿主上。
- **`CoSec-App-Id` 已删除。** 客户端不再声明应用；该请求头不被读取，发送它的请求取凭证的应用。网关转发的身份仍然携带它的应用：配置了应用的服务拒绝声明其他应用或未声明应用的身份，返回 `400 invalid-request`。请读取 `SecurityContext.scope.appId` 获取请求的应用。
- **`SecurityContext` 是只读的**：`tenantId`、`principal`、非空的 `scope` 和 `forwardedIdentity`（`gatewayRequestId`、`credentialId`，取代属性 `cosec.identity.rid` 和 `cosec.identity.jti`）以及 `requestId` 和 `deviceId`（见上）。属性表及其访问方法已删除；`tenant.tenantId` 变为 `tenantId`。
- **`CoSecPrincipal` 是应用读取到的请求信息**：`id`、显式的 `authenticated`、`attributes`。请求生效的角色在 `SecurityContext.scope.roles` 中；`principal.roles` 和 `principal.policies` 已删除。
- **令牌契约都是值。** `TokenVerifier.verify(accessToken: String)` 和 `refresh` 返回 `VerifiedToken`（`me.ahoo.cosec.spi.token`），没有类型参数；`TokenRevoker.revoke` 接收令牌字符串，或一个 `CompositeToken`（见下）。`TokenConverter` 接收 `TokenSubject`（`me.ahoo.cosec.login.token`），`Authentication<C>` 返回它。已删除：包装类 `SimplePrincipal`、`SimpleTenantPrincipal`、`TenantPrincipal`、`TokenPrincipal`、`TokenTenantPrincipal`、`SimpleTokenPrincipal`、`SimpleTokenTenantPrincipal`、`MembershipMarkedPrincipal`、`Delegated`、`findInDelegationChain`、`IssuedAtCapable`、`ExpiresAtCapable`、`MembershipMarkerCapable`、`AccessToken`、`RefreshToken`、`Token` 和 `TokenIdCapable`。自定义的 `TokenVerifier` 改为构造 `VerifiedToken`；它的构造函数拒绝无效声明，抛出的正是 `verify` 本来就会抛出的 `TokenVerificationException`。
- **`RoleCapable`、`PolicyCapable`、`SpacedRoleId`、`RoleIdCapable`、`SpaceIdCapable`、`Tenant`、`TenantCapable`、`SimpleTenant` 和 `Named` 已删除。** 声明名是 `MembershipClaims` 的常量，租户 id 是 `TenantId.DEFAULT` 和 `TenantId.PLATFORM`；`role@space` 语法不变。`SocialAuthenticationProvider` 保留 `name`。
- **`sub` 以 `(` 开头的令牌无效**（`IDN-12`），没有 `sub` 或 `jti` 的令牌也无效：令牌不能使用 CoSec 自己的 id，尤其是匿名主体 `(0)`。
- **`AttributeRequest` 携带 `tenant`**，`Subject.tenantId` 已删除：A 阶段提供者读取 `request.tenant`。
- **`SwitchTenantCredentials` 携带请求的 `context`（`SecurityContext`）**，不再是 `principal`，`switchTenant` 接收它（`AbstractSwitchTenantAuthentication.switchTenant` 现在是 `SwitchTenantAuthentication` 的 `TenantSwitch` 钩子，见上）。新令牌保留原令牌的应用。
- **登录改为挂起函数，`cosec-login` 不再引入 Reactor**（批次 R7c）。`Authentication.authenticate`、`TokenCompositeAuthentication.authenticateAsToken`、`AbstractSwitchTenantAuthentication.switchTenant`（现在是 `SwitchTenantAuthentication` 的 `TenantSwitch` 钩子，见上）、`SocialAuthenticationProvider.authorizeUrl` 和 `authenticate`、`SocialAuthentication.authorizeUrl` 以及 `SocialUserPrincipalConverter.convert` 都是 `suspend` 函数，直接返回值而不是 `Mono`。迁移方法：把重写改成 `suspend` 并返回值；用 `awaitSingle()` 等待 Reactor 客户端；把控制器函数改成 `suspend`。用 `Mono.empty()` 拒绝登录的代码要改为抛出异常，因为不再有“空”的回答。通过 `cosec-login` 编译 Reactor 的模块需要自行声明它。`cosec-social` 不再引入 Guava。
- **`DefaultAuthenticationProvider` 和 `SocialProviderManager` 改为类**，starter 为每个应用上下文各装配一个（#1222）。对旧单例的调用请换成 `AuthenticationProvider` 或 `SocialProviderManager` Bean；`SocialAuthentication` 通过构造函数接收管理器。
- **`JustAuthProvider` 运行在自己的调度器上**，每个提供方最多 16 个线程，社交登录不再需要 `Schedulers.boundedElastic()`。
- **库模块导出其公共类实现的类型**（`cocache`、`kafka`、`ip2region`、`micrometer`、`opentelemetry`）：只为编译它们而添加的依赖可以去掉。

- **公共 API 已归类**（RFC 0010 C4）。应用需要的保持公开；其余的是 `@InternalCoSecApi`（应用永不选择加入）或 `internal`。`cosec-engine` 的大部分、`cosec-model` 的条件 AST、`cosec-runtime` 的快照视图、影子记录与文档模型、控制面的 `ControlPlane`、授权与访问分析，以及 starter 的自动配置类现在都是内部的。如果你编译时依赖其中之一，请改用文档页面描述的方式：检查和监听器都还在。测试工具里只有 `PolicyTestKit`、`PipelineFixture`、`PermittedRequest`、`AttributeRequests`、`EngineOutcome` 及其异常是公共的。
- **`PipelineComponents` 成为普通类**，`SnapshotOptions` 也是；两者都失去了 `copy` 和 `componentN` 函数。`PipelineComponents` 不再接受 `engine`。不使用 Spring Boot 的宿主用 `Pipeline.builder(authenticator, store)` 构建（本条最初写的 `SuspendingSnapshotSource.of(store)` 后来已删除，见上）。
- **`PolicyDocumentCompiler.compile` 成为内部 API。** CI 检查调用 `validate`；`forPlatform(platform)` 是对应的重载。
- **公共 `data class` 现在只是不会增长的值**（RFC 0010 C4）：`Identity`、`Identity.Credential`、`TokenSubject`、`SocialUser`、`ForwardedIdentity`、`Audit*Data`、`ShadowReport`、`ShadowSample`、`InstalledDocument`、`InstanceStatus` 以及 starter 的 `*Properties` 失去了 `copy` 和 `componentN`。`Identity`、`Identity.Credential`、`TokenSubject` 和影子记录仍按值比较；其余的不再定义 `equals`。
- **需要访问令牌的地方拒绝刷新令牌，需要刷新令牌的地方也拒绝访问令牌**（`IDN-13`）。`JwtTokenConverter` 在每个刷新令牌上写 `cosec.token: "refresh"`，带该声明的令牌不会完成认证；刷新只读取带该声明、且 `iat` 与访问令牌相同的令牌。此前签发的访问令牌在过期前继续有效；它们的刷新令牌没有该声明，不能再刷新，其用户需要重新登录。
- **刷新令牌属于会话**（RFC 0009，第二轮复审 M2）。登录会开始一个会话，即一族令牌对，两个令牌都带有它的 id、登录时间和代数（`sid`、`auth_time`、`cosec.generation`），保存在 Redis 的 `<cosec.key-prefix>:token:family:<sid>` 下（[会话](../integration/authentication.md#sessions)）。只有当前一对能刷新；更早的一对再次出现会结束会话；会话从登录起持续 `cosec.jwt.session.lifetime`（30 天）；登出会结束整个会话。对应用的影响：
  - **每个用户需要重新登录一次：** 本版本之前签发的令牌对不带会话，刷新时被拒绝（`token-invalid`）；它的访问令牌在过期前仍可用。
  - **通过 `TokenCompositeAuthentication` 签发令牌**，它会开始会话；或者用 `TokenFamilies.start` 和 `TokenSubject.inSession` 自己开始会话：单独由 `TokenConverter.toToken` 签发的令牌对永远不能刷新。`TokenCompositeAuthentication` 和 `SimpleRefreshTokenAuthentication`（现在是 `RefreshTokenAuthentication`，见上）接收一个 `TokenFamilies`（starter 提供的 Bean）。
  - **客户端只保留最新的一对：** 在更新的一对签发之后出示旧的一对会结束会话，除非发生在那次刷新之后的 `cosec.jwt.session.rotation-leeway`（30 秒）之内：此时它会得到最新一代的一对令牌，因此响应丢失的刷新可以重试。
  - **`TokenRevoker.revoke(compositeToken)`** 用于访问令牌已过期时登出；`revoke(accessToken)` 现在也会结束会话。自定义的 `TokenRevoker` 要实现两者。
  - **切换租户需要会话：** 此前是 `SwitchTenantCredentials.accessToken` 和接收 `TokenVerifier` 的 `AbstractSwitchTenantAuthentication`；现在它从请求的会话签发（`SwitchTenantAuthentication`，见上），新令牌保留登录时间。
  - **不使用 `cosec-cocache` 时，请注册 `TokenFamilyStore`**，否则启动失败（单实例可用 `InMemoryTokenFamilyStore`）。Redis 中每个存活的会话一个 key。
- **无法读取的吊销存储会被如实报告，绝不读成“未吊销”**（第二轮复审 H1，`IDN-14`）。存储无法读取时，`RevocationReader` 抛出 `RevocationUnavailableException`；吊销表的 `unknown-read` 锁定为 `throw`，设置 `cocache.caches.[RevokedTokenCache].unknown-read` 会让启动失败。此时流水线仍像以前一样接受请求的令牌；刷新改为失败而不签发令牌，审批人的吊销无法读取时，控制面不发布任何东西。读取失败时回答“未吊销”的自定义 `RevocationReader` 改为抛出 `RevocationUnavailableException`。若请求也要拒绝，请设置 `cosec.authentication.revocation.fail-closed: true`，它取代了吊销表上的 `unknown-read: throw`。
- **吊销只有一个判断，令牌契约不做阻塞 I/O。** 按契约，`TokenVerifier` 是纯计算。`RevocableTokenVerifier` 已移除：流水线、刷新和登出都通过 `TokenRevocation`（`cosec-runtime`）读取 `RevocationReader` 中的吊销。`TokenStore` 只保留写入 `revoke` 和 `revokeSubject`，二者改为 `suspend`；同步的 `isRevoked`、`subjectRevokedBefore` 已移除（请通过 `RevocationReader` 读取），`TokenStore.NoOp` 也已移除。`TokenRevoker.revoke` 和 `revokeSubject` 改为 `suspend`：从 `suspend` 处理函数中调用即可，无需再移出事件循环。`DefaultTokenRevoker` 接收 `TokenRevocation`（现在是 `me.ahoo.cosec.login.token` 中的 `StoreTokenRevoker`，见上）。`SimpleRefreshTokenAuthentication(tokenVerifier)` 改为 `SimpleRefreshTokenAuthentication(tokenVerifier, tokenRevocation)`（现在是 `RefreshTokenAuthentication`，见上），不再使用自己的调度器。starter 的 `TokenVerifier` Bean 是普通的 `JwtTokenVerifier`，另外提供一个 `TokenRevocation` Bean。自定义的 `TokenStore` 以 `suspend` 函数实现两个写入，读取则作为 `RevocationReader` Bean 提供。
- **实例状态携带文档格式级别**（RFC 0010 C5）：没有 `formatLevel` 的实例状态会被本版本的控制面拒绝，读取到它的影子窗口会失败，直到所有实例升级完毕（控制面只计入最近一分钟内写入的状态，`ShadowReportReader.STATUS_TTL`；key 本身存活两分钟）。

- **控制面拒绝原本到运行时才失败的内容。** 平台变更把被其他文档引用的策略改成边界、或把被引用的边界改成策略，是 `REFERENCES`（`422`、`CTL-30`）。绑定指向平台边界同样是 `REFERENCES`：这样的绑定从未授予任何东西，它适用的每个请求都是 `503`，含有它的文档要先移除它才能再次发布。变更写入的 id 不能含 `/`（`V2`、`422`）：以 `*` 结尾的规则匹配不到这样的条目。权限策略的 `perm:<app>/<id>` 可以含；文档中已有这种 id 的条目可以保留或删除。移除租户所绑定的策略、或移除应用简写形式所派生的策略的应用变更，在检查时就是 `REFERENCES`：以前它能到达 `CHECKED`，直到发布时才失败（`CTL-30`）。
- **`ChangeEvent.Kind` 去掉了 `BOOTSTRAP` 和 `MIGRATE`。** 这两种变更事件从未被发出过：bootstrap 和迁移通过存储写入修订，记录在修订的
  `provenance.kind`（`BOOTSTRAP`、`MIGRATE`）里。对 `ChangeEvent.Kind` 做 `when` 且列出了它们的代码不再能编译；删掉这些分支即可，
  它们从未收到过任何东西。
- **应用声明了 `CorsConfigurationSource` bean 时，预检请求不再被策略、限流或审计看到**（`STA-5`，[CORS](../integration/web.md#cors)）。应用声明了所在栈的
  `CorsConfigurationSource`、且没有自己的 `CorsFilter` 或 `CorsWebFilter` 时，启动器现在会在 Spring MVC 和 WebFlux 上于 CoSec 之前注册一个 CORS 过滤器：
  预检请求仅凭 source 由它回答，所以针对 `http:OPTIONS` 的语句、限流、黑名单、审计、判定事件和指标都看不到预检请求；source 不允许的 origin 在管道之前得到一个普通的
  `403`，没有 problem 体和 `CoSec-Request-Id`；拒绝响应现在带 CORS 头。`@CrossOrigin` 和 `addCorsMappings` 不再回答预检请求：把每个 origin、方法和请求头
  都写进这个 bean。只为让预检请求通过而写的语句（`http:OPTIONS`）不再需要，留着也无妨。没有这个 bean、或有自己的过滤器的应用不受影响。
- **从平台或应用文档删除 DENY 需要第二个人**（RFC 0007 §7.4.4、`CTL-31`）。此前，没有 `cosec:approve` 的平台主体可以独自发布只删除条目的变更。现在，拿掉 DENY 的删除（含 DENY 的策略，或所绑策略含 DENY 的绑定，也包括经删除角色或以更少权限 `putRole` 去掉的）会停在 `AWAITING_APPROVAL`，每项带一个 `NOT_ANALYZED` 升级；开启 `four-eyes` 时信任根的这类变更也是如此（[审批](../policies/testing.md#approval)）。删除 ALLOW、租户删除自己的 DENY 以及撤销 break-glass 不变。请为紧急删除平台 DENY 安排好审批人。
- **`cosec:grant` 看角色实际派生了什么**（RFC 0012 §7.2）：让 `"*"` 角色派生出新绑定的 `putCatalog` 或 `putPolicy`，与 `putRole` 一样需要该角色作用域上的 `cosec:grant`。
- **break-glass 只需要 `cosec:breakglass`**（RFC 0007 §8）：申请、批准后发布以及撤销都不再额外需要 `cosec:publish`。其他变更不能用 `cosec:breakglass` 代替。
- **回滚可以带 `confirmations`**，恢复带 `onMissing: "nomatch"` 的 DENY 时与提议相同。此前这样的回滚根本无法确认。
- **`repair-references` 也会删除**未能写入头的发布登记的条目，并输出 `Removed <n> reference(s) no head refers to.`。见[修复反向引用索引](./upgrading.md#repair-the-back-reference-index)。

- **`cosec-model`、`cosec-engine` 和 `cosec-spi` 的公开 API 已按 8.0 归类**（R9、RFC 0010 C4）：仍然公开的，就是 8.0 保持稳定的。应用会注意到：
  - `ApiKeyStore` 已删除。请求通过 `ApiKeyReader` 读取 key。管理 key 请用 `InMemoryApiKeyStore` 或 `RedisApiKeyStore` 这个 Bean，它们的方法不变；按 Bean 自己的类型注入。流水线从不读取应用自己声明的 `ApiKeyStore` Bean：要从别处查找 key，请声明 `ApiKeyReader`。
  - 评估轨迹换了包，并且是实验性的。`me.ahoo.cosec.engine.flat.FlatTrace` 改为 `me.ahoo.cosec.engine.trace.EvaluationTrace`，`CandidateTrace` 和 `CandidateOutcome` 在同一个包中。这些类型和 `DecisionEvent.evaluationTrace` 是 `@ExperimentalCoSecApi`（用 `@OptIn(ExperimentalCoSecApi::class)` 选择使用），构造器是内部的。`ViewVersion` 和 `AttributeStatus` 在 `me.ahoo.cosec.engine` 中，保持稳定。审计记录及其 JSON 不变。
  - `RevocationUnavailableException` 继承 `CoSecException`，与 `MembershipUnavailableException` 一样。把所有 `CoSecException`（一种 `SecurityException`）都答成 `401` 的处理器现在也会捕获它：请先处理它，它表示吊销存储不可用。
  - `RateLimitResult` 不再是 `data class`：没有了 `copy` 和 `componentN`，相等性不变。
  - `SnapshotDocument.DOCUMENT_SCHEMA` 不再是 `const`，已编译的代码读取的是运行时 CoSec 的 schema。用它初始化的 `const val` 不再能编译：改为 `val`。
  - **`SnapshotDocument` 由它的字节构造**（`SNP-12`）：构造函数现在是内部的。把 `SnapshotDocument(schema, revision, publishedAt, bytes)` 换成 `SnapshotDocuments.read(bytes)`（`cosec-document`），它从字节中读取头部，并拒绝不是文档的字节。存储的字节不变。
  - 以下现在是内部 API（`@InternalCoSecApi`）：`Environment`、条件字面量（`Literal`）、`Attribute` 及其 `Kind`、`ScopeResolver`、`ScopeSubject`、`SubjectRevocation`、`IdentityFormatException`、`IdentityOverflowException` 和 `Identifiers.isUnpairedSurrogateAt`。`ScopeResolution.Resolved` 和 `ScopeResolution.Rejected` 的构造器是内部的；`ScopeResolution.Rejected.Reason` 保留。

- **`cosec-runtime` 和 Web 适配器的公开 API 已按 8.0 归类**（R9）。使用 starter 时运行时没有变化；在代码中：
  - 不使用 Spring 组装管道是实验性的：`Pipeline.builder` 和 `PipelineBuilder`、`Pipeline.evaluate`、`RequestGate`、`HttpFacts`、`Headers`、`Cookies`、`Outcome`、`Problem`、`ProblemEncoder` 和 `RequestSecurity` 是 `@ExperimentalCoSecApi`：用 `@OptIn(ExperimentalCoSecApi::class)` 选择使用。`PipelineOptions`、`SnapshotOptions`、`SuspendingSnapshotSource`、`AttributeProviders`、`DecisionListeners`、`IdentityIssuer`、`IdentityGate` 和 `CoSecParameters.read` 是 `@InternalCoSecApi`（不能选择使用），`Pipeline` 的构造器和 `PipelineComponents` 是 `internal`；它们起初是实验性的，后来由 `Pipeline.builder` 取代。用这种方式构建管道的测试改从 `PipelineFixture`（`cosec-testkit`）获得管道。`Pipeline` 本身、`AuthorizationChecker(pipeline)`、`ProblemType` 和 `CoSecParameters` 的请求头名保持稳定。
  - 以下现在是内部 API（`@InternalCoSecApi`）：`IdentityCodec`（契约是 [Web 集成](../integration/web.md)中的 `CoSec-Identity` 格式）、`RemoteIpResolver`、`PathNormalizer`、`HttpAttributes`、`PolicyDocument.attributes`、`PolicyDocumentCodec.encode`、`ATTRIBUTE_TYPES`、`WILDCARD` 和 `SCHEMA`、`PolicyDocumentCompiler` 的上限、`PERMISSION_PREFIX` 和 `permissionPrefix`，以及接收 `RequestSecurity` 的 `AuthorizationChecker.check` 和 `decide`（接收 `SecurityContext` 与字符串的保留）。`PipelineWebFilter`、`PipelineServletFilter` 和 `PipelineGatewayFilter` 的构造器（使用 starter 的 Bean，不要自己声明）、`LoggingAuditEventSink(KLogger)`（无参构造器保留）和 `MetricsListener.bind(registry, listeners)` 也是。`RouteResolution.Mapped` 和 `Invalid` 的构造器是内部的：由 `RouteMap.resolve` 给出。
  - `InstanceStatus.FORMAT_LEVEL` 不再是 `const`。

- **`cosec-control` 和 `cosec-cocache` 的公开 API 已按 8.0 归类**（R9）：
  - `ControlStore`、`InMemoryControlStore`、`CoCacheControlStore` 和存储保存的记录（`ChangeRequest`、`ChangeRequestCodec`、`ChangeState`、`Base`、`Approval`、`CheckedApps`、`Finding`、`Severity`、`Operation`、`ShadowState`、`StageSlot`、`AppList`、`PendingRecord`、`RevisionEntry`、`RevisionIndex`、`StoredDocument`、`Versioned`、`HeadWrite`），连同影子窗口端口及其报告（`CandidateStore`、`ShadowReportSink`、`ShadowReportReader`、`CoCacheShadowReports`、`InstanceStatus`、`InstalledDocument`、`ShadowReport`、`ShadowSample`、`StoredCandidate`、`ShadowOutcome`）和 `ProvenanceKind`，是 `@ExperimentalCoSecApi`：自己实现的存储需要选择使用。`ChangeListener`、`ChangeEvent` 及其携带的内容保持稳定；`Escalation` 和 `Confirmation` 不再是 `data class`（没有 `copy` 和 `componentN`，相等性不变）。
  - CoCache 缓存（`RevokedTokenCache`、`RevokedSubjectCache`、`MembershipCache`、`PolicySnapshotCache`）是 `@InternalCoSecApi`，`cosec-cocache` 中接收 CoCache 或 Spring Data 类型的构造器和工厂（`CoCacheTokenStore`、`CoCacheMembershipProvider`、`CoCacheTokenFamilyStore`、`RedisApiKeyReader`、`RedisApiKeyStore`、`ReactiveRedisRateLimiter`、`RedisSnapshotDocumentStore` 及其摘要辅助函数、`CoCacheControlStore.create`、`CoCacheShadowReports.create`）以及 `DEFAULT_*_KEY_PREFIX` 常量也是。这些缓存只能由本来就是内部的 `CoSecCacheSpecs` 构建：starter 构建它们，应用按 Bean 的类型（`RedisApiKeyStore`、`CoCacheMembershipProvider` 等）使用。`cocache.caches.[<CacheName>]` 中的缓存名不变。
- **`LocalRateLimiter` 保留每一个仍可能拒绝请求的计数，满了就无法计数**（`OBL-5`、`OBL-6`）。它曾丢弃空闲一小时的桶，于是窗口更长（最长一天）的规则在客户端停顿一小时后又放它进来；它也曾按容量逐出桶，于是某条规则上大量新键会重置所有规则的计数。现在桶只有在空闲满一小时、且空闲满其规则的两个窗口后才会被移除，且从不为腾出空间而移除。限流器总共最多 100,000 个桶，单条规则最多 50,000 个；超出任一上限的新桶无法计数，由规则的 `strictFailure` 决定：严格规则返回 `503`（`RateLimiterUnavailable`），宽松规则被跳过。以前它会从零开始计数。如果一条以客户端可以变化的内容为键的规则现在返回 `503`，请改用主体或可信地址作为键（[本地限流器有上限](../policies/rate-limiting.md#the-local-limiter-is-bounded)）。`LocalRateLimiter` 的构造器多了第二个参数 `maximumBucketsPerRule`，位于 `expireAfterAccess` 和 `clock` 之前：按名传参的调用不受影响，按位置传参的需要修改。`cosec-runtime` 不再引入 Guava：经由 CoSec 使用它的应用需要自己声明。

- **starter、`cosec-login`、`cosec-social` 和 Web 适配器的公开 API 已按 8.0 归类**（R9）：
  - **`cosec-login` 的 `Credentials` 改为 `LoginCredentials`，`Authentication.supportCredentials` 改为 `credentialsType`。** 一个产品里有两个公开的 `Credentials`，指的却是不同的东西：管道的那个（`me.ahoo.cosec.spi.authn.Credentials`，请求的令牌或 API key）保留原名。你自己的 `Authentication` 这样改：

    ```kotlin
    // 之前
    data class PasswordCredentials(val username: String, val password: String) : Credentials
    class PasswordAuthentication : Authentication<PasswordCredentials> {
        override val supportCredentials = PasswordCredentials::class.java
        // ...
    }
    // 之后
    data class PasswordCredentials(val username: String, val password: String) : LoginCredentials
    class PasswordAuthentication : Authentication<PasswordCredentials> {
        override val credentialsType = PasswordCredentials::class.java
        // ...
    }
    ```

  - `AbstractRefreshTokenAuthentication` 已删除：`SimpleRefreshTokenAuthentication`（现在是 `RefreshTokenAuthentication`，见上）自己实现 `Authentication<RefreshTokenCredentials>`。`AuthenticationProvider`、`DefaultAuthenticationProvider` 和 `CompositeAuthentication` 的构造器是 `@InternalCoSecApi`：starter 注册每个 `Authentication` Bean。
  - `cosec-social`：`SocialProviderManager` 和 `SocialAuthenticationProvider` 是 `@InternalCoSecApi`，`SocialAuthentication` 和 `JustAuthProvider` 的构造器（后者用到 JustAuth 的 `AuthRequest`）也是。`JustAuthProvider.name()` 已删除：读取 `name`。
  - starter 的 `*Properties` 类和 `EnabledCapable` 是 `@InternalCoSecApi`，`@ConditionalOn*` 注解是 `internal`。契约是 `cosec.*` 配置键，它们不变。读取配置值请用 `@Value("\${cosec.jwt.enabled}")` 或 `Environment`，不要注入 CoSec 的 `*Properties` Bean；给你自己的 Bean 加条件请用 `@ConditionalOnProperty("cosec.jwt.enabled")`，不要用 `@ConditionalOnJwtEnabled`。
  - `checkMono`（`cosec-webflux`）和 `checkBlocking`（`cosec-webmvc`）位于名为 `AuthorizationCheckers` 的 Java 类中，不再是 `PipelineWebFilterKt` 和 `PipelineServletFilterKt`；Kotlin 调用方不受影响。`SuspendingHandlerHolderInterceptor` 是 `@InternalCoSecApi`：starter 会注册它。
- **`cosec-dependencies` 不再管理 Guava 的版本**，CoSec 的模块也都不再引入 Guava。从 CoSec 的 BOM 取 Guava 版本的应用需要自己声明。

- **`ChangeState.SHADOWED` 已删除。** 没有任何变更处于过这个状态：影子窗口关闭不是一个状态，而是变更的 `shadow`（`ShadowState.closed`）。点名 `ChangeState.SHADOWED` 的代码（`when`、按状态的过滤、测试）不再编译：删掉该分支。存储的变更请求不可能带它，`"state":"SHADOWED"` 也无法解码；没有任何版本写过。现有状态为 `DRAFT`、`CHECKED`、`SHADOWING`、`AWAITING_APPROVAL`、`READY`、`CONFLICTED`、`PUBLISHED`、`REJECTED`、`ABANDONED` 和 `EXPIRED`。变更事件的 `SHADOWED`（"窗口已关闭"）保留，其日志行也保留。
- **一个文档族最多持有其上限加上明确的余量**（`SNP-14`）。冷加载突发时，一个族可能持有约 `cosec.authorization.snapshot.max-apps` 或 `max-tenants` 再加上后台线程排队的 4,096 个驱逐。现在它最多持有上限的两倍，且绝不超出上限 4,096 个以上，另加此刻正在加载的文档；安装时发现族超出这个数的，在安装线程上先驱逐再应答。无需配置，没有默认值改变；按旧的最坏情况做的内存预算依然安全。[这个属性](./configuration.md#snapshot)写明了这个数。
- **`Retry-After` 与 `RateLimit-Reset` 向上取整，低于一毫秒的上报间隔使启动失败。** 窗口还剩 1.75 秒时，`429` 曾答 `1`，按此等待的客户端会撞上同一个窗口；两个头现在都是剩余时间的整秒数，向上取整，至少为 `1`（`1 ms` 与 `1000 ms` 为 `1`，`1001 ms` 为 `2`）。无需处理：客户端最多多等一秒，`RateLimit-Remaining` 和 `-Limit` 不变。starter 现在拒绝低于 `1ms` 的 `cosec.authorization.shadow.report-interval`（`cosec.authorization.shadow.report-interval is at least one millisecond, not …`），此前它会启动一个永不挂起的上报循环：请设为 `1ms` 或更大（默认 `10s`）。
- **此前的 `cosec-migrate bootstrap` 丢弃平台租户的角色。** 它只用策略、绑定和限流规则重建平台租户的文档（`tenant:(platform)`），所以它发布的 `BOOTSTRAP` 修订没有 `roles`。把 7.x 角色转换进该文档、再运行 `bootstrap`（带或不带 `--force`）的迁移丢掉了它们，这些角色的成员随后被拒绝：失败即拒绝，绝不放开。现在它保留文档的其余全部内容。如果你在转换角色之后运行过更早的 `bootstrap`，请以管理员身份用控制面 API 检查该文档：找到 `provenance.kind` 为 `BOOTSTRAP` 的修订（`GET /documents/tenant:(platform)/revisions`，再 `GET …/revisions/<n>`），然后读 `GET /documents/tenant:(platform)/diff?from=<n-1>&to=<n>`：`roles.removed` 非空，就是 bootstrap 丢掉的角色。用一次变更把它们放回去（`putRole` 操作，定义取自修订 `<n-1>`）。先 bootstrap 后转换角色、或平台租户没有角色的部署，无需处理。
- **`ControlStore` 新增 `stagedCandidate`，`putCandidate` 按摘要写入。** 候选只会覆盖写入者读到的那一份，所以被抢占的变更迟到的写入不会替换占了槽位的那个变更的候选（#1235）。`suspend fun stagedCandidate(key: SnapshotKey): Versioned<StoredCandidate>?` 是新增的；`putCandidate(key, expected: SnapshotDigest?, candidate: StoredCandidate): Boolean` 接收它期望的摘要（`null`：仅当还没有候选时写入），期间候选变了就答 `false`。自己的 `ControlStore` 要实现这两个（`CoCacheControlStore` 与 `InMemoryControlStore` 都实现了）；写入结果未知时回读，而不是再写一次。使用 starter 的 Redis 存储无需处理。
- **`cosec-migrate` 命令行更严格，并接受 `--name=value`。** `--name value` 与 `--name=value` 都可以；以 `--` 开头的值是用法错误（此前下一个选项会被当作值，它自己的值被丢掉）：这样的值请写 `--name=--value`。重复给出的开关是 `--flag is given twice.`，给开关加值是 `--flag takes no value.`，重复或未知的选项是用法错误。依赖某个选项吞掉下一个参数的脚本现在会失败：请修正它的参数。
- **控制面 API 的路径参数只做一次百分号解码。** `GET /documents/tenant:acme%20west` 曾读取键 `tenant:acme%20west`，它不是存储和请求体使用的 `tenant:acme west` 那份文档；现在先把路径拆成段，再对每个参数解码一次（`+` 仍是 `+`，编码的 `/` 是值的一部分、不会成为第二段，`tenant%3Aacme` 即 `tenant:acme`，路由自己的词按原样匹配）。对 id 编码了两次、或发送编码后的 id 并指望它原样到达控制面的客户端，现在指向另一份文档：只编码一次。
- **访问审查列出破窗绑定。** `POST /analyze` 曾把破窗绑定（它的策略 `cosec:root` 不由任何文档定义）算作 `opaque`。现在它是 `allows` 中的一项，来自引擎自己的语句的 `cosec:root` 的 allow，带 `breakGlass: true`、`expiresAt` 和 `conditional: true`（审查不求值窗口）；`conditional` 现在也标记任何带窗口的绑定。解析审查结果的客户端会看到 `opaque` 减少，平台租户的审查里多出 `cosec:root` 的 `allows`；把每个 allow 都当作永久的工具应读取 `conditional` 与 `expiresAt`。
- **生成的路由映射按操作取服务器前缀，并拒绝它放不下的内容。** 前缀取自操作自己的 `servers`，没有则取其路径项的，再没有则取文档的；服务器 URL 按组成部分读取：`//example.test/v1`、`https://example.test:8443/v1` 和 `/v1` 都给出 `/v1`。此前能生成路由映射的文档，现在可能被拒绝，并点名操作（`GET /orders: …`）：一个操作的服务器路径不同、路径中有服务器变量、服务器路径不以 `/` 开头（`v1`、`example.test:8080/v1`、`{baseUrl}/v1`：它相对于文档被获取的位置，生成器不知道）或含点段。请重新生成路由映射；给被拒绝的文档写绝对的服务器 URL，或以 `/` 开头的路径。
- **存储的、无法编译的文档会被点名、记录和计数。** `cosec-model` 中的 `Cause` 新增 `DocumentInvalid(document, revision, error)`，所以对 `Cause` 做穷尽 `when` 的应用不再编译：补上分支。决策仍是 `503`（实例无法决策）；`SourceUnavailable` 现在表示存储无法读取。实例对每个修订、摘要和输入记录一行 `ERROR`，重新可编译时记录一行 `INFO`；`cosecSnapshot` 健康指示器给出 `invalid`（无法编译的文档数）和 `invalidFirst`（前十个及其存储的修订）（[快照健康状况](./observability.md#snapshot-health)）。除 `when` 外无需处理；原先靠"没有日志"发现这种情况的，请改为对这行日志告警。
- **请求不再记录被丢弃的决策事件。** BEST_EFFORT 监听器的队列已满或已关闭时，请求只计数；一个上报协程在请求线程之外，对每个监听器每个间隔记录一行 `WARN`（`Dropping decision events: the queue of listener [<name>] is full; <n> dropped since the last report.`，或 `is closed`）。原先每个被丢弃的事件一行的日志告警，现在每个间隔只看到一个计数。
- **`TraceContext` 新增 `traceState`。** 父 span 的 W3C `tracestate`，不透明，没有则为 `""`；三个参数的构造器保留。`cosec.authorize` span 继承它，此前从默认状态开始。它随事件到达每个 `DecisionListener`，是厂商数据：不要记录它。审计记录、Kafka 记录和指标标签都不带它。
- **`cosec-testkit` 的签名增加。** `PermittedRequest.withToken(token, verifier, revocations, headers)` 接收请求头（默认空）；携带凭证的头（`Authorization`、`X-API-Key`、`CoSec-Identity`）被拒绝，两个不同的 `CoSec-Device-Id` 值会像管道那样使请求失败。`PipelineFixture.of` 与 `load` 接收 `routes` 和 `listeners`（默认没有），`AttributeRequests` 为提供者的测试构造 `AttributeRequest`。测试工具的异常带原因，并拒绝它读取内容中重复的字段，以及两个存放同一份文档的文件。源码兼容（有默认值），二进制不兼容：请针对这个版本重新编译测试。

## 从 8.0.0-alpha.3 升级 {#from-8-0-0-alpha-3}

8.0.0-alpha.3 是预览版，没有兼容性承诺。它保存的数据仍然可读，只有反向引用集合、已批准的变更以及用其他拼法写星期的文档例外（见下）。

- **控制面规模**（批次 E2b：#1214、#1217、#1218、#1219，以及 #1290 留下的暂存窗口）。实例把自己的状态写在自己的 key
  `<s>:shadow:status:<instance>` 下，并登记在 `<s>:shadow:instances:all` 中，所以一次心跳只写一个状态，与实例数无关；旧的
  `<s>:shadow:status:instances` 不再被读取，两分钟后过期。实例升到这个版本之前，读取某个修订的传播情况时不会计入它（只读；
  发布从不等它）：请一起升级数据面实例，并把 `~<s>:shadow:instances:*` 加入实例 Redis ACL 的 key 模式
  （[Redis key](./storage.md#redis-keys)）。同一个变更的所有决定基于对控制面文档的一次确认，结束时再确认一次，大变更不再为每个条目
  读两次。代码方面：自己实现的 `ControlStore` 从 `openChanges()` 返回未结束的变更请求（`List<ChangeRequest>`，每个只读一次），并把
  `putCandidate(key, candidate)` 换成 `stagedCandidate(key)` 与 `putCandidate(key, expected, candidate): Boolean`，即在读到的候选之上写入
  （[扩展](../architecture/extending.md#control-store)）。`CoCacheShadowReports` 的第四个存储是实例列表。`SnapshotDigest.toString()`
  和条目摘要不变，只是更快。
- **审计记录说明是什么做出了决定**（批次 R7a，#1237）。`authorization.source` 现在是 `{ via, document, policy, statement }`
  （[审计事件](./observability.md#audit-events)）：旧的 `type` 把租户的角色绑定和边界都记成 `GLOBAL_POLICY`。边界拒绝过去的来源是
  `NONE`、没有 `binding`，现在 `via` 为 `BOUNDARY`，`policy` 是该边界，`binding` 为 `boundary:<tenantId>`。审计日志和 Kafka topic
  的消费方随之修改：

  | 之前 | 现在 |
  |---|---|
  | `source.type` `BLACKLIST` | `source.via` `BUILTIN` |
  | `source.type` `ROOT` | `source.via` `BINDING`、`source.policy` `cosec:root`（break-glass 放行还带 `breakGlass: true`） |
  | `source.type` `ROLE_PERMISSION` | `source.via` `BINDING`、`source.document` `APP` |
  | `source.type` `PRINCIPAL_POLICY` | `source.via` `ATTACHMENT` |
  | `source.type` `GLOBAL_POLICY` | `source.via` `BINDING`、`source.document` `PLATFORM` 或 `TENANT` |
  | `source.policy.id` | `source.policy` |
  | `source.policy.statement` | `source.statement` |
  | `source.policy.type`、`source.role`、`principal.policies`、`request.device.userAgent` | 已删除：`type` 只是重复 `source.type`，其余的总是空的 |

  代码层面：`AuditSourceType`、`AuditPolicy`、`AuditRole`、`AuditPermission` 及其数据类、`PolicyType` 和
  `AuditReasonCode.REGEX_TIMEOUT` 已删除，新增 `AuditVia` 和 `AuditDocument`，没有人读取的属性键
  `AuditTrace.TRACE_ID_ATTRIBUTE_KEY` 和 `SPAN_ID_ATTRIBUTE_KEY` 也已移除。
- **关闭时等待 Kafka 审计**（批次 R7a，#1250）。`KafkaAuditListener` 最多等待 `cosec.observability.listener.drain-timeout`，
  让它手中的发送和 broker 的应答完成，之后才关闭 producer。一次关闭可能用掉两倍这段时间（一次给监听器的队列，一次给这里）：
  请检查 pod 的终止宽限期。
- **`authorization` cookie 只在明确开启时读取**（批次 R6、`PIP-13`）。唯一凭证是这个 cookie 的请求现在是匿名请求。浏览器会自动带上 cookie，跨站请求也不例外，默认读取它等于让每个部署都要应对 CSRF。把令牌放在 `Authorization` 请求头中，或者设置 `cosec.pipeline.allow-cookie-token=true` 并自行防御 CSRF（[认证](../integration/authentication.md)）。无论是否读取，网关都会从转发的请求中移除这个 cookie。
- **`CoSec-App-Id`、`CoSec-Space-Id` 和 `CoSec-Device-Id` 只读请求头**（批次 R6、`SCP-4`）。同名查询参数不再被读取：它不设置任何值，也不再让请求产生歧义。把它们放在 URL 中的客户端改为发送请求头（[应用与空间](../integration/web.md#app-and-space)）。
- **未知的 `cosec.*` key 总是让启动失败**（批次 R6、`SNP-9`）。`cosec.unknown-properties` 和 `UnknownPropertiesMode` 已删除，设置这个配置项本身现在就是未知 key：请移除它。错误信息列出这些 key，并链接到[已删除和改名的 key 对照表](./upgrading.md#removed-and-renamed-properties)，不再逐个给出替代项。
- **没有 `format` 的已存储 API key 和其他错误格式一样失败**（批次 R6）：消息是 `API key format [null] is not 2.`。这样的 key 原本就被拒绝；`cosec-migrate api-keys` 仍然可以转换它。
- **角色和简写权限**（RFC 0012）。目录条目可以用 `action` 和 `resource` 定义权限，应用和租户文档可以有 `roles`（[角色权限](../policies/role-permissions.md#roles)）。`schema` 仍是 2，已发布内容的含义不变。旧实例读不了带这些字段的文档：严格读取器拒绝未知字段，按失败关闭处理。**先升级全部数据面实例，再发布这样的文档**。变更不能再写入 id 以 `role:` 开头的策略或绑定，已有的仍可读。`GET /documents/{key}/diff` 多了 `roles`，策略和绑定按展开后的文档报告。`POST /analyze` 不再把租户对可读应用权限的绑定算作 opaque。代码方面：`Operation` 增加 `PutRole` 和 `RemoveRole`，`DocumentDiff`、`CatalogPermission` 和 `PolicyDocument` 增加了字段。
- **控制面的正确性**（审计批次 E2a：#1205、#1206、#1207、#1210、#1226、#1227）。修订 diff 的 `sections` 按内容比较目录、限制、属性、边界和限流规则：同 id 的规则改了额度也算变化。`POST /analyze` 只列出能在所查询租户生效的绑定，所以平台中给别的租户的主体或角色的绑定不再列出。候选撤回、最后一次报告写入之后，实例忘掉这个影子窗口。代码方面：自己实现的 `ControlStore` 要实现 `withdrawCandidate(key, window)`（`putCandidate` 只接受候选，不再接受 `null`）、带每个引用方修订的 `referrers` 与 `putReferrers`（`Map<SnapshotKey, Long>`），并保存 `RevisionIndex.reclaiming`；`releaseLease` 必须在一步中检查并删除（[扩展](../architecture/extending.md#control-store)）。`ShadowRecorder.record` 的第一个参数是窗口的文档，`forget` 接受它最后一次报告计入的样本数，`ShadowReporter` 接受它读取已结束窗口所用的 `CandidateStore`。
- **控制面实例一起升级，并重建反向引用集合**（批次 R4）。反向引用集合（`<p>:control:refs:*`）只能按对象形式读取：每个引用方及其修订。alpha.3 写入的文档 key 的 JSON 数组是格式错误：涉及它的发布会失败，而 `cosec-migrate repair-references` 写之前要先读集合，所以也越不过它。先停掉 alpha.3 的控制面实例，删除这些 key，运行这个版本的 `cosec-migrate repair-references`（它按 head 重新写入每个集合，带上每个引用方的修订），然后才启动这个版本的控制面实例。alpha.3 的控制面实例读不了这样写入的集合，也读不了列有待删记录的修订索引，遇到它们即按失败关闭处理：它涉及这些集合的发布会失败，对该文档的回收会停止。数据面实例不必按顺序：alpha.3 的实例只是会一直写已结束影子窗口的报告。
- **存储的批准与确认只有一种形状**（批次 R4）。存储的批准总是带 `memberships`、`attributes`、`principalBindings`、`breakGlass`、`credentialId` 和 `issuedAt`，没有时为 `null`，缺少这些字段就是格式错误。升级前请发布或放弃每个带批准的未结束变更，或者在升级后移除它的记录（`<p>:control:change:<id>`）：只要这样的变更还开着，它就回答 `503`，每一轮维护都会停下，什么都不回收。`deny-nomatch` 确认的摘要总是基于 `{statement, policyCondition}` 计算，所以升级前对没有条件的策略给出的确认不再匹配：对应的变更会被拒绝，直到重新确认该语句（`428`）。已发布的文档保留它的确认。
- **星期只有一种拼法**（批次 R5）。`daysOfWeek` 只接受 `MON`、`TUE`、`WED`、`THU`、`FRI`、`SAT` 和 `SUN`，这也是 schema 和[策略语言](../policies/language.md)一直列出的名称。`MONDAY`、`mon` 和 ` Fri ` 不再能编译：写入它们的变更被拒绝，保存了它们的文档被安装为失败，读取它的请求回答 `503`。升级前请把这些文档改用三字母名称发布。`cosec-migrate` 现在写三字母名称，并且仍然读取 7.x 接受的任何拼法。
- **`tenantId` 声明为空时令牌无效**（批次 R5、`IDN-11`），过去它表示默认租户 `(0)`：读作没有租户，会把主体连同令牌的角色放进默认租户。请求按匿名评估，拒绝时回答 `401 token-invalid`，刷新这样的令牌也会失败。没有 `tenantId` 声明的令牌仍属于默认租户。自定义 `TokenVerifier` 的主体遵守同一规则；主体的租户 id 为空时，`JwtTokenConverter` 抛出 `IllegalArgumentException`：请不给它租户，或者用 `SimpleTenant.DEFAULT`。
- **限流计数器只有一种形式**（批次 R5）。每条规则按它的文档和 id 计数：平台规则从原来的裸 id 改为 `platform/<id>`，应用和租户规则不变（`app:<appId>/<id>`、`tenant:<tenantId>/<id>`）。按 key 计数的桶是 `k:<value>`，不再是 `g:<value>`。文档中的规则 id 不变。Redis 计数器重新开始，所以每条规则的当前窗口从完整额度开始，两个版本同时服务时各自计数。你自己的 `RateLimiter`，以及读取 `Cause.RateLimiterUnavailable.ruleId` 或 `Basis.RateLimited.ruleId` 的代码，会看到新的名称。

- **三组配置项改名。** 旧 key 会作为未知配置项使启动失败：

  | 旧 key | 新 key |
  |---|---|
  | `cosec.pipeline.gateway.max-age`、`cosec.pipeline.gateway.max-size` | `cosec.pipeline.identity.max-age`、`cosec.pipeline.identity.max-size` |
  | `cosec.audit.required.timeout` | `cosec.observability.listener.required-timeout` |
  | `cosec.authorization.cache.token.*` | `cosec.authentication.revocation.cache.*` |

- **路由映射改为显式启用。** `cosec.authorization.routes.location` 默认未设置，未设置的宿主不加载路由映射，所以没有映射的 WebFlux 或 Spring MVC 服务无需任何配置。依赖默认读取 `classpath:cosec-routes.json` 的服务会启动失败，报错说明修法：把配置项设为该位置。没有路由的映射不再能加载（[路由映射](../integration/web.md#route-maps)）。
- **`cosec.pipeline.mode=gateway` 时 `cosec.jwt.enabled` 默认为 `false`。** 网关之后也签发令牌的服务要设为 `true`；否则注入 `TokenConverter` 的地方找不到它。`enforce` 模式不变。
- **`cosec:tenant-admin` 也持有 `cosec:skip-shadow`，且只在自己租户的文档上**（`CTL-27`），这扩大了租户管理员能做的事：它可以带着记录的原因跳过窗口，只收窄的变更也能抢占该文档上被占用的槽位。授出分析和审批不变。要保持原来的行为，改为绑定你自己的策略（[控制面](../policies/testing.md#the-control-plane)）。
- **有 `cosec-cocache` 而没有 `CacheRuntime` 时启动失败**，并点名 `me.ahoo.cocache:cocache-spring-boot-starter`。这种组合本来就不能工作：结果是误导性的报错，或永远不就绪的实例。
- **`cosecServiceGenerator` 端点读取 springdoc 的扫描结果**，需要 `springdoc-openapi-starter-webflux-api` 或 `-webmvc-api`。只有一个 `OpenAPI` bean 不再能让它生成；扫描不到任何操作时报错，而不是返回空文档。
- **`AuthorizationChecker.decideAs` 和 `decide(security, app, action, resource)` 改为 `@InternalCoSecApi`**：它们属于控制面，应用不要启用。按请求自身的主体检查，用 `check` 和 `decide`（[代码中的检查](../integration/checks.md)）。

## 从 8.0.0-alpha.2 升级 {#from-8-0-0-alpha-2}

8.0.0-alpha.2 是预览版，没有兼容性承诺。此后来自 [RC 安全评审](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/security/8.0-rc-review.md)的以下变更，让授权分析按执行时的方式读取作者的权限，因此一些过去可以单独发布的变更现在要等待审批（[策略测试](../policies/testing.md#approval)）。文档的内容无需迁移，不过 8.0.0 经 CoCache 8 保存数据，而 CoCache 8 读不了 alpha.2 经 CoCache 6 写入的内容：请在所有实例停止后用 8.0.0-alpha.4 的 `cosec-migrate cocache8` 迁移，它是最后一个带有这条命令的版本（[经 CoCache 6 写入的数据](#data-written-through-cocache-6)）。从 **身份转发** 开始的那些变更涉及网关与租户隔离的边缘（RC1-5、RC1-6、RC1-15 至 RC1-18、RC1-48、RC1-49），改变网关转发什么、转发给哪些路由：升级网关之前请先阅读。

- **从 8.0.0-alpha.2 升级的存储，运行一次 [`cosec-migrate repair-references`](./upgrading.md#repair-the-back-reference-index)**，让 app 文档列表（`<s>:control:list:documents`）完整（[存储](./storage.md#publishing-through-the-control-plane)）。
- **任意类型的 token 属性**（RC1-2、`GRT-5`）。分析按引擎的方式读取 `subject.attributes.*`，布尔值和数字读作它们的文本。token 中写着 `"suspended": true` 时，条件为未被 `suspended` 的 ALLOW 不再算作作者持有。代码中，`Operator.attributes` 与 `Grantor.attributes` 是 `Map<String, Any>`，即 token 声明原样，alpha.2 只保留了字符串。
- **会到期的绑定**（RC1-3、`GRT-3`）。作者经带 `expiresAt` 的绑定持有的权限，只包含自身绑定不晚于它到期的授出；没有 `expiresAt` 的授出、到期更晚的授出、把策略设为可附加，都是升级，短缺为 `EXPIRY`。
- **删除平台也定义了同一 id 的租户策略**（RC1-9、`GRT-4`）按把平台的策略授给租户中该 id 的绑定与附加来分析。两边都定义的 id 不给作者任何权限。
- **每一份 app 文档**（RC1-4、`GRT-6`）。租户变更的分析读取 app 列表（`<s>:control:apps:all`，[存储](./storage.md#publishing-through-the-control-plane)）列出的每一份 app 文档，而不只是变更点名的那些，所以 app 绑定给作者某个角色的 DENY，会在触及该 app 的每个作用域中限制作者可以授出的东西。读不到的 app 文档使这样的授出成为升级（`UNREADABLE`）。分析只在 app 列表完整时信任它，而升级上来的存储的列表并不完整：**升级后，在 [`cosec-migrate repair-references`](./upgrading.md#repair-the-back-reference-index) 运行之前，覆盖所有 app 的每个租户授出（app 为 `*` 的绑定，或附加，包括经条件收窄到变更没有点名的 app 的授出）都需要审批**（`UNREADABLE`），控制面会记一条点名该命令的 WARN，每分钟至多一次。
- **平台策略不能占用租户的 id**（RC1-9、`CTL-21`）。平台变更新增的策略 id 若已被某个租户文档定义或绑定，变更被拒绝（`V2`、`422`，列出这些租户文档）。自己定义了该 id 的租户，不再阻止平台删除它自己的同 id 策略。反向引用索引只记下租户在升级之后新增的 id；租户文档原有的 id 只有经 `repair-references` 才进入索引：**在每个控制面实例和每个 `cosec-migrate` 二进制都运行这个版本之后，以 Kubernetes Job 运行一次 [`cosec-migrate repair-references`](./upgrading.md#repair-the-back-reference-index)**，使用与控制面相同的 Redis 和 key 前缀；任何一次降级之后再运行一次。在它运行之前，平台策略若占用了某个租户在升级前就有的自有策略的 id，不会被拒绝，只会在那个租户中二义，执行时对此失败即拒绝。
- **升级**新增原因 `UNRESOLVED`（新绑定指向没有任何文档定义的策略，RC1-32）、`UNREADABLE`（RC1-4）与 `TENANT_BINDING`（应用文档的变更放宽了的每条租户绑定，RC1-34、`APR-6`）。`TENANT_BINDING` 仅供参考：应用变更因 `NOT_ANALYZED` 而需要审批，这一项只写出绑定和它所在的租户文档。穷举 `UnknownReason` 或 `Shortfall` 的代码要加上 `UNRESOLVED`、`UNREADABLE` 与 `EXPIRY`。
- **发布后的头变化**（RC1-33）。变更检查时读过的其他文档或 app 列表在它写入之前变化时，控制面在 `PUBLISHED` 之后记 `HEADS_MOVED` 或 `REVIEW_NEEDED`，变化的头在新成员 `moved` 中（[一次发布比较什么](./storage.md#what-a-publish-compares)）。处理每一种 `ChangeEvent.Kind` 的变更监听器要加上这两种。
- **信任根属于平台**（RC1-10、`APR-3`）。只有持有 `cosec:approve` 的平台主体发布自己的变更时不经分析；平台授予了 `cosec:approve` 的租户主体，它自己的变更现在要经分析，它审批别人的变更。租户的边界属于平台：租户主体的 `SetBoundary` 返回 `403`，它以 `REPLACE` 做的 `apply` 或回滚如果会删除或改变平台设定的边界，同样返回 `403`；也只有平台主体能审批、驳回或放弃设置边界的变更（`BDY-2`）。API key 从来不是信任根，即使其主体是持有 `cosec:approve` 的平台主体；任何名为 `api-key` 的凭证（包括转发来的身份所带的）也不是。开启 `four-eyes` 时，平台主体的变更需要另一位平台主体审批。
- **`cosec:platform-admin` 只由平台绑定**（RC1-36、`TNT-2`）：在平台文档中，或在平台租户自己的文档中（`bootstrap` 就写在这里）。绑定它的租户或 app 文档不再能编译；升级前请删除这样的绑定。
- **审批是人的行为，并会再次核查**（RC1-11、`APR-7`、`APR-8`）。以 API key 认证的主体不能审批（`403`）。审批记录审批人的凭证（`jti`、`iat`）；变更发布之前吊销这个令牌，或吊销审批人的令牌，审批就不再算数。成员关系来自成员关系提供者的审批人，既要按它审批时持有且现在仍持有的角色被放行，也要按提供者现在的答复被放行；starter 现在把应用的 `MembershipProvider` 交给控制面（RC1-13）。**没有记下凭证 id 的审批不算数**：升级前审批过的每个变更，发布时都会回到 `AWAITING_APPROVAL`，需要重新审批；凭证不带 id 的主体不能审批（`403`）。API 中变更的视图（`GET /changes/{id}` 及各步骤的答复）不再显示审批的 `memberships`、`attributes`、`credentialId` 和 `issuedAt`。代码中，`AuthenticatedPrincipal` 多了 `issuedAt` 和 `apiKey`。
- **starter 检查审批如何被吊销。** 控制面和数据面管道一样需要吊销读取器：一个 `RevocationReader` bean，或 CoSec 的令牌加 `cosec-cocache`；两者都没有时启动失败（审批人的凭证从不吊销时，声明 `RevocationReader.NONE`）。吊销的保留时长 `cosec.jwt.token-validity.refresh` 短于 `cosec.control.approval.expire-after` 时，启动也会失败。
- **被占用的暂存槽可以被抢占**（RC1-12、`CTL-22`、`BRK-9`）：撤销 break-glass，以及持有 `cosec:skip-shadow` 的人提交的只收窄变更（[抢占被占用的暂存槽](../policies/testing.md#taking-a-busy-slot)）。原先持有槽的变更成为 `CONFLICTED`。答复丢失后重试的发布报告 `PUBLISHED`。
- **租户 `(0)` 中的 `Anyone`**（RC1-35、`GRT-7`）：这样的授出会作用于未认证的调用方，需要审批（`ANONYMOUS`）。穷举 `UnknownReason` 的代码要加上 `ANONYMOUS`。
- **控制面资源中的默认空间写作 `(default)`**（RC1-37、`SCP-9`），以 `(` 开头的空间 id 保留给 CoSec：声明这种空间的请求返回 `403`（`scope-rejected`），在这种空间中绑定的文档不能编译。为 `scope/<tenant>/<app>/-` 写的控制面策略现在指名为 `-` 的空间；默认空间请写 `scope/<tenant>/<app>/(default)`。**写在 `scope/*/*/-` 上的控制面 DENY 不再作用于默认空间**：升级前把它改写为 `scope/*/*/(default)`，否则它原先挡住的授出会被放行。空间 id 以 `(` 开头的绑定会让所在文档无法编译，这种空间中的请求也会被拒绝：升级前在每份文档中查找 `"space": "(`，把这样的绑定移到你自己的空间 id，并把该空间的成员关系一并迁过去。
- **break-glass**（RC1-27、RC1-29、RC1-30）：`cosec-migrate` 不写 break-glass 绑定（[root 变为 break-glass](./upgrading.md#root-becomes-break-glass)）；`BreakGlass.request` 自行派生绑定 id，不再有 `binding` 参数；审批 break-glass 需要 REQUIRED 变更监听器（否则 `503`，`BRK-3`）。
- **撤销 break-glass 按效果作答**（R4-1）。`DELETE /break-glass/{bindingId}` 只在该绑定在平台文档的头上不再生效之后才返回 `200`，无论是谁删除的。撤销后绑定仍在生效时，现在返回 `409`，`detail` 写明调用方的变更所处的状态（`CONFLICTED` 等），此前返回的是带着该变更的 `200`：把 `200` 当作"已撤销"而不看 `state` 的客户端，需要处理这个 `409`。平台文档中没有的绑定 id 现在返回 `404`；该 id 的绑定不是 break-glass 绑定时（无论是否到期）返回 `409`（`binding [<id>] is not a break-glass binding`）；两者都不提议任何变更，此前都返回带着 `DRAFT` 变更的 `200`（`BRK-11`）。被另一个撤销抢占，或遇到已变化的头的撤销，会再撤销一次，至多再试三次。
- **放弃变更**（RC1-51、`BRK-10`）。撤销仍在生效的 break-glass 绑定的变更只有其作者能放弃：其他人（包括被撤销的主体）得到 `403`。请改为再撤销一次该绑定，这会发布停住的撤销，或抢走它的暂存槽。每次放弃都记一条新的变更事件 `ABANDONED`；放弃 break-glass 变更需要 REQUIRED 变更监听器（否则 `503`）。处理每一种 `ChangeEvent.Kind` 的变更监听器要加上 `ABANDONED`。
- **身份转发改为按 scheme 选择加入**（RC1-49、`IDN-7`）。网关只把 `CoSec-Identity` 转发给指向已注册服务的路由（`uri: lb://...`、`lb:ws://...`），或元数据为 `cosec-identity: true` 的路由；`false` 仍然让任何路由都不接收，`true` 以外的值都按 `false` 处理。**经 `http://` 或 `https://` 指向你自己服务的路由不再收到身份，其后的服务看到的是匿名请求**：升级网关之前，给这些路由的元数据加上 `cosec-identity: true`，不要给指向第三方的路由加（[转发身份](../integration/web.md#forwarding-the-identity)）。请求属性 `cosec.identity.forward` 现在在每个被路由的请求上都是 `true` 或 `false`，过去只设为 `false`。
- **不向下游转发任何凭证**（RC1-5、`IDN-8`）。除 `Authorization` 外，网关还从它转发的每个请求中移除 API key 头（`cosec.pipeline.api-key-header`，无论名称设成什么）和 `authorization` 查询参数，不论网关自己是否从查询参数读令牌（只有 `cosec.pipeline.allow-query-token=true` 时才读，默认关闭）。自己读取它们的服务需要 `cosec.gateway.identity.forward-authorization=true`；打开后，它们现在只发往本会接收身份的路由（`lb://`，或 `cosec-identity: true`），因此指向第三方的路由一个也收不到。
- **`authorization` cookie 也留在网关**（`IDN-8`）。在同一个开关下，网关从它转发的每个 `Cookie` 头中移除它（名称比较不区分大小写），其他 cookie 保留；不再剩下任何 cookie 的头不发送。从这个 cookie 读取令牌的服务需要 `forward-authorization=true`，以及一条本会接收身份的路由。
- **路由指定它的应用**（RC1-6、`SCP-2`）。路由元数据 `cosec-app-id` 是该路由上请求的应用，优先于 `cosec.authorization.scope.app-id`：在这样的路由上，声明了其他应用的请求为 `403` `scope-rejected`（匿名调用方为 `401`），没有声明的请求取路由的应用、它的角色绑定及其中的 DENY。没有这项元数据的路由沿用网关的宿主应用，或客户端声明的应用。在面向多个应用的网关上，给每条路由指定它的应用。不是字符串、或不能作为应用 id 的 `cosec-app-id` 使该路由的每个请求失败：数字形式的应用 id 要加引号。
- **网关上的 actuator**（RC1-48、`STA-2`）。按策略决策的网关宿主，在其应用端口上（`management.server.port` 未设置，或与 `server.port` 相同）通过 HTTP 暴露了 `health` 以外的 actuator 端点时不再启动：CoSec 授权的是网关的路由，不是这些端点。把 actuator 放到单独的端口上并置于网络策略之后，只暴露 `health`，或在新配置项 `cosec.gateway.unprotected-endpoints` 中列出由其他手段保护的端点；它从不接受 `gateway`（[网关上的 actuator](../integration/web.md#actuator-on-a-gateway)）。`cosec-gateway-server` 和 `k8s/` 只暴露 `health`。
- **空白的 `CoSec-*` 值与另一个值同时出现即有歧义**（RC1-16、`SCP-4`）：请求头 `CoSec-Space-Id: s1` 加上 `?CoSec-Space-Id=` 现在是 `400` `invalid-request`，过去取 `s1`。空白只指 ASCII 空白字符，因此由 Unicode 空格组成的值（例如 `%E3%80%80`）是一个值：单独出现时，它是主体必须在其中有角色的空间。每个参数只发送一次。
- **应用 id 拒绝控制字符**（RC1-15、`SCP-10`），与租户 id 相同：声明的应用含控制字符时为 `403` `scope-rejected`（匿名调用方为 `401`），宿主应用含控制字符时启动失败。
- **id 拒绝落单的 UTF-16 代理项**（RC1-52、`IDN-10`）。JSON 令牌或请求体可以用转义写出一个落单的代理项（`"beta\uD800"`），而 Redis key 在它的位置写 `?`，所以租户 `beta\uD800` 读到的是租户 `beta?` 的文档，主体的成员关系、吊销与限流的桶也同样共用。现在：令牌的 `sub`、`tenantId`、`jti`、`roles` 或 `policies` 里有落单的代理项时为 `401` `token-invalid`，CoSec 自己的签发方拒绝签出这样的令牌（登录失败）；指名这种 id 的 `CoSec-Identity` 为 `400`；路径、查询或请求体任何位置带有落单代理项的控制请求为 `400`；给出这种 id 的认证器、API Key 记录或成员关系提供方让请求以 `503` 失败；带落单代理项的应用或租户 id 与带控制字符的一样被拒绝。限流 `key` 的值带落单代理项时计入它自己的桶（`e:` 加上转义后的值）。含代理对（如 emoji）的 id 不受影响。日志与错误信息把这种代理项写成 `\uXXXX`，从不写成 `?`。在代码中，`Identifiers`（`cosec-model`）持有这条规则；`ApiKey` 与 `AuthenticatedPrincipal` 在构造时拒绝这种 id，`RevokedTokenCache.key`、`RevokedSubjectCache.key` 与 `MembershipCache.key` 对它们抛出异常。无需迁移；如果令牌签发方或脚本可能发送过这种 id，以带 `?` 的 id 存储的内容可能是为另一个 id 写入的。
- **控制面只接受应用 id**（`SCP-10`）。应用 id 不为空白、不是 `*`、不含 `:` 和控制字符，且不超过 128 个字符；之前的构建接受任何不含 `:` 的应用。现在其他应用中的绑定是位于 `$.bindings[i].app` 的 `V2` 发现（`400`），无论它在候选中的什么位置。`analyze` 对这种应用回答 `400`。**升级控制面之前**，移除这种应用中的租户绑定，放弃这种应用文档上未结束的变更，并清空以这种 id 发布的应用文档（发布一个没有策略和绑定的修订），这会移除它们的反向引用。升级之后就无法再处理它们，遗留下来的内容是格式错误，按失败关闭处理，绝不跳过：保留这种绑定的文档仍然可读，而该绑定不授予任何东西，但发布和 `repair-references` 都过不去；这种应用上未结束的变更回答 `503`，并让每一轮维护都停下，直到它的记录被移除；应用列表中的条目让授予涉及的每个应用都变成未知，直到删除 `<p>:control:apps:all` 并运行 `cosec-migrate repair-references`；指向这种应用的反向引用让读取该集合的操作都失败，直到删除该集合并运行 `repair-references`；实例状态的条目让实例状态的读取失败，直到它过期（该实例最后一次写入后两分钟）。
- **按策略决策的宿主丢弃客户端的 `CoSec-Identity`**（RC1-18、`IDN-9`）。`enforce` 模式的 WebFlux 或 Spring MVC 宿主会从处理器看到的请求中移除该头，因此它从不转递客户端写的身份。在那里读取它的代码读到的是任何客户端都能写的内容；请读取 `SecurityContext`。
- **租户把应用权限授给 `Anyone` 或某个主体的绑定现在能解析**（RC1-17、`TNT-4`）。该应用中的请求现在无论主体有什么角色都读取应用文档，因此绑定中的 DENY 或 ALLOW 生效（过去这样的请求都是 `503`），应用文档的限流规则也随之生效。`simulate`、`explain` 和影子窗口按同一规则读取它（`CTL-3`）。代码中，`CompiledDocument.tenant` 接收集合 `boundApps`，由 `CompiledDocument.needsApp` 回答；自行构造的 `ShadowListener` 需要已安装的视图（`views`），才能比较让请求读取应用文档的租户候选，没有视图时上报 `CANDIDATE_ERROR`。
- **一个 key 前缀**（RC1-46、`SNP-9`）。[`cosec.key-prefix`](./configuration.md#key-prefix) 取代 `cosec.authorization.snapshot.key-prefix`、`cosec.authorization.cache.key-prefix` 和 `cosec.api-key.key-prefix`。仍设置其中任何一项的环境都会启动失败，和其他 CoSec 不读取的 key 一样。每个 key 都在它之下：API key 在 `<p>:api-key` 下，过去固定的限流计数器和社交登录的 OAuth 状态分别在 `<p>:rate-limiter` 和 `<p>:oauth:state` 下（[Redis key](./storage.md#redis-keys)）。使用默认值时每个 key 都还在原处。设置过这三项之一的部署，改为把 `cosec.key-prefix` 设成原来的快照前缀；**保存在其他前缀下的吊销和 API key 不再被读取**，在那里吊销的令牌会重新被接受：切换之前，把每个这样的 key 复制到新前缀下（`COPY`，或保留过期时间的 `RENAME`）。成员关系会自行重新填充。`cosec-migrate api-keys` 不再接受 `--api-key-prefix`：它转换 `<--key-prefix>:api-key` 下的 key。

  设 `<p>` 为 `cosec.key-prefix` 的值，各 key 族的移动如下：
  - 快照文档、控制面记录、候选和影子报告：从旧的 `cosec.authorization.snapshot.key-prefix`（默认 `cosec`）移到 `<p>`；
  - 令牌吊销和成员关系：从旧的 `cosec.authorization.cache.key-prefix`（默认 `cosec`）移到 `<p>`，应用自己创建的成员关系缓存除外，它们留在自己的前缀下；
  - API key：从旧的 `cosec.api-key.key-prefix`（默认 `cosec:api-key`）移到 `<p>:api-key`；
  - 限流计数器和社交登录的 OAuth 状态：从 `cosec` 移到 `<p>`。
- **`revalidate-interval` 不能关闭周期确认**（RC1-50、`SNP-6`）。失效消息丢失时，周期确认是唯一的界，因此 `cosec.authorization.snapshot.revalidate-interval` 必须至少为 `1s`（每一轮都从 Redis 读取每个已安装的文档）、不超过 `5m`、小于 `lag-warn`，配置了 `fail-closed.tenants` 时还要小于 `fail-closed.max-lag`；否则启动失败并指出该配置项，`SnapshotOptions` 也同样拒绝。把 `0` 换成范围内的值；更长的间隔可以降低确认在 Redis 上的开销（[存储](./storage.md#what-revalidation-costs-redis)）。
- **路径模式有开销上界**（RC1-8、`DOC-4`）。`http:path:` 模式不超过 2,048 个字符，把 `*`、`?` 或 `{name}` 与其他文本混在一起的段最多 16 个部分（连续的 `*` 算一个），一个文档中这样的片段合计最多 2,048 个通配单位（它们的部分数加上文本的字符数，前导 `**` 之后的片段和以捕获变量为 key 的限流规则计两次），类型化模式不超过 2,058 个字符（[策略语言](../policies/language.md#path-pattern-bounds)）。控制面对超出上界的变更回答 `COMPILE` 发现（`422`），位置为该模式或 `$`。之前发布、含有这种模式的文档在升级后的实例上不再能编译，读取它的每个请求都失败关闭：升级数据面之前，改写它并发布。
- **每个策略在每一遍中对每个绑定它的文档只求值一次**（`ALG-9`），无论该文档有多少条绑定生效；在附加策略中声明两次的策略只附加一次。决策及其报告的语句和绑定都不变：报告的是候选顺序中的第一条绑定。追踪按第一条绑定的结果列出之后的绑定，不再重新求值。
- **文档读取的请求头重复出现时为 `400`**（RC1-38、`PIP-12`）。请求以不同的值多次发送某个请求头，而它的视图所读文档中的任何语句、策略条件、边界或限流 key 读取这个请求头（包括 `Origin` 和 `Referer`）时，管道在求值之前回答 `400` `invalid-request`；过去读取它的 DENY 使请求为 `503`，读取它的 ALLOW 不匹配。相同的重复值算一个值（[Web](../integration/web.md#responses-and-status-codes)）。
- **格式错误的 `roles`、`policies` 或 `attributes` 声明是无效令牌**（`PIP-6`）：`401` `token-invalid`，按匿名求值，过去是 `500`。
- **处处拒绝重复的 key**（RC1-44、`DOC-5`）。任何含重复 key 的存储格式（API key、成员关系、影子报告、吊销）和控制面请求体都无法读取：存储的值为 `503`，请求体为 `400`。`cosec-migrate` 也拒绝含重复 key 的 7.x 导出和文件。
- **CoSec 的缓存无论由谁构建都保留其固定项**（RC1-45、`SNP-3`）。`createPolicySnapshotCache` 对任何 spec 都应用权威存储的策略、快照的编解码器，并去掉 key 过滤器、按条目的 TTL 和加载器；`CacheSpecCustomizer` 不再能改变 CoSec 缓存的编解码器、加载器、key 过滤器或按条目的 TTL。
- **编译时栈溢出的文档失败关闭**（RC1-43、`SNP-8`），与其他编译错误一样，而不再保留之前的修订。
- **限流在 Redis Cluster 上计数**（#1229、`OBL-4`）。Redis 限流器的计数器移到 `<p>:rate-limiter:{<rule>:<bucket>}:<window>`：hash tag 让一个桶的两个窗口（脚本一起读取它们）在同一个 slot 上。在集群上每次调用都以 `CROSSSLOT` 失败，于是没有 `strictFailure` 的规则被跳过、什么也不限制，有 `strictFailure` 的规则让每个放行的请求返回 `503`。在单个 Redis 上只有 key 变化。**滚动升级期间，两个版本的实例各自在自己的 key 里计数**：新旧版本的实例同时服务期间，以及最后一个旧实例停止后的至多两个窗口内，一条规则至多放行两倍的上限。旧 key 在两个窗口后过期。第一个 `{` 后紧跟 `}`（空 hash tag）的 `cosec.key-prefix` 现在会启动失败。
- **边界的外部属性会被获取**（#1208、`ATR-8`）。属性计划包含约束这次请求的边界的属性，即它的语句的与它的策略条件的，于是阶段 B 的提供者会被询问这些属性。这样的边界允许的请求过去返回 `503`（`BoundaryUnavailable`），现在放行；每个请求询问提供者的属性可能变多。
- **检查获取它需要的属性**（#1209、`ATR-8`）。`AuthorizationChecker` 向提供者询问它的动作与资源读取的每个属性，包括阶段 A 的提供者（与阶段 A 一样不带作用域）；过去除非注册了某个阶段 B 的提供者，阶段 A 的属性都取不到（`AttributeUnavailable`）。现在每次需要阶段 A 提供者某个属性的检查都会调用它。
- **向提供者询问的属性变少**（#1211、`ATR-8`）。属性计划遍历求值会访问的语句，即语句索引给出的语句，而不再是动作匹配的每一条语句：资源路径不可能匹配请求的语句不会规划任何属性，它的策略条件也不会。没有任何读取某属性的语句可能匹配的请求，不再调用阶段 B 的提供者。请求依据什么决策不变。
- **同一时间只有一轮重新确认**（#1215、`SNP-6`）。超过 `revalidate-interval` 的周期确认不再有另一轮叠加上来：下一轮在它结束后立即开始，所以慢的 Redis 同一时间至多收到一个实例的四个重新确认读。失效消息丢失的变化由第一个到达该文档的一轮装上，这时可能超过一个间隔（[存储](./storage.md#how-instances-read-documents)）。
- **各种标准数字类型的身份声明**（#1228）。网关转发 `Byte`、`Short`、`Long` 范围内的 `BigInteger`、`Float`，以及 `Double` 能精确表示的 `BigDecimal` 类型的声明，过去这样的请求返回 `500`。头不能精确表示的数字仍以 `500` 拒绝，`NaN` 和无穷大也一样，JSON 没有表示它们的数字。
- **`simulate` 与 `explain` 在决定该请求的租户上决策与授权**（RC1-40、`CTL-23`）。没有 `principal` 的请求与执法一样在
  `(0)` 的文档上决策：它需要 `(0)` 上的 `cosec:simulate` 或 `cosec:explain`，而 alpha.2 检查的是请求指定的租户；指定其他
  租户的请求是 `400`。要模拟匿名请求，请写 `"tenant": "(0)"`，由能在那里模拟的主体来做。
- **`simulate` 与 `explain` 拒绝执法所拒绝的**（`CTL-3`、`PIP-12`）：以不同的值发送其文档所读取的头的请求是 `400`，而之前
  会被决策（DENY 读它时为 Indeterminate）；`http` 资源与请求路径经同一个规范化：执法拒绝的路径（点号段、格式错误的转义、
  超过 2,048 个字符或每段 256 个）是 `400`，路径参数在决策前去掉，而之前按原样决策；超过这些长度的类型化 id 是 `400`。
  `analyze` 的资源也一样。
- **控制 API 的 `400` 说得更少，存储的值解码失败是 `500`**（RC1-39）。`400` 的 `detail` 是控制面自己的某项检查，或者点名
  解析不了的字段；它不再带解析器或异常的消息（`No enum constant ...`、`For input string ...`、JSON 解析器的消息），解析
  不了的时间（`expiresAt`、`time`）是 `400`，而之前是 `500`。存储的文档或记录解码失败是不带详情的 `500`，而之前是带有存储
  文档 JSON 路径的 `400`；控制面主机按 key 记录它。按 `detail` 文本做匹配的客户端，请改为按状态码和 `code` 匹配。
- **路径模式中落单的代理项不再编译**（#1200 审查的 L4）：`http:path:` 模式文本中只有一半的代理对是一个 `COMPILE` 发现，
  而之前它能编译，并能匹配一个代理对的中间，Spring 永远不会这样匹配。之前发布的含有它的文档，在升级后的实例上失败即拒绝。
- **CoSec 的 Redis 模板从主节点读**（RC1-47）：通过 `spring.data.redis.lettuce.read-from` 或客户端定制器从副本读的
  `LettuceConnectionFactory` 会让启动失败（[Redis 要求](./storage.md#redis-requirements)）。另外，新的健康指示器
  `cosecSnapshotEviction` 在 Redis 可能驱逐快照时为 `DOWN`，每个 `revalidate-interval` 读一次；读不到策略时，
  `eviction-check: fail` 下为 `DOWN`，`warn` 下为 `UP`，详情 `eviction: unknown`。它是告警：`cosecSnapshot` 不变，
  只有它属于就绪组。
- **滚动升级期间**，旧实例新建的 app 文档不在 app 列表中；新实例在启动时和每次回收任务（`cosec.control.revisions.reclaim-interval`，默认 1 小时）时发现它，在此之前分析授出时可能读不到这个 app 的 DENY。滚动期间不要经旧实例新建 app，或者在此期间调低 `reclaim-interval`。旧实例还会把持有 `cosec:approve` 的租户主体当作信任根，并接受 API key 的审批：滚动完成之前，只经已升级的实例审批和发布。旧实例接受的审批没有记下凭证，已升级的实例不会凭它发布。
- **没有使用者的公共 API 已移除。** `KafkaAuditEventSink`：starter 注册的是 `KafkaAuditListener`，它写入相同的 key 和 value。`SimpleSecurityContext` 及其扩展属性 `CoSecPrincipal.tenant`：请求的 `SecurityContext` 由管道创建，测试中请 mock 或自行实现该接口。引擎请求中的 `AuthzRequest.scope`、类 `me.ahoo.cosec.model.Scope`，以及 `Subject.roles`、`policies` 和 `root`，引擎都不读取：构造 `AuthzRequest` 或 `Subject` 的代码去掉这些参数即可。引擎使用的租户、角色和附加策略在 `EngineRequest.scope` 与 `EngineRequest.memberships` 中。`Subject.tenantId` 保留，供 stage A 的 `AttributeProvider` 使用，它在作用域解析之前运行。

### 经 CoCache 6 写入的数据 {#data-written-through-cocache-6}

8.0.0-alpha.2 经 CoCache 6 在 Redis 中保存数据；8.0.0 经 CoCache 8 保存，key 不变，但 CoCache 8 读不了 CoCache 6 的格式。它
在这种格式中读到的内容按读不懂的内容处理，失败即拒绝：需要这种文档的请求是 `503`（`SourceUnavailable`），在其中被吊销的令牌
或主体是 `503` 而不是 `401`，控制面 API 回答 `500`。没有任何读取会把这样的值当作不存在，所以被吊销的令牌绝不会重新被接受，
吊销表也不会。

8.0.0 的 `cosec-migrate` 不再有迁移这些数据的命令：请在升级到 8.0.0 之前，用 8.0.0-alpha.4 的 `cosec-migrate` 按下面的
步骤迁移（[从 8.0.0-alpha.4 升级](#from-8-0-0-alpha-4)）。

| 数据 | Key | 迁移之前，在 8.0.0 上 | `cosec-migrate cocache8` |
|---|---|---|---|
| 快照文档 | `<p>:snapshot:*` | 读取它的每个请求都是 `503` | 作为每份文档的第 1 个修订重新发布（来源 `MIGRATE`，没有父修订：父修订的 SHA-1 摘要不是 CoCache 8 认识的摘要） |
| 吊销 | `<p>:token:revoked:*`、`<p>:token:subject:revoked:*` | 被吊销的令牌或主体是 `503` | 重新写入，各自保留剩余的寿命 |
| 控制面记录 | `<p>:control:*` | 控制面 API 回答 `500` | 删除：历史从重新发布的文档开始 |
| 候选与影子报告 | `<p>:candidate:*`、`<p>:shadow:*` | 影子窗口无法报告 | 删除；仍在进行的变更会让命令拒绝执行（见下） |
| 成员关系 | `<p>:membership:*` | 从 `MembershipLoader` 重新加载 | 删除；它们会重新填充 |
| API key、限流计数器、OAuth 状态 | `<p>:api-key:*`、`<p>:rate-limiter:*`、`<p>:oauth:state:*` | 不变：由 Spring 的 Redis 模板保存，不经 CoCache | 不处理 |

1. 发布或放弃每个进行中的变更，并结束每个破窗授权：存在打开的变更（包括草稿）、暂存槽或候选时，除非指定
   `--discard-pending`，命令拒绝执行；含破窗绑定的文档也会被拒绝，与 `publish` 相同。
2. 如果 alpha.2 设置过被 `cosec.key-prefix` 取代的 key 前缀之一，先迁移那些 key（见上面的“一个 key 前缀”）：命令只读一个前缀。
3. 停止所有 CoSec 实例，包括控制面。alpha.2 实例与 8.0.0 实例既读不了对方的值，也收不到对方的失效消息：CoCache 8 的失效通道
   以 key 前缀命名。
4. 先看看会迁移什么，不写入任何内容：
   `cosec-migrate cocache8 --redis redis://localhost:6379 --key-prefix cosec --out ./cocache8 --dry-run`
   （Redis Cluster 加 `--cluster` 并给出种子节点）。它会列出拒绝执行的原因：仍打开的变更（包括草稿）、`publish` 会拒绝的
   文档、读不懂的值，或者既没有需要迁移的文档也没有平台文档。
5. 去掉 `--dry-run` 再运行。它先把文档按 `publish` 读取的布局写到 `--out`，并写一个标记 `<p>:meta:cocache8`，再写吊销
   （覆盖 CoCache 6 的值，所以任何时刻都不缺吊销），然后删除其他 CoCache 6 的值，发布文档，最后删除标记。如果它在删除之后
   中断，不要再运行 `cocache8` 来完成：它看不到自己已删除的文档，标记还在时它会拒绝执行。用它打印的命令完成：
   `cosec-migrate publish --to-redis <uri> --key-prefix <p> [--cluster] --in ./cocache8 --resume`，每份文档都重新发布后，
   这条命令会删除标记：这里用的是 alpha.4 的 `publish`，8.0.0 的 `publish` 在标记存在时会拒绝执行。
6. 启动 8.0.0 实例。每份文档的修订号从 1 重新开始；迁移之前的历史不会保留，如果需要，在第 5 步之前保存一份
   `<p>:control:revision:*`。

摘要现在是 SHA-256，alpha.2 用的是 CoCache 6 的 SHA-1：控制 API 的修订历史、变更的基线与检查，以及提到摘要的事件，都写作
`SHA-256:<64 位十六进制>`。自己实现的 `SnapshotDocumentStore` 必须给出 32 字节的 SHA-256 摘要，否则每次读取都失败即拒绝
（[扩展](../architecture/extending.md#snapshot-document-stores)）。

## 从 8.0.0-alpha.1 升级 {#from-8-0-0-alpha-1}

8.0.0-alpha.1 是预览版，没有兼容性承诺。此后来自 [RC 安全评审](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/security/8.0-rc-review.md)的以下变更，会破坏基于它写成的决策、代码或配置。7.x 部署在下面各节中读到它们。

- **Kubernetes 根据 Service 设置的 `cosec.gateway.*` 变量**（`COSEC_GATEWAY_SERVICE_HOST` 之类）在 8.0.0-alpha.1 中会被当作 CoSec 不读取的 `cosec.*` key 而拒绝启动。从 alpha.2 起，检查会跳过这些确切的变量；清单里的 `enableServiceLinks: false` 本来就不让它们进来（[部署](./deployment.md#what-the-manifests-set)）。
- **角色上限只移除 ALLOW**（RC1-1、`BND-9`、`IDN-2`）。带上限的凭证（带 `roles` 的 API key）仍然得不到其主体附加策略和主体绑定的授权，但它们的 DENY 现在作用于它：主体被拒绝的请求，该主体的每个 key 都被拒绝，无论是否有上限。alpha.1 中上限把这些 DENY 也一并去掉了，因此带上限的 key 能做主体不能做的事。无需迁移；要看哪些请求的结果会变，用 `principalBindings: false` 对它们做 `simulate`（[策略测试](../policies/testing.md)）。授权分析以同样方式计入带上限作者的 DENY。
- **break-glass 按来源判断**（RC1-26、`BRK-8`）。只有 CoSec 自己的 `JwtTokenVerifier` 校验过的令牌才能得到 break-glass。包装其他 `TokenVerifier` 的 `TokenAuthenticator` 认证其令牌时不带 break-glass，并以它的 `method`（默认 `token`）而不是 `jwt` 命名凭证。方式 `jwt` 是保留名：`AuthenticatedPrincipal` 拒绝你的代码用这个方式构造的凭证（`IllegalArgumentException`，即 `503`），`TokenAuthenticator` 也拒绝为非 CoSec 的校验器设置 `method = "jwt"`。请按凭证给你的认证器的方式命名（[扩展](../architecture/extending.md#authenticators)）。
- **改名后的 API key 头是携带凭证的请求头**（RC1-21、`CND-5`）。设置了 `cosec.pipeline.api-key-header` 时，读取该请求头的文档（条件或限流规则的 `key`）无论在控制面还是实例上都不再能编译，管道也从不为条件读取它。升级前删除这类引用。代码中，`ConditionChecker`、`PolicyDocumentCompiler.forPlatform` 和 `of`、`PlatformCompiler`、`ChangeChecks`、`Simulator` 与 `HttpAttributes` 以新参数接收该请求头。
- **`tenantId` 声明不是字符串时令牌无效**（RC1-22），alpha.1 则把它读作默认租户 `(0)`。请以字符串签发租户。
- **租户切换重新得出成员关系**（RC1-23）。`AbstractSwitchTenantAuthentication.switchTenant` 回答一个 `TenantMembership`，即用户在目标租户中的角色、策略和属性（或 `TenantMembership.fromProvider()`），而不是一个主体；`authenticate` 只依据它构造新主体。包装 `previousPrincipal` 的实现会把先前租户的角色带进新令牌（[租户](../integration/authentication.md#tenants)）。
- **JWT 密钥有最短长度**（RC1-24）：`hmac256`、`hmac384`、`hmac512` 分别至少 32、48、64 个 UTF-8 字节。更短的 `cosec.jwt.secret` 会让启动失败；在签发方和每个校验方设置更长的密钥，用旧密钥签发的令牌随之失效。
- **社交登录只签名提供方拥有的字段**（RC1-25）。`DirectSocialUserPrincipalConverter` 只给主体两个属性 `cosec_oauth_provider` 和 `cosec_oauth_user_id`，不再使用用户可编辑的提供方原始用户信息，也不再写入它转换的 `SocialUser`。`attributes` 中带 `apiKeyId`（CoSec 为 API key 设置）的令牌按 `token-invalid` 拒绝，`JwtTokenConverter` 也不会签发这样的令牌。要保留资料字段，请声明你自己的 `SocialUserPrincipalConverter`，并且只保留用户无法更改的字段（[社交登录](../integration/authentication.md#social-login)）。
