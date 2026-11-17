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
import { render, screen, waitFor } from '@testing-library/react';
import { RefreshableRouteGuard } from '../../src/cosec/RefreshableRouteGuard';
import { SecurityProvider } from '../../src/cosec/SecurityContext';
import {
  TokenStorage,
  JwtTokenManager,
  CoSecTokenRefresher,
} from '@ahoo-wang/fetcher-cosec';
import { InMemoryStorage } from '@ahoo-wang/fetcher-storage';
import { Fetcher } from '@ahoo-wang/fetcher';

// Valid JWT tokens for testing (exp: 2000000000 = ~2033-05-18)
const validAccessToken =
  'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyMTIzIiwiZXhwIjoyMDAwMDAwMDAwfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c';
const validRefreshToken =
  'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyMTIzIiwiZXhwIjoyMDAwMDAwMDAwfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c';

// Expired JWT tokens for testing (exp: 1000000000 = ~2001-09-09)
const expiredAccessToken =
  'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyMTIzIiwiZXhwIjoxMDAwMDAwMDAwfQ.8KQ_7kmqjJXW5d6RDXZ7WQpJCpkKpJc3nQE8XHJL8zE';
const expiredRefreshToken =
  'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyMTIzIiwiZXhwIjoxMDAwMDAwMDAwfQ.8KQ_7kmqjJXW5d6RDXZ7WQpJCpkKpJc3nQE8XHJL8zE';

const validCompositeToken = {
  accessToken: validAccessToken,
  refreshToken: validRefreshToken,
};

const expiredCompositeToken = {
  accessToken: expiredAccessToken,
  refreshToken: validRefreshToken,
};

