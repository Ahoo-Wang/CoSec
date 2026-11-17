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
  Fetcher,
  FetcherConfigurer,
  FetchExchange,
} from '@ahoo-wang/fetcher';
import { AuthorizationRequestInterceptor } from './authorizationRequestInterceptor.js';
import { AuthorizationResponseInterceptor } from './authorizationResponseInterceptor.js';
import { CoSecRequestInterceptor } from './cosecRequestInterceptor.js';
import { DeviceIdStorage } from './deviceIdStorage.js';
import {
  ForbiddenErrorInterceptor,
  type ForbiddenErrorInterceptorOptions,
} from './forbiddenErrorInterceptor.js';
import type { CoSecProblem } from './problem.js';
import {
  DEFAULT_REFRESH_RETRY_DELAYS,
  JwtTokenManager,
} from './jwtTokenManager.js';
import { ResourceAttributionRequestInterceptor } from './resourceAttributionRequestInterceptor.js';
import type { TokenRefresher } from './tokenRefresher.js';
import { TokenStorage } from './tokenStorage.js';
import { UnauthorizedErrorInterceptor } from './unauthorizedErrorInterceptor.js';
import type { AppIdCapable, DeviceIdStorageCapable } from './types.js';
import type { RequestTrustCapable } from './requestTrust.js';
import type { SpaceIdProvider } from './spaceIdProvider.js';
import { NoneSpaceIdProvider } from './spaceIdProvider.js';

/**
 * Configuration interface for CoSec security features.
 *
 * This interface provides a simplified, flexible configuration for setting up
 * CoSec authentication and security features. It supports both full JWT
 * authentication setups and minimal header injection configurations.
 *
 * @remarks
 * **Optional Properties:**
 * - tokenStorage: Custom token persistence (defaults to TokenStorage)
 * - deviceIdStorage: Custom device ID management (defaults to DeviceIdStorage)
 * - tokenRefresher: JWT token refresh handler (enables authentication)
 * - spaceIdProvider: Space identifier resolver (enables multi-tenant)
 * - onUnauthorized: Custom 401 error handler
 * - onForbidden: Custom 403 error handler
 *
 * **Configuration Levels:**
 * - Minimal: Nothing (headers only, no authentication)
 * - Standard: tokenRefresher (full JWT auth)
 * - Enterprise: All options (multi-tenant with custom handlers)
 *
 * @example
 * ```typescript
 * // Minimal: Headers only, no authentication
 * const minimalConfig: CoSecConfig = {
 * };
 *
 * // Standard: Full JWT authentication
 * const standardConfig: CoSecConfig = {
 *   tokenRefresher: {
 *     refresh: async (token) => refreshMyToken(token)
 *   },
 *   onUnauthorized: () => window.location.href = '/login',
 *   onForbidden: () => showError('Access denied')
 * };
 *
 * // Enterprise: Multi-tenant with custom storage
 * const enterpriseConfig: CoSecConfig = {
 *   tokenStorage: myCustomTokenStorage,
 *   deviceIdStorage: myCustomDeviceStorage,
 *   tokenRefresher: myTokenRefresher,
 *   spaceIdProvider: mySpaceProvider,
 *   onUnauthorized: handle401,
 *   onForbidden: handle403
 * };
 * ```
 */
