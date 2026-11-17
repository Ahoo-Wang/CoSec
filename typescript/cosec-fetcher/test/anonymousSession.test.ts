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
import { it, expect, vi } from 'vitest';
import {
  AuthorizationRequestInterceptor,
  AuthorizationResponseInterceptor,
  JwtTokenManager,
  TokenStorage,
  UnauthorizedErrorInterceptor,
} from '../src';

const token = (id: string) => {
  const jwt = `e30.${btoa(JSON.stringify({ jti: id, exp: Date.now() / 1000 + 7200 }))}.signature`;
  return { accessToken: jwt, refreshToken: jwt };
};

it.each([true, false])(
  'preserves initial anonymous ownership with request interceptor = %s',
  async installRequest => {
    const storage = new TokenStorage({
      storage: new InMemoryStorage(),
      eventBus: new SerialTypedEventBus('anonymous-session'),
    });
    const original = token('login');
    const refreshed = token('refreshed');
    const refresh = vi.fn(async () => refreshed);
    const manager = new JwtTokenManager(storage, { refresh });
    const client = new Fetcher({ baseURL: 'https://example.test' });
    if (installRequest)
      client.interceptors.request.use(
        new AuthorizationRequestInterceptor({ tokenManager: manager }),
      );
    client.interceptors.response.use(
      new AuthorizationResponseInterceptor({ tokenManager: manager }),
    );
    const unauthorized = vi.fn();
    client.interceptors.error.use(
      new UnauthorizedErrorInterceptor({ onUnauthorized: unauthorized }),
    );
    let started!: () => void;
    const sentRequest = new Promise<void>(resolve => {
      started = resolve;
    });
    let respond!: (response: Response) => void;
    const sent: { method?: string; authorization: string | null }[] = [];
    vi.stubGlobal('fetch', async (_url: string, init: RequestInit) => {
      sent.push({
        method: init.method,
        authorization: new Headers(init.headers).get('Authorization'),
      });
      if (sent.length === 1) {
        started();
        return new Promise<Response>(resolve => {
          respond = resolve;
        });
      }
      return new Response('', { status: 200 });
    });
    try {
      const request = client.post('/anonymous').then(
        response => ({ status: response.status }),
        error => ({ error: error.exchange?.error?.name }),
      );
      await sentRequest;
      storage.signIn(original);
      const loggedIn = storage.get();
      respond(new Response('', { status: 401 }));
      expect(await request).toEqual(
        installRequest
          ? { error: 'RefreshSessionChangedError' }
          : { status: 200 },
      );
      expect(sent[0]).toEqual({ method: 'POST', authorization: null });
      expect(sent).toHaveLength(installRequest ? 1 : 2);
      expect(refresh).toHaveBeenCalledTimes(installRequest ? 0 : 1);
      expect(unauthorized).not.toHaveBeenCalled();
      if (installRequest) expect(storage.get()).toBe(loggedIn);
      else expect(storage.get()?.token).toEqual(refreshed);
    } finally {
      storage.destroy();
      storage.eventBus.destroy();
      vi.unstubAllGlobals();
    }
  },
);
