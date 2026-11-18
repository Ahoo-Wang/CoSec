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

import {
  combineURLs,
  type FetchExchange,
  isAbsoluteURL,
} from '@ahoo-wang/fetcher';

/** Exchange attribute holding the trust decided for it, per predicate. */
const REQUEST_TRUST_ATTRIBUTE = 'CoSec-Request-Trust';

/**
 * Decides whether a request to an absolute URL may carry CoSec credentials
 * (the `Authorization` token and the CoSec headers, device ID included).
 *
 * @param url - The request's absolute URL, before path parameters are filled.
 *   For a relative URL that the URL parser resolves to another host (such as
 *   `\host/path`), the resolved absolute URL.
 * @param exchange - The exchange being sent
 */
export type RequestTrust = (url: string, exchange: FetchExchange) => boolean;

export interface RequestTrustCapable {
  /**
   * Which absolute request URLs may carry CoSec credentials: the access
   * token, the device ID and the other CoSec headers, and the token's tenant
   * and owner in the URL. By default {@link sameOriginTrust}: the origin of an
   * absolute `baseURL` and the page's own origin, so a pagination link, a
   * download URL or a callback to another origin gets none. Pass your own
   * predicate to trust more origins; one that wants every request to carry
   * them says so, `() => true`. A relative request URL that resolves against
   * the fetcher's own `baseURL` is always trusted; one that the URL parser
   * resolves to another host (such as `\host/path`) is judged like an
   * absolute URL.
   */
  readonly isTrusted?: RequestTrust;
}

/** Base for relative URLs where there is no page location (Node, workers). */
const NO_LOCATION_BASE = 'http://location.invalid/';

function parseURL(url: string): URL | undefined {
  try {
    return new URL(url, globalThis.location?.href ?? NO_LOCATION_BASE);
  } catch {
    return undefined;
  }
}

function originOf(url: string): string | undefined {
  try {
    return new URL(url, globalThis.location?.href).origin;
  } catch {
    return undefined;
  }
}

/**
 * Decides a relative request URL. Joined to the `baseURL`, it normally stays
 * on the `baseURL`'s scheme and host and is trusted. URL parsing can still
 * take it elsewhere — `\host/path`, mixed slashes, or tabs and newlines the
 * parser strips — so a URL that leaves is judged by `isTrusted` on the URL it
 * actually resolves to.
 */
function isTrustedRelative(
  url: string,
  exchange: FetchExchange,
  isTrusted: RequestTrust,
): boolean {
  const baseURL = exchange.fetcher.urlBuilder.baseURL;
  const target = parseURL(combineURLs(baseURL, url));
  if (!target) return false;
  const base = parseURL(baseURL);
  if (base && target.protocol === base.protocol && target.host === base.host) {
    return true;
  }
  return isTrusted(target.href, exchange);
}

/**
 * The default of `isTrusted`: trusts an absolute URL on the origin of the
 * fetcher's `baseURL` (when that is absolute), or on the page's own origin
 * (when there is a page: no `location` in Node or a worker). Any other
 * origin — a pagination link, a download URL, a callback — gets no
 * credentials.
 *
 * @example
 * ```typescript
 * // Also trust a second API.
 * new CoSecConfigurer({
 *   tokenRefresher,
 *   isTrusted: (url, exchange) =>
 *     sameOriginTrust(url, exchange) ||
 *     new URL(url).origin === 'https://files.example.com',
 * });
 * ```
 */
export const sameOriginTrust: RequestTrust = (url, exchange) => {
  const target = originOf(url);
  if (!target || target === 'null') return false;
  const baseURL = exchange.fetcher.urlBuilder.baseURL;
  return (
    (isAbsoluteURL(baseURL) && originOf(baseURL) === target) ||
    globalThis.location?.origin === target
  );
};

/**
 * Whether `exchange` may carry CoSec credentials: a relative request URL may
 * when it stays on the configured `baseURL`'s scheme and host, and an absolute
 * one — or a relative one the URL parser takes to another host — when
 * `isTrusted` accepts it; without `isTrusted`, {@link sameOriginTrust} does.
 */
export function isTrustedRequest(
  exchange: FetchExchange,
  isTrusted: RequestTrust = sameOriginTrust,
): boolean {
  // Trust is decided once per exchange, on its first pass through the request
  // chain: a retry (after a 401 refresh) sees the URL already resolved
  // against the baseURL, and must not lose the credentials the original
  // relative URL was trusted with.
  let decisions: Map<RequestTrust, boolean> | undefined =
    exchange.attributes.get(REQUEST_TRUST_ATTRIBUTE);
  const decided = decisions?.get(isTrusted);
  if (decided !== undefined) return decided;
  const url = exchange.request.url;
  const trusted = isAbsoluteURL(url)
    ? isTrusted(url, exchange)
    : isTrustedRelative(url, exchange, isTrusted);
  if (!decisions) {
    decisions = new Map();
    exchange.attributes.set(REQUEST_TRUST_ATTRIBUTE, decisions);
  }
  decisions.set(isTrusted, trusted);
  return trusted;
}
