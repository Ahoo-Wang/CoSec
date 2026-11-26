---
title: "路线图"
description: "CoSec 8.0 改变什么、发布前处于什么状态，以及 7.x 用户现在该做什么。"
---

# 路线图 {#roadmap}

面向正在使用 CoSec 7.x 或正在评估 CoSec、想知道 8.0 是什么以及何时可以发布的团队。本页只写你可以依赖什么。工作的现状、候选发布版之前还差什么、哪些事留到 8.1，见贡献者用的
[`docs/roadmap.md`](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/roadmap.md)，每一项设计决定见
[RFC](https://github.com/Ahoo-Wang/CoSec/tree/main/docs/rfc)。本页与 RFC 不一致时，以 RFC 为准。

## 8.0 是什么 {#what-8-0-is}

8.0 是 CoSec 的一次彻底重新设计，没有兼容目标：它遵循目标架构，而不是 7.x 的行为
（[RFC 0008 §2](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/rfc/0008-conformance-and-direct-build.md)）。
正确性由 8.0 的规范定义，由一致性套件检查。改变的内容：

- **纯函数决策引擎。** 授权是请求与内存中策略集的函数，不做 I/O
  （[RFC 0002](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/rfc/0002-pure-engine.md)）。
- **基于绑定的唯一扁平算法**，分平台层与租户层，带成员关系与作用域，取代 7.x 中全局策略、主体策略、角色权限的分层顺序
  （[RFC 0004](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/rfc/0004-unified-model.md)）。
- **带类型的条件 AST、`service:verb` 动作与编译执行**，取代 OGNL、SpEL 与内置匹配器
  （[RFC 0005](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/rfc/0005-condition-ast.md)）。
- **策略快照**，取代每个请求读一次仓库
  （[RFC 0003](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/rfc/0003-policy-snapshot.md)）。
- **协程 Pipeline**：`Indeterminate` 决策返回 503，限流改为义务，决策监听器取代装饰器链
  （[RFC 0006](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/rfc/0006-coroutine-pipeline.md)）。
- CoSec 写的错误响应一律使用 **RFC 9457 problem+json**（RFC 0006 Q27）。
- **控制面**：修订记录、发布前的影子窗口，以及授出分析，保证任何人授出的权限不超过自己持有的
  （[RFC 0007](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/rfc/0007-control-plane.md)）。
- 网关镜像使用 **JDK AOT 缓存**，CI 在每次构建时启动它并发送请求
  （[RFC 0001 §3.10](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/rfc/0001-target-architecture.md)）。
  8.0 没有测试 Spring AOT 与原生镜像，不支持它们；计划在之后的 8.x 版本中支持。

7.x 应用必须迁移：策略、权限和配置的形状都会改变，`cosec-migrate` 把 7.x 数据转换成 8.0 的格式。[升级](../operations/upgrading.md)列出了每一项改变及其迁移方法。

## 8.0 的现状 {#where-8-0-stands}

上述架构已经合入 `main`，7.x 的代码已经移除。当前的发布版是 [`8.0.0-rc.2`](https://github.com/Ahoo-Wang/CoSec/releases/tag/v8.0.0-rc.2)，第二个候选发布版：即 8.0.0-rc.1，只是它的 npm 包同时接受 fetcher 8（[预览版之间的升级](../operations/upgrading-previews.md#from-8-0-0-rc-1)）；JVM 的模块没有变化。第一个候选版为 GA 定下了公共面。适配器契约（`RequestGate`、`HttpFacts`、`Outcome` 及它答复的问题）、测试套件的请求上下文（`PermittedRequest`、`IdentityHeader`、`CoSecTestBindings`）、身份头的写方与控制面的变更事件是稳定的；控制面的存储与影子窗口的端口是 CoSec 自己的；整个 8.0 都保持实验性的部分会标明（[稳定性等级](../architecture/extending.md#stability-levels)）。每次构建都对着上一个发布版 8.0.0-rc.1 测试，两个发布版共用一个存储。它基于 Spring Boot 4.2 与 CoCache 8.2 构建。接下来是 8.0 GA。GA 之前还差什么，见 [`docs/roadmap.md`](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/roadmap.md)，由它负责跟踪；本页只写你可以依赖什么。

## 发布策略 {#release-policy}

- **8.0 的 alpha 与 beta 是预览版，不承诺兼容性。** 公共 API、`cosec.*` 属性、文档与线上格式（`schema: 2`、`CoSec-Identity`）
  以及授权语义在它们之间都改变过，预览版之间不提供迁移路径。
- **候选发布版（最新为 `8.0.0-rc.2`）就是 8.0 要成为的样子。** 它的公共 API、`cosec.*` 属性、格式与授权语义在 GA 之前只为修复缺陷而改变，每处这样的改变都列在下一个候选版的说明里。第一个候选版在上述检查之后发布：对整个系统的两轮审查及其发现全部修复、ABI 校验、一个真实项目用过 8.0.0-beta.9，以及在它的提交上跑过的门禁，其中包括与 8.0.0-beta.9 的混跑。GA 时 ABI 校验冻结公共 API（RFC 0011 D4），自此 8.0 遵循语义化版本。
- **8.0 不比 7.0 慢。** 性能预算以 7.0 的实测数字设定：编译执行的授权吞吐与 Pipeline 吞吐不低于 7.0 的 100 %，分配不高于 7.0
  （RFC 0008 §3.8）。预算先在端到端切片上测量；在从 7.x 切换的变更合入之前，这些预算已经达标。

## 给 7.x 用户 {#for-7-x-users-today}

- `7.x` 接收安全和关键修复，直到 8.0 GA 之后 6 个月；`release/6.1.x` 只接收安全修复
  （[`CONTRIBUTING.md`](https://github.com/Ahoo-Wang/CoSec/blob/main/CONTRIBUTING.md)）。
- 最新的修复是 [7.0.2](https://github.com/Ahoo-Wang/CoSec/releases/tag/v7.0.2)：策略解码器现在拒绝格式错误的策略
  （未知字段、应为列表处写了标量、重复键），不再把它们编译成范围更大的策略。升级前先读它的升级说明：已存入 Redis 的策略在重新发布之前，可能让请求失败。
- 最新的安全公告是
  [GHSA-2293-jx8h-56m8](https://github.com/Ahoo-Wang/CoSec/security/advisories/GHSA-2293-jx8h-56m8)，已在 7.0.1 与 6.1.2 中修复。
- 现在该做的：生产环境继续用 7.0.2，即最新的 7.0.x。想评估 8.0，在测试环境中试用候选发布版（`8.0.0-rc.2`，需要 Java 21），配合[升级](../operations/upgrading.md)；
  GA 之前它只为修复缺陷而改变。

## 如何跟进 {#how-to-follow}

- [RFC 索引](https://github.com/Ahoo-Wang/CoSec/tree/main/docs/rfc)：设计、决定与待决问题。
- [`docs/roadmap.md`](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/roadmap.md)：工作的现状、候选发布版的门槛，以及留到 8.1 的事项。
- GitHub 上的 [Issues](https://github.com/Ahoo-Wang/CoSec/issues) 与 [Pull Requests](https://github.com/Ahoo-Wang/CoSec/pulls)。
