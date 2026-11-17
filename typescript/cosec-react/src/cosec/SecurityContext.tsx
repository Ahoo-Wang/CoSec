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

import type { UseSecurityOptions, UseSecurityReturn } from './useSecurity.js';
import { useSecurity } from './useSecurity.js';
import type { ReactNode } from 'react';
import { createContext, useContext } from 'react';
import type { TokenStorage } from '@ahoo-wang/fetcher-cosec';

/**
 * Type alias for the security context value, representing the authentication state and methods.
 * This is equivalent to the return type of the useSecurity hook, providing reactive access to:
 * - Current authenticated user information (JWT payload)
 * - Authentication status (boolean flag)
 * - Sign-in method for authenticating users
 * - Sign-out method for clearing authentication
 *
 * Used internally by the SecurityContext to type the context value.
 */
export type SecurityContextValue = UseSecurityReturn;

/**
 * React context for managing authentication state across the component tree.
 * This context enables sharing of authentication state and methods between components
 * without prop drilling. Components can access authentication data and methods through
 * the useSecurityContext hook when wrapped by the SecurityProvider.
 *
 * The context value is undefined by default, requiring components to be wrapped by
 * SecurityProvider to access authentication functionality.
 */
export const SecurityContext = createContext<SecurityContextValue | undefined>(
  undefined,
);

/**
 * Configuration options for the SecurityProvider component.
 * Extends UseSecurityOptions to include provider-specific settings like token storage and children.
 */
export interface SecurityContextOptions extends UseSecurityOptions {
  /**
   * The token storage instance used to manage authentication tokens.
   * This should be a valid TokenStorage implementation that handles token persistence,
   * retrieval, and lifecycle management across different storage backends (localStorage,
   * sessionStorage, memory, etc.).
   */
  tokenStorage: TokenStorage;

  /**
   * The child components that will have access to the security context.
   * These components can use the useSecurityContext hook to access authentication state and methods.
   */
  children: ReactNode;
}

/**
 * Provider component that supplies authentication state and methods to its child components.
 * This component wraps the application or a portion of it to provide access to authentication
 * functionality through the useSecurityContext hook. It internally uses the useSecurity hook
 * to manage authentication state and makes it available via React context.
 *
 * @param tokenStorage - The token storage instance for managing authentication tokens.
 *                      This should be a valid TokenStorage implementation that handles
 *                      token persistence, retrieval, and lifecycle management across different
 *                      storage backends (localStorage, sessionStorage, memory, etc.).
 * @param children - The child components that will have access to the security context.
 *                  These components can use the useSecurityContext hook to access authentication
 *                  state and methods without prop drilling.
 * @param useSecurityOptions - Optional configuration object containing lifecycle callbacks
 *                            for sign-in and sign-out events. Extends UseSecurityOptions interface.
 * @param useSecurityOptions.onSignIn - Callback function invoked when sign in is successful.
 * @param useSecurityOptions.onSignOut - Callback function invoked when sign out occurs.
 * @returns A React element that provides the security context to its children.
 *          The context value includes currentUser, authenticated status, signIn, and signOut methods.
 * @throws {Error} May throw errors if tokenStorage operations fail during initialization,
 *                 such as invalid tokens or storage access issues (implementation dependent).
 * @example
 * ```tsx
 * import { SecurityProvider } from '@ahoo-wang/fetcher-react';
 * import { tokenStorage } from './tokenStorage';
 * import { useNavigate } from 'react-router-dom';
 *
 * function App() {
 *   const navigate = useNavigate();
 *
 *   return (
 *     <SecurityProvider
 *       tokenStorage={tokenStorage}
 *       onSignIn={() => navigate('/dashboard')}
 *       onSignOut={() => navigate('/login')}
 *     >
 *       <MyApp />
 *     </SecurityProvider>
 *   );
 * }
 * ```
 */
export function SecurityProvider({
  tokenStorage,
  children,
  ...useSecurityOptions
}: SecurityContextOptions) {
  const value = useSecurity(tokenStorage, useSecurityOptions);
  return (
    <SecurityContext.Provider value={value}>
      {children}
    </SecurityContext.Provider>
  );
}

/**
 * Hook to access the security context within components wrapped by SecurityProvider.
 * This hook provides reactive access to authentication state and methods throughout the component tree.
 *
 * @returns The security context containing currentUser, authenticated status, signIn, and signOut methods.
 * @throws {Error} Throws an error if used outside of a SecurityProvider component.
 * @example
 * ```tsx
 * import { useSecurityContext } from '@ahoo-wang/fetcher-react';
 *
 * function UserProfile() {
 *   const { currentUser, authenticated, signOut } = useSecurityContext();
 *
 *   if (!authenticated) {
 *     return <div>Please sign in</div>;
 *   }
 *
 *   return (
 *     <div>
 *       <p>Welcome, {currentUser?.sub}!</p>
 *       <button onClick={signOut}>Sign Out</button>
 *     </div>
 *   );
 * }
 * ```
 */
export function useSecurityContext(): SecurityContextValue {
  const context = useContext(SecurityContext);
  if (!context) {
    throw new Error(
      'useSecurityContext must be used within a SecurityProvider',
    );
  }
  return context;
}
