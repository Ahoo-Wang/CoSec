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

import { SerialTypedEventBus } from '@ahoo-wang/fetcher-eventbus';
import { InMemoryStorage } from '@ahoo-wang/fetcher-storage';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { CoSecTenantSwitcher } from '../src';
import {
  JwtTokenManager,
  RefreshSessionChangedError,
  TenantSwitchError,
  TokenStorage,
  type TokenRefresher,
} from '../src';
import { createClient, destroyClients } from './support/client';
import { claims, FakeCoSecHost } from './support/fakeCoSec';

/** Web Locks as far as the client uses them: one holder per name, in turn. */
function fakeLocks() {
  const tails = new Map<string, Promise<unknown>>();
  const request = vi.fn(
    <T>(name: string, callback: () => Promise<T>): Promise<T> => {
      const run = (tails.get(name) ?? Promise.resolve()).then(callback);
      tails.set(
        name,
        run.catch(() => undefined),
      );
      return run;
    },
  );
  return { request };
}

/** A promise and the function that settles it. */
function gate() {
  let open!: () => void;
  const opened = new Promise<void>(resolve => (open = resolve));
  return { opened, open };
}

const wire = (host: FakeCoSecHost) =>
  host.seen.map(seen => `${seen.path} ${seen.status}`);

