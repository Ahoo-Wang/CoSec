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

import { SerialTypedEventBus } from '@ahoo-wang/fetcher-eventbus';
import { InMemoryStorage } from '@ahoo-wang/fetcher-storage';
import {
  JwtTokenManager,
  RefreshSessionChangedError,
  TokenStorage,
  type CompositeToken,
} from '../src';

const jwt = (sub: string, exp: number) =>
  `e30.${btoa(JSON.stringify({ sub, exp }))}.signature`;
const token = (sub: string): CompositeToken => ({
  accessToken: jwt(sub, Date.now() / 1000 + 3600),
  refreshToken: jwt(sub, Date.now() / 1000 + 7200),
});

/** Web Locks stand-in: one promise chain (an exclusive lock) per name. */
function fakeLocks() {
  const tails = new Map<string, Promise<unknown>>();
  const requested: string[] = [];
  return {
    requested,
    request<T>(name: string, callback: () => Promise<T>): Promise<T> {
      requested.push(name);
      const result = (tails.get(name) ?? Promise.resolve()).then(() =>
        callback(),
      );
      tails.set(
        name,
        result.catch(() => undefined),
      );
      return result;
    },
  };
}

/**
 * Two tabs over one localStorage. Their storages share one bus, so a change
 * one tab makes reaches the other at once.
 */
function twoTabs() {
  const shared = new InMemoryStorage();
  // Tabs share the browser's localStorage; the lock applies to it only.
  vi.stubGlobal('window', { localStorage: shared });
  const bus = new SerialTypedEventBus<any>('refresh-lock');
  const tabA = new TokenStorage({ storage: shared, eventBus: bus });
  const tabB = new TokenStorage({ storage: shared, eventBus: bus });
  tabA.signIn(token('original'));
  return { shared, tabA, tabB };
}

describe('cross-tab refresh lock', () => {
  it('lets the second tab reuse the token the first one rotated', async () => {
    const locks = fakeLocks();
    vi.stubGlobal('navigator', { locks });
    const { shared, tabA, tabB } = twoTabs();
    const original = tabA.get()!;

    // A's refresh is in flight with the one-time refresh token; B's would be
    // rejected at once, before A has stored the new token.
    let completeA!: (value: CompositeToken) => void;
    const managerA = new JwtTokenManager(tabA, {
      refresh: () =>
        new Promise(resolve => {
          completeA = resolve;
        }),
    });
    const refreshB = vi.fn(async () => {
      throw new Error('refresh token already used');
    });
    const managerB = new JwtTokenManager(tabB, { refresh: refreshB });

    const refreshingA = managerA.refresh();
    const refreshingB = managerB.refresh();
    await vi.waitFor(() => expect(completeA).toBeTypeOf('function'));
    await new Promise(resolve => setTimeout(resolve, 0));
    completeA(token('rotated'));

    await expect(refreshingA).resolves.toBeUndefined();
    await expect(refreshingB).resolves.toBeUndefined();
    expect(refreshB).not.toHaveBeenCalled();
    expect(locks.requested).toEqual([
      'cosec-refresh:cosec-token',
      'cosec-refresh:cosec-token',
    ]);
    for (const tab of [tabA, tabB]) {
      expect(tab.get()?.access.payload.sub).toBe('rotated');
      expect(tab.get()?.sessionId).toBe(original.sessionId);
    }
    expect(shared.getItem('cosec-token')).toContain(original.sessionId);
  });

  it('does not refresh a session another tab signed out meanwhile', async () => {
    vi.stubGlobal('navigator', { locks: fakeLocks() });
    const { tabA, tabB } = twoTabs();
    const refresh = vi.fn(async () => token('rotated'));
    const manager = new JwtTokenManager(tabB, { refresh });

    const refreshing = manager.refresh();
    tabA.signOut();

    await expect(refreshing).rejects.toBeInstanceOf(RefreshSessionChangedError);
    expect(refresh).not.toHaveBeenCalled();
    expect(tabB.get()).toBeNull();
  });

  it('names the lock after the storage key', async () => {
    const locks = fakeLocks();
    vi.stubGlobal('navigator', { locks });
    const localStorage = new InMemoryStorage();
    vi.stubGlobal('window', { localStorage });
    const storage = new TokenStorage({
      key: 'app-token',
      storage: localStorage,
      eventBus: new SerialTypedEventBus('app-token'),
    });
    storage.signIn(token('original'));
    await new JwtTokenManager(storage, {
      refresh: async () => token('rotated'),
    }).refresh();
    expect(locks.requested).toEqual(['cosec-refresh:app-token']);
    expect(storage.get()?.access.payload.sub).toBe('rotated');
  });

  it('refreshes unlocked without Web Locks', async () => {
    vi.stubGlobal('navigator', undefined);
    const { tabA } = twoTabs();
    const refresh = vi.fn(async () => token('rotated'));
    const refreshing = new JwtTokenManager(tabA, { refresh }).refresh();
    // Called at once, not after a lock grant.
    expect(refresh).toHaveBeenCalledOnce();
    await refreshing;
    expect(tabA.get()?.access.payload.sub).toBe('rotated');
  });

  it('does not lock outside the browser, where one process holds many users', async () => {
    const locks = fakeLocks();
    vi.stubGlobal('navigator', { locks });
    vi.stubGlobal('window', undefined);
    const storage = new TokenStorage({
      storage: new InMemoryStorage(),
      eventBus: new SerialTypedEventBus('server-token'),
    });
    storage.signIn(token('original'));
    await new JwtTokenManager(storage, {
      refresh: async () => token('rotated'),
    }).refresh();
    expect(locks.requested).toEqual([]);
    expect(storage.get()?.access.payload.sub).toBe('rotated');
  });

  it('does not lock a storage tabs do not share', async () => {
    const locks = fakeLocks();
    vi.stubGlobal('navigator', { locks });
    vi.stubGlobal('window', { localStorage: new InMemoryStorage() });
    const storage = new TokenStorage({
      storage: new InMemoryStorage(),
      eventBus: new SerialTypedEventBus('session-token'),
    });
    storage.signIn(token('original'));
    await new JwtTokenManager(storage, {
      refresh: async () => token('rotated'),
    }).refresh();
    expect(locks.requested).toEqual([]);
  });
});
