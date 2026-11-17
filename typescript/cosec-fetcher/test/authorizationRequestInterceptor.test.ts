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
  AuthorizationRequestInterceptor,
  AUTHORIZATION_REQUEST_INTERCEPTOR_NAME,
  AUTHORIZATION_REQUEST_INTERCEPTOR_ORDER,
} from '../src';
import { CoSecHeaders, IGNORE_REFRESH_TOKEN_ATTRIBUTE_KEY } from '../src';
import type { FetchExchange } from '@ahoo-wang/fetcher';

// Mock token class
class MockToken {
  constructor(
    public access: { token: string },
    public isRefreshNeeded: boolean = false,
    public isRefreshable: boolean = true,
  ) {}
}

// Mock token manager
class MockTokenManager {
  public currentToken: MockToken | null = null;
  public refreshCalled = false;

  async refresh(): Promise<void> {
    this.refreshCalled = true;
    // Simulate token refresh
    if (this.currentToken) {
      this.currentToken = new MockToken(
        { token: 'refreshed-token' },
        false, // isRefreshNeeded
        this.currentToken.isRefreshable,
      );
    }
  }

  reset() {
    this.refreshCalled = false;
  }
}

// Mock exchange
class MockFetchExchange implements Partial<FetchExchange> {
  public requestHeaders: Record<string, string> = {};
  public attributes: Map<string, any> = new Map();

  ensureRequestHeaders(): Record<string, string> {
    return this.requestHeaders;
  }
}

describe('AuthorizationRequestInterceptor', () => {
  let interceptor: AuthorizationRequestInterceptor;
  let mockTokenManager: MockTokenManager;
  let mockExchange: MockFetchExchange;

  beforeEach(() => {
    mockTokenManager = new MockTokenManager();
    const options = {
      tokenManager: mockTokenManager,
    };
    interceptor = new AuthorizationRequestInterceptor(options);
    mockExchange = new MockFetchExchange();
  });

  afterEach(() => {
    mockTokenManager.reset();
  });

  it('should have correct name and order', () => {
    expect(interceptor.name).toBe(AUTHORIZATION_REQUEST_INTERCEPTOR_NAME);
    expect(interceptor.order).toBe(AUTHORIZATION_REQUEST_INTERCEPTOR_ORDER);
  });

  it('should not set Authorization header when no token exists', async () => {
    mockTokenManager.currentToken = null;

    await interceptor.intercept(mockExchange as FetchExchange);

    expect(
      mockExchange.requestHeaders[CoSecHeaders.AUTHORIZATION],
    ).toBeUndefined();
  });

  it('should not modify existing Authorization header', async () => {
    mockTokenManager.currentToken = new MockToken({ token: 'test-token' });
    mockExchange.requestHeaders[CoSecHeaders.AUTHORIZATION] =
      'Existing Bearer token';

    await interceptor.intercept(mockExchange as FetchExchange);

    expect(mockExchange.requestHeaders[CoSecHeaders.AUTHORIZATION]).toBe(
      'Existing Bearer token',
    );
  });

  it('should set Authorization header when token exists and no header is set', async () => {
    const token = new MockToken({ token: 'test-token' });
    mockTokenManager.currentToken = token;

    await interceptor.intercept(mockExchange as FetchExchange);

    expect(mockExchange.requestHeaders[CoSecHeaders.AUTHORIZATION]).toBe(
      'Bearer test-token',
    );
  });

  it('should refresh token when token needs refresh and is refreshable', async () => {
    const token = new MockToken({ token: 'old-token' }, true, true);
    mockTokenManager.currentToken = token;

    await interceptor.intercept(mockExchange as FetchExchange);

    expect(mockTokenManager.refreshCalled).toBe(true);
    expect(mockExchange.requestHeaders[CoSecHeaders.AUTHORIZATION]).toBe(
      'Bearer refreshed-token',
    );
  });

  it('should not refresh token when token does not need refresh', async () => {
    const token = new MockToken({ token: 'valid-token' }, false, true);
    mockTokenManager.currentToken = token;

    await interceptor.intercept(mockExchange as FetchExchange);

    expect(mockTokenManager.refreshCalled).toBe(false);
    expect(mockExchange.requestHeaders[CoSecHeaders.AUTHORIZATION]).toBe(
      'Bearer valid-token',
    );
  });

  it('should not refresh token when token is not refreshable', async () => {
    const token = new MockToken(
      { token: 'non-refreshable-token' },
      true,
      false,
    );
    mockTokenManager.currentToken = token;

    await interceptor.intercept(mockExchange as FetchExchange);

    expect(mockTokenManager.refreshCalled).toBe(false);
    expect(mockExchange.requestHeaders[CoSecHeaders.AUTHORIZATION]).toBe(
      'Bearer non-refreshable-token',
    );
  });

  it('should not refresh token when refresh is ignored by attribute', async () => {
    const token = new MockToken({ token: 'expiring-token' }, true, true);
    mockTokenManager.currentToken = token;
    mockExchange.attributes.set(IGNORE_REFRESH_TOKEN_ATTRIBUTE_KEY, true);

    await interceptor.intercept(mockExchange as FetchExchange);

    expect(mockTokenManager.refreshCalled).toBe(false);
    expect(mockExchange.requestHeaders[CoSecHeaders.AUTHORIZATION]).toBe(
      'Bearer expiring-token',
    );
  });

  it('should not set Authorization header when token becomes null after refresh', async () => {
    // Create a mock token manager that clears the token on refresh
    class MockTokenManagerWithNullRefresh extends MockTokenManager {
      async refresh(): Promise<void> {
        this.refreshCalled = true;
        this.currentToken = null;
      }
    }

    const nullTokenManager = new MockTokenManagerWithNullRefresh();
    nullTokenManager.currentToken = new MockToken(
      { token: 'old-token' },
      true,
      true,
    );

    const interceptorWithNullToken = new AuthorizationRequestInterceptor({
      tokenManager: nullTokenManager,
    });

    await interceptorWithNullToken.intercept(mockExchange as FetchExchange);

    expect(nullTokenManager.refreshCalled).toBe(true);
    expect(
      mockExchange.requestHeaders[CoSecHeaders.AUTHORIZATION],
    ).toBeUndefined();
  });
});
