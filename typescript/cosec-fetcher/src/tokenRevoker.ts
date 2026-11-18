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

import type { Fetcher } from '@ahoo-wang/fetcher';
import { sessionRequestAttributes } from './sessionRequest.js';
import {
  DEFAULT_REFRESH_TIMEOUT,
  type CompositeToken,
} from './tokenRefresher.js';

/**
 * Ends a session at the server, as CoSec's `TokenRevoker` does there: what
 * {@link JwtTokenManager.logout} calls before it signs out here.
 */
export interface TokenRevoker {
  /**
   * Ends the session of `token` at the server. Resolves when the server ended
   * it, rejects otherwise with the error of the request.
   */
  logout(token: CompositeToken): Promise<void>;
}

/** Options of a {@link CoSecTokenRevoker}. */
export interface CoSecTokenRevokerOptions {
  /** The Fetcher the refresh goes through: the logout is marked as it is. */
  fetcher: Fetcher;
  /** CoSec's logout route, as `cosec.authentication.login.logout.path` sets it. */
  endpoint: string;
  /**
   * Timeout of the logout request in milliseconds
   * ({@link DEFAULT_REFRESH_TIMEOUT} by default; `0` for none).
   */
  timeout?: number;
}

/**
 * The {@link TokenRevoker} of CoSec's logout route: `POST` of the pair to
 * `endpoint`, `204` when the session is ended. Its requests are marked as
 * {@link CoSecTokenRefresher}'s are: they never start a refresh, are never
 * spaced, and their `401` notifies no one ({@link JwtTokenManager.logout}
 * signs out here then).
 *
 * @example
 * ```typescript
 * const cosec = new CoSecConfigurer({
 *   tokenRefresher: new CoSecTokenRefresher({ fetcher, endpoint: '/auth/refresh' }),
 *   tokenRevoker: new CoSecTokenRevoker({ fetcher, endpoint: '/auth/logout' }),
 * });
 * ```
 */
export class CoSecTokenRevoker implements TokenRevoker {
  /** @param options - The Fetcher and the logout route. */
  constructor(public readonly options: CoSecTokenRevokerOptions) {}

  /** Posts `token` to the logout route; resolves on `204`. */
  async logout(token: CompositeToken): Promise<void> {
    await this.options.fetcher.post(
      this.options.endpoint,
      {
        body: token,
        timeout: this.options.timeout ?? DEFAULT_REFRESH_TIMEOUT,
      },
      { attributes: sessionRequestAttributes(() => false) },
    );
  }
}
