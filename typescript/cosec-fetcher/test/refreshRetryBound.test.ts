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

import {
  ExchangeError,
  Fetcher,
  HttpStatusValidationError,
  type FetchExchange,
} from '@ahoo-wang/fetcher';
import { SerialTypedEventBus } from '@ahoo-wang/fetcher-eventbus';
import { InMemoryStorage } from '@ahoo-wang/fetcher-storage';
import {
  AuthorizationRequestInterceptor,
  AuthorizationResponseInterceptor,
  ForbiddenErrorInterceptor,
  JwtTokenManager,
  RefreshTokenError,
  RefreshUnavailableError,
  TokenStorage,
  UnauthorizedErrorInterceptor,
  type CompositeToken,
  type TokenRefresher,
} from '../src';

const jwt = (sub: string, exp: number) =>
  `e30.${btoa(JSON.stringify({ sub, exp }))}.signature`;
const token = (sub: string): CompositeToken => ({
  accessToken: jwt(sub, Date.now() / 1000 + 3600),
  refreshToken: jwt(sub, Date.now() / 1000 + 7200),
});

/**
 * Regression suite for #1249: a 401 is refreshed and retried by re-running the
 * whole interceptor chain, which re-enters AuthorizationResponseInterceptor.
 * These tests drive a real Fetcher so the recursion is the production one.
 */
