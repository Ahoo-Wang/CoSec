/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { StrictMode } from 'react';
import { act, render, renderHook, screen } from '@testing-library/react';
import { JwtTokenManager, TokenStorage } from '@ahoo-wang/fetcher-cosec';
import { InMemoryStorage } from '@ahoo-wang/fetcher-storage';
import { SerialTypedEventBus } from '@ahoo-wang/fetcher-eventbus';
import {
  RefreshableRouteGuard,
  SecurityProvider,
  useSecurity,
} from '../../src';

// Unsigned test JWTs: only their payload is read on the client.
type Token = { accessToken: string; refreshToken: string };
function token(sub: string, accessExp = 2_000_000_000): Token {
  const jwt = (exp: number) =>
    `eyJhbGciOiJub25lIn0.${btoa(JSON.stringify({ sub, exp }))}.signature`;
  return { accessToken: jwt(accessExp), refreshToken: jwt(2_000_000_000) };
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const storages: TokenStorage[] = [];
function createStorage(key: string) {
  const storage = new TokenStorage({
    key,
    storage: new InMemoryStorage(),
    eventBus: new SerialTypedEventBus(key),
  });
  storages.push(storage);
  return storage;
}
afterEach(() => {
  for (const storage of storages.splice(0)) storage.destroy();
});

describe('a pending async signIn is superseded by later session actions (#2004)', () => {
  it('signOut invalidates it', async () => {
    const storage = createStorage('pending-signin');
    storage.signIn(token('current-user'));
    const pending = deferred<Token>();
    const onSignIn = vi.fn();
    const { result } = renderHook(() => useSecurity(storage, { onSignIn }));
    let login!: Promise<void>;
    act(() => {
      login = result.current.signIn(() => pending.promise);
    });
    act(() => result.current.signOut());
    await act(async () => {
      pending.resolve(token('old-user'));
      await login;
    });
    expect(storage.get()).toBeNull();
    expect(result.current.authenticated).toBe(false);
    expect(onSignIn).not.toHaveBeenCalled();
  });

  it('a newer direct signIn is not overwritten', async () => {
    const storage = createStorage('overlap-direct');
    const pending = deferred<Token>();
    const { result } = renderHook(() => useSecurity(storage));
    let oldLogin!: Promise<void>;
    act(() => {
      oldLogin = result.current.signIn(() => pending.promise);
    });
    await act(async () => {
      await result.current.signIn(token('new-user'));
    });
    await act(async () => {
      pending.resolve(token('old-user'));
      await oldLogin;
    });
    expect(result.current.currentUser.sub).toBe('new-user');
  });

  it('a newer async signIn wins even when it completes first', async () => {
    const storage = createStorage('overlap-async');
    const older = deferred<Token>();
    const newer = deferred<Token>();
    const onSignIn = vi.fn();
    const { result } = renderHook(() => useSecurity(storage, { onSignIn }));
    let first!: Promise<void>;
    let second!: Promise<void>;
    act(() => {
      first = result.current.signIn(() => older.promise);
      second = result.current.signIn(() => newer.promise);
    });
    await act(async () => {
      newer.resolve(token('newer'));
      await second;
      older.resolve(token('older'));
      await first;
    });
    expect(result.current.currentUser.sub).toBe('newer');
    expect(onSignIn).toHaveBeenCalledOnce();
  });

  it('holds across useSecurity instances sharing the storage', async () => {
    const storage = createStorage('shared');
    const pending = deferred<Token>();
    const a = renderHook(() => useSecurity(storage));
    const b = renderHook(() => useSecurity(storage));
    let login!: Promise<void>;
    act(() => {
      login = a.result.current.signIn(() => pending.promise);
    });
    act(() => b.result.current.signOut());
    await act(async () => {
      pending.resolve(token('old-user'));
      await login;
    });
    expect(storage.get()).toBeNull();
    expect(a.result.current.authenticated).toBe(false);
    expect(b.result.current.authenticated).toBe(false);
  });

  it('keeps a single async signIn, its order and its errors', async () => {
    const storage = createStorage('single');
    const order: string[] = [];
    const { result } = renderHook(() =>
      useSecurity(storage, { onSignIn: () => order.push('onSignIn') }),
    );
    await act(async () => {
      await result.current.signIn(async () => {
        order.push('provider');
        return token('user');
      });
    });
    expect(order).toEqual(['provider', 'onSignIn']);
    expect(result.current.currentUser.sub).toBe('user');
    await expect(
      result.current.signIn(async () => {
        throw new Error('denied');
      }),
    ).rejects.toThrow('denied');
    expect(result.current.currentUser.sub).toBe('user');
  });
});

describe('RefreshableRouteGuard follows the current session (#2005)', () => {
  function renderGuard(storage: TokenStorage, manager: JwtTokenManager) {
    return render(
      <SecurityProvider tokenStorage={storage}>
        <RefreshableRouteGuard
          tokenManager={manager}
          fallback={<p>fallback</p>}
          refreshing={<p>refreshing</p>}
        >
          <p>protected</p>
        </RefreshableRouteGuard>
      </SecurityProvider>,
    );
  }

  it.each(['succeeds', 'fails'] as const)(
    'refreshes a replaced expired session when the old refresh %s late',
    async outcome => {
      vi.spyOn(console, 'error').mockImplementation(() => {});
      const storage = createStorage(`guard-switch-${outcome}`);
      storage.signIn(token('session-a', 1));
      const first = deferred<Token>();
      const second = deferred<Token>();
      const refresh = vi
        .fn()
        .mockImplementationOnce(() => first.promise)
        .mockImplementationOnce(() => second.promise);
      renderGuard(storage, new JwtTokenManager(storage, { refresh }));
      expect(refresh).toHaveBeenCalledTimes(1);
      await act(async () => {
        storage.signIn(token('session-b', 1));
      });
      await act(async () => {
        if (outcome === 'succeeds') first.resolve(token('session-a'));
        else first.reject(new Error('session a failed'));
      });
      expect(refresh).toHaveBeenCalledTimes(2);
      expect(screen.getByText('refreshing')).toBeTruthy();
      await act(async () => {
        second.resolve(token('session-b'));
      });
      expect(screen.getByText('protected')).toBeTruthy();
      expect(storage.get()?.access.payload?.sub).toBe('session-b');
    },
  );

  it('a new session after a failed refresh shows refreshing, then children', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const storage = createStorage('guard-after-failure');
    storage.signIn(token('session-a', 1));
    const second = deferred<Token>();
    const refresh = vi
      .fn()
      .mockImplementationOnce(() => Promise.reject(new Error('unavailable')))
      .mockImplementationOnce(() => second.promise);
    renderGuard(storage, new JwtTokenManager(storage, { refresh }));
    await act(async () => {});
    // A failure that keeps the token (the refresher was unavailable).
    expect(storage.get()).not.toBeNull();
    expect(screen.getByText('fallback')).toBeTruthy();
    await act(async () => {
      storage.signIn(token('session-b', 1));
    });
    expect(screen.getByText('refreshing')).toBeTruthy();
    await act(async () => {
      second.resolve(token('session-b'));
    });
    expect(screen.getByText('protected')).toBeTruthy();
  });

  it('one refresh per session, also under StrictMode', async () => {
    const storage = createStorage('guard-dedupe');
    storage.signIn(token('session-a', 1));
    const pending = deferred<Token>();
    const refresh = vi.fn(() => pending.promise);
    const manager = new JwtTokenManager(storage, { refresh });
    const view = render(
      <StrictMode>
        <SecurityProvider tokenStorage={storage}>
          <RefreshableRouteGuard
            tokenManager={manager}
            fallback={<p>fallback</p>}
            refreshing={<p>refreshing</p>}
          >
            <p>protected</p>
          </RefreshableRouteGuard>
        </SecurityProvider>
      </StrictMode>,
    );
    view.rerender(
      <StrictMode>
        <SecurityProvider tokenStorage={storage}>
          <RefreshableRouteGuard
            tokenManager={manager}
            fallback={<p>fallback</p>}
            refreshing={<p>refreshing</p>}
          >
            <p>protected</p>
          </RefreshableRouteGuard>
        </SecurityProvider>
      </StrictMode>,
    );
    expect(refresh).toHaveBeenCalledTimes(1);
    await act(async () => {
      pending.resolve(token('session-a'));
    });
    expect(screen.getByText('protected')).toBeTruthy();
  });
});
