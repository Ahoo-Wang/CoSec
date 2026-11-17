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
  createServer,
  type IncomingMessage,
  type ServerResponse,
} from 'node:http';
import type { AddressInfo } from 'node:net';
import {
  ExchangeError,
  Fetcher,
  getHeader,
  ResultExtractors,
} from '@ahoo-wang/fetcher';
import { SerialTypedEventBus } from '@ahoo-wang/fetcher-eventbus';
import { InMemoryStorage } from '@ahoo-wang/fetcher-storage';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  AuthorizationRequestInterceptor,
  AuthorizationResponseInterceptor,
  CoSecConfigurer,
  CoSecTokenRefresher,
  DefaultSpaceIdProvider,
  DeviceIdStorage,
  JwtTokenManager,
  NonReplayableRequestError,
  sameOriginTrust,
  SpaceIdStorage,
  TokenStorage,
  UnauthorizedErrorInterceptor,
} from '../src';

const jwt = (sub: string, exp = Date.now() / 1000 + 3600) =>
  `e30.${btoa(JSON.stringify({ sub, jti: crypto.randomUUID(), exp }))}.sig`;
const pair = (sub: string) => ({
  accessToken: jwt(sub),
  refreshToken: jwt(sub, Date.now() / 1000 + 7200),
});

const destroyables: { destroy(): void }[] = [];
const storages = () => {
  const options = (key: string) => ({
    key,
    storage: new InMemoryStorage(),
    eventBus: new SerialTypedEventBus<never>(key),
  });
  const tokens = new TokenStorage(options('recovery-tokens'));
  const devices = new DeviceIdStorage(options('recovery-devices'));
  const spaces = new SpaceIdStorage(options('recovery-spaces'));
  destroyables.push(tokens, devices, spaces);
  return { tokens, devices, spaces };
};
afterEach(() => {
  destroyables.splice(0).forEach(value => value.destroy());
});

// #1996
describe('a 401 from a request the trust policy kept credentials from', () => {
  it.each(['get', 'post'] as const)(
    'is returned as is for %s: no refresh, no replay, no onUnauthorized',
    async method => {
      const { tokens, devices } = storages();
      tokens.signIn(pair('original'));
      const calls: (string | null)[] = [];
      const refresh = vi.fn(async () => pair('refreshed'));
      const onUnauthorized = vi.fn();
      const client = new Fetcher({
        baseURL: 'https://api.example.test',
        fetch: async (_url, init) => {
          calls.push(new Headers(init?.headers).get('authorization'));
          return new Response('', { status: 401 });
        },
      });
      new CoSecConfigurer({
        appId: 'audit',
        tokenStorage: tokens,
        deviceIdStorage: devices,
        isTrusted: sameOriginTrust,
        tokenRefresher: { refresh },
        onUnauthorized,
      }).applyTo(client);
      const original = tokens.get();
      const error = await client[method](
        'https://untrusted.example.test/action',
        method === 'post' ? { body: { operation: 'side-effect' } } : {},
      ).catch(error => error);
      expect(error).toBeInstanceOf(ExchangeError);
      expect(error.exchange.response.status).toBe(401);
      expect(calls).toEqual([null]);
      expect(refresh).not.toHaveBeenCalled();
      expect(onUnauthorized).not.toHaveBeenCalled();
      expect(tokens.get()).toBe(original);
    },
  );

  it('still refreshes and retries a trusted request', async () => {
    const { tokens, devices } = storages();
    tokens.signIn(pair('original'));
    let calls = 0;
    const refresh = vi.fn(async () => pair('refreshed'));
    const client = new Fetcher({
      baseURL: 'https://api.example.test',
      fetch: async () =>
        new Response('{}', { status: ++calls === 1 ? 401 : 200 }),
    });
    new CoSecConfigurer({
      appId: 'audit',
      tokenStorage: tokens,
      deviceIdStorage: devices,
      isTrusted: sameOriginTrust,
      tokenRefresher: { refresh },
    }).applyTo(client);
    await client.get('/resource');
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(calls).toBe(2);
  });

  it('is honored by a standalone response interceptor', async () => {
    const { tokens } = storages();
    tokens.signIn(pair('original'));
    const refresh = vi.fn(async () => pair('refreshed'));
    const manager = new JwtTokenManager(tokens, { refresh });
    let calls = 0;
    const client = new Fetcher({
      baseURL: 'https://api.example.test',
      fetch: async () => {
        calls++;
        return new Response('', { status: 401 });
      },
    });
    client.interceptors.request.use(
      new AuthorizationRequestInterceptor({
        tokenManager: manager,
        isTrusted: sameOriginTrust,
      }),
    );
    client.interceptors.response.use(
      new AuthorizationResponseInterceptor({ tokenManager: manager }),
    );
    await client.get('https://untrusted.example.test/x').catch(() => {});
    expect(refresh).not.toHaveBeenCalled();
    expect(calls).toBe(1);
  });
});

