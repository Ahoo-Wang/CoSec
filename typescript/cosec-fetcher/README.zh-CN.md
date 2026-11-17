# `@ahoo-wang/fetcher-cosec`

面向 Fetcher 的 CoSec 请求归属与可选 JWT 认证。服务端契约要求 CoSec 设备、请求、
空间或 Bearer Token 行为时使用。

**对接的 CoSec 版本。** 6.x 对接 CoSec 7.x。下一个主版本**只对接 CoSec 8.0**：CoSec 7
服务端请使用 6.x。见[升级](#从-6x-升级)。

## 安装

```bash
pnpm add @ahoo-wang/fetcher @ahoo-wang/fetcher-eventbus \
  @ahoo-wang/fetcher-storage @ahoo-wang/fetcher-cosec
```

Peer 依赖：`fetcher`、`fetcher-eventbus` 和 `fetcher-storage`。

需要 Node `^22.12.0 || ^24.0.0 || >=26.0.0`，即 nanoid 6 支持的 Node 版本，并从 22 系列中首个支持 `require()` 加载 ES 模块的版本起：nanoid 仅提供 ES 模块，CommonJS 入口（`require`）以这种方式加载它。

## 示例

```ts
import { Fetcher } from '@ahoo-wang/fetcher';
import { CoSecConfigurer, sameOriginTrust } from '@ahoo-wang/fetcher-cosec';

const api = new Fetcher({ baseURL: 'https://api.example.com' });

const cosec = new CoSecConfigurer({
  isTrusted: sameOriginTrust,
  onUnauthorized: () => window.location.assign('/login'),
  onForbidden: async () => console.error('Access denied'),
});

cosec.applyTo(api);
```

该最小配置只添加归属请求头，不启用认证。提供 `TokenRefresher` 后会启用令牌存储、
Bearer 注入与自动刷新。刷新请求使用独立、未配置 CoSec 的 Fetcher。

`isTrusted: sameOriginTrust` 让令牌与 CoSec 请求头（含设备 ID）只发往 `baseURL`
所在源与页面自身的源。不设 `isTrusted` 时，发往任意源绝对 URL 的请求都会收到访问令牌。

## 核心能力

- 设备、请求、空间、租户与所有者归属。
- JWT 解析、持久化、登录、退出与当前用户读取。
- 合并并发自动刷新，并防止递归刷新；刷新保持在 CoSec 8.0 的 `rotation-leeway`
  之内（响应丢失的刷新，只在服务端仍会应答时才用同一对 token 重试）。
- CoSec 8.0 的 problem 类型（`application/problem+json`）：只有 `token-expired`
  的 401 才刷新；`token-revoked` 与 `token-invalid` 结束会话；`429` 绝不让用户
  退出登录；`scope-rejected` 有自己的回调。
- 可配置的 401、403 与 `scope-rejected` 回调，并接收 problem。
- 用 `isTrusted` / `sameOriginTrust` 按源决定是否携带凭据。
- 为自定义管线提供独立拦截器。

切勿记录、嵌入或提交真实令牌。

## CoSec 8.0 应用必须允许什么

CoSec 不提供登录或刷新端点；应用编写的端点必须：

- 允许**匿名调用方**访问刷新路由：客户端发送的是已过期的访问令牌，CoSec 把它当作
  匿名（否则用户在第一次过期时就会被退出登录）；
- 拒绝刷新时回答 **`401`**（只有 `401` 结束会话；`429`、`5xx` 与网络错误保留会话），
  无法判断时回答 `503`；
- 客户端跨源时，在 CoSec 之前配置 CORS 过滤器，允许 `CoSec-Device-Id`、
  `CoSec-Space-Id`、`CoSec-Request-Id` 与 `Authorization`，并暴露 `CoSec-Request-Id`、
  `RateLimit-*` 与 `Retry-After`。

把 `rotationLeeway` 设为至多等于服务端的 `cosec.jwt.session.rotation-leeway`（默认 30
秒）。同一会话的各标签页必须共享一个存储和一把锁：带 Web Locks 的 `localStorage`，
或用 `new TokenStorage({ sharedAcrossTabs: true })` 声明的其他存储。使用
`sessionStorage` 或每个标签页各自的内存存储时，只在一个地方刷新。细节见
[实战文档](https://fetcher.ahoo.me/zh/guides/integrations/cosec)。

## 从 6.x 升级

- `appId` 已弃用并被忽略：CoSec 8.0 不读取 `CoSec-App-Id`，应用是令牌的 `aud`。
  `CoSecHeaders.APP_ID` 已移除。
- `AuthorizeResult` 与 `AuthorizeResults` 已移除：CoSec 8.0 不会发送它们。
- 只有刷新路由返回 `401` 才结束会话；`400`、`403`、`404` 或 `429` 现在会保留会话
  （`RefreshUnavailableError`）。刷新路由用其他状态拒绝时，传入 `isRefreshRejected`。
- `token-revoked` 与 `token-invalid` 不再刷新，并移除已存的那对 token。
- `403 scope-rejected` 交给 `onScopeRejected`（没有时交给 `onForbidden`）。回调的第二个
  参数是 CoSec problem。
- 刷新请求在 10 秒后被掐断（`CoSecTokenRefresher` 的 `timeout` 选项）。

## 文档

- [CoSec 认证实战](https://fetcher.ahoo.me/zh/guides/integrations/cosec)
- [CoSec 参考](https://fetcher.ahoo.me/zh/reference/cosec)

[English](./README.md) · [许可证](../../LICENSE)
