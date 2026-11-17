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
  CoSecProblemType,
  resolveCoSecProblem,
  type CoSecProblem,
} from './problem.js';

/**
 * The name identifier for the ForbiddenErrorInterceptor.
 * Used for interceptor registration and identification in the interceptor chain.
 */
export const FORBIDDEN_ERROR_INTERCEPTOR_NAME = 'ForbiddenErrorInterceptor';

/**
 * The execution order for the ForbiddenErrorInterceptor.
 * Set to 0, indicating it runs at default priority in the interceptor chain.
 */
export const FORBIDDEN_ERROR_INTERCEPTOR_ORDER = 0;

/**
 * Configuration options for the ForbiddenErrorInterceptor.
 */
export interface ForbiddenErrorInterceptorOptions {
  /**
   * Callback function invoked when a forbidden (403) response is detected.
   * This allows custom handling of authorization failures, such as displaying
   * permission error messages, redirecting to appropriate pages, or triggering
   * privilege escalation flows.
   *
   * @param exchange - The fetch exchange containing the request and response details
   *                   that resulted in the forbidden error
   * @returns Promise that resolves when the forbidden error handling is complete
   *
   * @example
   * ```typescript
   * const options: ForbiddenErrorInterceptorOptions = {
   *   onForbidden: async (exchange) => {
   *     console.log('Access forbidden for:', exchange.request.url);
   *     // Show permission error or redirect
   *     showPermissionError('You do not have permission to access this resource');
   *   }
   * };
   * ```
   */
  onForbidden?: (
    exchange: FetchExchange,
    problem?: CoSecProblem,
  ) => Promise<void>;

  /**
   * Callback invoked for a `403 scope-rejected`: the app of the request is
   * not the host's (the token's `aud`), or the `CoSec-Space-Id` it sent
   * (`getHeader(exchange.request.headers, CoSecHeaders.SPACE_ID)`) is a space
   * the signed-in principal has no role in, or one beginning with `(`. The
   * remedy differs from a `forbidden`: another space, or a token for the
   * app. Retrying the same request is refused again.
   *
   * **The client does not drop the stored space or fall back to the default
   * space by itself**: that would silently move the user's next writes into
   * another space. The application decides.
   *
   * Without it, `onForbidden` receives a `scope-rejected` as well, with its
   * problem as the second argument.
   */
  onScopeRejected?: (
    exchange: FetchExchange,
    problem: CoSecProblem,
  ) => Promise<void>;
}

/**
 * An error interceptor that handles HTTP 403 Forbidden responses by invoking a custom callback.
 *
 * This interceptor is designed to provide centralized handling of authorization failures
 * across all HTTP requests. When a response with status code 403 is encountered, it calls
 * the configured `onForbidden` callback, allowing applications to implement custom
 * authorization recovery logic such as:
 * - Displaying permission error messages
 * - Redirecting users to access request pages
 * - Triggering privilege escalation workflows
 * - Logging security events
 * - Showing upgrade prompts for premium features
 *
 * The interceptor does not modify the response or retry requests automatically - it delegates
 * all handling to the provided callback function. This allows for flexible, application-specific
 * handling of forbidden access scenarios.
 *
 * @example
 * ```typescript
 * // Basic usage with error display
 * const interceptor = new ForbiddenErrorInterceptor({
 *   onForbidden: async (exchange) => {
 *     console.log('Forbidden access detected for:', exchange.request.url);
 *     showErrorToast('You do not have permission to access this resource');
 *   }
 * });
 *
 * fetcher.interceptors.error.use(interceptor);
 * ```
 *
 * @example
 * ```typescript
 * // Advanced usage with role-based handling
 * const interceptor = new ForbiddenErrorInterceptor({
 *   onForbidden: async (exchange) => {
 *     const userRole = getCurrentUserRole();
 *
 *     if (userRole === 'guest') {
 *       // Redirect to login for guests
 *       redirectToLogin(exchange.request.url);
 *     } else if (userRole === 'user') {
 *       // Show upgrade prompt for basic users
 *       showUpgradePrompt('Upgrade to premium for access to this feature');
 *     } else {
 *       // Log security event for authenticated users
 *       logSecurityEvent('Forbidden access attempt', {
 *         url: exchange.request.url,
 *         userId: getCurrentUserId(),
 *         timestamp: new Date().toISOString()
 *       });
 *       showErrorToast('Access denied due to insufficient permissions');
 *     }
 *   }
 * });
 * ```
 */
