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
import { render } from '@testing-library/react';
import { RouteGuard } from '../../src';
import { SecurityProvider } from '../../src';
import { TokenStorage } from '@ahoo-wang/fetcher-cosec';
import { InMemoryStorage } from '@ahoo-wang/fetcher-storage';

// Valid JWT tokens for testing
const validAccessToken =
  'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyMTIzIiwiZXhwIjoyMDAwMDAwMDAwfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c';
const validRefreshToken =
  'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyMTIzIiwiZXhwIjoyMDAwMDAwMDAwfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c';

const validCompositeToken = {
  accessToken: validAccessToken,
  refreshToken: validRefreshToken,
};

describe('RouteGuard', () => {
  let storage: InMemoryStorage;
  let tokenStorage: TokenStorage;

  beforeEach(() => {
    storage = new InMemoryStorage();
    tokenStorage = new TokenStorage({
      key: 'test-route-guard',
      storage: storage,
    });
  });

  afterEach(() => {
    storage.clear();
    tokenStorage.destroy();
  });

  describe('when user is authenticated', () => {
    beforeEach(() => {
      tokenStorage.signIn(validCompositeToken);
    });

    it('should render children when authenticated', () => {
      const { getByText } = render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <RouteGuard>
            <div>Protected Content</div>
          </RouteGuard>
        </SecurityProvider>,
      );

      expect(getByText('Protected Content')).toBeTruthy();
    });

    it('should not render fallback when authenticated', () => {
      const { queryByText } = render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <RouteGuard fallback={<div>Fallback Content</div>}>
            <div>Protected Content</div>
          </RouteGuard>
        </SecurityProvider>,
      );

      expect(queryByText('Fallback Content')).toBeNull();
    });

    it('should not call onUnauthorized when authenticated', () => {
      const onUnauthorized = vi.fn();

      render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <RouteGuard onUnauthorized={onUnauthorized}>
            <div>Protected Content</div>
          </RouteGuard>
        </SecurityProvider>,
      );

      expect(onUnauthorized).not.toHaveBeenCalled();
    });
  });

  describe('when user is not authenticated', () => {
    it('should render fallback when provided', () => {
      const { getByText } = render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <RouteGuard fallback={<div>Please log in</div>}>
            <div>Protected Content</div>
          </RouteGuard>
        </SecurityProvider>,
      );

      expect(getByText('Please log in')).toBeTruthy();
    });

    it('should not render children when not authenticated', () => {
      const { queryByText } = render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <RouteGuard fallback={<div>Please log in</div>}>
            <div>Protected Content</div>
          </RouteGuard>
        </SecurityProvider>,
      );

      expect(queryByText('Protected Content')).toBeNull();
    });

    it('should render null when no fallback provided', () => {
      const { container } = render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <RouteGuard>
            <div>Protected Content</div>
          </RouteGuard>
        </SecurityProvider>,
      );

      expect(container.firstChild).toBeNull();
    });

    it('should call onUnauthorized when provided', () => {
      const onUnauthorized = vi.fn();

      render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <RouteGuard onUnauthorized={onUnauthorized}>
            <div>Protected Content</div>
          </RouteGuard>
        </SecurityProvider>,
      );

      expect(onUnauthorized).toHaveBeenCalledTimes(1);
    });
  });

  describe('dynamic authentication changes', () => {
    it('should re-render when authentication state changes', () => {
      let renderCount = 0;

      function TestComponent() {
        renderCount++;
        return (
          <RouteGuard fallback={<div>Not Authenticated</div>}>
            <div>Authenticated</div>
          </RouteGuard>
        );
      }

      const { rerender, getByText } = render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <TestComponent />
        </SecurityProvider>,
      );

      expect(renderCount).toBe(1);
      expect(getByText('Not Authenticated')).toBeTruthy();

      // Sign in
      tokenStorage.signIn(validCompositeToken);

      rerender(
        <SecurityProvider tokenStorage={tokenStorage}>
          <TestComponent />
        </SecurityProvider>,
      );

      expect(renderCount).toBe(2);
      expect(getByText('Authenticated')).toBeTruthy();
    });
  });

  describe('edge cases', () => {
    it('should handle empty children', () => {
      const { container } = render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <RouteGuard>{null}</RouteGuard>
        </SecurityProvider>,
      );

      expect(container.firstChild).toBeNull();
    });

    it('should handle complex children', () => {
      tokenStorage.signIn(validCompositeToken);

      const { getByText } = render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <RouteGuard>
            <div>
              <h1>Title</h1>
              <p>Content</p>
            </div>
          </RouteGuard>
        </SecurityProvider>,
      );

      expect(getByText('Title')).toBeTruthy();
      expect(getByText('Content')).toBeTruthy();
    });
  });

  describe('error handling', () => {
    it('should throw error when used outside SecurityProvider', () => {
      expect(() => {
        render(
          <RouteGuard>
            <div>Content</div>
          </RouteGuard>,
        );
      }).toThrow('useSecurityContext must be used within a SecurityProvider');
    });
  });

  describe('type safety', () => {
    it('should accept all valid prop combinations', () => {
      // This test ensures TypeScript accepts various prop combinations
      expect(() => {
        render(
          <SecurityProvider tokenStorage={tokenStorage}>
            <RouteGuard
              fallback={<div>Fallback</div>}
              onUnauthorized={() => {}}
            >
              <div>Content</div>
            </RouteGuard>
          </SecurityProvider>,
        );
      }).not.toThrow();
    });
  });
});