const switchError = async (promise: Promise<unknown>) => {
  const error = await promise.then(
    () => undefined,
    (error: unknown) => error,
  );
  expect(error).toBeInstanceOf(TenantSwitchError);
  return error as TenantSwitchError;
};

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
});
afterEach(() => {
  destroyClients();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('a tenant switch with the session pair (IDN-22)', () => {
  it('presents one pair, in Authorization and in the body, and stores the pair it answers', async () => {
    const host = new FakeCoSecHost();
    const pair = host.login();
    const { configurer, tokens, spaces } = createClient(host, {
      signedIn: pair,
    });
    spaces.set('w1');

    await configurer.tokenManager!.switchTenant({
      endpoint: '/auth/switch/acme',
      body: { app: 'orders', accessToken: 'not this one' },
    });

    const [sent] = host.on('/auth/switch/acme');
    const body = sent.body as Record<string, string>;
    expect(sent.status).toBe(200);
    expect(body.accessToken).toBe(pair.accessToken);
    expect(body.refreshToken).toBe(pair.refreshToken);
    expect(body.app).toBe('orders');
    expect(sent.headers.get('authorization')).toBe(
      `Bearer ${pair.accessToken}`,
    );
    expect(sent.headers.get('cosec-device-id')).toBeTruthy();
    expect(sent.headers.get('cosec-space-id')).toBeNull();
    expect(tokens.currentUser?.tenantId).toBe('acme');
    // The switch moves nothing of the session it was presented from.
    expect(host.sessionOf(pair)).toEqual({ generation: 1, revoked: false });
  });

  it('refreshes an expired access token first, and presents the refreshed pair', async () => {
    const host = new FakeCoSecHost();
    const pair = host.login();
    const { configurer, tokens } = createClient(host, { signedIn: pair });
    vi.setSystemTime(Date.now() + 11 * 60_000);

    await configurer.tokenManager!.switchTenant({
      endpoint: '/auth/switch/acme',
    });

    expect(wire(host)).toEqual(['/auth/refresh 200', '/auth/switch/acme 200']);
    const body = host.on('/auth/switch/acme')[0].body as Record<string, string>;
    expect(body.refreshToken).not.toBe(pair.refreshToken);
    expect(claims(body.refreshToken)['cosec.generation']).toBe(2);
    expect(tokens.currentUser?.tenantId).toBe('acme');
  });

  it('holds back a refresh asked while it is on its way: nothing of the old session is refreshed', async () => {
    const host = new FakeCoSecHost();
    const { configurer, tokens } = createClient(host, {
      signedIn: host.login(),
    });
    const hold = gate();
    host.after = async (seen, response) => {
      if (seen.path.startsWith('/auth/switch/')) await hold.opened;
      return response;
    };
    const manager = configurer.tokenManager!;

    const switching = manager.switchTenant({ endpoint: '/auth/switch/acme' });
    const refreshing = manager.refresh();
    hold.open();
    await Promise.all([switching, refreshing]);

    expect(host.on('/auth/refresh')).toHaveLength(0);
    expect(tokens.currentUser?.tenantId).toBe('acme');
  });

  it('is not sent twice at once: a second switch waits for the first and presents its pair', async () => {
    const host = new FakeCoSecHost();
    const { configurer, tokens } = createClient(host, {
      signedIn: host.login(),
    });
    const manager = configurer.tokenManager!;

    await Promise.all([
      manager.switchTenant({ endpoint: '/auth/switch/acme' }),
      manager.switchTenant({ endpoint: '/auth/switch/globex' }),
    ]);

    const [first, second] = host.seen;
    expect(first.path).toBe('/auth/switch/acme');
    expect(
      claims((second.body as { accessToken: string }).accessToken).tenantId,
    ).toBe('acme');
    expect(tokens.currentUser?.tenantId).toBe('globex');
  });
});

describe('a refused switch', () => {
  it.each([
    [
      'revoked',
      'token-revoked',
      (host: FakeCoSecHost, pair: ReturnType<FakeCoSecHost['login']>) =>
        host.logout(pair),
    ],
    ['over', 'token-expired', (host: FakeCoSecHost) => host.expireSessions()],
  ] as const)(
    'ends the session here when it is %s: the pair removed, onUnauthorized once, nothing retried',
    async (_, slug, end) => {
      const host = new FakeCoSecHost();
      const pair = host.login();
      const onUnauthorized = vi.fn();
      const { configurer, tokens } = createClient(host, {
        signedIn: pair,
        config: { onUnauthorized },
      });
      end(host, pair);

      const error = await switchError(
        configurer.tokenManager!.switchTenant({
          endpoint: '/auth/switch/acme',
        }),
      );

      expect(error.sessionEnded).toBe(true);
      expect(error.status).toBe(401);
      expect(error.problem?.slug).toBe(slug);
      expect(tokens.get()).toBeNull();
      expect(onUnauthorized).toHaveBeenCalledTimes(1);
      expect(host.on('/auth/switch/acme')).toHaveLength(1);
      expect(host.on('/auth/refresh')).toHaveLength(0);
    },
  );

  it('ends the session without onUnauthorized when no callback is configured', async () => {
    const host = new FakeCoSecHost();
    const pair = host.login();
    const { configurer, tokens } = createClient(host, { signedIn: pair });
    host.logout(pair);

    const error = await switchError(
      configurer.tokenManager!.switchTenant({ endpoint: '/auth/switch/acme' }),
    );

    expect(error.sessionEnded).toBe(true);
    expect(tokens.get()).toBeNull();
  });

  it('leaves a pair that another sign-in stored meanwhile, and asks for no sign-in', async () => {
    const host = new FakeCoSecHost();
    const pair = host.login();
    const other = host.login('u2');
    const onUnauthorized = vi.fn();
    const { configurer, tokens } = createClient(host, {
      signedIn: pair,
      config: { onUnauthorized },
    });
    host.logout(pair);
    host.after = (seen, response) => {
      if (seen.path.startsWith('/auth/switch/')) tokens.signIn(other);
      return response;
    };

    const error = await switchError(
      configurer.tokenManager!.switchTenant({ endpoint: '/auth/switch/acme' }),
    );

    expect(error.sessionEnded).toBe(true);
    expect(tokens.get()?.token).toEqual(other);
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it('revokes nothing: an older pair is never presented, since the stored one is read under the lock', async () => {
    const host = new FakeCoSecHost();
    const { configurer } = createClient(host, { signedIn: host.login() });
    vi.setSystemTime(Date.now() + 11 * 60_000);
    await configurer.tokenManager!.refresh();
    vi.setSystemTime(Date.now() + 60_000); // past the leeway of the move

    await configurer.tokenManager!.switchTenant({
      endpoint: '/auth/switch/acme',
    });

    expect(host.switches).toEqual([{ generation: 2, outcome: 'switched' }]);
  });
});

describe('a switch that issued nothing keeps the session', () => {
  it.each([
    [
      'a 503',
      '/auth/switch/acme',
      503,
      'unavailable',
      (host: FakeCoSecHost) => (host.unavailableNextSwitches = 1),
    ],
    [
      "the application's own refusal",
      '/auth/switch/closed',
      403,
      undefined,
      () => undefined,
    ],
    [
      'a lost answer',
      '/auth/switch/acme',
      undefined,
      undefined,
      (host: FakeCoSecHost) => (host.loseNextSwitchResponses = 1),
    ],
  ] as const)(
    'on %s, without a retry',
    async (_, endpoint, status, slug, fault) => {
      const host = new FakeCoSecHost();
      const pair = host.login();
      const onUnauthorized = vi.fn();
      const { configurer, tokens } = createClient(host, {
        signedIn: pair,
        config: { onUnauthorized },
      });
      fault(host);

      const error = await switchError(
        configurer.tokenManager!.switchTenant({ endpoint }),
      );

      expect(error.sessionEnded).toBe(false);
      expect(error.status).toBe(status);
      expect(error.problem?.slug).toBe(slug);
      expect(tokens.get()?.token).toEqual(pair);
      expect(onUnauthorized).not.toHaveBeenCalled();
      expect(host.on(endpoint)).toHaveLength(1);
    },
  );

  it('switches when called again after a lost answer: the pair is still current', async () => {
    const host = new FakeCoSecHost();
    const pair = host.login();
    const { configurer, tokens } = createClient(host, { signedIn: pair });
    host.loseNextSwitchResponses = 1;
    const manager = configurer.tokenManager!;

    await switchError(manager.switchTenant({ endpoint: '/auth/switch/acme' }));
    await manager.switchTenant({ endpoint: '/auth/switch/acme' });

    expect(host.switches.map(each => each.outcome)).toEqual([
      'switched',
      'switched',
    ]);
    expect(host.sessionOf(pair)).toEqual({ generation: 1, revoked: false });
    expect(tokens.currentUser?.tenantId).toBe('acme');
  });

  it('carries no token on the error', async () => {
    const host = new FakeCoSecHost();
    const pair = host.login();
    const { configurer } = createClient(host, { signedIn: pair });
    host.unavailableNextSwitches = 1;

    const error = await switchError(
      configurer.tokenManager!.switchTenant({ endpoint: '/auth/switch/acme' }),
    );

    const seen =
      JSON.stringify(error, Object.getOwnPropertyNames(error)) +
      String(error.cause) +
      error.stack;
    expect(seen).not.toContain(pair.accessToken);
    expect(seen).not.toContain(pair.refreshToken);
    expect(error).not.toHaveProperty('exchange');
  });

  it('rejects without a tenantSwitcher, or without a signed-in session, and sends nothing', async () => {
    const host = new FakeCoSecHost();
    const { configurer } = createClient(host, {
      signedIn: host.login(),
      config: { tenantSwitcher: undefined },
    });
    await expect(
      configurer.tokenManager!.switchTenant({ endpoint: '/auth/switch/acme' }),
    ).rejects.toThrow(/No tenantSwitcher/);

    const signedOut = createClient(host);
    await expect(
      signedOut.configurer.tokenManager!.switchTenant({
        endpoint: '/auth/switch/acme',
      }),
    ).rejects.toThrow('No token found');
    expect(host.seen).toHaveLength(0);
  });
});

describe('tabs of one session', () => {
  function tab(backing: Storage, name: string): TokenStorage {
    return new TokenStorage({
      key: 'switch-shared',
      storage: backing,
      eventBus: new SerialTypedEventBus<never>(name),
      sharedAcrossTabs: true,
    });
  }

  it("switch under the refresh's Web Lock: another tab's refresh waits, and refreshes nothing of the old session", async () => {
    const host = new FakeCoSecHost();
    const locks = fakeLocks();
    vi.stubGlobal('navigator', { locks });
    const backing = new InMemoryStorage();
    const first = tab(backing, 'tab-1');
    const second = tab(backing, 'tab-2');
    const { configurer } = createClient(host, { tokens: first });
    first.signIn(host.login());
    second.reload();
    const refresher: TokenRefresher = {
      refresh: async token => {
        const response = await host.fetch('/auth/refresh', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(token),
        });
        return response.json();
      },
    };
    const other = new JwtTokenManager(second, refresher);
    const hold = gate();
    host.after = async (seen, response) => {
      if (seen.path.startsWith('/auth/switch/')) await hold.opened;
      return response;
    };

    const switching = configurer.tokenManager!.switchTenant({
      endpoint: '/auth/switch/acme',
    });
    await vi.waitFor(() =>
      expect(host.on('/auth/switch/acme')).toHaveLength(1),
    );
    const refreshing = other.refresh().then(
      () => undefined,
      (error: unknown) => error,
    );
    hold.open();
    await switching;

    expect(await refreshing).toBeInstanceOf(RefreshSessionChangedError);
    expect(locks.request).toHaveBeenCalledWith(
      'cosec-refresh:switch-shared',
      expect.any(Function),
    );
    expect(host.on('/auth/refresh')).toHaveLength(0);
    expect(second.reload()?.access.payload?.tenantId).toBe('acme');
    first.destroy();
    second.destroy();
  });

  it('is the one switcher of the configurer, sent on its Fetcher', () => {
    const host = new FakeCoSecHost();
    const { configurer, client } = createClient(host);
    const switcher = configurer.config.tenantSwitcher as CoSecTenantSwitcher;
    expect(switcher.options.fetcher).toBe(client);
    expect(configurer.tokenManager!.options.tenantSwitcher).toBe(switcher);
  });
});