export interface CoSecConfig
  extends AppIdCapable, Partial<DeviceIdStorageCapable>, RequestTrustCapable {
  /**
   * Does nothing: CoSec 8.0 does not read a request's app. The app of a
   * request is the `aud` of its token, set by whoever issued it; a host that
   * serves one app (`cosec.authorization.scope.app-id`) answers
   * `403 scope-rejected` to a token of another app, or of none.
   *
   * @deprecated Remove it. Kept so existing configuration still compiles; it
   * is removed in the next major version.
   */
  readonly appId?: string;

  /**
   * Custom implementation for storing and retrieving JWT tokens.
   *
   * If not provided, a default TokenStorage instance will be created
   * using browser localStorage. Use this property to provide custom
   * storage backends or testing implementations.
   *
   * @defaultValue new TokenStorage()
   *
   * @example
   * ```typescript
   * // Custom encrypted storage
   * const encryptedStorage = new EncryptedTokenStorage();
   * const config: CoSecConfig = {
   *   tokenStorage: encryptedStorage
   * };
   *
   * // Memory storage for testing
   * const memoryStorage = new InMemoryTokenStorage();
   * const config: CoSecConfig = {
   *   tokenStorage: memoryStorage
   * };
   * ```
   */
  readonly tokenStorage?: TokenStorage;

  /**
   * Custom implementation for storing and retrieving device identifiers.
   *
   * If not provided, a default DeviceIdStorage instance will be created
   * using browser localStorage with cross-tab synchronization. Use this
   * property for custom device identification strategies.
   *
   * @defaultValue new DeviceIdStorage()
   *
   * @example
   * ```typescript
   * // Custom device ID with custom key
   * const customDeviceStorage = new DeviceIdStorage({
   *   key: 'my-app-device-id',
   *   storage: sessionStorage
   * });
   * const config: CoSecConfig = {
   *   deviceIdStorage: customDeviceStorage
   * };
   * ```
   */
  readonly deviceIdStorage?: DeviceIdStorage;

  /**
   * Token refresh handler for automatic JWT token renewal.
   *
   * When provided, enables full JWT authentication including:
   * - Automatic Bearer token injection in requests
   * - Token refresh on 401 responses
   * - Persistent token storage
   *
   * When not provided, no authentication interceptors are added.
   *
   * @defaultValue undefined (no authentication)
   *
   * @example
   * ```typescript
   * // Basic refresh implementation
   * const myRefresher: TokenRefresher = {
   *   refresh: async (token) => {
   *     const response = await fetch('/api/auth/refresh', {
   *       method: 'POST',
   *       headers: { 'Content-Type': 'application/json' },
   *       body: JSON.stringify({ refreshToken: token.refreshToken })
   *     });
   *     if (!response.ok) throw new Error('Refresh failed');
   *     return response.json();
   *   }
   * };
   *
   * const config: CoSecConfig = {
   *   tokenRefresher: myRefresher
   * };
   * ```
   */
  readonly tokenRefresher?: TokenRefresher;

  /**
   * Provider for resolving space identifiers from requests.
   *
   * Used for multi-tenant applications to scope requests to specific
   * spaces within a tenant. When provided, the CoSec-Space-Id header
   * will be added to requests for space-scoped resources.
   *
   * @defaultValue NoneSpaceIdProvider (no space identification)
   *
   * @example
   * ```typescript
   * // Multi-tenant space provider
   * const spaceProvider: SpaceIdProvider = {
   *   resolveSpaceId: (exchange) => {
   *     return exchange.request.headers['X-Current-Space'] || null;
   *   }
   * };
   *
   * const config: CoSecConfig = {
   *   tokenRefresher: myRefresher,
   *   spaceIdProvider: spaceProvider
   * };
   * ```
   */
  readonly spaceIdProvider?: SpaceIdProvider;

  /**
   * Handler invoked when an HTTP 401 Unauthorized response is received.
   *
   * This callback is triggered when the server returns a 401 status code,
   * typically indicating expired or invalid authentication. Use this to
   * implement custom error handling such as redirecting to login.
   *
   * @param exchange - The fetch exchange containing the failed request
   * @returns Promise<void> or void
   *
   * @defaultValue undefined (401 errors not intercepted)
   *
   * @example
   * ```typescript
   * // Redirect to login
   * const config: CoSecConfig = {
   *   tokenRefresher: myRefresher,
   *   onUnauthorized: async (exchange) => {
   *     await authService.logout();
   *     window.location.href = '/login?reason=session_expired';
   *   }
   * };
   *
   * // Silent token refresh
   * const config: CoSecConfig = {
   *   tokenRefresher: myRefresher,
   *   onUnauthorized: async (exchange) => {
   *     // Force logout without redirect
   *     authService.clearSession();
   *     dispatch(logoutAction());
   *   }
   * };
   * ```
   */
  readonly onUnauthorized?: (
    exchange: FetchExchange,
    problem?: CoSecProblem,
  ) => Promise<void> | void;

  /**
   * Handler invoked when an HTTP 403 Forbidden response is received.
   *
   * This callback is triggered when the server returns a 403 status code,
   * indicating the authenticated user lacks permission for the requested
   * resource. Use this to implement custom access denial handling.
   *
   * @param exchange - The fetch exchange containing the failed request
   * @returns Promise<void>
   *
   * @defaultValue undefined (403 errors not intercepted)
   *
   * @example
   * ```typescript
   * // Show permission error
   * const config: CoSecConfig = {
   *   tokenRefresher: myRefresher,
   *   onForbidden: async (exchange) => {
   *     notification.error({
   *       message: 'Access Denied',
   *       description: 'You do not have permission to access this resource.'
   *     });
   *   }
   * };
   *
   * // Redirect to access denied page
   * const config: CoSecConfig = {
   *   onForbidden: async (exchange) => {
   *     router.push('/access-denied');
   *   }
   * };
   * ```
   */
  readonly onForbidden?: (
    exchange: FetchExchange,
    problem?: CoSecProblem,
  ) => Promise<void>;

  /**
   * Whether the error of a failed refresh ends the session. By default only a
   * `401` does (see {@link isRefreshRejection}); a `429`, a `5xx`, a network
   * error and every other status keep it. Set it for an application whose
   * refresh route refuses a dead pair with another status, say `400`.
   */
  readonly isRefreshRejected?: (error: unknown) => boolean;

  /**
   * The time, in milliseconds, within which the server answers a refresh
   * presented again: **at most** its `cosec.jwt.session.rotation-leeway`
   * (30 seconds by default), which the client cannot read. A refresh whose
   * outcome is unknown (a timeout, a lost response, a 5xx) is retried with
   * the same pair only until this time, less 5 seconds, has passed since it
   * was first sent: later, the server may already have recorded it, and
   * presenting the pair again revokes the session.
   */
  readonly rotationLeeway?: number;

  /**
   * The pauses, in milliseconds, before the retries of a refresh whose
   * outcome is unknown, inside the window of {@link rotationLeeway}; its
   * length is the number of retries. Default `[500, 2000]`; `[]` for none (a
   * later request still retries the same pair inside the window).
   */
  readonly refreshRetryDelays?: readonly number[];

  /**
   * Callback invoked for a `403 scope-rejected` (see
   * {@link ForbiddenErrorInterceptorOptions.onScopeRejected}). Without it,
   * `onForbidden` receives it too, with the problem as its second argument.
   */
  readonly onScopeRejected?: (
    exchange: FetchExchange,
    problem: CoSecProblem,
  ) => Promise<void>;
}

