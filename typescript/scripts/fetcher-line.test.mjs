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

// What fetcher-line.mjs decides for the fetcher 8 legs of CI: which range, which version, the one line of the
// development catalog it changes, and the exemption from minimumReleaseAge, the three fetcher packages only.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  FETCHER,
  catalog,
  compareVersions,
  exempt,
  pin,
  rangeOf,
} from './fetcher-line.mjs';

// The committed workspace, not the file on disk: on a fetcher 8 leg the script has already rewritten that one.
const yaml = execFileSync('git', ['show', 'HEAD:./pnpm-workspace.yaml'], {
  cwd: fileURLToPath(new URL('..', import.meta.url)),
  encoding: 'utf8',
});

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

  it('picks the newest version in the range, in semantic versioning order', () => {
    const listed = [
      '8.0.0-rc.1',
      '8.0.0',
      '8.0.0-rc.10',
      '8.0.0-rc.2',
      '8.1.0',
    ];
    assert.deepEqual([...listed].sort(compareVersions), [
      '8.0.0-rc.1',
      '8.0.0-rc.2',
      '8.0.0-rc.10',
      '8.0.0',
      '8.1.0',
    ]);
    assert.equal(compareVersions('8.0.0-rc.1', '8.0.0-rc.1'), 0);
  });

  it('exempts only the three fetcher packages, at their exact versions, and keeps the age strict for the rest', () => {
    assert.deepEqual(FETCHER, [
      '@ahoo-wang/fetcher',
      '@ahoo-wang/fetcher-eventbus',
      '@ahoo-wang/fetcher-storage',
    ]);
    assert.doesNotMatch(yaml, /minimumReleaseAge(Exclude|Strict)/);
    const pins = Object.fromEntries(FETCHER.map(name => [name, '8.0.0-rc.1']));
    const exempted = exempt(yaml, pins);
    const before = yaml.split('\n');
    const added = exempted.split('\n').filter(line => !before.includes(line));
    assert.deepEqual(added, [
      'minimumReleaseAgeStrict: true',
      'minimumReleaseAgeExclude:',
      "  - '@ahoo-wang/fetcher@8.0.0-rc.1'",
      "  - '@ahoo-wang/fetcher-eventbus@8.0.0-rc.1'",
      "  - '@ahoo-wang/fetcher-storage@8.0.0-rc.1'",
    ]);
    assert.match(exempted, /^minimumReleaseAge: 1440$/m);
    assert.throws(() => exempt(exempted, pins), /already sets the age policy/);
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
