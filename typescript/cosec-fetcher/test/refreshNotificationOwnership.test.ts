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

import {
  ExchangeError,
  Fetcher,
  deleteHeader,
  type FetchExchange,
} from '@ahoo-wang/fetcher';
import { InMemoryStorage } from '@ahoo-wang/fetcher-storage';
import { SerialTypedEventBus } from '@ahoo-wang/fetcher-eventbus';
import {
  AuthorizationRequestInterceptor,
  AuthorizationResponseInterceptor,
  CoSecTokenRefresher,
  JwtTokenManager,
  TokenStorage,
  UnauthorizedErrorInterceptor,
} from '../src';

const jwt = (sub: string, exp: number) =>
  `e30.${btoa(JSON.stringify({ sub, exp }))}.signature`;
const token = (sub: string, expired = false) => ({
  accessToken: jwt(sub, Date.now() / 1000 + (expired ? -1 : 3600)),
  refreshToken: jwt(sub, Date.now() / 1000 + 7200),
});

describe('shared refresh notification ownership', () => {
  let storage: TokenStorage;
  beforeEach(() => {
    storage = new TokenStorage({
      storage: new InMemoryStorage(),
      eventBus: new SerialTypedEventBus('refresh-notification-ownership'),
    });
  });
  afterEach(() => {
    storage.destroy();
    storage.eventBus.destroy();
  });

  function configure(client: Fetcher, manager: JwtTokenManager) {
    client.interceptors.request.use(
      new AuthorizationRequestInterceptor({ tokenManager: manager }),
    );
    client.interceptors.response.use(
      new AuthorizationResponseInterceptor({ tokenManager: manager }),
    );
  }

  it.each(['proactive', '401'])(
    'notifies once across later waiters of a shared %s refresh',
    async trigger => {
      storage.signIn(token('original', trigger === 'proactive'));
      const clients = Array.from(
        { length: 3 },
        () => new Fetcher({ baseURL: 'https://example.test' }),
      );
      const refreshClient = new Fetcher({ baseURL: 'https://example.test' });
      const refresher = new CoSecTokenRefresher({
        fetcher: refreshClient,
        endpoint: '/refresh',
      });
      const manager = new JwtTokenManager(storage, refresher);
      const refreshCalls = vi.spyOn(manager, 'refresh');
      const notifications: string[] = [];
      for (const client of clients) configure(client, manager);
      for (const client of [refreshClient, ...clients.slice(1)]) {
        client.interceptors.error.use(
          new UnauthorizedErrorInterceptor({
            onUnauthorized: exchange => {
              notifications.push(
                new URL(exchange.request.url, 'https://example.test').pathname,
              );
            },
          }),
        );
      }
      let release!: (response: Response) => void;
      let markStarted!: () => void;
      const started = new Promise<void>(resolve => {
        markStarted = resolve;
      });
      let refreshRequests = 0;
      vi.stubGlobal('fetch', async (url: string) => {
        if (url.endsWith('/refresh')) {
          refreshRequests++;
          markStarted();
          return new Promise<Response>(resolve => {
            release = resolve;
          });
        }
        return new Response('', { status: 401 });
      });
      const first = clients[0].post('/first').catch(error => error);
      await started;
      const others = clients
        .slice(1)
        .map((client, index) =>
          client.post(`/later-${index}`).catch(error => error),
        );
      await vi.waitFor(() => expect(refreshCalls).toHaveBeenCalledTimes(3));
      release(new Response('', { status: 401 }));
      const results = await Promise.all([first, ...others]);
      expect(results).toEqual(
        Array.from({ length: 3 }, () =>
          expect.objectContaining({
            exchange: expect.objectContaining({
              error: expect.objectContaining({ name: 'RefreshTokenError' }),
            }),
          }),
        ),
      );
      expect(refreshRequests).toBe(1);
      expect(notifications).toHaveLength(1);
      expect(notifications[0]).toMatch(/^\/later-/);
      expect(storage.get()).toBeNull();
    },
  );

  it.each(['resolve', 'reject', 'sign-out-reject', 'sign-in-reject'])(
    'does not notify a waiter after the refresh callback starts and ends with %s',
    async outcome => {
      storage.signIn(token('original', true));
      const client = new Fetcher({ baseURL: 'https://example.test' });
      const laterClient = new Fetcher({ baseURL: 'https://example.test' });
      const refreshClient = new Fetcher({ baseURL: 'https://example.test' });
      const manager = new JwtTokenManager(
        storage,
        new CoSecTokenRefresher({
          fetcher: refreshClient,
          endpoint: '/refresh',
        }),
      );
      configure(client, manager);
      configure(laterClient, manager);
      let releaseNotification!: () => void;
      let notificationStarted!: () => void;
      const started = new Promise<void>(resolve => {
        notificationStarted = resolve;
      });
      const notifications: string[] = [];
      const callbackError = new Error('unauthorized callback failed');
      const replacement = token('replacement');
      refreshClient.interceptors.error.use(
        new UnauthorizedErrorInterceptor({
          onUnauthorized: async exchange => {
            notifications.push(
              new URL(exchange.request.url, 'https://example.test').pathname,
            );
            notificationStarted();
            await new Promise<void>(resolve => {
              releaseNotification = resolve;
            });
            if (outcome === 'sign-out-reject') storage.signOut();
            if (outcome === 'sign-in-reject') storage.signIn(replacement);
            if (outcome !== 'resolve') throw callbackError;
          },
        }),
      );
      laterClient.interceptors.error.use(
        new UnauthorizedErrorInterceptor({
          onUnauthorized: exchange => {
            notifications.push(
              new URL(exchange.request.url, 'https://example.test').pathname,
            );
          },
        }),
      );
      const refreshCalls = vi.spyOn(manager, 'refresh');
      vi.stubGlobal('fetch', async () => new Response('', { status: 401 }));
      const first = client.post('/first').catch(error => error);
      await started;
      const later = laterClient.post('/later').catch(error => error);
      await vi.waitFor(() => expect(refreshCalls).toHaveBeenCalledTimes(2));
      releaseNotification();
      const results = await Promise.all([first, later]);
      expect(notifications).toEqual(['/refresh']);
      for (const result of results) {
        expect(result.exchange.error.name).toBe(
          outcome === 'sign-in-reject'
            ? 'RefreshSessionChangedError'
            : 'RefreshTokenError',
        );
        if (outcome !== 'resolve') {
          // The refresh request rejects with an ExchangeError, as every
          // fetcher exchange does; the callback's error is its cause.
          expect(result.exchange.error.cause).toBeInstanceOf(ExchangeError);
          expect(result.exchange.error.cause.cause).toBe(callbackError);
        }
      }
      if (outcome === 'sign-in-reject') {
        expect(storage.get()?.token.accessToken).toBe(replacement.accessToken);
      } else {
        expect(storage.get()).toBeNull();
      }
    },
  );

  it('does not claim a notification for a non-401 refresh error', async () => {
    const refreshClient = new Fetcher({ baseURL: 'https://example.test' });
    const claimNotification = vi.fn(() => true);
    const onUnauthorized = vi.fn();
    refreshClient.interceptors.error.use(
      new UnauthorizedErrorInterceptor({ onUnauthorized }),
    );
    const refresher = new CoSecTokenRefresher({
      fetcher: refreshClient,
      endpoint: '/refresh',
    });
    vi.stubGlobal('fetch', async () => new Response('', { status: 500 }));
    await expect(
      refresher.refresh(token('original'), claimNotification),
    ).rejects.toMatchObject({ exchange: { response: { status: 500 } } });
    expect(claimNotification).not.toHaveBeenCalled();
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it.each(['object', 'headers', 'tuples'])(
    'does not replay an anonymous POST after a later interceptor deletes %s authorization',
    async shape => {
      storage.signIn(token('original'));
      const original = storage.get();
      const refresh = vi.fn(async () => token('refreshed'));
      const manager = new JwtTokenManager(storage, { refresh });
      const client = new Fetcher({ baseURL: 'https://example.test' });
      configure(client, manager);
      client.interceptors.request.use({
        name: 'anonymous-endpoint',
        order: 0,
        intercept: async (exchange: FetchExchange) => {
          deleteHeader(exchange.ensureRequestHeaders(), 'Authorization');
        },
      });
      const authorizations: (string | null)[] = [];
      vi.stubGlobal('fetch', async (_url: string, init: RequestInit) => {
        authorizations.push(new Headers(init.headers).get('Authorization'));
        return new Response('', { status: 401 });
      });
      const headers =
        shape === 'headers' ? new Headers() : shape === 'tuples' ? [] : {};
      await expect(
        client.post('/anonymous', { headers }),
      ).rejects.toMatchObject({
        exchange: { response: { status: 401 } },
      });
      expect(authorizations).toEqual([null]);
      expect(refresh).not.toHaveBeenCalled();
      expect(storage.get()).toBe(original);
    },
  );

  it('keeps the response-only refresh path when no credential was injected', async () => {
    storage.signIn(token('original'));
    const refresh = vi.fn(async () => token('refreshed'));
    const client = new Fetcher({ baseURL: 'https://example.test' });
    client.interceptors.response.use(
      new AuthorizationResponseInterceptor({
        tokenManager: new JwtTokenManager(storage, { refresh }),
      }),
    );
    let requests = 0;
    vi.stubGlobal(
      'fetch',
      async () => new Response('', { status: ++requests === 1 ? 401 : 200 }),
    );
    expect((await client.get('/resource')).status).toBe(200);
    expect(requests).toBe(2);
    expect(refresh).toHaveBeenCalledTimes(1);
  });
});
