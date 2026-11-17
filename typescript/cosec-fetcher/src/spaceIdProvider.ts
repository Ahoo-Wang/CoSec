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

import type { KeyStorageOptions } from '@ahoo-wang/fetcher-storage';
import {
  KeyStorage,
  typedIdentitySerializer,
} from '@ahoo-wang/fetcher-storage';
import { storageEventBus } from './storageEventBus.js';
import type { FetchExchange } from '@ahoo-wang/fetcher';

/**
 * Represents a space identifier for resource scoping within a tenant.
 *
 * A SpaceId uniquely identifies a space (workspace, project, organization unit, etc.)
 * that belongs to a tenant. This enables multi-tenant applications to further
 * categorize resources into discrete spaces for access control and data isolation.
 *
 * In a typical multi-tenant architecture:
 * - TenantId: Identifies the tenant/organization
 * - SpaceId: Identifies a space within that tenant (e.g., team, project, department)
 *
 * @example
 * ```
 * // A tenant might have multiple spaces:
 * Tenant: "company-abc"
 *   Space: "engineering-team"
 *   Space: "marketing-team"
 *   Space: "project-alpha"
 * ```
 */
type SpaceId = string;

/**
 * Interface for resolving space identifiers from HTTP request exchanges.
 *
 * A SpaceIdProvider determines which space a particular request belongs to
 * based on the incoming request characteristics. This abstraction allows
 * different strategies for space identification, such as header-based,
 * URL-based, or cookie-based resolution.
 *
 * @remarks
 * Implementations of this interface are used by the CoSecRequestInterceptor
 * to add the appropriate space identifier to outgoing requests. This enables
 * proper routing and access control in multi-tenant, multi-space environments.
 *
 * @example
 * ```typescript
 * // Example: Extract space ID from a custom header
 * const headerSpaceIdProvider: SpaceIdProvider = {
 *   resolveSpaceId: (exchange) => {
 *     return exchange.request.headers['X-Space-Id'] || null;
 *   }
 * };
 * ```
 */
export interface SpaceIdProvider {
  /**
   * Resolves the space identifier for a given fetch exchange.
   *
   * This method examines the request exchange and determines which space
   * the request should be associated with. The resolution strategy depends
   * on the implementation - it may examine headers, URL patterns, cookies,
   * or other request attributes.
   *
   * @param exchange - The fetch exchange containing the HTTP request details
   * @returns The resolved space identifier, or null if no space can be determined
   *
   * @example
   * ```typescript
   * // Example: Extract space ID from a custom header
   * const provider: SpaceIdProvider = {
   *   resolveSpaceId: (exchange) => {
   *     return exchange.request.headers['X-Space-Id'] || null;
   *   }
   * };
   *
   * // Example: Extract space ID from URL path
   * const urlSpaceIdProvider: SpaceIdProvider = {
   *   resolveSpaceId: (exchange) => {
   *     const match = exchange.request.url.match(/\/spaces\/([^\/]+)/);
   *     return match ? match[1] : null;
   *   }
   * };
   * ```
   */
  resolveSpaceId(exchange: FetchExchange): SpaceId | null;
}

/**
 * A no-op SpaceIdProvider that always returns null.
 *
 * This provider is used when space identification is not required or when
 * the application operates without space-level resource isolation.
 * It provides a convenient default that requires no configuration.
 *
 * @remarks
 * Use NoneSpaceIdProvider when:
 * - Resources are not organized into spaces
 * - Space context is managed externally
 * - Single-space per tenant deployment
 *
 * @example
 * ```typescript
 * // Single-space configuration (no space-level isolation needed)
 * const interceptor = new CoSecRequestInterceptor({
 *   deviceIdStorage,
 *   spaceIdProvider: NoneSpaceIdProvider,
 * });
 * ```
 */
export const NoneSpaceIdProvider: SpaceIdProvider = {
  resolveSpaceId: () => null,
};

/**
 * The default storage key used for persisting space identifiers.
 *
 * This key is used by SpaceIdStorage to store and retrieve space identifiers
 * from the browser's localStorage. The default value ensures consistency
 * across different parts of the application that need to access the stored space ID.
 */
export const DEFAULT_COSEC_SPACE_ID_KEY = 'cosec-space-id';

