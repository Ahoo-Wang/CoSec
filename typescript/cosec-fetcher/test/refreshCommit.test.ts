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

import { ExchangeError, Fetcher, FetchExchange } from '@ahoo-wang/fetcher';
import { SerialTypedEventBus } from '@ahoo-wang/fetcher-eventbus';
import { InMemoryStorage } from '@ahoo-wang/fetcher-storage';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  CoSecConfigurer,
  CoSecTokenRefresher,
  DeviceIdStorage,
  JwtCompositeToken,
  JwtTokenManager,
  RefreshSessionChangedError,
  RefreshTokenError,
  RefreshUnavailableError,
  TokenStorage,
  type CompositeToken,
  type TokenRefresher,
} from '../src';

const jwt = (version: string, exp = Date.now() / 1000 + 3600) =>
  `e30.${btoa(JSON.stringify({ sub: 'test-user', jti: version, exp }))}.test`;
const token = (version: string) => ({
  accessToken: jwt(version),
  refreshToken: jwt(version, Date.now() / 1000 + 7200),
});
const expired = (version: string) => ({
  accessToken: jwt(version, 1),
  refreshToken: jwt(version, Date.now() / 1000 + 7200),
});

const destroyables: { destroy(): void }[] = [];
const track = <T extends { destroy(): void }>(value: T): T => {
  destroyables.push(value);
  return value;
};
afterEach(() => {
  destroyables.splice(0).forEach(value => value.destroy());
});
const bus = (key: string) => track(new SerialTypedEventBus<never>(key));

/** A Web Locks stand-in that runs the callbacks for one name one at a time. */
function serialLocks() {
  const tails = new Map<string, Promise<unknown>>();
  return {
    request<T>(name: string, callback: () => Promise<T>): Promise<T> {
      const run = (tails.get(name) ?? Promise.resolve()).then(callback);
      tails.set(
        name,
        run.catch(() => {}),
      );
      return run;
    },
  };
}

// #2000: another tab signs out or in while this tab's refresh is in flight,
// and its change event has not arrived when the refresh resolves.
describe.each([
  ['without Web Locks', () => ({})],
  ['with Web Locks', () => ({ locks: serialLocks() })],
])(
  'a refresh completing after another tab changed the session (%s)',
  (_, navigator) => {
    it.each(['signOut', 'signIn', 'refresh'] as const)(
      'keeps the other tab %s',
      async operation => {
        vi.stubGlobal('navigator', navigator());
        const key = `refresh-commit-${crypto.randomUUID()}`;
        const backend = new InMemoryStorage();
        // Both tabs share localStorage; separate buses with no broadcast model
        // a change event that has not been delivered yet.
        vi.stubGlobal('window', { localStorage: backend });
        const tabA = track(new TokenStorage({ key, eventBus: bus(key) }));
        tabA.signIn(token('original'));
        const tabB = track(new TokenStorage({ key, eventBus: bus(key) }));
        const original = tabA.get()!;
        let complete!: (value: CompositeToken) => void;
        let started!: () => void;
        const refreshStarted = new Promise<void>(
          resolve => (started = resolve),
        );
        const manager = new JwtTokenManager(tabA, {
          refresh: () => {
            started();
            return new Promise(resolve => (complete = resolve));
          },
        });
        const refreshing = manager.refresh().then(
          () => 'resolved',
          error => error,
        );
        await refreshStarted;
        if (operation === 'signOut') tabB.signOut();
        else if (operation === 'signIn') tabB.signIn(token('new-login'));
        else
          tabB.set(
            new JwtCompositeToken(
              token('refreshed-by-b'),
              0,
              original.sessionId,
            ),
          );
        const written = backend.getItem(key);
        complete(token('refreshed-by-a'));
        const result = await refreshing;

        expect(backend.getItem(key)).toBe(written);
        if (operation === 'refresh') {
          // The same session, refreshed by the other tab: its token is reused.
          expect(result).toBe('resolved');
          expect(tabA.get()?.token).toEqual(tabB.get()?.token);
        } else {
          expect(result).toBeInstanceOf(RefreshSessionChangedError);
          expect(tabA.get()?.sessionId ?? null).toBe(
            tabB.get()?.sessionId ?? null,
          );
        }
      },
    );
  },
);

