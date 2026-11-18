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

/**
 * The client against `contract/` (`PIP-25`): the files CoSec's server is tested
 * against, as the answers and the facts a client relies on. Nothing is stated
 * twice: a change on the server that changes a file fails here until the client
 * follows, and a change of the client that the files do not allow fails here
 * too. `test/support/fakeCoSec.ts` answers from the same files.
 *
 * What it does not cover: that a real host writes these files (the server's
 * `ClientContractTest`s, and `pnpm test:host`), and a client of another stack.
 */
import { readFileSync } from 'node:fs';
import { ExchangeError } from '@ahoo-wang/fetcher';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ts from 'typescript';
import {
  CoSecHeaders,
  CoSecProblemType,
  CoSecTokenRefresher,
  CoSecTokenRevoker,
  JwtToken,
  LogoutUnconfirmedError,
  RefreshTokenError,
  RefreshUnavailableError,
  idGenerator,
  readCoSecProblem,
  retryAfterSeconds,
  type CompositeToken,
  type CoSecJwtPayload,
} from '../src';
import { isCompositeToken } from '../src/compositeToken';
import { createClient, destroyClients } from './support/client';
import {
  PROBLEM_SLUGS,
  contract,
  jsonTypeOf,
  type ClaimRule,
  type RouteCase,
} from './support/contract';
import {
  FakeCoSecHost,
  claims,
  jwt,
  problemResponse,
} from './support/fakeCoSec';

const MINUTE = 60_000;

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
});
afterEach(() => {
  destroyClients();
  vi.useRealTimers();
});

/** The response a route's case says the server answers, `pair` being a pair of `host`. */
function caseResponse(route: RouteCase, host: FakeCoSecHost): Response {
  const headers: Record<string, string> = {};
  if (route.contentType) headers['Content-Type'] = route.contentType;
  if (route.problem) {
    const response = problemResponse(route.problem);
    expect(response.status, route.case).toBe(route.status);
    expect(response.headers.get('content-type'), route.case).toBe(
      route.contentType,
    );
    return response;
  }
  const body = route.body === 'pair' ? JSON.stringify(host.login('u2')) : null;
  return new Response(body, { status: route.status, headers });
}

describe('the problem types', () => {
  it('are those the client knows, one file each', () => {
    // A type added on the server writes a file; the client names it in CoSecProblemType in the same change.
    expect([...PROBLEM_SLUGS].sort()).toEqual(
      Object.values(CoSecProblemType).sort(),
    );
  });

  it.each(PROBLEM_SLUGS)(
    '%s: its status and body read as the file says',
    async slug => {
      const file = contract.problem(slug);
      const wire = (extra: Record<string, unknown> = {}) =>
        new Response(JSON.stringify({ ...file.body, ...extra }), {
          status: file.status,
          headers: file.headers,
        });

      const problem = await readCoSecProblem(wire());
      expect(problem).toEqual({
        slug,
        type: file.body.type,
        title: file.body.title,
        status: file.status,
        code: file.body.code,
        requestId: file.body.requestId,
        traceId: undefined,
      });
      expect(file.body.code).toBe(slug.toUpperCase().replaceAll('-', '_'));
      expect(file.headers['CoSec-Request-Id']).toBe(file.body.requestId);

      // The one optional member, as the file types it.
      expect(file.optionalMembers).toEqual({ traceId: 'string' });
      expect((await readCoSecProblem(wire({ traceId: 'abc' })))?.traceId).toBe(
        'abc',
      );
    },
  );

  it('a type the client does not know falls back to its status class', async () => {
    const file = contract.problem('forbidden');
    const unknown = () =>
      new Response(
        JSON.stringify({
          ...file.body,
          type: 'https://cosec.ahoo.me/problems/a-type-of-a-later-release',
          code: 'A_TYPE_OF_A_LATER_RELEASE',
          somethingNew: 1,
        }),
        { status: file.status, headers: file.headers },
      );
    expect(await readCoSecProblem(unknown())).toMatchObject({
      slug: 'a-type-of-a-later-release',
      status: 403,
    });

    // A 403 of an unknown type is a refusal like any 403: the application is told, nothing is retried or cleared.
    const host = new FakeCoSecHost();
    const onForbidden = vi.fn(async () => undefined);
    const { client, tokens } = createClient(host, {
      signedIn: host.login(),
      config: { onForbidden },
    });
    host.after = (seen, response) =>
      seen.path === '/me' ? unknown() : response;
    await client.get('/me').catch(() => undefined);
    expect(onForbidden).toHaveBeenCalledTimes(1);
    expect(host.on('/me')).toHaveLength(1);
    expect(tokens.get()).not.toBeNull();
  });

  it('a 429 is waited out as Retry-After says', () => {
    const file = contract.problem('too-many-requests');
    const response = new Response(null, {
      status: file.status,
      headers: file.headers,
    });
    expect(retryAfterSeconds(response)).toBe(
      Number(file.headers['Retry-After']),
    );
    expect(Object.keys(file.headers).sort()).toEqual(
      [...contract.headers().response.tooManyRequests].sort(),
    );
  });
});

