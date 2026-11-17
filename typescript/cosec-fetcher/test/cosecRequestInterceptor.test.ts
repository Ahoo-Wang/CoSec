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

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  CoSecRequestInterceptor,
  COSEC_REQUEST_INTERCEPTOR_NAME,
  COSEC_REQUEST_INTERCEPTOR_ORDER,
} from '../src';
import { CoSecHeaders } from '../src';
import type { FetchExchange } from '@ahoo-wang/fetcher';
import { DeviceIdStorage } from '../src';
import type { SpaceIdProvider } from '../src/spaceIdProvider';
import { NoneSpaceIdProvider, SpaceIdStorage } from '../src/spaceIdProvider';
import { idGenerator } from '../src/idGenerator';

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
  },
  nameGenerator: {
    generate: vi.fn((prefix: string) => `${prefix}_1`),
  },
}));

vi.mock('../src/idGenerator', () => ({
  idGenerator: {
    generateId: vi.fn(() => 'test-request-id-12345'),
  },
}));

class MockFetchExchange implements Partial<FetchExchange> {
  public requestHeaders: Record<string, string> = {};
  public attributes: Map<string, any> = new Map();

  ensureRequestHeaders(): Record<string, string> {
    return this.requestHeaders;
  }
}

// CoSec 8.0 does not read this header: the app is the token's `aud`.
const APP_ID_HEADER = 'CoSec-App-Id';

