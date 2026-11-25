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
import { afterEach, describe, expect, it } from 'vitest';
import {
  CoSecTokenRefresher,
  CoSecTokenRevoker,
  type RequestTrust,
} from '../src';
import { createClient, destroyClients } from './support/client';
import { FakeCoSecHost } from './support/fakeCoSec';

// A request that carries the session's token pair (a tenant switch, a refresh, a logout) goes only where every other
// credentialed request may go (`isTrusted`, wiki/integration/clients.md): to an endpoint the client does not trust it is
// refused before anything is sent, whatever the endpoint names. Review A-1 of beta.9.

afterEach(() => destroyClients());

/** A host that records every URL it is asked, and the Authorization and body of each. */
function watched() {
  const host = new FakeCoSecHost();
  const seen: {
    url: string;
    authorization: string | null;
    body: string;
    redirect: RequestRedirect | undefined;
  }[] = [];
  const original = host.fetch;
  host.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input instanceof Request ? input.url : input);
    const headers = new Headers(
      input instanceof Request ? input.headers : init?.headers,
    );
    seen.push({
      url,
      authorization: headers.get('authorization'),
      body: String(init?.body ?? ''),
      redirect: init?.redirect,
    });
    return original(input as never, init as never);
  }) as typeof host.fetch;
  return { host, seen };
}

const evil = (seen: { url: string }[]) =>
  seen.filter(({ url }) => new URL(url).origin === 'https://evil.example');

const trustingEvil: RequestTrust = url =>
  new URL(url).origin === 'https://evil.example';

