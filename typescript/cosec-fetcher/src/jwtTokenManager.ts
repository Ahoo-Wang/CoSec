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

import type { TokenStorage } from './tokenStorage.js';
import type { CompositeToken, TokenRefresher } from './tokenRefresher.js';
import {
  isNotificationGuardRefresher,
  type NotificationGuardRefresher,
} from './refreshProtocol.js';
import {
  JwtCompositeToken,
  type RefreshTokenStatusCapable,
} from './jwtToken.js';
import type { FetchExchange } from '@ahoo-wang/fetcher';
import {
  TOKEN_SESSION_ATTRIBUTE,
  UNAUTHORIZED_ERROR_INTERCEPTOR_NAME,
} from './constants.js';
import {
  assertTokenSession,
  isSameToken,
  isSameTokenSession,
} from './refreshSession.js';
import {
  InvalidRefreshResponseError,
  RefreshSessionChangedError,
  RefreshTokenError,
  RefreshUnavailableError,
} from './errors.js';
import { withRefreshLock } from './refreshLock.js';
import { isCompositeToken } from './compositeToken.js';
import {
  beginAttempt,
  clearAttempt,
  pairFingerprint,
} from './refreshAttempt.js';
import {
  CoSecProblemType,
  getCoSecProblem,
  retryAfterSeconds,
} from './problem.js';

export {
  RefreshSessionChangedError,
  RefreshTokenError,
  RefreshUnavailableError,
} from './errors.js';

/**
 * Whether a failed refresh ends the session: the refresh route answered
 * `401` (CoSec 8.0: `token-revoked`, `token-expired` or `token-invalid` for a
 * session that is over or a pair that is not one; the status the application
 * is recommended to give a refresh it refuses), or answered with a body that
 * is not a composite token. Read from the `ExchangeError` the refresh client
 * rejects with, so a custom TokenRefresher signals a rejection the same way.
 *
 * Anything else is no verdict on the session and keeps it: `429` (a rate
 * limit, not a refusal), `400`/`403`/`404`, `5xx`, a network error, a timeout.
 * An application whose refresh route refuses with another status passes its
 * own `isRefreshRejected` to {@link JwtTokenManager}.
 */
export function isRefreshRejection(error: unknown): boolean {
  if (error instanceof InvalidRefreshResponseError) return true;
  const status = (
    error as { exchange?: { response?: { status?: unknown } } } | undefined
  )?.exchange?.response?.status;
  return status === 401;
}

/** Options of a {@link JwtTokenManager}. */
export interface JwtTokenManagerOptions {
  /**
   * Whether the error of a failed refresh means the session is over (the
   * stored token is removed, `RefreshTokenError`, `onUnauthorized`) rather
   * than "no answer" (the session is kept, `RefreshUnavailableError`).
   * Defaults to {@link isRefreshRejection}: only a `401`.
   */
  isRefreshRejected?: (error: unknown) => boolean;

  /**
   * The time, in milliseconds, within which CoSec answers a refresh presented
   * again (`cosec.jwt.session.rotation-leeway`, 30 seconds by default).
   * **Set it to at most the server's**: the client cannot read it. After it,
   * a pair whose refresh may have been recorded is a pair "presented again",
   * and presenting it revokes the session (IDN-16).
   */
  rotationLeeway?: number;

  /**
   * The pauses before the retries of a refresh whose outcome is unknown
   * (a timeout, a network error, a 5xx), within the window of the leeway;
   * its length is the number of retries. None by default for a manager built
   * directly (a later request retries the pair, inside the same window);
   * {@link CoSecConfigurer} passes {@link DEFAULT_REFRESH_RETRY_DELAYS}.
   */
  refreshRetryDelays?: readonly number[];
}

/** CoSec's default `cosec.jwt.session.rotation-leeway`. */
export const DEFAULT_ROTATION_LEEWAY = 30_000;

/**
 * The part of the leeway kept back for the retry itself to arrive and for the
 * server's clock: a retry is sent only until `leeway - margin` after the
 * first attempt.
 */
export const ROTATION_LEEWAY_MARGIN = 5_000;

/** What {@link CoSecConfigurer} gives a manager: two retries, after 500 ms and 2 s. */
export const DEFAULT_REFRESH_RETRY_DELAYS: readonly number[] = [500, 2_000];

/** The longest a `Retry-After` keeps the client from calling the refresh route. */
const MAX_RETRY_AFTER = 60_000;

/**
 * CoSec refusals that come from the pipeline, before the refresh handler ran:
 * nothing was moved, so the attempt is resolved (and not "unknown").
 */
