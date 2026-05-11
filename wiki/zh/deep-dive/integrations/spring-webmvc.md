---
title: Spring WebMVC 集成
description: CoSec 如何通过 jakarta.servlet.Filter、请求解析和线程本地安全上下文与 Spring MVC Servlet 应用集成。
---

# Spring WebMVC 集成

CoSec 通过 `jakarta.servlet.Filter` 实现为传统 Spring MVC 应用提供基于 Servlet 的集成路径。Servlet 集成与响应式 WebFlux 集成类似，但使用线程本地上下文传播代替 Reactor Context。

## 架构概览

```mermaid
graph TD
    A["Incoming HTTP Request"] --> B["AuthorizationFilter<br>(jakarta.servlet.Filter)"]
    B --> C["AbstractAuthorizationInterceptor<br>(base class)"]
    C --> D["ServletRequestParser"]
    D --> E["CoSecServletRequest"]
    C --> F["SecurityContextParser"]
    F --> G["SecurityContext"]
    C --> H["Authorization.authorize()"]
    H --> I{Authorized?}
    I -->|Yes| J["FilterChain.doFilter()"]
    I -->|No| K["HTTP 401 / 403 Response"]
    J --> L["SecurityContextHolder<br>(ThreadLocal propagation)"]
    J --> M["ServletRequests<br>(request attribute propagation)"]

    style A fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style B fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style C fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style D fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style E fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style F fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style G fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style H fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style I fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style J fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style K fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style L fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style M fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

```

## 核心组件

### AuthorizationFilter

Servlet 过滤器入口点。它实现了 `jakarta.servlet.Filter` 并扩展了 `AbstractAuthorizationInterceptor`，对每个传入请求执行授权检查。

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant Filter as AuthorizationFilter
    participant Interceptor as AbstractAuthorizationInterceptor
    participant RParser as ServletRequestParser
    participant SCParser as SecurityContextParser
    participant Auth as Authorization
    participant Chain as FilterChain

    Client->>Filter: doFilter(request, response, chain)
    Filter->>Interceptor: authorize(httpRequest, httpResponse)
    Interceptor->>RParser: parse(servletRequest)
    RParser-->>Interceptor: CoSecServletRequest
    Interceptor->>SCParser: parse(request)
    Note over Interceptor,SCParser: TokenVerificationException caught<br>falls back to anonymous context
    SCParser-->>Interceptor: SecurityContext
    Interceptor->>Auth: authorize(request, securityContext).block()
    Auth-->>Interceptor: AuthorizeResult
    alt Authorized
        Interceptor-->>Filter: true
        Filter->>Chain: chain.doFilter(request, response)
    else Not Authenticated
        Interceptor-->>Filter: false
        Filter-->>Client: HTTP 401 UNAUTHORIZED
    else Forbidden
        Interceptor-->>Filter: false
        Filter-->>Client: HTTP 403 FORBIDDEN
    end



