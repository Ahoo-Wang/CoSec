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
  CoSecTokenRefresher,
  DeviceIdStorage,
  TokenStorage,
  type CompositeToken,
  type RequestTrust,
} from '../src';

// What the default trust (`sameOriginTrust`) does to the refresh request when the application's refresh endpoint is
// another origin than the `baseURL`: an auth service of its own. Everything here asserts on the request actually sent.

const base64Url = (value: object) =>
  btoa(JSON.stringify(value))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
const jwt = (exp: number, jti: string) =>
  `e30.${base64Url({ sub: 'user', jti, exp })}.signature`;
const now = Math.floor(Date.now() / 1000);
const expired: CompositeToken = {
  accessToken: jwt(now - 60, 'expired'),
  refreshToken: jwt(now + 7200, 'refresh'),
};
const renewed: CompositeToken = {
  accessToken: jwt(now + 3600, 'renewed'),
  refreshToken: jwt(now + 7200, 'refresh-2'),
};

interface Sent {
  url: string;
  headers: Headers;
  body?: string;
}
let sent: Sent[];

beforeEach(() => {
  sent = [];
  vi.stubGlobal('fetch', async (url: string, init: RequestInit) => {
    sent.push({
      url,
      headers: new Headers(init.headers as HeadersInit),
      body: typeof init.body === 'string' ? init.body : undefined,
    });
    return new Response(
      url.endsWith('/refresh') ? JSON.stringify(renewed) : '{}',
      { headers: { 'Content-Type': 'application/json' } },
    );
  });
});

const CREDENTIAL_HEADERS = [
  'authorization',
  'cosec-device-id',
  'cosec-request-id',
  'cosec-space-id',
];

/** A client on `https://api.example.com/v1` whose access token has expired; the refresher is built by `refresher`. */
function client(
  refresher: (configured: Fetcher) => CoSecTokenRefresher,
  isTrusted?: RequestTrust,
) {
  const api = new Fetcher({ baseURL: 'https://api.example.com/v1' });
  const tokenStorage = new TokenStorage({
    storage: new InMemoryStorage(),
    eventBus: new SerialTypedEventBus('refresh-trust'),
  });
  tokenStorage.signIn(expired);
  new CoSecConfigurer({
    tokenStorage,
    deviceIdStorage: new DeviceIdStorage({
      storage: new InMemoryStorage(),
      eventBus: new SerialTypedEventBus('refresh-trust-device'),
    }),
    tokenRefresher: refresher(api),
    spaceIdProvider: { resolveSpaceId: () => 'space-1' },
    isTrusted,
  }).applyTo(api);
  return { api, tokenStorage };
}

const refreshes = () => sent.filter(({ url }) => url.endsWith('/refresh'));

describe('the refresh endpoint on another origin than the baseURL', () => {
  it('is unaffected on a Fetcher of its own: no CoSec header, the pair in the body, the session renewed', async () => {
    const { api, tokenStorage } = client(
      () =>
        new CoSecTokenRefresher({
          fetcher: new Fetcher({ baseURL: 'https://auth.example.net' }),
          endpoint: '/refresh',
        }),
    );
    await api.get('/users');

    const [refresh] = refreshes();
    expect(refresh.url).toBe('https://auth.example.net/refresh');
    for (const name of CREDENTIAL_HEADERS) {
      expect(refresh.headers.has(name), name).toBe(false);
    }
    expect(JSON.parse(refresh.body ?? '{}')).toEqual(expired);
    expect(tokenStorage.get()?.token).toEqual(renewed);
    // The retried business request carries the new token.
    expect(sent.at(-1)?.url).toBe('https://api.example.com/v1/users');
    expect(sent.at(-1)?.headers.get('authorization')).toBe(
      `Bearer ${renewed.accessToken}`,
    );
  });

  it('is refused on the configured Fetcher: nothing sent, the pair never leaves, the session kept', async () => {
    const { api, tokenStorage } = client(
      configured =>
        new CoSecTokenRefresher({
          fetcher: configured,
          endpoint: 'https://auth.example.net/refresh',
        }),
    );
    // The pair goes only where a credentialed request may (review A-1 of beta.9): the refresh is refused before it is
    // sent, the manager keeps the session (a refresh it could not make), and the business request fails with it.
    await expect(api.get('/users')).rejects.toThrow();

    expect(refreshes()).toHaveLength(0);
    expect(sent.some(({ body }) => body?.includes(expired.refreshToken))).toBe(
      false,
    );
    expect(tokenStorage.get()?.token).toEqual(expired);
  });

  it('keeps them on the configured Fetcher when the endpoint is on the baseURL', async () => {
    const { api } = client(
      configured =>
        new CoSecTokenRefresher({ fetcher: configured, endpoint: '/refresh' }),
    );
    await api.get('/users');

    const [refresh] = refreshes();
    expect(refresh.url).toBe('https://api.example.com/v1/refresh');
    expect(refresh.headers.get('authorization')).toBe(
      `Bearer ${expired.accessToken}`,
    );
    expect(refresh.headers.has('cosec-device-id')).toBe(true);
    expect(refresh.headers.has('cosec-request-id')).toBe(true);
    // The expired token is sent and the server reads it as anonymous; and no space on a refresh.
    expect(refresh.headers.has('cosec-space-id')).toBe(false);
  });

  it('gets them back from a predicate that names the origin', async () => {
    const { api } = client(
      configured =>
        new CoSecTokenRefresher({
          fetcher: configured,
          endpoint: 'https://auth.example.net/refresh',
        }),
      url => new URL(url).origin === 'https://auth.example.net',
    );
    await api.get('/users');

    const [refresh] = refreshes();
    expect(refresh.headers.has('cosec-device-id')).toBe(true);
    expect(refresh.headers.has('cosec-request-id')).toBe(true);
  });
});
