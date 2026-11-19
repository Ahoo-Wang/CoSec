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

import { defineConfig } from 'vitest/config';

// The client against a deployed CoSec (`pnpm test:system` of the repository's system test, test/system-test): through the
// gateway, to a service behind it. The stack is already running (the `system-test` workflow starts it); the gateway's
// address is COSEC_HOST_URL, which `pnpm test:system` takes from COSEC_SYSTEM_GATEWAY_URL.
export default defineConfig({
  test: {
    globals: true,
    include: ['test-system/**/*.system.ts'],
    testTimeout: 30_000,
    hookTimeout: 30_000,
    fileParallelism: false,
    // The workflow's summary reads JUnit, from where COSEC_SYSTEM_JUNIT says.
    ...(process.env.COSEC_SYSTEM_JUNIT
      ? {
          reporters: ['default', 'junit'],
          outputFile: { junit: process.env.COSEC_SYSTEM_JUNIT },
        }
      : {}),
    clearMocks: true,
    restoreMocks: true,
    unstubGlobals: true,
  },
});
