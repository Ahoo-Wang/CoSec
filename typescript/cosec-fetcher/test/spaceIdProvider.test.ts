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

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import type {
  SpaceIdProvider,
  SpacedResourcePredicate,
  SpaceIdProviderOptions,
} from '../src';
import {
  NoneSpaceIdProvider,
  SpaceIdStorage,
  DEFAULT_COSEC_SPACE_ID_KEY,
  DefaultSpaceIdProvider,
} from '../src';
import type { Fetcher, FetchRequest } from '@ahoo-wang/fetcher';
import { FetchExchange } from '@ahoo-wang/fetcher';

const mockStorage = {
  getItem: vi.fn(),
  setItem: vi.fn(),
  removeItem: vi.fn(),
  clear: vi.fn(),
  key: vi.fn(),
  length: 0,
};

class MockBroadcastChannel {
  postMessage = vi.fn();
  close = vi.fn();
  onmessage = null;
  constructor(_name: string) {}
}

vi.stubGlobal('BroadcastChannel', MockBroadcastChannel);
vi.stubGlobal('window', { localStorage: mockStorage });

// Records the channel name of every SerialTypedEventBus constructed by the
// module mock below, so tests can assert channel-name derivation.
const { serialBusChannels } = vi.hoisted(() => ({
  serialBusChannels: [] as string[],
}));

vi.mock('@ahoo-wang/fetcher-eventbus', () => ({
  BroadcastTypedEventBus: class BroadcastTypedEventBus {
    emit = vi.fn().mockResolvedValue(undefined);
    on = vi.fn();
    off = vi.fn();
    destroy = vi.fn();
  },
  SerialTypedEventBus: class SerialTypedEventBus {
    emit = vi.fn().mockResolvedValue(undefined);
    on = vi.fn();
    off = vi.fn();
    destroy = vi.fn();
    constructor(name: string) {
      serialBusChannels.push(name);
    }
  },
  nameGenerator: {
    generate: vi.fn((prefix: string) => `${prefix}_1`),
  },
}));

const createMockFetcher = (): Fetcher =>
  ({
    fetch: vi.fn(),
    createRequest: vi.fn(),
    addInterceptor: vi.fn(),
    removeInterceptor: vi.fn(),
    getInterceptors: vi.fn(),
    getDefaultConfig: vi.fn(),
    setDefaultConfig: vi.fn(),
    getBaseUrl: vi.fn(),
    setBaseUrl: vi.fn(),
    getBaseHeaders: vi.fn(),
    setBaseHeaders: vi.fn(),
    getMiddleware: vi.fn(),
    setMiddleware: vi.fn(),
    getPlugins: vi.fn(),
    setPlugins: vi.fn(),
    getResultExtractors: vi.fn(),
    setResultExtractors: vi.fn(),
    config: {
      baseUrl: '',
      baseHeaders: {},
      credentials: 'same-origin',
    },
  }) as unknown as Fetcher;

const createMockFetchRequest = (
  overrides: Partial<FetchRequest> = {},
): FetchRequest => ({
  url: 'https://api.example.com/space-resource',
  method: 'GET',
  headers: {},
  body: undefined,
  urlParams: undefined,
  ...overrides,
});

const createMockExchange = (
  requestOverrides: Partial<FetchRequest> = {},
): FetchExchange => {
  const fetcher = createMockFetcher();
  const request = createMockFetchRequest(requestOverrides);
  return new FetchExchange({ fetcher, request });
};

