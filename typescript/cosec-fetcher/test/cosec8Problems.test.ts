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

import { getHeader, HttpStatusValidationError } from '@ahoo-wang/fetcher';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  CoSecHeaders,
  getCoSecProblem,
  retryAfterSeconds,
  type CoSecProblem,
} from '../src';
import { createClient, destroyClients } from './support/client';
import { FakeCoSecHost, problemResponse } from './support/fakeCoSec';

afterEach(() => {
  destroyClients();
  vi.useRealTimers();
});

describe('a 401 says why (M6)', () => {
  it.each(['token-revoked', 'token-invalid'] as const)(
    'ends the session on %s without a refresh, and tells the application why',
    async slug => {
      const host = new FakeCoSecHost();
      const pair = host.login();
      const onUnauthorized = vi.fn();
      const { client, tokens } = createClient(host, {
        signedIn: pair,
        config: { onUnauthorized },
      });
      if (slug === 'token-revoked') host.logout(pair);
      else tokens.signIn(host.invalidAccessToken(pair));
      const error = await client.get('/me').catch(error => error);
      expect(error).toBeInstanceOf(HttpStatusValidationError);
      expect(host.on('/auth/refresh')).toHaveLength(0);
      expect(host.on('/me')).toHaveLength(1);
      expect(tokens.get()).toBeNull();
      expect(onUnauthorized).toHaveBeenCalledTimes(1);
      const problem = onUnauthorized.mock.calls[0][1] as CoSecProblem;
      expect(problem.slug).toBe(slug);
      expect(problem.code).toBe(slug.toUpperCase().replaceAll('-', '_'));
    },
  );

  it('notifies once for concurrent requests of one revoked session', async () => {
    const host = new FakeCoSecHost();
    const pair = host.login();
    const onUnauthorized = vi.fn();
    const { client, tokens } = createClient(host, {
      signedIn: pair,
      config: { onUnauthorized },
    });
    host.logout(pair);
    await Promise.allSettled([
      client.get('/me'),
      client.get('/me'),
      client.get('/me'),
    ]);
    expect(host.on('/auth/refresh')).toHaveLength(0);
    expect(tokens.get()).toBeNull();
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });

  it('does not remove a pair another tab signed in with meanwhile', async () => {
    const host = new FakeCoSecHost();
    const pair = host.login();
    const next = host.login('u2');
    const onUnauthorized = vi.fn();
    const { client, tokens } = createClient(host, {
      signedIn: pair,
      config: { onUnauthorized },
    });
    host.logout(pair);
    host.after = async (_seen, response) => {
      tokens.signIn(next); // another tab signs in while the request is out
      return response;
    };
    await client.get('/me').catch(() => undefined);
    expect(tokens.get()?.token.accessToken).toBe(next.accessToken);
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it('does not refresh on unauthorized, keeps the pair and asks for a sign-in', async () => {
    const host = new FakeCoSecHost();
    const pair = host.login();
    const onUnauthorized = vi.fn();
    const { client, tokens } = createClient(host, {
      signedIn: pair,
      config: { onUnauthorized },
    });
    // no credential was seen by the server
    host.after = async () => problemResponse('unauthorized');
    await client.get('/me').catch(() => undefined);
    expect(host.on('/auth/refresh')).toHaveLength(0);
    expect(tokens.get()?.token).toEqual(pair);
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
    expect(onUnauthorized.mock.calls[0][1].slug).toBe('unauthorized');
  });

  it('still refreshes once and retries on token-expired', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    const host = new FakeCoSecHost();
    const pair = host.login();
    const { client, tokens } = createClient(host, { signedIn: pair });
    // the client believes the token valid; the server says it expired
    let first = true;
    host.after = async (seen, response) => {
      if (first && seen.path === '/me') {
        first = false;
        return problemResponse('token-expired');
      }
      return response;
    };
    const response = await client.get('/me');
    expect(response.status).toBe(200);
    expect(host.on('/auth/refresh')).toHaveLength(1);
    expect(tokens.get()?.token.accessToken).not.toBe(pair.accessToken);
  });

  it.each([
    ['a bodyless 401', () => new Response('', { status: 401 })],
    [
      'a type it does not know',
      () =>
        problemResponse('token-expired').clone() &&
        new Response(
          JSON.stringify({ type: 'https://cosec.ahoo.me/problems/new-thing' }),
          {
            status: 401,
            headers: { 'Content-Type': 'application/problem+json' },
          },
        ),
    ],
  ])('still refreshes once on %s', async (_name, make) => {
    const host = new FakeCoSecHost();
    const pair = host.login();
    const { client } = createClient(host, { signedIn: pair });
    let first = true;
    host.after = async (seen, response) => {
      if (first && seen.path === '/me') {
        first = false;
        return make();
      }
      return response;
    };
    const response = await client.get('/me');
    expect(response.status).toBe(200);
    expect(host.on('/auth/refresh')).toHaveLength(1);
  });
});

describe('a 403 says which (M4, M5)', () => {
  const rejected = () => {
    const host = new FakeCoSecHost();
    const pair = host.login('u1', { roles: ['admin@team-a'] });
    return { host, pair };
  };

  it('reports a space the principal has no role in as scope-rejected, with the space that was sent', async () => {
    const { host, pair } = rejected();
    const onForbidden = vi.fn(async () => undefined);
    const onScopeRejected = vi.fn(async () => undefined);
    const { client, spaces } = createClient(host, {
      signedIn: pair,
      config: { onForbidden, onScopeRejected },
    });
    spaces.set('team-gone');
    await client.get('/me').catch(() => undefined);
    expect(onForbidden).not.toHaveBeenCalled();
    expect(onScopeRejected).toHaveBeenCalledTimes(1);
    const [exchange, problem] = onScopeRejected.mock.calls[0] as unknown as [
      { request: { headers: Record<string, string> } },
      CoSecProblem,
    ];
    expect(problem.slug).toBe('scope-rejected');
    expect(getHeader(exchange.request.headers, CoSecHeaders.SPACE_ID)).toBe(
      'team-gone',
    );
    // the application decides: nothing was cleared or retried
    expect(spaces.get()).toBe('team-gone');
    expect(host.on('/me')).toHaveLength(1);
  });

  it('gives a scope-rejected to onForbidden when there is no onScopeRejected', async () => {
    const { host, pair } = rejected();
    const onForbidden = vi.fn(async () => undefined);
    const { client, spaces } = createClient(host, {
      signedIn: pair,
      config: { onForbidden },
    });
    spaces.set('team-gone');
    await client.get('/me').catch(() => undefined);
    expect(onForbidden).toHaveBeenCalledTimes(1);
    expect((onForbidden.mock.calls[0] as unknown[])[1]).toMatchObject({
      slug: 'scope-rejected',
    });
  });

  it('works for a space the principal has a role in', async () => {
    const { host, pair } = rejected();
    const { client, spaces } = createClient(host, { signedIn: pair });
    spaces.set('team-a');
    expect((await client.get('/me')).status).toBe(200);
  });

  it('reports a forbidden as forbidden, with its problem', async () => {
    const { host, pair } = rejected();
    const onForbidden = vi.fn(async () => undefined);
    const onScopeRejected = vi.fn(async () => undefined);
    const { client } = createClient(host, {
      signedIn: pair,
      spaced: () => false,
      config: { onForbidden, onScopeRejected },
    });
    await client.get('/denied').catch(() => undefined);
    expect(onScopeRejected).not.toHaveBeenCalled();
    expect((onForbidden.mock.calls[0] as unknown[])[1]).toMatchObject({
      slug: 'forbidden',
    });
  });

  it('reports a token of another app as scope-rejected, whatever appId says (M5)', async () => {
    const host = new FakeCoSecHost({ hostApp: 'order' });
    const pair = host.login('u1', { aud: 'billing' });
    const onScopeRejected = vi.fn(async () => undefined);
    const { client } = createClient(host, {
      signedIn: pair,
      spaced: () => false,
      config: {
        onScopeRejected,
        appId: 'order', // the deprecated option decides nothing
      },
    });
    await client.get('/me').catch(() => undefined);
    expect(host.on('/me')[0].headers.has('cosec-app-id')).toBe(false);
    expect(onScopeRejected).toHaveBeenCalledTimes(1);
  });

  it('serves a token of the host app', async () => {
    const host = new FakeCoSecHost({ hostApp: 'order' });
    const pair = host.login('u1', { aud: 'order' });
    const { client } = createClient(host, {
      signedIn: pair,
      spaced: () => false,
    });
    expect((await client.get('/me')).status).toBe(200);
  });
});

describe('the space of a request that is anonymous (M4)', () => {
  it('is not sent without a token, so the answer is not "sign in"', async () => {
    const host = new FakeCoSecHost();
    const { client, spaces } = createClient(host);
    spaces.set('team-a');
    const response = await client.get('/public');
    expect(response.status).toBe(200);
    expect(host.on('/public')[0].headers.has('cosec-space-id')).toBe(false);
  });

  it('is not sent on the refresh call, so the refresh is not answered 401', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    const host = new FakeCoSecHost();
    const pair = host.login('u1', { roles: ['admin@team-a'] });
    const { client, tokens, spaces } = createClient(host, { signedIn: pair });
    spaces.set('team-a');
    vi.setSystemTime(Date.now() + 11 * 60_000); // the access token expired
    expect((await client.get('/me')).status).toBe(200);
    expect(host.on('/auth/refresh')).toHaveLength(1);
    expect(host.on('/auth/refresh')[0].headers.has('cosec-space-id')).toBe(
      false,
    );
    expect(host.on('/me').at(-1)!.headers.get('cosec-space-id')).toBe('team-a');
    expect(tokens.get()?.token.accessToken).not.toBe(pair.accessToken);
  });

  it('is sent with a token the caller supplied itself', async () => {
    const host = new FakeCoSecHost();
    const pair = host.login('u1', { roles: ['admin@team-a'] });
    const { client, spaces } = createClient(host);
    spaces.set('team-a');
    const response = await client.get('/me', {
      headers: { Authorization: `Bearer ${pair.accessToken}` },
    });
    expect(response.status).toBe(200);
    expect(host.on('/me')[0].headers.get('cosec-space-id')).toBe('team-a');
  });
});

