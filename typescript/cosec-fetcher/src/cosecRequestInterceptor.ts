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

import type { FetchExchange } from '@ahoo-wang/fetcher';
import { setHeader, type RequestInterceptor } from '@ahoo-wang/fetcher';
import {
  COSEC_REQUEST_INTERCEPTOR_ORDER,
  CREDENTIALS_WITHHELD_ATTRIBUTE,
  IGNORE_REFRESH_TOKEN_ATTRIBUTE_KEY,
  SPACE_ID_ATTRIBUTE,
} from './constants.js';
import type { AppIdCapable, DeviceIdStorageCapable } from './types.js';
import { CoSecHeaders } from './types.js';
import { idGenerator } from './idGenerator.js';
import type { SpaceIdProvider } from './spaceIdProvider.js';
import { NoneSpaceIdProvider } from './spaceIdProvider.js';
import type { DeviceIdStorage } from './deviceIdStorage.js';
import {
  isTrustedRequest,
  type RequestTrust,
  type RequestTrustCapable,
} from './requestTrust.js';

/**
 * Configuration options for CoSecRequestInterceptor.
 *
 * This interface combines the required deviceIdStorage with an optional
 * spaceIdProvider for multi-tenant, multi-space applications.
 *
 * @remarks
 * CoSec 8.0 does not read an app from a request: the app is the `aud` of the
 * token. The deprecated `appId` is accepted and ignored.
 * The deviceIdStorage manages persistent device identifiers for tracking.
 * The spaceIdProvider (optional) enables space-scoped resource access control.
 *
 * @example
 * ```typescript
 * // Basic configuration
 * const options: CoSecRequestOptions = {
 *   deviceIdStorage: new DeviceIdStorage()
 * };
 *
 * // With space support for multi-tenant applications
 * const optionsWithSpace: CoSecRequestOptions = {
 *   deviceIdStorage: new DeviceIdStorage(),
 *   spaceIdProvider: new DefaultSpaceIdProvider({
 *     spacedResourcePredicate: urlPredicate,
 *     spaceIdStorage: new SpaceIdStorage()
 *   })
 * };
 * ```
 */
export interface CoSecRequestOptions
  extends AppIdCapable, DeviceIdStorageCapable, RequestTrustCapable {
  /**
   * Optional provider for resolving space identifiers from requests.
   *
   * When provided, enables space-scoped resource access by adding the
   * CoSec-Space-Id header to requests for space-scoped resources.
   *
   * @defaultValue NoneSpaceIdProvider (no space identification)
   */
  spaceIdProvider?: SpaceIdProvider;
}

/**
 * The unique identifier name for the CoSecRequestInterceptor.
 *
 * This constant is used by the Fetcher interceptor registration system
 * to identify and manage this interceptor instance.
 *
 * @example
 * ```typescript
 * // Used internally by Fetcher for interceptor registration
 * fetcher.addInterceptor(new CoSecRequestInterceptor(options));
 * console.log(interceptor.name); // 'CoSecRequestInterceptor'
 * ```
 */
export const COSEC_REQUEST_INTERCEPTOR_NAME = 'CoSecRequestInterceptor';

/** Attribute key holding the space an exchange was first sent to. */

export {
  COSEC_REQUEST_INTERCEPTOR_ORDER,
  IGNORE_REFRESH_TOKEN_ATTRIBUTE_KEY,
} from './constants.js';

