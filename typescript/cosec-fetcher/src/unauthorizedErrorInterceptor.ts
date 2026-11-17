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
import type { ErrorInterceptor, FetchExchange } from '@ahoo-wang/fetcher';
import { ResponseCodes } from './types.js';
import {
  getCoSecProblem,
  resolveCoSecProblem,
  type CoSecProblem,
} from './problem.js';
import {
  NonReplayableRequestError,
  RefreshSessionChangedError,
  RefreshTokenError,
  RefreshUnavailableError,
} from './errors.js';
import {
  CREDENTIALS_WITHHELD_ATTRIBUTE,
  UNAUTHORIZED_ERROR_INTERCEPTOR_NAME,
} from './constants.js';

export { UNAUTHORIZED_ERROR_INTERCEPTOR_NAME } from './constants.js';

/**
 * The execution order for the UnauthorizedErrorInterceptor.
 * Set to 0, indicating it runs at default priority in the interceptor chain.
 */
export const UNAUTHORIZED_ERROR_INTERCEPTOR_ORDER = 0;

/**
 * Configuration options for the UnauthorizedErrorInterceptor.
 */
export interface UnauthorizedErrorInterceptorOptions {
  /**
   * Callback function invoked when an unauthorized (401) response is detected.
   * This allows custom handling of authentication failures, such as redirecting to login
   * or triggering token refresh mechanisms.
   *
   * @param exchange - The fetch exchange containing the request and response details
   *                   that resulted in the unauthorized error
   */
  onUnauthorized: (
    exchange: FetchExchange,
    problem?: CoSecProblem,
  ) => Promise<void> | void;
}

/**
 * An error interceptor that handles HTTP 401 Unauthorized responses by invoking a custom callback.
 *
 * This interceptor is designed to provide centralized handling of authentication failures
 * across all HTTP requests. When a response with status code 401 is encountered, it calls
 * the configured `onUnauthorized` callback, allowing applications to implement custom
 * authentication recovery logic such as:
 * - Redirecting users to login pages
 * - Triggering token refresh flows
 * - Clearing stored authentication state
 * - Displaying authentication error messages
 *
 * The interceptor does not modify the response or retry requests automatically - it delegates
 * all handling to the provided callback function.
 *
 * @example
 * ```typescript
 * const interceptor = new UnauthorizedErrorInterceptor({
 *   onUnauthorized: (exchange) => {
 *     console.log('Unauthorized access detected for:', exchange.request.url);
 *     // Redirect to login page or refresh token
 *     window.location.href = '/login';
 *   }
 * });
 *
 * fetcher.interceptors.error.use(interceptor);
 * ```
 */
export class UnauthorizedErrorInterceptor implements ErrorInterceptor {
  /**
   * The unique name identifier for this interceptor instance.
   */
  readonly name = UNAUTHORIZED_ERROR_INTERCEPTOR_NAME;

  /**
   * The execution order priority for this interceptor in the chain.
   */
  readonly order = UNAUTHORIZED_ERROR_INTERCEPTOR_ORDER;

  /**
   * Creates a new UnauthorizedErrorInterceptor instance.
   *
   * @param options - Configuration options containing the callback to handle unauthorized responses
   */
  constructor(public options: UnauthorizedErrorInterceptorOptions) {}

  /**
   * Intercepts fetch exchanges to detect and handle unauthorized (401) responses
   * and RefreshTokenError exceptions.
   *
   * This method checks if the response status is 401 (Unauthorized) or if the exchange
   * contains an error of type `RefreshTokenError`. If either condition is met, it invokes
   * the configured `onUnauthorized` callback with the exchange details. The method
   * does not return a value or throw exceptions - all error handling is delegated
   * to the callback function.
   *
   * @param exchange - The fetch exchange containing request, response, and error information
   *                   to be inspected for unauthorized status codes or refresh token errors
   * @returns {void} This method does not return a value
   *
   * @example
   * ```typescript
   * const interceptor = new UnauthorizedErrorInterceptor({
   *   onUnauthorized: (exchange) => {
   *      // Custom logic here
   *   }
   * });
   * ```
   */
  intercept(exchange: FetchExchange): void | Promise<void> {
    const notification = exchange.attributes?.get(this.name);
    if (
      exchange.error instanceof RefreshSessionChangedError ||
      // A refresh that could not reach the server keeps the session.
      exchange.error instanceof RefreshUnavailableError ||
      exchange.error instanceof NonReplayableRequestError ||
      exchange.attributes?.get(CREDENTIALS_WITHHELD_ATTRIBUTE) === true ||
      notification === true
    ) {
      return;
    }
    if (
      exchange.response?.status === ResponseCodes.UNAUTHORIZED ||
      exchange.error instanceof RefreshTokenError
    ) {
      if (typeof notification === 'function' && !notification()) return;
      exchange.attributes?.set(this.name, true);
      // Why the user must sign in: the refresh that was refused, or the
      // response that was. `token-expired` after a refresh the server refused
      // is the session's end (or a refresh route closed to anonymous callers).
      // The callback runs at once unless a response body has to be read, and
      // a handler written for 6.x sees only the exchange when there is no
      // problem.
      const { onUnauthorized } = this.options;
      const notify = (problem: CoSecProblem | undefined) =>
        problem ? onUnauthorized(exchange, problem) : onUnauthorized(exchange);
      const found =
        exchange.error instanceof RefreshTokenError
          ? getCoSecProblem(exchange.error)
          : resolveCoSecProblem(exchange);
      return found instanceof Promise ? found.then(notify) : notify(found);
    }
  }
}
