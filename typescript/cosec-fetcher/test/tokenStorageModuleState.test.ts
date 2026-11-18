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

import { randomUUID } from 'node:crypto';
import { expect, it, vi } from 'vitest';
import { InMemoryStorage } from '@ahoo-wang/fetcher-storage';
import { TokenStorage } from '../src/tokenStorage';

it('reuses the serializer of a default-created bus across CoSec module copies', async () => {
  vi.resetModules();
  const other = await import('../src/tokenStorage');
  const otherJwt = await import('../src/jwtToken');
  const first = new TokenStorage({
    key: randomUUID(),
    earlyPeriod: 30,
    storage: new InMemoryStorage(),
  });
  const serialize = vi.spyOn(
    otherJwt.JwtCompositeTokenSerializer.prototype,
    'serialize',
  );
  const second = new other.TokenStorage({
    eventBus: first.eventBus,
    key: first.eventBus.type,
    earlyPeriod: 30,
    storage: new InMemoryStorage(),
  });
  const emitted = vi.spyOn(second.eventBus, 'emit');
  try {
    second.signIn({ accessToken: 'a', refreshToken: 'b' });
    await emitted.mock.results[0].value;
    expect(serialize).not.toHaveBeenCalled();
    expect(first.get()?.earlyPeriod).toBe(30);
  } finally {
    serialize.mockRestore();
    first.destroy();
    second.destroy();
    first.eventBus.destroy();
  }
});

it('rejects a different earlyPeriod across CoSec module copies', async () => {
  vi.resetModules();
  const other = await import('../src/tokenStorage');
  const key = randomUUID();
  const first = new TokenStorage({ key, earlyPeriod: 0 });
  try {
    expect(
      () =>
        new other.TokenStorage({
          key,
          eventBus: first.eventBus,
          earlyPeriod: 60,
        }),
    ).toThrow('same earlyPeriod');
  } finally {
    first.destroy();
    first.eventBus.destroy();
  }
});
