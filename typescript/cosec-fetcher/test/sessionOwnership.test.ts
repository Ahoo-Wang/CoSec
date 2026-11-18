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

describe('refresh session ownership', () => {
  let storage: TokenStorage;
  beforeEach(() => {
    storage = new TokenStorage({
      storage: new InMemoryStorage(),
      eventBus: new SerialTypedEventBus('session-ownership'),
    });
  });
  afterEach(() => {
    storage.destroy();
    storage.eventBus.destroy();
  });

  function configure(client: Fetcher, refreshClient = client) {
    const manager = new JwtTokenManager(
      storage,
      new CoSecTokenRefresher({ fetcher: refreshClient, endpoint: '/refresh' }),
    );
    client.interceptors.request.use(
      new AuthorizationRequestInterceptor({ tokenManager: manager }),
    );
    client.interceptors.response.use(
      new AuthorizationResponseInterceptor({ tokenManager: manager }),
    );
    return manager;
  }

  it('does not refresh a replacement session for an earlier managed 401', async () => {
    const original = token('A');
    const replacement = token('B');
    storage.signIn(original);
    const client = new Fetcher({ baseURL: 'https://example.test' });
    configure(client);
    const requests: (string | null)[] = [];
    vi.stubGlobal('fetch', async (url: string, init: RequestInit) => {
      if (url.endsWith('/refresh')) return Response.json(token('B-refreshed'));
      requests.push(new Headers(init.headers).get('Authorization'));
      if (requests.length === 1) {
        storage.signOut();
        storage.signIn(replacement);
        return new Response('', { status: 401 });
      }
      return new Response('');
    });
    await expect(client.post('/transfer')).rejects.toMatchObject({
      exchange: { error: { name: 'RefreshSessionChangedError' } },
    });
    expect(requests).toEqual([`Bearer ${original.accessToken}`]);
    expect(storage.get()?.token).toEqual(replacement);
  });

  it.each([
    ['proactive', 'success'],
    ['401', 'success'],
    ['proactive', 'failure'],
    ['401', 'failure'],
  ])(
    'preserves session B when %s refresh %s emits a storage event',
    async (trigger, outcome) => {
      const original = token('A', trigger === 'proactive');
      const replacement = token('B');
      const refreshed = token('A-refreshed');
      storage.signIn(original);
      const client = new Fetcher({ baseURL: 'https://example.test' });
      configure(client);
      const notifications: (string | undefined)[] = [];
      client.interceptors.error.use(
        new UnauthorizedErrorInterceptor({
          onUnauthorized: () => {
            notifications.push(storage.currentUser?.sub);
            storage.signOut();
          },
        }),
      );
      storage.addListener({
        name: 'replace-on-refresh-write',
        handle: event => {
          if (
            outcome === 'success'
              ? event.newValue?.token.accessToken === refreshed.accessToken
              : event.newValue === null &&
                event.oldValue?.token.accessToken === original.accessToken
          ) {
            storage.signOut();
            storage.signIn(replacement);
          }
        },
      });
      const requests: (string | null)[] = [];
      vi.stubGlobal('fetch', async (url: string, init: RequestInit) => {
        if (url.endsWith('/refresh'))
          return outcome === 'success'
            ? Response.json(refreshed)
            : new Response('', { status: 401 });
        requests.push(new Headers(init.headers).get('Authorization'));
        return new Response('', {
          status: trigger === '401' && requests.length === 1 ? 401 : 200,
        });
      });
      const result = await client.post('/transfer').then(
        () => 'resolved',
        error => error.exchange?.error?.name,
      );
      expect(result).toBe(
        outcome === 'success'
          ? 'RefreshSessionChangedError'
          : 'RefreshTokenError',
      );
      expect(storage.get()?.token).toEqual(replacement);
      expect(notifications).toEqual([]);
      expect(requests).toEqual(
        trigger === 'proactive' ? [] : [`Bearer ${original.accessToken}`],
      );
    },
  );

  it.each([
    ['proactive', 'success', 'same'],
    ['401', 'success', 'same'],
    ['proactive', 'failure', 'same'],
    ['401', 'failure', 'same'],
    ['proactive', 'failure', 'refresh-only'],
    ['401', 'failure', 'refresh-only'],
  ])(
    'aborts the old %s request after replacement and refresh %s (%s handler)',
    async (trigger, outcome, handler) => {
      const original = token('A', trigger === 'proactive');
      const replacement = token('B');
      storage.signIn(original);
      const client = new Fetcher({ baseURL: 'https://example.test' });
      const refreshClient =
        handler === 'same'
          ? client
          : new Fetcher({ baseURL: 'https://example.test' });
      configure(client, refreshClient);
      let notifications = 0;
      refreshClient.interceptors.error.use(
        new UnauthorizedErrorInterceptor({
          onUnauthorized: () => {
            notifications++;
            storage.signOut();
          },
        }),
      );
      let markStarted!: () => void;
      const started = new Promise<void>(resolve => {
        markStarted = resolve;
      });
      let finishRefresh!: (response: Response) => void;
      const requests: (string | null)[] = [];
      vi.stubGlobal('fetch', async (url: string, init: RequestInit) => {
        if (url.endsWith('/refresh')) {
          markStarted();
          return new Promise<Response>(resolve => {
            finishRefresh = resolve;
          });
        }
        requests.push(new Headers(init.headers).get('Authorization'));
        return new Response('', {
          status: trigger === '401' && requests.length === 1 ? 401 : 200,
        });
      });
      const request = client.post('/transfer').then(
        () => 'resolved',
        () => 'rejected',
      );
      await started;
      storage.signOut();
      storage.signIn(replacement);
      finishRefresh(
        outcome === 'success'
          ? new Response(JSON.stringify(token('A-refreshed')))
          : new Response('', { status: 401 }),
      );

      expect(await request).toBe('rejected');
      expect(storage.get()?.token).toEqual(replacement);
      expect(notifications).toBe(0);
      expect(requests).toEqual(
        trigger === 'proactive' ? [] : [`Bearer ${original.accessToken}`],
      );
    },
  );

  it('notifies a direct refresher 401 without an outer exchange', async () => {
    const client = new Fetcher({ baseURL: 'https://example.test' });
    const notifications: string[] = [];
    client.interceptors.error.use(
      new UnauthorizedErrorInterceptor({
        onUnauthorized: exchange => {
          notifications.push(exchange.request.url);
        },
      }),
    );
    vi.stubGlobal('fetch', async () => new Response('', { status: 401 }));
    const refresher = new CoSecTokenRefresher({
      fetcher: client,
      endpoint: '/refresh',
    });
    await expect(refresher.refresh(token('A'))).rejects.toBeDefined();
    expect(notifications).toEqual(['https://example.test/refresh']);
  });

  it('notifies on the refresh client when the API client has no handler', async () => {
    storage.signIn(token('A'));
    const client = new Fetcher({ baseURL: 'https://example.test' });
    const refreshClient = new Fetcher({ baseURL: 'https://example.test' });
    configure(client, refreshClient);
    const notifications: string[] = [];
    refreshClient.interceptors.error.use(
      new UnauthorizedErrorInterceptor({
        onUnauthorized: exchange => {
          notifications.push(exchange.request.url);
          storage.signOut();
        },
      }),
    );
    vi.stubGlobal('fetch', async () => new Response('', { status: 401 }));
    await expect(client.post('/transfer')).rejects.toMatchObject({
      exchange: { error: { name: 'RefreshTokenError' } },
    });
    expect(notifications).toEqual(['https://example.test/refresh']);
    expect(storage.get()).toBeNull();
  });
});
