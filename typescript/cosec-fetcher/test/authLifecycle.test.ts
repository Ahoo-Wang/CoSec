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

import { Fetcher, type FetchExchange } from '@ahoo-wang/fetcher';
import { SerialTypedEventBus } from '@ahoo-wang/fetcher-eventbus';
import { InMemoryStorage } from '@ahoo-wang/fetcher-storage';
import {
  AuthorizationRequestInterceptor,
  AuthorizationResponseInterceptor,
  CoSecTokenRefresher,
  ForbiddenErrorInterceptor,
  IGNORE_REFRESH_TOKEN_ATTRIBUTE_KEY,
  JwtTokenManager,
  RefreshTokenError,
  TokenStorage,
  UnauthorizedErrorInterceptor,
  type CompositeToken,
} from '../src';

const jwt = (sub: string, exp: number) =>
  `e30.${btoa(JSON.stringify({ sub, exp }))}.signature`;
const token = (sub: string): CompositeToken => ({
  accessToken: jwt(sub, Date.now() / 1000 + 3600),
  refreshToken: jwt(sub, Date.now() / 1000 + 7200),
});

describe('authentication lifecycle', () => {
  let storage: TokenStorage;

  beforeEach(() => {
    storage = new TokenStorage({
      storage: new InMemoryStorage(),
      eventBus: new SerialTypedEventBus('auth-lifecycle'),
    });
    storage.signIn(token('original'));
  });

  afterEach(() => {
    storage.destroy();
    storage.eventBus.destroy();
  });

  it.each(['success', 'failure'] as const)(
    'keeps a signed-out session signed out after refresh %s',
    async outcome => {
      let resolve!: (value: CompositeToken) => void;
      let reject!: (error: Error) => void;
      const manager = new JwtTokenManager(storage, {
        refresh: () =>
          new Promise((yes, no) => {
            resolve = yes;
            reject = no;
          }),
      });
      const refresh = manager.refresh();
      const settled = refresh.catch(() => undefined);
      storage.signOut();
      if (outcome === 'success') resolve(token('refreshed'));
      else reject(new Error('refresh rejected'));
      await settled;
      expect(storage.get()).toBeNull();
    },
  );

  it.each(['success', 'failure'] as const)(
    'preserves a replacement session after old refresh %s',
    async outcome => {
      let resolve!: (value: CompositeToken) => void;
      let reject!: (error: Error) => void;
      const manager = new JwtTokenManager(storage, {
        refresh: () =>
          new Promise((yes, no) => {
            resolve = yes;
            reject = no;
          }),
      });
      const settled = manager.refresh().catch(() => undefined);
      const replacement = token('replacement');
      storage.signOut();
      storage.signIn(replacement);
      if (outcome === 'success') resolve(token('refreshed'));
      else reject(new Error('refresh rejected'));
      await settled;
      expect(storage.get()?.token).toEqual(replacement);
    },
  );

  it.each(['proactive', '401'])(
    'notifies once for a failed %s refresh through the same Fetcher',
    async trigger => {
      if (trigger === 'proactive') {
        storage.signIn({
          ...token('original'),
          accessToken: jwt('original', Date.now() / 1000 - 1),
        });
      }
      const client = new Fetcher({ baseURL: 'https://example.test' });
      const manager = new JwtTokenManager(
        storage,
        new CoSecTokenRefresher({
          fetcher: client,
          endpoint: '/refresh',
        }),
      );
      client.interceptors.request.use(
        new AuthorizationRequestInterceptor({ tokenManager: manager }),
      );
      client.interceptors.response.use(
        new AuthorizationResponseInterceptor({ tokenManager: manager }),
      );
      const unauthorized: FetchExchange[] = [];
      client.interceptors.error.use(
        new UnauthorizedErrorInterceptor({
          onUnauthorized: exchange => {
            unauthorized.push(exchange);
          },
        }),
      );
      const requested: string[] = [];
      vi.stubGlobal('fetch', async (url: string) => {
        requested.push(url);
        return new Response('', { status: 401 });
      });
      const result = client.get('/resource').then(
        () => 'resolved',
        () => 'rejected',
      );
      let timer: ReturnType<typeof setTimeout> | undefined;
      try {
        const outcome = await Promise.race([
          result,
          new Promise(resolve => {
            timer = setTimeout(() => resolve('pending'), 100);
          }),
        ]);
        expect(outcome).toBe('rejected');
        expect(requested).toEqual(
          trigger === 'proactive'
            ? ['https://example.test/refresh']
            : ['https://example.test/resource', 'https://example.test/refresh'],
        );
        expect(storage.get()).toBeNull();
        expect(unauthorized).toHaveLength(1);
        expect(unauthorized[0].request.url).toMatch(/\/resource$/);
        expect(unauthorized[0].error).toBeInstanceOf(RefreshTokenError);
      } finally {
        clearTimeout(timer);
      }
    },
  );

  it.each([401, 403])(
    'preserves %i notification when only refresh is ignored',
    async status => {
      const client = new Fetcher({ baseURL: 'https://example.test' });
      const manager = new JwtTokenManager(storage, {
        refresh: async old => old,
      });
      client.interceptors.request.use(
        new AuthorizationRequestInterceptor({ tokenManager: manager }),
      );
      client.interceptors.response.use(
        new AuthorizationResponseInterceptor({ tokenManager: manager }),
      );
      const notifications: string[] = [];
      client.interceptors.error.use(
        new UnauthorizedErrorInterceptor({
          onUnauthorized: () => {
            notifications.push('unauthorized');
          },
        }),
      );
      client.interceptors.error.use(
        new ForbiddenErrorInterceptor({
          onForbidden: async () => {
            notifications.push('forbidden');
          },
        }),
      );
      vi.stubGlobal('fetch', async () => new Response('', { status }));

      await expect(
        client.post(
          '/logout',
          {},
          {
            attributes: new Map([[IGNORE_REFRESH_TOKEN_ATTRIBUTE_KEY, true]]),
          },
        ),
      ).rejects.toMatchObject({ exchange: { response: { status } } });
      expect(notifications).toEqual([
        status === 401 ? 'unauthorized' : 'forbidden',
      ]);
    },
  );

  it('notifies once when the refreshed request still returns 401', async () => {
    const client = new Fetcher({ baseURL: 'https://example.test' });
    const refreshed = token('refreshed');
    const manager = new JwtTokenManager(storage, {
      refresh: async () => refreshed,
    });
    client.interceptors.request.use(
      new AuthorizationRequestInterceptor({ tokenManager: manager }),
    );
    client.interceptors.response.use(
      new AuthorizationResponseInterceptor({ tokenManager: manager }),
    );
    const unauthorized: FetchExchange[] = [];
    client.interceptors.error.use(
      new UnauthorizedErrorInterceptor({
        onUnauthorized: exchange => {
          unauthorized.push(exchange);
        },
      }),
    );
    vi.stubGlobal('fetch', async () => new Response('', { status: 401 }));

    await expect(client.get('/resource')).rejects.toMatchObject({
      exchange: { response: { status: 401 } },
    });
    expect(unauthorized).toHaveLength(1);
    expect(storage.get()?.token).toEqual(refreshed);
  });

  it('preserves an explicitly supplied lowercase authorization header', async () => {
    const client = new Fetcher({ baseURL: 'https://example.test' });
    const manager = new JwtTokenManager(storage, { refresh: async old => old });
    client.interceptors.request.use(
      new AuthorizationRequestInterceptor({ tokenManager: manager }),
    );
    let authorization: string | null = null;
    vi.stubGlobal('fetch', async (_url: string, init: RequestInit) => {
      authorization = new Headers(init.headers).get('authorization');
      return new Response('{}');
    });
    await client.get('/resource', {
      headers: { authorization: 'Bearer explicit' },
    });
    expect(authorization).toBe('Bearer explicit');
  });

  it.each([
    ['Authorization', 'Bearer explicit'],
    ['authorization', 'Bearer explicit'],
    ['AUTHORIZATION', ''],
  ])('does not retry a 401 with explicit %s: %j', async (name, credential) => {
    const client = new Fetcher({ baseURL: 'https://example.test' });
    const manager = new JwtTokenManager(storage, {
      refresh: async () => token('refreshed'),
    });
    client.interceptors.request.use(
      new AuthorizationRequestInterceptor({ tokenManager: manager }),
    );
    client.interceptors.response.use(
      new AuthorizationResponseInterceptor({ tokenManager: manager }),
    );
    const original = storage.get();
    const authorizations: (string | null)[] = [];
    vi.stubGlobal('fetch', async (_url: string, init: RequestInit) => {
      const authorization = new Headers(init.headers).get('authorization');
      authorizations.push(authorization);
      return new Response('', {
        status: authorization === credential ? 401 : 200,
      });
    });

    await expect(
      client.post('/resource', { headers: { [name]: credential } }),
    ).rejects.toMatchObject({ exchange: { response: { status: 401 } } });
    expect(authorizations).toEqual([credential]);
    expect(storage.get()).toBe(original);
  });

  it('retries a managed authorization header with the refreshed token', async () => {
    const client = new Fetcher({ baseURL: 'https://example.test' });
    const refreshed = token('refreshed');
    const original = storage.get()!.token;
    const manager = new JwtTokenManager(storage, {
      refresh: async () => refreshed,
    });
    client.interceptors.request.use(
      new AuthorizationRequestInterceptor({ tokenManager: manager }),
    );
    client.interceptors.response.use(
      new AuthorizationResponseInterceptor({ tokenManager: manager }),
    );
    const authorizations: (string | null)[] = [];
    vi.stubGlobal('fetch', async (_url: string, init: RequestInit) => {
      authorizations.push(new Headers(init.headers).get('authorization'));
      return new Response('', {
        status: authorizations.length === 1 ? 401 : 200,
      });
    });

    const response = await client.get('/resource');
    expect(response.status).toBe(200);
    expect(authorizations).toEqual([
      `Bearer ${original.accessToken}`,
      `Bearer ${refreshed.accessToken}`,
    ]);
    expect(storage.get()?.token).toEqual(refreshed);
  });

  it.each(['success', 'failure'] as const)(
    'deduplicates replacement-session refresh independently of old refresh %s',
    async outcome => {
      // Without Web Locks: the two refreshes run side by side in this tab.
      vi.stubGlobal('navigator', {});
      const original = storage.get()!.token;
      const replacement = token('replacement');
      const refreshed = token('replacement-refreshed');
      const requestedTokens: string[] = [];
      let resolveOld!: (value: CompositeToken) => void;
      let rejectOld!: (error: Error) => void;
      let resolveNew!: (value: CompositeToken) => void;
      const manager = new JwtTokenManager(storage, {
        refresh: current => {
          requestedTokens.push(current.accessToken);
          return new Promise((resolve, reject) => {
            if (current.accessToken === original.accessToken) {
              resolveOld = resolve;
              rejectOld = reject;
            } else {
              resolveNew = resolve;
            }
          });
        },
      });
      const first = manager.refresh().catch(() => undefined);
      storage.signOut();
      storage.signIn(replacement);
      const second = manager.refresh().then(
        () => 'refreshed',
        () => 'rejected',
      );
      expect(requestedTokens).toEqual([
        original.accessToken,
        replacement.accessToken,
      ]);

      if (outcome === 'success') resolveOld(token('old-refreshed'));
      else rejectOld(new Error('old refresh failed'));
      await first;
      expect(storage.get()?.token).toEqual(replacement);
      const third = manager.refresh();
      expect(requestedTokens).toHaveLength(2);

      resolveNew(refreshed);
      await expect(second).resolves.toBe('refreshed');
      await third;
      expect(storage.get()?.token).toEqual(refreshed);
    },
  );
});
