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

import { ExchangeError } from '@ahoo-wang/fetcher';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  RefreshTokenError,
  RefreshUnavailableError,
  type CoSecProblem,
} from '../src';
import { createClient, destroyClients } from './support/client';
import { FakeCoSecHost } from './support/fakeCoSec';

const MINUTE = 60_000;

/** The error the refresh raised: the request rejects with an ExchangeError around it. */
function refreshError(error: unknown): unknown {
  expect(error).toBeInstanceOf(ExchangeError);
  return (error as ExchangeError).exchange.error;
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
});
afterEach(() => {
  destroyClients();
  vi.useRealTimers();
});

/** A signed-in client whose access token has expired. */
function expired(host: FakeCoSecHost, config = {}) {
  const pair = host.login('u1');
  const onUnauthorized = vi.fn();
  const made = createClient(host, {
    signedIn: pair,
    spaced: () => false,
    config: { onUnauthorized, ...config },
  });
  vi.setSystemTime(Date.now() + 11 * MINUTE);
  return { ...made, pair, onUnauthorized };
}

describe('which refresh failures end the session (M1)', () => {
  it('keeps the session on a 429 of the refresh route', async () => {
    const host = new FakeCoSecHost();
    const { client, tokens, pair, onUnauthorized } = expired(host);
    host.limitRefreshNext = 1;
    const error = refreshError(await client.get('/me').catch(error => error));
    expect(error).toBeInstanceOf(RefreshUnavailableError);
    expect((error as RefreshUnavailableError).problem?.slug).toBe(
      'too-many-requests',
    );
    expect(tokens.get()?.token).toEqual(pair);
    expect(onUnauthorized).not.toHaveBeenCalled();
    expect(host.on('/me')).toHaveLength(0);
  });

  it('refreshes at the next request once the limit is gone', async () => {
    const host = new FakeCoSecHost();
    const { client, tokens, pair } = expired(host);
    host.limitRefreshNext = 1;
    await client.get('/me').catch(() => undefined);
    // Retry-After has passed
    vi.setSystemTime(Date.now() + 10_000);
    expect((await client.get('/me')).status).toBe(200);
    expect(tokens.get()?.token.accessToken).not.toBe(pair.accessToken);
  });

  it.each([400, 403, 404, 500, 503])(
    'keeps the session on a %i from the refresh handler of the application',
    async status => {
      const host = new FakeCoSecHost();
      const { client, tokens, pair, onUnauthorized } = expired(host);
      host.refreshHandlerStatus = status;
      const error = refreshError(await client.get('/me').catch(error => error));
      expect(error).toBeInstanceOf(RefreshUnavailableError);
      expect(tokens.get()?.token).toEqual(pair);
      expect(onUnauthorized).not.toHaveBeenCalled();
    },
  );

  it('keeps the session on a network error', async () => {
    const host = new FakeCoSecHost();
    const { client, tokens, pair, onUnauthorized } = expired(host);
    host.dropNextRefreshRequests = 3;
    const error = refreshError(await client.get('/me').catch(error => error));
    expect(error).toBeInstanceOf(RefreshUnavailableError);
    expect(tokens.get()?.token).toEqual(pair);
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it.each(['token-revoked', 'token-invalid', 'token-expired'] as const)(
    'ends the session on a 401 %s, and tells the application why',
    async slug => {
      const host = new FakeCoSecHost();
      const { client, tokens, pair, onUnauthorized } = expired(host);
      if (slug === 'token-revoked') host.logout(pair);
      if (slug === 'token-expired') host.expireSessions();
      if (slug === 'token-invalid') host.forgetSessions();
      const error = refreshError(await client.get('/me').catch(error => error));
      expect(error).toBeInstanceOf(RefreshTokenError);
      expect((error as RefreshTokenError).problem?.slug).toBe(slug);
      expect(tokens.get()).toBeNull();
      expect(onUnauthorized).toHaveBeenCalledTimes(1);
      expect((onUnauthorized.mock.calls[0][1] as CoSecProblem).slug).toBe(slug);
    },
  );

  it('ends the session on a status the application says ends it (isRefreshRejected)', async () => {
    const host = new FakeCoSecHost();
    const { client, tokens, onUnauthorized } = expired(host, {
      isRefreshRejected: (error: unknown) =>
        (error as { exchange?: { response?: { status?: number } } }).exchange
          ?.response?.status === 400,
    });
    host.refreshHandlerStatus = 400;
    const error = refreshError(await client.get('/me').catch(error => error));
    expect(error).toBeInstanceOf(RefreshTokenError);
    expect(tokens.get()).toBeNull();
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });
});

describe('the refresh route and anonymous callers (M7)', () => {
  it('refreshes with the expired access token when the route lets anonymous callers in', async () => {
    const host = new FakeCoSecHost({ anonymousRefresh: true });
    const { client, tokens, pair } = expired(host);
    expect((await client.get('/me')).status).toBe(200);
    const sent = host.on('/auth/refresh')[0];
    // the expired access token goes along: the server reads it as anonymous
    expect(sent.headers.get('authorization')).toBe(
      `Bearer ${pair.accessToken}`,
    );
    expect(sent.body).toEqual(pair);
    expect(tokens.get()?.token.accessToken).not.toBe(pair.accessToken);
  });

  it('signs the user out when the route is closed to anonymous callers, and says token-expired', async () => {
    const host = new FakeCoSecHost({ anonymousRefresh: false });
    const { client, tokens, onUnauthorized } = expired(host);
    const error = refreshError(await client.get('/me').catch(error => error));
    // 401 token-expired is also what a session past its lifetime answers: the
    // client cannot tell them apart; the documentation says what to allow.
    expect(error).toBeInstanceOf(RefreshTokenError);
    expect((error as RefreshTokenError).problem?.slug).toBe('token-expired');
    expect(tokens.get()).toBeNull();
    expect((onUnauthorized.mock.calls[0][1] as CoSecProblem).slug).toBe(
      'token-expired',
    );
  });
});