/**
 * Configuration options for SpaceIdStorage.
 *
 * This interface extends KeyStorageOptions to provide SpaceId-specific
 * configuration while allowing customization of the underlying storage behavior.
 * All properties from KeyStorageOptions are optional and will use sensible defaults.
 *
 * @remarks
 * When no options are provided, SpaceIdStorage will use:
 * - DEFAULT_COSEC_SPACE_ID_KEY as the storage key
 * - A BroadcastTypedEventBus for cross-tab synchronization
 * - The browser's localStorage for persistence
 * - The identity serializer for direct string storage
 *
 * @example
 * ```typescript
 * // Default configuration
 * const storage = new SpaceIdStorage();
 *
 * // Custom key
 * const customStorage = new SpaceIdStorage({
 *   key: 'my-custom-space-key'
 * });
 *
 * // Custom storage with event bus
 * const storageWithCustomBus = new SpaceIdStorage({
 *   key: 'space-id',
 *   eventBus: myCustomEventBus,
 *   storage: myCustomStorage
 * });
 * ```
 */
// eslint-disable-next-line @typescript-eslint/no-empty-object-type
export interface SpaceIdStorageOptions extends Partial<
  KeyStorageOptions<SpaceId>
> {}

/**
 * Storage class for managing space identifiers with persistence and cross-tab synchronization.
 *
 * SpaceIdStorage extends KeyStorage to provide specialized storage for space
 * identifiers. It handles persistence to localStorage and provides automatic
 * synchronization across multiple browser tabs using the BroadcastChannel API.
 *
 * @remarks
 * Key features:
 * - Automatic persistence to localStorage
 * - Cross-tab synchronization via BroadcastChannel
 * - Caching for performance optimization
 * - Event-based notification of changes
 *
 * The storage uses the identity serializer, meaning space IDs are stored
 * and retrieved as-is without any transformation.
 *
 * @example
 * ```typescript
 * // Basic usage
 * const spaceStorage = new SpaceIdStorage();
 * spaceStorage.set('workspace-alpha');
 * const spaceId = spaceStorage.get(); // 'workspace-alpha'
 *
 * // Listening for changes
 * const removeListener = spaceStorage.addListener({
 *   name: 'space-change',
 *   handle: (event) => {
 *     console.log('Space changed from', event.oldValue, 'to', event.newValue);
 *   }
 * });
 *
 * // Cleanup
 * spaceStorage.destroy();
 * removeListener();
 * ```
 */
export class SpaceIdStorage extends KeyStorage<SpaceId> {
  /**
   * Creates a new SpaceIdStorage instance.
   *
   * @param options - Optional configuration options for the storage
   * @param options.key - The storage key (defaults to DEFAULT_COSEC_SPACE_ID_KEY)
   * @param options.eventBus - Custom event bus for cross-tab communication, whose channel name defaults to one derived from the storage key
   * @param options.storage - Custom storage implementation (defaults to localStorage)
   * @param options.serializer - Custom serializer (defaults to identity serializer)
   * @param options.defaultValue - Default value when no space ID is stored
   *
   * @throws Error if the underlying storage is unavailable
   *
   * @example
   * ```typescript
   * // Default configuration
   * const storage = new SpaceIdStorage();
   *
   * // With custom options
   * const storage = new SpaceIdStorage({
   *   key: 'custom-space-key',
   *   storage: sessionStorage,
   *   defaultValue: 'default-space'
   * });
   * ```
   */
  constructor({
    key = DEFAULT_COSEC_SPACE_ID_KEY,
    eventBus,
    ...reset
  }: SpaceIdStorageOptions = {}) {
    super({
      key,
      eventBus: storageEventBus(key, eventBus, reset.storage),
      ...reset,
      serializer: typedIdentitySerializer(),
    });
    if (!eventBus) this.ownEventBus();
  }
}

/**
 * Interface for predicates that determine if a resource requires space scoping.
 *
 * A SpacedResourcePredicate is used to evaluate whether a given request exchange
 * should be associated with a space identifier. This allows fine-grained control
 * over which resources belong to spaces and require space-based access control.
 *
 * @remarks
 * Predicates can implement various strategies:
 * - URL pattern matching (e.g., /spaces/{spaceId}/resources)
 * - Header presence checks
 * - Method-based filtering
 * - Custom business logic
 *
 * @example
 * ```typescript
 * // URL-based predicate for space-scoped resources
 * const urlPredicate: SpacedResourcePredicate = {
 *   test: (exchange) => {
 *     return exchange.request.url.includes('/spaces/');
 *   }
 * };
 *
 * // Header-based predicate
 * const headerPredicate: SpacedResourcePredicate = {
 *   test: (exchange) => {
 *     return 'X-Require-Space' in exchange.request.headers;
 *   }
 * };
 *
 * // Composite predicate using logical operations
 * const compositePredicate: SpacedResourcePredicate = {
 *   test: (exchange) => {
 *     return urlPredicate.test(exchange) && headerPredicate.test(exchange);
 *   }
 * };
 * ```
 */
