---
title: "选择部署方式"
description: "运行 CoSec 的几种方式：一个自己执行授权的服务、网关加其后的服务、校验另一个服务所签发令牌的服务、登录服务，以及控制面的两种部署（它自己的宿主，或内嵌在 IAM 服务中）。每种方式运行什么、需要什么、必须连到哪里、靠什么保证安全。"
---

# 选择部署方式 {#choosing-a-deployment}

面向要决定哪些进程运行 CoSec、每个进程需要什么的运维人员和架构师。下面每种方式都是跑在同一个 Redis 上的一组 Spring Boot 应用；它们的区别在于谁来决策、谁来校验令牌、谁持有哪个密钥。请同时阅读[加固清单](../guide/security-model.md#hardening-checklist)：本页说明每种方式靠什么成立，清单说明在它承载流量之前要设置什么。设置项见[配置](./configuration.md)，网关镜像见[部署](./deployment.md)。

| 方式 | 进程 | 谁决策 | 谁校验令牌 | 靠什么成立 |
|---|---|---|---|---|
| [一个自己执行授权的服务](#one-service-that-enforces) | 一个应用 | 该服务 | 该服务 | 签名密钥和 Redis |
| [网关，其后的服务使用 `gateway` 模式](#a-gateway-with-services-behind-it) | 一个网关及其服务 | 网关 | 网关 | 网关与服务之间的网络 |
| [网关和服务各自再决策一次](#a-gateway-and-services-that-decide-again) | 一个网关及其服务 | 两者 | 两者 | 每个进程中的签名密钥，以及 Redis |
| [校验另一个服务所签发令牌的服务](#a-service-that-verifies-tokens-another-service-issues) | 签发方旁边的一个应用 | 服务自己的代码 | 该服务 | 签发方的密钥和签发方的 Redis |
| [登录服务](#a-login-service) | 一个应用 | | 它负责签发 | 签名密钥 |
| [网关、控制面宿主和 IAM](#control-plane-host) | 一个网关、`cosec-control` 镜像和一个 IAM 服务 | 对运维人员，是网关；对每个变更，是控制面 | 宿主上没有人：它相信收到的身份 | 放行其调用方的网络，以及对 Redis 的写权限 |
| [网关和内嵌控制面的 IAM](#control-plane-embedded) | 一个网关和一个 IAM 应用 | 对运维人员，是网关；对每个变更，是控制面 | IAM 服务签发；网关校验 | 作为唯一入口的网关、IAM 自己的代码，以及对 Redis 的写权限 |

## 每种方式都共有的 {#what-every-topology-shares}

- **Redis 是权威存储**，保存文档、吊销记录和会话，要求 `maxmemory-policy noeviction`、持久化、副本和备份（[Redis 要求](./storage.md#redis-requirements)）。所有读写 CoSec 键的进程，每个环境用同一个 `cosec.key-prefix`。
- **`cosec.jwt.secret` 既签名又校验。** HMAC 是对称的：**每个持有该密钥的进程，都能为任意租户、主体和角色签发令牌**，包括控制面的 `(platform)` 管理员。“只校验”的服务，对任何拿到它配置的人来说就是签发方。只把密钥交给你愿意让它签发令牌的进程，放在密钥库里，把泄露当作所有账号的泄露。轮换密钥会让用旧密钥签发的每个令牌失效：实例切换的那一刻，持有另一个密钥的实例上每个请求都是 `401` `token-invalid`，所有会话都要重新登录。每个部署只有一个密钥，没有密钥 id，所以滚动重启期间是混合状态；请在安静的时段轮换，并预期所有人会被登出。
- **starter 在启动时检查模块**，缺什么就在失败信息里写出制品名（[模块](../guide/introduction.md#modules)，STA-1）。它还会拒绝写成它读不懂的值的 `cosec.*` 开关（`true` 或 `false`；`cosec.pipeline.mode` 为 `enforce` 或 `gateway`；API key 存储为 `memory` 或 `redis`），而不是把它当作“关”（[功能开关](./configuration.md#feature-switches)），并记录一行 `INFO`，说明 CoSec 被配置成什么样；`cosec.enabled=false` 时则是一行 `WARN`（[启动摘要](./observability.md#startup-summary)）。

## 一个自己执行授权的服务 {#one-service-that-enforces}

快速开始构建的就是它：一个使用默认 `cosec.pipeline.mode=enforce` 的 Spring Boot 应用。

| | |
|---|---|
| 运行 | 该应用，带 `cosec-spring-boot`、它的 Web 适配器（`cosec-webflux` 或 `cosec-webmvc`）和 `cosec-cocache`（以及 Spring 与 CoCache 的 Redis starter）；可选 `cosec-control`、`cosec-kafka`、`cosec-micrometer` |
| 设置 | `cosec.jwt.secret`、`spring.data.redis.*`，以及按它所服务的内容设置 `cosec.authorization.scope.app-id` 和 `cosec.authorization.remote-ip.max-trusted-index` |
| 连到 | Redis（主节点）；审计发往 Kafka 时还有 Kafka |
| 靠什么成立 | 签名密钥；客户端地址，它取决于前面的代理 |
| CoSec 会检查 | 功能所需的模块；三个令牌存储；Redis 不会淘汰数据；客户端地址设置；就绪状态取决于平台文档 |
| CoSec 不检查 | Redis 是否持久化或有副本；两个环境是否用了两个前缀；各进程的密钥是否相同 |

见[快速开始](../guide/quick-start.md)、[Web 集成](../integration/web.md)和[存储](./storage.md)。

## 网关，其后的服务使用 `gateway` 模式 {#a-gateway-with-services-behind-it}

Spring Cloud Gateway 用文档决策；每个服务设置 `cosec.pipeline.mode=gateway`，从网关转发的 `CoSec-Identity` 头读取调用方。

| | |
|---|---|
| 运行 | 网关（[镜像](./deployment.md)，或 `cosec-gateway` 加 `cosec-cocache`），以及每个带 `cosec-webflux` 或 `cosec-webmvc` 的服务 |
| 设置 | 网关：`cosec.jwt.secret`、Redis、路由；服务：`cosec.pipeline.mode=gateway` 和 `cosec.pipeline.identity.only-gateway-can-reach=true`（没有 Redis，没有密钥） |
| 连到 | 网关：Redis 和各服务；服务：CoSec 方面什么都不连 |
| 靠什么成立 | **只靠网络** |
| CoSec 会检查 | 服务启动时的一条 `WARN`；网关主机设置 `gateway` 模式时启动失败；每个请求中，无法读取、格式版本未知、太旧或太大的头会被拒绝 |
| CoSec 不检查 | 谁能访问该服务 |

这个头是无签名的 base64url JSON，没有密钥也没有签名。**`gateway` 模式的服务会相信它收到的任何 `CoSec-Identity`**：能向它发请求的人，都可以冒充平台管理员。守住边界的是网络：只放行网关 Pod 的 Kubernetes `NetworkPolicy`、私有网络，或双向 TLS。没有设置策略的命名空间（这是 Kubernetes 的默认状态）允许集群里任何 Pod 发送这个头。[`k8s/examples/gateway-mode-service-networkpolicy.yaml`](https://github.com/Ahoo-Wang/CoSec/blob/main/k8s/examples/gateway-mode-service-networkpolicy.yaml) 是一份可以复制给该服务用的 `NetworkPolicy`：它只放行网关所在命名空间里标签为 `app.kubernetes.io/name: cosec-gateway` 的 Pod 访问服务的端口。它需要一个会执行 `NetworkPolicy` 的网络插件；没有就什么都不会发生，所以要测试另一个标签的 Pod 无法连接。`cosec.pipeline.identity.max-age` 只限制被截获的头能有多旧，伪造者把它设成现在即可。服务能被访问之前，先建好这道边界（[网关后面的服务](./deployment.md#services-behind-the-gateway)，[信任边界](../guide/security-model.md#trust-boundaries)）。

## 网关和服务各自再决策一次 {#a-gateway-and-services-that-decide-again}

网关认证并决策，转发凭证（`cosec.gateway.identity.forward-authorization: true`，只发往 `lb://` 路由和标记了 `cosec-identity: true` 的路由），每个服务运行完整的 Pipeline。

| | |
|---|---|
| 运行 | 如上的网关，以及 `enforce` 模式的服务 |
| 设置 | 每个服务：网关的 `cosec.jwt.secret`、Redis、相同的 `cosec.key-prefix`，以及它自己的 `cosec.authorization.scope.app-id` |
| 连到 | 网关和服务：Redis |
| 靠什么成立 | 每个进程共有的密钥（见上），以及前缀一致 |
| CoSec 会检查 | 跨进程什么都不检查 |
| CoSec 不检查 | 网关和服务是否持有相同的密钥或前缀 |

密钥不同，在服务上表现为 `401` `token-invalid`；前缀不同，表现为 `503`，因为服务读不到平台文档。文档、动作名和两次决策见[网关拓扑](../scenarios/gateway.md)。

## 校验另一个服务所签发令牌的服务 {#a-service-that-verifies-tokens-another-service-issues}

签发方旁边的资源服务器：校验签发方的令牌，在自己的代码里决策。

| | |
|---|---|
| 运行 | 一个 `enforce` 模式的应用，带 `cosec-cocache`，不登录 |
| 设置 | 签发方的 `cosec.jwt.secret` 和 `cosec.jwt.algorithm`；**签发方 Redis** 的 `spring.data.redis.*` 和签发方的 `cosec.key-prefix`；自己的、放在内存里的平台文档 |
| 连到 | 签发方的 Redis |
| 靠什么成立 | 签发方的密钥，以及读取签发方的吊销记录 |
| CoSec 会检查 | 启动时的三个令牌存储；读取器是 `RevocationReader.NONE` 时的一条 `WARN` |
| CoSec 不检查 | 前缀是不是签发方的：前缀不同会读到一张空表，已吊销的令牌被接受，而且没有任何提示 |

[校验另一个服务所签发令牌的服务](../scenarios/resource-server.md)里有文档和示例。

## 登录服务 {#a-login-service}

签发令牌的应用（`cosec-login` 和 `cosec-jwt` 随 starter 一起提供）。

| | |
|---|---|
| 运行 | 一个带 `cosec.authentication.login.*` 的应用，它可以提供刷新和登出路由 |
| 设置 | `cosec.jwt.*`，以及存储用的 `cosec-cocache`；在网关后面时设置 `cosec.jwt.enabled=true` |
| 连到 | Redis（它写吊销记录和会话） |
| 靠什么成立 | **签名密钥，它是每个账号的信任根** |
| CoSec 会检查 | 密钥是否存在、令牌存储，以及启动时的刷新和登出路由 |
| CoSec 不检查 | 网关和各校验方是否持有相同的密钥 |

见[认证](../integration/authentication.md)和[你的应用提供什么](../integration/clients.md#what-your-application-serves)。

## 控制面 {#the-control-plane}

文档的唯一写入方：变更在发布之前先校验、影子验证并审批。它以两种部署之一运行，也只有这两种。两者运行的是同一份代码，区别在于控制面在哪里，以及谁来调用它。

### 网关、控制面宿主和 IAM {#control-plane-host}

控制面是自己的一个进程，即网关之后的 `cosec-control` 镜像。IAM 服务是它的一个调用方，和你放行的其他服务一样。

| | |
|---|---|
| 运行 | 上述网关；[`cosec-control` 镜像](./deployment.md#the-control-plane-host)；以及你的 IAM 服务，它签发令牌，并在自己要做变更时调用宿主的服务端口 |
| 设置 | 宿主上：`cosec.pipeline.identity.only-trusted-callers-can-reach=true`（没有它镜像不会启动）、可以写文档的 Redis 账户，以及每个端口一条只放行其调用方的网络策略；网关上：把 `/cosec/control/**` 路由到宿主，并带 `cosec-app-id: cosec-control`；用 `cosec-migrate bootstrap` 指定第一个管理员 |
| 连到 | Redis（写），变更日志发往 Kafka 时还有 Kafka；IAM 服务连到宿主的服务端口 |
| 靠什么成立 | **放行网关和你点名的服务的网络**，以及对 Redis 的写权限。宿主不持有签名密钥：它不校验令牌 |
| CoSec 会检查 | 声明已写；声明写对了（有服务端口时只写网关那条会启动失败）；镜像没有开启凭证校验、登录或 API key；每个控制动作，由策略针对调用方声称的主体决策 |
| CoSec 不检查 | 声明是否属实；被放行到服务端口的服务是否只声明它自己所代表的主体 |

第一份平台文档仍然来自 `cosec-migrate publish`；它安装之前宿主不就绪。运维人员的调用经网关到达，网关已校验他们的令牌并转发身份。服务通过 `HttpControlService`（`cosec-control-http`，`cosec.control.service-url`）调用服务端口，它为每次调用写出 `CoSec-Identity` 请求头（[从代码中修改策略](../integration/checks.md#changing-policies-from-your-code)）。

### 网关和内嵌控制面的 IAM {#control-plane-embedded}

控制面是 IAM 服务的一个库，IAM 服务是网关之后你自己的应用。没有控制面宿主。

| | |
|---|---|
| 运行 | 上述网关；一个带 `cosec-control` 和 `cosec-cocache` 的 IAM 应用，处于 `enforce`，设有 `cosec.pipeline.caller=forwarded-identity`，它在自己的代码里通过 [`ControlService`](../integration/checks.md#changing-policies-from-your-code) 调用控制面 |
| 设置 | `cosec.pipeline.identity.only-gateway-can-reach=true`；可以写文档的 Redis 账户；面向运维人员时，把 `/cosec/control/**` 路由到 IAM 服务，并带 `cosec-app-id: cosec-control`；没有人通过 HTTP 调用控制 API、代码只在进程内访问它时设 `cosec.control.http.enabled=false` |
| 连到 | Redis（写），变更日志发往 Kafka 时还有 Kafka |
| 靠什么成立 | **作为唯一入口的网关**、IAM 服务自己的代码，以及对 Redis 的写权限：该进程里的代码持有存储的写账户，它声明的行动主体会被相信 |
| CoSec 会检查 | 声明，以及隐藏方法过滤器；每个控制动作，由策略针对调用所声明的主体决策，并记为该主体的一步，来自代码的调用在 `via.method` 中记为 `in-process` |
| CoSec 不检查 | 声明是否属实；IAM 服务自己的路由：在以身份确定调用方的宿主上，Web 适配器不对请求做决策，所以它们要么在网关处授权，要么没有授权 |

需要第二个人审批的变更会停在 `AWAITING_APPROVAL`，无论谁提出：由人在运维人员的路由上、经网关批准。IAM 服务的每个实例都运行控制面的维护任务（回收修订、关闭影子窗口、对账应用列表和暂存列表），并像控制面宿主那样由租约隔开：可以有任意多个实例运行，每个任务同一时刻只有一个实例在做。

### 控制面信任什么，不信任什么 {#what-the-control-plane-trusts}

两种部署都相信它们收到的 `CoSec-Identity` 请求头，而这个请求头不带签名（`STA-9`）。所以能连到宿主或 IAM 服务的人，就是他们声称的任何人，平台管理员也不例外，并且能发布任何租户的任何策略。控制面仍然决策并记录每次调用：每次调用都由策略针对其声明的主体决策，每一步都记录这个身份是被声明的，连同凭证的方式和 id、请求的 id，以及请求所经连接的地址（[控制面变更日志](./observability.md#control-plane-change-log)）。它不能防御一个能连到它的、已被攻破的工作负载，后者还能批准自己的变更。**网络才是边界。** 这就是宿主在运维人员声明什么能连到它之前不启动的原因，也是网关、或任何被放行到服务端口的服务一旦被攻破，就等于控制面被攻破的原因。给请求头签名计划在 8.1 提供。

两种部署中，对平台文档、平台租户的文档或控制应用的文档的变更，如果会把发布者挡在控制面之外，都是 `422`，除非发布请求带 `"lockout": true`（检查 `V4`）。

已被确认的锁定，或检查看不到的锁定，由持有存储凭证的人用 `cosec-migrate restore` 恢复（[从被锁在外面中恢复](./storage.md#recovering-from-a-lock-out)）。

见[控制面](../policies/testing.md#the-control-plane)、[存储](./storage.md#publishing-documents)和[之后修改文档](../guide/quick-start.md#change-a-document-later)。

**下一步：**运行网关镜像及其清单，见[部署](./deployment.md)。
