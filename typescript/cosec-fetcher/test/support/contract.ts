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

/**
 * The files of the repository's `contract/` (`PIP-25`), which CoSec's server is
 * tested against: what a client of a CoSec application relies on. The unit
 * tests' fake server answers from them, and `clientContract.test.ts` checks the
 * client against them, so a test here and the real host agree by construction.
 * Nothing in these files is written by hand: the server's tests write them.
 */
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../../../../contract/', import.meta.url));

function read<T>(path: string): T {
  return JSON.parse(readFileSync(`${ROOT}${path}`, 'utf8')) as T;
}

/** `contract/problems/<slug>.json`. */
export interface ProblemFile {
  readonly status: number;
  readonly headers: Readonly<Record<string, string>>;
  readonly body: Readonly<Record<string, unknown>>;
  /** Members a body may carry besides, by JSON type. */
  readonly optionalMembers: Readonly<Record<string, string>>;
}

/** `contract/headers.json`. */
export interface HeadersFile {
  readonly request: readonly string[];
  readonly requestId: { readonly pattern: string };
  readonly response: {
    readonly everyAnswer: readonly string[];
    readonly problem: readonly string[];
    readonly tooManyRequests: readonly string[];
    readonly permitCounted: readonly string[];
  };
}

export interface ClaimRule {
  readonly type: string;
  readonly client: 'read' | 'do-not-depend';
  readonly presence: 'always' | 'optional';
}

/** `contract/token/claims.json`. */
export interface ClaimsFile {
  readonly access: Readonly<Record<string, ClaimRule>>;
  readonly refresh: Readonly<Record<string, ClaimRule>>;
}

export interface RouteCase {
  readonly case: string;
  readonly status: number;
  readonly contentType?: string;
  /** `pair`, when the body is the token pair. */
  readonly body?: string;
  /** The slug of the problem the body is. */
  readonly problem?: string;
}

/** `contract/routes/refresh.json` and `logout.json`. */
export interface RouteFile {
  readonly cacheControl: string;
  readonly cases: readonly RouteCase[];
}

export const PROBLEM_SLUGS: readonly string[] = readdirSync(`${ROOT}problems`)
  .filter(name => name.endsWith('.json'))
  .map(name => name.slice(0, -'.json'.length))
  .sort();

export const contract = {
  problem: (slug: string) => read<ProblemFile>(`problems/${slug}.json`),
  headers: () => read<HeadersFile>('headers.json'),
  claims: () => read<ClaimsFile>('token/claims.json'),
  /** The members of the token pair and their JSON types (the values are placeholders). */
  pair: () =>
    read<{ body: Record<string, unknown> }>('token/composite-token.json').body,
  route: (name: 'refresh' | 'logout') => read<RouteFile>(`routes/${name}.json`),
};

export function jsonTypeOf(value: unknown): string {
  if (value === null) return 'null';
  if (Array.isArray(value)) return 'array';
  return typeof value;
}

/**
 * Throws unless `claims` are a token's claims as the contract allows: every
 * claim it states, of the type it states, and every `always` one present.
 */
export function assertClaimsMatch(
  kind: 'access' | 'refresh',
  claims: Record<string, unknown>,
): void {
  const rules = contract.claims()[kind];
  for (const [name, value] of Object.entries(claims)) {
    const rule = rules[name];
    if (!rule) throw new Error(`The contract has no ${kind} claim "${name}"`);
    if (jsonTypeOf(value) !== rule.type) {
      throw new Error(`${kind} claim "${name}" is not a ${rule.type}`);
    }
  }
  for (const [name, rule] of Object.entries(rules)) {
    if (rule.presence === 'always' && !(name in claims)) {
      throw new Error(
        `The contract says ${kind} claim "${name}" is always there`,
      );
    }
  }
}
