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

import type {
  CoSecJwtPayload,
  EarlyPeriodCapable,
  JwtPayload,
} from './jwts.js';
import { isTokenExpired, parseJwtPayload } from './jwts.js';
import type { CompositeToken } from './tokenRefresher.js';
import type { Serializer } from '@ahoo-wang/fetcher-storage';
import { idGenerator } from './idGenerator.js';
import { isCompositeToken } from './compositeToken.js';

/**
 * Interface for JWT token with typed payload.
 *
 * This interface represents a JWT token that includes the raw token string,
 * a parsed payload of a specific type, and methods to check expiration status.
 * It extends EarlyPeriodCapable to support early expiration checks.
 *
 * @template Payload The type of the JWT payload, must extend JwtPayload
 *
 * @example
 * ```typescript
 * interface CustomPayload extends JwtPayload {
 *   userId: string;
 *   roles: string[];
 * }
 *
 * const token: IJwtToken<CustomPayload> = new JwtToken<CustomPayload>('jwt.token.here');
 * if (!token.isExpired) {
 *   console.log(token.payload?.userId);
 * }
 * ```
 */
export interface IJwtToken<
  Payload extends JwtPayload,
> extends EarlyPeriodCapable {
  /**
   * The raw JWT token string.
   */
  readonly token: string;

  /**
   * The parsed JWT payload. Null if the token could not be parsed.
   */
  readonly payload: Payload | null;

  /**
   * Indicates whether the token is expired, considering the early period.
   */
  isExpired: boolean;
}

/**
 * Class representing a JWT token with typed payload.
 *
 * This class provides a concrete implementation of IJwtToken, parsing the JWT
 * token string and providing access to the payload and expiration status.
 * It supports early expiration periods for proactive token refresh.
 *
 * @template Payload The type of the JWT payload, must extend JwtPayload
 *
 * @example
 * ```typescript
 * const token = new JwtToken<CoSecJwtPayload>('eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...', 300); // 5 min early period
 * console.log(token.isExpired); // false if not expired
 * console.log(token.payload?.sub); // user ID from payload
 * ```
 */
export class JwtToken<
  Payload extends JwtPayload,
> implements IJwtToken<Payload> {
  /**
   * The parsed JWT payload. Null if the token could not be parsed.
   */
  public readonly payload: Payload | null;

  /**
   * Creates a new JwtToken instance.
   *
   * Parses the JWT token string to extract the payload and stores the early period
   * for expiration checks.
   *
   * @param token The raw JWT token string to parse
   * @param earlyPeriod The early expiration period in seconds (default: 0).
   *                   Tokens are considered expired this many seconds before their actual expiration time.
   *
   * @throws Will not throw but payload will be null if token parsing fails
   */
  constructor(
    public readonly token: string,
    public readonly earlyPeriod: number = 0,
  ) {
    this.payload = parseJwtPayload<Payload>(token);
  }

  /**
   * Checks if the token is expired.
   *
   * Considers both the token's expiration time and the early period.
   * Returns true if the payload is null (parsing failed) or if the token is expired.
   *
   * @returns true if the token is expired or invalid, false otherwise
   */
  get isExpired(): boolean {
    if (!this.payload) {
      return true;
    }
    return isTokenExpired(this.payload, this.earlyPeriod);
  }
}

/**
 * Interface for checking refresh token status capabilities.
 *
 * This interface defines methods to check if token refresh is needed and possible.
 */
export interface RefreshTokenStatusCapable {
  /**
   * Checks if the access token needs to be refreshed.
   *
   * @returns true if the access token is expired, false otherwise
   */
  readonly isRefreshNeeded: boolean;

  /**
   * Checks if the refresh token is still valid and can be used to refresh the access token.
   *
   * @returns true if the refresh token is not expired, false otherwise
   */
  readonly isRefreshable: boolean;
}

/**
 * Class representing a composite token containing both access and refresh tokens.
 *
 * This class manages both access and refresh JWT tokens together, providing
 * convenient methods to check authentication status, refresh needs, and user information.
 * It implements both EarlyPeriodCapable and RefreshTokenStatusCapable interfaces.
 *
 * @example
 * ```typescript
 * const compositeToken = new JwtCompositeToken({
 *   accessToken: 'access.jwt.here',
 *   refreshToken: 'refresh.jwt.here'
 * }, 300); // 5 min early period
 *
 * if (compositeToken.authenticated) {
 *   console.log(compositeToken.currentUser?.sub);
 * }
 *
 * if (compositeToken.isRefreshNeeded && compositeToken.isRefreshable) {
 *   // Refresh the access token
 * }
 * ```
 */
