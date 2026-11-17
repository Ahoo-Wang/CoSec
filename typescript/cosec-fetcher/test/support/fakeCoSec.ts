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
 * A model of the parts of CoSec 8.0's wire contract the client depends on. It
 * is written from CoSec's `StatusMapping`, `ProblemEncoder`, `CoSecParameters`
 * and the IDN/SCP rules and `wiki/integration/clients.md`, not from a running
 * CoSec: it proves the client against the contract as read, nothing more.
 */

export interface ProblemRow {
  readonly status: number;
  readonly title: string;
}

/** `ProblemType`: slug, status and title of the ten types. */
export const PROBLEM_ROWS: Record<string, ProblemRow> = {
  'invalid-request': { status: 400, title: 'Invalid Request' },
  unauthorized: { status: 401, title: 'Unauthorized' },
  'token-invalid': { status: 401, title: 'Token Invalid' },
  'token-expired': { status: 401, title: 'Token Expired' },
  'token-revoked': { status: 401, title: 'Token Revoked' },
  forbidden: { status: 403, title: 'Forbidden' },
  'scope-rejected': { status: 403, title: 'Scope Rejected' },
  'too-many-requests': { status: 429, title: 'Too Many Requests' },
  unavailable: { status: 503, title: 'Service Unavailable' },
  'internal-error': { status: 500, title: 'Internal Server Error' },
};

export interface ProblemOptions {
  requestId?: string;
  traceId?: string;
  headers?: Record<string, string>;
}

/**
 * The refusal CoSec answers: `application/problem+json` whose members are
 * `type`, `title`, `status`, `code`, `requestId` and then `traceId`, in this
 * order and nothing else (`ProblemEncoder`), with the `CoSec-Request-Id` header.
 * A `429` carries `Retry-After` (pass it in `headers`).
 */
export function problemResponse(
  slug: string,
  { requestId = 'req-1', traceId, headers = {} }: ProblemOptions = {},
): Response {
  const row = PROBLEM_ROWS[slug];
  const body: Record<string, unknown> = {
    type: `https://cosec.ahoo.me/problems/${slug}`,
    title: row.title,
    status: row.status,
    code: slug.toUpperCase().replaceAll('-', '_'),
    requestId,
  };
  if (traceId) body.traceId = traceId;
  return new Response(JSON.stringify(body), {
    status: row.status,
    headers: {
      'Content-Type': 'application/problem+json',
      'CoSec-Request-Id': requestId,
      ...headers,
    },
  });
}

function base64Url(value: unknown): string {
  return btoa(JSON.stringify(value))
    .replaceAll('+', '-')
    .replaceAll('/', '_')
    .replace(/=+$/, '');
}

/** An unsigned JWT with the given claims (the client never verifies one). */
export function jwt(claims: Record<string, unknown>): string {
  return `${base64Url({ alg: 'none' })}.${base64Url(claims)}.signature`;
}

// ---------------------------------------------------------------------------
// A host: login, refresh with rotation, and a few routes
// ---------------------------------------------------------------------------

export interface FakeToken {
  accessToken: string;
  refreshToken: string;
}

interface Session {
  sid: string;
  generation: number;
  movedAt: number;
  revoked: boolean;
  endsAt: number;
  authTime: number;
  aud?: string;
  sub: string;
  roles: string[];
}

export interface Seen {
  method: string;
  path: string;
  headers: Headers;
  body?: unknown;
  status: number;
}

export interface FakeHostOptions {
  /** `cosec.jwt.session.rotation-leeway`, ms. */
  leeway?: number;
  /** `cosec.jwt.token-validity.access`, seconds. */
  accessTtl?: number;
  /** `cosec.jwt.token-validity.refresh`, seconds. */
  refreshTtl?: number;
  /** `cosec.jwt.session.lifetime`, seconds. */
  sessionLifetime?: number;
  /** `cosec.authorization.scope.app-id`. */
  hostApp?: string;
  /** Whether a statement lets an anonymous caller reach `/auth/refresh`. */
  anonymousRefresh?: boolean;
}