/**
 * Configurer class that applies CoSec security interceptors to a Fetcher instance.
 *
 * This class provides a simplified, declarative way to configure all CoSec
 * authentication and security features. It implements FetcherConfigurer and
 * handles the conditional creation and registration of interceptors based
 * on the provided configuration.
 *
 * @remarks
 * **Architecture:**
 * The configurer acts as a composition root for all CoSec interceptors,
 * managing their lifecycle and ensuring proper ordering in the interceptor
 * chain. This approach provides a clean separation between configuration
 * and implementation.
 *
 * **Interceptor Ordering:**
 * Interceptors are registered in a specific order to ensure correct
 * header injection and error handling:
 * 1. CoSecRequestInterceptor (request) - Adds security headers
 * 2. ResourceAttributionRequestInterceptor (request) - Adds tenant attribution
 * 3. AuthorizationRequestInterceptor (request) - Adds Bearer token [conditional]
 * 4. AuthorizationResponseInterceptor (response) - Handles 401 refresh [conditional]
 * 5. UnauthorizedErrorInterceptor (error) - Handles 401 errors [conditional]
 * 6. ForbiddenErrorInterceptor (error) - Handles 403 errors [conditional]
 *
 * **Conditional Features:**
 * - Authentication (tokenRefresher): Enables JWT Bearer token injection and refresh
 * - Space Support (spaceIdProvider): Enables multi-tenant space scoping
 * - Error Handlers (onUnauthorized/onForbidden): Enables custom error handling
 *
 * @example
 * ```typescript
 * import { Fetcher } from '@ahoo-wang/fetcher';
 * import { CoSecConfigurer } from '@ahoo-wang/fetcher-cosec';
 *
 * // Create and configure
 * const configurer = new CoSecConfigurer({
 *   tokenRefresher: {
 *     refresh: async (token) => {
 *       const res = await fetch('/api/refresh', {
 *         method: 'POST',
 *         body: JSON.stringify({ refreshToken: token.refreshToken })
 *       });
 *       return res.json();
 *     }
 *   },
 *   onUnauthorized: () => window.location.href = '/login',
 *   onForbidden: () => showAccessDenied()
 * });
 *
 * // Apply to fetcher
 * const fetcher = new Fetcher({ baseUrl: 'https://api.example.com' });
 * configurer.applyTo(fetcher);
 *
 * // All requests now have CoSec headers and JWT auth
 * const users = await fetcher.get('/api/users');
 * ```
 *
 * @example
 * ```typescript
 * // Minimal setup - headers only, no authentication
 * const fetcher = new Fetcher();
 * new CoSecConfigurer({
 * }).applyTo(fetcher);
 *
 * // Requests will include:
 * // - CoSec-App-Id: my-tracking-app
 * // - CoSec-Device-Id: <generated>
 * // - CoSec-Request-Id: <unique-per-request>
 * ```
 */
