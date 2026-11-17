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

import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  ResourceAttributionRequestInterceptor,
  RESOURCE_ATTRIBUTION_REQUEST_INTERCEPTOR_NAME,
  RESOURCE_ATTRIBUTION_REQUEST_INTERCEPTOR_ORDER,
  type ResourceAttributionOptions,
} from '../src';
import type { FetchExchange, UrlParams } from '@ahoo-wang/fetcher';
import type { TokenStorage } from '../src';
import type { JwtCompositeToken } from '../src';

describe('ResourceAttributionRequestInterceptor', () => {
  let mockTokenStorage: TokenStorage;
  let resourceAttributionOptions: ResourceAttributionOptions;
  let interceptor: ResourceAttributionRequestInterceptor;
  let mockExchange: FetchExchange;

  beforeEach(() => {
    // Create mock TokenStorage
    mockTokenStorage = {
      get: vi.fn(),
    } as unknown as TokenStorage;

    resourceAttributionOptions = {
      tenantId: 'tenantId',
      ownerId: 'ownerId',
      tokenStorage: mockTokenStorage,
    };

    interceptor = new ResourceAttributionRequestInterceptor(
      resourceAttributionOptions,
    );

    // Create mock exchange
    mockExchange = {
      request: {
        url: '/api/{tenantId}/resources/{ownerId}',
      },
      fetcher: {
        urlBuilder: {
          baseURL: '',
          urlTemplateResolver: {
            extractPathParams: vi.fn().mockReturnValue(['tenantId', 'ownerId']),
          },
        },
      },
      ensureRequestUrlParams: vi.fn().mockReturnValue({
        path: {},
      }),
    } as unknown as FetchExchange;
  });

  it('should have correct name and order', () => {
    expect(interceptor.name).toBe(
      RESOURCE_ATTRIBUTION_REQUEST_INTERCEPTOR_NAME,
    );
    expect(interceptor.order).toBe(
      RESOURCE_ATTRIBUTION_REQUEST_INTERCEPTOR_ORDER,
    );
  });

  it('should not modify request when no token exists', () => {
    vi.mocked(mockTokenStorage.get).mockReturnValue(null);

    interceptor.intercept(mockExchange);

    expect(mockTokenStorage.get).toHaveBeenCalled();
    expect(
      mockExchange.fetcher.urlBuilder.urlTemplateResolver.extractPathParams,
    ).not.toHaveBeenCalled();
  });

  it('should not modify request when token has no payload', () => {
    const mockToken = {
      access: {
        payload: null,
      },
    } as unknown as JwtCompositeToken;

    vi.mocked(mockTokenStorage.get).mockReturnValue(mockToken);

    interceptor.intercept(mockExchange);

    expect(mockTokenStorage.get).toHaveBeenCalled();
    expect(
      mockExchange.fetcher.urlBuilder.urlTemplateResolver.extractPathParams,
    ).not.toHaveBeenCalled();
  });

  it('should not modify request when payload has no tenantId and sub', () => {
    const mockToken = {
      access: {
        payload: {},
      },
    } as unknown as JwtCompositeToken;

    vi.mocked(mockTokenStorage.get).mockReturnValue(mockToken);

    interceptor.intercept(mockExchange);

    expect(mockTokenStorage.get).toHaveBeenCalled();
    expect(
      mockExchange.fetcher.urlBuilder.urlTemplateResolver.extractPathParams,
    ).not.toHaveBeenCalled();
  });

  it('should add tenantId and ownerId to path parameters', () => {
    const mockToken = {
      access: {
        payload: {
          tenantId: 'test-tenant',
          sub: 'test-owner',
        },
      },
    } as unknown as JwtCompositeToken;

    vi.mocked(mockTokenStorage.get).mockReturnValue(mockToken);

    const pathParams = {};
    vi.mocked(mockExchange.ensureRequestUrlParams).mockReturnValue({
      path: pathParams,
    } as Required<UrlParams>);

    interceptor.intercept(mockExchange);

    expect(mockTokenStorage.get).toHaveBeenCalled();
    expect(
      mockExchange.fetcher.urlBuilder.urlTemplateResolver.extractPathParams,
    ).toHaveBeenCalledWith(mockExchange.request.url);
    expect(pathParams).toEqual({
      tenantId: 'test-tenant',
      ownerId: 'test-owner',
    });
  });

  it('should only add tenantId when ownerId is not in path params', () => {
    const mockToken = {
      access: {
        payload: {
          tenantId: 'test-tenant',
          sub: 'test-owner',
        },
      },
    } as unknown as JwtCompositeToken;

    vi.mocked(mockTokenStorage.get).mockReturnValue(mockToken);
    vi.mocked(
      mockExchange.fetcher.urlBuilder.urlTemplateResolver.extractPathParams,
    ).mockReturnValue(['tenantId']);

    const pathParams = {};
    vi.mocked(mockExchange.ensureRequestUrlParams).mockReturnValue({
      path: pathParams,
    } as Required<UrlParams>);

    interceptor.intercept(mockExchange);

    expect(pathParams).toEqual({
      tenantId: 'test-tenant',
    });
  });

  it('should only add ownerId when tenantId is not in path params', () => {
    const mockToken = {
      access: {
        payload: {
          tenantId: 'test-tenant',
          sub: 'test-owner',
        },
      },
    } as unknown as JwtCompositeToken;

    vi.mocked(mockTokenStorage.get).mockReturnValue(mockToken);
    vi.mocked(
      mockExchange.fetcher.urlBuilder.urlTemplateResolver.extractPathParams,
    ).mockReturnValue(['ownerId']);

    const pathParams = {};
    vi.mocked(mockExchange.ensureRequestUrlParams).mockReturnValue({
      path: pathParams,
    } as Required<UrlParams>);

    interceptor.intercept(mockExchange);

    expect(pathParams).toEqual({
      ownerId: 'test-owner',
    });
  });

  it('should not overwrite existing path parameters', () => {
    const mockToken = {
      access: {
        payload: {
          tenantId: 'test-tenant',
          sub: 'test-owner',
        },
      },
    } as unknown as JwtCompositeToken;

    vi.mocked(mockTokenStorage.get).mockReturnValue(mockToken);

    const pathParams: Record<string, any> = {
      tenantId: 'existing-tenant',
    };
    vi.mocked(mockExchange.ensureRequestUrlParams).mockReturnValue({
      path: pathParams,
    } as Required<UrlParams>);

    interceptor.intercept(mockExchange);

    expect(pathParams).toEqual({
      tenantId: 'existing-tenant',
      ownerId: 'test-owner',
    });
  });

  it('should not add tenantId when it is not present in token', () => {
    const mockToken = {
      access: {
        payload: {
          sub: 'test-owner',
        },
      },
    } as unknown as JwtCompositeToken;

    vi.mocked(mockTokenStorage.get).mockReturnValue(mockToken);

    const pathParams = {};
    vi.mocked(mockExchange.ensureRequestUrlParams).mockReturnValue({
      path: pathParams,
    } as Required<UrlParams>);

    interceptor.intercept(mockExchange);

    expect(pathParams).toEqual({
      ownerId: 'test-owner',
    });
  });

  it('should not add ownerId when it is not present in token', () => {
    const mockToken = {
      access: {
        payload: {
          tenantId: 'test-tenant',
        },
      },
    } as unknown as JwtCompositeToken;

    vi.mocked(mockTokenStorage.get).mockReturnValue(mockToken);

    const pathParams = {};
    vi.mocked(mockExchange.ensureRequestUrlParams).mockReturnValue({
      path: pathParams,
    } as Required<UrlParams>);

    interceptor.intercept(mockExchange);

    expect(pathParams).toEqual({
      tenantId: 'test-tenant',
    });
  });
});