/**
 * CoSec 8.0 as a client sees it: tokens whose `exp` is checked, a rejected
 * token that is anonymous, `SCP-1`/`SCP-3`/`SCP-11`, problem bodies, and a
 * session that rotates on refresh (`IDN-15`, `IDN-16`, `IDN-17`, `IDN-18`).
 * Time is `Date.now()`, so a test moves it with `vi.setSystemTime`.
 *
 * Routes: `/auth/login`, `/auth/refresh` (POST, `{accessToken, refreshToken}`),
 * `/public` (anyone), `/me` (authenticated), `/denied` (authenticated, but
 * the decision is deny), `/limited` (permitted, then `429`), `/down` (`503`).
 */
export class FakeCoSecHost {
  readonly leeway: number;
  readonly accessTtl: number;
  readonly refreshTtl: number;
  readonly sessionLifetime: number;
  hostApp?: string;
  anonymousRefresh: boolean;

  readonly seen: Seen[] = [];
  readonly refreshes: { at: number; generation: number; outcome: string }[] =
    [];
  private readonly sessions = new Map<string, Session>();
  private readonly revokedJti = new Set<string>();
  private counter = 0;

  /** Refresh requests whose response is lost after the server processed it. */
  loseNextRefreshResponses = 0;
  /** Refresh requests that never reach the server (nothing is processed). */
  dropNextRefreshRequests = 0;
  /** Refresh requests answered 503 after the server recorded the move. */
  unavailableAfterRecordingNext = 0;
  /** `Retry-After` of a `429`, seconds. */
  retryAfter = 7;
  /** Refresh requests answered by the pipeline with a rate limit `429`. */
  limitRefreshNext = 0;
  /** A status the application's refresh handler answers instead (no problem body). */
  refreshHandlerStatus?: number;
  /** Replaces an answer after the server produced it (a proxy, a fault). */
  after?: (seen: Seen, response: Response) => Response | Promise<Response>;

  constructor(options: FakeHostOptions = {}) {
    this.leeway = options.leeway ?? 30_000;
    this.accessTtl = options.accessTtl ?? 600;
    this.refreshTtl = options.refreshTtl ?? 7 * 86_400;
    this.sessionLifetime = options.sessionLifetime ?? 30 * 86_400;
    this.hostApp = options.hostApp;
    this.anonymousRefresh = options.anonymousRefresh ?? true;
  }

  private id(prefix: string): string {
    return `${prefix}-${++this.counter}`;
  }

  private nowSec(): number {
    return Math.floor(Date.now() / 1000);
  }

  private pair(session: Session): FakeToken {
    const iat = this.nowSec();
    const accessJti = this.id('at');
    const accessExp = Math.min(iat + this.accessTtl, session.endsAt);
    const common = {
      sid: session.sid,
      auth_time: session.authTime,
      'cosec.generation': session.generation,
    };
    return {
      accessToken: jwt({
        jti: accessJti,
        sub: session.sub,
        iat,
        exp: accessExp,
        roles: session.roles,
        ...(session.aud ? { aud: session.aud } : {}),
        ...common,
      }),
      refreshToken: jwt({
        jti: this.id('rt'),
        sub: accessJti,
        iat,
        exp: Math.min(iat + this.refreshTtl, session.endsAt),
        'cosec.token': 'refresh',
        ...common,
      }),
    };
  }

  /** A login: a new session, generation 1. */
  login(
    sub = 'u1',
    { aud, roles = [] }: { aud?: string; roles?: string[] } = {},
  ): FakeToken {
    const now = this.nowSec();
    const session: Session = {
      sid: this.id('sid'),
      generation: 1,
      movedAt: Date.now(),
      revoked: false,
      endsAt: now + this.sessionLifetime,
      authTime: now,
      aud,
      sub,
      roles,
    };
    this.sessions.set(session.sid, session);
    return this.pair(session);
  }

  /** `TokenRevoker.revoke`: ends the session of a pair and revokes its access token. */
  logout(token: FakeToken): void {
    const access = claims(token.accessToken);
    const session = this.sessions.get(access.sid as string);
    if (session) session.revoked = true;
    this.revokedJti.add(access.jti as string);
  }

  /**
   * An access token the client reads as valid, and the server refuses as
   * \`token-invalid\`: a refresh token where an access token belongs (IDN-13).
   */
  invalidAccessToken(token: FakeToken): FakeToken {
    const access = claims(token.accessToken);
    return {
      ...token,
      accessToken: jwt({ ...access, 'cosec.token': 'refresh' }),
    };
  }

