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
import { afterEach, describe, expect, it, vi } from 'vitest';
import { getCoSecProblem, type CoSecProblem } from '../src';
import { hostUrl, type HostPair } from '../../test-host/hostApi';
import {
  createHostClient,
  destroyHostClients,
  recordWire,
} from '../test-host/support';

// The gateway, then a service behind it: a request goes through CoSec's gateway, which authenticates and decides, and the
// service answers what reached it. The paths are the system test's own platform document (test/system-test/documents).
const ORDER = '/behind/orders/1';
const DENIED = '/behind/denied/1';

afterEach(() => {
  destroyHostClients();
  vi.useRealTimers();
});

/**
 * Signs in at the system's login host through the gateway, as an application's own login does. With `accessSeconds` the
 * access token lasts that long, negative for one that has already expired: what a client holds when its token ran out.
 */
async function signIn(
  username: string,
  accessSeconds?: number,
): Promise<HostPair> {
  const response = await fetch(`${hostUrl()}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, tenant: 'ts-client', accessSeconds }),
  });
  if (!response.ok) throw new Error(`/auth/login answered ${response.status}`);
  return (await response.json()) as HostPair;
}

describe('the client through the gateway', () => {
  it('signs in, and a permitted call reaches the service as that principal', async () => {
    const { client, tokens } = createHostClient();
    tokens.signIn(await signIn('tess'));
    const response = await client.get(ORDER);
    expect(response.status).toBe(200);
    const echo = (await response.json()) as {
      security: { principal: string; tenant: string; authenticated: boolean };
      headers: Record<string, string[]>;
    };
    expect(echo.security).toMatchObject({
      principal: 'tess',
      tenant: 'ts-client',
      authenticated: true,
    });
    // The client's own token stayed at the gateway; the service got the identity the gateway verified.
    expect(echo.headers['cosec-identity']).toHaveLength(1);
    expect(echo.headers['authorization']).toBeUndefined();
  });

  it('parses a call the gateway refuses as forbidden, and keeps the session', async () => {
    const onForbidden = vi.fn();
    const { client, tokens } = createHostClient({ config: { onForbidden } });
    tokens.signIn(await signIn('tess'));
    const error = await client.get(DENIED).catch(error => error);
    expect(error).toBeInstanceOf(HttpStatusValidationError);
    expect(onForbidden).toHaveBeenCalledTimes(1);
    expect((onForbidden.mock.calls[0][1] as CoSecProblem).slug).toBe(
      'forbidden',
    );
    expect(getCoSecProblem(error)?.status).toBe(403);
    expect(tokens.authenticated).toBe(true);
  });

  it('answers an anonymous caller unauthorized, and starts no refresh', async () => {
    const onUnauthorized = vi.fn();
    const { client } = createHostClient({ config: { onUnauthorized } });
    const wire = recordWire();
    const error = await client.get(ORDER).catch(error => error);
    expect(error).toBeInstanceOf(HttpStatusValidationError);
    expect((onUnauthorized.mock.calls[0][1] as CoSecProblem).slug).toBe(
      'unauthorized',
    );
    expect(wire).toEqual([`${ORDER} 401`]);
  });

  it('refreshes before it sends a call whose access token it sees has expired', async () => {
    const { client, tokens } = createHostClient();
    const pair = await signIn('tess', -3600);
    tokens.signIn(pair);
    expect(tokens.get()?.access.isExpired).toBe(true);
    const wire = recordWire();
    const response = await client.get(ORDER);
    expect(response.status).toBe(200);
    // No 401 was on the wire: the client read the expiry itself, and the gateway routed the refresh to the login host.
    expect(wire).toEqual(['/auth/refresh 200', `${ORDER} 200`]);
    expect(tokens.get()?.token.accessToken).not.toBe(pair.accessToken);
  });

  it('refreshes once and retries after the gateway answers 401 token-expired', async () => {
    // The client's clock is behind the gateway's, so the token still looks valid to it: the one case where only the
    // gateway's 401 tells the client the token has expired.
    const { client, tokens } = createHostClient();
    tokens.signIn(await signIn('tess', -3600));
    vi.useFakeTimers({ toFake: ['Date'], now: Date.now() - 7_200_000 });
    expect(tokens.get()?.access.isExpired).toBe(false);
    const wire = recordWire();
    const response = await client.get(ORDER);
    expect(response.status).toBe(200);
    expect(wire).toEqual([`${ORDER} 401`, '/auth/refresh 200', `${ORDER} 200`]);
  });

  it('signs out at the host through the gateway, and the pair no longer refreshes', async () => {
    const { client, configurer, tokens } = createHostClient();
    const pair = await signIn('tess');
    tokens.signIn(pair);
    const wire = recordWire();
    await configurer.tokenManager!.logout();
    expect(wire).toEqual(['/auth/logout 204']);
    expect(tokens.get()).toBeNull();
    const refresh = await fetch(`${hostUrl()}/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(pair),
    });
    expect(refresh.status).toBe(401);
    // Anonymous again, and refused as such.
    const error = await client.get(ORDER).catch(error => error);
    expect(error).toBeInstanceOf(HttpStatusValidationError);
  });
});
