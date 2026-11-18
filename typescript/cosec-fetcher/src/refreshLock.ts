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

interface RefreshLocks {
  request<T>(name: string, callback: () => Promise<T>): Promise<T>;
}

const lockNames = new WeakMap<object, string>();

/** Names the cross-tab refresh lock of a token storage after its key. */
export function setRefreshLockName(tokenStorage: object, key: string): void {
  lockNames.set(tokenStorage, `cosec-refresh:${key}`);
}

/**
 * Runs `refresh` under the Web Lock of `tokenStorage`, so that tabs sharing
 * that storage refresh one after another: a one-time refresh token is then
 * never spent by two tabs at once. `refresh` learns whether it holds the
 * lock; without Web Locks (or for a storage without a lock name) it runs at
 * once, unlocked.
 */
export function withRefreshLock<T>(
  tokenStorage: object,
  refresh: (locked: boolean) => Promise<T>,
): Promise<T> {
  const locks = (
    globalThis.navigator as { locks?: Partial<RefreshLocks> } | undefined
  )?.locks;
  const name = lockNames.get(tokenStorage);
  if (!name || typeof locks?.request !== 'function') return refresh(false);
  return (locks as RefreshLocks).request(name, () => refresh(true));
}