export class CoSecConfigurer implements FetcherConfigurer {
  /**
   * Token storage instance for JWT token persistence.
   *
   * Created from config.tokenStorage or instantiated with default TokenStorage.
   * Used by authorization interceptors to retrieve and store JWT tokens.
   */
  readonly tokenStorage: TokenStorage;

  /**
   * Device ID storage instance for device identifier management.
   *
   * Created from config.deviceIdStorage or instantiated with default DeviceIdStorage.
   * Provides persistent device identification with cross-tab synchronization.
   */
  readonly deviceIdStorage: DeviceIdStorage;

  /**
   * JWT token manager for authentication operations.
   *
   * Only created when tokenRefresher is provided in the config.
   * When undefined, no authentication interceptors are registered.
   */
  readonly tokenManager?: JwtTokenManager;

  /**
   * Space identifier provider for multi-tenant support.
   *
   * Created from config.spaceIdProvider or defaulted to NoneSpaceIdProvider.
   * Resolves space IDs from requests for tenant-scoped resource access.
   */
  readonly spaceIdProvider?: SpaceIdProvider;

  /**
   * Creates a new CoSecConfigurer instance with the provided configuration.
   *
   * The constructor performs dependency initialization:
   * - Creates or wraps tokenStorage and deviceIdStorage with defaults
   * - Initializes spaceIdProvider (defaults to NoneSpaceIdProvider)
   * - Conditionally creates tokenManager only if tokenRefresher is provided
   *
   * @param config - CoSec configuration object containing all settings
   *
   *
   * @example
   * ```typescript
   * // Full configuration
   * const configurer = new CoSecConfigurer({
   *   tokenStorage: customTokenStorage,
   *   deviceIdStorage: customDeviceStorage,
   *   tokenRefresher: myRefresher,
   *   spaceIdProvider: mySpaceProvider,
   *   onUnauthorized: handle401,
   *   onForbidden: handle403
   * });
   *
   * // Minimal configuration
   * const configurer = new CoSecConfigurer({});
   *
   * // Custom storage with default refresh
   * const configurer = new CoSecConfigurer({
   *   tokenStorage: encryptedStorage,
   *   deviceIdStorage: customDeviceStorage
   * });
   * ```
   */
  constructor(public readonly config: CoSecConfig) {
    // Initialize storage instances with provided or default implementations
    this.tokenStorage = config.tokenStorage ?? new TokenStorage();
    this.deviceIdStorage = config.deviceIdStorage ?? new DeviceIdStorage();
    this.spaceIdProvider = config.spaceIdProvider ?? NoneSpaceIdProvider;

    // Conditionally create token manager for authentication
    if (config.tokenRefresher) {
      this.tokenManager = new JwtTokenManager(
        this.tokenStorage,
        config.tokenRefresher,
        {
          isRefreshRejected: config.isRefreshRejected,
          rotationLeeway: config.rotationLeeway,
          refreshRetryDelays:
            config.refreshRetryDelays ?? DEFAULT_REFRESH_RETRY_DELAYS,
        },
      );
    }
  }