const NOT_PROCESSED: ReadonlySet<string> = new Set([
  CoSecProblemType.TOO_MANY_REQUESTS,
  CoSecProblemType.INVALID_REQUEST,
  CoSecProblemType.UNAUTHORIZED,
  CoSecProblemType.FORBIDDEN,
  CoSecProblemType.SCOPE_REJECTED,
]);

/** Whether the refresh client already notified the application, and whether the fetcher has a handler to. */
interface RefreshNotification {
  hasHandler: boolean;
  handled: boolean;
}

/**
 * Manages JWT token refreshing operations and provides status information
 */
export class JwtTokenManager implements RefreshTokenStatusCapable {
  /**
   * After a 429 of the refresh route: the pair it answered, until when the
   * route is not called again (`Retry-After`), and the error to report.
   */
  private refreshBlocked?: {
    fingerprint: string;
    until: number;
    error: unknown;
  };

  private refreshInProgress?: {
    token: JwtCompositeToken;
    promise: Promise<JwtCompositeToken>;
    notification: RefreshNotification;
  };

  /**
   * Creates a new JwtTokenManager instance
   * @param tokenStorage The storage used to persist tokens
   * @param tokenRefresher The refresher used to refresh expired tokens
   */
  constructor(
    public readonly tokenStorage: TokenStorage,
    public readonly tokenRefresher: TokenRefresher,
    public readonly options: JwtTokenManagerOptions = {},
  ) {}

  /**
   * Gets the current JWT composite token from storage
   * @returns The current token or null if none exists
   */
  get currentToken(): JwtCompositeToken | null {
    return this.tokenStorage.get();
  }

  /**
   * Refreshes the JWT token
   * @param exchange Optional originating request used to select the unauthorized notification owner
   * @returns Promise that resolves when refresh is complete
   * @throws Error if no token is found or refresh fails
   * @throws RefreshSessionChangedError if the session changes while refreshing
   */
  async refresh(exchange?: FetchExchange): Promise<void> {
    const jwtToken = this.currentToken;
    if (!jwtToken) {
      throw new Error('No token found');
    }
    if (exchange && this.bindExchange(exchange, jwtToken)) {
      return;
    }
    const inProgress =
      this.refreshInProgress?.token === jwtToken
        ? this.refreshInProgress
        : undefined;
    const notification = inProgress?.notification ?? {
      hasHandler: false,
      handled: false,
    };
    notification.hasHandler ||=
      isNotificationGuardRefresher(this.tokenRefresher) &&
      (exchange?.fetcher.interceptors.error.interceptors.some(
        interceptor => interceptor.name === UNAUTHORIZED_ERROR_INTERCEPTOR_NAME,
      ) ??
        false);
    const promise =
      inProgress?.promise ?? this.startRefresh(jwtToken, notification);
    try {
      await this.awaitRefreshed(exchange, promise);
    } catch (error) {
      if (error instanceof RefreshTokenError && exchange) {
        this.leaveNotificationToTheExchange(exchange, notification);
      }
      throw error;
    }
  }

  /**
   * Ties the originating request to the session it refreshes (the unauthorized
   * notification fires only while that session is the current one).
   *
   * @returns true when the exchange was already refreshed by another call, so
   * there is nothing left to do for it
   */
  private bindExchange(
    exchange: FetchExchange,
    jwtToken: JwtCompositeToken,
  ): boolean {
    const previousToken = exchange.attributes.get(TOKEN_SESSION_ATTRIBUTE);
    assertTokenSession(exchange, jwtToken);
    exchange.attributes.set(TOKEN_SESSION_ATTRIBUTE, jwtToken);
    if (exchange.attributes.get(UNAUTHORIZED_ERROR_INTERCEPTOR_NAME) !== true) {
      exchange.attributes.set(
        UNAUTHORIZED_ERROR_INTERCEPTOR_NAME,
        () =>
          this.currentToken ===
          exchange.attributes.get(TOKEN_SESSION_ATTRIBUTE),
      );
    }
    return !!previousToken && previousToken !== jwtToken;
  }

  /**
   * Starts the one refresh of a pair that every caller then shares: under the
   * lock tabs sharing the storage take turns (Web Locks), so the tab that
   * waited reads the token the other stored instead of spending the already
   * used refresh token, and so never signs that session out.
   */
  private startRefresh(
    jwtToken: JwtCompositeToken,
    notification: RefreshNotification,
  ): Promise<JwtCompositeToken> {
    const promise: Promise<JwtCompositeToken> = withRefreshLock(
      this.tokenStorage,
      async locked => {
        if (locked) {
          const adopted = this.adoptStoredToken(jwtToken);
          if (adopted) return adopted;
        }
        return this.sendRefresh(
          jwtToken,
          this.requestOf(jwtToken, notification),
        )
          .then(newToken => this.store(jwtToken, newToken))
          .catch(error => this.settleFailure(jwtToken, notification, error));
      },
    ).finally(() => {
      if (this.refreshInProgress?.promise === promise) {
        this.refreshInProgress = undefined;
      }
    });
    this.refreshInProgress = { token: jwtToken, promise, notification };
    return promise;
  }

