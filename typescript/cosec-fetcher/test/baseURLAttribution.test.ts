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
  Fetcher,
  ResultExtractors,
  UrlTemplateStyle,
  type FetchExchange,
} from '@ahoo-wang/fetcher';
import { SerialTypedEventBus } from '@ahoo-wang/fetcher-eventbus';
import { InMemoryStorage } from '@ahoo-wang/fetcher-storage';
import { afterAll, describe, expect, it } from 'vitest';
import { ResourceAttributionRequestInterceptor, TokenStorage } from '../src';

const bus = new SerialTypedEventBus<never>('attribution-base-url');
const tokens = new TokenStorage({
  storage: new InMemoryStorage(),
  eventBus: bus,
});
const jwt = `e30.${btoa(JSON.stringify({ sub: 'demo-owner', tenantId: 'demo-tenant', exp: Date.now() / 1000 + 3600 }))}.demo`;
tokens.signIn({ accessToken: jwt, refreshToken: jwt });
afterAll(() => {
  tokens.destroy();
  bus.destroy();
});

async function send(
  baseURL: string,
  url: string,
  {
    request = {},
    style = UrlTemplateStyle.UriTemplate,
    names = {},
  }: {
    request?: Record<string, unknown>;
    style?: UrlTemplateStyle;
    names?: { tenantId?: string; ownerId?: string };
  } = {},
) {
  const sent: string[] = [];
  const client = new Fetcher({
    baseURL,
    urlTemplateStyle: style,
    fetch: async url => {
      sent.push(String(url));
      return new Response('{}');
    },
  });
  client.interceptors.request.use(
    new ResourceAttributionRequestInterceptor({
      tokenStorage: tokens,
      ...names,
    }),
  );
  const exchange: FetchExchange = await client.get(url, request, {
    resultExtractor: ResultExtractors.Exchange,
  });
  return { url: exchange.request.url, sent };
}

// #2038: placeholders in Fetcher.baseURL are filled like ones in the request URL.
describe('ResourceAttributionRequestInterceptor with a templated baseURL', () => {
  const host = 'https://example.test';
  const expected = `${host}/tenants/demo-tenant/owners/demo-owner/orders`;

  it.each([
    [
      'the whole template in baseURL',
      host + '/tenants/{tenantId}/owners/{ownerId}',
      '/orders',
      UrlTemplateStyle.UriTemplate,
    ],
    [
      'the template split between baseURL and request',
      host + '/tenants/{tenantId}',
      '/owners/{ownerId}/orders',
      UrlTemplateStyle.UriTemplate,
    ],
    [
      'the template in the request',
      host,
      '/tenants/{tenantId}/owners/{ownerId}/orders',
      UrlTemplateStyle.UriTemplate,
    ],
    [
      'an Express baseURL',
      host + '/tenants/:tenantId/owners/:ownerId',
      '/orders',
      UrlTemplateStyle.Express,
    ],
  ] as const)('fills %s', async (_, baseURL, url, style) => {
    const result = await send(baseURL, url, { style });
    expect(result.url).toBe(expected);
    expect(result.sent).toEqual([expected]);
  });

  it('fills custom placeholder names in baseURL', async () => {
    const result = await send(host + '/orgs/{org}/users/{user}', '/orders', {
      names: { tenantId: 'org', ownerId: 'user' },
    });
    expect(result.url).toBe(`${host}/orgs/demo-tenant/users/demo-owner/orders`);
  });

  it('keeps explicit path values', async () => {
    const result = await send(
      host + '/tenants/{tenantId}/owners/{ownerId}',
      '/orders',
      { request: { urlParams: { path: { tenantId: 't', ownerId: 'o' } } } },
    );
    expect(result.url).toBe(`${host}/tenants/t/owners/o/orders`);
  });

  it('ignores the baseURL an absolute request URL overrides', async () => {
    const result = await send(host + '/tenants/{tenantId}', host + '/orders');
    expect(result.url).toBe(`${host}/orders`);
  });
});
