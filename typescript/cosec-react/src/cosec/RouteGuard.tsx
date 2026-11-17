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

import { useEffect, type ReactNode } from 'react';
import { useLatest } from '../core/useLatest.js';
import { useSecurityContext } from './SecurityContext.js';

/**
 * Props for the RouteGuard component.
 */
export interface RouteGuardProps {
  /**
   * The content to render when the user is authenticated.
   */
  children: ReactNode;

  /**
   * The fallback content to render when the user is not authenticated.
   * If not provided, nothing will be rendered.
   */
  fallback?: ReactNode;

  /**
   * Optional redirect function to call when user is not authenticated.
   * This can be used to programmatically navigate to a login page. It runs
   * after the render commits, once each time the user becomes (or starts
   * out) unauthenticated — not during rendering.
   */
  onUnauthorized?: () => void;
}

/**
 * Route guard component that conditionally renders content based on authentication status.
 *
 * This component uses the SecurityContext to check if the user is authenticated.
 * If authenticated, it renders the children. If not authenticated, it renders the fallback
 * content (if provided) and optionally calls the onUnauthorized callback.
 *
 * @param children - The protected content to render when authenticated.
 * @param fallback - Optional fallback content to render when not authenticated.
 * @param onUnauthorized - Optional callback to execute when user is not authenticated.
 * @returns The children if authenticated, fallback if provided, or null otherwise.
 * @example
 * ```tsx
 * import { RouteGuard } from '@ahoo-wang/fetcher-react';
 *
 * function ProtectedPage() {
 *   return (
 *     <RouteGuard
 *       fallback={<div>Please log in to access this page.</div>}
 *       onUnauthorized={() => navigate('/login')}
 *     >
 *       <div>Protected content</div>
 *     </RouteGuard>
 *   );
 * }
 * ```
 */
export function RouteGuard({
  children,
  fallback,
  onUnauthorized,
}: RouteGuardProps) {
  const { authenticated } = useSecurityContext();
  const latestOnUnauthorized = useLatest(onUnauthorized);

  // A side effect such as navigate() belongs after commit, not in render.
  useEffect(() => {
    if (!authenticated) {
      latestOnUnauthorized.current?.();
    }
  }, [authenticated, latestOnUnauthorized]);

  if (authenticated) {
    return <>{children}</>;
  }

  // Return fallback if provided, otherwise return null
  return fallback;
}
