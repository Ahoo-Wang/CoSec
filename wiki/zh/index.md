---
layout: home
hero:
  name: CoSec
  text: 面向 Spring 的多租户授权框架
  tagline: 策略是有版本的文档，每个请求都由纯函数引擎按“拒绝优先”做出决策。
  actions:
    - theme: brand
      text: 开始使用
      link: /zh/guide/introduction
    - theme: alt
      text: 快速开始
      link: /zh/guide/quick-start
    - theme: alt
      text: GitHub
      link: https://github.com/Ahoo-Wang/CoSec
features:
  - icon: 🛡️
    title: 策略即数据
    details: 平台、每个应用、每个租户各有一份 JSON 文档。无论来自哪里，拒绝都优先于允许；未匹配的请求一律拒绝；读不到的数据永远不会带来放行。
    link: /zh/policies/language
  - icon: 🏢
    title: 多租户与角色权限
    details: 主体属于租户，角色在应用内按空间生效，权限目录通过绑定授予角色。
    link: /zh/policies/role-permissions
  - icon: 🧪
    title: 先评审，再生效
    details: 控制面检查并编译每个变更，在影子窗口中用真实流量试验，授予超出作者权限的内容时要求另一个人审批，并以修订的形式发布，可以 diff 和回滚。
    link: /zh/policies/testing
  - icon: ⚡
    title: 快速且纯粹
    details: 编译后的引擎不做 I/O，基于内存中的快照求值。一个请求走完整条 Pipeline 不到 2 µs，不阻塞事件循环。
    link: /zh/architecture/overview#performance
  - icon: 🔌
    title: WebFlux、MVC 与 Gateway
    details: 三种适配器共用同一条协程 Pipeline，应答逐字节一致。
    link: /zh/integration/web
  - icon: 🔑
    title: JWT 与社交登录
    details: 支持签发、刷新令牌，吊销检查始终开启；为机器客户端提供 API key；并通过 JustAuth 支持 OAuth 登录。
    link: /zh/integration/authentication
  - icon: 📊
    title: 可审计
    details: 每个请求都产生一个决策事件，记录做出决策的语句和绑定以及读取的文档修订，供审计日志、指标、OpenTelemetry 和 Kafka 使用。
    link: /zh/operations/observability
---
