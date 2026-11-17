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

import type { CompositeToken, TokenRefresher } from './tokenRefresher.js';

/**
 * Brand of a refresher that takes JwtTokenManager's unauthorized
 * notification guard as its second `refresh` argument (CoSecTokenRefresher).
 *
 * A registered symbol rather than `instanceof`, so the manager recognizes a
 * refresher made by another copy of this package — the CommonJS build next to
 * the ES module one, or a second bundled copy — and the refresh request and
 * the business request still share a single onUnauthorized notification.
 */
export const NOTIFICATION_GUARD_REFRESHER = Symbol.for(
  '@ahoo-wang/fetcher-cosec/notification-guard-refresher',
);

export interface NotificationGuardRefresher extends TokenRefresher {
  refresh(
    token: CompositeToken,
    shouldNotifyUnauthorized?: () => boolean,
  ): Promise<CompositeToken>;
}

export function isNotificationGuardRefresher(
  refresher: TokenRefresher,
): boolean {
  return (
    (refresher as { [NOTIFICATION_GUARD_REFRESHER]?: unknown })[
      NOTIFICATION_GUARD_REFRESHER
    ] === true
  );
}
