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
import {
  BroadcastTypedEventBus,
  SerialTypedEventBus,
} from '@ahoo-wang/fetcher-eventbus';
import { InMemoryStorage, type StorageEvent } from '@ahoo-wang/fetcher-storage';
import { TokenStorage } from '../src/tokenStorage';
import { JwtCompositeToken } from '../src/jwtToken';

it.each([false, true])(
  'shares a bus with the same earlyPeriod (default bus: %s)',
  async useDefault => {
    const bus = new BroadcastTypedEventBus<StorageEvent<JwtCompositeToken>>({
      delegate: new SerialTypedEventBus(randomUUID()),
    });
    const storage = new InMemoryStorage();
    const first = new TokenStorage({
      key: 'k',
      storage,
      eventBus: useDefault ? undefined : bus,
      earlyPeriod: 30,
    });
    const second = new TokenStorage({
      key: 'k',
      storage,
      eventBus: first.eventBus,
      earlyPeriod: 30,
    });
    try {
      const token = new JwtCompositeToken(
        { accessToken: 'a', refreshToken: 'b' },
        30,
      );
      await first.eventBus.emit({ newValue: token, oldValue: null });
      expect(first.get()).toBe(token);
      expect(second.get()).toBe(token);
      expect(second.get()?.earlyPeriod).toBe(30);
    } finally {
      first.destroy();
      second.destroy();
      first.eventBus.destroy();
      bus.destroy();
    }
  },
);

it('rejects sharing a bus with another earlyPeriod before registering a handler', () => {
  const bus = new BroadcastTypedEventBus<StorageEvent<JwtCompositeToken>>({
    delegate: new SerialTypedEventBus(randomUUID()),
  });
  const first = new TokenStorage({ key: 'k', eventBus: bus, earlyPeriod: 0 });
  try {
    expect(
      () => new TokenStorage({ key: 'k', eventBus: bus, earlyPeriod: 60 }),
    ).toThrow('same earlyPeriod');
    expect(bus.handlers).toHaveLength(1);
  } finally {
    first.destroy();
    bus.destroy();
  }
});

it('keeps independent early periods for separate default buses on the same channel', async () => {
  const key = randomUUID();
  const storage = new InMemoryStorage();
  vi.stubGlobal('window', { localStorage: storage });
  const first = new TokenStorage({ key, storage, earlyPeriod: 0 });
  const second = new TokenStorage({ key, storage, earlyPeriod: 60 });
  try {
    const changed = new Promise<StorageEvent<JwtCompositeToken>>(resolve =>
      second.addListener({ name: 'next', once: true, handle: resolve }),
    );
    first.signIn({ accessToken: 'a', refreshToken: 'b' });
    const received = await changed;
    expect(received.newValue?.earlyPeriod).toBe(60);
    expect(second.get()?.earlyPeriod).toBe(60);
    expect(first.get()?.earlyPeriod).toBe(0);
  } finally {
    first.destroy();
    second.destroy();
    first.eventBus.destroy();
    second.eventBus.destroy();
  }
});
