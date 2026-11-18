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

import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { DeviceIdStorage, TokenStorage } from '../src';
import { Fetcher } from '@ahoo-wang/fetcher';
import type { TokenRefresher } from '../src';
import { CoSecConfigurer } from '../src';

describe('CoSecConfigurer', () => {
  let fetcher: Fetcher;
  let tokenRefresher: TokenRefresher;
  let configurer: CoSecConfigurer;

  beforeEach(() => {
    fetcher = new Fetcher({
      baseURL: 'https://api.example.com',
    });

    tokenRefresher = {
      refresh: vi.fn(),
    };

    configurer = new CoSecConfigurer({
      tokenRefresher,
    });
  });

  describe('constructor', () => {
    it('should create instance with required config', () => {
      expect(configurer).toBeDefined();
      expect(configurer.tokenStorage).toBeDefined();
      expect(configurer.deviceIdStorage).toBeDefined();
      expect(configurer.tokenManager).toBeDefined();
    });

    it('should create instance without tokenRefresher', () => {
      const configurerWithoutAuth = new CoSecConfigurer({});

      expect(configurerWithoutAuth).toBeDefined();
      expect(configurerWithoutAuth.tokenStorage).toBeDefined();
      expect(configurerWithoutAuth.deviceIdStorage).toBeDefined();
      expect(configurerWithoutAuth.tokenManager).toBeUndefined();
    });

    it('should use custom storage implementations', () => {
      const customTokenStorage = {
        get: vi.fn(),
        set: vi.fn(),
        remove: vi.fn(),
        exists: vi.fn(),
      };
      const customDeviceStorage = {
        get: vi.fn(),
        set: vi.fn(),
        getOrCreate: vi.fn(),
        clear: vi.fn(),
        generateDeviceId: vi.fn(),
      };

      const configurerWithCustom = new CoSecConfigurer({
        tokenStorage: customTokenStorage as unknown as TokenStorage,
        deviceIdStorage: customDeviceStorage as unknown as DeviceIdStorage,
        tokenRefresher,
      });

      expect(configurerWithCustom.tokenStorage).toBe(customTokenStorage);
      expect(configurerWithCustom.deviceIdStorage).toBe(customDeviceStorage);
    });
  });

  describe('applyTo', () => {
    it('should add request and response interceptors', () => {
      const initialRequestCount =
        fetcher.interceptors.request.interceptors.length;
      const initialResponseCount =
        fetcher.interceptors.response.interceptors.length;

      configurer.applyTo(fetcher);

      expect(fetcher.interceptors.request.interceptors.length).toBe(
        initialRequestCount + 3, // CoSecRequest + ResourceAttribution + Authorization
      );
      expect(fetcher.interceptors.response.interceptors.length).toBe(
        initialResponseCount + 1, // AuthorizationResponse
      );
    });

    it('should add interceptors with correct names', () => {
      configurer.applyTo(fetcher);

      const requestInterceptors = fetcher.interceptors.request.interceptors;
      const responseInterceptors = fetcher.interceptors.response.interceptors;

      const requestNames = requestInterceptors.map(i => i.name);
      const responseNames = responseInterceptors.map(i => i.name);

      expect(requestNames).toContain('CoSecRequestInterceptor');
      expect(requestNames).toContain('AuthorizationRequestInterceptor');
      expect(requestNames).toContain('ResourceAttributionRequestInterceptor');

      expect(responseNames).toContain('AuthorizationResponseInterceptor');
    });

    it('should add only basic interceptors without tokenRefresher', () => {
      const configurerBasic = new CoSecConfigurer({});

      const basicFetcher = new Fetcher();
      const initialRequestCount =
        basicFetcher.interceptors.request.interceptors.length;
      const initialResponseCount =
        basicFetcher.interceptors.response.interceptors.length;

      configurerBasic.applyTo(basicFetcher);

      expect(basicFetcher.interceptors.request.interceptors.length).toBe(
        initialRequestCount + 2, // CoSecRequest + ResourceAttribution (no Authorization)
      );
      expect(basicFetcher.interceptors.response.interceptors.length).toBe(
        initialResponseCount, // No AuthorizationResponse
      );

      const requestNames = basicFetcher.interceptors.request.interceptors.map(
        i => i.name,
      );
      expect(requestNames).toContain('CoSecRequestInterceptor');
      expect(requestNames).toContain('ResourceAttributionRequestInterceptor');
      expect(requestNames).not.toContain('AuthorizationRequestInterceptor');
    });

    it('should conditionally add error interceptors', () => {
      // Test with unauthorized handler only
      const configurerWithUnauthorized = new CoSecConfigurer({
        tokenRefresher,
        onUnauthorized: vi.fn(),
      });

      const fetcher1 = new Fetcher();
      const initialErrorCount1 =
        fetcher1.interceptors.error.interceptors.length;
      configurerWithUnauthorized.applyTo(fetcher1);

      expect(fetcher1.interceptors.error.interceptors.length).toBe(
        initialErrorCount1 + 1,
      );

      // Test with forbidden handler only
      const configurerWithForbidden = new CoSecConfigurer({
        tokenRefresher,
        onForbidden: vi.fn(),
      });

      const fetcher2 = new Fetcher();
      const initialErrorCount2 =
        fetcher2.interceptors.error.interceptors.length;
      configurerWithForbidden.applyTo(fetcher2);

      expect(fetcher2.interceptors.error.interceptors.length).toBe(
        initialErrorCount2 + 1,
      );

      // Test with both handlers
      const configurerWithBoth = new CoSecConfigurer({
        tokenRefresher,
        onUnauthorized: vi.fn(),
        onForbidden: vi.fn(),
      });

      const fetcher3 = new Fetcher();
      const initialErrorCount3 =
        fetcher3.interceptors.error.interceptors.length;
      configurerWithBoth.applyTo(fetcher3);

      expect(fetcher3.interceptors.error.interceptors.length).toBe(
        initialErrorCount3 + 2,
      );

      // Test with no error handlers
      const configurerWithNone = new CoSecConfigurer({
        tokenRefresher,
      });

      const fetcher4 = new Fetcher();
      const initialErrorCount4 =
        fetcher4.interceptors.error.interceptors.length;
      configurerWithNone.applyTo(fetcher4);

      expect(fetcher4.interceptors.error.interceptors.length).toBe(
        initialErrorCount4,
      );
    });
  });

  describe('properties', () => {
    it('should expose token storage instance', () => {
      const storage = configurer.tokenStorage;
      expect(storage).toBeDefined();
      expect(typeof storage.get).toBe('function');
    });

    it('should expose device ID storage instance', () => {
      const storage = configurer.deviceIdStorage;
      expect(storage).toBeDefined();
      expect(typeof storage.getOrCreate).toBe('function');
    });

    it('should expose token manager instance', () => {
      const manager = configurer.tokenManager;
      expect(manager).toBeDefined();
      expect(manager!.currentToken).toBeNull();
    });

    it('should have undefined tokenManager when no tokenRefresher provided', () => {
      const configurerBasic = new CoSecConfigurer({});

      expect(configurerBasic.tokenManager).toBeUndefined();
    });
  });
});
