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
import { describe, expect, it, vi } from 'vitest';
import {
  AuthorizationRequestInterceptor,
  JwtCompositeTokenSerializer,
  JwtTokenManager,
  TokenStorage,
} from '../src';

const jwt = `e30.${btoa(JSON.stringify({ sub: 'user', exp: Date.now() / 1000 + 3600 }))}.test`;

// #2001: a stored record that is valid JSON but not a composite token must be
// rejected, so TokenStorage drops it instead of sending `Bearer undefined`.
const invalidRecords: [string, unknown][] = [
  ['null', null],
  ['an array', []],
  ['a string', 'token'],
  ['an empty object', {}],
  ['a missing refresh token', { accessToken: jwt }],
  ['a missing access token', { refreshToken: jwt }],
  ['number token fields', { accessToken: 1, refreshToken: 2 }],
  ['a non-string refresh token', { accessToken: jwt, refreshToken: {} }],
];

describe('JwtCompositeTokenSerializer.deserialize', () => {
  it.each(invalidRecords)('rejects %s', (_, record) => {
    expect(() =>
      new JwtCompositeTokenSerializer().deserialize(JSON.stringify(record)),
    ).toThrow(TypeError);
  });

  it('keeps the session id and early period of a current record', () => {
    const token = new JwtCompositeTokenSerializer(30).deserialize(
      JSON.stringify({ accessToken: jwt, refreshToken: jwt, sessionId: 's1' }),
    );
    expect(token.sessionId).toBe('s1');
    expect(token.earlyPeriod).toBe(30);
    expect(token.token).toEqual({ accessToken: jwt, refreshToken: jwt });
  });

  it('migrates a valid legacy record without a session id', () => {
    const serializer = new JwtCompositeTokenSerializer();
    const record = JSON.stringify({ accessToken: jwt, refreshToken: jwt });
    const token = serializer.deserialize(record);
    expect(token.sessionId).toEqual(expect.any(String));
    expect(serializer.deserialize(record).sessionId).toBe(token.sessionId);
  });
});

describe('TokenStorage with an invalid stored record', () => {
  it.each(invalidRecords)(
    'removes %s and sends no Authorization',
    async (_, record) => {
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const key = 'invalid-token-record';
      const backend = new InMemoryStorage();
      backend.setItem(key, JSON.stringify(record));
      const bus = new SerialTypedEventBus<never>(key);
      const storage = new TokenStorage({
        key,
        storage: backend,
        eventBus: bus,
      });
      try {
        expect(storage.get()).toBeNull();
        expect(backend.getItem(key)).toBeNull();
        expect(warn).toHaveBeenCalled();

        let authorization: string | null = 'unset';
        const client = new Fetcher({
          baseURL: 'https://local.example.test',
          fetch: async (_url, init) => {
            authorization = new Headers(init?.headers).get('authorization');
            return new Response('{}');
          },
        });
        client.interceptors.request.use(
          new AuthorizationRequestInterceptor({
            tokenManager: new JwtTokenManager(storage, {
              refresh: async () => {
                throw new Error('not expected');
              },
            }),
          }),
        );
        await client.get('/resource');
        expect(authorization).toBeNull();
      } finally {
        storage.destroy();
        bus.destroy();
      }
    },
  );
});
