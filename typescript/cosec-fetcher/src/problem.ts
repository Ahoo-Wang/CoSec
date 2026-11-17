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

import type { FetchExchange } from '@ahoo-wang/fetcher';
import {
  isProblemDetailsResponse,
  readProblemDetails,
  type ProblemDetails,
} from '@ahoo-wang/fetcher';

/**
 * Prefix of the `type` of every problem CoSec answers a refusal with
 * (`application/problem+json`, RFC 9457).
 */
export const COSEC_PROBLEM_TYPE_PREFIX = 'https://cosec.ahoo.me/problems/';

/**
 * The slugs of the problem types CoSec 8.0 defines: the last segment of the
 * problem's `type`, and the lower-case form of its `code`.
 *
 * The set grows in minor releases of CoSec: a `switch` over it needs a
 * `default`, and {@link CoSecProblem.slug} may hold a slug not listed here.
 */
export const CoSecProblemType = {
  /** 400: a path or `CoSec-*` parameter that cannot be read without ambiguity. A defect of the client. */
  INVALID_REQUEST: 'invalid-request',
  /** 401: no credential was seen. Sign in. */
  UNAUTHORIZED: 'unauthorized',
  /** 401: the credential is not accepted; a refresh cannot help. */
  TOKEN_INVALID: 'token-invalid',
  /** 401: the access token has expired; refresh once. */
  TOKEN_EXPIRED: 'token-expired',
  /** 401: the token or its session was revoked; a refresh cannot help. */
  TOKEN_REVOKED: 'token-revoked',
  /** 403: authenticated, and denied. */
  FORBIDDEN: 'forbidden',
  /** 403: the app or the space of the request is not one the token may use. */
  SCOPE_REJECTED: 'scope-rejected',
  /** 429: a rate limit rule has no permit left; see `Retry-After`. */
  TOO_MANY_REQUESTS: 'too-many-requests',
  /** 503: no decision could be made. Keep the session and retry later. */
  UNAVAILABLE: 'unavailable',
  /** 500: a defect of CoSec. */
  INTERNAL_ERROR: 'internal-error',
} as const;

export type CoSecProblemSlug =
  (typeof CoSecProblemType)[keyof typeof CoSecProblemType];

/**
 * The body of a refusal by CoSec 8.0: always `type`, `title`, `status`, `code`
 * and `requestId`, and `traceId` when tracing captured one; nothing else.
 */
export interface CoSecProblem {
  /** The last segment of {@link type}, e.g. `token-expired`. Decide on this, never on `title`. */
  readonly slug: CoSecProblemSlug | (string & {});
  /** The problem's `type`: `https://cosec.ahoo.me/problems/<slug>`. */
  readonly type: string;
  readonly title?: string;
  readonly status?: number;
  /** The slug in upper snake case, e.g. `TOKEN_EXPIRED`. */
  readonly code?: string;
  /** Equal to the `CoSec-Request-Id` response header: it finds the request in the audit. */
  readonly requestId?: string;
  readonly traceId?: string;
}

const PROBLEM_ATTRIBUTE = 'CoSec-Problem';

function stringMember(
  details: ProblemDetails,
  name: string,
): string | undefined {
  const value = details[name];
  return typeof value === 'string' ? value : undefined;
}

/**
 * The CoSec problem of parsed problem details, or `undefined` when its `type`
 * is not one of CoSec's.
 */
export function toCoSecProblem(
  details: ProblemDetails | undefined,
): CoSecProblem | undefined {
  if (
    !details ||
    typeof details.type !== 'string' ||
    !details.type.startsWith(COSEC_PROBLEM_TYPE_PREFIX)
  ) {
    return undefined;
  }
  const slug = details.type.slice(COSEC_PROBLEM_TYPE_PREFIX.length);
  if (!slug || slug.includes('/')) return undefined;
  return {
    slug,
    type: details.type,
    title: details.title,
    status: details.status,
    code: stringMember(details, 'code'),
    requestId: stringMember(details, 'requestId'),
    traceId: stringMember(details, 'traceId'),
  };
}

/**
 * Reads the CoSec problem of a response from a copy of its body. Resolves to
 * `undefined` for a response that is not `application/problem+json` of CoSec's
 * (a proxy's error page, an application's own answer, a body already read).
 */
export async function readCoSecProblem(
  response: Response,
): Promise<CoSecProblem | undefined> {
  return toCoSecProblem(await readProblemDetails(response));
}

/**
 * The CoSec problem an error carries, without reading anything: an
 * `HttpStatusValidationError` of a problem response, or a
 * {@link RefreshTokenError} / {@link RefreshUnavailableError} of a refresh that
 * was refused.
 */
export function getCoSecProblem(error: unknown): CoSecProblem | undefined {
  if (typeof error !== 'object' || error === null) return undefined;
  const { problem, cause } = error as { problem?: unknown; cause?: unknown };
  if (typeof problem === 'object' && problem !== null) {
    const known = problem as Partial<CoSecProblem> & ProblemDetails;
    return typeof known.slug === 'string'
      ? (known as CoSecProblem)
      : toCoSecProblem(known);
  }
  // Errors wrapping the refresh failure (not the exchange's own).
  return cause === error ? undefined : getCoSecProblem(cause);
}

/**
 * The CoSec problem of an exchange's refusal, read at most once per response:
 * from the error the status validation raised when it carries one, otherwise
 * from a copy of the response body. It answers at once (not a promise) when
 * nothing has to be read: an error that carries the problem, or a response
 * that is not `application/problem+json`.
 */
export function resolveCoSecProblem(
  exchange: FetchExchange,
): CoSecProblem | undefined | Promise<CoSecProblem | undefined> {
  const response = exchange.response;
  // A retry replaces the response: the memo answers for one response only.
  const memo = exchange.attributes?.get(PROBLEM_ATTRIBUTE) as
    | { response: Response | undefined; problem: CoSecProblem | undefined }
    | undefined;
  if (memo && memo.response === response) return memo.problem;
  const remember = (problem: CoSecProblem | undefined) => {
    exchange.attributes?.set(PROBLEM_ATTRIBUTE, { response, problem });
    return problem;
  };
  const known = getCoSecProblem(exchange.error);
  if (known || !response || !hasProblemBody(response)) return remember(known);
  return readCoSecProblem(response).then(remember, () => remember(undefined));
}

function hasProblemBody(response: Response): boolean {
  try {
    return isProblemDetailsResponse(response);
  } catch {
    // A Response of another realm or a stand-in without headers: it has no
    // problem to read, and reading must never fail a request.
    return false;
  }
}

/**
 * The seconds a `Retry-After` response header asks to wait (whole seconds, as
 * CoSec writes it, or an HTTP date), or `undefined` when absent or unreadable.
 */
export function retryAfterSeconds(
  response: Response | undefined,
  now: number = Date.now(),
): number | undefined {
  const value = response?.headers?.get('retry-after')?.trim();
  if (!value) return undefined;
  if (/^\d+$/.test(value)) return Number(value);
  const at = Date.parse(value);
  return Number.isNaN(at)
    ? undefined
    : Math.max(0, Math.ceil((at - now) / 1000));
}
