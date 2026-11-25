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

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { RefreshTokenError, TenantSwitchError } from '../src';
import {
  createHostClient,
  destroyHostClients,
  endSession,
  login,
  loginExpired,
  recordWire,
  refreshAt,
  resetStats,
  revocationsDown,
  revocationsUp,
  switchAt,
} from './support';

beforeEach(resetStats);
afterEach(() => {
  destroyHostClients();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

/** The client's clock an hour behind the host's: a token the host refuses as expired still looks valid here. */
const clockBehind = () =>
  vi.useFakeTimers({ toFake: ['Date'], now: Date.now() - 3_600_000 });

const switchError = async (promise: Promise<unknown>) => {
  const error = await promise.then(
    () => undefined,
    (error: unknown) => error,
  );
  expect(error).toBeInstanceOf(TenantSwitchError);
  return error as TenantSwitchError;
};

describe('a tenant switch against a real host (IDN-22)', () => {
  it('stores the pair of the target tenant, and moves nothing of the session it was presented from', async () => {
    const { client, configurer, tokens } = createHostClient();
    const pair = await login('alice');
    tokens.signIn(pair);
    const wire = recordWire();

    await configurer.tokenManager!.switchTenant({
      endpoint: '/auth/switch/acme',
    });

    expect(tokens.currentUser?.tenantId).toBe('acme');
    expect((await client.get('/orders/1')).status).toBe(200);
    expect(wire).toEqual(['/auth/switch/acme 200', '/orders/1 200']);
    expect((await refreshAt(pair)).status).toBe(200);
  });

  it('refreshes an expired access token first, and presents the refreshed pair', async () => {
    const wire = recordWire();
    const { configurer, tokens } = createHostClient();
    tokens.signIn(await loginExpired('alice'));

    await configurer.tokenManager!.switchTenant({
      endpoint: '/auth/switch/acme',
    });

    expect(wire).toEqual(['/auth/refresh 200', '/auth/switch/acme 200']);
    expect(tokens.currentUser?.tenantId).toBe('acme');
  });

  it('ends the session here when the host refuses the pair: removed, onUnauthorized once, not retried', async () => {
    const onUnauthorized = vi.fn();
    const { configurer, tokens } = createHostClient({
      config: { onUnauthorized },
    });
    const pair = await login('alice');
    tokens.signIn(pair);
    await endSession(pair);
    const wire = recordWire();

    const error = await switchError(
      configurer.tokenManager!.switchTenant({ endpoint: '/auth/switch/acme' }),
    );

    expect(error.sessionEnded).toBe(true);
    expect(error.status).toBe(401);
    expect(error.problem?.slug).toBe('token-revoked');
    expect(tokens.get()).toBeNull();
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
    expect(wire).toEqual(['/auth/switch/acme 401']);
  });

  it('refreshes once when the host finds the access token expired, and switches with the new pair', async () => {
    const onUnauthorized = vi.fn();
    const { configurer, tokens } = createHostClient({
      config: { onUnauthorized },
    });
    tokens.signIn(await loginExpired('alice'));
    clockBehind();
    expect(tokens.get()?.access.isExpired).toBe(false);
    const wire = recordWire();

    await configurer.tokenManager!.switchTenant({
      endpoint: '/auth/switch/acme',
    });

    expect(wire).toEqual([
      '/auth/switch/acme 401',
      '/auth/refresh 200',
      '/auth/switch/acme 200',
    ]);
    expect(tokens.currentUser?.tenantId).toBe('acme');
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it('ends the session through the refresh when the host refuses it: one notification, no second switch', async () => {
    const onUnauthorized = vi.fn();
    const { configurer, tokens } = createHostClient({
      config: { onUnauthorized },
    });
    const pair = await loginExpired('alice');
    tokens.signIn(pair);
    await endSession(pair);
    clockBehind();
    const wire = recordWire();

    const error = await configurer
      .tokenManager!.switchTenant({ endpoint: '/auth/switch/acme' })
      .catch((error: unknown) => error);

    expect(error).toBeInstanceOf(RefreshTokenError);
    expect(wire).toEqual(['/auth/switch/acme 401', '/auth/refresh 401']);
    expect(tokens.get()).toBeNull();
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });

  it("keeps the session on the application's own refusal", async () => {
    const { configurer, tokens } = createHostClient();
    const pair = await login('alice');
    tokens.signIn(pair);

    const error = await switchError(
      configurer.tokenManager!.switchTenant({
        endpoint: '/auth/switch/closed',
      }),
    );

    expect(error.sessionEnded).toBe(false);
    expect(error.status).toBe(403);
    expect(error.problem).toBeUndefined();
    expect(tokens.get()?.token).toEqual(pair);
  });

  it('keeps the session while the revocations cannot be read, and switches once they can', async () => {
    const { configurer, tokens } = createHostClient();
    const pair = await login('alice');
    tokens.signIn(pair);
    await revocationsDown();
    let error: TenantSwitchError;
    try {
      error = await switchError(
        configurer.tokenManager!.switchTenant({
          endpoint: '/auth/switch/acme',
        }),
      );
    } finally {
      await revocationsUp();
    }

    expect(error.sessionEnded).toBe(false);
    expect(error.status).toBe(503);
    expect(error.problem?.slug).toBe('unavailable');
    expect(tokens.get()?.token).toEqual(pair);
    await configurer.tokenManager!.switchTenant({
      endpoint: '/auth/switch/acme',
    });
    expect(tokens.currentUser?.tenantId).toBe('acme');
  });

  it('switches again with the same pair, as a retry after a lost answer would', async () => {
    const pair = await login('alice');
    expect((await switchAt('acme', pair)).status).toBe(200);
    const { configurer, tokens } = createHostClient();
    tokens.signIn(pair);

    await configurer.tokenManager!.switchTenant({
      endpoint: '/auth/switch/acme',
    });

    expect(tokens.currentUser?.tenantId).toBe('acme');
  });

  it('holds a session that ends with the login (IDN-36): its refresh is refused once the login is ended', async () => {
    const { configurer, tokens } = createHostClient();
    const pair = await login('alice');
    tokens.signIn(pair);
    await configurer.tokenManager!.switchTenant({
      endpoint: '/auth/switch/acme',
    });
    await endSession(pair);

    const error = await configurer
      .tokenManager!.refresh()
      .catch(error => error);

    expect(error).toBeInstanceOf(RefreshTokenError);
    expect((error as RefreshTokenError).problem?.slug).toBe('token-revoked');
    expect(tokens.get()).toBeNull();
  });
});
