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

const jwt = (version: string, exp: number) =>
  `e30.${btoa(JSON.stringify({ sub: 'same-user', jti: version, exp }))}.signature`;
const token = (version: string) => ({
  accessToken: jwt(version, Date.now() / 1000 + 3600),
  refreshToken: jwt(version, Date.now() / 1000 + 7200),
});

describe('authorization after token rotation', () => {
  let storage: TokenStorage;
  beforeEach(() => {
    storage = new TokenStorage({
      storage: new InMemoryStorage(),
      eventBus: new SerialTypedEventBus('token-rotation'),
    });
  });
  afterEach(() => {
    storage.destroy();
    storage.eventBus.destroy();
  });

  it.each([
    'success',
    'unauthorized',
    'refresh expired',
    'two rotations',
    'new login',
  ] as const)(
    'handles a late original-token 401 after rotation: %s',
    async outcome => {
      const original = token('A');
      const refreshed = {
        ...token('B'),
        ...(outcome === 'refresh expired'
          ? { refreshToken: jwt('B', Date.now() / 1000 - 1) }
          : {}),
      };
      const successor = token('C');
      let expectedToken = refreshed;
      storage.signIn(original);
      const client = new Fetcher({ baseURL: 'https://example.test' });
      const refresher = new CoSecTokenRefresher({
        fetcher: client,
        endpoint: '/refresh',
      });
      const refresh = vi.spyOn(refresher, 'refresh');
      const manager = new JwtTokenManager(storage, refresher);
      client.interceptors.request.use(
        new AuthorizationRequestInterceptor({ tokenManager: manager }),
      );
      client.interceptors.response.use(
        new AuthorizationResponseInterceptor({ tokenManager: manager }),
      );
      const onUnauthorized = vi.fn();
      client.interceptors.error.use(
        new UnauthorizedErrorInterceptor({ onUnauthorized }),
      );

      let bothStarted!: () => void;
      const started = new Promise<void>(resolve => {
        bothStarted = resolve;
      });
      const pending = new Map<string, (response: Response) => void>();
      const sent: { path: string; authorization: string | null }[] = [];
      vi.stubGlobal('fetch', async (url: string, init: RequestInit) => {
        const path = new URL(url).pathname;
        if (path === '/refresh')
          return Response.json(
            outcome === 'two rotations' && refresh.mock.calls.length > 1
              ? successor
              : refreshed,
          );
        const authorization = new Headers(init.headers).get('Authorization');
        sent.push({ path, authorization });
        if (authorization === `Bearer ${original.accessToken}`) {
          return new Promise<Response>(resolve => {
            pending.set(path, resolve);
            if (pending.size === 2) bothStarted();
          });
        }
        return new Response('', {
          status: path === '/late' && outcome === 'unauthorized' ? 401 : 200,
        });
      });
      const settled = (path: string) =>
        client.get(path).then(
          response => ({ status: response.status }),
          error => ({
            status: error.exchange?.response?.status,
            error: error.exchange?.error?.name,
          }),
        );
      const first = settled('/first');
      const late = settled('/late');
      await started;
      pending.get('/first')!(new Response('', { status: 401 }));
      expect(await first).toEqual({ status: 200 });
      expect(storage.get()?.token).toEqual(refreshed);
      expect(refresh).toHaveBeenCalledTimes(1);

      if (outcome === 'two rotations') {
        await manager.refresh();
        expectedToken = successor;
      }
      if (outcome === 'new login') {
        const previousSession = storage.get()!;
        storage.signOut();
        storage.signIn(previousSession.token);
        expect(storage.get()).not.toBe(previousSession);
      }
      pending.get('/late')!(new Response('', { status: 401 }));
      const result = await late;
      if (outcome === 'new login') {
        expect(result).toMatchObject({ error: 'RefreshSessionChangedError' });
        expect(sent.filter(request => request.path === '/late')).toEqual([
          { path: '/late', authorization: `Bearer ${original.accessToken}` },
        ]);
      } else {
        expect(result.status).toBe(outcome === 'unauthorized' ? 401 : 200);
        expect(sent.filter(request => request.path === '/late')).toEqual([
          { path: '/late', authorization: `Bearer ${original.accessToken}` },
          {
            path: '/late',
            authorization: `Bearer ${expectedToken.accessToken}`,
          },
        ]);
      }
      expect(refresh).toHaveBeenCalledTimes(
        outcome === 'two rotations' ? 2 : 1,
      );
      expect(onUnauthorized).toHaveBeenCalledTimes(
        outcome === 'unauthorized' ? 1 : 0,
      );
      expect(storage.get()?.token).toEqual(expectedToken);
    },
  );
});
