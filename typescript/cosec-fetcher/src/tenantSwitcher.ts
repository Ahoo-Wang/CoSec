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
import { ResultExtractors } from '@ahoo-wang/fetcher';
import { pairOf } from './compositeToken.js';
import { sessionRequestAttributes } from './sessionRequest.js';
import {
  DEFAULT_REFRESH_TIMEOUT,
  type CompositeToken,
} from './tokenRefresher.js';
import { CoSecHeaders } from './types.js';

/**
 * One tenant or app switch: the application's switch route
 * (`SwitchTenantAuthentication` behind a route of its own), which names the
 * target as that route reads it, in its path or in `body`.
 */
export interface TenantSwitchRequest {
  /** The application's switch route, resolved by the switcher's `Fetcher` (e.g. `/auth/select/acme/orders`). */
  readonly endpoint: string;
  /**
   * Members of the application's own credentials besides the pair. The body
   * always carries `accessToken` and `refreshToken` of the pair the switch
   * presents, whatever this holds.
   */
  readonly body?: Readonly<Record<string, unknown>>;
}

export interface CoSecTenantSwitcherOptions {
  /**
   * The application's configured `Fetcher` (the one {@link CoSecConfigurer}
   * applies to): the switch is a request the pipeline permits, and it carries
   * the `CoSec-Device-Id` a `TenantSwitch` may check. Never a space.
   */
  fetcher: Fetcher;
  /**
   * Timeout of the switch request in milliseconds
   * ({@link DEFAULT_REFRESH_TIMEOUT} by default; `0` for none).
   */
  timeout?: number;
}

/**
 * Sends a tenant switch with the session's token pair (`IDN-22`): the pair in
 * the body and its access token in `Authorization`, so the two are always one
 * pair. {@link JwtTokenManager.switchTenant} calls it, under the session's
 * lock, with the pair of the current generation; give it to the manager with
 * `CoSecConfig.tenantSwitcher`.
 */
export class CoSecTenantSwitcher {
  constructor(public readonly options: CoSecTenantSwitcherOptions) {}

  /**
   * Posts the switch for `token`; resolves with the pair the route answered.
   *
   * @internal
   */
  switch(
    token: CompositeToken,
    request: TenantSwitchRequest,
    shouldNotifyUnauthorized?: () => boolean,
  ): Promise<CompositeToken> {
    const pair = pairOf(token);
    return this.options.fetcher.post<CompositeToken>(
      request.endpoint,
      {
        headers: { [CoSecHeaders.AUTHORIZATION]: `Bearer ${pair.accessToken}` },
        body: { ...request.body, ...pair },
        timeout: this.options.timeout ?? DEFAULT_REFRESH_TIMEOUT,
      },
      {
        resultExtractor: ResultExtractors.Json,
        attributes: sessionRequestAttributes(shouldNotifyUnauthorized),
      },
    );
  }
}
