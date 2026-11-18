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

import { Fetcher } from '@ahoo-wang/fetcher';
import {
  BroadcastTypedEventBus,
  SerialTypedEventBus,
} from '@ahoo-wang/fetcher-eventbus';
import { InMemoryStorage, type StorageEvent } from '@ahoo-wang/fetcher-storage';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  AuthorizationRequestInterceptor,
  DeviceIdStorage,
  JwtTokenManager,
  SpaceIdStorage,
  TokenStorage,
  type JwtCompositeToken,
} from '../src';

const jwt = (sub: string, jti = sub) =>
  `e30.${btoa(JSON.stringify({ sub, jti, exp: Date.now() / 1000 + 3600 }))}.test`;
const token = (sub: string, jti = sub) => ({
  accessToken: jwt(sub, jti),
  refreshToken: jwt(sub, jti),
});
const delivered = () => new Promise(resolve => setTimeout(resolve, 30));

const destroyables: { destroy(): void }[] = [];
const track = <T extends { destroy(): void }>(value: T): T => {
  destroyables.push(value);
  return value;
};
afterEach(() => {
  destroyables.splice(0).forEach(value => value.destroy());
});

// #1999: in Node every default TokenStorage has its own InMemoryStorage, so
// per-request storages must not share their auth state through a native
// BroadcastChannel named after the (shared) default key.
describe('default storages without a browser window', () => {
  it('runs in an environment with a native BroadcastChannel', () => {
    expect(typeof window).toBe('undefined');
    expect(typeof BroadcastChannel).toBe('function');
  });

  it('keeps each request storage to its own user', async () => {
    const first = track(new TokenStorage());
    const second = track(new TokenStorage());
    first.signIn(token('request-A-user'));
    await delivered();
    expect(second.currentUser).toBeNull();
    expect(second.get()).toBeNull();

    let authorization: string | null = 'unset';
    const client = new Fetcher({
      baseURL: 'https://local.example.test',
      fetch: async (_url, init) => {
        authorization = new Headers(init?.headers).get('authorization');
        return new Response('{}');
      },
    });
    client.interceptors.request.use(
      new AuthorizationRequestInterceptor({
        tokenManager: new JwtTokenManager(second, {
          refresh: async () => {
            throw new Error('not expected');
          },
        }),
      }),
    );
    await client.get('/private');
    expect(authorization).toBeNull();
  });

  it('does not let a sign-out, refresh or sign-in reach another request', async () => {
    const first = track(new TokenStorage());
    const second = track(new TokenStorage());
    second.signIn(token('request-B-user'));
    const own = second.get();
    first.signIn(token('request-A-user'));
    await delivered();
    first.signIn(token('request-A-user', 'refreshed'));
    first.signOut();
    await delivered();
    expect(second.get()).toBe(own);
    expect(second.currentUser?.sub).toBe('request-B-user');
  });

  it('keeps device and space ids per storage', async () => {
    const firstDevice = track(new DeviceIdStorage());
    const secondDevice = track(new DeviceIdStorage());
    firstDevice.set('device-a');
    const firstSpace = track(new SpaceIdStorage());
    const secondSpace = track(new SpaceIdStorage());
    firstSpace.set('space-a');
    await delivered();
    expect(secondDevice.get()).toBeNull();
    expect(secondSpace.get()).toBeNull();
  });

  it('keeps independent InMemoryStorage backends apart under one key', async () => {
    const key = `isolated-${crypto.randomUUID()}`;
    const first = track(
      new TokenStorage({ key, storage: new InMemoryStorage() }),
    );
    const second = track(
      new TokenStorage({ key, storage: new InMemoryStorage() }),
    );
    first.signIn(token('a'));
    await delivered();
    expect(second.get()).toBeNull();
  });

  it('keeps an explicitly shared event bus shared', async () => {
    const key = `shared-${crypto.randomUUID()}`;
    const bus = track(
      new BroadcastTypedEventBus<StorageEvent<JwtCompositeToken>>({
        delegate: new SerialTypedEventBus(key),
      }),
    );
    const otherBus = track(
      new BroadcastTypedEventBus<StorageEvent<JwtCompositeToken>>({
        delegate: new SerialTypedEventBus(key),
      }),
    );
    const storage = new InMemoryStorage();
    const first = track(new TokenStorage({ key, storage, eventBus: bus }));
    const second = track(
      new TokenStorage({ key, storage, eventBus: otherBus }),
    );
    first.signIn(token('shared'));
    await delivered();
    expect(second.get()?.sessionId).toBe(first.get()?.sessionId);
  });
});

describe('default storages on the browser localStorage', () => {
  it('still syncs tabs sharing localStorage', async () => {
    const localStorage = new InMemoryStorage();
    vi.stubGlobal('window', { localStorage });
    const key = `tabs-${crypto.randomUUID()}`;
    const first = track(new TokenStorage({ key }));
    const second = track(new TokenStorage({ key }));
    first.signIn(token('tab-user'));
    await delivered();
    expect(second.currentUser?.sub).toBe('tab-user');
    first.signOut();
    await delivered();
    expect(second.get()).toBeNull();
  });

  it('does not sync a non-localStorage backend', async () => {
    vi.stubGlobal('window', { localStorage: new InMemoryStorage() });
    const key = `session-${crypto.randomUUID()}`;
    const first = track(
      new TokenStorage({ key, storage: new InMemoryStorage() }),
    );
    const second = track(
      new TokenStorage({ key, storage: new InMemoryStorage() }),
    );
    first.signIn(token('a'));
    await delivered();
    expect(second.get()).toBeNull();
  });
});
