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
import { SerialTypedEventBus } from '@ahoo-wang/fetcher-eventbus';
import { InMemoryStorage } from '@ahoo-wang/fetcher-storage';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  RefreshTokenError,
  RefreshUnavailableError,
  TokenStorage,
  type CoSecProblem,
} from '../src';
import { createClient, destroyClients } from './support/client';
import { FakeCoSecHost } from './support/fakeCoSec';

const SECOND = 1000;
const MINUTE = 60 * SECOND;

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
});
afterEach(() => {
  destroyClients();
  vi.useRealTimers();
});

function signedInExpired(host: FakeCoSecHost, config = {}) {
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

const refreshError = (error: unknown) => {
  expect(error).toBeInstanceOf(ExchangeError);
  return (error as ExchangeError).exchange.error;
};
const outcomes = (host: FakeCoSecHost) =>
  host.refreshes.map(refresh => refresh.outcome);
const sent = (host: FakeCoSecHost) => host.on('/auth/refresh').length;

describe('a refresh whose response was lost (M2)', () => {
  it('is retried with the same pair at once, inside the leeway, and the request goes on', async () => {
    const host = new FakeCoSecHost();
    const { client, tokens, pair } = signedInExpired(host);
    host.loseNextRefreshResponses = 1;
    const response = await client.get('/me');
    expect(response.status).toBe(200);
    // the server recorded the move once, and answered the retry
    expect(outcomes(host)).toEqual(['moved', 'healed']);
    expect(host.sessionOf(pair).revoked).toBe(false);
    expect(tokens.get()?.token.accessToken).not.toBe(pair.accessToken);
  });

  it('is retried as often as there are pauses, and then reported as no answer', async () => {
    const host = new FakeCoSecHost();
    const { client, tokens, pair, onUnauthorized } = signedInExpired(host);
    host.loseNextRefreshResponses = 10;
    const error = refreshError(await client.get('/me').catch(e => e));
    expect(error).toBeInstanceOf(RefreshUnavailableError);
    expect(sent(host)).toBe(3);
    expect(tokens.get()?.token).toEqual(pair);
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it('is retried by the next request while the window is open, and heals', async () => {
    const host = new FakeCoSecHost();
    const { client, tokens, pair } = signedInExpired(host);
    host.loseNextRefreshResponses = 3;
    await client.get('/me').catch(() => undefined);
    vi.setSystemTime(Date.now() + 10 * SECOND);
    expect((await client.get('/me')).status).toBe(200);
    expect(host.sessionOf(pair).revoked).toBe(false);
    expect(tokens.get()?.token.accessToken).not.toBe(pair.accessToken);
  });

  it('is retried only until the window closes, measured from the first attempt', async () => {
    const host = new FakeCoSecHost();
    const { client } = signedInExpired(host);
    host.loseNextRefreshResponses = 100;
    await client.get('/me').catch(() => undefined); // t0: 3 sends
    expect(sent(host)).toBe(3);
    vi.setSystemTime(Date.now() + 10 * SECOND); // t0 + 10 s
    await client.get('/me').catch(() => undefined);
    expect(sent(host)).toBe(6);
    vi.setSystemTime(Date.now() + 10 * SECOND); // t0 + 20 s: still inside
    await client.get('/me').catch(() => undefined);
    expect(sent(host)).toBe(9);
    // t0 + 26 s: the window (30 s less a 5 s margin) is closed. t0 did not
    // slide with the attempts after it, so this is a late attempt: one send.
    vi.setSystemTime(Date.now() + 6 * SECOND);
    await client.get('/me').catch(() => undefined);
    expect(sent(host)).toBe(10);
  });

  it('keeps the window across a reload: another instance over the same storage', async () => {
    const host = new FakeCoSecHost();
    const storage = new InMemoryStorage();
    const open = () =>
      new TokenStorage({
        key: 'reload',
        storage,
        eventBus: new SerialTypedEventBus<never>('reload'),
      });
    const first = open();
    const pair = host.login('u1');
    first.signIn(pair);
    const one = createClient(host, {
      tokens: first,
      spaced: () => false,
    });
    vi.setSystemTime(Date.now() + 11 * MINUTE);
    host.loseNextRefreshResponses = 100;
    await one.client.get('/me').catch(() => undefined);
    expect(sent(host)).toBe(3);

    vi.setSystemTime(Date.now() + 26 * SECOND); // the page is reloaded
    const two = createClient(host, { tokens: open(), spaced: () => false });
    await two.client.get('/me').catch(() => undefined);
    expect(sent(host)).toBe(4); // a late attempt: one send, not three
  });

  it('is a gamble after the window: it revokes the session if the first attempt was recorded', async () => {
    const host = new FakeCoSecHost();
    const { client, tokens, pair, onUnauthorized } = signedInExpired(host);
    host.loseNextRefreshResponses = 100;
    await client.get('/me').catch(() => undefined);
    vi.setSystemTime(Date.now() + 40 * SECOND);
    const error = refreshError(await client.get('/me').catch(e => e));
    // the server saw a pair presented again after the leeway (IDN-16)
    expect(host.refreshes.at(-1)?.outcome).toBe('revoked');
    expect(host.sessionOf(pair).revoked).toBe(true);
    // the session is over for the client, and the application is told why
    expect(error).toBeInstanceOf(RefreshTokenError);
    expect((error as RefreshTokenError).problem?.slug).toBe('token-revoked');
    expect(tokens.get()).toBeNull();
    expect((onUnauthorized.mock.calls[0][1] as CoSecProblem).slug).toBe(
      'token-revoked',
    );
  });

  it('saves the session after the window if the first attempt never arrived', async () => {
    const host = new FakeCoSecHost();
    const { client, tokens, pair } = signedInExpired(host);
    host.dropNextRefreshRequests = 3;
    await client.get('/me').catch(() => undefined);
    expect(sent(host)).toBe(3);
    vi.setSystemTime(Date.now() + 40 * SECOND);
    expect((await client.get('/me')).status).toBe(200);
    expect(outcomes(host)).toEqual(['moved']);
    expect(tokens.get()?.token.accessToken).not.toBe(pair.accessToken);
  });

  it('makes one late attempt at a time, for concurrent requests', async () => {
    const host = new FakeCoSecHost();
    const { client } = signedInExpired(host);
    host.loseNextRefreshResponses = 100;
    await client.get('/me').catch(() => undefined);
    vi.setSystemTime(Date.now() + 40 * SECOND);
    const before = sent(host);
    await Promise.allSettled([
      client.get('/me'),
      client.get('/me'),
      client.get('/me'),
    ]);
    expect(sent(host) - before).toBe(1);
  });

  it('forgets the attempt when the pair is replaced', async () => {
    const host = new FakeCoSecHost();
    const { client } = signedInExpired(host);
    host.loseNextRefreshResponses = 3;
    await client.get('/me').catch(() => undefined);
    vi.setSystemTime(Date.now() + 10 * SECOND);
    await client.get('/me'); // healed: a new pair
    vi.setSystemTime(Date.now() + 11 * MINUTE); // it expires; far past t0
    host.loseNextRefreshResponses = 100;
    const before = sent(host);
    await client.get('/me').catch(() => undefined);
    expect(sent(host) - before).toBe(3); // a fresh window, not a late attempt
  });
});

describe('a refresh refused before it ran moved nothing', () => {
  it('resolves its attempt: the next unknown outcome has a window of its own', async () => {
    const host = new FakeCoSecHost();
    const { client } = signedInExpired(host);
    host.limitRefreshNext = 1;
    await client.get('/me').catch(() => undefined); // 429
    vi.setSystemTime(Date.now() + MINUTE);
    host.loseNextRefreshResponses = 100;
    const before = sent(host);
    await client.get('/me').catch(() => undefined);
    expect(sent(host) - before).toBe(3);
  });

  it('is not repeated before its Retry-After', async () => {
    const host = new FakeCoSecHost();
    host.retryAfter = 7;
    const { client } = signedInExpired(host);
    host.limitRefreshNext = 1;
    await client.get('/me').catch(() => undefined);
    expect(sent(host)).toBe(1);
    vi.setSystemTime(Date.now() + 3 * SECOND);
    const error = refreshError(await client.get('/me').catch(e => e));
    expect(error).toBeInstanceOf(RefreshUnavailableError);
    expect((error as RefreshUnavailableError).problem?.slug).toBe(
      'too-many-requests',
    );
    expect(sent(host)).toBe(1); // the route was not called
    vi.setSystemTime(Date.now() + 5 * SECOND);
    expect((await client.get('/me')).status).toBe(200);
    expect(sent(host)).toBe(2);
  });

  it('does not clear an older attempt that is still unknown', async () => {
    const host = new FakeCoSecHost();
    host.retryAfter = 1;
    const { client } = signedInExpired(host);
    host.loseNextRefreshResponses = 3;
    await client.get('/me').catch(() => undefined); // t0, unknown
    vi.setSystemTime(Date.now() + 26 * SECOND);
    host.limitRefreshNext = 1;
    await client.get('/me').catch(() => undefined); // a 429: moved nothing
    vi.setSystemTime(Date.now() + 2 * SECOND);
    host.loseNextRefreshResponses = 100;
    const before = sent(host);
    await client.get('/me').catch(() => undefined);
    expect(sent(host) - before).toBe(1); // still late: t0 is where it was
  });
});
