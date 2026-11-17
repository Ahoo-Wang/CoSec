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

// Checks the npm packages as a consumer gets them: the tarballs, not the workspace. Run after `pnpm build`:
//
//   node scripts/package-check.mjs [--tarballs <dir>]     (from typescript/)
//
// Without --tarballs it packs every package of the workspace itself (`pnpm pack`). With it, it needs only node and
// npm, so a job can pack under the Node that runs pnpm and check under another: the Node 22.12.0 floor that
// `engines.node` promises.
//
// It checks, for every tarball:
//   1. publint (strict) on the unpacked package;
//   2. a fresh npm project installs the tarballs (npm adds the peers, the newest in range), then an ES module import
//      and a CommonJS require of the entry point give a non-empty module;
//   3. a consumer file imports the package and type-checks under node16, nodenext and bundler resolution.
import { execFileSync } from 'node:child_process';
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const workspace = resolve(fileURLToPath(new URL('..', import.meta.url)));
const tarballsOption = process.argv.indexOf('--tarballs');
const work = mkdtempSync(join(tmpdir(), 'package-check-'));
let failed = false;

const run = (command, args, options = {}) =>
  execFileSync(command, args, {
    stdio: ['ignore', 'pipe', 'inherit'],
    encoding: 'utf8',
    ...options,
  });
const fail = message => {
  failed = true;
  console.error(`::error::${message}`);
};

try {
  let tarballs;
  if (tarballsOption >= 0) {
    const dir = resolve(process.argv[tarballsOption + 1]);
    tarballs = readdirSync(dir)
      .filter(file => file.endsWith('.tgz'))
      .map(file => join(dir, file));
  } else {
    const dir = join(work, 'tarballs');
    mkdirSync(dir);
    tarballs = [];
    for (const name of readdirSync(workspace).filter(entry =>
      entry.startsWith('cosec-'),
    )) {
      const packageDirectory = join(workspace, name);
      if (!existsSync(join(packageDirectory, 'package.json'))) continue;
      run('pnpm', ['pack', '--pack-destination', dir], {
        cwd: packageDirectory,
      });
    }
    tarballs = readdirSync(dir).map(file => join(dir, file));
  }
  if (tarballs.length === 0) {
    console.log('No package to check.');
    process.exit(0);
  }

  // 1. publint, on each package as the tarball unpacks it.
  const publint = join(workspace, 'node_modules', '.bin', 'publint');
  const names = [];
  for (const tarball of tarballs) {
    const unpacked = mkdtempSync(join(work, 'unpacked-'));
    run('tar', ['-xzf', tarball, '-C', unpacked]);
    names.push(
      JSON.parse(
        readFileSync(join(unpacked, 'package', 'package.json'), 'utf8'),
      ).name,
    );
    try {
      run(publint, ['run', '--strict', join(unpacked, 'package')]);
    } catch {
      fail(`publint found problems in ${tarball}`);
    }
  }

  // 2. a fresh project that installs them, imports and requires every entry point.
  const project = join(work, 'consumer');
  mkdirSync(project);
  writeFileSync(
    join(project, 'package.json'),
    JSON.stringify({ name: 'consumer', private: true, type: 'module' }),
  );
  run(
    'npm',
    ['install', '--no-audit', '--no-fund', '--loglevel=error', ...tarballs],
    { cwd: project },
  );
  // A package whose types name React's has no @types/react of its own: the consumer that uses it has them.
  run(
    'npm',
    [
      'install',
      '--no-audit',
      '--no-fund',
      '--loglevel=error',
      '--save-dev',
      '@types/react@^19',
    ],
    { cwd: project },
  );
  for (const name of names) {
    const esm = run(
      'node',
      [
        '--input-type=module',
        '-e',
        `import * as m from '${name}'; console.log(Object.keys(m).length)`,
      ],
      { cwd: project },
    );
    if (Number(esm.trim()) === 0) fail(`${name} imports as an empty module`);
    const cjs = run(
      'node',
      ['-e', `console.log(Object.keys(require('${name}')).length)`],
      { cwd: project },
    );
    if (Number(cjs.trim()) === 0) fail(`${name} requires as an empty module`);
  }

  // 3. the types, as a consumer's compiler reads them.
  const tsc = join(workspace, 'node_modules', '.bin', 'tsc');
  writeFileSync(
    join(project, 'consumer.ts'),
    names
      .map(
        (name, index) =>
          `import * as m${index} from '${name}';\nvoid m${index};`,
      )
      .join('\n'),
  );
  for (const [module, moduleResolution] of [
    ['node16', 'node16'],
    ['nodenext', 'nodenext'],
    ['esnext', 'bundler'],
  ]) {
    writeFileSync(
      join(project, 'tsconfig.json'),
      JSON.stringify({
        compilerOptions: {
          module,
          moduleResolution,
          target: 'es2022',
          strict: true,
          noEmit: true,
          skipLibCheck: false,
          types: [],
        },
        files: ['consumer.ts'],
      }),
    );
    try {
      run(tsc, ['-p', project]);
    } catch {
      fail(
        `the types of the packages do not compile under ${moduleResolution} resolution`,
      );
    }
  }
  console.log(
    `Checked ${names.join(', ')}: publint, import, require and types.`,
  );
} finally {
  rmSync(work, { recursive: true, force: true });
}
process.exit(failed ? 1 : 0);
