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
import { TokenStorage, DEFAULT_COSEC_TOKEN_KEY } from '../src';
import { JwtCompositeToken, JwtCompositeTokenSerializer } from '../src';

// Mock Storage
const mockStorage = {
  getItem: vi.fn(),
  setItem: vi.fn(),
  removeItem: vi.fn(),
  clear: vi.fn(),
  key: vi.fn(),
  length: 0,
};

// Mock BroadcastChannel
class MockBroadcastChannel {
  postMessage = vi.fn();
  close = vi.fn();
  onmessage = null;
  constructor(_name: string) {}
}

vi.stubGlobal('BroadcastChannel', MockBroadcastChannel);
vi.stubGlobal('window', { localStorage: mockStorage });

// Records the channel name of every SerialTypedEventBus constructed by the
// module mock below, so tests can assert channel-name derivation.
const { serialBusChannels } = vi.hoisted(() => ({
  serialBusChannels: [] as string[],
}));

// Mock BroadcastTypedEventBus and SerialTypedEventBus
vi.mock('@ahoo-wang/fetcher-eventbus', () => ({
  BroadcastTypedEventBus: class BroadcastTypedEventBus {
    emit = vi.fn().mockResolvedValue(undefined);
    on = vi.fn();
    off = vi.fn();
    destroy = vi.fn();
  },
  SerialTypedEventBus: class SerialTypedEventBus {
    emit = vi.fn().mockResolvedValue(undefined);
    on = vi.fn();
    off = vi.fn();
    destroy = vi.fn();
    constructor(name: string) {
      serialBusChannels.push(name);
    }
  },
  nameGenerator: {
    generate: vi.fn((prefix: string) => `${prefix}_1`),
  },
}));

// Mock JwtCompositeToken
vi.mock('../src/jwtToken', () => ({
  JwtCompositeToken: class JwtCompositeToken {
    token = { accessToken: '', refreshToken: '' };
    authenticated = true;
    access = { payload: { userId: 'default', username: 'default' } };
    isRefreshNeeded = vi.fn(() => false);
    isRefreshable = vi.fn(() => true);
    constructor(_token: { accessToken: string; refreshToken: string }) {
      this.token = _token;
    }
  },
  JwtCompositeTokenSerializer: class JwtCompositeTokenSerializer {
    serialize = vi.fn(
      (value: { token: { accessToken: string; refreshToken: string } }) =>
        JSON.stringify(value.token),
    );
    deserialize = vi.fn((value: string) => {
      const token = JSON.parse(value);
      return {
        token,
        authenticated: true,
        access: { payload: { userId: '123', username: 'testuser' } },
        isRefreshNeeded: vi.fn(() => false),
        isRefreshable: vi.fn(() => true),
      };
    });
    constructor(_earlyPeriod: number) {}
  },
}));