describe('a refusal that no session depends on', () => {
  it.each([
    ['/limited', 'too-many-requests', 429],
    ['/down', 'unavailable', 503],
    ['/broken', 'internal-error', 500],
  ] as const)(
    'keeps the session and every callback quiet on %s',
    async (path, slug, status) => {
      const host = new FakeCoSecHost();
      const pair = host.login();
      const onUnauthorized = vi.fn();
      const onForbidden = vi.fn(async () => undefined);
      const onScopeRejected = vi.fn(async () => undefined);
      const { client, tokens } = createClient(host, {
        signedIn: pair,
        spaced: () => false,
        config: { onUnauthorized, onForbidden, onScopeRejected },
      });
      const error = (await client
        .get(path)
        .catch(error => error)) as HttpStatusValidationError;
      expect(error).toBeInstanceOf(HttpStatusValidationError);
      expect(error.exchange.response?.status).toBe(status);
      expect(getCoSecProblem(error)?.slug).toBe(slug);
      expect(host.on('/auth/refresh')).toHaveLength(0);
      expect(tokens.get()?.token).toEqual(pair);
      expect(onUnauthorized).not.toHaveBeenCalled();
      expect(onForbidden).not.toHaveBeenCalled();
      expect(onScopeRejected).not.toHaveBeenCalled();
    },
  );

  it('exposes how long a 429 asks to wait', async () => {
    const host = new FakeCoSecHost();
    host.retryAfter = 12;
    const { client } = createClient(host, {
      signedIn: host.login(),
      spaced: () => false,
    });
    const error = (await client
      .get('/limited')
      .catch(error => error)) as HttpStatusValidationError;
    expect(retryAfterSeconds(error.exchange.response)).toBe(12);
  });

  it('keeps the session on a 400 invalid-request', async () => {
    const host = new FakeCoSecHost();
    const pair = host.login();
    const onUnauthorized = vi.fn();
    const { client, tokens } = createClient(host, {
      signedIn: pair,
      config: { onUnauthorized },
    });
    host.after = async () => problemResponse('invalid-request');
    const error = await client.get('/me').catch(error => error);
    expect(getCoSecProblem(error)?.slug).toBe('invalid-request');
    expect(tokens.get()?.token).toEqual(pair);
    expect(onUnauthorized).not.toHaveBeenCalled();
  });
});
