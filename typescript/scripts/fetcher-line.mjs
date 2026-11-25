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
// for one CI leg:
//
//   node scripts/fetcher-line.mjs <major>
//
// For each fetcher package of the peer catalog it takes the part of the published peer range for <major>, asks npm for
// the versions in it, and pins the catalog entry to the newest one that is older than the workspace's
// `minimumReleaseAge`: the guard holds here as for every install, so a version published less than a day ago is
// never chosen, and a major with no version old enough yet fails the leg, saying so. Then `pnpm install` installs it.
// Nothing is committed: the leg runs on its own checkout.
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const workspace = resolve(fileURLToPath(new URL('..', import.meta.url)));
const FETCHER = [
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
    .find(it => new RegExp(`^[\\^~>=]*${major}\\.`).test(it));
  if (!part)
    throw new Error(
      `the peer range ${peer} accepts no version of major ${major}`,
    );
  return part;
}

/**
 * The newest of [versions] (oldest first, as npm lists them) that was published at least [minutes] before [now],
 * from [time] (npm's `time` of the package: version to its publish date); null when there is none.
 */
export function newestOldEnough(versions, time, minutes, now) {
  const limit = now - minutes * 60_000;
  return (
    [...versions]
      .reverse()
      .find(version => time[version] && Date.parse(time[version]) <= limit) ??
    null
  );
}

/** pnpm-workspace.yaml with the development catalog's entry for [name] set to [version], nothing else changed. */
export function pin(yaml, name, version) {
  const lines = yaml.split('\n');
  const start = lines.findIndex(line => line === 'catalog:');
  const at = lines.findIndex(
    (line, index) =>
      index > start &&
      new RegExp(`^  '?${name.replace(/[/@-]/g, '\\$&')}'?:`).test(line),
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
  const minutes = Number(/^minimumReleaseAge:\s*(\d+)/m.exec(yaml)?.[1] ?? 0);
  for (const name of FETCHER) {
    const range = rangeOf(peers[name], major);
    const found = npmJson([`${name}@${range}`, 'version']);
    const versions =
      found === null ? [] : Array.isArray(found) ? found : [found];
    const time = npmJson([name, 'time']) ?? {};
    const version = newestOldEnough(versions, time, minutes, Date.now());
    if (version === null) {
      const newest = versions.at(-1);
      console.error(
        `::error::no version of ${name} in ${range} is older than minimumReleaseAge (${minutes} minutes) yet` +
          (newest
            ? `: the newest, ${newest}, was published at ${time[newest]}`
            : ': npm lists none'),
      );
      process.exit(1);
    }
    console.log(
      `${name}: ${version} (the newest of ${range} older than ${minutes} minutes)`,
    );
    yaml = pin(yaml, name, version);
  }
  writeFileSync(file, yaml);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) main();