describe('401 refresh-retry bound through a real Fetcher', () => {
  let storage: TokenStorage;
  let client: Fetcher;
  let unauthorized: FetchExchange[];
  let refreshCount: number;
  let requested: string[];

  const wire = (refresh: TokenRefresher['refresh']) => {
    const manager = new JwtTokenManager(storage, {
      refresh: current => {
        refreshCount++;
        return refresh(current);
      },
    });
    client.interceptors.request.use(
      new AuthorizationRequestInterceptor({ tokenManager: manager }),
    );
    client.interceptors.response.use(
      new AuthorizationResponseInterceptor({ tokenManager: manager }),
    );
    client.interceptors.error.use(
      new UnauthorizedErrorInterceptor({
        onUnauthorized: exchange => {
          unauthorized.push(exchange);
        },
      }),
    );
  };

  const serve = (status: (call: number) => number) => {
    vi.stubGlobal('fetch', async (url: string) => {
      requested.push(url);
      return new Response('', { status: status(requested.length) });
    });
  };

  beforeEach(() => {
    storage = new TokenStorage({
      storage: new InMemoryStorage(),
      eventBus: new SerialTypedEventBus('refresh-retry-bound'),
    });
    storage.signIn(token('original'));
    client = new Fetcher({ baseURL: 'https://example.test' });
    unauthorized = [];
    refreshCount = 0;
    requested = [];
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    storage.destroy();
    storage.eventBus.destroy();
  });

  it('stops after one refresh when the retried request still returns 401', async () => {
    // Every refresh succeeds with a new token; the endpoint keeps answering 401.
    // Unbounded, each retry re-enters the interceptor and refreshes again.
    const issued: CompositeToken[] = [];
    wire(async () => {
      issued.push(token(`refreshed-${issued.length + 1}`));
      return issued[issued.length - 1];
    });
    serve(() => 401);

    const outcome = await Promise.race([
      client.get('/resource').then(
        () => 'resolved',
        error => error,
      ),
      new Promise(resolve => setTimeout(() => resolve('pending'), 200)),
    ]);

    expect(refreshCount).toBe(1);
    expect(requested).toEqual([
      'https://example.test/resource',
      'https://example.test/resource',
    ]);
    // The original failure surfaces: the retried request's 401, not a
    // refresh error or a hang.
    expect(outcome).toBeInstanceOf(ExchangeError);
    const { exchange } = outcome as ExchangeError;
    expect(exchange.error).toBeInstanceOf(HttpStatusValidationError);
    expect(exchange.response?.status).toBe(401);
    // A successful refresh keeps the (valid) refreshed token.
    expect(storage.get()?.token).toEqual(issued[0]);
    expect(unauthorized).toHaveLength(1);
  });

  it('retries exactly once after a successful refresh', async () => {
    wire(async () => token('refreshed'));
    serve(call => (call === 1 ? 401 : 200));

    const response = await client.get('/resource');

    expect(response.status).toBe(200);
    expect(refreshCount).toBe(1);
    expect(requested).toHaveLength(2);
    expect(unauthorized).toHaveLength(0);
  });

  it('removes the token once and surfaces RefreshTokenError when refresh fails', async () => {
    const remove = vi.spyOn(storage, 'remove');
    wire(async () => {
      throw Object.assign(new Error('refresh rejected'), {
        exchange: { response: { status: 401 } },
      });
    });
    serve(() => 401);

    const error = await client.get('/resource').catch(error => error);
    expect(error).toBeInstanceOf(ExchangeError);
    expect((error as ExchangeError).exchange.error).toBeInstanceOf(
      RefreshTokenError,
    );

    expect(refreshCount).toBe(1);
    expect(requested).toHaveLength(1);
    expect(remove).toHaveBeenCalledTimes(1);
    expect(storage.get()).toBeNull();
    expect(unauthorized).toHaveLength(1);
  });

  it('shares one refresh across simultaneous 401s and bounds each retry', async () => {
    let release!: () => void;
    const gate = new Promise<void>(resolve => {
      release = resolve;
    });
    const refreshed = token('refreshed');
    wire(async () => {
      await gate;
      return refreshed;
    });
    serve(() => 401);

    const results = Promise.allSettled([
      client.get('/a'),
      client.get('/b'),
      client.get('/c'),
    ]);
    await vi.waitFor(() => expect(requested).toHaveLength(3));
    release();
    const settled = await results;

    expect(refreshCount).toBe(1);
    // Each request: original + one retry.
    expect(requested).toHaveLength(6);
    for (const result of settled) {
      expect(result.status).toBe('rejected');
      const { exchange } = (result as PromiseRejectedResult)
        .reason as ExchangeError;
      expect(exchange.error).toBeInstanceOf(HttpStatusValidationError);
    }
    expect(storage.get()?.token).toEqual(refreshed);
    expect(unauthorized).toHaveLength(3);
  });

  it('runs the error phase once and does not wrap the error when the retry fails', async () => {
    wire(async () => token('refreshed'));
    const errors: unknown[] = [];
    client.interceptors.error.use({
      name: 'ErrorRecorder',
      order: 1,
      intercept: async exchange => {
        errors.push(exchange.error);
      },
    });
    let forbidden = 0;
    client.interceptors.error.use(
      new ForbiddenErrorInterceptor({
        onForbidden: async () => {
          forbidden++;
        },
      }),
    );
    serve(call => (call === 1 ? 401 : 403));

    const error = await client.get('/resource').catch(error => error);

    expect(error).toBeInstanceOf(ExchangeError);
    const cause = (error as ExchangeError).exchange.error;
    expect(cause).toBeInstanceOf(HttpStatusValidationError);
    expect(errors).toEqual([cause]);
    expect(forbidden).toBe(1);
    expect(unauthorized).toHaveLength(0);
  });

  it('runs later response interceptors once on the retried response', async () => {
    wire(async () => token('refreshed'));
    const seen: number[] = [];
    client.interceptors.response.use({
      name: 'BodyReader',
      order: 0,
      intercept: async exchange => {
        seen.push(exchange.response!.status);
        await exchange.response!.text();
      },
    });
    serve(call => (call === 1 ? 401 : 200));

    const response = await client.get('/resource');

    expect(response.status).toBe(200);
    expect(seen).toEqual([200]);
  });

  it('gives the retry its own timeout after the first attempt used it', async () => {
    client = new Fetcher({ baseURL: 'https://example.test', timeout: 50 });
    wire(async () => token('refreshed'));
    vi.stubGlobal('fetch', async (url: string, init: RequestInit) => {
      requested.push(url);
      expect(init.signal?.aborted).toBe(false);
      return new Response('', { status: requested.length === 1 ? 401 : 200 });
    });

    const response = await client.get('/resource');

    expect(response.status).toBe(200);
    expect(requested).toHaveLength(2);
  });

  it.each([
    ['a network failure', () => new TypeError('Failed to fetch')],
    [
      'a 5xx from the refresh endpoint',
      () =>
        Object.assign(new Error('refresh unavailable'), {
          exchange: { response: { status: 503 } },
        }),
    ],
  ])('keeps the session and does not notify on %s', async (_name, failure) => {
    const remove = vi.spyOn(storage, 'remove');
    wire(async () => {
      throw failure();
    });
    serve(() => 401);

    const error = await client.get('/resource').catch(error => error);
    expect(error).toBeInstanceOf(ExchangeError);
    expect((error as ExchangeError).exchange.error).toBeInstanceOf(
      RefreshUnavailableError,
    );
    expect(remove).not.toHaveBeenCalled();
    expect(storage.get()).not.toBeNull();
    expect(unauthorized).toHaveLength(0);
  });
});
