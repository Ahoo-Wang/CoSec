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

import {
  IGNORE_REFRESH_TOKEN_ATTRIBUTE_KEY,
  UNAUTHORIZED_ERROR_INTERCEPTOR_NAME,
} from './constants.js';

/**
 * The attributes of a request that carries the token pair itself (a refresh, a
 * logout): it never starts a refresh of its own, is never spaced, and its
 * `401` notifies the unauthorized interceptor only when `shouldNotify` says
 * so. Shared by {@link CoSecTokenRefresher} and {@link CoSecTokenRevoker}, not
 * exported by the package.
 */
export function sessionRequestAttributes(
  shouldNotify?: () => boolean,
): Map<string, unknown> {
  const attributes = new Map<string, unknown>([
    [IGNORE_REFRESH_TOKEN_ATTRIBUTE_KEY, true],
  ]);
  if (shouldNotify) {
    attributes.set(UNAUTHORIZED_ERROR_INTERCEPTOR_NAME, shouldNotify);
  }
  return attributes;
}
