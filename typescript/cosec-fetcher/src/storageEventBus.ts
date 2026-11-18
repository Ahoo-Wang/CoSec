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

import type { StorageEvent } from '@ahoo-wang/fetcher-storage';
import {
  BroadcastTypedEventBus,
  SerialTypedEventBus,
  type TypedEventBus,
} from '@ahoo-wang/fetcher-eventbus';

/**
 * Whether a storage backend is the browser's `localStorage`, the one store
 * every tab of an origin shares. `undefined` stands for the default backend,
 * which is `localStorage` in a browser and a new `InMemoryStorage` elsewhere.
 */
export function isSharedAcrossTabs(storage: Storage | undefined): boolean {
  if (typeof window === 'undefined') return false;
  try {
    return (storage ?? window.localStorage) === window.localStorage;
  } catch {
    // localStorage is unavailable (e.g. a sandboxed frame): nothing is shared.
    return false;
  }
}

/**
 * The event bus a CoSec storage uses: the given one, or a new bus of its own.
 *
 * The new bus is a cross-tab bus (a `BroadcastChannel` named after the
 * storage key, so storages for different keys never cross-talk) only when
 * the storage keeps its value in the browser's `localStorage`, the one
 * backend other tabs read too. Any other backend — the `InMemoryStorage` a
 * server process gets for each request, `sessionStorage`, a custom store —
 * is private to this storage, so its changes stay in this storage instead of
 * reaching every other storage with the same key in the process. A storage
 * that gets a new bus owns it (`ownEventBus()`), and closes it on destroy.
 */
export function storageEventBus<T>(
  key: string,
  eventBus: TypedEventBus<StorageEvent<T>> | undefined,
  storage: Storage | undefined,
  shared: boolean = isSharedAcrossTabs(storage),
): TypedEventBus<StorageEvent<T>> {
  if (eventBus) return eventBus;
  const delegate = new SerialTypedEventBus<StorageEvent<T>>(key);
  return shared ? new BroadcastTypedEventBus({ delegate }) : delegate;
}
