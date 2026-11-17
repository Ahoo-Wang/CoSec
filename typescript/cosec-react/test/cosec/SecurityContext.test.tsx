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

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { render, act, waitFor } from '@testing-library/react';
import React from 'react';
import { SecurityProvider, useSecurityContext } from '../../src';
import { TokenStorage } from '@ahoo-wang/fetcher-cosec';
import { InMemoryStorage } from '@ahoo-wang/fetcher-storage';
import { ANONYMOUS_USER } from '../../src';

// Valid JWT tokens for testing
const validAccessToken =
  'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyMTIzIiwiZXhwIjoyMDAwMDAwMDAwfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c';
const validRefreshToken =
  'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyMTIzIiwiZXhwIjoyMDAwMDAwMDAwfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c';

const validCompositeToken = {
  accessToken: validAccessToken,
  refreshToken: validRefreshToken,
};

// Test component that uses the security context
function TestComponent() {
  const { currentUser, authenticated, signIn, signOut } = useSecurityContext();

  return (
    <div>
      <div data-testid="authenticated">{authenticated ? 'true' : 'false'}</div>
      <div data-testid="currentUser">{currentUser?.sub || 'null'}</div>
      <button data-testid="signIn" onClick={() => signIn(validCompositeToken)}>
        Sign In
      </button>
      <button data-testid="signOut" onClick={signOut}>
        Sign Out
      </button>
    </div>
  );
}

// Component that throws when used outside provider
function ComponentWithoutProvider() {
  try {
    useSecurityContext();
    return <div>Should not reach here</div>;
  } catch (error) {
    return <div data-testid="error">{(error as Error).message}</div>;
  }
}

