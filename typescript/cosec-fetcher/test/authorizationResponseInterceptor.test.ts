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
import {
  AuthorizationResponseInterceptor,
  AUTHORIZATION_RESPONSE_INTERCEPTOR_NAME,
  AUTHORIZATION_RESPONSE_INTERCEPTOR_ORDER,
  RefreshTokenError,
} from '../src';
import { ResponseCodes, type CoSecOptions } from '../src';
import type { FetchExchange } from '@ahoo-wang/fetcher';
import { InterceptorRegistry, type Fetcher } from '@ahoo-wang/fetcher';
import { JwtTokenManager } from '../src';
import type { TokenStorage } from '../src';
import type { TokenRefresher } from '../src';

describe('AuthorizationResponseInterceptor', () => {
  let mockFetcher: Fetcher;
  let mockTokenStorage: TokenStorage;
  let mockTokenRefresher: TokenRefresher;
  let mockJwtTokenManager: JwtTokenManager;
  let coSecOptions: CoSecOptions;
  let interceptor: AuthorizationResponseInterceptor;

  beforeEach(() => {
    mockFetcher = {
      interceptors: {
        request: { intercept: vi.fn() },
        response: new InterceptorRegistry(),
      },
    } as unknown as Fetcher;

    mockTokenStorage = {
      get: vi.fn(),
      set: vi.fn().mockImplementation(token => {
        mockTokenStorage.get = vi.fn().mockReturnValue(token);
      }),
      remove: vi.fn(),
      // The stored value is whatever `get` currently answers.
      reload: vi.fn(() => mockTokenStorage.get()),
    } as unknown as TokenStorage;

    mockTokenRefresher = {
      refresh: vi.fn(),
    } as unknown as TokenRefresher;

    mockJwtTokenManager = new JwtTokenManager(
      mockTokenStorage,
      mockTokenRefresher,
    );
    // 使用 vi.spyOn 替代直接定义属性
    const isRefreshableSpy = vi
      .spyOn(mockJwtTokenManager, 'isRefreshable', 'get')
      .mockReturnValue(true);

    coSecOptions = {
      appId: 'test-app-id',
      deviceIdStorage: {} as any,
      tokenManager: mockJwtTokenManager,
    } as CoSecOptions;

    interceptor = new AuthorizationResponseInterceptor(coSecOptions);
  });

  it('should have correct name and order', () => {
    expect(interceptor.name).toBe(AUTHORIZATION_RESPONSE_INTERCEPTOR_NAME);
    expect(interceptor.order).toBe(AUTHORIZATION_RESPONSE_INTERCEPTOR_ORDER);
  });

  it('should not intercept when there is no response', async () => {
    const exchange = {
      response: undefined,
    } as unknown as FetchExchange;

    await interceptor.intercept(exchange);

    expect(mockTokenRefresher.refresh).not.toHaveBeenCalled();
    expect(mockFetcher.interceptors.request.intercept).not.toHaveBeenCalled();
  });

  it('should not intercept when response status is not 401', async () => {
    const exchange = {
      response: {
        status: 200,
      } as Response,
    } as unknown as FetchExchange;

    await interceptor.intercept(exchange);

    expect(mockTokenRefresher.refresh).not.toHaveBeenCalled();
    expect(mockFetcher.interceptors.request.intercept).not.toHaveBeenCalled();
  });

  it('should not intercept when token is not refreshable', async () => {
    // Mock isRefreshable to return false using spyOn
    const isRefreshableSpy = vi
      .spyOn(mockJwtTokenManager, 'isRefreshable', 'get')
      .mockReturnValue(false);

    const exchange = {
      response: {
        status: ResponseCodes.UNAUTHORIZED,
      } as Response,
    } as unknown as FetchExchange;

    await interceptor.intercept(exchange);

    expect(mockTokenRefresher.refresh).not.toHaveBeenCalled();
    expect(mockFetcher.interceptors.request.intercept).not.toHaveBeenCalled();
  });

  it('should attempt to refresh token and retry request on 401 response', async () => {
    const exchange = {
      response: {
        status: ResponseCodes.UNAUTHORIZED,
      } as Response,
      fetcher: mockFetcher,
    } as unknown as FetchExchange;

    mockTokenStorage.get = vi.fn().mockReturnValue({
      token: 'current-token',
    });

    mockTokenRefresher.refresh = vi.fn().mockResolvedValue({
      accessToken: 'new-access-token',
      refreshToken: 'new-refresh-token',
    });

    await interceptor.intercept(exchange);

    expect(mockTokenStorage.get).toHaveBeenCalled();
    expect(mockTokenRefresher.refresh).toHaveBeenCalledWith('current-token');
    expect(mockFetcher.interceptors.request.intercept).toHaveBeenCalledWith(
      exchange,
    );
  });

  it('should clear tokens and throw error when token refresh fails', async () => {
    const exchange = {
      response: {
        status: ResponseCodes.UNAUTHORIZED,
      } as Response,
      fetcher: mockFetcher,
    } as unknown as FetchExchange;

    const refreshError = Object.assign(new Error('Refresh failed'), {
      exchange: { response: { status: 401 } },
    });

    mockTokenStorage.get = vi.fn().mockReturnValue({
      token: 'current-token',
    });

    mockTokenRefresher.refresh = vi.fn().mockRejectedValue(refreshError);

    await expect(interceptor.intercept(exchange)).rejects.toThrow(
      RefreshTokenError,
    );

    expect(mockTokenStorage.get).toHaveBeenCalled();
    expect(mockTokenRefresher.refresh).toHaveBeenCalledWith('current-token');
    expect(mockTokenStorage.remove).toHaveBeenCalled();
    expect(mockFetcher.interceptors.request.intercept).not.toHaveBeenCalled();
  });

  // A failure of the retried request itself (network error, another 401, ...)
  // must propagate without clearing the freshly refreshed and still valid
  // token — that would log the user out on transient failures.
  it('should not clear tokens when the retried request fails', async () => {
    const exchange = {
      response: {
        status: ResponseCodes.UNAUTHORIZED,
      } as Response,
      fetcher: mockFetcher,
      attributes: new Map(),
    } as unknown as FetchExchange;

    mockTokenStorage.get = vi.fn().mockReturnValue({
      token: 'current-token',
    });

    mockTokenRefresher.refresh = vi.fn().mockResolvedValue({
      accessToken: 'new-access-token',
      refreshToken: 'new-refresh-token',
    });
    mockFetcher.interceptors.request.intercept = vi
      .fn()
      .mockRejectedValue(new Error('Network error'));

    await expect(interceptor.intercept(exchange)).rejects.toThrow(
      'Network error',
    );

    expect(mockTokenRefresher.refresh).toHaveBeenCalled();
    expect(mockFetcher.interceptors.request.intercept).toHaveBeenCalledWith(
      exchange,
    );
    expect(mockTokenStorage.remove).not.toHaveBeenCalled();
  });

  it('should not attempt to refresh when no current token exists', async () => {
    const exchange = {
      response: {
        status: ResponseCodes.UNAUTHORIZED,
      } as Response,
      fetcher: mockFetcher,
    } as unknown as FetchExchange;

    mockTokenStorage.get = vi.fn().mockReturnValue(null);

    const refreshError = new Error('No token found');

    await expect(interceptor.intercept(exchange)).rejects.toThrow(
      'No token found',
    );

    expect(mockTokenStorage.get).toHaveBeenCalled();
    expect(mockTokenRefresher.refresh).not.toHaveBeenCalled();
    expect(mockTokenStorage.remove).not.toHaveBeenCalled();
    expect(mockFetcher.interceptors.request.intercept).not.toHaveBeenCalled();
  });

  // BUG: the interceptor retries a 401 by re-running the whole interceptor
  // response chain, which re-invokes THIS SAME
  // interceptor. With no retry bound, a retried request that STILL returns 401
  // recurses refresh indefinitely — hammering the refresh endpoint until the
  // refresh token expires or the call stack overflows.
  it('should not infinitely refresh when the retried request still returns 401', async () => {
    mockTokenStorage.get = vi.fn().mockReturnValue({ token: 'current-token' });

    let refreshCount = 0;
    mockTokenRefresher.refresh = vi.fn().mockImplementation(() => {
      refreshCount++;
      // Safety stop: cap refresh attempts so a buggy implementation cannot
      // hang the test runner. The fix bounds refresh to a single attempt per
      // exchange.
      if (refreshCount > 10) {
        return Promise.reject(new Error('forced-stop'));
      }
      return Promise.resolve(`new-token-${refreshCount}`);
    });

    const exchange = {
      response: {
        status: ResponseCodes.UNAUTHORIZED,
      } as Response,
      fetcher: mockFetcher,
      attributes: new Map(),
    } as unknown as FetchExchange;

    // Simulate the real chain: retrying re-runs response interceptors, and
    // the retried response is STILL 401.
    mockFetcher.interceptors.response.use(interceptor);

    try {
      await interceptor.intercept(exchange);
    } catch {
      // fixed code resolves or rejects once — either is fine; the count is
      // what matters
    }
    expect(refreshCount).toBeLessThanOrEqual(1);
  });
});
