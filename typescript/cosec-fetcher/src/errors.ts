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

import { FetcherError } from '@ahoo-wang/fetcher';
import { getCoSecProblem, type CoSecProblem } from './problem.js';

/**
 * The server refused the refresh token, so the session ended.
 *
 * It carries no token: the request of a refresh has the pair as its body, so
 * the error of that request is not kept. Its `cause` is a plain `Error` with
 * the name and message of that error (and its causes, likewise), and
 * {@link problem} and {@link status} say what the server answered.
 */
export class RefreshTokenError extends FetcherError {
  /**
   * Why CoSec refused the refresh, when it said so (`token-revoked`: the
   * session ended; `token-expired`: its lifetime is over, or the refresh route
   * is closed to anonymous callers; `token-invalid`: the pair is not one).
   */
  readonly problem?: CoSecProblem;
  /** The status the refresh route answered, when it answered. */
  readonly status?: number;

  constructor(cause?: unknown) {
    super(`Refresh token failed.`, withoutExchange(cause));
    this.problem = getCoSecProblem(cause);
    this.status = answeredStatus(cause);
    this.name = 'RefreshTokenError';
  }
}

/**
 * The session changed while refreshing; the original request must stop. Like
 * the other refresh errors, its `cause` keeps only the name and message of the
 * refresh's error, never its exchange.
 */
export class RefreshSessionChangedError extends FetcherError {
  constructor(cause?: unknown) {
    super('The token session changed during refresh.', withoutExchange(cause));
    this.name = 'RefreshSessionChangedError';
  }
}

/**
 * The refresh could not complete — a network failure, a timeout, an abort, a
 * 5xx — but the server did not reject the refresh token, so the session is
 * kept and no unauthorized notification is sent. A later request refreshes
 * again. Only a 4xx answer from the refresh endpoint (or a malformed refresh
 * response) ends the session with {@link RefreshTokenError}.
 *
 * It carries no token: its `cause` is a plain `Error` with the name and message
 * of the refresh's error, whose request had the pair as its body; {@link problem}
 * and {@link status} say what the server answered.
 */
export class RefreshUnavailableError extends FetcherError {
  /** What CoSec answered (`too-many-requests`, `unavailable`, ...), when it said. */
  readonly problem?: CoSecProblem;
  /** The status the refresh route answered, when it answered. */
  readonly status?: number;

  constructor(cause?: unknown) {
    super(
      'Refresh token is unavailable; the session is kept.',
      withoutExchange(cause),
    );
    this.problem = getCoSecProblem(cause);
    this.status = answeredStatus(cause);
    this.name = 'RefreshUnavailableError';
  }
}

/**
 * The server did not confirm a logout: a `5xx`, a network failure, a timeout,
 * or no {@link TokenRevoker} to ask. The pair was removed here all the same
 * ({@link JwtTokenManager.logout}); the session may live on at the server until
 * it ends there, at the latest when its refresh token expires.
 *
 * It carries no token: the request of a logout has the pair as its body, so
 * the error of that request is not kept. Its `cause` is a plain `Error` with
 * the name and message of that error (and its causes, likewise), and
 * {@link problem} and {@link status} say what the server answered.
 */
export class LogoutUnconfirmedError extends FetcherError {
  /** What CoSec answered (`unavailable`, `internal-error`, ...), when it said. */
  readonly problem?: CoSecProblem;
  /** The status the server answered, when it answered. */
  readonly status?: number;

  constructor(cause?: unknown) {
    super(
      'The server did not confirm the logout; the session was ended here.',
      withoutExchange(cause),
    );
    this.problem = getCoSecProblem(cause);
    this.status = answeredStatus(cause);
    this.name = 'LogoutUnconfirmedError';
  }
}

/** The status of the response `error`'s exchange received, when it received one. */
function answeredStatus(error: unknown): number | undefined {
  const status = (
    error as { exchange?: { response?: { status?: unknown } } } | undefined
  )?.exchange?.response?.status;
  return typeof status === 'number' ? status : undefined;
}

/** Causes past this depth are left out (a chain can loop). */
const CAUSE_DEPTH = 8;

/**
 * The name and message of `error` and of each of its causes, and nothing else
 * of them: not an exchange, not a request. A fetcher error holds the request,
 * whose body may be the token pair, so no original error object is kept.
 */
function withoutExchange(error: unknown, depth = 0): Error | undefined {
  if (error === undefined || error === null || depth >= CAUSE_DEPTH) {
    return undefined;
  }
  const cause =
    error instanceof Error
      ? withoutExchange((error as { cause?: unknown }).cause, depth + 1)
      : undefined;
  const plain: Error & { cause?: unknown } = new Error(
    error instanceof Error ? error.message : String(error),
  );
  if (cause !== undefined) plain.cause = cause;
  plain.name = error instanceof Error ? error.name : 'Error';
  return plain;
}

/** The refresh endpoint answered, but not with a composite token. */
export class InvalidRefreshResponseError extends Error {
  constructor() {
    super('The refresh response has no accessToken and refreshToken.');
    this.name = 'InvalidRefreshResponseError';
  }
}

/**
 * A request answered 401 could not be retried after the refresh, because its
 * body (a `ReadableStream` or another async iterable) was consumed by the
 * first attempt. The session was refreshed: send the request again with a
 * new body.
 */
export class NonReplayableRequestError extends FetcherError {
  constructor() {
    super(
      'The request body can be sent only once; the token was refreshed, send the request again with a new body.',
    );
    this.name = 'NonReplayableRequestError';
  }
}