// #1997
describe('a retry that fails after a successful refresh', () => {
  const failures: [string, () => unknown][] = [
    ['a network error', () => new TypeError('network offline')],
    ['an abort', () => new DOMException('aborted', 'AbortError')],
    ['a timeout', () => new DOMException('timed out', 'TimeoutError')],
  ];
  it.each(failures)(
    'fails with %s, keeps the token and does not notify',
    async (_, failure) => {
      const { tokens } = storages();
      tokens.signIn(pair('original'));
      let calls = 0;
      const thrown = failure();
      const client = new Fetcher({
        baseURL: 'https://api.example.test',
        fetch: async () => {
          if (++calls === 1) return new Response('', { status: 401 });
          throw thrown;
        },
      });
      const manager = new JwtTokenManager(tokens, {
        refresh: async () => pair('refreshed'),
      });
      const onUnauthorized = vi.fn(() => tokens.signOut());
      client.interceptors.request.use(
        new AuthorizationRequestInterceptor({ tokenManager: manager }),
      );
      client.interceptors.response.use(
        new AuthorizationResponseInterceptor({ tokenManager: manager }),
      );
      client.interceptors.error.use(
        new UnauthorizedErrorInterceptor({ onUnauthorized }),
      );
      const error = await client.get('/resource').catch(error => error);
      expect(calls).toBe(2);
      expect(error.cause).toBe(thrown);
      expect(error.exchange.response).toBeUndefined();
      expect(onUnauthorized).not.toHaveBeenCalled();
      expect(tokens.currentUser?.sub).toBe('refreshed');
    },
  );

  it('still notifies when the retry answers 401 again', async () => {
    const { tokens } = storages();
    tokens.signIn(pair('original'));
    const client = new Fetcher({
      baseURL: 'https://api.example.test',
      fetch: async () => new Response('', { status: 401 }),
    });
    const manager = new JwtTokenManager(tokens, {
      refresh: async () => pair('refreshed'),
    });
    const onUnauthorized = vi.fn();
    client.interceptors.request.use(
      new AuthorizationRequestInterceptor({ tokenManager: manager }),
    );
    client.interceptors.response.use(
      new AuthorizationResponseInterceptor({ tokenManager: manager }),
    );
    client.interceptors.error.use(
      new UnauthorizedErrorInterceptor({ onUnauthorized }),
    );
    await client.get('/resource').catch(() => {});
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });
});

async function withServer(
  handler: (req: IncomingMessage, res: ServerResponse) => void,
  run: (baseURL: string) => Promise<void>,
) {
  const server = createServer(handler);
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    await run(`http://127.0.0.1:${(server.address() as AddressInfo).port}`);
  } finally {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
}

async function readBody(req: IncomingMessage) {
  let body = '';
  for await (const chunk of req) body += chunk;
  return body;
}

