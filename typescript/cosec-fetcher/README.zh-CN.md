# `@ahoo-wang/cosec-fetcher`

面向 Fetcher 的 CoSec 请求归属与可选 JWT 认证。服务端契约要求 CoSec 设备、请求、
空间或 Bearer Token 行为时使用。

**对接的 CoSec 版本。** **只对接 CoSec 8.0**。它是 CoSec 线上契约的客户端
（[构建客户端](https://cosec.ahoo.me/zh/integration/clients)），与 CoSec 一起版本化、一起发布。CoSec 7
服务端请使用 `@ahoo-wang/fetcher-cosec` 6.x。从那个包迁移：见[升级](#从-ahoo-wangfetcher-cosec-6x-升级)。

## 安装

```bash
pnpm add @ahoo-wang/fetcher @ahoo-wang/fetcher-eventbus \
  @ahoo-wang/fetcher-storage @ahoo-wang/cosec-fetcher@beta
```

CoSec 8.0 发布之前，这个包的 `latest` 标签指向一个标为已弃用的 `0.0.0` 占位版本，所以不带标签安装得到的是占位版本和一条弃用提示，而不是客户端。安装你所运行的 CoSec 版本对应的标签：`8.0.0-beta.n` 用 `@beta`（如上），候选发布版用 `@rc`，8.0 正式发布之后不带标签；也可以写明确切版本，它在所有这些阶段都正确。标签不会越过它所指的版本线：候选发布版出现之后，`@beta` 仍是最后一个 beta，与 RC 的服务端不匹配。
它随每次 CoSec 发布而发布，版本与 CoSec 相同。

Peer 依赖：`fetcher`、`fetcher-eventbus` 和 `fetcher-storage`。

需要 Node `^22.12.0 || ^24.0.0 || >=26.0.0`，即 nanoid 6 支持的 Node 版本，并从 22 系列中首个支持 `require()` 加载 ES 模块的版本起：nanoid 仅提供 ES 模块，CommonJS 入口（`require`）以这种方式加载它。

## 示例

```ts
// cosec.ts
import { Fetcher } from '@ahoo-wang/fetcher';
import {
  CoSecConfigurer,
  CoSecTokenRefresher,
  CoSecTokenRevoker,
  type CompositeToken,
} from '@ahoo-wang/cosec-fetcher';

const baseURL = 'https://api.example.com';

// 应用自己的 Fetcher：携带令牌的那个。
export const api = new Fetcher({ baseURL });
// 第二个未配置的 Fetcher，用于刷新和登出：它从不携带 CoSec 请求头。
const bare = new Fetcher({ baseURL });

export const cosec = new CoSecConfigurer({
  tokenRefresher: new CoSecTokenRefresher({
    fetcher: bare,
    endpoint: '/auth/refresh',
  }),
  tokenRevoker: new CoSecTokenRevoker({
    fetcher: bare,
    endpoint: '/auth/logout',
  }),
  onUnauthorized: () => window.location.assign('/login'),
  onForbidden: async () => console.error('Access denied'),
});
cosec.applyTo(api);

// 登录：登录路由是应用自己的。它返回令牌对（`accessToken`、`refreshToken`），
// 放进 TokenStorage。
export async function login(username: string, password: string): Promise<void> {
  const response = await fetch(`${baseURL}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  if (!response.ok) throw new Error('Sign-in failed');
  cosec.tokenStorage.signIn((await response.json()) as CompositeToken);
}

// 登出：先在服务端结束会话，再删除这里的令牌对。
export const logout = () => cosec.tokenManager!.logout();
```

每一部分都不能少：`TokenRefresher` 启用令牌存储、Bearer 注入和自动刷新；`TokenRevoker` 让 `logout()` 在服务端结束会话；`login` 是令牌对进入 `TokenStorage` 的唯一入口（`cosec.tokenStorage.signIn`）。刷新和登出请使用上面那个独立的、未配置的 `Fetcher`。没有 `TokenRefresher` 时，配置器只添加归属请求头。

令牌、CoSec 请求头（含设备 ID）以及用来填充路径变量的租户和所有者，只发往绝对 `baseURL` 所在的源和页面自身的源：
`isTrusted` 默认是 `sameOriginTrust`。发往其他源绝对 URL 的请求（分页链接、下载地址、回调）不带任何凭据，它的 `401`
既不触发刷新也不结束会话。要信任更多源，传入自己的判断函数，对绝对 URL 它单独决定：
`isTrusted: (url, exchange) => sameOriginTrust(url, exchange) || new URL(url).origin === 'https://files.example.com'`。
在 Node 中（没有页面），只有绝对 `baseURL` 的源被信任；`baseURL` 是相对的时，绝对 URL 不带凭据。

**刷新端点在 `baseURL` 之外的另一个源**（独立的认证服务）。刷新请求用自己的 `Fetcher`（上文建议的做法）时没有任何变化：它从来不带 CoSec
请求头，令牌对在请求体里。用已配置的 `Fetcher` 并且端点是另一个源上的绝对 URL 时，刷新请求现在不带 `Authorization`（已过期的访问令牌）、
`CoSec-Device-Id`、`CoSec-Request-Id` 和 `CoSec-Space-Id`，会话仍然能刷新：令牌对在请求体里，CoSec 把过期令牌当作匿名，刷新路由不需要这个请求头。
丢掉的是这次调用上的设备 ID 和请求 ID。想保留它们，就写明那个源：
`isTrusted: (url, exchange) => sameOriginTrust(url, exchange) || new URL(url).origin === 'https://auth.example.net'`。
刷新端点在 `baseURL` 上时，刷新请求照旧带过期的 `Authorization`、`CoSec-Device-Id` 和 `CoSec-Request-Id`，不带空间。

## 核心能力

- 设备、请求、空间、租户与所有者归属。
- JWT 解析、持久化、登录、退出与当前用户读取。
- 合并并发自动刷新，并防止递归刷新；刷新保持在 CoSec 8.0 的 `rotation-leeway`
  之内（响应丢失的刷新，只在服务端仍会应答时才用同一对 token 重试）。
- CoSec 8.0 的 problem 类型（`application/problem+json`）：只有 `token-expired`
  的 401 才刷新；`token-revoked` 与 `token-invalid` 结束会话；`429` 绝不让用户
  退出登录；`scope-rejected` 有自己的回调。
- 可配置的 401、403 与 `scope-rejected` 回调，并接收 problem。
- 按源决定是否携带凭据：默认同源（`sameOriginTrust`），用自己的 `isTrusted` 改变。
- 为自定义管线提供独立拦截器。

切勿记录、嵌入或提交真实令牌。

## 登出

用户点击“登出”时调用 `logout()`：它在服务端结束会话（CoSec 的登出路由，`cosec.authentication.login.logout.path`），并在本地删除令牌对。已知会话已经结束时，或应用通过自己的登出端点结束会话时，调用 `tokenStorage.signOut()`：它只在本地忘掉令牌对，会话在服务端仍可刷新，直到它在那里结束。

```ts
import { logout } from './cosec'; // 来自上面的示例

await logout();
```

正在进行的刷新会先被等待，会话用它存下的令牌对结束。服务端结束了会话（`204`）或回答 `401`（会话已不可用）时，`logout()` 正常完成；无论哪种结果，本地的令牌对都会删除。`LogoutUnconfirmedError`（`503`、`500`、网络故障、没有 `tokenRevoker`）要求应用告诉用户：服务端可能仍持有该会话，直到它在那里结束，最迟到其刷新令牌过期。它不携带任何令牌：`problem` 和 `status` 说明服务端的回答。

## CoSec 8.0 应用必须允许什么

设置了 `cosec.authentication.login.refresh.path` 时，starter 提供刷新路由，其应答正是本客户端所依赖的（[构建客户端](https://cosec.ahoo.me/zh/integration/clients#what-your-application-serves)）；登录属于应用。应用必须：

- 允许**匿名调用方**访问刷新路由：客户端发送的是已过期的访问令牌，CoSec 把它当作
  匿名（否则用户在第一次过期时就会被退出登录）；
- 如果自己编写刷新路由，拒绝刷新时回答 **`401`**（只有 `401` 结束会话；`429`、`5xx`
  与网络错误保留会话），无法判断时回答 `503`，与提供的路由相同；
- 客户端跨源时，在 CoSec 之前配置 CORS 过滤器，允许 `CoSec-Device-Id`、
  `CoSec-Space-Id`、`CoSec-Request-Id` 与 `Authorization`，并暴露 `CoSec-Request-Id`、
  `RateLimit-*` 与 `Retry-After`。

把 `rotationLeeway` 设为至多等于服务端的 `cosec.jwt.session.rotation-leeway`（默认 30
秒）。同一会话的各标签页必须共享一个存储和一把锁：带 Web Locks 的 `localStorage`，
或用 `new TokenStorage({ sharedAcrossTabs: true })` 声明的其他存储。使用
`sessionStorage` 或每个标签页各自的内存存储时，只在一个地方刷新。细节见
[实战文档](https://fetcher.ahoo.me/zh/guides/integrations/cosec)。

## 上报错误

本包在刷新或退出时抛出的错误（`RefreshTokenError`、`RefreshUnavailableError`、`RefreshSessionChangedError`、
`LogoutUnconfirmedError`）不带令牌：刷新或退出请求的请求体就是令牌对，所以不保留该请求的错误。它们的 `cause` 是一个普通的
`Error`，只有那个错误及其每一层原因的名字和消息，`problem` 和 `status` 说明服务端回答了什么。

应用自己请求的错误（`FetcherError`，以及包着上述错误之一的 `ExchangeError`）仍然带着该请求的 `exchange`：请求头，所以包含
`Authorization` 里的访问令牌，以及请求体。把错误发给上报工具（Sentry、日志管道）的应用要先清理 `exchange`，或者只上报
`name`、`message` 和 `problem`。

## 从 `@ahoo-wang/fetcher-cosec` 6.x 升级

- **凭据默认只发往受信任的源。** 不设 `isTrusted` 时，6.x 会把令牌、`CoSec-Device-Id`、`CoSec-Request-Id` 和 `CoSec-Space-Id`
  发往每个绝对 URL，并用令牌填充 `{tenantId}` 和 `{ownerId}`。现在只有绝对 `baseURL` 所在的源和页面自身的源会得到它们。
  会坏掉的是：依赖令牌的、发往第三个源的调用（另一台主机上的第二个 API、校验令牌的 CDN）现在不带令牌，得到那个源的 `401`。
  迁移方式：用判断函数写明那个源，
  `isTrusted: (url, exchange) => sameOriginTrust(url, exchange) || new URL(url).origin === 'https://files.example.com'`。
  确实要发往所有源的代码写 `isTrusted: () => true`，并自己承担这个决定。在 Node 里没有绝对 `baseURL` 时，绝对 URL 的调用同样不带凭据。

- 包名改为 `@ahoo-wang/cosec-fetcher`：修改 `package.json` 和每一处导入。React hooks 移到了 `@ahoo-wang/cosec-react`。
- `appId` 已移除（`CoSecConfig.appId`、`CoSecRequestOptions.appId`、`AppIdCapable`）：CoSec 8.0 不读取 `CoSec-App-Id`，
  应用是令牌的 `aud`。`CoSecHeaders.APP_ID` 已移除。只是把没人用的选项合在一起的 `CoSecOptions` 同样移除，
  `TokenStorage.setCompositeToken` 也移除：用 `signIn`。
- `RefreshTokenError` 和 `RefreshUnavailableError` 不再携带令牌对（`token` 属性和第一个构造参数）：序列化错误自有属性的错误上报工具
  会把两个令牌都发出去。它们的 `cause` 类型是 `unknown`；`JwtPayload` 的索引签名和 `CoSecJwtPayload.attributes` 也是 `unknown`：
  读取类型化声明之外的声明时用类型守卫。
- `JwtTokenManager.refreshBlocked` 和 `refreshInProgress` 变为私有。
- `parseJwtPayload` 无法解析令牌时不再打印日志（返回 `null`）。
- 包在同一页面的实例之间共享的两个 `Symbol.for` 键现在是 `@ahoo-wang/cosec-fetcher/shared-token-serializers` 和
  `@ahoo-wang/cosec-fetcher/notification-guard-refresher`。迁移期间两个包可以在同一页面上：它们不再共享注册表或标记。
- `AuthorizeResult` 与 `AuthorizeResults` 已移除：CoSec 8.0 不会发送它们。
- 只有刷新路由返回 `401` 才结束会话；`400`、`403`、`404` 或 `429` 现在会保留会话
  （`RefreshUnavailableError`）。刷新路由用其他状态拒绝时，传入 `isRefreshRejected`。
- `token-revoked` 与 `token-invalid` 不再刷新，并移除已存的那对 token。
- `403 scope-rejected` 交给 `onScopeRejected`（没有时交给 `onForbidden`）。回调的第二个
  参数是 CoSec problem。
- 刷新请求在 10 秒后被掐断（`CoSecTokenRefresher` 的 `timeout` 选项）。

## 文档

- [构建客户端](https://cosec.ahoo.me/zh/integration/clients)：客户端可以依赖什么
- [Fetcher](https://fetcher.ahoo.me)：它所基于的 HTTP 客户端

[English](./README.md) · [许可证](../../LICENSE)
