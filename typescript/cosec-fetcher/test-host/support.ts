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
import { vi } from 'vitest';
import { SerialTypedEventBus } from '@ahoo-wang/fetcher-eventbus';
import { InMemoryStorage } from '@ahoo-wang/fetcher-storage';
import {
  CoSecConfigurer,
  CoSecTokenRefresher,
  DeviceIdStorage,
  TokenStorage,
  type CoSecConfig,
} from '../src';
import { hostUrl } from '../../test-host/hostApi';

export * from '../../test-host/hostApi';

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

/**
 * Records `<path> <status>` of every request the client puts on the wire, in order: which path a case took is read from
 * here, not from timing. Call it after `vi.stubGlobal`s of your own, and before the first request.
 */
export function recordWire(): string[] {
  const wire: string[] = [];
  const send = globalThis.fetch;
  vi.stubGlobal('fetch', async (...args: Parameters<typeof fetch>) => {
    const response = await send(...args);
    wire.push(`${new URL(String(args[0])).pathname} ${response.status}`);
    return response;
  });
  return wire;
}
