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
  FetcherError,
  HttpMethod,
  type Fetcher,
  type FetchRequest,
  type FetchRequestInit,
  type RequestOptions,
} from '@ahoo-wang/fetcher';
import {
  isTrustedRequest,
  sameOriginTrust,
  type RequestTrust,
} from './requestTrust.js';

/**
 * The trust each configured Fetcher was given (`CoSecConfig.isTrusted`), so
 * that a request carrying the session's token pair is held to the same rule as
 * every credentialed request on that Fetcher. Not exported by the package.
 */
const CONFIGURED = new WeakMap<Fetcher, RequestTrust>();

/** Records that `fetcher` sends credentials only where `isTrusted` says. */
export function rememberTrust(
  fetcher: Fetcher,
  isTrusted: RequestTrust | undefined,
): void {
  CONFIGURED.set(fetcher, isTrusted ?? sameOriginTrust);
}

/**
 * Sends a request that carries the session's token pair (a refresh, a logout,
 * a tenant switch): a `POST` to `endpoint` with `init` and `options`, refused
 * before anything is sent when the Fetcher's trust does not accept it, and
 * never following a redirect.
 *
 * The trust is asked about the exchange the Fetcher builds for this very
 * request (its method, headers, body and attributes, the endpoint resolved
 * against the Fetcher's `baseURL`), judged as {@link isTrustedRequest} judges
 * any request, by the `isTrusted` of the configuration the Fetcher was given,
 * else {@link sameOriginTrust}. A relative endpoint that stays on the `baseURL`
 * is always trusted, so a refresher on a Fetcher of its own, whose `baseURL` is
 * the auth service, keeps working.
 *
 * `redirect: 'error'`: a `307` or `308` would send the body, the pair in it, to
 * wherever it points. CoSec's refresh, logout and switch routes never redirect,
 * so a redirect fails the request as one without an answer.
 */
export function sendSessionRequest<R>(
  fetcher: Fetcher,
  endpoint: string,
  init: Omit<FetchRequestInit, 'method' | 'redirect'>,
  options: RequestOptions,
): Promise<R> {
  const request: FetchRequest = {
    ...init,
    url: endpoint,
    method: HttpMethod.POST,
    redirect: 'error',
  };
  const exchange = fetcher.resolveExchange(request, options);
  if (!isTrustedRequest(exchange, CONFIGURED.get(fetcher) ?? sameOriginTrust)) {
    return Promise.reject(
      new FetcherError(
        "Refused to send the session's token pair to an endpoint the client does not trust (isTrusted).",
      ),
    );
  }
  return fetcher.request<R>(request, options);
}
