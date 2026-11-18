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

// What both packages' host tests ask of the host of test/client-host, over plain HTTP: no CoSec client in it, so a case
// sees the host as an application's login and a test's counters do, and the two packages share one copy (nothing is
// imported from either: a package's tests may not depend on the other's sources or on a published export).

/** A token pair as the host's login answers it. */
export interface HostPair {
  accessToken: string;
  refreshToken: string;
}

// Node's, which vitest runs on: the packages do not depend on @types/node.
declare const process: { env: Record<string, string | undefined> };

/** The host `pnpm test:host` started (test/client-host). */
export function hostUrl(): string {
  const url = process.env.COSEC_HOST_URL;
  if (!url) {
    throw new Error(
      'COSEC_HOST_URL is not set: these tests need the host of test/client-host, which `pnpm test:host` starts.',
    );
  }
  return url;
}

async function post(path: string, body?: unknown): Promise<Response> {
  return fetch(`${hostUrl()}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

async function pairOf(path: string, username: string): Promise<HostPair> {
  const response = await post(path, { username });
  if (!response.ok) throw new Error(`${path} answered ${response.status}`);
  return (await response.json()) as HostPair;
}

/** Signs `username` in at the host, as the application's own login does: the pair CoSec issued. */
export const login = (username: string) => pairOf('/auth/login', username);

/**
 * Signs `username` in with an access token that has already expired and a live session and refresh token: what a client
 * holds when its token ran out, with nothing to wait for. The host refuses the token `401 token-expired`.
 */
export const loginExpired = (username: string) =>
  pairOf('/test/login-expired', username);

export interface HostStats {
  logins: number;
  refreshes: number;
}

/** What reached the host since the last {@link resetStats}. */
export async function stats(): Promise<HostStats> {
  return (await (await fetch(`${hostUrl()}/test/stats`)).json()) as HostStats;
}

export async function resetStats(): Promise<void> {
  await post('/test/reset');
}

/**
 * The host's revocations cannot be read from now on, until {@link revocationsUp}: the served refresh answers `503`
 * (`IDN-14`), nothing issued, nothing spent.
 */
export async function revocationsDown(): Promise<void> {
  await post('/test/revocations/down');
}

export async function revocationsUp(): Promise<void> {
  await post('/test/revocations/up');
}

/** Every refresh fails with a bug of the application's own code while on: the served refresh answers `500`. */
export async function refreshError(on: boolean): Promise<void> {
  await post(`/test/refresh-error/${on}`);
}

/** Ends the session of `pair` at the host, as a revocation or a server-side sign-out would. */
export async function endSession(pair: HostPair): Promise<void> {
  const response = await post('/test/end-session', pair);
  if (!response.ok) throw new Error(`end-session answered ${response.status}`);
}
