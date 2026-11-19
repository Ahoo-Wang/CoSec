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

import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

// Runs a command against a deployed CoSec's gateway: the stack of the repository's system test (test/system-test), which
// the `system-test` workflow has started. Unlike with-host.mjs it starts nothing; it names the gateway for the tests
// (COSEC_HOST_URL, which the packages' host support reads) and fails at once, with a message that says what is missing.

/** The gateway's address from COSEC_SYSTEM_GATEWAY_URL, without a trailing slash; throws when it is not an http(s) URL. */
export function gatewayUrl(env) {
  const value = env.COSEC_SYSTEM_GATEWAY_URL;
  if (!value) {
    throw new Error(
      'COSEC_SYSTEM_GATEWAY_URL is not set: `pnpm test:system` needs the running stack of test/system-test (see test/system-test/README.md).',
    );
  }
  const url = new URL(value);
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new Error(`COSEC_SYSTEM_GATEWAY_URL is not an http(s) URL: ${value}`);
  }
  return value.replace(/\/+$/, '');
}

/** The command after `--`. */
export function commandOf(argv) {
  const separator = argv.indexOf('--');
  if (separator < 0 || separator === argv.length - 1) {
    throw new Error(
      'usage: node scripts/with-system.mjs -- <command> [args...]',
    );
  }
  return argv.slice(separator + 1);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  try {
    const [command, ...args] = commandOf(process.argv);
    const result = spawnSync(command, args, {
      stdio: 'inherit',
      env: { ...process.env, COSEC_HOST_URL: gatewayUrl(process.env) },
    });
    process.exit(result.status ?? 1);
  } catch (error) {
    console.error(`::error::${error.message}`);
    process.exit(1);
  }
}