  sessionOf(token: FakeToken): { generation: number; revoked: boolean } {
    const session = this.sessions.get(claims(token.accessToken).sid as string)!;
    return { generation: session.generation, revoked: session.revoked };
  }

  /** Every session reaches its lifetime (`token-expired` on refresh). */
  expireSessions(): void {
    for (const session of this.sessions.values()) session.endsAt = 0;
  }

  /** The server knows no session of the pairs it issued (`token-invalid` on refresh). */
  forgetSessions(): void {
    this.sessions.clear();
  }

  /** What `StatusMapping.denied` calls the authentication result. */
  private authenticate(
    headers: Headers,
  ):
    | { kind: 'anonymous' }
    | { kind: 'rejected'; slug: string }
    | { kind: 'authenticated'; claims: Record<string, unknown> } {
    const authorization = headers.get('authorization');
    if (!authorization) return { kind: 'anonymous' };
    const token = authorization.replace(/^Bearer /, '');
    const payload = claims(token);
    if (!payload.jti || !payload.sub) {
      return { kind: 'rejected', slug: 'token-invalid' };
    }
    if (payload['cosec.token']) {
      return { kind: 'rejected', slug: 'token-invalid' };
    }
    if (this.revokedJti.has(payload.jti as string)) {
      return { kind: 'rejected', slug: 'token-revoked' };
    }
    if ((payload.exp as number) <= this.nowSec()) {
      return { kind: 'rejected', slug: 'token-expired' };
    }
    return { kind: 'authenticated', claims: payload };
  }

  /** A `fetch` implementation to hand to a `Fetcher`. */
  fetch = async (
    input: RequestInfo | URL,
    init?: RequestInit,
  ): Promise<Response> => {
    const url = new URL(
      String(input instanceof Request ? input.url : input),
      'https://api.test',
    );
    const method = (init?.method ?? 'GET').toUpperCase();
    const headers = new Headers(init?.headers);
    let body: unknown;
    if (typeof init?.body === 'string') {
      try {
        body = JSON.parse(init.body);
      } catch {
        body = init.body;
      }
    }
    const seen: Seen = { method, path: url.pathname, headers, body, status: 0 };
    this.seen.push(seen);
    try {
      let response = await this.route(url.pathname, method, headers, body);
      if (this.after) response = await this.after(seen, response);
      seen.status = response.status;
      return response;
    } catch (error) {
      seen.status = 0;
      throw error;
    }
  };

  private respond(headers: Headers, status: number, body?: unknown): Response {
    const requestId = headers.get('cosec-request-id') ?? 'req-1';
    return new Response(body === undefined ? null : JSON.stringify(body), {
      status,
      headers: {
        'Content-Type': 'application/json',
        'CoSec-Request-Id': requestId,
      },
    });
  }

  private refuse(
    headers: Headers,
    slug: string,
    extra: Record<string, string> = {},
  ): Response {
    return problemResponse(slug, {
      requestId: headers.get('cosec-request-id') ?? 'req-1',
      headers: extra,
    });
  }