describe('the headers', () => {
  it('the CoSec request headers the client names are the file’s', () => {
    // CoSecHeaders also holds Authorization, which is not a CoSec parameter.
    const named = Object.values(CoSecHeaders).filter(name =>
      name.startsWith('CoSec-'),
    );
    expect([...named].sort()).toEqual([...contract.headers().request].sort());
  });

  it('a request in a space sends those, and no other CoSec header', async () => {
    const host = new FakeCoSecHost();
    const pair = host.login('u1', { roles: ['admin@team-a'] });
    const { client, spaces } = createClient(host, { signedIn: pair });
    spaces.set('team-a');
    await client.get('/me');
    const sent = [...host.on('/me')[0].headers.keys()]
      .filter(name => name.startsWith('cosec-'))
      .sort();
    expect(sent).toEqual(
      contract
        .headers()
        .request.map(name => name.toLowerCase())
        .sort(),
    );
  });

  it('the answer of every outcome carries the request id the client sent', async () => {
    const host = new FakeCoSecHost();
    const { client } = createClient(host, { signedIn: host.login() });
    const response = await client.get('/me');
    const sent = host.on('/me')[0].headers.get('cosec-request-id');
    for (const name of contract.headers().response.everyAnswer) {
      expect(response.headers.get(name)).toBe(sent);
    }
  });

  it('the request id the client generates matches the file’s grammar', async () => {
    const pattern = new RegExp(contract.headers().requestId.pattern);
    for (let index = 0; index < 1000; index++) {
      expect(idGenerator.generateId()).toMatch(pattern);
    }
    const host = new FakeCoSecHost();
    const { client } = createClient(host, { signedIn: host.login() });
    await client.get('/me');
    await client.get('/me');
    const ids = host
      .on('/me')
      .map(seen => seen.headers.get('cosec-request-id'));
    expect(new Set(ids).size).toBe(2);
    for (const id of ids) expect(id).toMatch(pattern);
  });

  it('the host replaces a request id outside the grammar, as the file says', async () => {
    // The fake host answers as the file says; this pins the shared grammar to what the client sends.
    const host = new FakeCoSecHost();
    const response = await host.fetch('/public', {
      headers: { 'CoSec-Request-Id': 'not a plain id' },
    });
    expect(response.headers.get('CoSec-Request-Id')).toMatch(
      new RegExp(contract.headers().requestId.pattern),
    );
  });
});

describe('the token pair', () => {
  it('has the members and types of the file, and only a pair of strings is one', () => {
    const body = contract.pair();
    expect(isCompositeToken(body)).toBe(true);
    for (const name of Object.keys(body)) {
      expect(isCompositeToken({ ...body, [name]: 1 })).toBe(false);
      const without = { ...body };
      delete without[name];
      expect(isCompositeToken(without)).toBe(false);
    }
  });

  it('is what the refresh sends, and what the host answers', async () => {
    const host = new FakeCoSecHost();
    const pair = host.login();
    const { client } = createClient(host, { signedIn: pair });
    vi.setSystemTime(Date.now() + 11 * MINUTE);
    await client.get('/me');
    const sent = host.on('/auth/refresh')[0].body as Record<string, unknown>;
    const shape = (value: Record<string, unknown>) =>
      Object.fromEntries(
        Object.entries(value)
          .map(([name, member]) => [name, jsonTypeOf(member)] as const)
          .sort(),
      );
    expect(shape(sent)).toEqual(shape(contract.pair()));
  });
});