/**
 * Request interceptor that automatically adds CoSec authentication headers to outgoing requests.
 *
 * CoSecRequestInterceptor enriches each HTTP request with security-related headers
 * that enable the CoSec authentication system to:
 * - Track device identifiers for security monitoring
 * - Identify the application making the request
 * - Correlate requests for logging and analytics
 * - Scope requests to specific spaces in multi-tenant environments
 *
 * @remarks
 * **Header Injection:**
 * This interceptor injects the following headers into every outgoing request:
 * - `CoSec-App-Id`: Identifies your application
 * - `CoSec-Device-Id`: Unique device identifier (persisted or generated)
 * - `CoSec-Request-Id`: Unique identifier for this request (per-request)
 * - `CoSec-Space-Id`: Space identifier (when spaceIdProvider returns a value)
 *
 * **Execution Order:**
 * The interceptor runs at COSEC_REQUEST_INTERCEPTOR_ORDER position, which ensures:
 * 1. Request body processing is complete
 * 2. Authentication headers are not overwritten by subsequent interceptors
 * 3. Headers are present before the actual HTTP request is made
 *
 * **Device ID Management:**
 * - First request: Generates a new device ID using nanoid
 * - Subsequent requests: Reuses the stored device ID
 * - Storage: Persisted in localStorage with cross-tab synchronization
 *
 * **Space ID Resolution:**
 * - The spaceIdProvider determines if the request requires space scoping
 * - Space ID is resolved before header injection
 * - Supports both space-scoped and non-scoped resources
 *
 * @example
 * ```typescript
 * import { Fetcher } from '@ahoo-wang/fetcher';
 * import { CoSecRequestInterceptor } from '@ahoo-wang/fetcher-cosec';
 * import { DeviceIdStorage } from '@ahoo-wang/fetcher-cosec';
 *
 * // Create dependencies
 * const deviceIdStorage = new DeviceIdStorage();
 *
 * // Create the interceptor
 * const coSecInterceptor = new CoSecRequestInterceptor({
 *   deviceIdStorage
 * });
 *
 * // Register with Fetcher
 * const fetcher = new Fetcher()
 *   .addInterceptor(coSecInterceptor)
 *   .setBaseUrl('https://api.example.com');
 *
 * // All requests will now include CoSec headers
 * const response = await fetcher.get('/api/users');
 * // Request headers include:
 * // - CoSec-App-Id: my-saas-application
 * // - CoSec-Device-Id: <unique-device-id>
 * // - CoSec-Request-Id: <unique-request-id>
 * ```
 *
 * @example
 * ```typescript
 * // Multi-tenant with space support
 * import { DefaultSpaceIdProvider, SpaceIdStorage } from '@ahoo-wang/fetcher-cosec';
 *
 * const spaceStorage = new SpaceIdStorage();
 * const spaceIdProvider = new DefaultSpaceIdProvider({
 *   spacedResourcePredicate: {
 *     test: (exchange) => exchange.request.url.includes('/spaces/')
 *   },
 *   spaceIdStorage: spaceStorage
 * });
 *
 * const coSecInterceptor = new CoSecRequestInterceptor({
 *   deviceIdStorage,
 *   spaceIdProvider
 * });
 * ```
 */
export class CoSecRequestInterceptor implements RequestInterceptor {
  /**
   * The unique name of this interceptor.
   *
   * Used by the Fetcher interceptor system for identification and ordering.
   */
  readonly name = COSEC_REQUEST_INTERCEPTOR_NAME;

  /**
   * The execution order of this interceptor.
   *
   * Set to COSEC_REQUEST_INTERCEPTOR_ORDER to ensure proper positioning
   * in the interceptor chain.
   */
  readonly order = COSEC_REQUEST_INTERCEPTOR_ORDER;

  /**
   * Storage for device identifiers.
   *
   * Provides persistent storage and retrieval of device identifiers
   * with cross-tab synchronization.
   *
   * @internal
   */
  readonly deviceIdStorage: DeviceIdStorage;

  /**
   * Provider for resolving space identifiers.
   *
   * Determines which space a request belongs to (if any) for
   * multi-tenant, multi-space applications.
   *
   * @internal
   */
  readonly spaceIdProvider: SpaceIdProvider;
  /** @internal */
  readonly isTrusted?: RequestTrust;

  /**
   * Creates a new CoSecRequestInterceptor instance.
   *
   * @param options - The CoSec configuration options
   * @param options.deviceIdStorage - Storage for device identifier management
   * @param options.spaceIdProvider - Optional provider for space identification
   * @param options.isTrusted - Optional predicate for which absolute URLs get the CoSec headers (default: all)
   *
   *
   * @example
   * ```typescript
   * // Basic instantiation
   * const interceptor = new CoSecRequestInterceptor({
   *   deviceIdStorage: new DeviceIdStorage()
   * });
   *
   * // With custom device ID storage
   * const customStorage = new DeviceIdStorage({
   *   key: 'custom-device-key',
   *   storage: sessionStorage
   * });
   * const interceptorWithCustom = new CoSecRequestInterceptor({
   *   deviceIdStorage: customStorage
   * });
   *
   * // With space support
   * const spaceStorage = new SpaceIdStorage();
   * const spaceProvider = new DefaultSpaceIdProvider({
   *   spacedResourcePredicate: { test: (e) => true },
   *   spaceIdStorage: spaceStorage
   * });
   * const interceptorWithSpace = new CoSecRequestInterceptor({
   *   deviceIdStorage,
   *   spaceIdProvider: spaceProvider
   * });
   * ```
   */
  constructor({
    deviceIdStorage,
    spaceIdProvider,
    isTrusted,
  }: CoSecRequestOptions) {
    this.deviceIdStorage = deviceIdStorage;
    this.spaceIdProvider = spaceIdProvider ?? NoneSpaceIdProvider;
    this.isTrusted = isTrusted;
  }

