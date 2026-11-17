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

import { useCallback, useEffect, useReducer } from 'react';
import { useKeyStorage } from '../storage/index.js';
import type {
  TokenStorage,
  CompositeToken,
  CoSecJwtPayload,
} from '@ahoo-wang/fetcher-cosec';
import { useLatest } from '../core/index.js';

/**
 * Type representing a composite token provider.
 * Can be either a direct composite token or a function that returns a promise of a composite token.
 */
type CompositeTokenProvider = CompositeToken | (() => Promise<CompositeToken>);

export const ANONYMOUS_USER: CoSecJwtPayload = {
  jti: '',
  sub: 'anonymous',
  iat: 0,
  exp: 0,
};

/**
 * Options for configuring the useSecurity hook.
 */
export interface UseSecurityOptions {
  /**
   * Callback function invoked when sign in is successful.
   * This is called after the token has been successfully stored.
   */
  onSignIn?: () => void;

  /**
   * Callback function invoked when sign out occurs.
   * This is called after the token has been removed.
   */
  onSignOut?: () => void;
}

/**
 * Return type for the useSecurity hook.
 */
export interface UseSecurityReturn {
  /**
   * The stored access token's JWT payload, even once it has expired (check
   * `authenticated`), or ANONYMOUS_USER when no token is stored.
   */
  currentUser: CoSecJwtPayload;

  /**
   * Boolean indicating whether the user is currently authenticated.
   * True if a valid token exists and the user is signed in, false otherwise.
   */
  authenticated: boolean;

  /**
   * Function to sign in with a composite token or a function that returns a promise of composite token.
   * @param compositeTokenProvider - Either a composite token containing access and refresh tokens,
   *                                or a function that returns a promise resolving to a composite token.
   * @returns A promise that resolves when the sign-in operation is complete.
   */
  signIn: (compositeTokenProvider: CompositeTokenProvider) => Promise<void>;

  /**
   * Function to sign out the current user.
   */
  signOut: () => void;
}

/** The longest delay setTimeout accepts (2^31 - 1 ms). */
const MAX_TIMEOUT = 2_147_483_647;

// Session actions started through useSecurity, per storage: shared by every
// hook on that storage, so a later signIn or signOut from any of them
// supersedes a signIn whose provider is still pending.
const sessionGenerations = new WeakMap<TokenStorage, number>();

function nextSessionGeneration(tokenStorage: TokenStorage): number {
  const generation = (sessionGenerations.get(tokenStorage) ?? 0) + 1;
  sessionGenerations.set(tokenStorage, generation);
  return generation;
}

/**
 * Hook for managing authentication state and operations using CoSec tokens.
 *
 * This hook provides reactive access to the current user information, authentication status,
 * and methods to sign in and sign out. It integrates with the TokenStorage to persist tokens
 * and updates the state reactively when tokens change.
 *
 * @param tokenStorage - The token storage instance used to manage authentication tokens.
 *                      This should be a valid TokenStorage implementation that handles
 *                      token persistence and retrieval.
 * @param options - Optional configuration object containing lifecycle callbacks.
 * @param options.onSignIn - Callback function invoked when sign in is successful.
 * @param options.onSignOut - Callback function invoked when sign out occurs.
 * @returns An object containing:
 *          - currentUser: The stored access token's JWT payload (even if expired), or ANONYMOUS_USER when no token is stored.
 *          - authenticated: Boolean indicating whether the user is currently authenticated
 *            (the access token is unexpired). Computed at render; the hook also
 *            re-renders when the refresh token expires, so an idle page signs out then.
 *          - signIn: Function to authenticate with a composite token.
 *          - signOut: Function to sign out the current user.
 * @throws {Error} May throw errors if tokenStorage operations fail, such as invalid tokens
 *                 or storage access issues (implementation dependent).
 * @example
 * ```typescript
 * import { useSecurity } from '@ahoo-wang/fetcher-react';
 * import { tokenStorage } from './tokenStorage';
 * import { useNavigate } from 'react-router-dom';
 *
 * function App() {
 *   const navigate = useNavigate();
 *
 *   const { currentUser, authenticated, signIn, signOut } = useSecurity(tokenStorage, {
 *     onSignIn: () => {
 *       // Redirect to dashboard after successful login
 *       navigate('/dashboard');
 *     },
 *     onSignOut: () => {
 *       // Redirect to login page after logout
 *       navigate('/login');
 *     }
 *   });
 *
 *   const handleSignIn = async () => {
 *     // Direct token
 *     await signIn(compositeToken);
 *
 *     // Or async function
 *     await signIn(async () => {
 *       const response = await fetch('/api/auth/login', {
 *         method: 'POST',
 *         body: JSON.stringify({ username, password })
 *       });
 *       return response.json();
 *     });
 *   };
 *
 *   if (!authenticated) {
 *     return <button onClick={handleSignIn}>Sign In</button>;
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
export function useSecurity(
  tokenStorage: TokenStorage,
  options: UseSecurityOptions = {},
): UseSecurityReturn {
  // Use useKeyStorage to get reactive updates when token changes
  const [token, , remove] = useKeyStorage(tokenStorage);

  // `authenticated` is computed at render. Re-render when the session can no
  // longer be renewed (the refresh token expires), so a page left open signs
  // out then; an expired access token that can still be refreshed is renewed
  // by the next request instead of sending the user to sign in.
  const [, rerender] = useReducer((count: number) => count + 1, 0);
  const refreshExpiresAt = token?.refresh.payload?.exp;
  const earlyPeriod = tokenStorage.earlyPeriod;
  useEffect(() => {
    if (typeof refreshExpiresAt !== 'number') return;
    const deadline = (refreshExpiresAt - earlyPeriod) * 1000;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const arm = () => {
      const remaining = deadline - Date.now();
      if (remaining <= 0) {
        rerender();
        return;
      }
      // setTimeout overflows past ~24.8 days: wait in steps.
      timer = setTimeout(arm, Math.min(remaining, MAX_TIMEOUT));
    };
    arm();
    return () => clearTimeout(timer);
  }, [refreshExpiresAt, earlyPeriod]);
  const optionsRef = useLatest(options);
  const signIn = useCallback(
    async (compositeTokenProvider: CompositeTokenProvider) => {
      const generation = nextSessionGeneration(tokenStorage);
      const compositeToken =
        typeof compositeTokenProvider === 'function'
          ? await compositeTokenProvider()
          : compositeTokenProvider;
      // A signIn or signOut started meanwhile replaced this one.
      if (sessionGenerations.get(tokenStorage) !== generation) return;
      tokenStorage.signIn(compositeToken);
      optionsRef.current.onSignIn?.();
    },
    [tokenStorage, optionsRef],
  );
  const signOut = useCallback(() => {
    nextSessionGeneration(tokenStorage);
    remove();
    optionsRef.current.onSignOut?.();
  }, [tokenStorage, remove, optionsRef]);

  return {
    currentUser: token?.access?.payload ?? ANONYMOUS_USER,
    authenticated: token?.authenticated ?? false,
    signIn,
    signOut,
  };
}
