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

import { describe, expect, it } from 'vitest';
import type { IdGenerator } from '../src';
import { idGenerator, NanoIdGenerator } from '../src';

describe('idGenerator.ts', () => {
  describe('NanoIdGenerator', () => {
    it('should create NanoIdGenerator instance', () => {
      const generator = new NanoIdGenerator();
      expect(generator).toBeInstanceOf(NanoIdGenerator);
    });

    it('should implement IdGenerator interface', () => {
      const generator: IdGenerator = new NanoIdGenerator();
      expect(generator.generateId).toBeDefined();
    });

    it('should generate unique IDs', () => {
      const generator = new NanoIdGenerator();
      const id1 = generator.generateId();
      const id2 = generator.generateId();

      expect(id1).toBeDefined();
      expect(id2).toBeDefined();
      expect(id1).not.toBe(id2);
      expect(typeof id1).toBe('string');
      expect(typeof id2).toBe('string');
    });

    it('should generate non-empty IDs', () => {
      const generator = new NanoIdGenerator();
      const id = generator.generateId();

      expect(id).toBeDefined();
      expect(id.length).toBeGreaterThan(0);
    });
  });

  describe('idGenerator', () => {
    it('should export a default idGenerator instance', () => {
      expect(idGenerator).toBeDefined();
      expect(idGenerator).toBeInstanceOf(NanoIdGenerator);
    });

    it('should generate unique IDs using default instance', () => {
      const id1 = idGenerator.generateId();
      const id2 = idGenerator.generateId();

      expect(id1).toBeDefined();
      expect(id2).toBeDefined();
      expect(id1).not.toBe(id2);
      expect(typeof id1).toBe('string');
      expect(typeof id2).toBe('string');
    });
  });
});