const rejection = (status: number | undefined) => {
  const fetcher = new Fetcher({ baseURL: 'https://example.test' });
  return status === undefined
    ? new TypeError('network offline')
    : new ExchangeError(
        new FetchExchange({
          fetcher,
          request: { url: '/refresh' },
          response: new Response('', { status }),
        }),
      );
};

// #2019: a refresher that throws synchronously is handled like one that rejects.
describe.each([
  [
    'throws synchronously',
    (error: unknown): TokenRefresher => ({
      refresh() {
        throw error;
      },
    }),
  ],
  [
    'rejects',
    (error: unknown): TokenRefresher => ({
      refresh: async () => {
        throw error;
      },
    }),
  ],
])('a custom refresher that %s', (_, refresher) => {
  it.each([
    [401, RefreshTokenError, true, 1],
    [undefined, RefreshUnavailableError, false, 0],
    [503, RefreshUnavailableError, false, 0],
  ] as const)(
    'with status %s fails with %o (token removed: %s, notifications: %s)',
    async (status, errorType, removed, notified) => {
      const tokens = track(
        new TokenStorage({
          key: 'sync-token',
          storage: new InMemoryStorage(),
          eventBus: bus('sync-token'),
        }),
      );
      const devices = track(
        new DeviceIdStorage({
          key: 'sync-device',
          storage: new InMemoryStorage(),
          eventBus: bus('sync-device'),
        }),
      );
      let notifications = 0;
      const fetcher = new Fetcher({
        baseURL: 'https://example.test',
        fetch: async () => new Response('business response'),
      });
      new CoSecConfigurer({
        appId: 'demo',
        tokenStorage: tokens,
        deviceIdStorage: devices,
        tokenRefresher: refresher(rejection(status)),
        onUnauthorized: () => {
          notifications++;
        },
      }).applyTo(fetcher);
      tokens.signIn(expired('demo'));
      const error = await fetcher.get('/business').catch(error => error);
      expect(error.cause).toBeInstanceOf(errorType);
      expect(tokens.get() === null).toBe(removed);
      expect(notifications).toBe(notified);
    },
  );
});

// #2027: a CoSecTokenRefresher from another copy of the module (the CommonJS
// build next to the ES module one) keeps the notification ownership protocol.
describe('a CoSecTokenRefresher from another module instance', () => {
  it.each([false, true])(
    'notifies the business request once (callback signs out: %s)',
    async signOut => {
      vi.resetModules();
      const other = await import('../src/tokenRefresher');
      const otherInterceptor =
        await import('../src/unauthorizedErrorInterceptor');
      expect(other.CoSecTokenRefresher).not.toBe(CoSecTokenRefresher);

      const tokens = track(
        new TokenStorage({
          key: 'tokens',
          storage: new InMemoryStorage(),
          eventBus: bus('tokens'),
        }),
      );
      const devices = track(
        new DeviceIdStorage({
          key: 'devices',
          storage: new InMemoryStorage(),
          eventBus: bus('devices'),
        }),
      );
      const notifications: string[] = [];
      const refreshClient = new Fetcher({
        baseURL: 'https://example.test',
        fetch: async () => new Response('rejected', { status: 401 }),
      });
      refreshClient.interceptors.error.use(
        new otherInterceptor.UnauthorizedErrorInterceptor({
          onUnauthorized: () => {
            notifications.push('inner');
            if (signOut) tokens.signOut();
          },
        }),
      );
      const tokenRefresher: TokenRefresher = new other.CoSecTokenRefresher({
        fetcher: refreshClient,
        endpoint: '/refresh',
      });
      const client = new Fetcher({
        baseURL: 'https://example.test',
        fetch: async () => new Response('business rejected', { status: 401 }),
      });
      new CoSecConfigurer({
        appId: 'demo',
        tokenStorage: tokens,
        deviceIdStorage: devices,
        tokenRefresher,
        onUnauthorized: () => {
          notifications.push('outer');
          if (signOut) tokens.signOut();
        },
      }).applyTo(client);
      tokens.signIn(expired('demo'));
      const error = await client.get('/business').catch(error => error);
      expect(notifications).toEqual(['outer']);
      expect(error.cause?.name).toBe('RefreshTokenError');
    },
  );
});
