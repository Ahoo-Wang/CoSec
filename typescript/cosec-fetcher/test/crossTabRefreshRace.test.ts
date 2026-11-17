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

import { SerialTypedEventBus } from '@ahoo-wang/fetcher-eventbus';
import { InMemoryStorage } from '@ahoo-wang/fetcher-storage';
import { JwtTokenManager, TokenStorage, type CompositeToken } from '../src';

const jwt = (sub: string, exp: number) =>
  `e30.${btoa(JSON.stringify({ sub, exp }))}.signature`;
const token = (sub: string): CompositeToken => ({
  accessToken: jwt(sub, Date.now() / 1000 + 3600),
  refreshToken: jwt(sub, Date.now() / 1000 + 7200),
});

describe('cross-tab refresh race', () => {
  it('reuses the token another tab just stored instead of removing it', async () => {
    // Two tabs: one localStorage, change events not delivered yet (separate
    // local buses stand for a broadcast still in flight).
    const shared = new InMemoryStorage();
    const tabA = new TokenStorage({
      storage: shared,
      eventBus: new SerialTypedEventBus('tab-a'),
    });
    tabA.signIn(token('original'));
    const tabB = new TokenStorage({
      storage: shared,
      eventBus: new SerialTypedEventBus('tab-b'),
    });
    const original = tabB.get()!;

    // Tab A refreshes first; the refresh token is one-time.
    await new JwtTokenManager(tabA, {
      refresh: async () => token('refreshed'),
    }).refresh();
    const refreshed = shared.getItem('cosec-token');

    // Tab B still holds the old token and its refresh is rejected.
    expect(tabB.get()).toBe(original);
    const tabBManager = new JwtTokenManager(tabB, {
      refresh: async () => {
        throw new Error('refresh token already used');
      },
    });
    await tabBManager.refresh();

    expect(shared.getItem('cosec-token')).toBe(refreshed);
    expect(tabB.get()?.access.payload.sub).toBe('refreshed');
    expect(tabB.get()?.sessionId).toBe(original.sessionId);
  });
});

describe('broadcast bus ownership', () => {
  it('closes the broadcast bus a TokenStorage created, not one it was given', () => {
    const own = new TokenStorage({ storage: new InMemoryStorage() });
    const destroyOwn = vi.spyOn(own.eventBus, 'destroy');
    own.destroy();
    expect(destroyOwn).toHaveBeenCalled();

    const given = new SerialTypedEventBus<any>('given');
    const destroyGiven = vi.spyOn(given, 'destroy');
    new TokenStorage({
      storage: new InMemoryStorage(),
      eventBus: given,
    }).destroy();
    expect(destroyGiven).not.toHaveBeenCalled();
  });
});
