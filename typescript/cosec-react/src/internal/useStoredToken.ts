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

import { useCallback, useSyncExternalStore } from 'react';
import type { JwtCompositeToken, TokenStorage } from '@ahoo-wang/cosec-fetcher';

let listeners = 0;

const NO_TOKEN = (): null => null;

/**
 * The token pair a `TokenStorage` holds, as React state: `null` while there is
 * none.
 *
 * The snapshot is the stored value itself (`TokenStorage.get()` answers the
 * same object until the pair changes), so a render in which nothing changed
 * does not re-render. The server has no browser storage and renders `null`:
 * hydration starts from the same value, and the stored pair follows right
 * after.
 *
 * Internal: not exported from the package.
 */
export function useStoredToken(
  tokenStorage: TokenStorage,
): JwtCompositeToken | null {
  const subscribe = useCallback(
    (onChange: () => void) =>
      tokenStorage.addListener({
        name: `cosec-react:useStoredToken:${listeners++}`,
        handle: onChange,
      }),
    [tokenStorage],
  );
  const getSnapshot = useCallback(() => tokenStorage.get(), [tokenStorage]);
  return useSyncExternalStore(subscribe, getSnapshot, NO_TOKEN);
}