describe('NoneSpaceIdProvider', () => {
  describe('resolveSpaceId', () => {
    it('should always return null regardless of exchange', () => {
      const exchange = createMockExchange();
      const result = NoneSpaceIdProvider.resolveSpaceId(exchange);
      expect(result).toBeNull();
    });

    it('should return null for exchange with different request configurations', () => {
      const exchange1 = createMockExchange({
        url: 'https://api.example.com/resource1',
      });
      const exchange2 = createMockExchange({
        url: 'https://api.example.com/resource2',
        method: 'POST',
      });
      const exchange3 = createMockExchange({
        url: 'https://api.example.com/resource3',
        method: 'PUT',
      });

      expect(NoneSpaceIdProvider.resolveSpaceId(exchange1)).toBeNull();
      expect(NoneSpaceIdProvider.resolveSpaceId(exchange2)).toBeNull();
      expect(NoneSpaceIdProvider.resolveSpaceId(exchange3)).toBeNull();
    });

    it('should return null for exchange with headers', () => {
      const exchange = createMockExchange({
        headers: {
          'Content-Type': 'application/json',
          Authorization: 'Bearer token',
        },
      });
      expect(NoneSpaceIdProvider.resolveSpaceId(exchange)).toBeNull();
    });

    it('should return null for exchange with body', () => {
      const exchange = createMockExchange({
        method: 'POST',
        body: JSON.stringify({ key: 'value' }),
      });
      expect(NoneSpaceIdProvider.resolveSpaceId(exchange)).toBeNull();
    });

    it('should be a valid SpaceIdProvider', () => {
      const provider: SpaceIdProvider = NoneSpaceIdProvider;
      expect(provider).toBeDefined();
      expect(typeof provider.resolveSpaceId).toBe('function');
    });
  });
});