export class JwtCompositeToken
  implements EarlyPeriodCapable, RefreshTokenStatusCapable
{
  /**
   * The access JWT token instance.
   */
  public readonly access: JwtToken<CoSecJwtPayload>;

  /**
   * The refresh JWT token instance.
   */
  public readonly refresh: JwtToken<JwtPayload>;

  /**
   * Creates a new JwtCompositeToken instance.
   *
   * Initializes both access and refresh token instances with the provided early period.
   *
   * @param token The composite token containing access and refresh token strings
   * @param earlyPeriod The early expiration period in seconds (default: 0)
   * @param sessionId Session generation retained when restoring or refreshing a token.
   */
  constructor(
    public readonly token: CompositeToken,
    public readonly earlyPeriod: number = 0,
    public readonly sessionId: string = idGenerator.generateId(),
  ) {
    this.access = new JwtToken(token.accessToken, earlyPeriod);
    this.refresh = new JwtToken(token.refreshToken, earlyPeriod);
  }

  /**
   * Checks if the access token needs to be refreshed.
   *
   * @returns true if the access token is expired, false otherwise
   */
  get isRefreshNeeded(): boolean {
    return this.access.isExpired;
  }

  /**
   * Checks if the refresh token is still valid and can be used to refresh the access token.
   *
   * @returns true if the refresh token is not expired, false otherwise
   */
  get isRefreshable(): boolean {
    return !this.refresh.isExpired;
  }

  /**
   * Checks if the user is currently authenticated (access token is valid).
   *
   * @returns true if the access token is not expired, false otherwise
   */
  get authenticated(): boolean {
    return !this.access.isExpired;
  }
}

// A non-cryptographic fingerprint identifies the exact legacy record across tabs.
// It is not an authentication or integrity check; new sign-ins use random IDs.
function legacyTokenSessionId(token: CompositeToken): string {
  let hash = 0x6c62272e07bb014262b821756295c58dn;
  for (const byte of new TextEncoder().encode(
    JSON.stringify([token.accessToken, token.refreshToken]),
  )) {
    hash = BigInt.asUintN(
      128,
      (hash ^ BigInt(byte)) * 0x1000000000000000000013bn,
    );
  }
  return `legacy:${hash.toString(16).padStart(32, '0')}`;
}

/**
 * Serializer for JwtCompositeToken that handles conversion to and from JSON strings.
 *
 * This class provides serialization and deserialization functionality for JwtCompositeToken
 * instances, allowing them to be stored and retrieved from persistent storage.
 *
 * @example
 * ```typescript
 * const serializer = new JwtCompositeTokenSerializer(300);
 * const token = new JwtCompositeToken({ accessToken: '...', refreshToken: '...' });
 *
 * const serialized = serializer.serialize(token);
 * const deserialized = serializer.deserialize(serialized);
 * ```
 */
export class JwtCompositeTokenSerializer
  implements Serializer<string, JwtCompositeToken>, EarlyPeriodCapable
{
  /**
   * Creates a new JwtCompositeTokenSerializer instance.
   *
   * @param earlyPeriod The early expiration period in seconds to use for deserialized tokens (default: 0)
   */
  constructor(public readonly earlyPeriod: number = 0) {}

  /**
   * Deserializes a JSON string to a JwtCompositeToken.
   *
   * Parses the JSON string and creates a new JwtCompositeToken instance with the stored tokens.
   *
   * @param value The JSON string representation of a composite token
   * @returns A JwtCompositeToken instance
   * @throws SyntaxError if the JSON string is invalid
   * @throws TypeError if the parsed value is not a CompositeToken: a non-array object whose accessToken and refreshToken are strings
   */
  deserialize(value: string): JwtCompositeToken {
    const record: unknown = JSON.parse(value);
    if (!isCompositeToken(record)) {
      throw new TypeError('Invalid composite token');
    }
    const { sessionId, ...compositeToken } = record as CompositeToken & {
      sessionId?: unknown;
    };
    return new JwtCompositeToken(
      compositeToken,
      this.earlyPeriod,
      typeof sessionId === 'string' && sessionId.length > 0
        ? sessionId
        : legacyTokenSessionId(compositeToken),
    );
  }

  /** Restores the known object shape emitted by legacy token storage tabs. */
  deserializeLegacy(value: unknown): JwtCompositeToken {
    if (typeof value !== 'object' || value === null || !('token' in value)) {
      throw new TypeError('Invalid legacy composite token');
    }
    const token = value.token;
    if (
      typeof token !== 'object' ||
      token === null ||
      !('accessToken' in token) ||
      typeof token.accessToken !== 'string' ||
      !('refreshToken' in token) ||
      typeof token.refreshToken !== 'string'
    ) {
      throw new TypeError('Invalid legacy composite token');
    }
    return this.deserialize(this.serialize(value as JwtCompositeToken));
  }

  /**
   * Serializes a JwtCompositeToken to a JSON string.
   *
   * Converts the composite token to a JSON string for storage.
   *
   * @param value The JwtCompositeToken to serialize
   * @returns A JSON string representation of the composite token
   */
  serialize(value: JwtCompositeToken): string {
    return JSON.stringify({ ...value.token, sessionId: value.sessionId });
  }
}

/**
 * Default instance of JwtCompositeTokenSerializer with no early period.
 *
 * This pre-configured serializer can be used directly for basic serialization needs.
 */
export const jwtCompositeTokenSerializer = new JwtCompositeTokenSerializer();