```

`AuthorizationFilter.doFilter` 的关键行为：

1. **委托**给 `AbstractAuthorizationInterceptor.authorize()`。
2. **捕获** `TooManyRequestsException` 并返回 HTTP 429。
3. **捕获**意外异常，记录错误日志并返回 HTTP 500。
4. 授权成功后，调用 `chain.doFilter(request, response)`。

### AbstractAuthorizationInterceptor

包含授权算法的基类。它与 `ReactiveSecurityFilter` 中的逻辑保持一致 -- 此处的任何更改都应同步到响应式对应类中。

`authorize()` 方法：

1. 通过 `ServletRequestParser` 将 Servlet 请求解析为 CoSec `Request`。
2. 通过 `SecurityContextParser` 解析 `SecurityContext`，捕获 `TokenVerificationException`。
3. 将上下文存储到 `SecurityContextHolder`（线程本地）和请求属性中。
4. 设置 `X-Request-Id` 响应头。
5. 调用 `authorization.authorize()` 并阻塞等待结果（`.block()`）。
6. 如果被拒绝返回 `false`，如果被允许返回 `true`。

### ServletRequestParser

将 `jakarta.servlet.http.HttpServletRequest` 转换为 `CoSecServletRequest`，提取路径（通过 `servletPath`）、方法、远程 IP、来源、引用页和请求 ID。同时应用已注册的 `RequestAttributesAppender` 实例。

### CoSecServletRequest

包装 `HttpServletRequest` 的不可变数据类。实现了 CoSec 的 `Request` 接口和 `Delegated<HttpServletRequest>`，提供对底层 Servlet 请求中 headers、查询参数和 cookies 的访问。

### InjectSecurityContextFilter

`ReactiveInjectSecurityContextWebFilter` 的 Servlet 对应版本。专为已执行授权的 API 网关背后的下游服务设计。它使用 `SecurityContextParser.ensureParse()` 从请求头中提取安全上下文，无需令牌验证。

```mermaid
graph TD
    subgraph "Gateway Service"
        A["AuthorizationFilter<br>(verifies JWT + authorizes)"]
    end
    subgraph "Downstream Service"
        B["InjectSecurityContextFilter<br>(injects context from headers)"]
        B --> C["SecurityContextHolder.setContext()"]
        B --> D["ServletRequests.setSecurityContext()"]
    end
    A -->|"X-Request-Id, Authorization headers"| B

    style A fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style B fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style C fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style D fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

```

### SecurityContextHolder

当前安全上下文的线程本地持有者。使用 `InheritableThreadLocal` 使子线程继承父线程的上下文。提供静态方法 `setContext()`、`context`、`requiredContext` 和 `remove()`。

## 上下文传播

与使用 Reactor `Context` 的响应式集成不同，Servlet 集成使用两个并行通道：

| 通道 | 机制 | 作用域 |
|---------|-----------|-------|
| `SecurityContextHolder` | `InheritableThreadLocal` | 当前线程和子线程 |
| `HttpServletRequest` 属性 | `request.setAttribute()` | 当前请求生命周期 |

两者都在 `AbstractAuthorizationInterceptor.authorize()` 中设置，因此下游代码可以通过任一机制访问安全上下文。

## 参考资料

- [cosec-webmvc/src/main/kotlin/me/ahoo/cosec/servlet/AuthorizationFilter.kt:42](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-webmvc/src/main/kotlin/me/ahoo/cosec/servlet/AuthorizationFilter.kt#L42) -- Servlet 过滤器入口
- [cosec-webmvc/src/main/kotlin/me/ahoo/cosec/servlet/AbstractAuthorizationInterceptor.kt:51](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-webmvc/src/main/kotlin/me/ahoo/cosec/servlet/AbstractAuthorizationInterceptor.kt#L51) -- 包含授权逻辑的基类拦截器
- [cosec-webmvc/src/main/kotlin/me/ahoo/cosec/servlet/ServletRequestParser.kt:31](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-webmvc/src/main/kotlin/me/ahoo/cosec/servlet/ServletRequestParser.kt#L31) -- 请求解析
- [cosec-webmvc/src/main/kotlin/me/ahoo/cosec/servlet/CoSecServletRequest.kt:22](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-webmvc/src/main/kotlin/me/ahoo/cosec/servlet/CoSecServletRequest.kt#L22) -- 请求数据类
- [cosec-webmvc/src/main/kotlin/me/ahoo/cosec/servlet/InjectSecurityContextFilter.kt:40](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-webmvc/src/main/kotlin/me/ahoo/cosec/servlet/InjectSecurityContextFilter.kt#L40) -- 下游上下文注入
- [cosec-core/src/main/kotlin/me/ahoo/cosec/context/SecurityContextHolder.kt:26](https://github.com/Ahoo-Wang/CoSec/blob/main/cosec-core/src/main/kotlin/me/ahoo/cosec/context/SecurityContextHolder.kt#L26) -- 线程本地上下文持有者

## 相关页面

- [Spring WebFlux 集成](./spring-webflux.md)
- [Spring Cloud Gateway 集成](./spring-cloud-gateway.md)
- [自动配置](../extending/auto-configuration.md)
- [测试](../operations/testing.md)
