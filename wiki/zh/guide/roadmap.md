---
title: "路线图"
description: "CoSec 8.0 改变什么、发布前处于什么状态，以及 7.x 用户现在该做什么。"
---

# 路线图 {#roadmap}

面向正在使用 CoSec 7.x 或正在评估 CoSec、想知道 8.0 是什么以及何时可以发布的团队。本页只写你可以依赖什么。按 PR 的进度见贡献者用的
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

上述架构已经合入 `main`，7.x 的代码已经移除。当前的预览版是 `8.0.0-beta.5`。它把控制面作为网关旁边自己的一个镜像发布，给出运行它的两种方式（[选择部署方式](../operations/topologies.md#the-control-plane)），并带上了三轮审查的修复；系统测试覆盖这两种部署（[系统测试](../operations/deployment.md#the-system-test)）。下一个是 `8.0.0-beta.6`，带来控制面的类型化 Kotlin API，之后再做两轮审查并修复其发现，然后是候选发布版。按 PR 的状态见 [`docs/roadmap.md`](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/roadmap.md)，由它负责跟踪；本页只写你可以依赖什么。

## 发布策略 {#release-policy}

- **8.0 的预发布版（alpha 和 beta，最新为 `8.0.0-beta.5`）是预览版，不承诺兼容性。** 公共 API、`cosec.*` 属性、文档与线上格式（`schema: 2`、`CoSec-Identity`）
  以及授权语义，在之后的任何 alpha 或 RC 中都可能改变，预览版之间不提供迁移路径。请在生产以外试用，暂时不要在它上面构建。
- 候选发布版要等上述检查完成：对整个系统的两轮审查（RC 安全审查，然后按模块审查）及其发现全部修复，以及 ABI 校验（GA 时冻结公共 API，RFC 0011 D4）。此后 8.0 才遵循语义化版本。
- **8.0 不比 7.0 慢。** 性能预算以 7.0 的实测数字设定：编译执行的授权吞吐与 Pipeline 吞吐不低于 7.0 的 100 %，分配不高于 7.0
  （RFC 0008 §3.8）。预算先在端到端切片上测量；在从 7.x 切换的变更合入之前，这些预算已经达标。

## 给 7.x 用户 {#for-7-x-users-today}

- `7.x` 接收安全和关键修复，直到 8.0 GA 之后 6 个月；`release/6.1.x` 只接收安全修复
  （[`CONTRIBUTING.md`](https://github.com/Ahoo-Wang/CoSec/blob/main/CONTRIBUTING.md)）。
- 最新的修复是 [7.0.2](https://github.com/Ahoo-Wang/CoSec/releases/tag/v7.0.2)：策略解码器现在拒绝格式错误的策略
  （未知字段、应为列表处写了标量、重复键），不再把它们编译成范围更大的策略。升级前先读它的升级说明：已存入 Redis 的策略在重新发布之前，可能让请求失败。
- 最新的安全公告是
  [GHSA-2293-jx8h-56m8](https://github.com/Ahoo-Wang/CoSec/security/advisories/GHSA-2293-jx8h-56m8)，已在 7.0.1 与 6.1.2 中修复。
- 现在该做的：生产环境继续用 7.0.2，即最新的 7.0.x。想评估 8.0，在测试环境中试用最新的预发布版（`8.0.0-beta.5`，需要 Java 21），配合[升级](../operations/upgrading.md)；
  RC 之前还会有改动。

## 如何跟进 {#how-to-follow}

- [RFC 索引](https://github.com/Ahoo-Wang/CoSec/tree/main/docs/rfc)：设计、决定与待决问题。
- [`docs/roadmap.md`](https://github.com/Ahoo-Wang/CoSec/blob/main/docs/roadmap.md)：按 PR 的进度与审查轮次。
- GitHub 上的 [Issues](https://github.com/Ahoo-Wang/CoSec/issues) 与 [Pull Requests](https://github.com/Ahoo-Wang/CoSec/pulls)。