  /**
   * Applies CoSec interceptors to the specified Fetcher instance.
   *
   * This method registers all configured interceptors with the Fetcher's
   * interceptor chain. The registration is conditional based on the config:
   *
   * **Always Registered (Headers & Attribution):**
   * - CoSecRequestInterceptor: Injects security headers
   * - ResourceAttributionRequestInterceptor: Adds tenant attribution parameters
   *
   * **Conditional Registration (Authentication):**
   * - AuthorizationRequestInterceptor: Adds Bearer token [requires tokenRefresher]
   * - AuthorizationResponseInterceptor: Handles 401 refresh [requires tokenRefresher]
   *
   * **Conditional Registration (Error Handling):**
   * - UnauthorizedErrorInterceptor: Custom 401 handling [requires onUnauthorized]
   * - ForbiddenErrorInterceptor: Custom 403 handling [requires onForbidden]
   *
   * @param fetcher - The Fetcher instance to configure with CoSec interceptors
   *
   * @throws Error if fetcher is null or undefined
   *
   * @example
   * ```typescript
   * const fetcher = new Fetcher({ baseUrl: 'https://api.example.com' });
   *
   * const configurer = new CoSecConfigurer({
   *   tokenRefresher: myRefresher,
   *   onUnauthorized: () => redirectToLogin(),
   *   onForbidden: () => showError()
   * });
   *
   * configurer.applyTo(fetcher);
   *
   * // Now fetcher has all CoSec interceptors configured
   * const data = await fetcher.get('/api/data');
   * ```
   *
   * @example
   * ```typescript
   * // Applying to multiple fetchers
   * const apiFetcher = new Fetcher({ baseUrl: 'https://api.example.com' });
   * const adminFetcher = new Fetcher({ baseUrl: 'https://admin.example.com' });
   *
   * const configurer = new CoSecConfigurer({
   *   tokenRefresher: myRefresher,
   *   onForbidden: showAccessDenied
   * });
   *
   * // Apply same configuration to multiple fetchers
   * configurer.applyTo(apiFetcher);
   * configurer.applyTo(adminFetcher);
   * ```
   */
  applyTo(fetcher: Fetcher): void {
    // Register CoSec request interceptor for security headers
    fetcher.interceptors.request.use(
      new CoSecRequestInterceptor({
        deviceIdStorage: this.deviceIdStorage,
        spaceIdProvider: this.spaceIdProvider,
        isTrusted: this.config.isTrusted,
      }),
    );

    // Register resource attribution for tenant/owner path parameters
    fetcher.interceptors.request.use(
      new ResourceAttributionRequestInterceptor({
        tokenStorage: this.tokenStorage,
      }),
    );

    // Conditionally register authentication interceptors
    if (this.tokenManager) {
      // Authorization header injection
      fetcher.interceptors.request.use(
        new AuthorizationRequestInterceptor({
          tokenManager: this.tokenManager,
          isTrusted: this.config.isTrusted,
        }),
      );

      // 401 response handling and token refresh
      fetcher.interceptors.response.use(
        new AuthorizationResponseInterceptor({
          tokenManager: this.tokenManager,
          isTrusted: this.config.isTrusted,
        }),
      );
    }

    // Conditionally register error handlers
    if (this.config.onUnauthorized) {
      fetcher.interceptors.error.use(
        new UnauthorizedErrorInterceptor({
          onUnauthorized: this.config.onUnauthorized,
        }),
      );
    }

    if (this.config.onForbidden || this.config.onScopeRejected) {
      fetcher.interceptors.error.use(
        new ForbiddenErrorInterceptor({
          onForbidden: this.config.onForbidden,
          onScopeRejected: this.config.onScopeRejected,
        }),
      );
    }
  }
}
