# `@ahoo-wang/cosec-react`

React hooks and components for an application that talks to CoSec through
[`@ahoo-wang/cosec-fetcher`](../cosec-fetcher/README.md): the signed-in state
of the user as React state, and route guards.

**Which CoSec it speaks.** CoSec 8.0 only, like `@ahoo-wang/cosec-fetcher`: it is
released with CoSec and reads the token pair the client stores. A CoSec 7 server
needs the hooks of `@ahoo-wang/fetcher-react` 6.x. Moving from those:
[Upgrading](#upgrading-from-ahoo-wangfetcher-react-6x).

## Install

```bash
pnpm add react @ahoo-wang/fetcher @ahoo-wang/fetcher-eventbus \
  @ahoo-wang/fetcher-storage @ahoo-wang/cosec-fetcher @ahoo-wang/cosec-react
```

They are published with each CoSec release, at the version of CoSec, and tested against
the contract of that version: pin the version your server runs when the two must not
drift apart. The `beta` and `rc` tags name the last pre-releases of 8.0 and do not move;
never use them in production.

Peer dependencies: `react` (19) and `@ahoo-wang/cosec-fetcher`; the fetcher
packages are the client's (`cosec-fetcher`'s peers: 6.1.x or 8.x), and nothing else comes
with it. In particular it does not bring `@ahoo-wang/fetcher-react` or
`@ahoo-wang/fetcher-cosec`, the package `cosec-fetcher` replaces. The app must have exactly one copy of `@ahoo-wang/cosec-fetcher`: the hooks and
the client share the `TokenStorage` instance.

Requires Node `^22.12.0 || ^24.0.0 || >=26.0.0` to build.

## Example

```ts
// tokenStorage.ts: the module the example below imports.
import { cosec } from './cosec'; // the module that creates and applies the CoSecConfigurer (see the fetcher package's README)

export const tokenStorage = cosec.tokenStorage;
export const tokenManager = cosec.tokenManager;
```

```tsx
import {
  RouteGuard,
  SecurityProvider,
  useSecurityContext,
} from '@ahoo-wang/cosec-react';
import { tokenManager, tokenStorage } from './tokenStorage';

function App() {
  return (
    <SecurityProvider
      tokenStorage={tokenStorage}
      tokenManager={tokenManager}
      onSignOut={() => window.location.assign('/login')}
    >
      <RouteGuard
        fallback={<p>Please sign in</p>}
        onUnauthorized={() => window.location.assign('/login')}
      >
        <Profile />
      </RouteGuard>
    </SecurityProvider>
  );
}

function Profile() {
  const { currentUser, logout } = useSecurityContext();
  // The pair is gone here either way; a rejection says the server did not confirm.
  const onLogout = () =>
    logout().catch(() => alert('Signed out here; the server did not confirm.'));
  return <button onClick={onLogout}>Log out {currentUser.sub}</button>;
}
```

## What is in it

- `SecurityProvider`, `useSecurityContext`, `SecurityContext`: the security
  state of the page, from one `TokenStorage`.
- `useSecurity`: the same without the context.
- `logout` and `signOut`: `logout` is the user's "Log out": it ends the session at
  the server through the `tokenManager` option (`JwtTokenManager.logout()`, which
  needs a `tokenRevoker`), then removes the pair here. Until the server answers,
  the session is unchanged. It resolves on `204` or `401`, and rejects with
  `LogoutUnconfirmedError` otherwise (and without a `tokenManager`); the pair is
  gone here either way, and `onSignOut` fires unless a newer sign-in replaced the
  session. `signOut` removes the pair here only, at once: for a session known to
  be over. The `tokenManager` must be the manager of the hook's `tokenStorage`,
  or the hook throws.
- `RouteGuard`: renders its children for a signed-in user, otherwise the
  `fallback`. `onUnauthorized` runs after the render commits, once each time the
  user becomes (or starts out) not signed in.
- `RefreshableRouteGuard`: the same for a session that a `JwtTokenManager` can
  refresh: it starts the refresh, shows `refreshing` while it runs, and shows
  the `fallback` when the refresh fails. It logs a failed refresh with
  `console.error`, by the error's name and message only.
- `ANONYMOUS_USER`: the `currentUser` of a visitor that is not signed in.

`authenticated` is the `TokenStorage`'s own answer for the stored token. The
guard is a convenience of the user interface, never the decision: CoSec decides
on the server.

## Upgrading from `@ahoo-wang/fetcher-react` 6.x

- Import `SecurityProvider`, `useSecurity`, `useSecurityContext`, `RouteGuard`,
  `RefreshableRouteGuard` and `ANONYMOUS_USER` from `@ahoo-wang/cosec-react`.
  Nothing else changed in what they do: the code is the one of
  `@ahoo-wang/fetcher-react` 6.1.0, which no longer needs to be installed for
  them. `RouteGuard` and `RefreshableRouteGuard` return `ReactNode`, and
  `CompositeTokenProvider`, the parameter of `signIn`, is exported.
- `@ahoo-wang/cosec-fetcher` replaces `@ahoo-wang/fetcher-cosec` (see its
  README).
