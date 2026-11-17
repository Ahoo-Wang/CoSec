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
/**
 * Interface representing a JWT payload as defined in RFC 7519.
 * Contains standard JWT claims as well as custom properties.
 */
export interface JwtPayload {
  /**
   * JWT ID - provides a unique identifier for the JWT.
   */
  jti: string;
  /**
   * Subject - identifies the principal that is the subject of the JWT.
   */
  sub: string;
  /**
   * Issuer - identifies the principal that issued the JWT.
   */
  iss?: string;
  /**
   * Audience - identifies the recipients that the JWT is intended for.
   * Can be a single string or an array of strings.
   */
  aud?: string | string[];
  /**
   * Expiration Time - identifies the expiration time on or after which the JWT MUST NOT be accepted for processing.
   * Represented as NumericDate (seconds since Unix epoch).
   */
  exp: number;
  /**
   * Not Before - identifies the time before which the JWT MUST NOT be accepted for processing.
   * Represented as NumericDate (seconds since Unix epoch).
   */
  nbf?: number;
  /**
   * Issued At - identifies the time at which the JWT was issued.
   * Represented as NumericDate (seconds since Unix epoch).
   */
  iat: number;

  /**
   * Allows additional custom properties to be included in the payload.
   */
  [key: string]: any;
}

/**
 * Interface representing a JWT payload with CoSec-specific extensions.
 * Extends the standard JwtPayload interface with additional CoSec-specific properties.
 */
export interface CoSecJwtPayload extends JwtPayload {
  /**
   * Tenant identifier - identifies the tenant scope for the JWT.
   */
  tenantId?: string;
  /**
   * Policies - array of policy identifiers associated with the JWT.
   * These are security policies defined internally by Cosec.
   */
  policies?: string[];
  /**
   * Roles - array of role identifiers associated with the JWT.
   * Role IDs indicate what roles the token belongs to.
   */
  roles?: string[];
  /**
   * Attributes - custom key-value pairs providing additional information about the JWT.
   */
  attributes?: Record<string, any>;
  /** The session the pair belongs to (CoSec 8.0). Display only: nothing here depends on it. */
  sid?: string;
  /** When the user signed in, in seconds (CoSec 8.0). */
  auth_time?: number;
}

/**
 * Parses a JWT token and extracts its payload.
 *
 * This function decodes the payload part of a JWT token, handling Base64URL decoding
 * and JSON parsing. It validates the token structure and returns null for invalid tokens.
 *
 * @param token - The JWT token string to parse
 * @returns The parsed JWT payload or null if parsing fails
 */
export function parseJwtPayload<T extends JwtPayload>(token: string): T | null {
  try {
    if (typeof token !== 'string') {
      return null;
    }
    const parts = token.split('.');
    if (parts.length !== 3) {
      return null;
    }

    const base64Url = parts[1];
    const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');

    // Add padding if needed
    const paddedBase64 = base64.padEnd(
      base64.length + ((4 - (base64.length % 4)) % 4),
      '=',
    );

    const binaryBytes = Uint8Array.from(atob(paddedBase64), c =>
      c.charCodeAt(0),
    );
    const jsonPayload = new TextDecoder('utf-8').decode(binaryBytes);
    const payload: unknown = JSON.parse(jsonPayload);
    // A JWT claims set is a JSON object; `123` or `"x"` has no `exp`, and
    // would otherwise read as a token that never expires.
    if (
      typeof payload !== 'object' ||
      payload === null ||
      Array.isArray(payload)
    ) {
      return null;
    }
    return payload as T;
  } catch (error) {
    // Avoid exposing sensitive information in error logs
    console.error('Failed to parse JWT token', error);
    return null;
  }
}

export interface EarlyPeriodCapable {
  /**
   * The time in seconds before actual expiration when the token should be considered expired (default: 0)
   */
  readonly earlyPeriod: number;
}

/**
 * Checks if a JWT token is expired based on its expiration time (exp claim).
 *
 * This function determines if a JWT token has expired by comparing its exp claim
 * with the current time. If the token is a string, it will be parsed first.
 * Tokens without an exp claim are considered not expired.
 *
 * The early period parameter allows for early token expiration, which is useful
 * for triggering token refresh before the token actually expires. This helps
 * avoid race conditions where a token expires between the time it is checked and
 * the time it is used.
 *
 * @param token - The JWT token to check, either as a string or as a JwtPayload object
 * @param earlyPeriod - The time in seconds before actual expiration when the token should be considered expired (default: 0)
 * @returns true if the token is expired (or will expire within the early period) or cannot be parsed, false otherwise
 */
export function isTokenExpired(
  token: string | CoSecJwtPayload,
  earlyPeriod: number = 0,
): boolean {
  const payload = typeof token === 'string' ? parseJwtPayload(token) : token;
  if (!payload) {
    return true;
  }

  const expAt = payload.exp;
  if (expAt == null) {
    return false;
  }

  if (typeof expAt !== 'number' || !Number.isFinite(expAt)) {
    return true;
  }

  const now = Date.now() / 1000;
  return now >= expAt - earlyPeriod;
}
