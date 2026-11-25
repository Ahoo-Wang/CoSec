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

// What fetcher-line.mjs decides for the CI leg of another fetcher major: which range, which version, and the one line
// of pnpm-workspace.yaml it changes.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';
import { catalog, newestOldEnough, pin, rangeOf } from './fetcher-line.mjs';

const yaml = readFileSync(
  new URL('../pnpm-workspace.yaml', import.meta.url),
  'utf8',
);

describe('fetcher-line', () => {
  it('reads both catalogs of the workspace, and the peer range accepts fetcher 6 and 8', () => {
    const peers = catalog(yaml, 'peers');
    const development = catalog(yaml, 'catalog');
    for (const name of [
      '@ahoo-wang/fetcher',
      '@ahoo-wang/fetcher-eventbus',
      '@ahoo-wang/fetcher-storage',
    ]) {
      assert.equal(rangeOf(peers[name], 6), '^6.1.1');
      assert.equal(rangeOf(peers[name], 8), '^8.0.0-rc.1');
      assert.match(development[name], /^\^6\./);
    }
    assert.equal(peers.react, '^19.3.1');
    assert.throws(
      () => rangeOf(peers['@ahoo-wang/fetcher'], 7),
      /accepts no version of major 7/,
    );
  });

  it('picks the newest version older than the minimum release age, never a younger one', () => {
    const time = {
      '8.0.0-rc.1': '2026-11-25T23:40:05.739Z',
      '8.0.0-rc.2': '2026-11-27T10:00:00.000Z',
    };
    const versions = ['8.0.0-rc.1', '8.0.0-rc.2'];
    const day = 1440;
    assert.equal(
      newestOldEnough(versions, time, day, Date.parse('2026-11-26T12:00:00Z')),
      null,
    );
    assert.equal(
      newestOldEnough(versions, time, day, Date.parse('2026-11-27T00:00:00Z')),
      '8.0.0-rc.1',
    );
    assert.equal(
      newestOldEnough(versions, time, day, Date.parse('2026-11-28T12:00:00Z')),
      '8.0.0-rc.2',
    );
    assert.equal(newestOldEnough([], time, day, Date.now()), null);
  });

  it('changes the development catalog line of one package, and nothing else', () => {
    const pinned = pin(yaml, '@ahoo-wang/fetcher-eventbus', '8.0.0-rc.1');
    const before = yaml.split('\n');
    const after = pinned.split('\n');
    const changed = after.flatMap((line, index) =>
      line === before[index] ? [] : [line],
    );
    assert.deepEqual(changed, ["  '@ahoo-wang/fetcher-eventbus': 8.0.0-rc.1"]);
    assert.equal(
      catalog(pinned, 'peers')['@ahoo-wang/fetcher-eventbus'],
      '^6.1.1 || ^8.0.0-rc.1',
    );
  });
});
