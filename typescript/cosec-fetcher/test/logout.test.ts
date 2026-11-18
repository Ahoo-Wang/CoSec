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
  CoSecConfigurer,
  CoSecTokenRevoker,
  IGNORE_REFRESH_TOKEN_ATTRIBUTE_KEY,
  JwtTokenManager,
  LogoutUnconfirmedError,
  RefreshSessionChangedError,
  TokenStorage,
  type CompositeToken,
  type TokenRevoker,
} from '../src';

const jwt = (sub: string, session: string, generation: number) =>
  `e30.${btoa(JSON.stringify({ sub, sid: session, gen: generation, exp: Date.now() / 1000 + 3600 }))}.signature`;
const pair = (sub: string, session = 's1', generation = 1): CompositeToken => ({
  accessToken: jwt(sub, session, generation),
  refreshToken: jwt(sub, session, generation) + '.refresh',
});
/** What a Fetcher throws for an answer of `status`, holding the request's body: the pair. */
const answered = (status: number, body: CompositeToken) =>
  Object.assign(new Error(`answered ${status}`), {
    exchange: {
      request: { body },
      response: new Response(null, { status }),
    },
  });

/** A promise and the functions that settle it. */
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}

/** Every string reachable from `value`: its own properties, its causes, their exchanges, at any depth. */
function strings(value: unknown, seen = new Set<unknown>()): string[] {
  if (typeof value === 'string') return [value];
  if (value === null || typeof value !== 'object' || seen.has(value)) return [];
  seen.add(value);
  const keys = new Set([
    ...Object.getOwnPropertyNames(value),
    'message',
    'cause',
    'name',
  ]);
  return [...keys].flatMap(key =>
    strings((value as Record<string, unknown>)[key], seen),
  );
}

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

  const manager = (
    tokenRevoker?: TokenRevoker,
    refresh: (token: CompositeToken) => Promise<CompositeToken> = async token =>
      token,
  ) => new JwtTokenManager(storage, { refresh }, { tokenRevoker });

  const revoker = (logout: TokenRevoker['logout']): TokenRevoker => ({
    logout,
  });

  it('ends the session at the server, then here', async () => {
    const token = pair('alice');
    storage.signIn(token);
    const logout = vi.fn(async () => undefined);
    await manager(revoker(logout)).logout();
    expect(logout).toHaveBeenCalledWith(token);
    expect(storage.get()).toBeNull();
  });

  it('signs out here when the server answers 401: the session was not usable any more', async () => {
    const token = pair('alice');
    storage.signIn(token);
    await manager(
      revoker(async () => {
        throw answered(401, token);
      }),
    ).logout();
    expect(storage.get()).toBeNull();
  });

  it.each([
    ['a 503', 503],
    ['a 500', 500],
    ['a network failure', undefined],
  ])(
    'rejects LogoutUnconfirmedError on %s, and signs out here all the same',
    async (_case, status) => {
      const token = pair('alice');
      storage.signIn(token);
      const failure =
        status === undefined
          ? new TypeError('fetch failed')
          : answered(status, token);
      const error = await manager(
        revoker(async () => {
          throw failure;
        }),
      )
        .logout()
        .catch(error => error);
      expect(error).toBeInstanceOf(LogoutUnconfirmedError);
      expect((error as LogoutUnconfirmedError).status).toBe(status);
      expect(storage.get()).toBeNull();
    },
  );

  it('carries no token: not on the error, not on its causes, not on an exchange', async () => {
    const token = pair('alice');
    storage.signIn(token);
    const error = await manager(
      revoker(async () => {
        throw answered(503, token);
      }),
    )
      .logout()
      .catch(error => error);
    const reachable = strings(error);
    expect(reachable.length).toBeGreaterThan(0);
    for (const secret of [token.accessToken, token.refreshToken]) {
      expect(reachable.filter(text => text.includes(secret))).toEqual([]);
    }
    expect(
      strings(answered(503, token)).some(text => text === token.accessToken),
    ).toBe(true);
  });

  it('rejects without a token revoker, and signs out here', async () => {
    storage.signIn(pair('alice'));
    const error = await manager()
      .logout()
      .catch(error => error);
    expect(error).toBeInstanceOf(LogoutUnconfirmedError);
    expect(storage.get()).toBeNull();
  });

  it('does nothing without a stored pair', async () => {
    const logout = vi.fn(async () => undefined);
    await manager(revoker(logout)).logout();
    expect(logout).not.toHaveBeenCalled();
  });

  it('keeps a session another tab signed in while the logout was on its way', async () => {
    storage.signIn(pair('alice', 's1'));
    const next = pair('bob', 's2');
    await manager(
      revoker(async () => {
        storage.signIn(next);
      }),
    ).logout();
    expect(storage.get()?.token).toEqual(next);
  });

  it('waits for a refresh in flight, and ends the session with the pair it stored', async () => {
    const first = pair('alice', 's1', 1);
    const second = pair('alice', 's1', 2);
    storage.signIn(first);
    const answer = deferred<CompositeToken>();
    const sent: CompositeToken[] = [];
    const jwt = manager(
      revoker(async token => {
        sent.push(token);
      }),
      () => answer.promise,
    );
    const refreshing = jwt.refresh();
    const loggingOut = jwt.logout();
    await Promise.resolve();
    expect(sent).toEqual([]);
    answer.resolve(second);
    await refreshing;
    await loggingOut;
    expect(sent).toEqual([second]);
    expect(storage.get()).toBeNull();
  });

  it('a refresh answered after the logout cannot store its answer', async () => {
    const first = pair('alice', 's1', 1);
    storage.signIn(first);
    const ended = deferred<void>();
    const answer = deferred<CompositeToken>();
    const jwt = manager(
      revoker(() => ended.promise),
      () => answer.promise,
    );
    const loggingOut = jwt.logout();
    await Promise.resolve();
    // The refresh starts while the logout is on its way, from the pair still stored.
    const refreshing = jwt.refresh().catch(error => error);
    ended.resolve();
    await loggingOut;
    expect(storage.get()).toBeNull();
    answer.resolve(pair('alice', 's1', 2));
    expect(await refreshing).toBeInstanceOf(RefreshSessionChangedError);
    expect(storage.get()).toBeNull();
  });

  it('is reached through CoSecConfigurer', async () => {
    const token = pair('alice');
    const logout = vi.fn(async () => undefined);
    const configurer = new CoSecConfigurer({
      tokenStorage: storage,
      tokenRefresher: { refresh: async token => token },
      tokenRevoker: revoker(logout),
    });
    storage.signIn(token);
    await configurer.tokenManager!.logout();
    expect(logout).toHaveBeenCalledWith(token);
    expect(storage.get()).toBeNull();
  });
});

describe('CoSecTokenRevoker', () => {
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
    await new CoSecTokenRevoker({ fetcher, endpoint: '/auth/logout' }).logout(
      token,
    );
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
    const error = await new CoSecTokenRevoker({
      fetcher,
      endpoint: '/auth/logout',
    })
      .logout(pair('alice'))
      .catch(error => error);
    expect(error).toBeInstanceOf(HttpStatusValidationError);
  });
});