describe('the claims', () => {
  const access = () => contract.claims().access;
  const refresh = () => contract.claims().refresh;

  /** A value of the type the file gives a claim, a plausible one. */
  function valueOf(name: string, rule: ClaimRule): unknown {
    if (rule.type === 'number') return Math.floor(Date.now() / 1000) + 3600;
    if (rule.type === 'array') return ['a'];
    if (rule.type === 'object') return {};
    return name === 'cosec.token' ? 'refresh' : `${name}-value`;
  }

  /** The claim set of `rules` with `only` of them, always the `always` ones. */
  function tokenClaims(
    rules: Record<string, ClaimRule>,
    drop: readonly string[] = [],
  ): Record<string, unknown> {
    return Object.fromEntries(
      Object.entries(rules)
        .filter(([name]) => !drop.includes(name))
        .map(([name, rule]) => [name, valueOf(name, rule)]),
    );
  }

  /** The members of `JwtPayload` and `CoSecJwtPayload`, the types the client gives a claim set. */
  function typedClaims(): string[] {
    const source = ts.createSourceFile(
      'jwts.ts',
      readFileSync(new URL('../src/jwts.ts', import.meta.url), 'utf8'),
      ts.ScriptTarget.ES2020,
      true,
    );
    const names: string[] = [];
    source.forEachChild(node => {
      if (
        ts.isInterfaceDeclaration(node) &&
        ['JwtPayload', 'CoSecJwtPayload'].includes(node.name.text)
      ) {
        for (const member of node.members) {
          if (ts.isPropertySignature(member)) {
            names.push(member.name.getText(source).replaceAll("'", ''));
          }
        }
      }
    });
    return names;
  }

  it('the client types the claims the file says it may read, and no other of CoSec’s', () => {
    // iss and nbf are RFC 7519's, which CoSec's tokens do not carry.
    const typed = typedClaims().filter(name => !['iss', 'nbf'].includes(name));
    const read = Object.entries(access())
      .filter(([, rule]) => rule.client === 'read')
      .map(([name]) => name);
    expect(typed.sort()).toEqual(read.sort());
    // Of a refresh token, `exp` alone.
    expect(
      Object.entries(refresh())
        .filter(([, rule]) => rule.client === 'read')
        .map(([name]) => name),
    ).toEqual(['exp']);
  });

  it('reads of a token, at run time, only claims the file says it may', async () => {
    // JSON.parse hands the client a claim set: a proxy records which claims its code asks for.
    const accessed = new Set<string>();
    const parse = JSON.parse;
    vi.spyOn(JSON, 'parse').mockImplementation(((
      text: string,
      reviver?: never,
    ) => {
      const value = parse(text, reviver);
      if (
        typeof value === 'object' &&
        value !== null &&
        'exp' in value &&
        'jti' in value
      ) {
        return new Proxy(value, {
          get(target, key, receiver) {
            // Only what the client's own code asks: the fake host reads claims too.
            if (
              typeof key === 'string' &&
              /\/src\/[^/]+\.ts/.test(new Error().stack ?? '')
            ) {
              accessed.add(key);
            }
            return Reflect.get(target, key, receiver);
          },
        });
      }
      return value;
    }) as typeof JSON.parse);

    const host = new FakeCoSecHost();
    const { client, tokens } = createClient(host, {
      signedIn: host.login('u1', { aud: 'orders', roles: ['admin@team-a'] }),
    });
    await client.get('/me');
    vi.setSystemTime(Date.now() + 11 * MINUTE);
    await client.get('/me');
    expect(host.refreshes).toHaveLength(1);
    expect(tokens.get()?.access.payload).not.toBeNull();
    vi.restoreAllMocks();

    const mayRead = new Set(
      Object.entries(access())
        .filter(([, rule]) => rule.client === 'read')
        .map(([name]) => name),
    );
    expect(accessed.size).toBeGreaterThan(0);
    expect([...accessed].filter(name => !mayRead.has(name))).toEqual([]);
  });

  it.each([
    ['access', access],
    ['refresh', refresh],
  ] as const)(
    'a %s token with only its always-present claims is read as any other',
    (_kind, rules) => {
      const only = Object.fromEntries(
        Object.entries(rules()).filter(
          ([, rule]) => rule.presence === 'always',
        ),
      );
      const token = new JwtToken<CoSecJwtPayload>(jwt(tokenClaims(only)));
      expect(token.payload).not.toBeNull();
      expect(token.isExpired).toBe(false);
    },
  );

  it('works with any optional claim of an access token absent', async () => {
    const optional = Object.entries(access())
      .filter(([, rule]) => rule.presence === 'optional')
      .map(([name]) => name);
    expect(optional.length).toBeGreaterThan(0);
    for (const dropped of [...optional.map(name => [name]), optional]) {
      const host = new FakeCoSecHost();
      const pair = host.login();
      const accessToken = jwt({
        ...claims(pair.accessToken),
        ...tokenClaims(access(), dropped),
        jti: 'at-fixed',
      });
      const { client } = createClient(host, {
        signedIn: { accessToken, refreshToken: pair.refreshToken },
        // The path has a tenant and an owner: the interceptor fills what it can.
        spaced: () => false,
      });
      const response = await client.get('/me');
      expect(response.status, dropped.join()).toBe(200);
      expect(new JwtToken<CoSecJwtPayload>(accessToken).isExpired).toBe(false);
    }
  });

  it('every claim the file lists is one the fake host may issue, of the type it says', () => {
    // The fake host asserts this on every token it issues (assertClaimsMatch); one login exercises it.
    const host = new FakeCoSecHost();
    const pair = host.login('u1', { aud: 'orders', roles: ['r'] });
    for (const [name, value] of Object.entries(claims(pair.accessToken))) {
      expect(jsonTypeOf(value), name).toBe(access()[name]?.type);
    }
    for (const [name, value] of Object.entries(claims(pair.refreshToken))) {
      expect(jsonTypeOf(value), name).toBe(refresh()[name]?.type);
    }
  });
});

