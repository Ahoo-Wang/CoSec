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

import { Fetcher, HttpStatusValidationError } from '@ahoo-wang/fetcher';
import { SerialTypedEventBus } from '@ahoo-wang/fetcher-eventbus';
import { InMemoryStorage } from '@ahoo-wang/fetcher-storage';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  CoSecTokenRefresher,
  IGNORE_REFRESH_TOKEN_ATTRIBUTE_KEY,
  JwtTokenManager,
  LogoutUnconfirmedError,
  TokenStorage,
  type CompositeToken,
  type TokenRefresher,
} from '../src';

const jwt = (sub: string, session: string) =>
  `e30.${btoa(JSON.stringify({ sub, sid: session, exp: Date.now() / 1000 + 3600 }))}.signature`;
const pair = (sub: string, session = 's1'): CompositeToken => ({
  accessToken: jwt(sub, session),
  refreshToken: jwt(sub, session),
});
/** What a Fetcher throws for an answer of `status`, as far as the manager reads it. */
const answered = (status: number) =>
  Object.assign(new Error(`answered ${status}`), {
    exchange: { response: new Response(null, { status }) },
  });

describe('JwtTokenManager.logout', () => {
  let storage: TokenStorage;
  beforeEach(() => {
    storage = new TokenStorage({
      storage: new InMemoryStorage(),
      eventBus: new SerialTypedEventBus('logout'),
    });
  });
  afterEach(() => {
    storage.destroy();
    storage.eventBus.destroy();
  });

  const manager = (logout?: TokenRefresher['logout']) =>
    new JwtTokenManager(storage, {
      refresh: async token => token,
      ...(logout ? { logout } : {}),
    });

  it('ends the session at the server, then here', async () => {
    const token = pair('alice');
    storage.signIn(token);
    const logout = vi.fn(async () => undefined);
    await manager(logout).logout();
    expect(logout).toHaveBeenCalledWith(token);
    expect(storage.get()).toBeNull();
  });

  it('signs out here when the server answers 401: the session was not usable any more', async () => {
    storage.signIn(pair('alice'));
    await manager(async () => {
      throw answered(401);
    }).logout();
    expect(storage.get()).toBeNull();
  });

  it.each([
    ['a 503', answered(503)],
    ['a 500', answered(500)],
    ['a network failure', new TypeError('fetch failed')],
  ])(
    'rejects LogoutUnconfirmedError on %s, and signs out here all the same',
    async (_case, failure) => {
      storage.signIn(pair('alice'));
      const error = await manager(async () => {
        throw failure;
      })
        .logout()
        .catch(error => error);
      expect(error).toBeInstanceOf(LogoutUnconfirmedError);
      expect(error.cause).toBe(failure);
      expect(storage.get()).toBeNull();
    },
  );

  it('rejects without a logout route, and signs out here', async () => {
    storage.signIn(pair('alice'));
    const error = await manager()
      .logout()
      .catch(error => error);
    expect(error).toBeInstanceOf(LogoutUnconfirmedError);
    expect(storage.get()).toBeNull();
  });

  it('does nothing without a stored pair', async () => {
    const logout = vi.fn(async () => undefined);
    await manager(logout).logout();
    expect(logout).not.toHaveBeenCalled();
  });

  it('keeps a session another tab signed in while the logout was on its way', async () => {
    storage.signIn(pair('alice', 's1'));
    const next = pair('bob', 's2');
    await manager(async () => {
      storage.signIn(next);
    }).logout();
    expect(storage.get()?.token).toEqual(next);
  });
});

describe('CoSecTokenRefresher.logout', () => {
  it('posts the pair to the logout route, marked as a refresh is', async () => {
    const sent: { url: string; body: unknown }[] = [];
    const fetcher = new Fetcher({
      baseURL: 'https://api.test',
      fetch: async (input, init) => {
        sent.push({ url: String(input), body: JSON.parse(String(init?.body)) });
        return new Response(null, { status: 204 });
      },
    });
    const attributes: unknown[] = [];
    fetcher.interceptors.request.use({
      name: 'spy',
      order: 0,
      intercept: exchange => {
        attributes.push(
          exchange.attributes.get(IGNORE_REFRESH_TOKEN_ATTRIBUTE_KEY),
        );
      },
    });
    const token = pair('alice');
    await new CoSecTokenRefresher({
      fetcher,
      endpoint: '/auth/refresh',
      logoutEndpoint: '/auth/logout',
    }).logout(token);
    expect(sent).toEqual([
      { url: 'https://api.test/auth/logout', body: token },
    ]);
    expect(attributes).toEqual([true]);
  });

  it('rejects with the answer when the route does not end the session', async () => {
    const fetcher = new Fetcher({
      baseURL: 'https://api.test',
      fetch: async () => new Response(null, { status: 503 }),
    });
    const error = await new CoSecTokenRefresher({
      fetcher,
      endpoint: '/auth/refresh',
      logoutEndpoint: '/auth/logout',
    })
      .logout(pair('alice'))
      .catch(error => error);
    expect(error).toBeInstanceOf(HttpStatusValidationError);
  });

  it('rejects without a logout endpoint', async () => {
    const error = await new CoSecTokenRefresher({
      fetcher: new Fetcher({ baseURL: 'https://api.test' }),
      endpoint: '/auth/refresh',
    })
      .logout(pair('alice'))
      .catch(error => error);
    expect(error.message).toContain('No logoutEndpoint');
  });
});