describe('SpaceIdStorage', () => {
  let spaceIdStorage: SpaceIdStorage;

  beforeEach(() => {
    vi.clearAllMocks();
    mockStorage.getItem.mockReturnValue(null);
    mockStorage.setItem.mockImplementation(() => {});
    mockStorage.removeItem.mockImplementation(() => {});

    spaceIdStorage = new SpaceIdStorage({
      storage: mockStorage as unknown as Storage,
    });
  });

  afterEach(() => {
    if (spaceIdStorage) {
      spaceIdStorage.destroy();
    }
  });

  describe('constructor', () => {
    it('should initialize with default options', () => {
      const storage = new SpaceIdStorage();
      expect(storage).toBeDefined();
      storage.destroy();
    });

    it('should initialize with custom key', () => {
      const customKey = 'custom-space-id-key';
      const storage = new SpaceIdStorage({ key: customKey });
      expect(storage).toBeDefined();
      storage.destroy();
    });

    // Regression test: the default event bus channel must be derived from the
    // actual key. Otherwise storages for different keys share one broadcast
    // channel and cross-contaminate each other's cached value across tabs.
    it('should derive the default event bus channel from the storage key', () => {
      serialBusChannels.length = 0;
      const storage = new SpaceIdStorage({
        key: 'my-space-id',
        storage: mockStorage as any,
      });
      expect(storage).toBeDefined();
      expect(serialBusChannels).toContain('my-space-id');
      expect(serialBusChannels).not.toContain(DEFAULT_COSEC_SPACE_ID_KEY);
      storage.destroy();
    });

    it('should initialize with custom eventBus', () => {
      const mockEventBus = {
        emit: vi.fn().mockResolvedValue(undefined),
        on: vi.fn(),
        off: vi.fn(),
        destroy: vi.fn(),
      };
      const storage = new SpaceIdStorage({ eventBus: mockEventBus as any });
      expect(storage).toBeDefined();
      storage.destroy();
    });

    it('should use DEFAULT_COSEC_SPACE_ID_KEY when no key provided', () => {
      const storage = new SpaceIdStorage();
      expect(storage).toBeDefined();
      storage.destroy();
    });

    it('should initialize with storage option', () => {
      const storage = new SpaceIdStorage({
        storage: mockStorage as unknown as Storage,
      });
      expect(storage).toBeDefined();
    });

    it('should initialize with all custom options', () => {
      const customKey = 'custom-key';
      const mockEventBus = {
        emit: vi.fn().mockResolvedValue(undefined),
        on: vi.fn(),
        off: vi.fn(),
        destroy: vi.fn(),
      };
      const storage = new SpaceIdStorage({
        key: customKey,
        eventBus: mockEventBus as any,
        storage: mockStorage as unknown as Storage,
      });
      expect(storage).toBeDefined();
      storage.destroy();
    });
  });

  describe('get', () => {
    it('should return null when no space ID is stored', () => {
      mockStorage.getItem.mockReturnValue(null);
      const storage = new SpaceIdStorage({
        storage: mockStorage as unknown as Storage,
      });
      const result = storage.get();
      expect(result).toBeNull();
      storage.destroy();
    });

    it('should return stored space ID', () => {
      const storedSpaceId = 'space-12345';
      mockStorage.getItem.mockReturnValue(storedSpaceId);
      const storage = new SpaceIdStorage({
        storage: mockStorage as unknown as Storage,
      });
      const result = storage.get();
      expect(result).toBe(storedSpaceId);
      storage.destroy();
    });

    it('should return space ID from storage without caching issues', () => {
      const storedSpaceId = 'space-abc';
      mockStorage.getItem.mockReturnValue(storedSpaceId);
      const storage = new SpaceIdStorage({
        storage: mockStorage as unknown as Storage,
      });
      const result1 = storage.get();
      const result2 = storage.get();
      expect(result1).toBe(storedSpaceId);
      expect(result2).toBe(storedSpaceId);
      storage.destroy();
    });
  });

  describe('set', () => {
    it('should store space ID in storage', () => {
      const storage = new SpaceIdStorage({
        storage: mockStorage as unknown as Storage,
      });
      const spaceId = 'new-space-id';
      storage.set(spaceId);
      expect(mockStorage.setItem).toHaveBeenCalledWith(
        DEFAULT_COSEC_SPACE_ID_KEY,
        spaceId,
      );
      storage.destroy();
    });

    it('should store custom key when provided', () => {
      const customKey = 'custom-key';
      const storage = new SpaceIdStorage({
        key: customKey,
        storage: mockStorage as unknown as Storage,
      });
      const spaceId = 'space-xyz';
      storage.set(spaceId);
      expect(mockStorage.setItem).toHaveBeenCalledWith(customKey, spaceId);
      storage.destroy();
    });

    it('should be able to update existing space ID', () => {
      const storage = new SpaceIdStorage({
        storage: mockStorage as unknown as Storage,
      });
      const oldSpaceId = 'old-space';
      const newSpaceId = 'new-space';
      storage.set(oldSpaceId);
      storage.set(newSpaceId);
      expect(mockStorage.setItem).toHaveBeenCalledWith(
        DEFAULT_COSEC_SPACE_ID_KEY,
        newSpaceId,
      );
      storage.destroy();
    });

    it('should handle special characters in space ID', () => {
      const storage = new SpaceIdStorage({
        storage: mockStorage as unknown as Storage,
      });
      const specialSpaceId = 'space-with-special-chars_123-456';
      storage.set(specialSpaceId);
      expect(mockStorage.setItem).toHaveBeenCalledWith(
        DEFAULT_COSEC_SPACE_ID_KEY,
        specialSpaceId,
      );
      storage.destroy();
    });

    it('should handle empty string space ID', () => {
      const storage = new SpaceIdStorage({
        storage: mockStorage as unknown as Storage,
      });
      const emptySpaceId = '';
      storage.set(emptySpaceId);
      expect(mockStorage.setItem).toHaveBeenCalledWith(
        DEFAULT_COSEC_SPACE_ID_KEY,
        emptySpaceId,
      );
      storage.destroy();
    });
  });

  describe('remove', () => {
    it('should remove space ID from storage', () => {
      const storage = new SpaceIdStorage({
        storage: mockStorage as unknown as Storage,
      });
      storage.remove();
      expect(mockStorage.removeItem).toHaveBeenCalledWith(
        DEFAULT_COSEC_SPACE_ID_KEY,
      );
      storage.destroy();
    });

    it('should remove with custom key when provided', () => {
      const customKey = 'custom-key';
      const storage = new SpaceIdStorage({
        key: customKey,
        storage: mockStorage as unknown as Storage,
      });
      storage.remove();
      expect(mockStorage.removeItem).toHaveBeenCalledWith(customKey);
      storage.destroy();
    });
  });

  describe('addListener', () => {
    it('should add listener and return remove function', () => {
      const storage = new SpaceIdStorage({
        storage: mockStorage as unknown as Storage,
      });
      const listener = vi.fn();
      const remove = storage.addListener({
        name: 'test-listener',
        handle: listener,
      });
      expect(typeof remove).toBe('function');
      remove();
      storage.destroy();
    });
  });

  describe('destroy', () => {
    it('should destroy without errors', () => {
      const storage = new SpaceIdStorage();
      expect(() => storage.destroy()).not.toThrow();
    });

    it('should remove handler from custom eventBus on destroy', () => {
      const mockEventBus = {
        emit: vi.fn().mockResolvedValue(undefined),
        on: vi.fn(),
        off: vi.fn().mockReturnValue(true),
        destroy: vi.fn(),
      };
      const storage = new SpaceIdStorage({ eventBus: mockEventBus as any });
      storage.destroy();
      expect(mockEventBus.off).toHaveBeenCalled();
    });
  });
});

