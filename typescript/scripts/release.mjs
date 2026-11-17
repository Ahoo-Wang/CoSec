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

// What a release does with the npm packages of this workspace (`package-deploy.yml`, job `npm-publish`), and what CI
// rehearses on every pull request, so that the first real publish is not the first time any of it runs.
//
//   node scripts/release.mjs env                         the Node and npm that publishing needs, printed and asserted
//   node scripts/release.mjs check <tag>                 the tag, gradle.properties and every package.json agree
//   node scripts/release.mjs publish --tarballs <dir>    publish the tarballs in <dir>, in dependency order
//   node scripts/release.mjs publish --tarballs <dir> --dry-run    the same, without publishing
//
// The tarballs are the ones `pnpm pack` made and `scripts/package-check.mjs --tarballs` checked: what is published is
// what was checked, byte for byte. There is no token: npm exchanges the workflow's OIDC identity (the trusted
// publisher of each package) for a short-lived one, and writes the provenance.
import { execFileSync } from 'node:child_process';
import { readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const workspace = resolve(fileURLToPath(new URL('..', import.meta.url)));
const root = resolve(workspace, '..');

/** Publishing order: a package after the ones it depends on. */
const PUBLISH_ORDER = ['@ahoo-wang/cosec-fetcher', '@ahoo-wang/cosec-react'];

const MIN_NODE = [22, 14, 0];
const MIN_NPM = [11, 5, 1];

function fail(message) {
  console.error(`::error::${message}`);
  process.exit(1);
}

const parse = version => version.split('-')[0].split('.').map(Number);
function atLeast(version, minimum) {
  const actual = parse(version);
  for (let i = 0; i < minimum.length; i++) {
    if (actual[i] !== minimum[i]) return actual[i] > minimum[i];
  }
  return true;
}

/** The dist-tag of a version: a pre-release never gets `latest`. */
export function distTag(version) {
  const prerelease = /^\d+\.\d+\.\d+-(.+)$/.exec(version)?.[1];
  if (prerelease === undefined) {
    if (!/^\d+\.\d+\.\d+$/.test(version)) {
      throw new Error(`${version} is not a version`);
    }
    return 'latest';
  }
  if (/^alpha\./i.test(prerelease)) return 'alpha';
  if (/^beta\./i.test(prerelease)) return 'beta';
  if (/^rc/i.test(prerelease)) return 'rc';
  throw new Error(
    `${version}: no dist-tag is defined for pre-release "${prerelease}"`,
  );
}

function checkEnvironment() {
  const node = process.versions.node;
  const npm = execFileSync('npm', ['--version'], { encoding: 'utf8' }).trim();
  console.log(`node ${node}, npm ${npm}`);
  if (!atLeast(node, MIN_NODE)) {
    fail(`Node ${MIN_NODE.join('.')} or later is needed, this is ${node}`);
  }
  if (!atLeast(npm, MIN_NPM)) {
    fail(
      `npm ${MIN_NPM.join('.')} or later is needed for trusted publishing, this is ${npm}`,
    );
  }
}

function checkVersions(tag) {
  const gradle = readFileSync(join(root, 'gradle.properties'), 'utf8')
    .split('\n')
    .find(line => line.startsWith('version='))
    ?.slice('version='.length)
    .trim();
  const versions = {
    'release tag': tag.replace(/^v/, ''),
    'gradle.properties': gradle,
  };
  for (const directory of readdirSync(workspace).filter(name =>
    name.startsWith('cosec-'),
  )) {
    const manifest = JSON.parse(
      readFileSync(join(workspace, directory, 'package.json'), 'utf8'),
    );
    versions[`${directory}/package.json`] = manifest.version;
  }
  for (const [source, version] of Object.entries(versions)) {
    console.log(`${source.padEnd(32)} ${version}`);
  }
  if (new Set(Object.values(versions)).size !== 1) {
    fail(
      'the release tag, gradle.properties and every package.json must carry one version',
    );
  }
  console.log(`dist-tag ${distTag(gradle)}`);
}

function readManifest(tarball) {
  const text = execFileSync('tar', ['-xzOf', tarball, 'package/package.json'], {
    encoding: 'utf8',
  });
  return { text, manifest: JSON.parse(text) };
}

/**
 * Whether `name@version` is on npm. "Yes" needs positive evidence: `npm view` printing exactly that version. Only an
 * `E404` is "no". Anything else (a registry that cannot be reached, exit 0 with no or other output, as an older npm or a
 * mirror may give) throws: a skipped publish that nobody notices is a release without the package.
 */
export function isOnNpm(name, version) {
  let output;
  try {
    output = execFileSync('npm', ['view', `${name}@${version}`, 'version'], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    }).trim();
  } catch (error) {
    if (/E404/.test(String(error.stderr))) return false;
    throw error;
  }
  if (output === version) return true;
  throw new Error(
    `npm view ${name}@${version} exited 0 and printed ${JSON.stringify(output)}, not the version: it cannot be told whether it is published`,
  );
}

