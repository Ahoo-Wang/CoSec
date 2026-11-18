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

// Runs a command against a real CoSec host: the application of `test/client-host` (the starter over WebFlux, CoCache in
// memory), which Gradle builds and this script starts as a process of its own, so that stopping the run, however it
// ends, stops the host.
//
//   node scripts/with-host.mjs -- <command> [args...]
//
// The command runs with COSEC_HOST_URL set (`http://localhost:<port>`), in the directory this script was started from, and
// its exit code is the script's. The host's output is shown when it fails to start and when the command fails. GRADLE_ARGS (split on spaces)
// goes to Gradle: on a shared machine, `GRADLE_ARGS="--no-daemon --max-workers=2"`.
import { spawn, spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('../..', import.meta.url)));
const HOST_MAIN = 'me.ahoo.cosec.clienthost.ClientHostKt';
const START_TIMEOUT_MILLIS = 180_000;
const STOP_TIMEOUT_MILLIS = 10_000;

function fail(message) {
  console.error(`::error::${message}`);
  process.exit(1);
}

/** The command after `--`. */
export function commandOf(argv) {
  const separator = argv.indexOf('--');
  if (separator < 0 || separator === argv.length - 1) {
    fail('usage: node scripts/with-host.mjs -- <command> [args...]');
  }
  return argv.slice(separator + 1);
}

/** The URL of the host that announced its port on its standard output (`READY <port>`), or null: only a whole line counts. */
export function readyUrl(output) {
  const port = /^READY (\d+)\r?\n/m.exec(output)?.[1];
  return port ? `http://127.0.0.1:${port}` : null;
}

function hostClasspath() {
  const extra = (process.env.GRADLE_ARGS ?? '').split(' ').filter(Boolean);
  const gradle = spawnSync(
    join(root, 'gradlew'),
    [':client-host:hostClasspath', '--console=plain', '-q', ...extra],
    { cwd: root, stdio: 'inherit' },
  );
  if (gradle.status !== 0)
    fail('Gradle could not build the host (:client-host:hostClasspath)');
  return readFileSync(
    join(root, 'test/client-host/build/client-host.classpath'),
    'utf8',
  );
}

function startHost(classpath) {
  const java = process.env.JAVA_HOME
    ? join(process.env.JAVA_HOME, 'bin/java')
    : 'java';
  const host = spawn(
    java,
    ['-cp', classpath, '-Dlogging.level.root=WARN', HOST_MAIN],
    {
      cwd: root,
      stdio: ['pipe', 'pipe', 'pipe'],
    },
  );
  let output = '';
  const ready = new Promise((resolveReady, rejectReady) => {
    const timer = setTimeout(
      () =>
        rejectReady(
          new Error(`the host was not ready in ${START_TIMEOUT_MILLIS} ms`),
        ),
      START_TIMEOUT_MILLIS,
    );
    const onData = chunk => {
      output += chunk;
      const url = readyUrl(output);
      if (url) {
        clearTimeout(timer);
        resolveReady(url);
      }
    };
    host.stdout.setEncoding('utf8').on('data', onData);
    host.stderr.setEncoding('utf8').on('data', chunk => (output += chunk));
    host.on('error', rejectReady);
    host.on('exit', code => {
      clearTimeout(timer);
      rejectReady(
        new Error(`the host exited with ${code} before it was ready`),
      );
    });
  });
  return { host, ready, output: () => output };
}

async function stopHost(host) {
  if (host.exitCode !== null) return;
  const exited = new Promise(done => host.once('exit', done));
  host.stdin.end('stop\n');
  const timer = setTimeout(() => host.kill('SIGKILL'), STOP_TIMEOUT_MILLIS);
  await exited;
  clearTimeout(timer);
}

async function main() {
  const [command, ...args] = commandOf(process.argv.slice(2));
  const { host, ready, output } = startHost(hostClasspath());
  for (const signal of ['SIGINT', 'SIGTERM']) {
    process.once(signal, () => {
      host.kill('SIGKILL');
      process.exit(1);
    });
  }
  let url;
  try {
    url = await ready;
  } catch (error) {
    host.kill('SIGKILL');
    console.error(output());
    fail(error.message);
  }
  console.log(`CoSec host: ${url}`);
  try {
    const child = spawn(command, args, {
      stdio: 'inherit',
      env: { ...process.env, COSEC_HOST_URL: url },
    });
    const code = await new Promise(done => {
      child.on('error', error => {
        console.error(error.message);
        done(1);
      });
      child.on('exit', (status, signal) => done(status ?? (signal ? 1 : 0)));
    });
    // A failed run is often the host's doing: its output is what explains it.
    if (code !== 0) console.error(`--- output of the host ---\n${output()}`);
    process.exitCode = code;
  } finally {
    await stopHost(host);
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) await main();
