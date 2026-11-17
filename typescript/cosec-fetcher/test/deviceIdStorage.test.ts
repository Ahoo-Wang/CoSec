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
import { DeviceIdStorage, DEFAULT_COSEC_DEVICE_ID_KEY } from '../src';

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

// Mock idGenerator
vi.mock('../src/idGenerator', () => ({
  idGenerator: {
    generateId: vi.fn(() => 'generated-device-id'),
  },
}));

describe('DeviceIdStorage', () => {
  let deviceIdStorage: DeviceIdStorage;

  beforeEach(() => {
    vi.clearAllMocks();
    mockStorage.getItem.mockReturnValue(null);

    deviceIdStorage = new DeviceIdStorage({
      key: DEFAULT_COSEC_DEVICE_ID_KEY,
      storage: mockStorage as any,
    });
  });

  describe('constructor', () => {
    it('should initialize with all default values when no options provided', () => {
      // Test the default constructor: new DeviceIdStorage()
      const defaultStorage = new DeviceIdStorage();
      expect(defaultStorage).toBeDefined();
      // The constructor should create default key and eventBus internally
    });

    it('should initialize with custom key only', () => {
      // Test partial customization: new DeviceIdStorage({ key: 'custom' })
      const storage = new DeviceIdStorage({ key: 'custom-key' });
      expect(storage).toBeDefined();
      // Should use custom key but default eventBus
    });

    // Regression test: the default event bus channel must be derived from the
    // actual key. Otherwise storages for different keys share one broadcast
    // channel and cross-contaminate each other's cached value across tabs.
    it('should derive the default event bus channel from the storage key', () => {
      serialBusChannels.length = 0;
      new DeviceIdStorage({ key: 'my-device-id', storage: mockStorage as any });
      expect(serialBusChannels).toContain('my-device-id');
      expect(serialBusChannels).not.toContain(DEFAULT_COSEC_DEVICE_ID_KEY);
    });

    it('should initialize with custom eventBus only', () => {
      // Test partial customization: new DeviceIdStorage({ eventBus: custom })
      const mockEventBus = {
        emit: vi.fn().mockResolvedValue(undefined),
        on: vi.fn(),
        off: vi.fn(),
        destroy: vi.fn(),
      };
      const storage = new DeviceIdStorage({ eventBus: mockEventBus as any });
      expect(storage).toBeDefined();
      // Should use custom eventBus but default key
    });

    it('should initialize with all custom options', () => {
      // Test full customization
      const mockEventBus = {
        emit: vi.fn().mockResolvedValue(undefined),
        on: vi.fn(),
        off: vi.fn(),
        destroy: vi.fn(),
      };
      const customStorage = new DeviceIdStorage({
        key: 'custom-key',
        eventBus: mockEventBus as any,
        storage: mockStorage as any,
      });
      expect(customStorage).toBeDefined();
      // Should use all custom options
    });
  });

  describe('generateDeviceId', () => {
    it('should generate a new device ID', () => {
      const deviceId = deviceIdStorage.generateDeviceId();
      expect(deviceId).toBe('generated-device-id');
    });
  });

  describe('getOrCreate', () => {
    it('should return existing device ID if available', () => {
      mockStorage.getItem.mockReturnValue('existing-device-id');
      const deviceId = deviceIdStorage.getOrCreate();
      expect(deviceId).toBe('existing-device-id');
      expect(mockStorage.setItem).not.toHaveBeenCalled();
    });

    it('should generate and store new device ID if none exists', () => {
      mockStorage.getItem.mockReturnValue(null);
      const deviceId = deviceIdStorage.getOrCreate();
      expect(deviceId).toBe('generated-device-id');
      expect(mockStorage.setItem).toHaveBeenCalledWith(
        DEFAULT_COSEC_DEVICE_ID_KEY,
        'generated-device-id',
      );
    });
  });

  describe('inherited KeyStorage methods', () => {
    it('should get device ID', () => {
      mockStorage.getItem.mockReturnValue('stored-device-id');
      const deviceId = deviceIdStorage.get();
      expect(deviceId).toBe('stored-device-id');
    });

    it('should set device ID', () => {
      deviceIdStorage.set('new-device-id');
      expect(mockStorage.setItem).toHaveBeenCalledWith(
        DEFAULT_COSEC_DEVICE_ID_KEY,
        'new-device-id',
      );
    });

    it('should remove device ID', () => {
      deviceIdStorage.remove();
      expect(mockStorage.removeItem).toHaveBeenCalledWith(
        DEFAULT_COSEC_DEVICE_ID_KEY,
      );
    });

    it('should add listener', () => {
      const listener = vi.fn();
      const remove = deviceIdStorage.addListener({
        name: 'test',
        handle: listener,
      });
      expect(typeof remove).toBe('function');
      remove();
    });

    it('should destroy', () => {
      deviceIdStorage.destroy();
      // destroy removes the internal handler, but since it's mocked, just ensure no error
    });
  });
});
