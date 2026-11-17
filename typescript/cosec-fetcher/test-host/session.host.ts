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

import { HttpStatusValidationError } from '@ahoo-wang/fetcher';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { getCoSecProblem, type CoSecProblem } from '../src';
import {
  createHostClient,
  destroyHostClients,
  endSession,
  login,
  resetStats,
  stats,
  waitForExpiry,
} from './support';

beforeEach(resetStats);
afterEach(() => {
  destroyHostClients();
  vi.useRealTimers();
});

describe('a signed-in session against a real host', () => {
  it('signs in, and a permitted call is answered with the access token', async () => {
    const { client, tokens } = createHostClient();
    tokens.signIn(await login('alice'));
    const response = await client.get('/orders/1');
    expect(response.status).toBe(200);
    expect(await response.text()).toBe('order-1');
    expect(tokens.authenticated).toBe(true);
    expect(await stats()).toEqual({ logins: 1, refreshes: 0 });
  });

  it('parses a denied call as forbidden, and keeps the session', async () => {
    const onForbidden = vi.fn();
    const { client, tokens } = createHostClient({ config: { onForbidden } });
    tokens.signIn(await login('alice'));
    const error = await client.delete('/orders/1').catch(error => error);
    expect(error).toBeInstanceOf(HttpStatusValidationError);
    expect(onForbidden).toHaveBeenCalledTimes(1);
    const problem = onForbidden.mock.calls[0][1] as CoSecProblem;
    expect(problem.slug).toBe('forbidden');
    expect(problem.status).toBe(403);
    expect(getCoSecProblem(error)?.slug).toBe('forbidden');
    expect(tokens.authenticated).toBe(true);
    expect((await stats()).refreshes).toBe(0);
  });

  it('answers an anonymous caller 401 and starts no refresh', async () => {
    const onUnauthorized = vi.fn();
    const { client } = createHostClient({ config: { onUnauthorized } });
    const error = await client.get('/orders/1').catch(error => error);
    expect(error).toBeInstanceOf(HttpStatusValidationError);
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
    expect((onUnauthorized.mock.calls[0][1] as CoSecProblem).slug).toBe(
      'unauthorized',
    );
    expect((await stats()).refreshes).toBe(0);
  });

  it('signs out: the next call is anonymous, and nothing is refreshed', async () => {
    const { client, tokens } = createHostClient();
    tokens.signIn(await login('alice'));
    expect((await client.get('/orders/1')).status).toBe(200);
    tokens.signOut();
    const error = await client.get('/orders/1').catch(error => error);
    expect(error).toBeInstanceOf(HttpStatusValidationError);
    expect(tokens.authenticated).toBe(false);
    expect((await stats()).refreshes).toBe(0);
  });

  it('refreshes before it sends a call whose access token has expired', async () => {
    const { client, tokens } = createHostClient();
    const pair = await login('alice');
    tokens.signIn(pair);
    await waitForExpiry(pair);
    const response = await client.get('/orders/1');
    expect(response.status).toBe(200);
    expect(await stats()).toEqual({ logins: 1, refreshes: 1 });
    expect(tokens.get()?.token.accessToken).not.toBe(pair.accessToken);
  });

  it('refreshes once and retries after the host answers 401 token-expired', async () => {
    // The client's clock is behind the host's, so the token still looks valid to it: the one case where only the
    // host's 401 tells the client the token has expired.
    const { client, tokens } = createHostClient();
    const pair = await login('alice');
    tokens.signIn(pair);
    await waitForExpiry(pair);
    vi.useFakeTimers({ toFake: ['Date'], now: Date.now() - 60_000 });
    expect(tokens.get()?.access.isExpired).toBe(false);
    const answers: string[] = [];
    const send = globalThis.fetch;
    vi.stubGlobal('fetch', async (...args: Parameters<typeof fetch>) => {
      const response = await send(...args);
      answers.push(`${new URL(String(args[0])).pathname} ${response.status}`);
      return response;
    });
    const response = await client.get('/orders/1');
    expect(response.status).toBe(200);
    expect(await response.text()).toBe('order-1');
    // The host said 401 to the first try, the refresh renewed the pair, the retry was answered.
    expect(answers).toEqual([
      '/orders/1 401',
      '/auth/refresh 200',
      '/orders/1 200',
    ]);
    expect(await stats()).toEqual({ logins: 1, refreshes: 1 });
    expect(tokens.get()?.token.accessToken).not.toBe(pair.accessToken);
  });

  it('puts one refresh on the wire for calls that race the expiry', async () => {
    const { client, tokens } = createHostClient();
    const pair = await login('alice');
    tokens.signIn(pair);
    await waitForExpiry(pair);
    const responses = await Promise.all([
      client.get('/orders/1'),
      client.get('/orders/2'),
      client.get('/orders/3'),
    ]);
    expect(responses.map(response => response.status)).toEqual([200, 200, 200]);
    expect((await stats()).refreshes).toBe(1);
  });

  it('ends the session when the host refuses the refresh with 401', async () => {
    const onUnauthorized = vi.fn();
    const { client, tokens } = createHostClient({ config: { onUnauthorized } });
    const pair = await login('alice');
    tokens.signIn(pair);
    await endSession(pair);
    await waitForExpiry(pair);
    const error = await client.get('/orders/1').catch(error => error);
    expect(error).toBeInstanceOf(Error);
    expect(tokens.get()).toBeNull();
    expect((await stats()).refreshes).toBe(1);
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });
});

describe('credentials on the wire', () => {
  it('sends the token and the CoSec headers to the origin of the baseURL only', async () => {
    const { client, tokens } = createHostClient();
    tokens.signIn(await login('alice'));
    const own = (await (await client.get('/test/echo')).json()) as Record<
      string,
      string
    >;
    expect(own.authorization).toMatch(/^Bearer /);
    expect(own['cosec-device-id']).toBeTruthy();
    expect(own['cosec-request-id']).toBeTruthy();

    // 127.0.0.1 is another origin than localhost, though it is the same host.
    const other = new URL('/test/echo', client.urlBuilder.baseURL);
    other.hostname = '127.0.0.1';
    const bare = (await (await client.get(other.toString())).json()) as Record<
      string,
      string
    >;
    expect(bare.authorization).toBeUndefined();
    expect(bare['cosec-device-id']).toBeUndefined();
    expect(bare['cosec-request-id']).toBeUndefined();
    expect((await stats()).refreshes).toBe(0);
  });
});
