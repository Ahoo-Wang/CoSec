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
  CoSecConfigurer,
  CoSecTokenRefresher,
  DeviceIdStorage,
  TokenStorage,
  type CoSecConfig,
  type CompositeToken,
} from '../src';

// Node's, which vitest runs on: the packages do not depend on @types/node.
declare const process: { env: Record<string, string | undefined> };

/** The host `pnpm test:host` started (test/client-host). */
export function hostUrl(): string {
  const url = process.env.COSEC_HOST_URL;
  if (!url) {
    throw new Error(
      'COSEC_HOST_URL is not set: these tests need the host of test/client-host, which `pnpm test:host` starts.',
    );
  }
  return url;
}

const destroyables: { destroy(): void }[] = [];
let counter = 0;

/** Destroy what {@link createHostClient} made; call it from `afterEach`. */
export function destroyHostClients(): void {
  destroyables.splice(0).forEach(value => value.destroy());
}

export interface HostClient {
  client: Fetcher;
  tokens: TokenStorage;
  devices: DeviceIdStorage;
}

/**
 * A Fetcher over the real host, configured as an application would be: one Fetcher for requests and for the refresh
 * (the refresh marks its own requests), with the defaults of the package.
 */
export function createHostClient({
  baseURL = hostUrl(),
  config = {},
}: { baseURL?: string; config?: Partial<CoSecConfig> } = {}): HostClient {
  const key = `host-${++counter}`;
  const options = (name: string) => ({
    key: `${key}-${name}`,
    storage: new InMemoryStorage(),
    eventBus: new SerialTypedEventBus<never>(`${key}-${name}`),
  });
  const tokens = new TokenStorage(options('tokens'));
  const devices = new DeviceIdStorage(options('devices'));
  destroyables.push(tokens, devices);
  const client = new Fetcher({ baseURL });
  new CoSecConfigurer({
    tokenStorage: tokens,
    deviceIdStorage: devices,
    tokenRefresher: new CoSecTokenRefresher({
      fetcher: client,
      endpoint: '/auth/refresh',
    }),
    ...config,
  }).applyTo(client);
  return { client, tokens, devices };
}

/** Signs `username` in at the host, as the application's own login does: the pair CoSec issued. */
export async function login(username: string): Promise<CompositeToken> {
  const response = await fetch(`${hostUrl()}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username }),
  });
  if (!response.ok) throw new Error(`login answered ${response.status}`);
  return (await response.json()) as CompositeToken;
}

export interface HostStats {
  logins: number;
  refreshes: number;
}

/** What reached the host since the last {@link resetStats}. */
export async function stats(): Promise<HostStats> {
  return (await (await fetch(`${hostUrl()}/test/stats`)).json()) as HostStats;
}

export async function resetStats(): Promise<void> {
  await fetch(`${hostUrl()}/test/reset`, { method: 'POST' });
}

/** Ends the session of `pair` at the host, as a revocation or a server-side sign-out would. */
export async function endSession(pair: CompositeToken): Promise<void> {
  const response = await fetch(`${hostUrl()}/test/end-session`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(pair),
  });
  if (!response.ok) throw new Error(`end-session answered ${response.status}`);
}

/** Waits until the access token of `pair` has expired at the host (they live `ACCESS_VALIDITY`, 2 seconds, test/client-host). */
export async function waitForExpiry(pair: CompositeToken): Promise<void> {
  const [, payload] = pair.accessToken.split('.');
  const { exp } = JSON.parse(
    atob(payload.replaceAll('-', '+').replaceAll('_', '/')),
  ) as { exp: number };
  const wait = exp * 1000 - Date.now() + 1500;
  if (wait > 0) await new Promise(done => setTimeout(done, wait));
}
