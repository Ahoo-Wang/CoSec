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
import type { FetchExchange } from '@ahoo-wang/fetcher';
import type { JwtCompositeToken } from './jwtToken.js';
import { TOKEN_SESSION_ATTRIBUTE } from './constants.js';
import { RefreshSessionChangedError } from './errors.js';

export { TOKEN_SESSION_ATTRIBUTE } from './constants.js';

export function isSameTokenSession(
  expectedToken: JwtCompositeToken | null,
  currentToken: JwtCompositeToken | null,
): boolean {
  return (
    expectedToken === currentToken ||
    (expectedToken !== null &&
      currentToken !== null &&
      typeof expectedToken.sessionId === 'string' &&
      expectedToken.sessionId === currentToken.sessionId)
  );
}

/**
 * Whether two tokens are the same stored token: the same object, or the same
 * session with the same JWTs, as re-reading storage yields (a legacy record
 * is parsed into a new object each time).
 */
export function isSameToken(
  expectedToken: JwtCompositeToken,
  currentToken: JwtCompositeToken | null,
): boolean {
  return (
    expectedToken === currentToken ||
    (currentToken !== null &&
      expectedToken.sessionId === currentToken.sessionId &&
      expectedToken.token.accessToken === currentToken.token.accessToken &&
      expectedToken.token.refreshToken === currentToken.token.refreshToken)
  );
}

export function assertTokenSession(
  exchange: FetchExchange,
  currentToken: JwtCompositeToken | null,
): void {
  if (
    exchange.attributes?.has(TOKEN_SESSION_ATTRIBUTE) &&
    !isSameTokenSession(
      exchange.attributes.get(TOKEN_SESSION_ATTRIBUTE),
      currentToken,
    )
  ) {
    throw new RefreshSessionChangedError();
  }
}