// #1998
describe('a 401 on a request with a one-shot ReadableStream body', () => {
  const stream = () =>
    new ReadableStream({
      start(controller) {
        controller.enqueue(new TextEncoder().encode('upload'));
        controller.close();
      },
    });

  it('refreshes the session and fails with NonReplayableRequestError', async () => {
    const { tokens } = storages();
    tokens.signIn(pair('original'));
    const received: string[] = [];
    const refresh = vi.fn(async () => pair('refreshed'));
    const onUnauthorized = vi.fn();
    await withServer(
      async (req, res) => {
        received.push(await readBody(req));
        res.statusCode = received.length === 1 ? 401 : 200;
        res.end('ok');
      },
      async baseURL => {
        const client = new Fetcher({ baseURL });
        const manager = new JwtTokenManager(tokens, { refresh });
        client.interceptors.request.use(
          new AuthorizationRequestInterceptor({ tokenManager: manager }),
        );
        client.interceptors.response.use(
          new AuthorizationResponseInterceptor({ tokenManager: manager }),
        );
        client.interceptors.error.use(
          new UnauthorizedErrorInterceptor({ onUnauthorized }),
        );
        const error = await client
          .post('/upload', { body: stream() })
          .catch(error => error);
        expect(error.cause).toBeInstanceOf(NonReplayableRequestError);
        expect(error.exchange.response.status).toBe(401);
      },
    );
    expect(received).toEqual(['upload']);
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(tokens.currentUser?.sub).toBe('refreshed');
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it.each([
    ['a JSON body', () => ({ upload: true })],
    ['a string body', () => 'upload'],
    ['a Blob body', () => new Blob(['upload'])],
  ])('still retries %s', async (_, body) => {
    const { tokens } = storages();
    tokens.signIn(pair('original'));
    const received: string[] = [];
    await withServer(
      async (req, res) => {
        received.push(await readBody(req));
        res.statusCode = received.length === 1 ? 401 : 200;
        res.end('ok');
      },
      async baseURL => {
        const client = new Fetcher({ baseURL });
        const manager = new JwtTokenManager(tokens, {
          refresh: async () => pair('refreshed'),
        });
        client.interceptors.request.use(
          new AuthorizationRequestInterceptor({ tokenManager: manager }),
        );
        client.interceptors.response.use(
          new AuthorizationResponseInterceptor({ tokenManager: manager }),
        );
        const response = await client.post('/upload', { body: body() });
        expect(response.status).toBe(200);
      },
    );
    expect(received).toHaveLength(2);
    expect(received[1]).toBe(received[0]);
  });
});

// #2020
describe('the space of a request retried after a 401', () => {
  it.each([
    ['switched', 'workspace-b'],
    ['removed', null],
    ['unchanged', 'workspace-a'],
  ] as const)(
    'stays the original space when the current space is %s during refresh',
    async (_, nextSpace) => {
      const { tokens, devices, spaces } = storages();
      let completeRefresh!: (value: ReturnType<typeof pair>) => void;
      let markStarted!: () => void;
      const refreshStarted = new Promise<void>(resolve => {
        markStarted = resolve;
      });
      const sent: (string | undefined)[] = [];
      const fetcher = new Fetcher({
        baseURL: 'https://example.test',
        fetch: async (_url, init) => {
          sent.push(
            getHeader(
              init?.headers as Record<string, string>,
              'CoSec-Space-Id',
            ),
          );
          return new Response('', { status: sent.length === 1 ? 401 : 200 });
        },
      });
      new CoSecConfigurer({
        appId: 'demo',
        tokenStorage: tokens,
        deviceIdStorage: devices,
        spaceIdProvider: new DefaultSpaceIdProvider({
          spacedResourcePredicate: { test: () => true },
          spaceIdStorage: spaces,
        }),
        tokenRefresher: {
          refresh: () => {
            markStarted();
            return new Promise(resolve => (completeRefresh = resolve));
          },
        },
      }).applyTo(fetcher);
      tokens.signIn(pair('old'));
      spaces.set('workspace-a');
      const saving = fetcher.post('/documents', { body: { text: 'edit' } });
      await refreshStarted;
      if (nextSpace === null) spaces.remove();
      else spaces.set(nextSpace);
      completeRefresh(pair('new'));
      await saving;
      expect(sent).toEqual(['workspace-a', 'workspace-a']);

      await fetcher.get('/documents');
      expect(sent[2]).toBe(nextSpace ?? undefined);
    },
  );
});

// #2033
describe('the 401 response replaced by a refresh and retry', () => {
  it('has its body released by the library', async () => {
    const { tokens, devices } = storages();
    const old = pair('old');
    tokens.signIn(old);
    const requests: string[] = [];
    let originalClosed!: () => void;
    const closed = new Promise<void>(resolve => (originalClosed = resolve));
    await withServer(
      async (req, res) => {
        requests.push(req.url!);
        if (req.url === '/refresh') {
          await readBody(req);
          res.setHeader('Content-Type', 'application/json');
          res.end(JSON.stringify(pair('new')));
          return;
        }
        if (req.headers.authorization === `Bearer ${old.accessToken}`) {
          res.writeHead(401, { 'Content-Type': 'text/plain' });
          res.write('refresh required\n');
          res.on('close', () => originalClosed());
          return;
        }
        res.setHeader('Content-Type', 'application/json');
        res.end('{"ok":true}');
      },
      async baseURL => {
        const client = new Fetcher({ baseURL, timeout: 5000 });
        new CoSecConfigurer({
          appId: 'local',
          tokenStorage: tokens,
          deviceIdStorage: devices,
          tokenRefresher: new CoSecTokenRefresher({
            fetcher: client,
            endpoint: '/refresh',
          }),
        }).applyTo(client);
        const result = await client.get(
          '/business',
          {},
          { resultExtractor: ResultExtractors.Json },
        );
        expect(result).toEqual({ ok: true });
        await expect(
          Promise.race([
            closed.then(() => 'closed'),
            new Promise(resolve => setTimeout(resolve, 1000, 'open')),
          ]),
        ).resolves.toBe('closed');
      },
    );
    expect(requests).toEqual(['/business', '/refresh', '/business']);
  });

  const respond = (cancel: () => void) =>
    new Response(
      new ReadableStream({
        pull(controller) {
          controller.enqueue(new TextEncoder().encode('x'));
        },
        cancel,
      }),
      { status: 401 },
    );

  it('cancels an unread body before refreshing', async () => {
    const { tokens } = storages();
    tokens.signIn(pair('old'));
    const cancel = vi.fn();
    let calls = 0;
    let cancelledBeforeRefresh = false;
    const client = new Fetcher({
      baseURL: 'https://example.test',
      fetch: async () =>
        ++calls === 1 ? respond(cancel) : new Response('{}', { status: 200 }),
    });
    const manager = new JwtTokenManager(tokens, {
      refresh: async () => {
        await Promise.resolve();
        cancelledBeforeRefresh = cancel.mock.calls.length === 1;
        return pair('new');
      },
    });
    client.interceptors.request.use(
      new AuthorizationRequestInterceptor({ tokenManager: manager }),
    );
    client.interceptors.response.use(
      new AuthorizationResponseInterceptor({ tokenManager: manager }),
    );
    await client.get('/business');
    expect(cancel).toHaveBeenCalledTimes(1);
    expect(cancelledBeforeRefresh).toBe(true);
  });

  it('leaves a body that is already being read and keeps a refresh failure', async () => {
    const { tokens } = storages();
    tokens.signIn(pair('old'));
    const cancel = vi.fn();
    let reader: ReadableStreamDefaultReader | undefined;
    const client = new Fetcher({
      baseURL: 'https://example.test',
      fetch: async () => respond(cancel),
    });
    const failure = Object.assign(new Error('rejected'), {
      exchange: { response: { status: 401 } },
    });
    const manager = new JwtTokenManager(tokens, {
      refresh: async () => {
        throw failure;
      },
    });
    client.interceptors.request.use(
      new AuthorizationRequestInterceptor({ tokenManager: manager }),
    );
    client.interceptors.response.use({
      name: 'reader',
      order: Number.MIN_SAFE_INTEGER,
      intercept: exchange => {
        reader = exchange.response!.body!.getReader();
      },
    });
    client.interceptors.response.use(
      new AuthorizationResponseInterceptor({ tokenManager: manager }),
    );
    const error = await client.get('/business').catch(error => error);
    expect(error.cause.name).toBe('RefreshTokenError');
    expect(cancel).not.toHaveBeenCalled();
    await reader!.cancel();
  });
});