describe('RefreshableRouteGuard', () => {
  let storage: InMemoryStorage;
  let tokenStorage: TokenStorage;
  let fetcher: Fetcher;
  let tokenRefresher: CoSecTokenRefresher;
  let tokenManager: JwtTokenManager;

  beforeEach(() => {
    storage = new InMemoryStorage();
    tokenStorage = new TokenStorage({
      key: 'test-refreshable-guard',
      storage: storage,
    });
    fetcher = new Fetcher();
    tokenRefresher = new CoSecTokenRefresher({
      fetcher,
      endpoint: 'http://localhost:3000/auth/refresh',
    });
    tokenManager = new JwtTokenManager(tokenStorage, tokenRefresher);
  });

  afterEach(() => {
    storage.clear();
    tokenStorage.destroy();
    vi.clearAllMocks();
  });

  describe('when user is authenticated', () => {
    beforeEach(() => {
      tokenStorage.signIn(validCompositeToken);
    });

    it('should render children immediately when authenticated', () => {
      const { getByText } = render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <RefreshableRouteGuard tokenManager={tokenManager}>
            <div>Protected Content</div>
          </RefreshableRouteGuard>
        </SecurityProvider>,
      );

      expect(getByText('Protected Content')).toBeTruthy();
    });

    it('should not attempt token refresh when authenticated', () => {
      const refreshSpy = vi.spyOn(tokenManager, 'refresh');

      render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <RefreshableRouteGuard tokenManager={tokenManager}>
            <div>Protected Content</div>
          </RefreshableRouteGuard>
        </SecurityProvider>,
      );

      expect(refreshSpy).not.toHaveBeenCalled();
    });
  });

  describe('when user is not authenticated and refresh is not needed', () => {
    it('should render fallback when no token exists', () => {
      const { getByText } = render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <RefreshableRouteGuard
            tokenManager={tokenManager}
            fallback={<div>Please login</div>}
          >
            <div>Protected Content</div>
          </RefreshableRouteGuard>
        </SecurityProvider>,
      );

      expect(getByText('Please login')).toBeTruthy();
    });

    it('should not attempt token refresh when no token exists', () => {
      const refreshSpy = vi.spyOn(tokenManager, 'refresh');

      render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <RefreshableRouteGuard tokenManager={tokenManager}>
            <div>Protected Content</div>
          </RefreshableRouteGuard>
        </SecurityProvider>,
      );

      expect(refreshSpy).not.toHaveBeenCalled();
    });
  });

  describe('when user is not authenticated and refresh is needed but not possible', () => {
    beforeEach(() => {
      // Sign in with expired tokens where refresh is not possible
      tokenStorage.signIn({
        accessToken: expiredAccessToken,
        refreshToken: expiredRefreshToken,
      });
    });

    it('should render fallback when refresh is not possible', () => {
      const { getByText } = render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <RefreshableRouteGuard
            tokenManager={tokenManager}
            fallback={<div>Please login</div>}
          >
            <div>Protected Content</div>
          </RefreshableRouteGuard>
        </SecurityProvider>,
      );

      expect(getByText('Please login')).toBeTruthy();
    });

    it('should not attempt token refresh when not refreshable', () => {
      const refreshSpy = vi.spyOn(tokenManager, 'refresh');

      render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <RefreshableRouteGuard tokenManager={tokenManager}>
            <div>Protected Content</div>
          </RefreshableRouteGuard>
        </SecurityProvider>,
      );

      expect(refreshSpy).not.toHaveBeenCalled();
    });
  });

  describe('when user is not authenticated and refresh is needed and possible', () => {
    beforeEach(() => {
      // Sign in with expired access token but valid refresh token
      tokenStorage.signIn(expiredCompositeToken);
    });

    it('should attempt token refresh when refreshable', async () => {
      const refreshSpy = vi.spyOn(tokenManager, 'refresh').mockResolvedValue();

      render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <RefreshableRouteGuard tokenManager={tokenManager}>
            <div>Protected Content</div>
          </RefreshableRouteGuard>
        </SecurityProvider>,
      );

      await waitFor(() => {
        expect(refreshSpy).toHaveBeenCalledTimes(1);
      });
    });

    it('should render custom refreshing content', () => {
      const refreshSpy = vi
        .spyOn(tokenManager, 'refresh')
        .mockImplementation(
          () => new Promise(resolve => setTimeout(resolve, 100)),
        );

      const { getByText } = render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <RefreshableRouteGuard
            tokenManager={tokenManager}
            refreshing={<div>Custom Loading...</div>}
          >
            <div>Protected Content</div>
          </RefreshableRouteGuard>
        </SecurityProvider>,
      );

      expect(getByText('Custom Loading...')).toBeTruthy();
    });

    it('should render default refreshing content when none provided', () => {
      const refreshSpy = vi
        .spyOn(tokenManager, 'refresh')
        .mockImplementation(
          () => new Promise(resolve => setTimeout(resolve, 100)),
        );

      const { getByText } = render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <RefreshableRouteGuard tokenManager={tokenManager}>
            <div>Protected Content</div>
          </RefreshableRouteGuard>
        </SecurityProvider>,
      );

      expect(getByText('Refreshing...')).toBeTruthy();
    });

    it('should show refreshing content while the refresh is in flight', () => {
      const refreshSpy = vi.spyOn(tokenManager, 'refresh').mockResolvedValue();

      const { getByText } = render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <RefreshableRouteGuard
            tokenManager={tokenManager}
            refreshing={<div>Refreshing...</div>}
          >
            <div>Protected Content</div>
          </RefreshableRouteGuard>
        </SecurityProvider>,
      );

      expect(getByText('Refreshing...')).toBeTruthy();
      expect(refreshSpy).toHaveBeenCalledTimes(1);
    });

    it('should log a refresh error and render fallback instead of refreshing forever', async () => {
      const consoleErrorSpy = vi
        .spyOn(console, 'error')
        .mockImplementation(() => {});
      const refreshSpy = vi
        .spyOn(tokenManager, 'refresh')
        .mockRejectedValue(new Error('Refresh failed'));

      render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <RefreshableRouteGuard
            tokenManager={tokenManager}
            fallback={<div>Login required</div>}
            refreshing={<div>Refreshing...</div>}
          >
            <div>Protected Content</div>
          </RefreshableRouteGuard>
        </SecurityProvider>,
      );

      expect(screen.getByText('Refreshing...')).toBeTruthy();
      await waitFor(() => {
        expect(screen.getByText('Login required')).toBeTruthy();
      });
      expect(consoleErrorSpy).toHaveBeenCalledWith(new Error('Refresh failed'));
      expect(screen.queryByText('Refreshing...')).toBeNull();
      expect(refreshSpy).toHaveBeenCalledTimes(1);
    });
  });

  describe('error handling', () => {
    it('should throw error when used outside SecurityProvider', () => {
      expect(() => {
        render(
          <RefreshableRouteGuard tokenManager={tokenManager}>
            <div>Content</div>
          </RefreshableRouteGuard>,
        );
      }).toThrow('useSecurityContext must be used within a SecurityProvider');
    });
  });

  describe('edge cases', () => {
    it('should handle empty children gracefully', () => {
      tokenStorage.signIn(validCompositeToken);

      const { container } = render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <RefreshableRouteGuard tokenManager={tokenManager}>
            {null}
          </RefreshableRouteGuard>
        </SecurityProvider>,
      );

      // When authenticated with null children, renders null
      expect(container.firstChild).toBeNull();
    });

    it('should handle complex children structures', () => {
      tokenStorage.signIn(validCompositeToken);

      const { getByText } = render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <RefreshableRouteGuard tokenManager={tokenManager}>
            <div>
              <h1>Complex</h1>
              <p>
                Content with <strong>nested</strong> elements
              </p>
            </div>
          </RefreshableRouteGuard>
        </SecurityProvider>,
      );

      expect(getByText('Complex')).toBeTruthy();
      expect(getByText('nested')).toBeTruthy();
    });

    it('should handle null/undefined fallback gracefully', () => {
      const { container } = render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <RefreshableRouteGuard tokenManager={tokenManager}>
            <div>Content</div>
          </RefreshableRouteGuard>
        </SecurityProvider>,
      );

      expect(container.firstChild).toBeNull();
    });
  });

  describe('performance and optimization', () => {
    it('should not re-refresh when refreshable state changes unnecessarily', async () => {
      const refreshSpy = vi.spyOn(tokenManager, 'refresh').mockResolvedValue();

      tokenStorage.signIn(expiredCompositeToken);

      const { rerender } = render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <RefreshableRouteGuard tokenManager={tokenManager}>
            <div>Content</div>
          </RefreshableRouteGuard>
        </SecurityProvider>,
      );

      await waitFor(() => {
        expect(refreshSpy).toHaveBeenCalledTimes(1);
      });

      // Re-render with same props - should not refresh again due to useCallback memoization
      rerender(
        <SecurityProvider tokenStorage={tokenStorage}>
          <RefreshableRouteGuard tokenManager={tokenManager}>
            <div>Content</div>
          </RefreshableRouteGuard>
        </SecurityProvider>,
      );

      // Wait a bit to ensure no additional calls
      await new Promise(resolve => setTimeout(resolve, 50));
      expect(refreshSpy).toHaveBeenCalledTimes(1);
    });
  });

  describe('integration with real token manager', () => {
    it('should work with real JwtTokenManager instance', async () => {
      // Create a real token manager with expired token that needs refresh
      tokenStorage.signIn(expiredCompositeToken);

      // Mock the fetcher to simulate successful refresh
      const fetchSpy = vi
        .spyOn(fetcher, 'post')
        .mockResolvedValue(validCompositeToken);

      const { getByText } = render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <RefreshableRouteGuard
            tokenManager={tokenManager}
            refreshing={<div>Loading...</div>}
          >
            <div>Success</div>
          </RefreshableRouteGuard>
        </SecurityProvider>,
      );

      // Initially shows refreshing while refresh is triggered
      expect(getByText('Loading...')).toBeTruthy();

      await waitFor(() => {
        expect(fetchSpy).toHaveBeenCalledWith(
          'http://localhost:3000/auth/refresh',
          expect.objectContaining({
            body: expiredCompositeToken,
          }),
          expect.any(Object),
        );
      });

      // Current implementation shows refreshing initially, but since refresh completes
      // synchronously in test, it may show children immediately after
      // The important thing is that refresh was attempted
    });
  });

  describe('type safety and prop validation', () => {
    it('should accept all valid prop combinations', () => {
      tokenStorage.signIn(validCompositeToken);

      expect(() => {
        render(
          <SecurityProvider tokenStorage={tokenStorage}>
            <RefreshableRouteGuard
              tokenManager={tokenManager}
              refreshing={<div>Custom refreshing</div>}
            >
              <div>Content</div>
            </RefreshableRouteGuard>
          </SecurityProvider>,
        );
      }).not.toThrow();
    });
  });
});
