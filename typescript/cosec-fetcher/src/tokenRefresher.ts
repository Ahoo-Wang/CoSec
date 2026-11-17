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
import {
  IGNORE_REFRESH_TOKEN_ATTRIBUTE_KEY,
  UNAUTHORIZED_ERROR_INTERCEPTOR_NAME,
} from './constants.js';
import { NOTIFICATION_GUARD_REFRESHER } from './refreshProtocol.js';

/**
 * Interface representing an access token used for authentication.
 *
 * This interface defines the structure for access tokens, which are typically
 * short-lived tokens used to authenticate API requests.
 *
 * @interface AccessToken
 * @property {string} accessToken - The access token string used for authentication.
 */
export interface AccessToken {
  accessToken: string;
}

/**
 * Interface representing a refresh token used to obtain new access tokens.
 *
 * Refresh tokens are long-lived tokens that can be used to request new access
 * tokens without requiring the user to re-authenticate.
 *
 * @interface RefreshToken
 * @property {string} refreshToken - The refresh token string used to obtain new access tokens.
 */
export interface RefreshToken {
  refreshToken: string;
}

/**
 * Composite token interface that contains both access and refresh tokens.
 *
 * This interface combines AccessToken and RefreshToken, ensuring that both
 * tokens are always handled together. In authentication systems, access and
 * refresh tokens are typically issued and refreshed as a pair.
 *
 * @interface CompositeToken
 * @extends AccessToken
 * @extends RefreshToken
 * @property {string} accessToken - The access token string used for authentication.
 * @property {string} refreshToken - The refresh token string used to obtain new access tokens.
 */
export interface CompositeToken extends AccessToken, RefreshToken {}

/**
 * Interface for token refreshers.
 *
 * This interface defines the contract for classes responsible for refreshing
 * authentication tokens. Implementations should handle the logic of exchanging
 * an expired or soon-to-expire token for a new one.
 *
 * **CRITICAL**: When implementing the `refresh` method, always include the
 * `attributes: new Map([[IGNORE_REFRESH_TOKEN_ATTRIBUTE_KEY, true]])` in the
 * request options to prevent infinite loops. Without this attribute, the request
 * interceptor may attempt to refresh the token recursively, causing a deadlock.
 *
 * @interface TokenRefresher
 */
export interface TokenRefresher {
  /**
   * Refreshes the given composite token and returns a new one.
   *
   * This method should send the provided token to the appropriate endpoint
   * and return the refreshed token pair. **IMPORTANT**: The implementation must
   * include `attributes: new Map([[IGNORE_REFRESH_TOKEN_ATTRIBUTE_KEY, true]])`
   * in the request options to prevent recursive refresh attempts that could
   * cause infinite loops.
   *
   * @param {CompositeToken} token - The composite token to refresh, containing both access and refresh tokens.
   * @returns {Promise<CompositeToken>} A promise that resolves to a new CompositeToken with updated access and refresh tokens.
   * @throws {Error} Throws an error if the token refresh fails due to network issues, invalid tokens, or server errors.
   *
   * @example
   * ```typescript
   * const refresher: TokenRefresher = new CoSecTokenRefresher({ fetcher, endpoint: '/auth/refresh' });
   * const newToken = await refresher.refresh(currentToken);
   * console.log('New access token:', newToken.accessToken);
   * ```
   *
   * @warning **Critical**: Failing to set the `IGNORE_REFRESH_TOKEN_ATTRIBUTE_KEY`
   * attribute will result in infinite loops during token refresh operations.
   */
  refresh(token: CompositeToken): Promise<CompositeToken>;
}

/**
 * Configuration options for the CoSecTokenRefresher class.
 *
 * This interface defines the required options to initialize a CoSecTokenRefresher
 * instance, including the HTTP client and the refresh endpoint URL.
 *
 * @interface CoSecTokenRefresherOptions
 * @property {Fetcher} fetcher - The HTTP client instance used to make requests to the refresh endpoint.
 * @property {string} endpoint - The URL endpoint where token refresh requests are sent.
 */
/**
 * The longest, in milliseconds, a refresh request may take unless the options
 * say otherwise. CoSec answers a refresh presented again only within its
 * `rotation-leeway` (30 seconds by default) of the first one, so a refresh
 * that hangs for longer than that cannot be retried safely; it is cut short.
 */
export const DEFAULT_REFRESH_TIMEOUT = 10_000;

export interface CoSecTokenRefresherOptions {
  fetcher: Fetcher;
  endpoint: string;
  /**
   * Timeout of the refresh request in milliseconds ({@link DEFAULT_REFRESH_TIMEOUT}
   * by default; `0` for none). Keep it well below the server's
   * `cosec.jwt.session.rotation-leeway`.
   */
  timeout?: number;
}

