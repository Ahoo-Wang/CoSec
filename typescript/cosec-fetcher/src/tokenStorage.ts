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

import { JwtCompositeToken, JwtCompositeTokenSerializer } from './jwtToken.js';
import type { CompositeToken } from './tokenRefresher.js';
import type { CoSecJwtPayload, EarlyPeriodCapable } from './jwts.js';
import type { KeyStorageOptions } from '@ahoo-wang/fetcher-storage';
import { KeyStorage } from '@ahoo-wang/fetcher-storage';
import { isSharedAcrossTabs, storageEventBus } from './storageEventBus.js';
import { setRefreshLockName } from './refreshLock.js';
import { clearAttempt } from './refreshAttempt.js';

/**
 * Default key used for storing CoSec tokens in storage.
 */
export const DEFAULT_COSEC_TOKEN_KEY = 'cosec-token';

const SHARED_TOKEN_SERIALIZERS = Symbol.for(
  '@ahoo-wang/fetcher-cosec/shared-token-serializers',
);
const sharedState = globalThis as typeof globalThis & {
  [SHARED_TOKEN_SERIALIZERS]?: WeakMap<object, JwtCompositeTokenSerializer>;
};
const sharedTokenSerializers =
  sharedState[SHARED_TOKEN_SERIALIZERS] ??
  new WeakMap<object, JwtCompositeTokenSerializer>();
if (!sharedState[SHARED_TOKEN_SERIALIZERS]) {
  Object.defineProperty(sharedState, SHARED_TOKEN_SERIALIZERS, {
    value: sharedTokenSerializers,
  });
}

/**
 * Options for configuring TokenStorage.
 * Extends KeyStorageOptions excluding 'serializer' and includes EarlyPeriodCapable properties.
 */
export interface TokenStorageOptions
  extends
    Partial<Omit<KeyStorageOptions<JwtCompositeToken>, 'serializer'>>,
    Partial<EarlyPeriodCapable> {
  /**
   * Whether every tab (or process) of the session reads and writes this very
   * storage, so that they must take turns to refresh: a refresh runs under a
   * Web Lock, the tab that waited reads the pair the other stored instead of
   * refreshing it again, and changes are broadcast to the other tabs.
   *
   * Defaults to whether the storage is the browser's `localStorage`. Declare
   * `true` for another storage that tabs share (an IndexedDB or cookie backed
   * `Storage`). **Declare it only when it is true**: two holders of one pair
   * that each refresh it end the session (the next refresh of one is a pair
   * "presented again", IDN-16). `sessionStorage` is not shared (a duplicated
   * tab starts with a copy of the pair), and neither is a storage held in
   * memory by each tab; with those, refresh in one place only. Without
   * `navigator.locks` (an insecure context) tabs cannot take turns either.
   */
  sharedAcrossTabs?: boolean;
}

/**
 * Storage class for managing access and refresh tokens in CoSec authentication.
 * Provides methods to store, retrieve, and manage JWT composite tokens with early period support.
 * Extends KeyStorage to handle persistence and implements EarlyPeriodCapable for token refresh timing.
 */
export class TokenStorage
  extends KeyStorage<JwtCompositeToken>
  implements EarlyPeriodCapable
{
  /**
   * The early period in seconds for token refresh timing.
   */
  public readonly earlyPeriod: number;

  /**
   * Creates a new TokenStorage instance.
   * @param options - Configuration options for the token storage.
   * @param options.key - The storage key for tokens. Defaults to DEFAULT_COSEC_TOKEN_KEY.
   * @param options.eventBus - Event bus for token change notifications. Defaults to a SerialTypedEventBus named for the storage key, broadcast across tabs (BroadcastTypedEventBus) only when the storage is the browser's localStorage.
   * @param options.earlyPeriod - Early period for token refresh in seconds. Defaults to 0. Instances sharing an eventBus must use the same period; separate buses on the same channel can use different periods.
   * @param reset - Additional options passed to KeyStorage.
   */
  constructor({
    key = DEFAULT_COSEC_TOKEN_KEY,
    eventBus,
    earlyPeriod = 0,
    sharedAcrossTabs,
    ...reset
  }: TokenStorageOptions = {}) {
    const shared = sharedAcrossTabs ?? isSharedAcrossTabs(reset.storage);
    let serializer = eventBus
      ? sharedTokenSerializers.get(eventBus)
      : undefined;
    if (serializer && serializer.earlyPeriod !== earlyPeriod) {
      throw new Error(
        'TokenStorage instances sharing an event bus require the same earlyPeriod; use separate buses for different periods.',
      );
    }
    serializer ??= new JwtCompositeTokenSerializer(earlyPeriod);
    super({
      key,
      eventBus: storageEventBus(key, eventBus, reset.storage, shared),
      ...reset,
      serializer,
    });
    sharedTokenSerializers.set(this.eventBus, serializer);
    if (!eventBus) this.ownEventBus();
    this.earlyPeriod = earlyPeriod;
    // Only tabs sharing a storage race on a refresh. A server process holds
    // many users' storages under the same key; a process-wide lock there
    // would queue every user's refresh behind one.
    if (shared) setRefreshLockName(this, key);
  }

  /**
   * Sets a composite token in storage.
   * Converts the composite token to a JwtCompositeToken and stores it.
   * @deprecated Use signIn() instead for better semantic clarity.
   * @param compositeToken - The composite token containing access and refresh tokens.
   */
  setCompositeToken(compositeToken: CompositeToken) {
    this.signIn(compositeToken);
  }

  /**
   * Signs in by storing the composite token.
   * @param compositeToken - The composite token to store for authentication.
   */
  signIn(compositeToken: CompositeToken): void {
    this.set(new JwtCompositeToken(compositeToken, this.earlyPeriod));
  }

  /**
   * Signs out by removing the stored token.
   * Clears the token from storage.
   */
  signOut(): void {
    this.remove();
  }

  /** Removes the token and the record of a refresh attempt that went with it. */
  override remove(): void {
    super.remove();
    clearAttempt(this);
  }

  /**
   * Checks if the user is authenticated.
   * @returns true if a valid token is present and authenticated, false otherwise.
   */
  get authenticated(): boolean {
    return this.get()?.authenticated === true;
  }

  /**
   * Gets the current user's JWT payload.
   * @returns The JWT payload of the current user if authenticated, null otherwise.
   */
  get currentUser(): CoSecJwtPayload | null {
    if (!this.authenticated) {
      return null;
    }
    return this.get()?.access.payload ?? null;
  }
}
