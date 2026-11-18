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

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { FetcherError } from '@ahoo-wang/fetcher';
import {
  JwtTokenManager,
  RefreshSessionChangedError,
  RefreshTokenError,
  RefreshUnavailableError,
} from '../src';
import { TokenStorage } from '../src';
import type { TokenRefresher, CompositeToken } from '../src';
import { JwtCompositeToken } from '../src';

describe('JwtTokenManager', () => {
  let tokenStorage: TokenStorage;
  let tokenRefresher: TokenRefresher;
  let jwtTokenManager: JwtTokenManager;
  let mockStorage: Storage;

  beforeEach(() => {
    // Remembers what it stores, as Web Storage does: the manager re-reads
    // storage when a refresh fails.
    const stored = new Map<string, string>();
    mockStorage = {
      getItem: vi.fn((key: string) => stored.get(key) ?? null),
      setItem: vi.fn((key: string, value: string) => {
        stored.set(key, value);
      }),
      removeItem: vi.fn((key: string) => {
        stored.delete(key);
      }),
      clear: vi.fn(),
      key: vi.fn(),
      length: 0,
    };
    tokenStorage = new TokenStorage({
      key: 'test-token',
      storage: mockStorage,
    });
    tokenRefresher = { refresh: vi.fn() } as unknown as TokenRefresher;
    jwtTokenManager = new JwtTokenManager(tokenStorage, tokenRefresher);
  });

  it('should return null when no token is stored', () => {
    expect(jwtTokenManager.currentToken).toBeNull();
    expect(jwtTokenManager.isRefreshNeeded).toBe(false);
    expect(jwtTokenManager.isRefreshable).toBe(false);
  });

  it('should refresh token and update storage', async () => {
    // Setup
    const compositeToken = {
      accessToken: 'access-token',
      refreshToken: 'refresh-token',
    };

    const newCompositeToken = {
      accessToken: 'new-access-token',
      refreshToken: 'new-refresh-token',
    };

    tokenStorage.signIn(compositeToken);
    vi.mocked(tokenRefresher.refresh).mockResolvedValueOnce(newCompositeToken);

    // Act
    await jwtTokenManager.refresh();

    // Assert
    expect(tokenRefresher.refresh).toHaveBeenCalledWith(compositeToken);
    expect(tokenStorage.get()).not.toBeNull();
  });

  it('should throw error when no token found', async () => {
    await expect(jwtTokenManager.refresh()).rejects.toThrow('No token found');
  });

  it('should prevent concurrent refresh requests', async () => {
    // Setup
    const compositeToken = {
      accessToken: 'access-token',
      refreshToken: 'refresh-token',
    };

    const newCompositeToken = {
      accessToken: 'new-access-token',
      refreshToken: 'new-refresh-token',
    };

    tokenStorage.signIn(compositeToken);

    let resolvePromise: (value: CompositeToken) => void = () => {};
    const refreshPromise = new Promise<CompositeToken>(resolve => {
      resolvePromise = resolve;
    });

    vi.mocked(tokenRefresher.refresh).mockReturnValueOnce(
      refreshPromise as unknown as ReturnType<TokenRefresher['refresh']>,
    );

    // Act
    const firstRefreshPromise = jwtTokenManager.refresh();
    const secondRefreshPromise = jwtTokenManager.refresh();

    resolvePromise(newCompositeToken);

    await firstRefreshPromise;
    await secondRefreshPromise;

    // Assert
    expect(tokenRefresher.refresh).toHaveBeenCalledTimes(1);
  });

  it('should use stored token when no token provided', async () => {
    // Setup
    const compositeToken = {
      accessToken: 'access-token',
      refreshToken: 'refresh-token',
    };

    const jwtCompositeToken = new JwtCompositeToken(compositeToken);
    tokenStorage.set(jwtCompositeToken);

    const newCompositeToken = {
      accessToken: 'new-access-token',
      refreshToken: 'new-refresh-token',
    };

    vi.mocked(tokenRefresher.refresh).mockResolvedValueOnce(newCompositeToken);

    // Act
    await jwtTokenManager.refresh();

    // Assert
    expect(tokenRefresher.refresh).toHaveBeenCalledWith(compositeToken);
  });

  it('should remove token from storage when refresh fails', async () => {
    // Setup
    const compositeToken = {
      accessToken: 'access-token',
      refreshToken: 'refresh-token',
    };

    const jwtCompositeToken = new JwtCompositeToken(compositeToken);
    tokenStorage.set(jwtCompositeToken);

    // The server rejects the refresh token (a 4xx).
    const error = Object.assign(new Error('Refresh failed'), {
      exchange: { response: { status: 401 } },
    });
    vi.mocked(tokenRefresher.refresh).mockRejectedValueOnce(error);

    // Act & Assert
    await expect(jwtTokenManager.refresh()).rejects.toThrow(
      'Refresh token failed.',
    );

    expect(tokenStorage.get()).toBeNull();
  });

  it.each([
    ['a network failure', new TypeError('Failed to fetch')],
    ['a timeout or other error without a response', new Error('timeout')],
    [
      'a 5xx answer',
      Object.assign(new Error('unavailable'), {
        exchange: { response: { status: 502 } },
      }),
    ],
  ])('keeps the token when refresh fails with %s', async (_name, error) => {
    const jwtCompositeToken = new JwtCompositeToken({
      accessToken: 'access-token',
      refreshToken: 'refresh-token',
    });
    tokenStorage.set(jwtCompositeToken);
    vi.mocked(tokenRefresher.refresh).mockRejectedValueOnce(error);

    const failure = await jwtTokenManager.refresh().catch(e => e);
    expect(failure).toBeInstanceOf(RefreshUnavailableError);
    expect(failure.cause).toBe(error);
    // The error carries no credential: error reporters serialize own properties.
    expect(failure).not.toHaveProperty('token');
    expect(tokenStorage.get()).toBe(jwtCompositeToken);
  });

  it('sends a new request for the refresh after a finished one', async () => {
    // Setup
    const compositeToken = {
      accessToken: 'access-token',
      refreshToken: 'refresh-token',
    };

    const newCompositeToken = {
      accessToken: 'new-access-token',
      refreshToken: 'new-refresh-token',
    };

    tokenStorage.signIn(compositeToken);
    vi.mocked(tokenRefresher.refresh).mockResolvedValueOnce(newCompositeToken);

    // Act
    await jwtTokenManager.refresh();

    // The finished refresh is not joined by the next one: it sends its own.
    vi.mocked(tokenRefresher.refresh).mockResolvedValueOnce(newCompositeToken);
    await jwtTokenManager.refresh();
    expect(tokenRefresher.refresh).toHaveBeenCalledTimes(2);
  });

  it('sends a new request for the refresh after a failed one', async () => {
    // Setup
    const compositeToken = {
      accessToken: 'access-token',
      refreshToken: 'refresh-token',
    };

    // The refresh route cannot be reached: the session is kept.
    tokenStorage.signIn(compositeToken);
    vi.mocked(tokenRefresher.refresh).mockRejectedValueOnce(
      new Error('network down'),
    );

    // Act & Assert
    await expect(jwtTokenManager.refresh()).rejects.toThrow(
      RefreshUnavailableError,
    );

    vi.mocked(tokenRefresher.refresh).mockResolvedValueOnce({
      accessToken: 'new-access-token',
      refreshToken: 'new-refresh-token',
    });
    await jwtTokenManager.refresh();
    expect(tokenRefresher.refresh).toHaveBeenCalledTimes(2);
  });

  it('should return false for isRefreshNeeded when token has isRefreshNeeded false', () => {
    // Setup
    const compositeToken = {
      accessToken: 'access-token',
      refreshToken: 'refresh-token',
    };

    // Create a mock JwtCompositeToken with isRefreshNeeded = false
    const jwtCompositeToken = {
      token: compositeToken,
      isRefreshNeeded: false,
      isRefreshable: true,
    } as unknown as JwtCompositeToken;

    vi.spyOn(tokenStorage, 'get').mockReturnValue(jwtCompositeToken);

    // Act & Assert
    expect(jwtTokenManager.isRefreshNeeded).toBe(false);
  });

  it('should return false for isRefreshable when token has isRefreshable false', () => {
    // Setup
    const compositeToken = {
      accessToken: 'access-token',
      refreshToken: 'refresh-token',
    };

    // Create a mock JwtCompositeToken with isRefreshable = false
    const jwtCompositeToken = {
      token: compositeToken,
      isRefreshNeeded: true,
      isRefreshable: false,
    } as unknown as JwtCompositeToken;

    vi.spyOn(tokenStorage, 'get').mockReturnValue(jwtCompositeToken);

    // Act & Assert
    expect(jwtTokenManager.isRefreshable).toBe(false);
  });

  it.each([
    ['nothing', undefined],
    ['a string', 'new-token'],
    ['no refresh token', { accessToken: 'new-access-token' }],
    ['a non-string access token', { accessToken: 1, refreshToken: 'r' }],
  ])(
    'rejects a refresh response with %s instead of storing it',
    async (_, response) => {
      tokenStorage.signIn({
        accessToken: 'access-token',
        refreshToken: 'refresh-token',
      });
      vi.mocked(tokenRefresher.refresh).mockResolvedValueOnce(
        response as unknown as CompositeToken,
      );

      const error = await jwtTokenManager.refresh().catch(e => e);

      expect(error).toBeInstanceOf(RefreshTokenError);
      expect(error).not.toHaveProperty('token');
      expect(tokenStorage.get()).toBeNull();
      // the token is stored once (the storage also holds the refresh
      // attempt mark, under another key)
      expect(
        mockStorage.setItem.mock.calls.filter(([key]) => key === 'test-token'),
      ).toHaveLength(1);
    },
  );
});

describe('refresh errors', () => {
  it('keeps subclasses recognisable', () => {
    class AppRefreshTokenError extends RefreshTokenError {}
    class AppSessionChangedError extends RefreshSessionChangedError {}
    const refreshError = new AppRefreshTokenError();
    const sessionError = new AppSessionChangedError();

    expect(refreshError).toBeInstanceOf(AppRefreshTokenError);
    expect(refreshError).toBeInstanceOf(RefreshTokenError);
    expect(refreshError).toBeInstanceOf(FetcherError);
    expect(refreshError.name).toBe('RefreshTokenError');
    expect(sessionError).toBeInstanceOf(AppSessionChangedError);
    expect(sessionError).toBeInstanceOf(RefreshSessionChangedError);
    expect(sessionError.name).toBe('RefreshSessionChangedError');
  });
});