describe('TokenStorage', () => {
  let tokenStorage: TokenStorage;

  beforeEach(() => {
    vi.clearAllMocks();
    mockStorage.getItem.mockReturnValue(null);
    mockStorage.setItem.mockImplementation(() => {});
    mockStorage.removeItem.mockImplementation(() => {});

    tokenStorage = new TokenStorage({
      key: DEFAULT_COSEC_TOKEN_KEY,
      storage: mockStorage as any,
    });
  });

  describe('constructor', () => {
    it('should initialize with default options', () => {
      const mockEventBus = {
        emit: vi.fn().mockResolvedValue(undefined),
        on: vi.fn(),
        off: vi.fn(),
        destroy: vi.fn(),
      };
      const defaultStorage = new TokenStorage({
        key: 'test-token',
        eventBus: mockEventBus as any,
      });
      expect(defaultStorage).toBeDefined();
      expect(defaultStorage.earlyPeriod).toBe(0);
    });

    it('should initialize with custom options', () => {
      const customStorage = new TokenStorage({
        key: 'custom-key',
        earlyPeriod: 300,
        storage: mockStorage as any,
      });
      expect(customStorage).toBeDefined();
      expect(customStorage.earlyPeriod).toBe(300);
    });

    // Regression test: the default event bus channel must be derived from the
    // actual key. Otherwise storages for different keys share one broadcast
    // channel and cross-contaminate each other's cached token across tabs.
    it('should derive the default event bus channel from the storage key', () => {
      serialBusChannels.length = 0;
      new TokenStorage({ key: 'my-token', storage: mockStorage as any });
      expect(serialBusChannels).toContain('my-token');
      expect(serialBusChannels).not.toContain(DEFAULT_COSEC_TOKEN_KEY);
    });
  });

  describe('setCompositeToken', () => {
    it('should set composite token', () => {
      const compositeToken = {
        accessToken: 'access-token',
        refreshToken: 'refresh-token',
      };
      tokenStorage.setCompositeToken(compositeToken);
      expect(mockStorage.setItem).toHaveBeenCalledWith(
        DEFAULT_COSEC_TOKEN_KEY,
        JSON.stringify(compositeToken),
      );
    });
  });

  describe('signIn', () => {
    it('should sign in with composite token', () => {
      const compositeToken = {
        accessToken: 'access-token',
        refreshToken: 'refresh-token',
      };
      tokenStorage.signIn(compositeToken);
      expect(mockStorage.setItem).toHaveBeenCalledWith(
        DEFAULT_COSEC_TOKEN_KEY,
        JSON.stringify(compositeToken),
      );
    });
  });

  describe('signOut', () => {
    it('should sign out by removing token', () => {
      tokenStorage.signOut();
      expect(mockStorage.removeItem).toHaveBeenCalledWith(
        DEFAULT_COSEC_TOKEN_KEY,
      );
    });
  });

  describe('authenticated', () => {
    it('should return true when authenticated', () => {
      const storedToken = {
        accessToken: 'stored-access',
        refreshToken: 'stored-refresh',
      };
      mockStorage.getItem.mockReturnValue(JSON.stringify(storedToken));
      const mockJwtToken = {
        token: storedToken,
        authenticated: true,
        isRefreshNeeded: vi.fn(() => false),
        isRefreshable: vi.fn(() => true),
      };
      vi.spyOn(tokenStorage, 'get').mockReturnValue(mockJwtToken as any);
      expect(tokenStorage.authenticated).toBe(true);
    });

    it('should return false when not authenticated', () => {
      mockStorage.getItem.mockReturnValue(null);
      expect(tokenStorage.authenticated).toBe(false);
    });

    it('should return false when token exists but not authenticated', () => {
      const mockJwtToken = {
        token: { accessToken: 'stored-access', refreshToken: 'stored-refresh' },
        authenticated: false,
        access: { payload: null },
        isRefreshNeeded: vi.fn(() => false),
        isRefreshable: vi.fn(() => true),
      };
      vi.spyOn(tokenStorage, 'get').mockReturnValue(mockJwtToken as any);
      expect(tokenStorage.authenticated).toBe(false);
    });
  });

  describe('currentUser', () => {
    it('should return user payload when authenticated', () => {
      const mockPayload = { userId: '123', username: 'testuser' };
      const mockJwtToken = {
        token: { accessToken: 'stored-access', refreshToken: 'stored-refresh' },
        authenticated: true,
        access: { payload: mockPayload },
        isRefreshNeeded: vi.fn(() => false),
        isRefreshable: vi.fn(() => true),
      };
      vi.spyOn(tokenStorage, 'get').mockReturnValue(mockJwtToken as any);
      expect(tokenStorage.currentUser).toEqual(mockPayload);
    });

    it('should return null when not authenticated', () => {
      mockStorage.getItem.mockReturnValue(null);
      expect(tokenStorage.currentUser).toBe(null);
    });

    it('should return null when authenticated but no access payload', () => {
      const mockJwtToken = {
        token: { accessToken: 'stored-access', refreshToken: 'stored-refresh' },
        authenticated: true,
        access: { payload: null },
        isRefreshNeeded: vi.fn(() => false),
        isRefreshable: vi.fn(() => true),
      };
      vi.spyOn(tokenStorage, 'get').mockReturnValue(mockJwtToken as any);
      expect(tokenStorage.currentUser).toBe(null);
    });
  });

  describe('inherited KeyStorage methods', () => {
    it('should get token', () => {
      const storedToken = {
        accessToken: 'stored-access',
        refreshToken: 'stored-refresh',
      };
      mockStorage.getItem.mockReturnValue(JSON.stringify(storedToken));
      const token = tokenStorage.get();
      expect(token).toBeDefined();
      expect(token?.token).toEqual(storedToken);
    });

    it('should set token', () => {
      const jwtToken = {
        token: { accessToken: 'access', refreshToken: 'refresh' },
        isRefreshNeeded: vi.fn(),
        isRefreshable: vi.fn(),
      };
      tokenStorage.set(jwtToken as any);
      expect(mockStorage.setItem).toHaveBeenCalledWith(
        DEFAULT_COSEC_TOKEN_KEY,
        JSON.stringify(jwtToken.token),
      );
    });

    it('should set token', () => {
      const jwtToken = new JwtCompositeToken({
        accessToken: 'access',
        refreshToken: 'refresh',
      });
      tokenStorage.set(jwtToken);
      expect(mockStorage.setItem).toHaveBeenCalledWith(
        DEFAULT_COSEC_TOKEN_KEY,
        JSON.stringify(jwtToken.token),
      );
    });

    it('should remove token', () => {
      tokenStorage.remove();
      expect(mockStorage.removeItem).toHaveBeenCalledWith(
        DEFAULT_COSEC_TOKEN_KEY,
      );
    });

    it('should add listener', () => {
      const listener = vi.fn();
      const remove = tokenStorage.addListener({
        name: 'test',
        handle: listener,
      });
      expect(typeof remove).toBe('function');
      remove();
    });

    it('should destroy', () => {
      tokenStorage.destroy();
    });
  });
});