describe('DefaultSpaceIdProvider', () => {
  let spaceIdStorage: SpaceIdStorage;
  let predicate: SpacedResourcePredicate;
  let provider: DefaultSpaceIdProvider;

  beforeEach(() => {
    vi.clearAllMocks();
    mockStorage.getItem.mockReturnValue(null);
    mockStorage.setItem.mockImplementation(() => {});
    mockStorage.removeItem.mockImplementation(() => {});

    spaceIdStorage = new SpaceIdStorage({
      storage: mockStorage as unknown as Storage,
    });

    predicate = {
      test: vi.fn(),
    };

    const options: SpaceIdProviderOptions = {
      spacedResourcePredicate: predicate,
      spaceIdStorage,
    };
    provider = new DefaultSpaceIdProvider(options);
  });

  afterEach(() => {
    if (spaceIdStorage) {
      spaceIdStorage.destroy();
    }
  });

  describe('constructor', () => {
    it('should initialize with valid options', () => {
      const options: SpaceIdProviderOptions = {
        spacedResourcePredicate: predicate,
        spaceIdStorage,
      };
      const testProvider = new DefaultSpaceIdProvider(options);
      expect(testProvider).toBeDefined();
    });

    it('should be a valid SpaceIdProvider', () => {
      const options: SpaceIdProviderOptions = {
        spacedResourcePredicate: predicate,
        spaceIdStorage,
      };
      const testProvider = new DefaultSpaceIdProvider(options);
      const spaceIdProvider: SpaceIdProvider = testProvider;
      expect(spaceIdProvider).toBeDefined();
      expect(typeof spaceIdProvider.resolveSpaceId).toBe('function');
    });
  });

  describe('resolveSpaceId', () => {
    it('should return null when predicate returns false', () => {
      predicate.test = vi.fn().mockReturnValue(false);
      const exchange = createMockExchange();
      const result = provider.resolveSpaceId(exchange);
      expect(result).toBeNull();
      expect(predicate.test).toHaveBeenCalledWith(exchange);
    });

    it('should return space ID when predicate returns true and storage has value', () => {
      const storedSpaceId = 'space-12345';
      mockStorage.getItem.mockReturnValue(storedSpaceId);
      predicate.test = vi.fn().mockReturnValue(true);

      const exchange = createMockExchange();
      const result = provider.resolveSpaceId(exchange);

      expect(result).toBe(storedSpaceId);
      expect(predicate.test).toHaveBeenCalledWith(exchange);
    });

    it('should return null when predicate returns true but storage is empty', () => {
      mockStorage.getItem.mockReturnValue(null);
      predicate.test = vi.fn().mockReturnValue(true);

      const exchange = createMockExchange();
      const result = provider.resolveSpaceId(exchange);

      expect(result).toBeNull();
    });
  });

  describe('resolveSpaceId with various exchange configurations', () => {
    it('should handle GET requests', () => {
      mockStorage.getItem.mockReturnValue('space-get');
      predicate.test = vi.fn().mockReturnValue(true);
      const exchange = createMockExchange({ method: 'GET' });
      expect(provider.resolveSpaceId(exchange)).toBe('space-get');
    });

    it('should handle POST requests', () => {
      mockStorage.getItem.mockReturnValue('space-post');
      predicate.test = vi.fn().mockReturnValue(true);
      const exchange = createMockExchange({ method: 'POST' });
      expect(provider.resolveSpaceId(exchange)).toBe('space-post');
    });

    it('should handle PUT requests', () => {
      mockStorage.getItem.mockReturnValue('space-put');
      predicate.test = vi.fn().mockReturnValue(true);
      const exchange = createMockExchange({ method: 'PUT' });
      expect(provider.resolveSpaceId(exchange)).toBe('space-put');
    });

    it('should handle DELETE requests', () => {
      mockStorage.getItem.mockReturnValue('space-delete');
      predicate.test = vi.fn().mockReturnValue(true);
      const exchange = createMockExchange({ method: 'DELETE' });
      expect(provider.resolveSpaceId(exchange)).toBe('space-delete');
    });

    it('should handle requests with various URLs', () => {
      mockStorage.getItem.mockReturnValue('space-api');
      predicate.test = vi.fn().mockReturnValue(true);

      const urls = [
        'https://api.example.com/spaces',
        'https://api.example.com/spaces/123',
        'https://api.example.com/spaces/123/items',
        'https://different-domain.com/api',
      ];

      for (const url of urls) {
        const exchange = createMockExchange({ url });
        const result = provider.resolveSpaceId(exchange);
        expect(result).toBe('space-api');
      }
    });

    it('should handle requests with different headers', () => {
      mockStorage.getItem.mockReturnValue('space-auth');
      predicate.test = vi.fn().mockReturnValue(true);

      const exchangeWithAuth = createMockExchange({
        headers: { Authorization: 'Bearer token' },
      });
      const exchangeWithContentType = createMockExchange({
        headers: { 'Content-Type': 'application/json' },
      });

      expect(provider.resolveSpaceId(exchangeWithAuth)).toBe('space-auth');
      expect(provider.resolveSpaceId(exchangeWithContentType)).toBe(
        'space-auth',
      );
    });

    it('should handle requests with body', () => {
      mockStorage.getItem.mockReturnValue('space-body');
      predicate.test = vi.fn().mockReturnValue(true);

      const exchange = createMockExchange({
        method: 'POST',
        body: JSON.stringify({ data: 'test' }),
      });

      expect(provider.resolveSpaceId(exchange)).toBe('space-body');
    });

    it('should handle requests with URL parameters', () => {
      mockStorage.getItem.mockReturnValue('space-params');
      predicate.test = vi.fn().mockReturnValue(true);

      const exchange = createMockExchange({
        urlParams: { path: { id: '123' }, query: { page: '1' } },
      });

      expect(provider.resolveSpaceId(exchange)).toBe('space-params');
    });
  });

  describe('predicate behavior', () => {
    it('should call predicate with correct exchange object', () => {
      const testExchange = createMockExchange();
      predicate.test = vi.fn().mockReturnValue(false);
      provider.resolveSpaceId(testExchange);
      expect(predicate.test).toHaveBeenCalledWith(testExchange);
    });

    it('should only call predicate once per resolveSpaceId call', () => {
      predicate.test = vi.fn().mockReturnValue(true);
      mockStorage.getItem.mockReturnValue('space-once');
      const exchange = createMockExchange();
      provider.resolveSpaceId(exchange);
      expect(predicate.test).toHaveBeenCalledTimes(1);
    });
  });

  describe('storage interaction', () => {
    it('should get space ID from storage exactly once per resolveSpaceId call', () => {
      mockStorage.getItem.mockReturnValue('space-once');
      predicate.test = vi.fn().mockReturnValue(true);

      const exchange = createMockExchange();
      provider.resolveSpaceId(exchange);

      expect(mockStorage.getItem).toHaveBeenCalledTimes(1);
    });

    it('should not access storage when predicate returns false', () => {
      predicate.test = vi.fn().mockReturnValue(false);

      const exchange = createMockExchange();
      provider.resolveSpaceId(exchange);

      expect(mockStorage.getItem).not.toHaveBeenCalled();
    });
  });

  describe('edge cases', () => {
    it('should handle very long space IDs', () => {
      const longSpaceId = 'a'.repeat(10000);
      mockStorage.getItem.mockReturnValue(longSpaceId);
      predicate.test = vi.fn().mockReturnValue(true);

      const testProvider = new DefaultSpaceIdProvider({
        spacedResourcePredicate: predicate,
        spaceIdStorage,
      });

      const exchange = createMockExchange();
      const result = testProvider.resolveSpaceId(exchange);

      expect(result).toBe(longSpaceId);
      expect(result?.length).toBe(10000);
    });

    it('should handle space IDs with unicode characters', () => {
      const unicodeSpaceId = '空间-标识-123';
      mockStorage.getItem.mockReturnValue(unicodeSpaceId);
      predicate.test = vi.fn().mockReturnValue(true);

      const testProvider = new DefaultSpaceIdProvider({
        spacedResourcePredicate: predicate,
        spaceIdStorage,
      });

      const exchange = createMockExchange();
      const result = testProvider.resolveSpaceId(exchange);

      expect(result).toBe(unicodeSpaceId);
    });

    it('should handle space IDs with UUID format', () => {
      const uuidSpaceId = '550e8400-e29b-41d4-a716-446655440000';
      mockStorage.getItem.mockReturnValue(uuidSpaceId);
      predicate.test = vi.fn().mockReturnValue(true);

      const testProvider = new DefaultSpaceIdProvider({
        spacedResourcePredicate: predicate,
        spaceIdStorage,
      });

      const exchange = createMockExchange();
      const result = testProvider.resolveSpaceId(exchange);

      expect(result).toBe(uuidSpaceId);
    });

    it('should handle numeric-looking space IDs', () => {
      const numericSpaceId = '12345678901234567890';
      mockStorage.getItem.mockReturnValue(numericSpaceId);
      predicate.test = vi.fn().mockReturnValue(true);

      const testProvider = new DefaultSpaceIdProvider({
        spacedResourcePredicate: predicate,
        spaceIdStorage,
      });

      const exchange = createMockExchange();
      const result = testProvider.resolveSpaceId(exchange);

      expect(result).toBe(numericSpaceId);
      expect(result).not.toBe(12345678901234567890n);
    });

    it('should handle storage returning undefined explicitly', () => {
      mockStorage.getItem.mockReturnValue(undefined);
      predicate.test = vi.fn().mockReturnValue(true);

      const testProvider = new DefaultSpaceIdProvider({
        spacedResourcePredicate: predicate,
        spaceIdStorage,
      });

      const exchange = createMockExchange();
      const result = testProvider.resolveSpaceId(exchange);

      expect(result).toBeNull();
    });
  });
});