  private async route(
    path: string,
    method: string,
    headers: Headers,
    body: unknown,
  ): Promise<Response> {
    const authn = this.authenticate(headers);
    const space = headers.get('cosec-space-id')?.trim();
    const refreshRoute = path === '/auth/refresh';

    // Scope (SCP-1, SCP-3, SCP-11): decided before any statement.
    let scopeRejected = false;
    if (space) {
      if (authn.kind !== 'authenticated') {
        // An anonymous request that declares a space; a rejected token is
        // named by its type (StatusMapping.denied).
        return this.refuse(
          headers,
          authn.kind === 'rejected' ? authn.slug : 'unauthorized',
        );
      }
      const roles = (authn.claims.roles as string[] | undefined) ?? [];
      scopeRejected =
        space.startsWith('(') ||
        !roles.some(role => role.endsWith(`@${space}`));
    }
    if (
      authn.kind === 'authenticated' &&
      this.hostApp !== undefined &&
      authn.claims.aud !== this.hostApp
    ) {
      scopeRejected = true;
    }
    if (scopeRejected) return this.refuse(headers, 'scope-rejected');

    const deny = (): Response =>
      authn.kind === 'authenticated'
        ? this.refuse(headers, 'forbidden')
        : this.refuse(
            headers,
            authn.kind === 'rejected' ? authn.slug : 'unauthorized',
          );

    switch (path) {
      case '/public':
      case '/auth/login':
        return this.respond(headers, 200, { ok: true });
      case '/me':
        return authn.kind === 'authenticated'
          ? this.respond(headers, 200, { sub: authn.claims.sub })
          : deny();
      case '/denied':
        return deny();
      case '/limited':
        if (authn.kind !== 'authenticated') return deny();
        return this.refuse(headers, 'too-many-requests', {
          'Retry-After': String(this.retryAfter),
          'RateLimit-Limit': '10',
          'RateLimit-Remaining': '0',
          'RateLimit-Reset': String(this.retryAfter),
        });
      case '/down':
        return this.refuse(headers, 'unavailable');
      case '/broken':
        return this.refuse(headers, 'internal-error');
    }
    if (refreshRoute) {
      // The pipeline: an expired access token is anonymous.
      if (authn.kind !== 'authenticated' && !this.anonymousRefresh) {
        return deny();
      }
      if (this.limitRefreshNext > 0) {
        this.limitRefreshNext--;
        return this.refuse(headers, 'too-many-requests', {
          'Retry-After': String(this.retryAfter),
        });
      }
      if (this.dropNextRefreshRequests > 0) {
        this.dropNextRefreshRequests--;
        throw new TypeError('Failed to fetch');
      }
      return this.refreshHandler(headers, body);
    }
    return this.respond(headers, 404);
  }

  /** `SimpleRefreshTokenAuthentication` over `TokenFamilies` (IDN-15..19). */
  private refreshHandler(headers: Headers, body: unknown): Response {
    if (this.refreshHandlerStatus !== undefined) {
      return this.respond(headers, this.refreshHandlerStatus);
    }
    const pair = body as Partial<FakeToken> | undefined;
    if (
      !pair ||
      typeof pair.accessToken !== 'string' ||
      typeof pair.refreshToken !== 'string'
    ) {
      return this.respond(headers, 400);
    }
    const refresh = claims(pair.refreshToken);
    const access = claims(pair.accessToken);
    if (
      refresh['cosec.token'] !== 'refresh' ||
      refresh.sub !== access.jti ||
      (refresh.exp as number) <= this.nowSec()
    ) {
      return this.refuse(headers, 'token-invalid');
    }
    const session = this.sessions.get(refresh.sid as string);
    if (!session) return this.refuse(headers, 'token-invalid');
    if (session.revoked) return this.refuse(headers, 'token-revoked');
    if (session.endsAt <= this.nowSec()) {
      return this.refuse(headers, 'token-expired');
    }
    const generation = refresh['cosec.generation'] as number;
    const at = Date.now();
    if (generation === session.generation) {
      session.generation++;
      session.movedAt = at;
      this.refreshes.push({ at, generation, outcome: 'moved' });
      return this.deliver(headers, session);
    }
    if (
      generation === session.generation - 1 &&
      at - session.movedAt <= this.leeway
    ) {
      this.refreshes.push({ at, generation, outcome: 'healed' });
      return this.deliver(headers, session);
    }
    session.revoked = true;
    this.refreshes.push({ at, generation, outcome: 'revoked' });
    return this.refuse(headers, 'token-revoked');
  }

  private deliver(headers: Headers, session: Session): Response {
    if (this.loseNextRefreshResponses > 0) {
      this.loseNextRefreshResponses--;
      throw new TypeError('Failed to fetch');
    }
    if (this.unavailableAfterRecordingNext > 0) {
      this.unavailableAfterRecordingNext--;
      return this.refuse(headers, 'unavailable');
    }
    return this.respond(headers, 200, this.pair(session));
  }

  /** Requests the host saw on `path`. */
  on(path: string): Seen[] {
    return this.seen.filter(seen => seen.path === path);
  }
}

/** The claims of a token made by {@link jwt}. */
export function claims(token: string): Record<string, unknown> {
  const body = token.split('.')[1] ?? '';
  try {
    return JSON.parse(
      atob(body.replaceAll('-', '+').replaceAll('_', '/')),
    ) as Record<string, unknown>;
  } catch {
    return {};
  }
}
