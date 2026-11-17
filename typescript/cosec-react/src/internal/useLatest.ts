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

import type { RefObject } from 'react';
import { useInsertionEffect, useRef } from 'react';

/**
 * A ref holding the latest value, for callbacks that outlive a render
 * (an async `signIn`, a listener).
 *
 * It is updated once the render commits, not during it: a render React
 * discards (a concurrent or suspended one) must not leave its props in the
 * ref. Insertion effects run before every layout effect and effect, so those
 * already read the new value.
 *
 * Internal: not exported from the package.
 */
export function useLatest<T>(value: T): RefObject<T> {
  const ref = useRef(value);
  useInsertionEffect(() => {
    ref.current = value;
  });
  return ref;
}
