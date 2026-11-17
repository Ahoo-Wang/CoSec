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
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { JwtTokenManager, TokenStorage, type TokenRefresher } from '../src';
import { FakeCoSecHost } from './support/fakeCoSec';

/** Web Locks as far as the client uses them: one holder per name, in turn. */
function fakeLocks() {
  const tails = new Map<string, Promise<unknown>>();
  const request = vi.fn(
    <T>(name: string, callback: () => Promise<T>): Promise<T> => {
      const run = (tails.get(name) ?? Promise.resolve()).then(callback);
      tails.set(
        name,
        run.catch(() => undefined),
      );
      return run;
    },
  );
  return { request };
}

const tabs: TokenStorage[] = [];
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
});
afterEach(() => {
  tabs.splice(0).forEach(tab => tab.destroy());
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

/** A tab: its own storage object and bus over the backing storage all tabs share. */
function tab(
  backing: Storage,
  options: { sharedAcrossTabs?: boolean } = {},
): TokenStorage {
  const storage = new TokenStorage({
    key: 'shared-token',
    storage: backing,
    eventBus: new SerialTypedEventBus<never>(`tab-${tabs.length}`),
    ...options,
  });
  tabs.push(storage);
  return storage;
}

function refresher(host: FakeCoSecHost): TokenRefresher {
  return {
    refresh: async token => {
      const response = await host.fetch('/auth/refresh', {
        method: 'POST',
        body: JSON.stringify(token),
      });
      return response.json();
    },
  };
}

async function twoTabsRefresh(options: { sharedAcrossTabs?: boolean }) {
  const host = new FakeCoSecHost();
  const backing = new InMemoryStorage();
  const locks = fakeLocks();
  vi.stubGlobal('navigator', { locks });
  const first = tab(backing, options);
  const second = tab(backing, options);
  const pair = host.login();
  first.signIn(pair);
  // the second tab has read the stored pair too
  expect(second.reload()?.token).toEqual(pair);
  vi.setSystemTime(Date.now() + 11 * 60_000);
  const managers = [first, second].map(
    storage => new JwtTokenManager(storage, refresher(host)),
  );
  await Promise.all(managers.map(manager => manager.refresh()));
  return { host, locks, first, second, pair };
}

describe('tabs of one session on a storage other than localStorage (M3)', () => {
  it('take turns to refresh when the storage is declared shared: one refresh, one pair', async () => {
    const { host, locks, first, second, pair } = await twoTabsRefresh({
      sharedAcrossTabs: true,
    });
    expect(locks.request).toHaveBeenCalledTimes(2);
    expect(host.refreshes.map(refresh => refresh.outcome)).toEqual(['moved']);
    const stored = first.reload()?.token;
    expect(stored?.accessToken).not.toBe(pair.accessToken);
    expect(second.reload()?.token).toEqual(stored);
  });

  it('each refresh the one pair when it is not (documented: refresh in one place only)', async () => {
    const { host, locks } = await twoTabsRefresh({});
    expect(locks.request).not.toHaveBeenCalled();
    expect(host.refreshes.map(refresh => refresh.outcome)).toEqual([
      'moved',
      'healed',
    ]);
  });

  it('can be declared not shared where it is the default', async () => {
    const locks = fakeLocks();
    vi.stubGlobal('navigator', { locks });
    vi.stubGlobal('window', { localStorage: new InMemoryStorage() });
    const host = new FakeCoSecHost();
    const storage = new TokenStorage({
      key: 'declared',
      sharedAcrossTabs: false,
      eventBus: new SerialTypedEventBus<never>('declared'),
    });
    tabs.push(storage);
    storage.signIn(host.login());
    vi.setSystemTime(Date.now() + 11 * 60_000);
    await new JwtTokenManager(storage, refresher(host)).refresh();
    expect(locks.request).not.toHaveBeenCalled();
  });
});