  /**
   * After waiting for the lock: the token another tab stored for the same
   * session, which is the result; null when the stored token is still the
   * pair to refresh.
   *
   * @throws RefreshSessionChangedError when the stored session is another one
   */
  private adoptStoredToken(
    jwtToken: JwtCompositeToken,
  ): JwtCompositeToken | null {
    const storedToken = this.tokenStorage.reload();
    if (isSameToken(jwtToken, storedToken)) return null;
    if (storedToken && isSameTokenSession(jwtToken, storedToken)) {
      return storedToken;
    }
    throw new RefreshSessionChangedError();
  }

  /** One attempt to send the refresh of a pair; the refresher may also throw. */
  private requestOf(
    jwtToken: JwtCompositeToken,
    notification: RefreshNotification,
  ): () => Promise<CompositeToken> {
    const tokenRefresher = this.tokenRefresher as NotificationGuardRefresher;
    const guarded = isNotificationGuardRefresher(tokenRefresher);
    return () =>
      // A refresher that throws instead of rejecting gets the same error
      // handling (classification, cleanup, notification) as a rejection.
      new Promise<CompositeToken>(resolve =>
        resolve(
          guarded
            ? tokenRefresher.refresh(jwtToken.token, () => {
                if (
                  !isSameToken(jwtToken, this.currentToken) ||
                  notification.hasHandler
                )
                  return false;
                notification.handled = true;
                return true;
              })
            : tokenRefresher.refresh(jwtToken.token),
        ),
      );
  }

  /** Stores what the refresh answered, if the session it belongs to is still the stored one. */
  private store(
    jwtToken: JwtCompositeToken,
    newToken: CompositeToken,
  ): JwtCompositeToken {
    // Another tab may have signed out, signed in or refreshed while
    // this refresh was in flight, before its change event reached
    // this tab (Web Locks do not cover sign-in and sign-out): decide
    // from storage, not the cache, whether the result may be stored.
    const currentToken = this.tokenStorage.reload();
    if (!currentToken || !isSameTokenSession(jwtToken, currentToken)) {
      throw new RefreshSessionChangedError();
    }
    if (!isSameToken(jwtToken, currentToken)) return currentToken;
    // A malformed response must not be stored as the session token.
    if (!isCompositeToken(newToken)) {
      throw new InvalidRefreshResponseError();
    }
    const refreshedToken = new JwtCompositeToken(
      newToken,
      this.tokenStorage.earlyPeriod,
      jwtToken.sessionId,
    );
    this.tokenStorage.set(refreshedToken);
    return refreshedToken;
  }

  /**
   * What a failed refresh means: another tab's token to reuse, a session that
   * changed, one the server ended (the stored pair is removed), or one that
   * only could not be refreshed now (the pair is kept).
   */
  private settleFailure(
    jwtToken: JwtCompositeToken,
    notification: RefreshNotification,
    error: unknown,
  ): JwtCompositeToken {
    if (error instanceof RefreshSessionChangedError) {
      throw error;
    }
    // Another tab may already have refreshed this session with a
    // one-time refresh token (so ours failed) and written the new
    // token, before its change event reached this tab: read storage,
    // not the cache, so its token is reused instead of removed.
    const currentToken = this.tokenStorage.reload();
    if (
      currentToken &&
      !isSameToken(jwtToken, currentToken) &&
      isSameTokenSession(jwtToken, currentToken)
    ) {
      return currentToken;
    }
    // The refresh client's own notification may have signed out this session.
    if (
      !isSameToken(jwtToken, currentToken) &&
      (currentToken !== null || !notification.handled)
    ) {
      throw new RefreshSessionChangedError(error);
    }
    if (isSameToken(jwtToken, currentToken)) {
      // Only the server rejecting the refresh token ends the session;
      // a failure to reach it (offline, timeout, 5xx) keeps it.
      if (!(this.options.isRefreshRejected ?? isRefreshRejection)(error)) {
        throw new RefreshUnavailableError(error);
      }
      this.tokenStorage.remove();
    }
    throw new RefreshTokenError(error);
  }

  /** Waits for the shared refresh, and for the caller's exchange to carry the new session. */
  private async awaitRefreshed(
    exchange: FetchExchange | undefined,
    promise: Promise<JwtCompositeToken>,
  ): Promise<void> {
    const refreshedToken = await promise;
    exchange?.attributes.set(TOKEN_SESSION_ATTRIBUTE, refreshedToken);
    if (!isSameTokenSession(refreshedToken, this.currentToken)) {
      throw new RefreshSessionChangedError();
    }
  }

