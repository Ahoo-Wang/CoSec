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

// The packages accept two majors of fetcher (`catalogs.peers` in pnpm-workspace.yaml), and CI runs the same tests on
// each. The workspace develops against the lockfile's fetcher; this points the development catalog at another major
// for one CI leg, the fetcher 8 legs of the `typescript` and `typescript-host` jobs:
//
//   node scripts/fetcher-line.mjs <major>
//
// For each of the three fetcher packages it takes the part of the published peer range for <major>, asks npm for the
// versions in it, and pins the catalog entry to the newest one. Then `pnpm install` installs it.
//
// Those three versions, and nothing else, are exempt from `minimumReleaseAge` on this leg: the script lists each by its
// exact version in `minimumReleaseAgeExclude`, on the leg's own checkout, and states `minimumReleaseAgeStrict`, so a
// dependency they bring that is younger than a day fails the install, naming it. Why these three may skip the day:
// they are the maintainer's own packages, published by fetcher's release workflow from reviewed commits with provenance;
// the two legs hold no secret and no write permission; so the attack the guard is for, a third-party package poisoned
// and installed at once, hardly applies here. Holding them to the day would make CoSec wait a day after each fetcher
// release before it could test on it. The committed workspace keeps the guard for every install and every package, and
// the fetcher 6 legs install the lockfile as it is. Nothing is committed: the leg runs on its own checkout.
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const workspace = resolve(fileURLToPath(new URL('..', import.meta.url)));
export const FETCHER = [
  '@ahoo-wang/fetcher',
  '@ahoo-wang/fetcher-eventbus',
  '@ahoo-wang/fetcher-storage',
];

/** The entries of one catalog of pnpm-workspace.yaml (`catalog` or `catalogs.<name>`), as written: name to range. */
export function catalog(yaml, name) {
  const lines = yaml.split('\n');
  const start =
    name === 'catalog'
      ? lines.findIndex(line => line === 'catalog:')
      : lines.findIndex(
          (line, index) =>
            line === `  ${name}:` &&
            lines.slice(0, index).includes('catalogs:'),
        );
  if (start < 0) throw new Error(`pnpm-workspace.yaml has no catalog ${name}`);
  const indent = name === 'catalog' ? '  ' : '    ';
  const entries = {};
  for (const line of lines.slice(start + 1)) {
    if (line.trim() === '' || line.trim().startsWith('#')) continue;
    if (!line.startsWith(indent) || line.startsWith(`${indent} `)) break;
    const match = /^\s*'?([^':]+)'?:\s*(.+)$/.exec(line);
    if (match) entries[match[1]] = match[2].trim();
  }
  return entries;
}

/** The part of a peer range (`a || b`) for one major, or an error when it accepts none of it. */
export function rangeOf(peer, major) {
  const part = peer
    .split('||')
    .map(it => it.trim())
    .find(it => it.replace(/^[\^~>=]*/, '').startsWith(`${major}.`));
  if (!part)
    throw new Error(
      `the peer range ${peer} accepts no version of major ${major}`,
    );
  return part;
}

/** Semantic versioning's order of two versions (`1.2.3` or `1.2.3-pre.n`), negative when [a] comes first. */
export function compareVersions(a, b) {
  const [mainA, preA] = a.split(/-(.*)/s);
  const [mainB, preB] = b.split(/-(.*)/s);
  const numbers = main => main.split('.').map(Number);
  const [x, y] = [numbers(mainA), numbers(mainB)];
  for (let i = 0; i < 3; i++) if (x[i] !== y[i]) return x[i] - y[i];
  if (preA === undefined || preB === undefined)
    return (preA === undefined) - (preB === undefined);
  const [p, q] = [preA.split('.'), preB.split('.')];
  for (let i = 0; i < Math.max(p.length, q.length); i++) {
    if (p[i] === undefined || q[i] === undefined)
      return (p[i] !== undefined) - (q[i] !== undefined);
    const [m, n] = [/^\d+$/.test(p[i]), /^\d+$/.test(q[i])];
    if (m && n && Number(p[i]) !== Number(q[i]))
      return Number(p[i]) - Number(q[i]);
    if (m !== n) return m ? -1 : 1;
    if (!m && p[i] !== q[i]) return p[i] < q[i] ? -1 : 1;
  }
  return 0;
}

/**
 * pnpm-workspace.yaml with [pins] (name to exact version) exempt from `minimumReleaseAge`, and nothing else: the age stays,
 * strict, so any other package younger than it fails the install.
 */
export function exempt(yaml, pins) {
  const lines = yaml.split('\n');
  const at = lines.findIndex(line => /^minimumReleaseAge:\s*\d+/.test(line));
  if (at < 0) throw new Error('pnpm-workspace.yaml sets no minimumReleaseAge');
  if (lines.some(line => /^minimumReleaseAge(Strict|Exclude):/.test(line)))
    throw new Error('pnpm-workspace.yaml already sets the age policy');
  lines.splice(
    at + 1,
    0,
    'minimumReleaseAgeStrict: true',
    'minimumReleaseAgeExclude:',
    ...Object.entries(pins).map(
      ([name, version]) => `  - '${name}@${version}'`,
    ),
  );
  return lines.join('\n');
}

/** pnpm-workspace.yaml with the development catalog's entry for [name] set to [version], nothing else changed. */
export function pin(yaml, name, version) {
  const lines = yaml.split('\n');
  const start = lines.findIndex(line => line === 'catalog:');
  const at = lines.findIndex(
    (line, index) =>
      index > start && /^ {2}'?([^':]+)'?:/.exec(line)?.[1] === name,
  );
  if (start < 0 || at < 0)
    throw new Error(`the catalog has no entry for ${name}`);
  lines[at] = lines[at].replace(/:\s*.+$/, `: ${version}`);
  return lines.join('\n');
}

function npmJson(args) {
  const out = execFileSync('npm', ['view', ...args, '--json'], {
    encoding: 'utf8',
  }).trim();
  return out ? JSON.parse(out) : null;
}

function main() {
  const major = process.argv[2];
  if (!/^\d+$/.test(major ?? '')) {
    console.error('usage: node scripts/fetcher-line.mjs <major>');
    process.exit(2);
  }
  const file = join(workspace, 'pnpm-workspace.yaml');
  let yaml = readFileSync(file, 'utf8');
  const peers = catalog(yaml, 'peers');
  const pins = {};
  for (const name of FETCHER) {
    const range = rangeOf(peers[name], major);
    const found = npmJson([`${name}@${range}`, 'version']);
    const versions =
      found === null ? [] : Array.isArray(found) ? found : [found];
    const version = versions.sort(compareVersions).at(-1);
    if (!version) {
      console.error(`::error::npm lists no version of ${name} in ${range}`);
      process.exit(1);
    }
    console.log(
      `${name}: ${version} (the newest of ${range}; exempt from minimumReleaseAge on this leg)`,
    );
    yaml = pin(yaml, name, version);
    pins[name] = version;
  }
  writeFileSync(file, exempt(yaml, pins));
}

if (process.argv[1] === fileURLToPath(import.meta.url)) main();
