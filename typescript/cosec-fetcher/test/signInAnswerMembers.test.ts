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

import { InMemoryStorage } from '@ahoo-wang/fetcher-storage';
import { SerialTypedEventBus } from '@ahoo-wang/fetcher-eventbus';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  CoSecTokenRevoker,
  JwtCompositeTokenSerializer,
  TokenStorage,
} from '../src';
import { createClient, destroyClients } from './support/client';
import { FakeCoSecHost } from './support/fakeCoSec';

const MINUTE = 60_000;
const extras = { expiresIn: 600, user: { name: 'u1' } };

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
});
afterEach(() => {
  destroyClients();
  vi.useRealTimers();
});

// DR1-H-2: a login may answer members besides the pair (`clients.md`), and
// the refresh and logout routes refuse a body that is not exactly the pair
// (400 invalid-request, `contract/routes/refresh.json`, `logout.json`).
describe('a sign-in answer with members besides the pair', () => {
  it('keeps the pair alone', () => {
    const host = new FakeCoSecHost();
    const pair = host.login('u1');
    const { tokens } = createClient(host);
    tokens.signIn({ ...pair, ...extras });
    expect(tokens.get()?.token).toEqual(pair);
  });

  it('refreshes with the pair alone', async () => {
    const host = new FakeCoSecHost();
    const pair = host.login('u1');
    const { client, tokens } = createClient(host, { spaced: () => false });
    tokens.signIn({ ...pair, ...extras });
    vi.setSystemTime(Date.now() + 11 * MINUTE);
    expect((await client.get('/me')).status).toBe(200);
    const sent = host.on('/auth/refresh');
    expect(sent.map(seen => seen.status)).toEqual([200]);
    expect(sent[0].body).toEqual(pair);
    expect(tokens.get()?.token.accessToken).not.toBe(pair.accessToken);
  });

  it('logs out with the pair alone', async () => {
    const host = new FakeCoSecHost();
    const pair = host.login('u1');
    const sent: unknown[] = [];
    const { tokens, configurer } = createClient(host, {
      config: {
        tokenRevoker: new CoSecTokenRevoker({
          fetcher: {
            post: async (_url: string, request: { body: unknown }) => {
              sent.push(request.body);
            },
          } as never,
          endpoint: '/auth/logout',
        }),
      },
    });
    tokens.signIn({ ...pair, ...extras });
    await configurer.tokenManager!.logout();
    expect(sent).toEqual([pair]);
  });

  it('sends the pair alone for a record a client stored with extra members', async () => {
    const host = new FakeCoSecHost();
    const pair = host.login('u1');
    const storage = new InMemoryStorage();
    // What a client before this fix stored: the answer as given, and the session.
    storage.setItem(
      'stored-tokens',
      JSON.stringify({ ...pair, ...extras, sessionId: 's-1' }),
    );
    const tokens = new TokenStorage({
      key: 'stored-tokens',
      storage,
      eventBus: new SerialTypedEventBus('stored-tokens'),
    });
    expect(tokens.get()?.token).toEqual(pair);
    expect(tokens.get()?.sessionId).toBe('s-1');
    tokens.destroy();
    expect(
      new JwtCompositeTokenSerializer().deserialize(
        JSON.stringify({ ...pair, ...extras }),
      ).token,
    ).toEqual(pair);
  });
});