/** What npm says the dist-tags are now. Logged, never asserted: the registry can lag for minutes after a publish. */
export function printDistTags(name) {
  try {
    const tags = execFileSync('npm', ['view', name, 'dist-tags', '--json'], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    }).trim();
    console.log(`${name} dist-tags now: ${tags}`);
  } catch (error) {
    console.log(`${name}: dist-tags could not be read (${error.message})`);
  }
}

function publish(directory, dryRun) {
  const tarballs = readdirSync(directory)
    .filter(file => file.endsWith('.tgz'))
    .map(file => join(directory, file));
  const byName = new Map();
  for (const tarball of tarballs) {
    const { text, manifest } = readManifest(tarball);
    if (!PUBLISH_ORDER.includes(manifest.name)) {
      fail(`${tarball} is ${manifest.name}, which is not a package to publish`);
    }
    // pnpm rewrites `workspace:` and `catalog:` when it packs: one left would be uninstallable from npm.
    if (/"(workspace|catalog)[:"]/.test(text)) {
      fail(
        `${manifest.name}: the packed package.json still has a workspace: or catalog: specifier`,
      );
    }
    // npm's provenance ties the package to the repository of the workflow that publishes it.
    const repository = process.env.GITHUB_REPOSITORY;
    if (repository) {
      const expected = `git+https://github.com/${repository}.git`;
      if (manifest.repository?.url !== expected) {
        fail(
          `${manifest.name}: repository.url is ${manifest.repository?.url}, provenance needs ${expected}`,
        );
      }
    }
    const expectedDirectory = `typescript/${manifest.name.replace('@ahoo-wang/', '')}`;
    if (manifest.repository?.directory !== expectedDirectory) {
      fail(
        `${manifest.name}: repository.directory is ${manifest.repository?.directory}, it must be ${expectedDirectory}`,
      );
    }
    byName.set(manifest.name, { tarball, manifest });
  }
  for (const name of PUBLISH_ORDER) {
    if (!byName.has(name)) fail(`no tarball of ${name} in ${directory}`);
  }
  for (const name of PUBLISH_ORDER) {
    const { tarball, manifest } = byName.get(name);
    const tag = distTag(manifest.version);
    let published;
    try {
      published = isOnNpm(name, manifest.version);
    } catch (error) {
      fail(error.message);
    }
    if (published) {
      console.log(`${name}@${manifest.version} is already on npm`);
      if (!dryRun) {
        printDistTags(name);
        continue;
      }
    }
    const args = ['publish', tarball, '--tag', tag, '--access', 'public'];
    if (dryRun) args.push('--dry-run');
    else args.push('--provenance');
    console.log(`npm ${args.join(' ')}`);
    execFileSync('npm', args, { stdio: 'inherit' });
    console.log(
      `${name}@${manifest.version} ${dryRun ? 'would be published' : 'published'} with dist-tag ${tag}`,
    );
    if (!dryRun) printDistTags(name);
  }
}

function main() {
  const [command, ...rest] = process.argv.slice(2);
  switch (command) {
    case 'env':
      checkEnvironment();
      break;
    case 'check':
      if (!rest[0]) fail('usage: release.mjs check <tag>');
      checkVersions(rest[0]);
      break;
    case 'publish': {
      const at = rest.indexOf('--tarballs');
      if (at < 0 || !rest[at + 1])
        fail('usage: release.mjs publish --tarballs <dir> [--dry-run]');
      publish(resolve(rest[at + 1]), rest.includes('--dry-run'));
      break;
    }
    default:
      fail(
        'usage: release.mjs env | check <tag> | publish --tarballs <dir> [--dry-run]',
      );
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) main();