export interface SpacedResourcePredicate {
  /**
   * Tests whether the given exchange represents a space-scoped resource.
   *
   * This method evaluates the request exchange against the predicate's criteria
   * to determine if space-based routing or access control should be applied.
   *
   * @param exchange - The fetch exchange to evaluate
   * @returns true if the resource requires space scoping, false otherwise
   *
   * @example
   * ```typescript
   * const predicate: SpacedResourcePredicate = {
   *   test: (exchange) => {
   *     // Only resources under /api/spaces/ require space identification
   *     return exchange.request.url.startsWith('/api/v1/spaces/');
   *   }
   * };
   * ```
   */
  test(exchange: FetchExchange): boolean;
}

/**
 * Configuration options for DefaultSpaceIdProvider.
 *
 * This interface combines the predicate and storage dependencies required
 * by DefaultSpaceIdProvider to resolve space identifiers.
 */
export interface SpaceIdProviderOptions {
  /**
   * The predicate used to determine if a request requires space scoping.
   *
   * This predicate is consulted before attempting to retrieve the space ID
   * from storage. If the predicate returns false, no space ID will be returned.
   */
  spacedResourcePredicate: SpacedResourcePredicate;

  /**
   * The storage instance for persisting and retrieving space identifiers.
   *
   * This storage is used to look up the space ID once the predicate
   * confirms that the request requires space scoping.
   */
  spaceIdStorage: SpaceIdStorage;
}

/**
 * Default implementation of SpaceIdProvider that combines predicate-based
 * filtering with persistent storage.
 *
 * DefaultSpaceIdProvider implements a two-step resolution strategy:
 * 1. First, the predicate determines if the request requires space scoping
 * 2. If yes, the space ID is retrieved from the configured storage
 *
 * This separation enables:
 * - Efficient filtering of non-spaced resources
 * - Flexible predicate logic without storage coupling
 * - Testable and replaceable components
 *
 * @example
 * ```typescript
 * // Create storage for space IDs
 * const spaceStorage = new SpaceIdStorage();
 *
 * // Create predicate for space-scoped resources
 * const spacedResourcePredicate: SpacedResourcePredicate = {
 *   test: (exchange) => {
 *     return exchange.request.url.includes('/spaces/');
 *   }
 * };
 *
 * // Create the provider
 * const spaceIdProvider = new DefaultSpaceIdProvider({
 *   spacedResourcePredicate,
 *   spaceIdStorage: spaceStorage
 * });
 *
 * // Use with CoSecRequestInterceptor
 * const interceptor = new CoSecRequestInterceptor({
 *   deviceIdStorage,
 *   spaceIdProvider
 * });
 * ```
 */
export class DefaultSpaceIdProvider implements SpaceIdProvider {
  /**
   * Creates a new DefaultSpaceIdProvider instance.
   *
   * @param options - The configuration options containing the predicate and storage
   * @param options.spacedResourcePredicate - Determines which requests require space scoping
   * @param options.spaceIdStorage - Provides access to the persisted space identifier
   *
   * @example
   * ```typescript
   * const provider = new DefaultSpaceIdProvider({
   *   spacedResourcePredicate: {
   *     test: (exchange) => exchange.request.url.includes('/api/')
   *   },
   *   spaceIdStorage: new SpaceIdStorage()
   * });
   * ```
   */
  constructor(public options: SpaceIdProviderOptions) {}

  /**
   * Resolves the space identifier for a given fetch exchange.
   *
   * This method first checks if the request requires space scoping by
   * evaluating the predicate. If the predicate returns true, it retrieves
   * the space ID from storage. Otherwise, it returns null.
   *
   * @param exchange - The fetch exchange containing the HTTP request details
   * @returns The space identifier if the request requires scoping and one exists,
   *         otherwise null
   *
   * @example
   * ```typescript
   * const provider = new DefaultSpaceIdProvider({
   *   spacedResourcePredicate: {
   *     test: (exchange) => exchange.request.url.includes('/spaced/')
   *   },
   *   spaceIdStorage: new SpaceIdStorage()
   * });
   *
   * // For a request to /api/spaces/workspace-alpha/resources
   * const exchange = createMockExchange({ url: '/api/spaces/workspace-alpha/resources' });
   * const spaceId = provider.resolveSpaceId(exchange); // Returns stored space ID
   *
   * // For a request to /api/public/resources
   * const publicExchange = createMockExchange({ url: '/api/public/resources' });
   * const noSpace = provider.resolveSpaceId(publicExchange); // Returns null
   * ```
   */
  resolveSpaceId(exchange: FetchExchange): SpaceId | null {
    if (!this.options.spacedResourcePredicate.test(exchange)) {
      return null;
    }
    return this.options.spaceIdStorage.get();
  }
}
