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

import { Fetcher } from '@ahoo-wang/fetcher';
import { SerialTypedEventBus } from '@ahoo-wang/fetcher-eventbus';
import { InMemoryStorage } from '@ahoo-wang/fetcher-storage';
import {
  CoSecConfigurer,
  DeviceIdStorage,
  sameOriginTrust,
  TokenStorage,
  type CompositeToken,
  type RequestTrust,
} from '../src';

const jwt = (sub: string, exp: number) =>
  `e30.${btoa(JSON.stringify({ sub, exp, tenantId: 'tenant-1' }))}.signature`;
const token: CompositeToken = {
  accessToken: jwt('user', Date.now() / 1000 + 3600),
  refreshToken: jwt('user', Date.now() / 1000 + 7200),
};

let sent: Record<string, Headers>;

function configured(
  isTrusted?: RequestTrust,
  baseURL = 'https://api.example.com/v1',
  refresh?: () => Promise<CompositeToken>,
): Fetcher {
  const fetcher = new Fetcher({ baseURL });
  const tokenStorage = new TokenStorage({
    storage: new InMemoryStorage(),
    eventBus: new SerialTypedEventBus('trust'),
  });
  tokenStorage.signIn(token);
  new CoSecConfigurer({
    tokenStorage,
    deviceIdStorage: new DeviceIdStorage({
      storage: new InMemoryStorage(),
      eventBus: new SerialTypedEventBus('trust-device'),
    }),
    tokenRefresher: { refresh: refresh ?? (async () => token) },
    spaceIdProvider: { resolveSpaceId: () => 'space-1' },
    isTrusted,
  }).applyTo(fetcher);
  return fetcher;
}

beforeEach(() => {
  sent = {};
  vi.stubGlobal('fetch', async (url: string, init: RequestInit) => {
    sent[url] = new Headers(init.headers as HeadersInit);
    return new Response('{}');
  });
});

