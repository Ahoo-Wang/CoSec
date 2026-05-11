---
layout: home
hero:
  name: CoSec
  text: 多租户响应式安全框架
  tagline: 基于 RBAC 和策略的 JVM 访问控制框架，灵感来自 AWS IAM
  actions:
    - theme: brand
      text: 快速开始
      link: /zh/getting-started/overview
    - theme: alt
      text: 深入了解
      link: /zh/deep-dive/architecture/security-model
    - theme: alt
      text: GitHub
      link: https://github.com/Ahoo-Wang/CoSec
features:
  - icon: 🛡️
    title: 基于策略的授权
    details: AWS IAM 启发的拒绝优先评估引擎。可组合的策略，支持 ActionMatcher 和 ConditionMatcher SPI 扩展。
    link: /zh/deep-dive/authorization/authorization-flow
  - icon: ⚡
    title: 全响应式
    details: 基于 Project Reactor 构建，Mono 链式认证。集成 WebFlux、WebMvc 和 Spring Cloud Gateway。
    link: /zh/deep-dive/architecture/reactive-design
  - icon: 🏢
    title: 原生多租户
    details: 一流的租户支持，TenantPrincipal、租户范围策略，以及基于空间的角色权限。
    link: /zh/deep-dive/architecture/multi-tenancy
  - icon: 🔑
    title: JWT 与社交认证
    details: JWT 令牌生命周期管理（含刷新令牌），通过 JustAuth 支持 20+ 社交登录提供商。
    link: /zh/deep-dive/authentication/authentication-system
  - icon: 🔌
    title: 可扩展 SPI
    details: 基于 Java ServiceLoader 的扩展点，支持自定义 ActionMatcher 和 ConditionMatcher 实现。
    link: /zh/deep-dive/extending/custom-matchers
  - icon: 📊
    title: OpenTelemetry 链路追踪
    details: 分布式追踪，记录授权决策、匹配策略和角色的详细 span 属性。
    link: /zh/deep-dive/integrations/opentelemetry
---

## 快速开始

```kotlin
// build.gradle.kts
implementation("me.ahoo.cosec:cosec-spring-boot-starter:<version>")
```

```yaml
# application.yaml
cosec:
  jwt:
    algorithm: hmac256
    secret: your-secret-key-here
  authorization:
    local-policy:
      enabled: true
      init-repository: true
```

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Kotlin | 2.3.20 | 主要语言 |
| Java | 17 | 目标 JVM |
| Spring Boot | 4.0.5 | 应用框架 |
| Project Reactor | - | 响应式编程 |
| JWT (auth0) | 4.5.1 | 令牌处理 |
| CoCache | 4.0.2 | 分布式缓存 |
| JustAuth | 1.16.7 | 社交认证 |
| OpenTelemetry | 2.26.1 | 可观测性 |
