# `@ahoo-wang/cosec-react`

面向通过 [`@ahoo-wang/cosec-fetcher`](../cosec-fetcher/README.zh-CN.md) 访问 CoSec 的应用的 React hooks 和组件：把用户的登录状态变成 React 状态，并提供路由守卫。

**对应哪个 CoSec。** 只对应 CoSec 8.0，与 `@ahoo-wang/cosec-fetcher` 一样：它随 CoSec 发布，读取客户端保存的令牌对。CoSec 7 服务端需要
`@ahoo-wang/fetcher-react` 6.x 里的这些 hooks。从那里迁移：[升级](#从-ahoo-wangfetcher-react-6x-升级)。

## 安装

```bash
pnpm add react @ahoo-wang/fetcher @ahoo-wang/fetcher-eventbus \
  @ahoo-wang/fetcher-storage @ahoo-wang/cosec-fetcher @ahoo-wang/cosec-react
```

对等依赖：`react`（19）和 `@ahoo-wang/cosec-fetcher`；fetcher 的各个包是客户端（`cosec-fetcher` 的对等依赖）的，除此之外它不带任何别的东西。特别是它不会带上
`@ahoo-wang/fetcher-react`，也不会带上 `cosec-fetcher` 所取代的 `@ahoo-wang/fetcher-cosec`。
应用里必须只有一份 `@ahoo-wang/cosec-fetcher`：hooks 和客户端共用同一个 `TokenStorage` 实例。

构建需要 Node `^22.12.0 || ^24.0.0 || >=26.0.0`。

## 示例

```tsx
import {
  RouteGuard,
  SecurityProvider,
  useSecurityContext,
} from '@ahoo-wang/cosec-react';
import { tokenStorage } from './tokenStorage';

function App() {
  return (
    <SecurityProvider
      tokenStorage={tokenStorage}
      onSignOut={() => window.location.assign('/login')}
    >
      <RouteGuard
        fallback={<p>请先登录</p>}
        onUnauthorized={() => window.location.assign('/login')}
      >
        <Profile />
      </RouteGuard>
    </SecurityProvider>
  );
}

function Profile() {
  const { currentUser, signOut } = useSecurityContext();
  return <button onClick={signOut}>退出 {currentUser.sub}</button>;
}
```

## 里面有什么

- `SecurityProvider`、`useSecurityContext`、`SecurityContext`：页面的安全状态，来自一个 `TokenStorage`。
- `useSecurity`：不经过 context 的同一个 hook。
- `RouteGuard`：用户已登录时渲染子节点，否则渲染 `fallback`。`onUnauthorized` 在渲染提交之后运行，用户每次变为（或一开始就是）未登录时调用一次。
- `RefreshableRouteGuard`：用于 `JwtTokenManager` 能刷新的会话：它自己发起刷新，刷新期间渲染 `refreshing`，刷新失败时渲染 `fallback`。
- `ANONYMOUS_USER`：未登录访客的 `currentUser`。

`authenticated` 就是 `TokenStorage` 对所存令牌给出的答案。守卫只是界面上的便利，从来不是决定：决定在服务端由 CoSec 做出。

## 从 `@ahoo-wang/fetcher-react` 6.x 升级

- 从 `@ahoo-wang/cosec-react` 导入 `SecurityProvider`、`useSecurity`、`useSecurityContext`、`RouteGuard`、`RefreshableRouteGuard` 和
  `ANONYMOUS_USER`。它们做的事没有变化：代码就是 `@ahoo-wang/fetcher-react` 6.1.0 里的那一份，现在不必再为它们安装 `@ahoo-wang/fetcher-react`。`RouteGuard` 和
  `RefreshableRouteGuard` 返回 `ReactNode`，`signIn` 的参数类型 `CompositeTokenProvider` 现在被导出。
- `@ahoo-wang/cosec-fetcher` 取代 `@ahoo-wang/fetcher-cosec`（见它的 README）。
