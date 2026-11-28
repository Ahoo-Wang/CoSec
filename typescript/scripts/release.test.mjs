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

// The pieces of release.mjs that decide whether a version is published, and the rehearsal of a publish, with `npm`
// replaced by a stub on the PATH.
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import {
  chmodSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
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

describe('publish --dry-run, the rehearsal of every pull request', () => {
  const script = fileURLToPath(new URL('./release.mjs', import.meta.url));

  /** A tarball as `pnpm pack` makes one: package/package.json, here with only what publish reads. */
  function tarball(directory, name, version, overrides = {}) {
    const short = name.replace('@ahoo-wang/', '');
    const unpacked = mkdtempSync(join(tmpdir(), 'package-'));
    mkdirSync(join(unpacked, 'package'));
    const manifest = {
      name,
      version,
      repository: {
        type: 'git',
        url: 'git+https://github.com/Ahoo-Wang/CoSec.git',
        directory: `typescript/${short}`,
      },
      ...overrides,
    };
    writeFileSync(
      join(unpacked, 'package', 'package.json'),
      JSON.stringify(manifest),
    );
    execFileSync('tar', [
      '-czf',
      join(directory, `ahoo-wang-${short}-${version}.tgz`),
      '-C',
      unpacked,
      'package',
    ]);
  }

  /**
   * Runs the rehearsal over tarballs of [version], with an `npm` that answers `view` as published or not and records
   * every call; the calls and the exit status.
   */
  function rehearse(version, published, overrides = {}) {
    const directory = mkdtempSync(join(tmpdir(), 'tarballs-'));
    tarball(directory, '@ahoo-wang/cosec-fetcher', version, overrides);
    tarball(directory, '@ahoo-wang/cosec-react', version);
    const bin = mkdtempSync(join(tmpdir(), 'npm-stub-'));
    const calls = join(bin, 'calls');
    const view = published
      ? `echo ${version}`
      : 'echo "npm error code E404" >&2; exit 1';
    writeFileSync(
      join(bin, 'npm'),
      `#!/bin/sh\necho "$*" >> "${calls}"\ncase "$1" in view) ${view} ;; esac\nexit 0\n`,
    );
    chmodSync(join(bin, 'npm'), 0o755);
    const env = { ...process.env, PATH: `${bin}:${originalPath}` };
    delete env.GITHUB_REPOSITORY;
    const status = spawnSync(
      process.execPath,
      [script, 'publish', '--tarballs', directory, '--dry-run'],
      { env, encoding: 'utf8' },
    );
    const recorded = existsSync(calls)
      ? readFileSync(calls, 'utf8').trim().split('\n')
      : [];
    return {
      code: status.status,
      output: status.stdout + status.stderr,
      publishes: recorded.filter(call => call.startsWith('publish')),
    };
  }

  it('passes for a release already on npm, and asks npm to publish nothing', () => {
    const { code, output, publishes } = rehearse('8.0.0', true);
    assert.equal(code, 0, output);
    assert.deepEqual(publishes, []);
    assert.match(
      output,
      /@ahoo-wang\/cosec-fetcher@8\.0\.0 is already on npm: the rehearsal has nothing to publish/,
    );
  });

  it('still rehearses a version not on npm with a dry-run publish of each package, in order', () => {
    const { code, output, publishes } = rehearse('8.0.1', false);
    assert.equal(code, 0, output);
    assert.equal(publishes.length, 2);
    assert.match(
      publishes[0],
      /ahoo-wang-cosec-fetcher-8\.0\.1\.tgz --tag latest .*--dry-run/,
    );
    assert.match(
      publishes[1],
      /ahoo-wang-cosec-react-8\.0\.1\.tgz --tag latest .*--dry-run/,
    );
  });

  it('still checks the tarballs of a version already on npm', () => {
    const { code, output, publishes } = rehearse('8.0.0', true, {
      repository: {
        url: 'git+https://github.com/Ahoo-Wang/CoSec.git',
        directory: 'typescript/elsewhere',
      },
    });
    assert.equal(code, 1);
    assert.match(output, /repository\.directory is typescript\/elsewhere/);
    assert.deepEqual(publishes, []);
    const leftover = rehearse('8.0.0', true, {
      peerDependencies: { '@ahoo-wang/fetcher': 'catalog:peers' },
    });
    assert.equal(leftover.code, 1);
    assert.match(leftover.output, /workspace: or catalog: specifier/);
  });
});
