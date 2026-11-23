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
import { RefreshTokenError, RefreshUnavailableError } from '../src';
import { createClient, destroyClients } from './support/client';
import { FakeCoSecHost } from './support/fakeCoSec';

const MINUTE = 60_000;

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

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
});
afterEach(() => {
  destroyClients();
  vi.useRealTimers();
});

type Staging = (
  host: FakeCoSecHost,
  pair: { accessToken: string; refreshToken: string },
) => void;

// DR1-H-1: the request of a refresh has the pair as its body, so the error of
// that request is not kept; an error reporter receives what the caller does.
describe('a failed refresh raises an error that carries no token', () => {
  const failures: [
    string,
    Staging,
    typeof RefreshUnavailableError | typeof RefreshTokenError,
    number | undefined,
  ][] = [
    [
      'a 429',
      host => (host.limitRefreshNext = 1),
      RefreshUnavailableError,
      429,
    ],
    [
      'a 503',
      host => (host.unavailableAfterRecordingNext = 3),
      RefreshUnavailableError,
      503,
    ],
    [
      'a 500 of the application',
      host => (host.refreshHandlerStatus = 500),
      RefreshUnavailableError,
      500,
    ],
    [
      'a network error',
      host => (host.dropNextRefreshRequests = 3),
      RefreshUnavailableError,
      undefined,
    ],
    [
      'a timeout',
      host =>
        (host.after = seen =>
          seen.path === '/auth/refresh'
            ? new Promise<Response>(() => {})
            : new Response(null)),
      RefreshUnavailableError,
      undefined,
    ],
    ['a 401', (host, pair) => host.logout(pair), RefreshTokenError, 401],
  ];

  it.each(failures)('on %s', async (_name, stage, errorType, status) => {
    const host = new FakeCoSecHost();
    const pair = host.login('u1');
    // The real refresher, cut short sooner than its default for the timeout.
    const { client } = createClient(host, {
      signedIn: pair,
      spaced: () => false,
      refreshTimeout: 50,
    });
    vi.setSystemTime(Date.now() + 11 * MINUTE);
    stage(host, pair);

    const thrown = await client.get('/me').catch(error => error);
    expect(thrown).toBeInstanceOf(ExchangeError);
    const error = (thrown as ExchangeError).exchange.error;
    expect(error).toBeInstanceOf(errorType);
    expect((error as RefreshUnavailableError).status).toBe(status);
    // The refresh did go out with the pair: the walk below has something to miss.
    expect(
      host
        .on('/auth/refresh')
        .some(seen => JSON.stringify(seen.body).includes(pair.refreshToken)),
    ).toBe(true);

    const reachable = strings(error);
    expect(reachable.length).toBeGreaterThan(0);
    for (const secret of [pair.accessToken, pair.refreshToken]) {
      expect(reachable.filter(text => text.includes(secret))).toEqual([]);
    }
  });
});
