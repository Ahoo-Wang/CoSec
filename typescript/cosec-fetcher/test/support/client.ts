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
  DefaultSpaceIdProvider,
  DeviceIdStorage,
  SpaceIdStorage,
  TokenStorage,
  type CoSecConfig,
} from '../../src';
import type { FakeCoSecHost, FakeToken } from './fakeCoSec';

const destroyables: { destroy(): void }[] = [];

/** Destroy what {@link createClient} made; call it from `afterEach`. */
export function destroyClients(): void {
  destroyables.splice(0).forEach(value => value.destroy());
}

let counter = 0;

export interface TestClient {
  client: Fetcher;
  tokens: TokenStorage;
  spaces: SpaceIdStorage;
  configurer: CoSecConfigurer;
}

/**
 * A Fetcher over a {@link FakeCoSecHost}, configured as an application would:
 * one fetcher for requests and the refresh (the refresh marks its own requests),
 * every request below `/me` or `/denied`... "spaced" by default.
 */
export function createClient(
  host: FakeCoSecHost,
  {
    signedIn,
    spaced = () => true,
    tokens: sharedTokens,
    config = {},
  }: {
    signedIn?: FakeToken;
    spaced?: (url: string) => boolean;
    tokens?: TokenStorage;
    config?: Partial<CoSecConfig>;
  } = {},
): TestClient {
  const key = `test-${++counter}`;
  const options = (name: string) => ({
    key: `${key}-${name}`,
    storage: new InMemoryStorage(),
    eventBus: new SerialTypedEventBus<never>(`${key}-${name}`),
  });
  const tokens = sharedTokens ?? new TokenStorage(options('tokens'));
  const spaces = new SpaceIdStorage(options('spaces'));
  const devices = new DeviceIdStorage(options('devices'));
  destroyables.push(tokens, spaces, devices);
  const client = new Fetcher({
    baseURL: 'https://api.test',
    fetch: host.fetch,
  });
  if (signedIn) tokens.signIn(signedIn);
  const configurer = new CoSecConfigurer({
    tokenStorage: tokens,
    deviceIdStorage: devices,
    spaceIdProvider: new DefaultSpaceIdProvider({
      spacedResourcePredicate: { test: ex => spaced(ex.request.url) },
      spaceIdStorage: spaces,
    }),
    refreshRetryDelays: [0, 0],
    tokenRefresher: new CoSecTokenRefresher({
      fetcher: client,
      endpoint: '/auth/refresh',
    }),
    ...config,
  });
  configurer.applyTo(client);
  return { client, tokens, spaces, configurer };
}
