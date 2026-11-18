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

import { CoSecHeaders, ResponseCodes } from './types.js';
import type { FetchExchange } from '@ahoo-wang/fetcher';
import {
  deleteHeader,
  getHeader,
  type ResponseInterceptor,
} from '@ahoo-wang/fetcher';
import {
  CREDENTIALS_WITHHELD_ATTRIBUTE,
  IGNORE_REFRESH_TOKEN_ATTRIBUTE_KEY,
  TOKEN_SESSION_ATTRIBUTE,
  UNAUTHORIZED_ERROR_INTERCEPTOR_NAME,
} from './constants.js';
import { NonReplayableRequestError } from './errors.js';
import { isTrustedRequest } from './requestTrust.js';
import { isOneShotBody } from './requestBody.js';

export { NonReplayableRequestError } from './errors.js';
import { assertTokenSession, isSameToken } from './refreshSession.js';
import type { JwtCompositeToken } from './jwtToken.js';
import { CoSecProblemType, resolveCoSecProblem } from './problem.js';
import {
  AUTHORIZATION_REQUEST_INTERCEPTOR_NAME,
  type AuthorizationInterceptorOptions,
} from './authorizationRequestInterceptor.js';

/**
 * The name of the AuthorizationResponseInterceptor.
 */
export const AUTHORIZATION_RESPONSE_INTERCEPTOR_NAME =
  'AuthorizationResponseInterceptor';

/**
 * The order of the AuthorizationResponseInterceptor.
 * Set to a high negative value to ensure it runs early in the interceptor chain.
 */
export const AUTHORIZATION_RESPONSE_INTERCEPTOR_ORDER =
  Number.MIN_SAFE_INTEGER + 1000;

/**
 * Maximum number of times a single exchange may be refreshed and retried on a
 * 401 response. Retry re-runs the entire interceptor chain (including this
 * interceptor), so without a bound a retried request that still returns 401
 * would recurse refresh indefinitely.
 */
export const AUTHORIZATION_RESPONSE_MAX_RETRY = 1;

/**
 * Attribute key storing how many times an exchange has been refreshed and
 * retried, used to bound the refresh-retry loop.
 */
const AUTHORIZATION_RETRY_COUNT_ATTRIBUTE = 'AuthorizationResponseRetryCount';

/**
 * CoSecResponseInterceptor is responsible for handling unauthorized responses (401)
 * by attempting to refresh the authentication token and retrying the original request.
 *
 * This interceptor:
 * 1. Checks if the response status is 401 (UNAUTHORIZED)
 * 2. If so, and if there's a current token, attempts to refresh it
 * 3. On successful refresh, stores the new token and retries the original request
 * 4. On refresh failure, clears stored tokens and propagates the error
 * 5. A failure of the retried request itself propagates without clearing the refreshed token
 */
export class AuthorizationResponseInterceptor implements ResponseInterceptor {
  readonly name = AUTHORIZATION_RESPONSE_INTERCEPTOR_NAME;
  readonly order = AUTHORIZATION_RESPONSE_INTERCEPTOR_ORDER;

  /**
   * Creates a new AuthorizationResponseInterceptor instance.
   * @param options - The CoSec configuration options including token storage and refresher
   */
  constructor(public options: AuthorizationInterceptorOptions) {}

