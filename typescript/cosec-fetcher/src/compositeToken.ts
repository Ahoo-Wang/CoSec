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

import type { CompositeToken } from './tokenRefresher.js';

/**
 * Whether a value has the runtime shape of a {@link CompositeToken}: a
 * non-array object whose `accessToken` and `refreshToken` are strings. Used
 * on everything read from outside the type system (a stored record, a
 * refresh response), so a malformed one is rejected instead of becoming a
 * `Bearer undefined` credential.
 */
export function isCompositeToken(token: unknown): token is CompositeToken {
  return (
    typeof token === 'object' &&
    token !== null &&
    !Array.isArray(token) &&
    typeof (token as CompositeToken).accessToken === 'string' &&
    typeof (token as CompositeToken).refreshToken === 'string'
  );
}
