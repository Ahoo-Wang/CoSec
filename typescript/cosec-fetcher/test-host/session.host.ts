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
import {
  getCoSecProblem,
  LogoutUnconfirmedError,
  type CoSecProblem,
} from '../src';
import {
  createHostClient,
  destroyHostClients,
  endSession,
  refreshError,
  revocationsDown,
  revocationsUp,
  sessionsDown,
  sessionsUp,
  refreshAt,
  login,
  loginExpired,
  recordWire,
  resetStats,
  stats,
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
    expect(await response.json()).toEqual({ order: '1' });
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

  it('refreshes before it sends a call whose access token it sees has expired', async () => {
    const { client, tokens } = createHostClient();
    const pair = await loginExpired('alice');
    tokens.signIn(pair);
    expect(tokens.get()?.access.isExpired).toBe(true);
    const wire = recordWire();
    const response = await client.get('/orders/1');
    expect(response.status).toBe(200);
    // No 401 was ever on the wire: the client read the expiry itself.
    expect(wire).toEqual(['/auth/refresh 200', '/orders/1 200']);
    expect(await stats()).toEqual({ logins: 1, refreshes: 1 });
    expect(tokens.get()?.token.accessToken).not.toBe(pair.accessToken);
  });

  it('refreshes once and retries after the host answers 401 token-expired', async () => {
    // The client's clock is behind the host's, so the token still looks valid to it: the one case where only the
    // host's 401 tells the client the token has expired.
    const { client, tokens } = createHostClient();
    const pair = await loginExpired('alice');
    tokens.signIn(pair);
    vi.useFakeTimers({ toFake: ['Date'], now: Date.now() - 3_600_000 });
    expect(tokens.get()?.access.isExpired).toBe(false);
    const wire = recordWire();
    const response = await client.get('/orders/1');
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ order: '1' });
    // The host said 401 to the first try, the refresh renewed the pair, the retry was answered.
    expect(wire).toEqual([
      '/orders/1 401',
      '/auth/refresh 200',
      '/orders/1 200',
    ]);
    expect(await stats()).toEqual({ logins: 1, refreshes: 1 });
    expect(tokens.get()?.token.accessToken).not.toBe(pair.accessToken);
  });

  it('puts one refresh on the wire for calls that find the token expired', async () => {
    const { client, tokens } = createHostClient();
    tokens.signIn(await loginExpired('alice'));
    const wire = recordWire();
    const responses = await Promise.all([
      client.get('/orders/1'),
      client.get('/orders/2'),
      client.get('/orders/3'),
    ]);
    expect(responses.map(response => response.status)).toEqual([200, 200, 200]);
    expect(wire.filter(line => line.startsWith('/auth/refresh'))).toEqual([
      '/auth/refresh 200',
    ]);
    expect(wire.some(line => line.endsWith(' 401'))).toBe(false);
    expect((await stats()).refreshes).toBe(1);
  });

  it('puts one refresh on the wire for calls the host refused 401 token-expired', async () => {
    const { client, tokens } = createHostClient();
    tokens.signIn(await loginExpired('alice'));
    vi.useFakeTimers({ toFake: ['Date'], now: Date.now() - 3_600_000 });
    const wire = recordWire();
    const responses = await Promise.all([
      client.get('/orders/1'),
      client.get('/orders/2'),
      client.get('/orders/3'),
    ]);
    expect(responses.map(response => response.status)).toEqual([200, 200, 200]);
    expect(wire.filter(line => line.endsWith(' 401'))).toHaveLength(3);
    expect(wire.filter(line => line.startsWith('/auth/refresh'))).toEqual([
      '/auth/refresh 200',
    ]);
    expect((await stats()).refreshes).toBe(1);
  });

  it('ends the session when the host refuses the refresh with 401', async () => {
    const onUnauthorized = vi.fn();
    const { client, tokens } = createHostClient({ config: { onUnauthorized } });
    const pair = await loginExpired('alice');
    tokens.signIn(pair);
    // The pair was never rotated, so the 401 answers the ended session, not a pair presented again.
    await endSession(pair);
    const wire = recordWire();
    const error = await client.get('/orders/1').catch(error => error);
    expect(error).toBeInstanceOf(Error);
    expect(wire).toEqual(['/auth/refresh 401']);
    expect(tokens.get()).toBeNull();
    expect((await stats()).refreshes).toBe(1);
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });

  it.each([
    [
      'cannot read the revocations (503)',
      '/auth/refresh 503',
      revocationsDown,
      revocationsUp,
    ],
    [
      'fails with an error (500)',
      '/auth/refresh 500',
      () => refreshError(true),
      () => refreshError(false),
    ],
  ] as const)(
    'keeps the pair when the host %s, and the next call refreshes',
    async (_case, refused, fail, recover) => {
      const onUnauthorized = vi.fn();
      // No retries inside the call: the first call is the one that sees the failure.
      const { client, tokens } = createHostClient({
        config: { onUnauthorized, refreshRetryDelays: [] },
      });
      const pair = await loginExpired('alice');
      tokens.signIn(pair);
      await fail();
      const wire = recordWire();
      const error = await client
        .get('/orders/1')
        .catch(error => error)
        .finally(recover);
      expect(error).toBeInstanceOf(Error);
      expect(wire).toEqual([refused]);
      expect(tokens.get()?.token).toEqual(pair);
      expect(onUnauthorized).not.toHaveBeenCalled();

      const response = await client.get('/orders/1');
      expect(response.status).toBe(200);
      expect(wire).toEqual([refused, '/auth/refresh 200', '/orders/1 200']);
      expect(await stats()).toEqual({ logins: 1, refreshes: 2 });
      expect(tokens.get()?.token.accessToken).not.toBe(pair.accessToken);
    },
  );
});

