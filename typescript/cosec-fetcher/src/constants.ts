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

// Names shared across modules, kept here so the modules that use them do not
// import each other at runtime. Each is re-exported from its original module.

import { DEFAULT_INTERCEPTOR_ORDER_STEP } from '@ahoo-wang/fetcher';

/**
 * The name identifier for the UnauthorizedErrorInterceptor.
 * Used for interceptor registration and identification in the interceptor chain.
 */
export const UNAUTHORIZED_ERROR_INTERCEPTOR_NAME =
  'UnauthorizedErrorInterceptor';

/**
 * The execution order for the CoSecRequestInterceptor.
 *
 * The order is Number.MIN_SAFE_INTEGER + DEFAULT_INTERCEPTOR_ORDER_STEP, so
 * the interceptor runs among the first request interceptors: before
 * AuthorizationRequestInterceptor (one step later), RequestBodyInterceptor
 * (Number.MIN_SAFE_INTEGER + BUILT_IN_INTERCEPTOR_ORDER_STEP), URL resolution
 * and the actual HTTP request.
 *
 * @remarks
 * - Position: Before RequestBodyInterceptor
 * - Position: Before FetchInterceptor
 * - Value: Number.MIN_SAFE_INTEGER + 1000
 *
 * @example
 * ```typescript
 * const interceptor = new CoSecRequestInterceptor(options);
 * console.log(interceptor.order); // -9007199254740990
 * ```
 */
export const COSEC_REQUEST_INTERCEPTOR_ORDER =
  Number.MIN_SAFE_INTEGER + DEFAULT_INTERCEPTOR_ORDER_STEP;

/**
 * Attribute key used to mark requests that should skip token refresh.
 *
 * When this attribute is set to true on a request exchange, the
 * AuthorizationRequestInterceptor will skip the automatic token refresh
 * for that specific request. This is useful for operations where
 * token refresh would cause issues, such as logout requests.
 *
 * @remarks
 * Set this attribute on the exchange before the AuthorizationRequestInterceptor runs:
 * ```typescript
 * exchange.attributes.set(IGNORE_REFRESH_TOKEN_ATTRIBUTE_KEY, true);
 * ```
 *
 * @example
 * ```typescript
 * // Skip refresh during logout
 * const logoutExchange = await fetcher.createRequest('/logout', {
 *   method: 'POST'
 * }).getExchange();
 * logoutExchange.attributes.set(IGNORE_REFRESH_TOKEN_ATTRIBUTE_KEY, true);
 * await fetcher.fetch(logoutExchange);
 * ```
 */
export const IGNORE_REFRESH_TOKEN_ATTRIBUTE_KEY = 'Ignore-Refresh-Token';

/** Exchange attribute holding the token session a request was sent with. */
export const TOKEN_SESSION_ATTRIBUTE = 'CoSec-Token-Session';

/**
 * Set on an exchange whose request the trust policy kept the CoSec headers or
 * credentials from: its 401 is not about this application's session.
 */
export const CREDENTIALS_WITHHELD_ATTRIBUTE = 'CoSec-Credentials-Withheld';

/** The `CoSec-Space-Id` the request interceptor resolved for an exchange (`null`: none). */
export const SPACE_ID_ATTRIBUTE = 'CoSec-Space-Id';
