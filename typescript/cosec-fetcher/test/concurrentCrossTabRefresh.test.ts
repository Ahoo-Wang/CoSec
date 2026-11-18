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
import { describe, it, expect, vi } from 'vitest';
import {
  AuthorizationRequestInterceptor,
  AuthorizationResponseInterceptor,
  JwtTokenManager,
  TokenStorage,
} from '../src';

const token = (version: string) => {
  const jwt = `e30.${btoa(JSON.stringify({ sub: 'same-user', jti: version, exp: Date.now() / 1000 + 7200 }))}.signature`;
  return { accessToken: jwt, refreshToken: jwt };
};

describe('concurrent cross-tab refresh completion', () => {
  it.each([
    ['refresh', 'success'],
    ['refresh', 'failure'],
    ['sign in', 'success'],
    ['sign in', 'failure'],
  ] as const)(
    'handles another tab completing %s before the local refresh %s',
    async (operation, outcome) => {
      // Without Web Locks, so the other tab can complete while ours is still
      // refreshing (with them it waits: see refreshLock.test.ts).
      vi.stubGlobal('navigator', {});
      const key = `cosec-concurrent-${crypto.randomUUID()}`;
      const backing = new InMemoryStorage();
      // Tabs share the browser's localStorage, so their default buses broadcast.
      vi.stubGlobal('window', { localStorage: backing });
      const first = new TokenStorage({ key, storage: backing });
      first.signIn(token('A'));
      await new Promise(resolve => setTimeout(resolve, 0));
      const second = new TokenStorage({ key, storage: backing });
      const original = second.get()!;
      const winner = token('B');
      const discarded = token('C');
      let complete!: (value: ReturnType<typeof token>) => void;
      let fail!: (error: Error) => void;
      let started!: () => void;
      const refreshStarted = new Promise<void>(resolve => {
        started = resolve;
      });
      const refresh = vi.fn(() => {
        started();
        return new Promise<ReturnType<typeof token>>((resolve, reject) => {
          complete = resolve;
          fail = reject;
        });
      });
      const manager = new JwtTokenManager(second, { refresh });
      const client = new Fetcher({ baseURL: 'https://example.test' });
      client.interceptors.request.use(
        new AuthorizationRequestInterceptor({ tokenManager: manager }),
      );
      client.interceptors.response.use(
        new AuthorizationResponseInterceptor({ tokenManager: manager }),
      );
      const sent: (string | null)[] = [];
      vi.stubGlobal('fetch', async (_url: string, init: RequestInit) => {
        sent.push(new Headers(init.headers).get('Authorization'));
        return new Response('', { status: sent.length === 1 ? 401 : 200 });
      });
      try {
        const request = client.get('/data').then(
          response => ({ status: response.status }),
          error => ({ error: error.exchange?.error?.name }),
        );
        await refreshStarted;
        if (operation === 'refresh') {
          await new JwtTokenManager(first, {
            refresh: async () => winner,
          }).refresh();
        } else {
          first.signIn(winner);
        }
        await vi.waitFor(() => expect(second.get()).not.toBe(original));
        const received = second.get();
        if (outcome === 'success') complete(discarded);
        else fail(new Error('Refresh credential already rotated'));
        expect(await request).toEqual(
          operation === 'refresh'
            ? { status: 200 }
            : { error: 'RefreshSessionChangedError' },
        );
        expect(second.get()).toBe(received);
        expect(first.get()?.token).toEqual(winner);
        expect(second.get()?.token).toEqual(winner);
        expect(sent).toEqual(
          operation === 'refresh'
            ? [
                `Bearer ${original.token.accessToken}`,
                `Bearer ${winner.accessToken}`,
              ]
            : [`Bearer ${original.token.accessToken}`],
        );
        expect(refresh).toHaveBeenCalledOnce();
      } finally {
        first.destroy();
        first.eventBus.destroy();
        second.destroy();
        second.eventBus.destroy();
        vi.unstubAllGlobals();
      }
    },
  );
});
