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

import type { RouteGuardProps } from './RouteGuard.js';
import type { ReactNode } from 'react';
import { useEffect, useState } from 'react';
import type { JwtTokenManager } from '@ahoo-wang/fetcher-cosec';
import { useSecurityContext } from './SecurityContext.js';

export interface RefreshableRouteGuardProps extends Omit<
  RouteGuardProps,
  'onUnauthorized'
> {
  refreshing?: ReactNode;
  tokenManager: JwtTokenManager;
}

export function RefreshableRouteGuard({
  children,
  fallback,
  refreshing,
  tokenManager,
}: RefreshableRouteGuardProps) {
  const { authenticated } = useSecurityContext();
  const refreshable =
    tokenManager.isRefreshNeeded && tokenManager.isRefreshable;
  // The token being refreshed: a session that replaces it gets a refresh of
  // its own, even when both need one.
  const refreshToken = refreshable ? tokenManager.currentToken : null;
  // A failed refresh that leaves the token in storage (a network failure or
  // a 5xx keeps the session: RefreshUnavailableError) changes nothing the
  // guard re-renders on, so without this it would show `refreshing` forever.
  // It is recorded for its token only: a later session is not failed by it.
  const [failedToken, setFailedToken] = useState<object | null>(null);
  const refreshFailed = refreshToken !== null && failedToken === refreshToken;

  useEffect(() => {
    if (!refreshToken) {
      return;
    }
    let current = true;
    tokenManager.refresh().catch(error => {
      // The refresh of a session that is no longer the guard's.
      if (!current) return;
      console.error(error);
      setFailedToken(refreshToken);
    });
    return () => {
      current = false;
    };
  }, [refreshToken, tokenManager]);

  if (authenticated) {
    return <>{children}</>;
  }

  if (!refreshToken || refreshFailed) {
    return <>{fallback}</>;
  }
  const refreshingNode = refreshing ?? <p>Refreshing...</p>;
  return <>{refreshingNode}</>;
}