  /**
   * Intercepts the response and handles unauthorized responses by refreshing tokens.
   * @param exchange - The fetch exchange containing request and response information
   */
  async intercept(exchange: FetchExchange): Promise<void> {
    const response = exchange.response;
    // If there's no response, nothing to intercept
    if (!response) {
      return;
    }

    // Only handle unauthorized responses (401)
    if (
      response.status !== ResponseCodes.UNAUTHORIZED ||
      exchange.attributes?.has(IGNORE_REFRESH_TOKEN_ATTRIBUTE_KEY)
    ) {
      return;
    }
    // A request the trust policy kept this application's credentials from:
    // its 401 says nothing about the session, so it is neither refreshed for
    // nor replayed.
    if (
      exchange.attributes?.get(CREDENTIALS_WITHHELD_ATTRIBUTE) === true ||
      !isTrustedRequest(exchange, this.options.isTrusted)
    ) {
      return;
    }

    const authorization = getHeader(
      exchange.request?.headers,
      CoSecHeaders.AUTHORIZATION,
    );
    if (
      exchange.attributes?.get(AUTHORIZATION_REQUEST_INTERCEPTOR_NAME) !==
      authorization
    ) {
      return;
    }

    const currentToken = this.options.tokenManager.currentToken;
    assertTokenSession(exchange, currentToken);

    // A 401 of CoSec 8.0 says why, and only `token-expired` is one a refresh
    // can cure. `unauthorized`: no credential was seen. `token-revoked`,
    // `token-invalid`: the session ended or the credential is not accepted
    // (IDN-3, IDN-4, IDN-10..13, IDN-18), and the refresh would be refused.
    // Any other 401 (no problem body, a type this client does not know) is
    // refreshed once, as before.
    const problem = await resolveCoSecProblem(exchange);
    if (problem?.slug === CoSecProblemType.UNAUTHORIZED) return;
    if (
      problem?.slug === CoSecProblemType.TOKEN_REVOKED ||
      problem?.slug === CoSecProblemType.TOKEN_INVALID
    ) {
      this.endSession(exchange);
      return;
    }
    if (
      !this.options.tokenManager.isRefreshable &&
      (!exchange.attributes?.has(TOKEN_SESSION_ATTRIBUTE) ||
        exchange.attributes.get(TOKEN_SESSION_ATTRIBUTE) === currentToken)
    ) {
      return;
    }

    // Guard against infinite refresh-retry loops: retrying re-runs the whole
    // interceptor chain (including this interceptor), so a retried request
    // that still returns 401 would otherwise recurse refresh until the refresh
    // token expires or the call stack overflows.
    const attributes = (exchange.attributes ??= new Map<string, unknown>());
    const retryCount =
      (attributes.get(AUTHORIZATION_RETRY_COUNT_ATTRIBUTE) as
        number | undefined) ?? 0;
    if (retryCount >= AUTHORIZATION_RESPONSE_MAX_RETRY) {
      return;
    }
    attributes.set(AUTHORIZATION_RETRY_COUNT_ATTRIBUTE, retryCount + 1);

    // The 401 is replaced by the retry and never reaches the caller's body
    // readers: release its body now, so it holds no connection the refresh
    // and the retry may need (a pool of one would otherwise wait forever).
    releaseBody(response);

    // The manager owns cleanup for the session that started the refresh.
    await this.options.tokenManager.refresh(exchange);
    assertTokenSession(exchange, this.options.tokenManager.currentToken);
    // A stream body was consumed by the first attempt and cannot be sent
    // again. The session is refreshed; the caller re-sends with a new body.
    if (isOneShotBody(exchange.request?.body)) {
      attributes.set(UNAUTHORIZED_ERROR_INTERCEPTOR_NAME, true);
      throw new NonReplayableRequestError();
    }
    // Retrying re-runs the whole request interceptor chain. Drop the stale
    // Authorization header so AuthorizationRequestInterceptor re-adds it
    // with the refreshed token — it skips requests that already carry one.
    if (exchange.request?.headers) {
      deleteHeader(exchange.request.headers, CoSecHeaders.AUTHORIZATION);
    }
    // Retry the original request with the new token. A failure of the retry
    // itself (network error, another 401, ...) propagates unchanged: it must
    // not clear the freshly refreshed and still valid token.
    // The retry's own outcome replaces the 401: should its fetch fail
    // (offline, timeout, abort), the error chain must not see the old 401
    // and report the refreshed session as unauthorized.
    exchange.response = undefined;
    await this.retry(exchange);
  }

  /**
   * Re-sends the request inside the current response phase.
   *
   * Only the request phase and the response interceptors up to and including
   * this one are replayed; the outer response chain then carries on with the
   * remaining interceptors (status validation, body readers, ...) against the
   * fresh response, so each of them runs once. The error phase is not
   * replayed either: a failed retry propagates to the outer exchange, whose
   * error interceptors run once and see the original error unwrapped.
   *
   * @internal
   */
  /**
   * The session ended on the server: the pair that was sent is dead, so it
   * is removed (only when it is still the stored one: another tab may have
   * signed in again meanwhile) and the unauthorized notification goes to the
   * exchange that removed it, once.
   *
   * @internal
   */
  endSession(exchange: FetchExchange): void {
    const { tokenManager } = this.options;
    const sent = exchange.attributes.get(TOKEN_SESSION_ATTRIBUTE) as
      JwtCompositeToken | null | undefined;
    const ended = !!sent && isSameToken(sent, tokenManager.currentToken);
    if (ended) tokenManager.tokenStorage.remove();
    exchange.attributes.set(UNAUTHORIZED_ERROR_INTERCEPTOR_NAME, () => ended);
  }

  async retry(exchange: FetchExchange): Promise<void> {
    const { request, response } = exchange.fetcher.interceptors;
    await request.intercept(exchange);
    for (const interceptor of response.interceptors) {
      await interceptor.intercept(exchange);
      if (interceptor.name === this.name) {
        return;
      }
    }
  }
}

function releaseBody(response: Response): void {
  const body = response.body;
  if (!body || response.bodyUsed || body.locked) return;
  // Cleanup must not mask the 401 handling, so a failed cancel is ignored.
  body.cancel().catch(() => {});
}
