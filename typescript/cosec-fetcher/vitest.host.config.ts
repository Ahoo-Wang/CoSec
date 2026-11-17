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

// The client against a real CoSec host (`pnpm test:host` starts it: test/client-host). Apart from `pnpm test`: it needs
// the JVM and Gradle.
export default defineConfig({
  test: {
    globals: true,
    include: ['test-host/**/*.host.ts'],
    testTimeout: 30_000,
    hookTimeout: 30_000,
    // One host, one set of counters: files run one after the other.
    fileParallelism: false,
    clearMocks: true,
    restoreMocks: true,
    unstubGlobals: true,
  },
});
