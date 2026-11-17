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

import type {
  FetchExchange,
  RequestHeaders,
  RequestInterceptor,
} from '@ahoo-wang/fetcher';
import {
  DEFAULT_INTERCEPTOR_ORDER_STEP,
  deleteHeader,
  getHeader,
  setHeader,
} from '@ahoo-wang/fetcher';
import {
  COSEC_REQUEST_INTERCEPTOR_ORDER,
  CREDENTIALS_WITHHELD_ATTRIBUTE,
  IGNORE_REFRESH_TOKEN_ATTRIBUTE_KEY,
  SPACE_ID_ATTRIBUTE,
  TOKEN_SESSION_ATTRIBUTE,
} from './constants.js';
import type { JwtTokenManagerCapable } from './types.js';
import { isTrustedRequest, type RequestTrustCapable } from './requestTrust.js';
import { CoSecHeaders } from './types.js';
import { assertTokenSession } from './refreshSession.js';

export interface AuthorizationInterceptorOptions
  extends JwtTokenManagerCapable, RequestTrustCapable {}

export const AUTHORIZATION_REQUEST_INTERCEPTOR_NAME =
  'AuthorizationRequestInterceptor';
export const AUTHORIZATION_REQUEST_INTERCEPTOR_ORDER =
  COSEC_REQUEST_INTERCEPTOR_ORDER + DEFAULT_INTERCEPTOR_ORDER_STEP;

/**
 * Request interceptor that automatically adds Authorization header to requests.
 *
 * This interceptor handles JWT token management by:
 * 1. Adding Authorization header with Bearer token if not already present
 * 2. Refreshing tokens when needed and possible
 * 3. Skipping refresh when explicitly requested via attributes
 *
 * The interceptor runs after CoSecRequestInterceptor but before FetchInterceptor in the chain.
 */
export class AuthorizationRequestInterceptor implements RequestInterceptor {
  readonly name = AUTHORIZATION_REQUEST_INTERCEPTOR_NAME;
  readonly order = AUTHORIZATION_REQUEST_INTERCEPTOR_ORDER;

  /**
   * Creates an AuthorizationRequestInterceptor instance.
   *
   * @param options - Configuration options containing the token manager
   */
  constructor(readonly options: AuthorizationInterceptorOptions) {}

  /**
   * Intercepts the request exchange to add authorization headers.
   *
   * This method performs the following operations:
   * 1. Checks if a token exists and if Authorization header is already set
   * 2. Refreshes the token if needed, possible, and not explicitly ignored
   * 3. Adds the Authorization header with Bearer token if a token is available
   *
   * @param exchange - The fetch exchange containing request information
   * @returns Promise that resolves when the interception is complete
   */
  async intercept(exchange: FetchExchange): Promise<void> {
    // The token goes only where the configuration trusts, never to an origin
    // just because a request URL happened to be absolute.
    if (!isTrustedRequest(exchange, this.options.isTrusted)) {
      exchange.attributes.set(CREDENTIALS_WITHHELD_ATTRIBUTE, true);
      return;
    }
    // Get the current token from token manager
    let currentToken = this.options.tokenManager.currentToken;
    assertTokenSession(exchange, currentToken);

    const requestHeaders = exchange.ensureRequestHeaders();

    // Caller-provided credentials remain outside the managed session.
    if (getHeader(requestHeaders, CoSecHeaders.AUTHORIZATION) !== undefined) {
      return;
    }
    if (!currentToken) {
      exchange.attributes.set(TOKEN_SESSION_ATTRIBUTE, null);
      dropSpace(exchange, requestHeaders);
      return;
    }

    // Refresh token if needed and refreshable
    if (
      !exchange.attributes.has(IGNORE_REFRESH_TOKEN_ATTRIBUTE_KEY) &&
      currentToken.isRefreshNeeded &&
      currentToken.isRefreshable
    ) {
      await this.options.tokenManager.refresh(exchange);
    }

    // Get the current token again (might have been refreshed)
    currentToken = this.options.tokenManager.currentToken;
    assertTokenSession(exchange, currentToken);

    // Add Authorization header if we have a token
    if (currentToken) {
      const authorization = `Bearer ${currentToken.access.token}`;
      setHeader(requestHeaders, CoSecHeaders.AUTHORIZATION, authorization);
      // Only this injected credential may be replaced during a 401 retry.
      exchange.attributes.set(this.name, authorization);
      exchange.attributes.set(TOKEN_SESSION_ATTRIBUTE, currentToken);
    }
  }
}

/**
 * An anonymous request that declares a space is refused 401 `unauthorized`
 * (SCP-3), so a request that has no token to send does not send the space it
 * was given: the answer would say "sign in" about a space, not about the
 * request. Only the header the CoSec interceptor set is removed.
 */
function dropSpace(
  exchange: FetchExchange,
  requestHeaders: RequestHeaders,
): void {
  const spaceId = exchange.attributes.get(SPACE_ID_ATTRIBUTE);
  if (
    typeof spaceId === 'string' &&
    getHeader(requestHeaders, CoSecHeaders.SPACE_ID) === spaceId
  ) {
    deleteHeader(requestHeaders, CoSecHeaders.SPACE_ID);
  }
}