describe('request trust', () => {
  // CoSec 8.0's client keeps the credentials of the user to the origins the application is built against: its
  // baseURL and its page. Without `isTrusted` that is `sameOriginTrust`, and a request to another origin is sent bare.
  describe('by default', () => {
    const CREDENTIAL_HEADERS = [
      'authorization',
      'cosec-device-id',
      'cosec-request-id',
      'cosec-space-id',
    ];

    function expectCredentials(headers: Headers) {
      for (const name of CREDENTIAL_HEADERS) {
        expect(headers.has(name), name).toBe(true);
      }
      expect(headers.get('authorization')).toMatch(/^Bearer /);
    }

    function expectNone(headers: Headers) {
      for (const name of CREDENTIAL_HEADERS) {
        expect(headers.has(name), name).toBe(false);
      }
    }

    it('keeps all four headers from an absolute URL on a third origin', async () => {
      const fetcher = configured();
      await fetcher.get('https://other.example.net/page');
      expectNone(sent['https://other.example.net/page']);
      await fetcher.get('/users');
      expectCredentials(sent['https://api.example.com/v1/users']);
    });

    it('fills no tenant or owner path variable of a third origin from the token', async () => {
      const fetcher = configured();
      // The variable stays unfilled: nothing of the token is in the URL sent.
      await fetcher
        .get('https://other.example.net/t/{tenantId}/u/{ownerId}')
        .catch(() => undefined);
      expect(Object.keys(sent).join()).not.toContain('tenant-1');
      expect(Object.keys(sent).join()).not.toContain('user');
      // On the baseURL's own origin the same templates are filled.
      await fetcher.get('https://api.example.com/t/{tenantId}/u/{ownerId}');
      expect(Object.keys(sent)).toContain(
        'https://api.example.com/t/tenant-1/u/user',
      );
    });

    it('starts no refresh and keeps the session on a 401 of a third origin', async () => {
      const refresh = vi.fn(async () => token);
      const fetcher = configured(undefined, undefined, refresh);
      const requests: string[] = [];
      vi.stubGlobal('fetch', async (url: string, init: RequestInit) => {
        requests.push(url);
        sent[url] = new Headers(init.headers as HeadersInit);
        return new Response('{}', {
          status: url.startsWith('https://other.') ? 401 : 200,
        });
      });
      // Fetcher refuses a 401 as an error, as for any status it does not accept.
      await expect(
        fetcher.get('https://other.example.net/page'),
      ).rejects.toMatchObject({ exchange: { response: { status: 401 } } });
      expect(requests).toEqual(['https://other.example.net/page']);
      expect(refresh).not.toHaveBeenCalled();
      // Not signed out: the same user's request to the baseURL still carries the token.
      sent = {};
      await fetcher.get('/users');
      expectCredentials(sent['https://api.example.com/v1/users']);
    });

    it('sends credentials to a baseURL on another origin than the page, relative and absolute', async () => {
      vi.stubGlobal('location', new URL('https://app.example.com/page'));
      const fetcher = configured();
      await fetcher.get('/users');
      await fetcher.get('https://api.example.com/v2/users');
      expectCredentials(sent['https://api.example.com/v1/users']);
      expectCredentials(sent['https://api.example.com/v2/users']);
      // The page's own origin is trusted too; another is not.
      await fetcher.get('https://app.example.com/assets');
      expectCredentials(sent['https://app.example.com/assets']);
      await fetcher.get('https://cdn.example.net/file');
      expectNone(sent['https://cdn.example.net/file']);
    });

    it('sends credentials to the origin of the page when the baseURL is relative', async () => {
      vi.stubGlobal('location', new URL('https://app.example.com/page'));
      const fetcher = configured(undefined, '/api');
      await fetcher.get('/users');
      expectCredentials(sent['/api/users']);
      await fetcher.get('https://app.example.com/other');
      expectCredentials(sent['https://app.example.com/other']);
      await fetcher.get('https://api.example.com/users');
      expectNone(sent['https://api.example.com/users']);
    });

    // Node and workers have no `location`: only an absolute baseURL names an origin, and an absolute URL with none
    // is sent bare. A relative URL on a relative baseURL has no host to be sent to: it is the application's own.
    it('without a page location, trusts the origin of an absolute baseURL and nothing else', async () => {
      const fetcher = configured();
      await fetcher.get('https://api.example.com/v3/users');
      expectCredentials(sent['https://api.example.com/v3/users']);
      await fetcher.get('https://other.example.net/page');
      expectNone(sent['https://other.example.net/page']);
    });

    it('without a page location and without an absolute baseURL, sends an absolute URL bare', async () => {
      const fetcher = configured(undefined, '');
      await fetcher.get('https://api.example.com/users');
      expectNone(sent['https://api.example.com/users']);
      await fetcher.get('/users');
      expectCredentials(sent['/users']);
    });

    it('lets an explicit predicate decide alone', async () => {
      const fetcher = configured(url => url.startsWith('https://cdn.'));
      await fetcher.get('https://cdn.example.com/file');
      expectCredentials(sent['https://cdn.example.com/file']);
      // The baseURL's origin is not trusted by default's rule once a predicate is given.
      await fetcher.get('https://api.example.com/v2/users');
      expectNone(sent['https://api.example.com/v2/users']);
      // A relative URL on the baseURL is always trusted.
      await fetcher.get('/users');
      expectCredentials(sent['https://api.example.com/v1/users']);
    });

    it('trusts everything only when the application says so', async () => {
      const fetcher = configured(() => true);
      await fetcher.get('https://other.example.net/page');
      expectCredentials(sent['https://other.example.net/page']);
    });
  });

  describe('with sameOriginTrust', () => {
    it('sends credentials for relative URLs and the baseURL origin', async () => {
      const fetcher = configured(sameOriginTrust);
      await fetcher.get('/users');
      await fetcher.get('https://api.example.com/v2/users');
      for (const url of [
        'https://api.example.com/v1/users',
        'https://api.example.com/v2/users',
      ]) {
        expect(sent[url].get('authorization')).toMatch(/^Bearer /);
        expect(sent[url].has('cosec-app-id')).toBe(false);
      }
    });

    it('keeps the token and CoSec headers from another origin', async () => {
      const fetcher = configured(sameOriginTrust);
      await fetcher.get('https://evil.example.net/next-page');
      const headers = sent['https://evil.example.net/next-page'];
      expect(headers.has('authorization')).toBe(false);
      expect(headers.has('cosec-device-id')).toBe(false);
      expect(headers.has('cosec-app-id')).toBe(false);
    });
  });

  it('asks a custom predicate only about absolute URLs', async () => {
    const isTrusted = vi.fn((url: string) => url.startsWith('https://cdn.'));
    const fetcher = configured(isTrusted);
    await fetcher.get('/users');
    await fetcher.get('https://cdn.example.com/file');
    await fetcher.get('https://elsewhere.example.com/file');
    // Once per request: the decision is kept on the exchange.
    expect(isTrusted.mock.calls.map(([url]) => url)).toEqual([
      'https://cdn.example.com/file',
      'https://elsewhere.example.com/file',
    ]);
    expect(sent['https://cdn.example.com/file'].get('authorization')).toMatch(
      /^Bearer /,
    );
    expect(
      sent['https://elsewhere.example.com/file'].has('authorization'),
    ).toBe(false);
  });

  it('keeps the credentials of a trusted relative URL on the 401 retry', async () => {
    // The retry sees the URL already resolved against the baseURL; a
    // predicate that lists only extra origins must not drop the token there.
    const isTrusted = vi.fn((url: string) =>
      url.startsWith('https://files.partner.com/'),
    );
    const fetcher = configured(isTrusted);
    const authorizations: (string | null)[] = [];
    vi.stubGlobal('fetch', async (_url: string, init: RequestInit) => {
      const headers = new Headers(init.headers as HeadersInit);
      authorizations.push(headers.get('authorization'));
      return new Response('{}', {
        status: authorizations.length === 1 ? 401 : 200,
      });
    });
    const response = await fetcher.get('/users');
    expect(response.status).toBe(200);
    expect(authorizations).toHaveLength(2);
    expect(authorizations[1]).toMatch(/^Bearer /);
    expect(isTrusted).not.toHaveBeenCalled();
  });

  // #2009: the 401 retry re-runs the request chain on the same exchange; a
  // relative baseURL must not be prefixed to the resolved URL again.
  it.each(['/api', 'api', '/api/'])(
    'retries a request on the relative baseURL %j at the same URL',
    async baseURL => {
      const fetcher = configured(sameOriginTrust, baseURL);
      const requests: { url: string; authorization: string | null }[] = [];
      vi.stubGlobal('fetch', async (url: string, init: RequestInit) => {
        const headers = new Headers(init.headers as HeadersInit);
        requests.push({ url, authorization: headers.get('authorization') });
        return new Response('{}', {
          status: requests.length === 1 ? 401 : 200,
        });
      });
      const response = await fetcher.get('/resource', {
        urlParams: { query: { limit: 1 } },
      });
      expect(response.status).toBe(200);
      const expected = `${baseURL.replace(/\/+$/, '')}/resource?limit=1`;
      expect(requests.map(({ url }) => url)).toEqual([expected, expected]);
      expect(requests[1].authorization).toMatch(/^Bearer /);
    },
  );

  // #1995: a relative URL that the URL parser resolves to another host must
  // not be trusted just because it does not look absolute.
  describe('with a relative URL that leaves the baseURL host', () => {
    const escaping = [
      '\\evil.example.net/collect',
      '/\\evil.example.net/collect',
      '\\\\evil.example.net/collect',
      '\t//evil.example.net/collect',
      '/\n/evil.example.net/collect',
    ];

    function expectNoCredentials(headers: Headers) {
      expect(headers.has('authorization')).toBe(false);
      expect(headers.has('cosec-device-id')).toBe(false);
      expect(headers.has('cosec-app-id')).toBe(false);
    }

    it.each(['', '/api'])(
      'keeps credentials from it on a page, baseURL %j',
      async baseURL => {
        vi.stubGlobal('location', new URL('https://app.example.com/page'));
        const fetcher = configured(sameOriginTrust, baseURL);
        await fetcher.get('/users');
        const own = Object.keys(sent)[0];
        expect(sent[own].get('authorization')).toMatch(/^Bearer /);
        for (const url of escaping) {
          sent = {};
          await fetcher.get(url);
          const [requested] = Object.keys(sent);
          // Native fetch resolves what Fetcher sends to the other host.
          expect(new URL(requested, location.href).host).toBe(
            baseURL ? 'app.example.com' : 'evil.example.net',
          );
          if (baseURL) {
            expect(sent[requested].get('authorization')).toMatch(/^Bearer /);
          } else {
            expectNoCredentials(sent[requested]);
          }
        }
      },
    );

    it('keeps credentials from it without a page location', async () => {
      const fetcher = configured(sameOriginTrust, '');
      for (const url of escaping) {
        sent = {};
        await fetcher.get(url);
        expectNoCredentials(Object.values(sent)[0]);
      }
    });

    it('asks a custom predicate about the URL it resolves to', async () => {
      vi.stubGlobal('location', new URL('https://app.example.com/page'));
      const isTrusted = vi.fn(() => false);
      const fetcher = configured(isTrusted, '');
      await fetcher.get('/users');
      await fetcher.get('\\evil.example.net/collect?a=1');
      expect(isTrusted.mock.calls.map(([url]) => url)).toEqual([
        'https://evil.example.net/collect?a=1',
      ]);
      expectNoCredentials(sent['/\\evil.example.net/collect?a=1']);
      expect(sent['/users'].get('authorization')).toMatch(/^Bearer /);
    });

    it('still trusts a URL that stays on an absolute baseURL', async () => {
      const fetcher = configured(sameOriginTrust);
      await fetcher.get('\\evil.example.net/collect');
      const headers =
        sent['https://api.example.com/v1/\\evil.example.net/collect'];
      expect(headers.get('authorization')).toMatch(/^Bearer /);
    });

    it('does not trust a relative URL whose target does not parse', async () => {
      const isTrusted = vi.fn(() => true);
      const fetcher = configured(isTrusted, 'https://[invalid');
      await fetcher.get('/users');
      expect(isTrusted).not.toHaveBeenCalled();
      expectNoCredentials(sent['https://[invalid/users']);
    });
  });
});
