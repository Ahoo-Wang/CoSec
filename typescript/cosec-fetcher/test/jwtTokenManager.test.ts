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

    tokenStorage.setCompositeToken(compositeToken);
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

    tokenStorage.setCompositeToken(compositeToken);

    let resolvePromise: (value: CompositeToken) => void = () => {};
    const refreshPromise = new Promise<CompositeToken>(resolve => {
      resolvePromise = resolve;
    });

    vi.mocked(tokenRefresher.refresh).mockReturnValueOnce(
      refreshPromise as any,
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
    expect(failure.token).toBe(jwtCompositeToken);
    expect(tokenStorage.get()).toBe(jwtCompositeToken);
  });

  it('should clear refreshInProgress after successful refresh', async () => {
    // Setup
    const compositeToken = {
      accessToken: 'access-token',
      refreshToken: 'refresh-token',
    };

    const newCompositeToken = {
      accessToken: 'new-access-token',
      refreshToken: 'new-refresh-token',
    };

    tokenStorage.setCompositeToken(compositeToken);
    vi.mocked(tokenRefresher.refresh).mockResolvedValueOnce(newCompositeToken);

    // Act
    await jwtTokenManager.refresh();

    // After await, refreshInProgress should be undefined
    expect((jwtTokenManager as any).refreshInProgress).toBeUndefined();
  });

  it('should clear refreshInProgress after failed refresh', async () => {
    // Setup
    const compositeToken = {
      accessToken: 'access-token',
      refreshToken: 'refresh-token',
    };

    // The server rejects the refresh token (a 4xx).
    const error = Object.assign(new Error('Refresh failed'), {
      exchange: { response: { status: 401 } },
    });
    tokenStorage.setCompositeToken(compositeToken);
    vi.mocked(tokenRefresher.refresh).mockRejectedValueOnce(error);

    // Act & Assert
    await expect(jwtTokenManager.refresh()).rejects.toThrow(RefreshTokenError);

    expect((jwtTokenManager as any).refreshInProgress).toBeUndefined();
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
      const original = tokenStorage.get();
      vi.mocked(tokenRefresher.refresh).mockResolvedValueOnce(response as any);

      const error = await jwtTokenManager.refresh().catch(e => e);

      expect(error).toBeInstanceOf(RefreshTokenError);
      expect(error.token).toBe(original);
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
    const token = new JwtCompositeToken({
      accessToken: 'access-token',
      refreshToken: 'refresh-token',
    });

    const refreshError = new AppRefreshTokenError(token);
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
