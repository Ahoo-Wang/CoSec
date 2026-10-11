---
layout: home
hero:
  name: CoSec
  text: 面向 Spring 的多租户响应式安全框架
  tagline: IAM 风格的策略与基于角色的权限，每个请求都按“拒绝优先”求值。
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
    details: 用 JSON 编写策略，支持路径动作、可组合的条件和限流。任何一层的拒绝都优先于允许，未匹配的请求一律拒绝。
    link: /zh/guide/policies
  - icon: 🏢
    title: 多租户与角色权限
    details: 主体可感知租户；按应用定义权限目录，并按空间授予角色。
    link: /zh/guide/policies#app-permissions-and-roles
  - icon: ⚡
    title: 响应式且高性能
    details: 基于 Project Reactor，单次授权约 2 µs，不阻塞事件循环。
    link: /zh/architecture/overview#performance
  - icon: 🔌
    title: WebFlux、MVC 与 Gateway
    details: 三种适配器共用同一条安全处理流程，响应行为完全一致。
    link: /zh/guide/web-integration
  - icon: 🔑
    title: JWT 与社交登录
    details: 支持签发、刷新与立即吊销令牌，并通过 JustAuth 支持 OAuth 登录。
    link: /zh/guide/authentication
  - icon: 📊
    title: 可审计
    details: 每次授权决策都会产生审计事件和 OpenTelemetry Span，并记录做出决定的策略或权限。
    link: /zh/guide/operations#auditing
---
