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
import { expect, it } from 'vitest';
import {
  JwtCompositeToken,
  JwtCompositeTokenSerializer,
  TokenStorage,
} from '../src';
import { InMemoryStorage, type StorageEvent } from '@ahoo-wang/fetcher-storage';
import {
  BroadcastTypedEventBus,
  SerialTypedEventBus,
  type CrossTabMessageHandler,
} from '@ahoo-wang/fetcher-eventbus';

it('restores an old cloned JwtCompositeToken through the storage serializer hook', async () => {
  const jwt = `e30.${Buffer.from(JSON.stringify({ sub: 'legacy', exp: Math.floor(Date.now() / 1000) + 3600 })).toString('base64url')}.sig`;
  const original = new JwtCompositeToken({
    accessToken: jwt,
    refreshToken: jwt,
  });
  let incoming: CrossTabMessageHandler | undefined;
  const bus = new BroadcastTypedEventBus<StorageEvent<JwtCompositeToken>>({
    delegate: new SerialTypedEventBus('old-token'),
    messenger: {
      postMessage() {},
      close() {},
      set onmessage(handler: CrossTabMessageHandler) {
        incoming = handler;
      },
    },
  });
  const storage = new TokenStorage({
    key: 'old-token',
    storage: new InMemoryStorage(),
    eventBus: bus,
    earlyPeriod: 20,
  });
  await incoming?.(structuredClone({ newValue: original, oldValue: null }));
  expect(storage.authenticated).toBe(true);
  expect(storage.get()).toBeInstanceOf(JwtCompositeToken);
  expect(storage.get()?.sessionId).toBe(original.sessionId);
  expect(storage.get()?.earlyPeriod).toBe(20);
  storage.destroy();
  bus.destroy();
});

it.each([
  null,
  2,
  {},
  { token: {} },
  { token: { accessToken: 2, refreshToken: 'r' } },
  { token: { accessToken: 'a', refreshToken: 2 } },
])('rejects malformed legacy token data: %j', value => {
  expect(() =>
    new JwtCompositeTokenSerializer().deserializeLegacy(value),
  ).toThrow(TypeError);
});