/**
 * Implementation of TokenRefresher for refreshing composite tokens using CoSec authentication.
 *
 * This class provides a concrete implementation of the TokenRefresher interface,
 * using the provided Fetcher instance to send POST requests to a specified endpoint
 * for token refresh operations. It is designed to work with CoSec (Corporate Security)
 * authentication systems.
 *
 * @class CoSecTokenRefresher
 * @implements TokenRefresher
 *
 * @example
 * ```typescript
 * import { Fetcher } from '@ahoo-wang/fetcher';
 * import { CoSecTokenRefresher } from './tokenRefresher';
 *
 * const fetcher = new Fetcher();
 * const refresher = new CoSecTokenRefresher({
 *   fetcher,
 *   endpoint: 'https://api.example.com/auth/refresh'
 * });
 *
 * const currentToken = { accessToken: 'old-token', refreshToken: 'refresh-token' };
 * const newToken = await refresher.refresh(currentToken);
 * ```
 */
export class CoSecTokenRefresher implements TokenRefresher {
  /**
   * Creates a new instance of CoSecTokenRefresher.
   *
   * @param {CoSecTokenRefresherOptions} options - The configuration options for the token refresher.
   * @param {Fetcher} options.fetcher - The HTTP client instance used for making refresh requests.
   * @param {string} options.endpoint - The URL endpoint for token refresh operations.
   *
   * @example
   * ```typescript
   * const refresher = new CoSecTokenRefresher({
   *   fetcher: myFetcherInstance,
   *   endpoint: '/api/v1/auth/refresh'
   * });
   * ```
   */
  constructor(public readonly options: CoSecTokenRefresherOptions) {}

  /**
   * Refreshes the given composite token by sending a POST request to the configured endpoint.
   *
   * This method sends the current token pair to the refresh endpoint and expects
   * a new CompositeToken in response.
   *
   * **CRITICAL**: The request includes a special
   * attribute `attributes: new Map([[IGNORE_REFRESH_TOKEN_ATTRIBUTE_KEY, true]])`
   * to prevent infinite loops. Without this attribute, the request interceptor
   * may attempt to refresh the token again, causing a recursive loop.
   *
   * @param {CompositeToken} token - The composite token to refresh, containing both access and refresh tokens.
   * @returns {Promise<CompositeToken>} A promise that resolves to a new CompositeToken with refreshed tokens.
   * @throws {Error} Throws an error if the HTTP request fails, the server returns an error status, or the response cannot be parsed as JSON.
   * @throws {NetworkError} Throws a network error if there are connectivity issues.
   * @throws {AuthenticationError} Throws an authentication error if the refresh token is invalid or expired.
   *
   * @example
   * ```typescript
   * const refresher = new CoSecTokenRefresher({ fetcher, endpoint: '/auth/refresh' });
   * const token = { accessToken: 'expired-token', refreshToken: 'valid-refresh-token' };
   *
   * try {
   *   const newToken = await refresher.refresh(token);
   *   console.log('Refreshed access token:', newToken.accessToken);
   * } catch (error) {
   *   console.error('Token refresh failed:', error.message);
   * }
   * ```
   *
   * @warning **Important**: Always include the `IGNORE_REFRESH_TOKEN_ATTRIBUTE_KEY` attribute
   * in refresh requests to avoid infinite loops caused by recursive token refresh attempts.
   * @param shouldNotifyUnauthorized Optional manager guard, evaluated when a refresh error reaches the unauthorized interceptor
   */
  refresh(
    token: CompositeToken,
    shouldNotifyUnauthorized?: () => boolean,
  ): Promise<CompositeToken> {
    // Send a POST request to the configured endpoint with the token as body
    // and extract the response as JSON to return a new CompositeToken

    const attributes = new Map<string, unknown>([
      [IGNORE_REFRESH_TOKEN_ATTRIBUTE_KEY, true],
    ]);
    if (shouldNotifyUnauthorized) {
      attributes.set(
        UNAUTHORIZED_ERROR_INTERCEPTOR_NAME,
        shouldNotifyUnauthorized,
      );
    }
    return this.options.fetcher.post<CompositeToken>(
      this.options.endpoint,
      {
        body: token,
        timeout: this.options.timeout ?? DEFAULT_REFRESH_TIMEOUT,
      },
      {
        resultExtractor: ResultExtractors.Json,
        attributes,
      },
    );
  }
}

// Kept off the public type: JwtTokenManager checks the registered symbol, not
// the class identity, so it recognizes any copy of this package's refresher.
Object.defineProperty(
  CoSecTokenRefresher.prototype,
  NOTIFICATION_GUARD_REFRESHER,
  {
    value: true,
  },
);
