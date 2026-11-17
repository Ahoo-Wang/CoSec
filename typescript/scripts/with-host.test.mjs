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

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { commandOf, readyUrl } from './with-host.mjs';

describe('with-host', () => {
  it('runs what follows --', () => {
    assert.deepEqual(
      commandOf(['node', 'x', '--', 'pnpm', '-r', 'test:host']),
      ['node', 'x', '--', 'pnpm', '-r', 'test:host'].slice(3),
    );
  });

  it('reads the URL the host announced, and only a finished line', () => {
    assert.equal(
      readyUrl('WARN something\nREADY http://localhost:41234\n'),
      'http://localhost:41234',
    );
    assert.equal(readyUrl('READY http://localhost:41'), null);
    assert.equal(readyUrl('not READY http://localhost:41234'), null);
  });
});