export class ForbiddenErrorInterceptor implements ErrorInterceptor {
  /**
   * The unique name identifier for this interceptor instance.
   * Used for registration, debugging, and interceptor chain management.
   */
  readonly name = FORBIDDEN_ERROR_INTERCEPTOR_NAME;

  /**
   * The execution order priority for this interceptor in the error interceptor chain.
   * Lower values execute earlier in the chain. Default priority (0) allows other
   * interceptors to run first if needed.
   */
  readonly order = FORBIDDEN_ERROR_INTERCEPTOR_ORDER;

  /**
   * Creates a new ForbiddenErrorInterceptor instance.
   *
   * @param options - Configuration options containing the callback to handle forbidden responses.
   *                  Must include the `onForbidden` callback function.
   *
   * @throws Will throw an error if options are not provided or if `onForbidden` callback is missing.
   *
   * @example
   * ```typescript
   * const interceptor = new ForbiddenErrorInterceptor({
   *   onForbidden: async (exchange) => {
   *     // Handle forbidden access
   *   }
   * });
   * ```
   */
  constructor(public options: ForbiddenErrorInterceptorOptions) {}

  /**
   * Intercepts fetch exchanges to detect and handle forbidden (403) responses.
   *
   * This method examines the response status code and invokes the configured `onForbidden`
   * callback when a 403 Forbidden response is detected. The method is asynchronous to
   * allow the callback to perform async operations like API calls, redirects, or UI updates.
   *
   * The interceptor only acts on responses with status code 403. Other error codes are
   * ignored and passed through to other error interceptors in the chain.
   *
   * @param exchange - The fetch exchange containing request, response, and error information
   *                   to be inspected for forbidden status codes. The exchange object provides
   *                   access to the original request, response details, and any error information.
   * @returns Promise that resolves when the forbidden error handling is complete.
   *          Returns void - the method does not modify the exchange or return values.
   *
   * @remarks
   * - Only responds to HTTP 403 status codes
   * - Does not retry requests or modify responses
   * - Allows async operations in the callback
   * - Does not throw exceptions - delegates all error handling to the callback
   * - Safe to use with other error interceptors
   *
   * @example
   * ```typescript
   * // The intercept method is called automatically by the fetcher
   * // No manual invocation needed - this is for documentation purposes
   * const interceptor = new ForbiddenErrorInterceptor({
   *   onForbidden: async (exchange) => {
   *     // exchange.response.status === 403
   *     // exchange.request contains original request details
   *     await handleForbiddenAccess(exchange);
   *   }
   * });
   * ```
   */
  async intercept(exchange: FetchExchange): Promise<void> {
    // Check if the response status indicates forbidden access (403)
    if (exchange.response?.status === ResponseCodes.FORBIDDEN) {
      const problem = await resolveCoSecProblem(exchange);
      const { onForbidden, onScopeRejected } = this.options;
      // `forbidden` and `scope-rejected` have different remedies; a 403 with
      // no CoSec body (another service, a proxy) is reported as before.
      if (
        problem?.slug === CoSecProblemType.SCOPE_REJECTED &&
        onScopeRejected
      ) {
        await onScopeRejected(exchange, problem);
        return;
      }
      // Allow the callback to perform async operations
      // The problem is the second argument only when there is one: a handler
      // written for 6.x sees exactly what it saw.
      if (problem) await onForbidden?.(exchange, problem);
      else await onForbidden?.(exchange);
    }
  }
}
