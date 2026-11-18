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
import type { FetchExchange } from '@ahoo-wang/fetcher';
import {
  CoSecProblemType,
  getCoSecProblem,
  readCoSecProblem,
  retryAfterSeconds,
} from '../src';
import { resolveCoSecProblem, toCoSecProblem } from '../src/problem';
import { problemResponse } from './support/fakeCoSec';

describe('CoSec problems', () => {
  it('lists the ten types of CoSec 8.0', () => {
    expect(Object.values(CoSecProblemType).sort()).toEqual(
      [
        'forbidden',
        'internal-error',
        'invalid-request',
        'scope-rejected',
        'token-expired',
        'token-invalid',
        'token-revoked',
        'too-many-requests',
        'unauthorized',
        'unavailable',
      ].sort(),
    );
  });

  it('reads the body of a problem response without consuming it', async () => {
    const response = problemResponse('token-expired');
    const problem = await readCoSecProblem(response);
    expect(problem).toEqual({
      slug: 'token-expired',
      type: 'https://cosec.ahoo.me/problems/token-expired',
      title: 'Token Expired',
      status: 401,
      code: 'TOKEN_EXPIRED',
      requestId: 'req-1',
      traceId: undefined,
    });
    expect(response.bodyUsed).toBe(false);
  });

  it('reads the optional traceId', async () => {
    const problem = await readCoSecProblem(
      problemResponse('forbidden', { traceId: 'abc' }),
    );
    expect(problem?.traceId).toBe('abc');
  });

  it.each([
    ['a plain text body', () => new Response('nope', { status: 401 })],
    [
      'a problem of another service',
      () =>
        new Response(JSON.stringify({ type: 'https://example.com/p/x' }), {
          status: 401,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
    ],
    [
      'a CoSec type with no slug',
      () =>
        new Response(
          JSON.stringify({ type: 'https://cosec.ahoo.me/problems/' }),
          {
            status: 401,
            headers: { 'Content-Type': 'application/problem+json' },
          },
        ),
    ],
  ])('is no CoSec problem for %s', async (_name, make) => {
    expect(await readCoSecProblem(make())).toBeUndefined();
  });

  it('keeps a slug it does not know', () => {
    expect(
      toCoSecProblem({ type: 'https://cosec.ahoo.me/problems/new-thing' })
        ?.slug,
    ).toBe('new-thing');
  });

  it('takes the problem from an error, or from the wrapped cause', () => {
    const problem = toCoSecProblem({
      type: 'https://cosec.ahoo.me/problems/token-revoked',
    })!;
    expect(getCoSecProblem({ problem })).toBe(problem);
    expect(getCoSecProblem({ cause: { problem } })).toBe(problem);
    expect(getCoSecProblem(undefined)).toBeUndefined();
    expect(
      getCoSecProblem({
        problem: { type: 'https://cosec.ahoo.me/problems/forbidden' },
      })?.slug,
    ).toBe('forbidden');
  });

  it('resolves an exchange once, from the error or from the response', async () => {
    const response = problemResponse('scope-rejected');
    const exchange = {
      response,
      attributes: new Map<string, unknown>(),
    } as unknown as FetchExchange;
    const first = await resolveCoSecProblem(exchange);
    expect(first?.slug).toBe('scope-rejected');
    expect(await resolveCoSecProblem(exchange)).toBe(first);

    const withError = {
      response: problemResponse('forbidden'),
      error: new HttpStatusValidationError(
        { request: { url: '/x' }, response: { status: 403 } } as FetchExchange,
        {
          type: 'https://cosec.ahoo.me/problems/forbidden',
        },
      ),
      attributes: new Map<string, unknown>(),
    } as unknown as FetchExchange;
    expect((await resolveCoSecProblem(withError))?.slug).toBe('forbidden');
    expect(withError.response?.bodyUsed).toBe(false);
  });

  it('reads Retry-After as seconds or as a date', () => {
    const seconds = new Response(null, { headers: { 'Retry-After': '7' } });
    expect(retryAfterSeconds(seconds)).toBe(7);
    const at = new Date(1_000_000 + 12_000).toUTCString();
    const date = new Response(null, { headers: { 'Retry-After': at } });
    expect(retryAfterSeconds(date, 1_000_000)).toBe(12);
    expect(retryAfterSeconds(new Response(null))).toBeUndefined();
    expect(
      retryAfterSeconds(
        new Response(null, { headers: { 'Retry-After': 'x' } }),
      ),
    ).toBeUndefined();
  });
});