describe('SecurityContext', () => {
  let storage: InMemoryStorage;
  let tokenStorage: TokenStorage;

  beforeEach(() => {
    storage = new InMemoryStorage();
    tokenStorage = new TokenStorage({
      key: 'test-security-context',
      storage: storage,
    });
  });

  afterEach(() => {
    storage.clear();
    tokenStorage.destroy();
  });

  describe('SecurityProvider', () => {
    it('should render children correctly', () => {
      const { getByText } = render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <div>Test Child</div>
        </SecurityProvider>,
      );

      expect(getByText('Test Child')).toBeTruthy();
    });

    it('should provide security context to children', () => {
      const { getByTestId } = render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <TestComponent />
        </SecurityProvider>,
      );

      expect(getByTestId('authenticated').textContent).toBe('false');
      expect(getByTestId('currentUser').textContent).toBe(ANONYMOUS_USER.sub);
    });

    it('should update context when token is signed in', async () => {
      const { getByTestId, getByText } = render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <TestComponent />
        </SecurityProvider>,
      );

      act(() => {
        getByText('Sign In').click();
      });

      await waitFor(() => {
        expect(getByTestId('authenticated').textContent).toBe('true');
        expect(getByTestId('currentUser').textContent).toBe('user123');
      });
    });

    it('should update context when token is signed out', async () => {
      // First sign in
      tokenStorage.signIn(validCompositeToken);

      const { getByTestId, getByText } = render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <TestComponent />
        </SecurityProvider>,
      );

      expect(getByTestId('authenticated').textContent).toBe('true');

      act(() => {
        getByText('Sign Out').click();
      });

      await waitFor(() => {
        expect(getByTestId('authenticated').textContent).toBe('false');
        expect(getByTestId('currentUser').textContent).toBe(ANONYMOUS_USER.sub);
      });
    });

    it('should handle multiple providers independently', () => {
      const storage2 = new InMemoryStorage();
      const tokenStorage2 = new TokenStorage({
        key: 'test-security-context-2',
        storage: storage2,
      });

      const { getAllByTestId } = render(
        <div>
          <SecurityProvider tokenStorage={tokenStorage}>
            <TestComponent />
          </SecurityProvider>
          <SecurityProvider tokenStorage={tokenStorage2}>
            <TestComponent />
          </SecurityProvider>
        </div>,
      );

      const authenticatedElements = getAllByTestId('authenticated');
      expect(authenticatedElements).toHaveLength(2);
      expect(authenticatedElements[0].textContent).toBe('false');
      expect(authenticatedElements[1].textContent).toBe('false');

      tokenStorage2.destroy();
      storage2.clear();
    });

    it('should propagate context updates to all children', async () => {
      function MultipleChildren() {
        return (
          <div>
            <TestComponent />
            <TestComponent />
          </div>
        );
      }

      const { getAllByTestId, getAllByText } = render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <MultipleChildren />
        </SecurityProvider>,
      );

      const authenticatedElements = getAllByTestId('authenticated');
      expect(authenticatedElements).toHaveLength(2);
      expect(authenticatedElements[0].textContent).toBe('false');
      expect(authenticatedElements[1].textContent).toBe('false');

      act(() => {
        getAllByText('Sign In')[0].click();
      });

      await waitFor(() => {
        expect(authenticatedElements[0].textContent).toBe('true');
        expect(authenticatedElements[1].textContent).toBe('true');
      });
    });
  });

  describe('useSecurityContext', () => {
    it('should throw error when used outside SecurityProvider', () => {
      const { getByTestId } = render(<ComponentWithoutProvider />);

      expect(getByTestId('error').textContent).toBe(
        'useSecurityContext must be used within a SecurityProvider',
      );
    });

    it('should provide reactive updates through context', async () => {
      let renderCount = 0;

      function ReactiveComponent() {
        const { authenticated } = useSecurityContext();
        renderCount++;
        return (
          <div data-testid="reactive">{authenticated ? 'true' : 'false'}</div>
        );
      }

      const { getByTestId } = render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <ReactiveComponent />
        </SecurityProvider>,
      );

      expect(renderCount).toBe(1);
      expect(getByTestId('reactive').textContent).toBe('false');

      act(() => {
        tokenStorage.signIn(validCompositeToken);
      });

      await waitFor(() => {
        expect(getByTestId('reactive').textContent).toBe('true');
        expect(renderCount).toBe(2); // Should re-render due to context update
      });
    });
  });

  describe('edge cases', () => {
    it('should handle provider with no children', () => {
      expect(() => {
        render(
          <SecurityProvider
            tokenStorage={tokenStorage}
            children={undefined as any}
          />,
        );
      }).not.toThrow();
    });

    it('should handle provider with null children', () => {
      expect(() => {
        render(
          <SecurityProvider tokenStorage={tokenStorage}>
            {null}
          </SecurityProvider>,
        );
      }).not.toThrow();
    });

    it('should handle provider with fragment children', () => {
      expect(() => {
        render(
          <SecurityProvider tokenStorage={tokenStorage}>
            <>
              <div>Child 1</div>
              <div>Child 2</div>
            </>
          </SecurityProvider>,
        );
      }).not.toThrow();
    });

    it('should handle deeply nested components', () => {
      function DeeplyNested() {
        return (
          <div>
            <div>
              <TestComponent />
            </div>
          </div>
        );
      }

      const { getByTestId } = render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <DeeplyNested />
        </SecurityProvider>,
      );

      expect(getByTestId('authenticated').textContent).toBe('false');
    });

    it('should handle context used in effects', async () => {
      let effectValue: any = null;

      function EffectComponent() {
        const context = useSecurityContext();

        React.useEffect(() => {
          effectValue = context.authenticated;
        }, [context.authenticated]);

        return <div>Effect</div>;
      }

      render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <EffectComponent />
        </SecurityProvider>,
      );

      expect(effectValue).toBe(false);

      act(() => {
        tokenStorage.signIn(validCompositeToken);
      });

      await waitFor(() => {
        expect(effectValue).toBe(true);
      });
    });
  });

  describe('error handling', () => {
    it('should handle tokenStorage initialization errors', () => {
      const errorStorage = Object.assign(new InMemoryStorage(), {
        getItem: () => {
          throw new Error('Storage initialization error');
        },
      });

      const errorTokenStorage = new TokenStorage({
        key: 'error-key',
        storage: errorStorage,
      });

      expect(() => {
        render(
          <SecurityProvider tokenStorage={errorTokenStorage}>
            <div>Test</div>
          </SecurityProvider>,
        );
      }).toThrow('Storage initialization error');

      errorTokenStorage.destroy();
    });

    it('should handle context access errors gracefully', () => {
      // Test that accessing undefined properties doesn't crash the context
      function ErrorComponent() {
        const context = useSecurityContext();
        // Access undefined property - should not throw
        const nonexistent = (context as any).nonexistent;
        return (
          <div data-testid="error-test">
            {nonexistent ? 'exists' : 'undefined'}
          </div>
        );
      }

      const { getByTestId } = render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <ErrorComponent />
        </SecurityProvider>,
      );

      expect(getByTestId('error-test').textContent).toBe('undefined');
    });
  });

  describe('type safety', () => {
    it('should maintain type safety for context value', () => {
      function TypeSafeComponent() {
        const { authenticated, signIn, signOut } = useSecurityContext();

        // TypeScript should enforce these types
        const auth: boolean = authenticated;
        const signInFn: (token: any) => void = signIn;
        const signOutFn: () => void = signOut;

        return (
          <div>
            <div data-testid="types">
              {typeof auth === 'boolean' &&
              typeof signInFn === 'function' &&
              typeof signOutFn === 'function'
                ? 'valid'
                : 'invalid'}
            </div>
          </div>
        );
      }

      const { getByTestId } = render(
        <SecurityProvider tokenStorage={tokenStorage}>
          <TypeSafeComponent />
        </SecurityProvider>,
      );

      expect(getByTestId('types').textContent).toBe('valid');
    });
  });
});