describe('CoSecRequestInterceptor', () => {
  let mockDeviceIdStorage: DeviceIdStorage;
  let interceptor: CoSecRequestInterceptor;
  let mockExchange: MockFetchExchange;

  beforeEach(() => {
    vi.clearAllMocks();
    mockStorage.getItem.mockReturnValue(null);
    mockStorage.setItem.mockImplementation(() => {});
    mockStorage.removeItem.mockImplementation(() => {});

    mockDeviceIdStorage = new DeviceIdStorage({
      storage: mockStorage as unknown as Storage,
    });

    mockExchange = new MockFetchExchange();
  });

  afterEach(() => {
    mockDeviceIdStorage.destroy();
  });

  describe('constructor', () => {
    it('should have correct name', () => {
      interceptor = new CoSecRequestInterceptor({
        appId: 'test-app-id',
        deviceIdStorage: mockDeviceIdStorage,
      });
      expect(interceptor.name).toBe(COSEC_REQUEST_INTERCEPTOR_NAME);
    });

    it('should have correct order', () => {
      interceptor = new CoSecRequestInterceptor({
        appId: 'test-app-id',
        deviceIdStorage: mockDeviceIdStorage,
      });
      expect(interceptor.order).toBe(COSEC_REQUEST_INTERCEPTOR_ORDER);
    });

    it('should have order value calculated from Number.MIN_SAFE_INTEGER', () => {
      interceptor = new CoSecRequestInterceptor({
        appId: 'test-app-id',
        deviceIdStorage: mockDeviceIdStorage,
      });
      expect(interceptor.order).toBe(Number.MIN_SAFE_INTEGER + 1000);
    });

    it('should use provided appId', () => {
      const customAppId = 'custom-app-123';
      interceptor = new CoSecRequestInterceptor({
        appId: customAppId,
        deviceIdStorage: mockDeviceIdStorage,
      });
      expect(interceptor).toBeDefined();
    });

    it('should use provided deviceIdStorage', () => {
      const customDeviceIdStorage = new DeviceIdStorage({
        storage: mockStorage as unknown as Storage,
      });
      interceptor = new CoSecRequestInterceptor({
        appId: 'test-app',
        deviceIdStorage: customDeviceIdStorage,
      });
      expect(interceptor).toBeDefined();
      customDeviceIdStorage.destroy();
    });

    it('should use NoneSpaceIdProvider when spaceIdProvider not provided', () => {
      interceptor = new CoSecRequestInterceptor({
        appId: 'test-app',
        deviceIdStorage: mockDeviceIdStorage,
      });
      expect(interceptor).toBeDefined();
    });

    it('should use provided spaceIdProvider', () => {
      const mockSpaceIdProvider: SpaceIdProvider = {
        resolveSpaceId: () => 'test-space-id',
      };
      interceptor = new CoSecRequestInterceptor({
        appId: 'test-app',
        deviceIdStorage: mockDeviceIdStorage,
        spaceIdProvider: mockSpaceIdProvider,
      });
      expect(interceptor).toBeDefined();
    });
  });

  describe('intercept', () => {
    beforeEach(() => {
      interceptor = new CoSecRequestInterceptor({
        appId: 'test-app-id',
        deviceIdStorage: mockDeviceIdStorage,
      });
    });

    it('should add CoSec-Device-Id header', async () => {
      mockStorage.getItem.mockReturnValue('existing-device-id');

      await interceptor.intercept(mockExchange as unknown as FetchExchange);

      expect(mockExchange.requestHeaders[CoSecHeaders.DEVICE_ID]).toBe(
        'existing-device-id',
      );
    });

    it('should generate new device ID when none exists', async () => {
      mockStorage.getItem.mockReturnValue(null);

      await interceptor.intercept(mockExchange as unknown as FetchExchange);

      expect(mockExchange.requestHeaders[CoSecHeaders.DEVICE_ID]).toBeDefined();
    });

    it('should not send a CoSec-App-Id header (CoSec 8.0 does not read it)', async () => {
      await interceptor.intercept(mockExchange as unknown as FetchExchange);

      expect(mockExchange.requestHeaders[APP_ID_HEADER]).toBeUndefined();
    });

    it('should add CoSec-Request-Id header', async () => {
      await interceptor.intercept(mockExchange as unknown as FetchExchange);

      expect(mockExchange.requestHeaders[CoSecHeaders.REQUEST_ID]).toBe(
        'test-request-id-12345',
      );
    });

    it('should add CoSec-Space-Id header when spaceIdProvider returns value', async () => {
      const mockSpaceIdProvider: SpaceIdProvider = {
        resolveSpaceId: () => 'test-space-123',
      };
      const interceptorWithSpace = new CoSecRequestInterceptor({
        appId: 'test-app',
        deviceIdStorage: mockDeviceIdStorage,
        spaceIdProvider: mockSpaceIdProvider,
      });

      await interceptorWithSpace.intercept(
        mockExchange as unknown as FetchExchange,
      );

      expect(mockExchange.requestHeaders[CoSecHeaders.SPACE_ID]).toBe(
        'test-space-123',
      );
    });

    it('should not add CoSec-Space-Id header when spaceIdProvider returns null', async () => {
      const mockSpaceIdProvider: SpaceIdProvider = {
        resolveSpaceId: () => null,
      };
      const interceptorWithSpace = new CoSecRequestInterceptor({
        appId: 'test-app',
        deviceIdStorage: mockDeviceIdStorage,
        spaceIdProvider: mockSpaceIdProvider,
      });

      await interceptorWithSpace.intercept(
        mockExchange as unknown as FetchExchange,
      );

      expect(
        mockExchange.requestHeaders[CoSecHeaders.SPACE_ID],
      ).toBeUndefined();
    });

    it('should not add CoSec-Space-Id header when using NoneSpaceIdProvider', async () => {
      await interceptor.intercept(mockExchange as unknown as FetchExchange);

      expect(
        mockExchange.requestHeaders[CoSecHeaders.SPACE_ID],
      ).toBeUndefined();
    });
  });

  describe('intercept with existing headers', () => {
    beforeEach(() => {
      interceptor = new CoSecRequestInterceptor({
        appId: 'test-app-id',
        deviceIdStorage: mockDeviceIdStorage,
      });
    });

    it('should preserve existing headers when adding CoSec headers', async () => {
      mockExchange.requestHeaders['Content-Type'] = 'application/json';
      mockExchange.requestHeaders['Accept'] = 'application/json';

      await interceptor.intercept(mockExchange as unknown as FetchExchange);

      expect(mockExchange.requestHeaders['Content-Type']).toBe(
        'application/json',
      );
      expect(mockExchange.requestHeaders['Accept']).toBe('application/json');
      expect(mockExchange.requestHeaders[APP_ID_HEADER]).toBeUndefined();
      expect(mockExchange.requestHeaders[CoSecHeaders.DEVICE_ID]).toBeDefined();
      expect(
        mockExchange.requestHeaders[CoSecHeaders.REQUEST_ID],
      ).toBeDefined();
    });

    it('should overwrite existing CoSec headers', async () => {
      mockExchange.requestHeaders[CoSecHeaders.DEVICE_ID] = 'old-device-id';
      mockExchange.requestHeaders[CoSecHeaders.REQUEST_ID] = 'old-request-id';

      await interceptor.intercept(mockExchange as unknown as FetchExchange);

      expect(mockExchange.requestHeaders[APP_ID_HEADER]).toBeUndefined();
      expect(mockExchange.requestHeaders[CoSecHeaders.REQUEST_ID]).toBe(
        'test-request-id-12345',
      );
    });
  });

  describe('intercept multiple times', () => {
    it('should generate unique request IDs for each request', async () => {
      // Use the module-level vi.mock (not a hand-rolled save/restore that
      // leaks if the test fails). restoreMocks config resets after the test.
      let callCount = 0;
      vi.mocked(idGenerator.generateId).mockImplementation(() => {
        callCount++;
        return `request-id-${callCount}`;
      });

      const testInterceptor = new CoSecRequestInterceptor({
        appId: 'test-app-id',
        deviceIdStorage: mockDeviceIdStorage,
      });

      await testInterceptor.intercept(mockExchange as unknown as FetchExchange);
      const firstRequestId =
        mockExchange.requestHeaders[CoSecHeaders.REQUEST_ID];

      await testInterceptor.intercept(mockExchange as unknown as FetchExchange);
      const secondRequestId =
        mockExchange.requestHeaders[CoSecHeaders.REQUEST_ID];

      expect(firstRequestId).not.toBe(secondRequestId);
    });

    it('should use same device ID for multiple requests', async () => {
      mockStorage.getItem.mockReturnValue('persistent-device-id');

      const testInterceptor = new CoSecRequestInterceptor({
        appId: 'test-app-id',
        deviceIdStorage: mockDeviceIdStorage,
      });

      await testInterceptor.intercept(mockExchange as unknown as FetchExchange);
      const firstDeviceId = mockExchange.requestHeaders[CoSecHeaders.DEVICE_ID];

      await testInterceptor.intercept(mockExchange as unknown as FetchExchange);
      const secondDeviceId =
        mockExchange.requestHeaders[CoSecHeaders.DEVICE_ID];

      expect(firstDeviceId).toBe(secondDeviceId);
    });
  });

  describe('intercept with exchange without existing headers object', () => {
    it('should create request headers object when not present', async () => {
      const exchangeWithoutHeaders = {
        ensureRequestHeaders: vi.fn().mockReturnValue({}),
        attributes: new Map<string, any>(),
      } as unknown as FetchExchange;

      interceptor = new CoSecRequestInterceptor({
        appId: 'test-app-id',
        deviceIdStorage: mockDeviceIdStorage,
      });

      await interceptor.intercept(exchangeWithoutHeaders);

      expect(exchangeWithoutHeaders.ensureRequestHeaders).toHaveBeenCalled();
      expect(exchangeWithoutHeaders.ensureRequestHeaders()).not.toHaveProperty(
        APP_ID_HEADER,
      );
      expect(exchangeWithoutHeaders.ensureRequestHeaders()).toHaveProperty(
        CoSecHeaders.DEVICE_ID,
      );
      expect(exchangeWithoutHeaders.ensureRequestHeaders()).toHaveProperty(
        CoSecHeaders.REQUEST_ID,
      );
    });
  });

  describe('edge cases', () => {
    it('should ignore an empty appId', async () => {
      const interceptorWithEmptyAppId = new CoSecRequestInterceptor({
        appId: '',
        deviceIdStorage: mockDeviceIdStorage,
      });

      await interceptorWithEmptyAppId.intercept(
        mockExchange as unknown as FetchExchange,
      );

      expect(mockExchange.requestHeaders[APP_ID_HEADER]).toBeUndefined();
    });

    it('should ignore an appId, which CoSec 8.0 does not read', async () => {
      const interceptorWithSpecialAppId = new CoSecRequestInterceptor({
        appId: 'app-with-special-chars_123-456',
        deviceIdStorage: mockDeviceIdStorage,
      });

      await interceptorWithSpecialAppId.intercept(
        mockExchange as unknown as FetchExchange,
      );

      expect(mockExchange.requestHeaders[APP_ID_HEADER]).toBeUndefined();
    });

    it('should handle unicode characters in space ID', async () => {
      const mockSpaceIdProvider: SpaceIdProvider = {
        resolveSpaceId: () => '空间-标识-123',
      };
      const interceptorWithUnicodeSpace = new CoSecRequestInterceptor({
        appId: 'test-app',
        deviceIdStorage: mockDeviceIdStorage,
        spaceIdProvider: mockSpaceIdProvider,
      });

      await interceptorWithUnicodeSpace.intercept(
        mockExchange as unknown as FetchExchange,
      );

      expect(mockExchange.requestHeaders[CoSecHeaders.SPACE_ID]).toBe(
        '空间-标识-123',
      );
    });

    it('should handle very long request ID', async () => {
      const longRequestId = 'a'.repeat(100);
      vi.mocked(idGenerator.generateId).mockReturnValue(longRequestId);

      const interceptorWithLongId = new CoSecRequestInterceptor({
        appId: 'test-app',
        deviceIdStorage: mockDeviceIdStorage,
      });

      await interceptorWithLongId.intercept(
        mockExchange as unknown as FetchExchange,
      );

      expect(mockExchange.requestHeaders[CoSecHeaders.REQUEST_ID]).toBe(
        longRequestId,
      );
      expect(mockExchange.requestHeaders[CoSecHeaders.REQUEST_ID].length).toBe(
        100,
      );
    });

    it('should handle all HTTP methods', async () => {
      const methods = [
        'GET',
        'POST',
        'PUT',
        'DELETE',
        'PATCH',
        'HEAD',
        'OPTIONS',
      ];

      const testInterceptor = new CoSecRequestInterceptor({
        appId: 'test-app-id',
        deviceIdStorage: mockDeviceIdStorage,
      });

      for (const method of methods) {
        const exchangeForMethod = new MockFetchExchange();
        (exchangeForMethod as any).request = {
          method,
          url: 'https://api.example.com/test',
        };

        await testInterceptor.intercept(
          exchangeForMethod as unknown as FetchExchange,
        );

        expect(exchangeForMethod.requestHeaders[APP_ID_HEADER]).toBeUndefined();
        expect(
          exchangeForMethod.requestHeaders[CoSecHeaders.DEVICE_ID],
        ).toBeDefined();
        expect(
          exchangeForMethod.requestHeaders[CoSecHeaders.REQUEST_ID],
        ).toBeDefined();
      }
    });

    it('should handle different URL patterns', async () => {
      const urls = [
        'https://api.example.com/resource',
        'https://api.example.com/resource/123',
        'https://api.example.com/resource/123/subresource',
        'https://different-domain.com/api',
        'http://localhost:3000/api',
      ];

      const testInterceptor = new CoSecRequestInterceptor({
        appId: 'test-app-id',
        deviceIdStorage: mockDeviceIdStorage,
      });

      for (const url of urls) {
        const exchangeForUrl = new MockFetchExchange();
        (exchangeForUrl as any).request = { method: 'GET', url };

        await testInterceptor.intercept(
          exchangeForUrl as unknown as FetchExchange,
        );

        expect(exchangeForUrl.requestHeaders[APP_ID_HEADER]).toBeUndefined();
        expect(
          exchangeForUrl.requestHeaders[CoSecHeaders.DEVICE_ID],
        ).toBeDefined();
        expect(
          exchangeForUrl.requestHeaders[CoSecHeaders.REQUEST_ID],
        ).toBeDefined();
      }
    });
  });

  describe('spaceIdProvider interaction', () => {
    it('should call spaceIdProvider.resolveSpaceId with exchange', async () => {
      const mockResolveSpaceId = vi
        .fn<() => string | null>()
        .mockReturnValue('resolved-space-id');
      const mockSpaceIdProvider: SpaceIdProvider = {
        resolveSpaceId: mockResolveSpaceId,
      };
      const interceptorWithProvider = new CoSecRequestInterceptor({
        appId: 'test-app',
        deviceIdStorage: mockDeviceIdStorage,
        spaceIdProvider: mockSpaceIdProvider,
      });

      await interceptorWithProvider.intercept(
        mockExchange as unknown as FetchExchange,
      );

      expect(mockResolveSpaceId).toHaveBeenCalledWith(mockExchange);
    });

    it('should call spaceIdProvider.resolveSpaceId only once', async () => {
      const mockResolveSpaceId = vi
        .fn<() => string | null>()
        .mockReturnValue('resolved-space-id');
      const mockSpaceIdProvider: SpaceIdProvider = {
        resolveSpaceId: mockResolveSpaceId,
      };
      const interceptorWithProvider = new CoSecRequestInterceptor({
        appId: 'test-app',
        deviceIdStorage: mockDeviceIdStorage,
        spaceIdProvider: mockSpaceIdProvider,
      });

      await interceptorWithProvider.intercept(
        mockExchange as unknown as FetchExchange,
      );

      expect(mockResolveSpaceId).toHaveBeenCalledTimes(1);
    });

    it('should not call spaceIdProvider when using NoneSpaceIdProvider', async () => {
      const interceptorWithNoneProvider = new CoSecRequestInterceptor({
        appId: 'test-app',
        deviceIdStorage: mockDeviceIdStorage,
      });

      await interceptorWithNoneProvider.intercept(
        mockExchange as unknown as FetchExchange,
      );

      expect(
        mockExchange.requestHeaders[CoSecHeaders.SPACE_ID],
      ).toBeUndefined();
    });
  });

  describe('deviceIdStorage interaction', () => {
    it('should call deviceIdStorage.getOrCreate exactly once', async () => {
      mockStorage.getItem.mockReturnValue('stored-device-id');

      interceptor = new CoSecRequestInterceptor({
        appId: 'test-app-id',
        deviceIdStorage: mockDeviceIdStorage,
      });

      await interceptor.intercept(mockExchange as unknown as FetchExchange);

      expect(mockStorage.getItem).toHaveBeenCalled();
    });

    it('should store new device ID when none exists', async () => {
      mockStorage.getItem.mockReturnValue(null);

      interceptor = new CoSecRequestInterceptor({
        appId: 'test-app-id',
        deviceIdStorage: mockDeviceIdStorage,
      });

      await interceptor.intercept(mockExchange as unknown as FetchExchange);

      expect(mockStorage.setItem).toHaveBeenCalled();
    });
  });

  describe('CoSecHeaders constants', () => {
    it('should have correct header names', () => {
      expect('APP_ID' in CoSecHeaders).toBe(false);
      expect(CoSecHeaders.DEVICE_ID).toBe('CoSec-Device-Id');
      expect(CoSecHeaders.SPACE_ID).toBe('CoSec-Space-Id');
      expect(CoSecHeaders.REQUEST_ID).toBe('CoSec-Request-Id');
    });
  });
});
