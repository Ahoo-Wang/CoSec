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
  JwtCompositeTokenSerializer,
  TokenStorage,
} from '../src';

const token = (version: string) => {
  const jwt = `e30.${btoa(JSON.stringify({ sub: 'same-user', jti: version, exp: Date.now() / 1000 + 7200 }))}.signature`;
  return { accessToken: jwt, refreshToken: jwt };
};

describe.each(['current', 'legacy'] as const)(
  '%s token sessions through real BroadcastChannel',
  format => {
    it.each(['refresh', 'sign out and sign in', 'direct sign in'] as const)(
      'handles a delayed 401 after another tab performs %s',
      async operation => {
        const key = `cosec-cross-tab-${crypto.randomUUID()}`;
        const backing = new InMemoryStorage();
        // Tabs share the browser's localStorage, so their default buses broadcast.
        vi.stubGlobal('window', { localStorage: backing });
        const original = token('A');
        if (format === 'legacy') backing.setItem(key, JSON.stringify(original));
        const first = new TokenStorage({ key, storage: backing });
        if (format === 'current') first.signIn(original);
        // Let the first write finish before connecting the second context.
        await new Promise(resolve => setTimeout(resolve, 0));
        const second = new TokenStorage({ key, storage: backing });
        try {
          const receivedA = second.get()!;
          expect(receivedA).not.toBe(first.get());
          const rotated = token('B');
          const refreshFirst = vi.fn(async () => rotated);
          const managerFirst = new JwtTokenManager(first, {
            refresh: refreshFirst,
          });
          const refreshSecond = vi.fn(async () => rotated);
          const managerSecond = new JwtTokenManager(second, {
            refresh: refreshSecond,
          });
          const client = new Fetcher({ baseURL: 'https://example.test' });
          client.interceptors.request.use(
            new AuthorizationRequestInterceptor({
              tokenManager: managerSecond,
            }),
          );
          client.interceptors.response.use(
            new AuthorizationResponseInterceptor({
              tokenManager: managerSecond,
            }),
          );
          let started!: () => void;
          const requestStarted = new Promise<void>(resolve => {
            started = resolve;
          });
          let finish!: (response: Response) => void;
          const sent: (string | null)[] = [];
          vi.stubGlobal('fetch', async (_url: string, init: RequestInit) => {
            sent.push(new Headers(init.headers).get('Authorization'));
            if (sent.length === 1) {
              started();
              return new Promise<Response>(resolve => {
                finish = resolve;
              });
            }
            return new Response('', { status: 200 });
          });
          const request = client.get('/delayed').then(
            response => ({ status: response.status }),
            error => ({ error: error.exchange?.error?.name }),
          );
          await requestStarted;
          if (operation === 'refresh') {
            await managerFirst.refresh();
          } else {
            if (operation === 'sign out and sign in') first.signOut();
            // Identical JWT strings must still be a different login session.
            first.signIn(original);
          }
          await vi.waitFor(() => expect(second.get()).not.toBe(receivedA));
          expect(second.get()).not.toBe(first.get());
          finish(new Response('', { status: 401 }));
          const result = await request;
          if (operation === 'refresh') {
            expect(result).toEqual({ status: 200 });
            expect(sent).toEqual([
              `Bearer ${original.accessToken}`,
              `Bearer ${rotated.accessToken}`,
            ]);
            expect(refreshFirst).toHaveBeenCalledOnce();
          } else {
            expect(result).toEqual({ error: 'RefreshSessionChangedError' });
            expect(sent).toEqual([`Bearer ${original.accessToken}`]);
            expect(refreshFirst).not.toHaveBeenCalled();
          }
          expect(refreshSecond).not.toHaveBeenCalled();
        } finally {
          first.destroy();
          first.eventBus.destroy();
          second.destroy();
          second.eventBus.destroy();
          vi.unstubAllGlobals();
        }
      },
    );
  },
);

describe('legacy session identifiers', () => {
  it('uses canonical token fields, retains the migrated ID, and hides raw JWTs', () => {
    const original = token('canonical');
    const first = new JwtCompositeTokenSerializer().deserialize(
      JSON.stringify(original),
    );
    const second = new JwtCompositeTokenSerializer().deserialize(
      JSON.stringify({
        refreshToken: original.refreshToken,
        accessToken: original.accessToken,
      }),
    );
    expect(first.sessionId).toBe(second.sessionId);
    expect(first.sessionId).toMatch(/^legacy:[0-9a-f]{32}$/);
    expect(first.sessionId).not.toContain(original.accessToken);
    expect(first.sessionId).not.toContain(original.refreshToken);
    const restored = new JwtCompositeTokenSerializer().deserialize(
      new JwtCompositeTokenSerializer().serialize(first),
    );
    expect(restored.sessionId).toBe(first.sessionId);
    expect(restored.token).toEqual(original);
  });

  it('separates different access or refresh strings even for the same JWT subject', () => {
    const original = token('first');
    const changed = token('second');
    const serializer = new JwtCompositeTokenSerializer();
    const first = serializer.deserialize(JSON.stringify(original));
    for (const pair of [
      { ...original, accessToken: changed.accessToken },
      { ...original, refreshToken: changed.refreshToken },
    ]) {
      expect(serializer.deserialize(JSON.stringify(pair)).sessionId).not.toBe(
        first.sessionId,
      );
    }
  });
});
