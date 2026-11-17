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

// The API report of a package: every name its entry exports, with its full signature, held in
// `test/api/<package>.api.md`. It is to a package what `api/<project>.api` is to a Kotlin module: a changed line is
// a public change, and a removed or changed one is a breaking change (`!` in the PR title).
//
// Run from a package, after `pnpm build`:
//
//   node ../scripts/api-report.mjs        fails when the report differs from the build
//   node ../scripts/api-report.mjs -u     rewrites the report from the build; commit the change on purpose
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { Extractor, ExtractorConfig } from '@microsoft/api-extractor';

const packageRoot = resolve(process.cwd());
const manifest = JSON.parse(
  readFileSync(join(packageRoot, 'package.json'), 'utf8'),
);
const update = process.argv.includes('-u');
const entry = join(packageRoot, 'dist', 'index.d.ts');
if (!existsSync(entry)) {
  console.error(
    `${entry} does not exist: build the package first (pnpm build).`,
  );
  process.exit(1);
}

mkdirSync(join(packageRoot, 'test', 'api'), { recursive: true });
const temp = mkdtempSync(join(tmpdir(), 'api-report-'));
try {
  const config = ExtractorConfig.prepare({
    configObject: {
      projectFolder: packageRoot,
      mainEntryPointFilePath: entry,
      compiler: { tsconfigFilePath: join(packageRoot, 'tsconfig.json') },
      apiReport: {
        enabled: true,
        reportFileName: `${manifest.name.replace(/^@[^/]+\//, '')}.api.md`,
        reportFolder: join(packageRoot, 'test', 'api'),
        reportTempFolder: temp,
      },
      docModel: { enabled: false },
      dtsRollup: { enabled: false },
      tsdocMetadata: { enabled: false },
      messages: {
        extractorMessageReporting: {
          'ae-missing-release-tag': { logLevel: 'none' },
          'ae-forgotten-export': { logLevel: 'none' },
        },
      },
    },
    configObjectFullPath: join(packageRoot, 'api-extractor.json'),
    packageJsonFullPath: join(packageRoot, 'package.json'),
  });
  const result = Extractor.invoke(config, {
    localBuild: update,
    showVerboseMessages: false,
  });
  if (!result.succeeded) {
    console.error(
      `The API report of ${manifest.name} differs from the build. Review the change; if it is intended, run ` +
        '`pnpm test:api -u` and commit the report. The new report is in ' +
        temp,
    );
    process.exit(1);
  }
  console.log(
    `API report of ${manifest.name}: ${update ? 'written' : 'unchanged'}.`,
  );
} finally {
  if (process.exitCode !== 1) rmSync(temp, { recursive: true, force: true });
}
