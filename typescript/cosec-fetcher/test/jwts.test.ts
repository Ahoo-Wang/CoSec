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

import { describe, expect, it, vi } from 'vitest';
import type { CoSecJwtPayload } from '../src';
import { parseJwtPayload } from '../src';
import { isTokenExpired } from '../src';

describe('jwts', () => {
  const consoleErrorSpy = vi
    .spyOn(console, 'error')
    .mockImplementation(() => {});
  describe('parseJwtPayload', () => {
    it('should parse a valid JWT token and return its payload', () => {
      // A JWT token with payload: {"sub": "1234567890", "name": "John Doe", "iat": 1516239022}
      const token =
        'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c';
      const payload = parseJwtPayload(token);

      expect(payload).toBeDefined();
      expect(payload?.sub).toBe('1234567890');
      expect(payload?.name).toBe('John Doe');
      expect(payload?.iat).toBe(1516239022);
    });

    it('should return null for an invalid JWT token with wrong number of parts', () => {
      const token = 'invalid.token';
      const payload = parseJwtPayload(token);

      expect(payload).toBeNull();
    });

    it('should return null for a non-string input', () => {
      // @ts-expect-error Testing invalid input
      const payload = parseJwtPayload(null);

      expect(payload).toBeNull();
    });

    it('should return null for an empty string', () => {
      const payload = parseJwtPayload('');

      expect(payload).toBeNull();
    });

    it('should return null for a token with invalid Base64 payload', () => {
      // Header: {"alg": "HS256", "typ": "JWT"}
      // Payload: Invalid Base64 string
      const token =
        'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.invalid_payload.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c';
      const payload = parseJwtPayload(token);

      expect(payload).toBeNull();
    });

    it('should return null for a token with non-JSON payload', () => {
      // Header: {"alg": "HS256", "typ": "JWT"}
      // Payload: "invalid" (not JSON)
      const token =
        'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.aW52YWxpZA==.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c';
      const payload = parseJwtPayload(token);

      expect(payload).toBeNull();
    });

    it('should handle JWT with Base64URL encoding correctly', () => {
      // A JWT token with payload: {"iss": "test_issuer", "exp": 16094592}
      // Contains Base64URL encoded characters (- and _)
      const token =
        'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJ0ZXN0X2lzc3VlciIsImV4cCI6MTYwOTQ1OTJ9.fZl8SkVX449q5Zjpe7R0QDI6H5nCJQY4V5bE95iY44A';
      const payload = parseJwtPayload(token);

      expect(payload).toBeDefined();
      expect(payload?.iss).toBe('test_issuer');
      expect(payload?.exp).toBe(16094592);
    });

    it('should handle JWT with complex payload including arrays', () => {
      // A JWT token with payload: {"aud": ["service1", "service2"], "roles": ["admin", "user"]}
      const token =
        'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJhdWQiOlsic2VydmljZTEiLCJzZXJ2aWNlMiJdLCJyb2xlcyI6WyJhZG1pbiIsInVzZXIiXX0.5F4CQnY6h8QkjcOqZ39D3MqG_u9hXvUPso74KZy5rZc';
      const payload = parseJwtPayload(token);

      expect(payload).toBeDefined();
      expect(payload?.aud).toEqual(['service1', 'service2']);
      expect(payload?.roles).toEqual(['admin', 'user']);
    });
  });

  describe('isTokenExpired', () => {
    it('should return true for an expired token (string)', () => {
      // A token with exp: 1609459200 (2021-01-01 00:00:00 UTC)
      const expiredToken =
        'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJleHAiOjE2MDk0NTkyMDB9.XYS34_symbols_for_padding';
      const isExpired = isTokenExpired(expiredToken);

      expect(isExpired).toBe(true);
    });

    it('should return true for a token that cannot be parsed', () => {
      const invalidToken = 'invalid.token';
      const isExpired = isTokenExpired(invalidToken);

      expect(isExpired).toBe(true);
    });

    it('should return false for a token without exp claim (string)', () => {
      // A token without exp claim
      const tokenWithoutExp =
        'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c';
      const isExpired = isTokenExpired(tokenWithoutExp);

      expect(isExpired).toBe(false);
    });

    it('should return true for an expired token (JwtPayload)', () => {
      // Payload with past expiration time
      const expiredPayload: CoSecJwtPayload = {
        exp: 1609459200, // 2021-01-01 00:00:00 UTC
      };
      const isExpired = isTokenExpired(expiredPayload);

      expect(isExpired).toBe(true);
    });

    it('should return false for a non-expired token (JwtPayload)', () => {
      // Payload with future expiration time
      const futureExp = Math.floor(Date.now() / 1000) + 3600; // 1 hour from now
      const nonExpiredPayload: CoSecJwtPayload = {
        exp: futureExp,
      };
      const isExpired = isTokenExpired(nonExpiredPayload);

      expect(isExpired).toBe(false);
    });

    it('should apply early period in seconds', () => {
      vi.useFakeTimers();
      try {
        vi.setSystemTime(new Date('2026-06-02T00:00:00.000Z'));
        const now = Date.now() / 1000;

        expect(isTokenExpired({ exp: now + 60 } as CoSecJwtPayload, 30)).toBe(
          false,
        );
        expect(isTokenExpired({ exp: now + 30 } as CoSecJwtPayload, 30)).toBe(
          true,
        );
      } finally {
        vi.useRealTimers();
      }
    });

    it('should return true when current time equals exp claim', () => {
      vi.useFakeTimers();
      try {
        vi.setSystemTime(new Date('2026-06-02T00:00:00.000Z'));
        const payload: CoSecJwtPayload = {
          exp: Date.now() / 1000,
        };

        expect(isTokenExpired(payload)).toBe(true);
      } finally {
        vi.useRealTimers();
      }
    });

    it('should read exp claim from a prototype getter', () => {
      vi.useFakeTimers();
      try {
        vi.setSystemTime(new Date('2026-06-02T00:00:00.000Z'));
        class PayloadWithExpGetter {
          get exp() {
            return Date.now() / 1000;
          }
        }

        expect(
          isTokenExpired(new PayloadWithExpGetter() as CoSecJwtPayload),
        ).toBe(true);
      } finally {
        vi.useRealTimers();
      }
    });

    it('should return true for zero exp claim', () => {
      expect(isTokenExpired({ exp: 0 } as CoSecJwtPayload)).toBe(true);
    });

    it('should return true for invalid exp claim', () => {
      expect(isTokenExpired({ exp: Number.NaN } as CoSecJwtPayload)).toBe(true);
    });

    it('should return false for a payload without exp claim', () => {
      // Payload without exp claim
      const payloadWithoutExp: CoSecJwtPayload = {
        sub: '1234567890',
        name: 'John Doe',
      };
      const isExpired = isTokenExpired(payloadWithoutExp);

      expect(isExpired).toBe(false);
    });

    it('should return true for a null payload', () => {
      // @ts-expect-error Testing invalid input
      const isExpired = isTokenExpired(null);

      expect(isExpired).toBe(true);
    });
  });
});

describe('parseJwtPayload rejects a claims set that is not an object', () => {
  it.each(['123', '"text"', '[1]', 'null'])('payload %s', json => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const token = `e30.${btoa(json)}.signature`;
    expect(parseJwtPayload(token)).toBeNull();
    // So it reads as expired, not as a token that never expires.
    expect(isTokenExpired(token)).toBe(true);
  });
});
