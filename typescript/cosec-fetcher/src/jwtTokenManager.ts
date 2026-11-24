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
import type { TokenRevoker } from './tokenRevoker.js';
import type {
  CoSecTenantSwitcher,
  TenantSwitchRequest,
} from './tenantSwitcher.js';
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
  LogoutUnconfirmedError,
  RefreshSessionChangedError,
  RefreshTokenError,
  RefreshUnavailableError,
  TenantSwitchError,
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
  LogoutUnconfirmedError,
  RefreshSessionChangedError,
  RefreshTokenError,
  RefreshUnavailableError,
  TenantSwitchError,
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
  /**
   * What ends a session at the server when {@link JwtTokenManager.logout} is
   * called: {@link CoSecTokenRevoker} of CoSec's logout route. Without it,
   * `logout()` signs out here and rejects with {@link LogoutUnconfirmedError}.
   */
  tokenRevoker?: TokenRevoker;
  /**
   * What sends a tenant or app switch when {@link JwtTokenManager.switchTenant}
   * is called: {@link CoSecTenantSwitcher} over the application's configured
   * `Fetcher`. Without it, `switchTenant` rejects.
   */
  tenantSwitcher?: CoSecTenantSwitcher;
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

  /** The tenant switch in progress, which a refresh in this process waits for. */
  private switching?: Promise<void>;

  /** Counts the switches started, so the last one alone clears {@link switching}. */
  private switches = 0;

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
    const before = this.currentToken;
    if (this.switching) {
      await this.switching.catch(() => undefined);
      // The switch stored another session's pair, or removed it: there is
      // nothing left of what this call meant to refresh. An exchange made with
      // the old session fails rather than being sent again in the new one.
      if (before && !isSameToken(before, this.currentToken)) {
        if (exchange) assertTokenSession(exchange, this.currentToken);
        return;
      }
    }
    return this.refreshCurrent(exchange);
  }

  private async refreshCurrent(exchange?: FetchExchange): Promise<void> {
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
        return this.refreshPair(jwtToken, notification);
      },
    ).finally(() => {
      if (this.refreshInProgress?.promise === promise) {
        this.refreshInProgress = undefined;
      }
    });
    this.refreshInProgress = { token: jwtToken, promise, notification };
    return promise;
  }

  /** Sends the refresh of `jwtToken` and settles it: the caller holds the lock. */
  private refreshPair(
    jwtToken: JwtCompositeToken,
    notification: RefreshNotification,
  ): Promise<JwtCompositeToken> {
    return this.sendRefresh(jwtToken, this.requestOf(jwtToken, notification))
      .then(newToken => this.store(jwtToken, newToken))
      .catch(error => this.settleFailure(jwtToken, notification, error));
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
   * Logs out: ends the session at the server (the {@link TokenRevoker} of
   * `options.tokenRevoker`, CoSec's logout route), then signs out here.
   *
   * A refresh in flight is waited for first, and the session is ended with the
   * pair stored after it: the pair that refresh rotated away would be refused
   * (`401`) and the renewed session would live on at the server with nobody
   * holding it. A refresh that starts while the logout is on its way cannot
   * store its answer: the pair is gone, and it only stores into the session it
   * started from.
   *
   * The pair is removed here whatever the server answered, as long as it is
   * still the stored session (another tab may have signed in meanwhile): the
   * user asked to leave this device, and a pair kept after a failed logout would
   * stay usable here.
   *
   * - Resolves when the server ended the session (`204`), or answered `401`:
   *   the session was not usable any more, so there is nothing left to end.
   * - Rejects with {@link LogoutUnconfirmedError} for anything else (`503`,
   *   `500`, a network failure, a timeout, no `tokenRevoker`): the pair is gone
   *   here, and the session may live on at the server until it ends there, at
   *   the latest when its refresh token expires.
   *
   * Without a stored pair it does nothing. {@link TokenStorage.signOut} stays
   * the local, synchronous sign-out.
   */
  async logout(): Promise<void> {
    await this.switching?.catch(() => undefined);
    // A refresh can start again after one settles (another request, another tab's change): wait until none is running.
    for (
      let refreshing = this.refreshInProgress;
      refreshing;
      refreshing = this.refreshInProgress
    ) {
      await refreshing.promise.catch(() => undefined);
    }
    const jwtToken = this.currentToken;
    if (!jwtToken) return;
    let unconfirmed: unknown;
    try {
      const revoker = this.options.tokenRevoker;
      if (!revoker) {
        throw new Error(
          "No tokenRevoker: give the JwtTokenManager a CoSecTokenRevoker of the server's logout route.",
        );
      }
      await revoker.revoke(jwtToken.token);
    } catch (error) {
      if (!isRefreshRejection(error)) unconfirmed = error ?? new Error();
    } finally {
      if (isSameTokenSession(jwtToken, this.tokenStorage.reload())) {
        this.tokenStorage.signOut();
      }
    }
    if (unconfirmed !== undefined) {
      throw new LogoutUnconfirmedError(unconfirmed);
    }
  }

  /**
   * Switches the session to another tenant, or app, through the
   * application's switch route (`SwitchTenantAuthentication`), and stores the
   * pair it answers in place of the current one (`IDN-22`).
   *
   * - **The current generation is presented.** A refresh in flight is waited
   *   for, an expired access token is refreshed first, and the switch is sent
   *   under the lock refreshes take (in this process, and across tabs that
   *   share the storage: the same Web Lock), with the pair read again once the
   *   lock is held. No refresh replaces the pair while the switch is on its
   *   way, and `Authorization` and the body are always that one pair.
   * - **`token-expired` is refreshed once.** The route is reached with the
   *   access token, so a `401 token-expired` may be the pipeline's (a device
   *   clock behind the server's) or the route's (the session's lifetime is
   *   over): the pair is refreshed once and the switch sent again with the new
   *   pair, as `AuthorizationResponseInterceptor` does for any request. A
   *   session that is over refuses that refresh, which ends it as any refused
   *   refresh does (`RefreshTokenError`, `onUnauthorized` once); a second
   *   `token-expired` after a refresh that succeeded keeps the pair
   *   ({@link TenantSwitchError}, `sessionEnded` false).
   * - **Any other refused pair ends the session here.** A `401` from the
   *   switch route (or what `isRefreshRejected` names) removes the stored pair
   *   if it is still the one sent, `onUnauthorized` is called once, and it
   *   rejects with a {@link TenantSwitchError} whose `sessionEnded` is true.
   * - **Anything else keeps the session**: the application's own refusal
   *   (say a `403` of its `TenantSwitch`), a `503`, a network error or a
   *   timeout reject with a {@link TenantSwitchError} and leave the pair as it
   *   was. Nothing is retried. A switch never moves the session it is
   *   presented from (the server checks the pair as a refresh does, without
   *   rotating it), so calling `switchTenant` again is safe; a switch whose
   *   answer was lost started a session nobody holds, which ends with the
   *   login's session (`IDN-36`).
   * - **The answer is stored as one write**, and reaches the other tabs as a
   *   new sign-in. If the stored session changed meanwhile (a sign-out or a
   *   sign-in elsewhere), nothing is stored and it rejects with
   *   {@link RefreshSessionChangedError}.
   *
   * @throws TenantSwitchError when the switch issued nothing that was stored
   * @throws RefreshSessionChangedError when the stored session changed during the switch
   */
  async switchTenant(request: TenantSwitchRequest): Promise<void> {
    const switcher = this.options.tenantSwitcher;
    if (!switcher) {
      throw new Error(
        "No tenantSwitcher: give the JwtTokenManager a CoSecTenantSwitcher of the application's configured Fetcher.",
      );
    }
    const previous = this.switching;
    const run = (async () => {
      await previous?.catch(() => undefined);
      for (
        let refreshing = this.refreshInProgress;
        refreshing;
        refreshing = this.refreshInProgress
      ) {
        await refreshing.promise.catch(() => undefined);
      }
      const stored = this.currentToken;
      if (!stored) throw new Error('No token found');
      if (stored.isRefreshNeeded && stored.isRefreshable) {
        await this.refreshCurrent();
      }
      await withRefreshLock(this.tokenStorage, locked =>
        this.sendSwitch(switcher, request, locked),
      );
    })();
    const started = ++this.switches;
    this.switching = run;
    try {
      await run;
    } finally {
      if (this.switches === started) this.switching = undefined;
    }
  }

  /**
   * The switch itself, under the lock: present the stored pair, settle, store.
   *
   * The switch route is reached with the access token, so a `401
   * token-expired` may be the pipeline's (the access token expired, which a
   * device clock behind the server's does not see) or the route's (the
   * session's lifetime is over). As for any request
   * (`AuthorizationResponseInterceptor`), it is refreshed once and the switch
   * sent again with the new pair: a session that is over refuses the refresh,
   * which ends it. A second `token-expired` after a refresh that succeeded
   * keeps the pair.
   */
  private async sendSwitch(
    switcher: CoSecTenantSwitcher,
    request: TenantSwitchRequest,
    locked: boolean,
  ): Promise<void> {
    const storage = this.tokenStorage;
    const rejected = this.options.isRefreshRejected ?? isRefreshRejection;
    let sent = locked ? storage.reload() : this.currentToken;
    if (!sent) throw new Error('No token found');
    let refreshed = false;
    let answer: CompositeToken;
    for (;;) {
      try {
        // The switch's own 401 notifies nobody: what it means is decided here.
        answer = await switcher.switch(sent.token, request, () => false);
        break;
      } catch (error) {
        if (!rejected(error)) throw new TenantSwitchError(error, false);
        const expired =
          getCoSecProblem(error)?.slug === CoSecProblemType.TOKEN_EXPIRED;
        if (expired && refreshed) throw new TenantSwitchError(error, false);
        if (expired && sent.isRefreshable) {
          refreshed = true;
          sent = await this.refreshInSwitch(sent, error);
          continue;
        }
        if (isSameToken(sent, storage.reload())) {
          storage.remove();
          await notifyUnauthorized(error, error);
        }
        throw new TenantSwitchError(error, true);
      }
    }
    if (!isCompositeToken(answer)) {
      throw new TenantSwitchError(new InvalidRefreshResponseError(), false);
    }
    if (!isSameTokenSession(sent, storage.reload())) {
      throw new RefreshSessionChangedError();
    }
    storage.signIn(answer);
  }

  /**
   * The one refresh of a switch answered `token-expired`, under the lock the
   * switch holds. A refusal ends the session as any refresh's does, and the
   * unauthorized notification goes to the switch's request, once.
   */
  private async refreshInSwitch(
    jwtToken: JwtCompositeToken,
    switchError: unknown,
  ): Promise<JwtCompositeToken> {
    // The refresh request notifies nobody either: the switch's does, below.
    const notification = { hasHandler: true, handled: false };
    try {
      return await this.refreshPair(jwtToken, notification);
    } catch (error) {
      if (error instanceof RefreshTokenError && this.currentToken === null) {
        await notifyUnauthorized(switchError, error);
      }
      throw error;
    }
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

/**
 * Hands the unauthorized notification of a switch to the
 * `UnauthorizedErrorInterceptor` of the `Fetcher` that sent it, once: the
 * switch's request reached the error phase before its outcome was known.
 * `error` is what the interceptor reports (the switch's own refusal, or the
 * refresh that ended the session).
 */
async function notifyUnauthorized(
  switchError: unknown,
  error: unknown,
): Promise<void> {
  const exchange = (switchError as { exchange?: FetchExchange } | undefined)
    ?.exchange;
  const interceptor = exchange?.fetcher?.interceptors.error.interceptors.find(
    each => each.name === UNAUTHORIZED_ERROR_INTERCEPTOR_NAME,
  );
  if (!exchange || !interceptor) return;
  if (error !== switchError) exchange.error = error as Error;
  exchange.attributes.set(UNAUTHORIZED_ERROR_INTERCEPTOR_NAME, () => true);
  await interceptor.intercept(exchange);
}