  /**
   * After a refresh that ended the session: the exchange has no session to
   * belong to, and its unauthorized notification fires once, unless the
   * refresh client already notified.
   */
  private leaveNotificationToTheExchange(
    exchange: FetchExchange,
    notification: RefreshNotification,
  ): void {
    exchange.attributes.set(TOKEN_SESSION_ATTRIBUTE, null);
    if (exchange.attributes.get(UNAUTHORIZED_ERROR_INTERCEPTOR_NAME) !== true) {
      exchange.attributes.set(UNAUTHORIZED_ERROR_INTERCEPTOR_NAME, () => {
        if (this.currentToken !== null || notification.handled) return false;
        notification.handled = true;
        return true;
      });
    }
  }

  /**
   * Sends the refresh of a pair, keeping to what CoSec 8.0 allows of a pair
   * whose refresh may have been recorded (IDN-16).
   *
   * - The time of the first attempt whose outcome is unknown is kept with the
   *   pair, and never slides.
   * - Inside the window (`rotationLeeway` less a margin, from that time) the
   *   same pair is retried, for a few pauses, and by any later request: the
   *   server answers a pair presented again within the leeway, so this cures
   *   a response that was lost.
   * - After it, the outcome is still unknown and **nothing can make a retry
   *   safe**: it succeeds if the first attempt never arrived, and revokes the
   *   session if it was recorded (the new pair is lost either way). One
   *   attempt is made per call, and calls are single flight.
   * - A refusal from the pipeline (429, 400, ...) moved nothing: it resolves
   *   the attempt it started, and a 429 keeps the route from being called
   *   until its `Retry-After`.
   *
   * @internal
   */
  async sendRefresh(
    jwtToken: JwtCompositeToken,
    send: () => Promise<CompositeToken>,
  ): Promise<CompositeToken> {
    const storage = this.tokenStorage;
    const rejected = this.options.isRefreshRejected ?? isRefreshRejection;
    const leeway = this.options.rotationLeeway ?? DEFAULT_ROTATION_LEEWAY;
    const delays = this.options.refreshRetryDelays ?? [];
    const fingerprint = pairFingerprint(jwtToken.token);
    const blocked = this.refreshBlocked;
    if (blocked && blocked.fingerprint === fingerprint) {
      if (Date.now() < blocked.until) throw blocked.error;
      this.refreshBlocked = undefined;
    }
    for (let retries = 0; ; retries++) {
      const { at, fresh } = beginAttempt(storage, jwtToken.token, Date.now());
      try {
        const token = await send();
        clearAttempt(storage);
        return token;
      } catch (error) {
        // The session is over: the mark goes with the stored token.
        if (rejected(error)) throw error;
        const slug = getCoSecProblem(error)?.slug;
        if (slug !== undefined && NOT_PROCESSED.has(slug)) {
          if (fresh) clearAttempt(storage);
          this.blockAfterRateLimit(fingerprint, error);
          throw error;
        }
        // Outcome unknown. Retry only inside the window of the first attempt.
        const delay = delays[retries];
        const when = Date.now() + (delay ?? 0);
        if (
          delay === undefined ||
          when < at ||
          when - at >= leeway - ROTATION_LEEWAY_MARGIN
        ) {
          throw error;
        }
        await new Promise(resolve => setTimeout(resolve, delay));
        // Another tab may have signed out, or refreshed in the meantime.
        if (!isSameToken(jwtToken, storage.reload())) throw error;
      }
    }
  }

  /** @internal */
  blockAfterRateLimit(fingerprint: string, error: unknown): void {
    const response = (
      error as { exchange?: { response?: Response } } | undefined
    )?.exchange?.response;
    if (response?.status !== 429) return;
    const seconds = retryAfterSeconds(response);
    if (!seconds) return;
    this.refreshBlocked = {
      fingerprint,
      until: Date.now() + Math.min(seconds * 1000, MAX_RETRY_AFTER),
      error,
    };
  }

  /**
   * Indicates if the current token needs to be refreshed
   * @returns true if the access token is expired and needs refresh, false otherwise
   */
  get isRefreshNeeded(): boolean {
    if (!this.currentToken) {
      return false;
    }
    return this.currentToken.isRefreshNeeded;
  }

  /**
   * Indicates if the current token can be refreshed
   * @returns true if the refresh token is still valid, false otherwise
   */
  get isRefreshable(): boolean {
    if (!this.currentToken) {
      return false;
    }
    return this.currentToken.isRefreshable;
  }
}
