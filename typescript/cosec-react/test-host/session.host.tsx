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
import {
  CoSecTokenRefresher,
  JwtTokenManager,
  TokenStorage,
} from '@ahoo-wang/cosec-fetcher';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import {
  endSession,
  hostUrl,
  login,
  loginExpired,
  resetStats,
  stats,
} from '../../test-host/hostApi';
import { RefreshableRouteGuard, SecurityProvider, useSecurity } from '../src';

let counter = 0;
const created: TokenStorage[] = [];

function session() {
  const key = `react-host-${++counter}`;
  const tokenStorage = new TokenStorage({
    key,
    storage: new InMemoryStorage(),
    eventBus: new SerialTypedEventBus<never>(key),
  });
  created.push(tokenStorage);
  // The refresh goes through a Fetcher of its own, as the README of cosec-fetcher recommends.
  const tokenManager = new JwtTokenManager(
    tokenStorage,
    new CoSecTokenRefresher({
      fetcher: new Fetcher({ baseURL: hostUrl() }),
      endpoint: '/auth/refresh',
    }),
  );
  return { tokenStorage, tokenManager };
}

afterEach(() => {
  cleanup();
  created.splice(0).forEach(storage => {
    storage.destroy();
    storage.eventBus.destroy();
  });
});

describe('the hooks against a real host', () => {
  it('shows the guarded content after a refresh the host answered', async () => {
    await resetStats();
    const { tokenStorage, tokenManager } = session();
    const pair = await loginExpired('alice');
    tokenStorage.signIn(pair);
    render(
      <SecurityProvider tokenStorage={tokenStorage}>
        <RefreshableRouteGuard
          tokenManager={tokenManager}
          refreshing={<p>refreshing</p>}
          fallback={<p>signed out</p>}
        >
          <p>protected</p>
        </RefreshableRouteGuard>
      </SecurityProvider>,
    );
    expect(await screen.findByText('protected')).toBeTruthy();
    expect((await stats()).refreshes).toBe(1);
  });

  it('falls back to signed out when the host refuses the refresh', async () => {
    await resetStats();
    const { tokenStorage, tokenManager } = session();
    const pair = await loginExpired('alice');
    tokenStorage.signIn(pair);
    await endSession(pair);
    render(
      <SecurityProvider tokenStorage={tokenStorage}>
        <RefreshableRouteGuard
          tokenManager={tokenManager}
          refreshing={<p>refreshing</p>}
          fallback={<p>signed out</p>}
        >
          <p>protected</p>
        </RefreshableRouteGuard>
      </SecurityProvider>,
    );
    expect(await screen.findByText('signed out')).toBeTruthy();
    expect(screen.queryByText('protected')).toBeNull();
    expect((await stats()).refreshes).toBe(1);
  });

  it('signs in with a pair the host issued, and signs out', async () => {
    const { tokenStorage } = session();
    function Who() {
      const { authenticated, currentUser, signIn, signOut } =
        useSecurity(tokenStorage);
      return (
        <>
          <p>{authenticated ? `user ${currentUser.sub}` : 'anonymous'}</p>
          <button onClick={() => void signIn(() => login('carol'))}>in</button>
          <button onClick={signOut}>out</button>
        </>
      );
    }
    render(<Who />);
    expect(screen.getByText('anonymous')).toBeTruthy();
    screen.getByText('in').click();
    await waitFor(() => expect(screen.getByText('user carol')).toBeTruthy());
    screen.getByText('out').click();
    await waitFor(() => expect(screen.getByText('anonymous')).toBeTruthy());
  });
});