describe('a request carrying the token pair to an endpoint the client does not trust', () => {
  it('a tenant switch is refused and sends nothing, though a plain request there only loses its token', async () => {
    const { host, seen } = watched();
    const pair = host.login();
    const { client, configurer, tokens } = createClient(host, {
      signedIn: pair,
    });
    await client.get('https://evil.example/me').catch(() => undefined);
    expect(evil(seen)).toHaveLength(1);
    expect(evil(seen)[0].authorization).toBeNull();

    await expect(
      configurer.tokenManager!.switchTenant({
        endpoint: 'https://evil.example/auth/switch/acme',
      }),
    ).rejects.toThrow();
    expect(evil(seen)).toHaveLength(1);
    expect(seen.some(({ body }) => body.includes(pair.refreshToken))).toBe(
      false,
    );
    expect(tokens.get()?.token).toEqual(pair);
  });

  it('a switch to a relative endpoint the URL parser takes to another host is refused too', async () => {
    const { host, seen } = watched();
    const { configurer } = createClient(host, { signedIn: host.login() });
    await expect(
      configurer.tokenManager!.switchTenant({
        endpoint: '\\\\evil.example/auth/switch',
      }),
    ).rejects.toThrow();
    expect(evil(seen)).toHaveLength(0);
  });

  it('a refresh and a logout are refused and send nothing', async () => {
    const { host, seen } = watched();
    const pair = host.login();
    const { client } = createClient(host, { signedIn: pair });
    const refresher = new CoSecTokenRefresher({
      fetcher: client,
      endpoint: 'https://evil.example/auth/refresh',
    });
    const revoker = new CoSecTokenRevoker({
      fetcher: client,
      endpoint: 'https://evil.example/auth/logout',
    });
    await expect(refresher.refresh(pair)).rejects.toThrow('does not trust');
    await expect(revoker.revoke(pair)).rejects.toThrow('does not trust');
    expect(evil(seen)).toHaveLength(0);
  });

  it('each goes where the configured trust names the origin', async () => {
    const { host, seen } = watched();
    const pair = host.login();
    const { client, configurer } = createClient(host, {
      signedIn: pair,
      config: { isTrusted: trustingEvil },
    });
    await configurer
      .tokenManager!.switchTenant({
        endpoint: 'https://evil.example/auth/switch/acme',
      })
      .catch(() => undefined);
    await new CoSecTokenRevoker({
      fetcher: client,
      endpoint: 'https://evil.example/auth/logout',
    })
      .revoke(pair)
      .catch(() => undefined);
    expect(evil(seen).map(({ url }) => new URL(url).pathname)).toEqual([
      '/auth/switch/acme',
      '/auth/logout',
    ]);
  });

  it('a Fetcher of its own, the auth service, trusts its own baseURL', async () => {
    const { host, seen } = watched();
    const pair = host.login();
    createClient(host, { signedIn: pair });
    const { Fetcher } = await import('@ahoo-wang/fetcher');
    const auth = new Fetcher({
      baseURL: 'https://evil.example',
      fetch: host.fetch,
    });
    await new CoSecTokenRefresher({ fetcher: auth, endpoint: '/auth/refresh' })
      .refresh(pair)
      .catch(() => undefined);
    expect(evil(seen).map(({ url }) => url)).toEqual([
      'https://evil.example/auth/refresh',
    ]);
  });

  // Review A, V-1: the trust is asked about the request that is sent, a POST with its attributes, not a bare URL.
  it('a trust that tells reads from writes is asked about the POST that is sent', async () => {
    const { host, seen } = watched();
    const pair = host.login();
    const readsOnlyOnCdn: RequestTrust = (url, exchange) =>
      new URL(url).origin === 'https://api.test' ||
      (new URL(url).origin === 'https://evil.example' &&
        exchange.request.method !== 'POST');
    const { client, configurer } = createClient(host, {
      signedIn: pair,
      config: { isTrusted: readsOnlyOnCdn },
    });
    await client
      .post('https://evil.example/upload', { body: {} })
      .catch(() => undefined);
    expect(evil(seen)[0].authorization).toBeNull();
    await expect(
      configurer.tokenManager!.switchTenant({
        endpoint: 'https://evil.example/auth/switch/acme',
      }),
    ).rejects.toThrow();
    await expect(
      new CoSecTokenRefresher({
        fetcher: client,
        endpoint: 'https://evil.example/auth/refresh',
      }).refresh(pair),
    ).rejects.toThrow('does not trust');
    await expect(
      new CoSecTokenRevoker({
        fetcher: client,
        endpoint: 'https://evil.example/auth/logout',
      }).revoke(pair),
    ).rejects.toThrow('does not trust');
    expect(evil(seen).map(({ url }) => new URL(url).pathname)).toEqual([
      '/upload',
    ]);
    expect(seen.some(({ body }) => body.includes(pair.refreshToken))).toBe(
      false,
    );
  });

  // Review A, V-2: a 307 or 308 re-sends the body, the pair in it, wherever it points; CoSec's routes never redirect.
  it('a refresh, a logout and a switch follow no redirect', async () => {
    const { host, seen } = watched();
    const pair = host.login();
    const { client, configurer } = createClient(host, { signedIn: pair });
    await configurer
      .tokenManager!.switchTenant({ endpoint: '/auth/switch/acme' })
      .catch(() => undefined);
    await new CoSecTokenRefresher({
      fetcher: client,
      endpoint: '/auth/refresh',
    })
      .refresh(pair)
      .catch(() => undefined);
    await new CoSecTokenRevoker({ fetcher: client, endpoint: '/auth/logout' })
      .revoke(pair)
      .catch(() => undefined);
    const session = seen.filter(({ url }) =>
      ['/auth/switch/acme', '/auth/refresh', '/auth/logout'].includes(
        new URL(url).pathname,
      ),
    );
    expect(session.map(({ redirect }) => redirect)).toEqual([
      'error',
      'error',
      'error',
    ]);
  });

  it('a trusted route that answers a redirect fails the refresh and keeps the pair where it was', async () => {
    const host = new FakeCoSecHost();
    const pair = host.login();
    const sentTo: string[] = [];
    // fetch as the standard has it: a 307 re-sends the body unless the request says redirect: 'error'.
    const redirecting = (async (
      input: RequestInfo | URL,
      init?: RequestInit,
    ) => {
      const url = String(input instanceof Request ? input.url : input);
      sentTo.push(url);
      if (new URL(url).pathname !== '/auth/refresh')
        return host.fetch(input as never, init as never);
      if (init?.redirect === 'error')
        throw new TypeError('Failed to fetch: redirected');
      sentTo.push('https://evil.example/collect');
      return new Response(null, { status: 204 });
    }) as typeof fetch;
    const { Fetcher } = await import('@ahoo-wang/fetcher');
    const auth = new Fetcher({
      baseURL: 'https://api.test',
      fetch: redirecting,
    });
    await expect(
      new CoSecTokenRefresher({
        fetcher: auth,
        endpoint: '/auth/refresh',
      }).refresh(pair),
    ).rejects.toThrow();
    expect(sentTo).toEqual(['https://api.test/auth/refresh']);
  });
});