describe('SpaceIdProvider interface compliance', () => {
  it('NoneSpaceIdProvider should satisfy SpaceIdProvider interface', () => {
    const provider: SpaceIdProvider = NoneSpaceIdProvider;
    expect(provider.resolveSpaceId).toBeDefined();
    expect(typeof provider.resolveSpaceId).toBe('function');
  });

  it('DefaultSpaceIdProvider instance should satisfy SpaceIdProvider interface', () => {
    const mockEventBus = {
      emit: vi.fn().mockResolvedValue(undefined),
      on: vi.fn(),
      off: vi.fn(),
      destroy: vi.fn(),
    };
    const storage = new SpaceIdStorage({ eventBus: mockEventBus as any });
    const testPredicate: SpacedResourcePredicate = {
      test: () => false,
    };
    const provider = new DefaultSpaceIdProvider({
      spacedResourcePredicate: testPredicate,
      spaceIdStorage: storage,
    });
    const spaceIdProvider: SpaceIdProvider = provider;
    expect(spaceIdProvider.resolveSpaceId).toBeDefined();
    expect(typeof spaceIdProvider.resolveSpaceId).toBe('function');
    storage.destroy();
  });
});

describe('SpaceIdStorageOptions interface', () => {
  it('should accept partial KeyStorage options', () => {
    const storage = new SpaceIdStorage({
      key: 'custom-key',
      storage: mockStorage as unknown as Storage,
    });
    expect(storage).toBeDefined();
    storage.destroy();
  });
});