  /**
   * Intercepts outgoing requests to add CoSec authentication headers.
   *
   * This method is called by the Fetcher interceptor chain for each request.
   * It enriches the request with security headers before the HTTP request is made.
   *
   * **Header Injection Process:**
   * 1. Generates a unique request ID using the idGenerator
   * 2. Retrieves or creates a device ID from deviceIdStorage
   * 3. Resolves space ID from spaceIdProvider (if applicable)
   * 4. Adds all headers to the request
   *
   * @param exchange - The fetch exchange containing the request to process
   *
   * @remarks
   * **Header Values:**
   * - `CoSec-Device-Id`: Retrieved from storage or newly generated
   * - `CoSec-Request-Id`: Unique per request (not persisted)
   * - `CoSec-Space-Id`: Set only if spaceIdProvider returns a value
   *
   * **Error Handling:**
   * - If deviceIdStorage.getOrCreate() throws, the error propagates
   * - If spaceIdProvider.resolveSpaceId() throws, the error propagates
   * - If ensureRequestHeaders() fails, the error propagates
   *
   * **Thread Safety:**
   * - The idGenerator is safe for concurrent use
   * - deviceIdStorage handles concurrent access internally
   * - Each request gets a unique request ID
   *
   * @example
   * ```typescript
   * // The interceptor is called automatically by Fetcher
   * const fetcher = new Fetcher()
   *   .addInterceptor(new CoSecRequestInterceptor({
   *     deviceIdStorage: new DeviceIdStorage()
   *   }));
   *
   * // This request will have CoSec headers added
   * const response = await fetcher.get('/api/data');
   * ```
   *
   * @example
   * ```typescript
   * // Headers added to outgoing request:
   * // GET /api/users HTTP/1.1
   * // Host: api.example.com
   * // CoSec-App-Id: my-app
   * // CoSec-Device-Id: abc123xyz789
   * // CoSec-Request-Id: req_abc123def456
   * // CoSec-Space-Id: workspace-alpha  (if space resolved)
   * ```
   */
  async intercept(exchange: FetchExchange) {
    // The device ID and the other CoSec headers identify this client: only
    // trusted origins receive them.
    if (!isTrustedRequest(exchange, this.isTrusted)) {
      exchange.attributes.set(CREDENTIALS_WITHHELD_ATTRIBUTE, true);
      return;
    }
    // Generate a unique request ID for this request
    const requestId = idGenerator.generateId();

    // Get or create a device ID
    const deviceId = this.deviceIdStorage.getOrCreate();

    // Resolve space ID for multi-tenant support
    // The space is resolved once per exchange: a retry (after a 401 refresh)
    // is the same operation, so it keeps the space it was first sent to even
    // if the current space changed meanwhile.
    let spaceId: string | null | undefined;
    if (exchange.attributes.has(IGNORE_REFRESH_TOKEN_ATTRIBUTE_KEY)) {
      // The refresh call carries the access token that expired, so the server
      // reads it as anonymous, and an anonymous request that declares a space
      // is refused 401 (SCP-3): the refresh is never spaced.
      spaceId = null;
      exchange.attributes.set(SPACE_ID_ATTRIBUTE, null);
    } else if (exchange.attributes.has(SPACE_ID_ATTRIBUTE)) {
      spaceId = exchange.attributes.get(SPACE_ID_ATTRIBUTE);
    } else {
      spaceId = this.spaceIdProvider.resolveSpaceId(exchange);
      exchange.attributes.set(SPACE_ID_ATTRIBUTE, spaceId ?? null);
    }

    // Ensure request headers object exists
    const requestHeaders = exchange.ensureRequestHeaders();

    // Add CoSec headers to the request
    setHeader(requestHeaders, CoSecHeaders.DEVICE_ID, deviceId);
    setHeader(requestHeaders, CoSecHeaders.REQUEST_ID, requestId);
    if (spaceId) {
      setHeader(requestHeaders, CoSecHeaders.SPACE_ID, spaceId);
    }
  }
}