// DR1-H-2: a login may answer members besides the pair, and the refresh and
// logout routes accept exactly the pair: only the pair is sent back.
describe('a sign-in answer with members besides the pair', () => {
  const withExtras = <T extends object>(pair: T) => ({
    ...pair,
    expiresIn: 600,
    user: { name: 'alice' },
  });

  it('refreshes against a real host', async () => {
    const { client, tokens } = createHostClient();
    const pair = await loginExpired('alice');
    tokens.signIn(withExtras(pair));
    const wire = recordWire();
    expect((await client.get('/orders/1')).status).toBe(200);
    expect(wire).toEqual(['/auth/refresh 200', '/orders/1 200']);
    expect(tokens.get()?.token.accessToken).not.toBe(pair.accessToken);
  });

  it('logs out against a real host, which ends the session', async () => {
    const { configurer, tokens } = createHostClient();
    const pair = await login('alice');
    tokens.signIn(withExtras(pair));
    const wire = recordWire();
    await configurer.tokenManager!.logout();
    expect(wire).toEqual(['/auth/logout 204']);
    expect((await refreshAt(pair)).status).toBe(401);
  });
});

describe('logout against a real host', () => {
  it('ends the session at the host, then here', async () => {
    const { client, configurer, tokens } = createHostClient();
    const pair = await login('alice');
    tokens.signIn(pair);
    const wire = recordWire();
    await configurer.tokenManager!.logout();
    expect(wire).toEqual(['/auth/logout 204']);
    expect(tokens.get()).toBeNull();
    // The pair no longer refreshes anywhere: the host ended its session.
    expect((await refreshAt(pair)).status).toBe(401);
    expect((await client.get('/hello')).status).toBe(200);
  });

  it('waits for a refresh in flight and ends the session it renewed', async () => {
    const { client, configurer, tokens } = createHostClient();
    tokens.signIn(await loginExpired('alice'));
    const wire = recordWire();
    const call = client.get('/orders/1').catch(error => error);
    // The refresh has reached the host: the logout is called while it is in flight, or just after it stored.
    while ((await stats()).refreshes === 0) {
      await new Promise(done => setTimeout(done, 5));
    }
    await configurer.tokenManager!.logout();
    await call;
    const session = wire.filter(line => line.startsWith('/auth/'));
    // Not the rotated pair's 401: the logout sent the pair the refresh stored.
    expect(session).toEqual(['/auth/refresh 200', '/auth/logout 204']);
    expect(tokens.get()).toBeNull();
  });

  it('resolves for a session the host had already ended', async () => {
    const { configurer, tokens } = createHostClient();
    const pair = await login('alice');
    tokens.signIn(pair);
    await endSession(pair);
    const wire = recordWire();
    await configurer.tokenManager!.logout();
    expect(wire).toEqual(['/auth/logout 204']);
    expect(tokens.get()).toBeNull();
  });

  it('rejects LogoutUnconfirmedError when the host cannot end the session, and signs out here all the same', async () => {
    const { configurer, tokens } = createHostClient();
    const pair = await login('alice');
    tokens.signIn(pair);
    await sessionsDown();
    const wire = recordWire();
    const error = await configurer
      .tokenManager!.logout()
      .catch(error => error)
      .finally(sessionsUp);
    expect(error).toBeInstanceOf(LogoutUnconfirmedError);
    expect((error as LogoutUnconfirmedError).problem?.slug).toBe('unavailable');
    expect(wire).toEqual(['/auth/logout 503']);
    expect(tokens.get()).toBeNull();
    // What the recommendation costs: the session lives on at the host until it ends there.
    expect((await refreshAt(pair)).status).toBe(200);
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

    // The host announces 127.0.0.1 and listens on it only: localhost is another origin for the same host (Node resolves it
    // to ::1 first, refused, then to 127.0.0.1, which answers).
    const other = new URL('/test/echo', client.urlBuilder.baseURL);
    other.hostname = 'localhost';
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
