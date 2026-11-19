---
title: "选择部署方式"
description: "运行 CoSec 的几种方式：一个自己执行授权的服务、网关加其后的服务、校验另一个服务所签发令牌的服务、登录服务和控制面。每种方式运行什么、需要什么、必须连到哪里、靠什么保证安全。"
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
| [控制面](#the-control-plane) | 一个应用 | | | 持有 `(platform)` 令牌的人，以及对 Redis 的写权限 |

## 每种方式都共有的 {#what-every-topology-shares}

- **Redis 是权威存储**，保存文档、吊销记录和会话，要求 `maxmemory-policy noeviction`、持久化、副本和备份（[Redis 要求](./storage.md#redis-requirements)）。所有读写 CoSec 键的进程，每个环境用同一个 `cosec.key-prefix`。
- **`cosec.jwt.secret` 既签名又校验。** HMAC 是对称的：**每个持有该密钥的进程，都能为任意租户、主体和角色签发令牌**，包括控制面的 `(platform)` 管理员。“只校验”的服务，对任何拿到它配置的人来说就是签发方。只把密钥交给你愿意让它签发令牌的进程，放在密钥库里，把泄露当作所有账号的泄露。轮换密钥会让用旧密钥签发的每个令牌失效：实例切换的那一刻，持有另一个密钥的实例上每个请求都是 `401` `token-invalid`，所有会话都要重新登录。每个部署只有一个密钥，没有密钥 id，所以滚动重启期间是混合状态；请在安静的时段轮换，并预期所有人会被登出。
- **starter 在启动时检查模块**，缺什么就在失败信息里写出制品名（[模块](../guide/introduction.md#modules)，STA-1）。

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
| 设置 | 网关：`cosec.jwt.secret`、Redis、路由；服务：`cosec.pipeline.mode=gateway`，别无其他（没有 Redis，没有密钥） |
| 连到 | 网关：Redis 和各服务；服务：CoSec 方面什么都不连 |
| 靠什么成立 | **只靠网络** |
| CoSec 会检查 | 服务启动时的一条 `WARN`；网关主机设置 `gateway` 模式时启动失败；每个请求中，无法读取、格式版本未知、太旧或太大的头会被拒绝 |
| CoSec 不检查 | 谁能访问该服务 |

这个头是无签名的 base64url JSON，没有密钥也没有签名。**`gateway` 模式的服务会相信它收到的任何 `CoSec-Identity`**：能向它发请求的人，都可以冒充平台管理员。守住边界的是网络：只放行网关 Pod 的 Kubernetes `NetworkPolicy`、私有网络，或双向 TLS。没有设置策略的命名空间（这是 Kubernetes 的默认状态）允许集群里任何 Pod 发送这个头。`cosec.pipeline.identity.max-age` 只限制被截获的头能有多旧，伪造者把它设成现在即可。服务能被访问之前，先建好这道边界（[网关后面的服务](./deployment.md#services-behind-the-gateway)，[信任边界](../guide/security-model.md#trust-boundaries)）。

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

文档的唯一写入方：变更在发布之前先校验、影子验证并审批。

| | |
|---|---|
| 运行 | 你自己的、带 `cosec-control` 和 `cosec-cocache` 的应用。没有任何 CoSec 镜像运行控制面：网关镜像不含它，所以第一次 `cosec-migrate publish` 之后，每次变更都需要这个应用 |
| 设置 | `cosec.control.*`；可以写文档的 Redis 凭证；数据面实例上设 `cosec.control.enabled=false`，它们此后只读文档；用 `cosec-migrate bootstrap` 指定第一个管理员，并有办法签发 `(platform)` 令牌 |
| 连到 | Redis（写），审计发往 Kafka 时还有 Kafka |
| 靠什么成立 | 持有 `(platform)` 管理员令牌或签名密钥的人；对 Redis 的写权限 |
| CoSec 会检查 | 模块、吊销读取器、令牌保留时间与审批过期时间、控制面的 Bean（不可替换），以及 Redis 权限失败 |
| CoSec 不检查 | 管理员是否真的能拿到令牌 |

见[控制面](../policies/testing.md#the-control-plane)、[存储](./storage.md#publishing-documents)和[之后修改文档](../guide/quick-start.md#change-a-document-later)。

**下一步：**运行网关镜像及其清单，见[部署](./deployment.md)。
