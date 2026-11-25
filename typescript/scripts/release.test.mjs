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

// The pieces of release.mjs that decide whether a version is published, with `npm` replaced by a stub on the PATH.
import assert from 'node:assert/strict';
import { chmodSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, it } from 'node:test';
import { distTag, isOnNpm, printDistTags } from './release.mjs';

const originalPath = process.env.PATH;

/** Puts an `npm` on the PATH that runs `script` (a POSIX shell snippet) whatever it is asked. */
function stubNpm(script) {
  const directory = mkdtempSync(join(tmpdir(), 'npm-stub-'));
  const npm = join(directory, 'npm');
  writeFileSync(npm, `#!/bin/sh\n${script}\n`);
  chmodSync(npm, 0o755);
  process.env.PATH = `${directory}:${originalPath}`;
}

describe('isOnNpm', () => {
  beforeEach(() => {
    process.env.PATH = originalPath;
  });
  afterEach(() => {
    process.env.PATH = originalPath;
  });

  it('is true only when npm view prints exactly the version', () => {
    stubNpm('echo 8.0.0-beta.1');
    assert.equal(isOnNpm('@ahoo-wang/cosec-fetcher', '8.0.0-beta.1'), true);
  });

  it('is false on E404, the answer for a version that does not exist', () => {
    stubNpm('echo "npm error code E404" >&2; exit 1');
    assert.equal(isOnNpm('@ahoo-wang/cosec-fetcher', '8.0.0-beta.1'), false);
  });

  it('fails, with what npm printed, when npm exits 0 and prints nothing', () => {
    stubNpm('exit 0');
    assert.throws(
      () => isOnNpm('@ahoo-wang/cosec-fetcher', '8.0.0-beta.1'),
      /printed ""/,
    );
  });

  it('fails when npm exits 0 and prints another version', () => {
    stubNpm('echo 8.0.0-alpha.4');
    assert.throws(
      () => isOnNpm('@ahoo-wang/cosec-fetcher', '8.0.0-beta.1'),
      /printed "8.0.0-alpha.4"/,
    );
  });

  it('fails when the registry cannot be reached: that is not "not published"', () => {
    stubNpm('echo "npm error code ENOTFOUND" >&2; exit 1');
    assert.throws(() => isOnNpm('@ahoo-wang/cosec-fetcher', '8.0.0-beta.1'));
  });
});

describe('printDistTags', () => {
  afterEach(() => {
    process.env.PATH = originalPath;
  });

  it('logs what npm answers and never throws', () => {
    stubNpm('echo \'{"beta":"8.0.0-beta.1"}\'');
    assert.doesNotThrow(() => printDistTags('@ahoo-wang/cosec-fetcher'));
    stubNpm('exit 1');
    assert.doesNotThrow(() => printDistTags('@ahoo-wang/cosec-fetcher'));
  });
});

describe('distTag', () => {
  it('never gives a pre-release latest', () => {
    assert.equal(distTag('8.0.0'), 'latest');
    assert.equal(distTag('8.0.0-alpha.4'), 'alpha');
    assert.equal(distTag('8.0.0-beta.1'), 'beta');
    assert.equal(distTag('8.0.0-rc.1'), 'rc');
    // An uppercase or unnumbered pre-release sorts wrongly on npm: refused, never given a tag.
    assert.throws(() => distTag('8.0.0-RC1'));
    assert.throws(() => distTag('8.0.0-Beta.1'));
    assert.throws(() => distTag('8.0.0-rc'));
    assert.throws(() => distTag('8.0.0-snapshot.1'));
    assert.throws(() => distTag('8.0'));
  });
});