describe('what the served routes answer', () => {
  /** The error the refresh raised: the request rejects with an ExchangeError around it. */
  function raised(error: unknown): unknown {
    expect(error).toBeInstanceOf(ExchangeError);
    return (error as ExchangeError).exchange.error;
  }

  it.each(contract.route('refresh').cases.map(route => [route.case, route]))(
    'refresh: %s',
    async (_name, route) => {
      const host = new FakeCoSecHost();
      const pair = host.login('u1');
      const onUnauthorized = vi.fn();
      const { client, tokens } = createClient(host, {
        signedIn: pair,
        spaced: () => false,
        config: { onUnauthorized },
      });
      vi.setSystemTime(Date.now() + 11 * MINUTE);
      let answered: Response | undefined;
      host.after = (seen, response) => {
        if (seen.path !== '/auth/refresh') return response;
        answered = caseResponse(route, host);
        return answered.clone();
      };

      const outcome = await client.get('/me').catch(error => error);

      if (route.status === 200) {
        // The pair: stored, and the request goes on.
        expect(outcome.status).toBe(200);
        const body = (await answered!.json()) as CompositeToken;
        expect(tokens.get()?.token).toEqual(body);
        return;
      }
      const error = raised(outcome);
      const problem = (error as RefreshTokenError).problem;
      expect(problem?.slug).toBe(route.problem);
      if (route.status === 401) {
        // The session is over: the pair is dropped, the application told.
        expect(error).toBeInstanceOf(RefreshTokenError);
        expect(tokens.get()).toBeNull();
        expect(onUnauthorized).toHaveBeenCalledTimes(1);
      } else {
        // Anything else is no verdict on the session.
        expect(error).toBeInstanceOf(RefreshUnavailableError);
        expect(tokens.get()?.token).toEqual(pair);
        expect(onUnauthorized).not.toHaveBeenCalled();
      }
    },
  );

  it.each(contract.route('logout').cases.map(route => [route.case, route]))(
    'logout: %s',
    async (_name, route) => {
      const host = new FakeCoSecHost();
      const pair = host.login('u1');
      const { client, tokens } = createClient(host, {
        signedIn: pair,
        spaced: () => false,
      });
      const revoker = new CoSecTokenRevoker({
        fetcher: client,
        endpoint: '/auth/logout',
      });
      const manager = createClient(host, {
        tokens,
        config: { tokenRevoker: revoker },
      }).configurer.tokenManager!;
      host.after = (seen, response) =>
        seen.path === '/auth/logout' ? caseResponse(route, host) : response;
      // The route is not modelled: the host answers it 404, `after` says what the file does.
      const outcome = await manager.logout().then(
        () => undefined,
        error => error,
      );

      // The pair is gone here whatever the server said (the user asked to leave).
      expect(tokens.get()).toBeNull();
      expect(host.on('/auth/logout')).toHaveLength(1);
      expect(host.on('/auth/logout')[0].body).toEqual(pair);
      if (route.status === 204 || route.status === 401) {
        expect(outcome).toBeUndefined();
      } else {
        expect(outcome).toBeInstanceOf(LogoutUnconfirmedError);
        expect((outcome as LogoutUnconfirmedError).status).toBe(route.status);
        expect((outcome as LogoutUnconfirmedError).problem?.slug).toBe(
          route.problem,
        );
      }
    },
  );

  it('the refresher posts the pair as JSON, and CoSecTokenRefresher reads the file’s pair', async () => {
    const host = new FakeCoSecHost();
    const { client } = createClient(host);
    const refresher = new CoSecTokenRefresher({
      fetcher: client,
      endpoint: '/auth/refresh',
    });
    const pair = host.login();
    const renewed = await refresher.refresh(pair);
    expect(Object.keys(renewed).sort()).toEqual(
      Object.keys(contract.pair()).sort(),
    );
    expect(host.on('/auth/refresh')[0].headers.get('content-type')).toContain(
      'application/json',
    );
  });
});
