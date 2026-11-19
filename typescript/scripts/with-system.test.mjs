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
import { test } from 'node:test';
import { commandOf, gatewayUrl } from './with-system.mjs';

test('the gateway is named by COSEC_SYSTEM_GATEWAY_URL, without a trailing slash', () => {
  assert.equal(
    gatewayUrl({ COSEC_SYSTEM_GATEWAY_URL: 'http://127.0.0.1:8080/' }),
    'http://127.0.0.1:8080',
  );
});

test('a missing or malformed gateway address is refused, naming the variable', () => {
  assert.throws(() => gatewayUrl({}), /COSEC_SYSTEM_GATEWAY_URL is not set/);
  assert.throws(
    () => gatewayUrl({ COSEC_SYSTEM_GATEWAY_URL: 'ftp://x' }),
    /not an http\(s\) URL/,
  );
  assert.throws(() => gatewayUrl({ COSEC_SYSTEM_GATEWAY_URL: 'not a url' }));
});

test('the command is what follows --, and one is required', () => {
  assert.deepEqual(commandOf(['node', 'x', '--', 'pnpm', 'test']), [
    'pnpm',
    'test',
  ]);
  assert.throws(() => commandOf(['node', 'x']), /usage/);
  assert.throws(() => commandOf(['node', 'x', '--']), /usage/);
});
