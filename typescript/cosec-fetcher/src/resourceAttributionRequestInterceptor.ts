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

import type { FetchExchange, RequestInterceptor } from '@ahoo-wang/fetcher';
import {
  combineURLs,
  DEFAULT_INTERCEPTOR_ORDER_STEP,
  isAbsoluteURL,
  URL_RESOLVE_INTERCEPTOR_ORDER,
} from '@ahoo-wang/fetcher';
import type { TokenStorage } from './tokenStorage.js';

const TENANT_ID_PATH_KEY = 'tenantId';
const OWNER_ID_PATH_KEY = 'ownerId';

/**
 * Configuration options for resource attribution
 */
export interface ResourceAttributionOptions {
  /**
   * The path parameter key used for tenant ID in URL templates
   */
  tenantId?: string;
  /**
   * The path parameter key used for owner ID in URL templates
   */
  ownerId?: string;
  /**
   * Storage mechanism for retrieving current authentication tokens
   */
  tokenStorage: TokenStorage;
}

/**
 * Name identifier for the ResourceAttributionRequestInterceptor
 */
export const RESOURCE_ATTRIBUTION_REQUEST_INTERCEPTOR_NAME =
  'ResourceAttributionRequestInterceptor';
/**
 * Order priority for the ResourceAttributionRequestInterceptor: one step
 * before URL resolution, so the tenant and owner path parameters it adds are
 * in place when the URL template is resolved, and after interceptors with the
 * default order (0).
 */
export const RESOURCE_ATTRIBUTION_REQUEST_INTERCEPTOR_ORDER =
  URL_RESOLVE_INTERCEPTOR_ORDER - DEFAULT_INTERCEPTOR_ORDER_STEP;

/**
 * Request interceptor that automatically adds tenant and owner ID path parameters to requests
 * based on the current authentication token. This is useful for multi-tenant applications where
 * requests need to include tenant-specific information in the URL path.
 */
export class ResourceAttributionRequestInterceptor implements RequestInterceptor {
  readonly name = RESOURCE_ATTRIBUTION_REQUEST_INTERCEPTOR_NAME;
  readonly order = RESOURCE_ATTRIBUTION_REQUEST_INTERCEPTOR_ORDER;
  /** @internal */
  readonly tenantIdPathKey: string;
  /** @internal */
  readonly ownerIdPathKey: string;
  /** @internal */
  readonly tokenStorage: TokenStorage;

  /**
   * Creates a new ResourceAttributionRequestInterceptor
   * @param options - Configuration options for resource attribution including tenantId, ownerId and tokenStorage
   */
  constructor({
    tenantId = TENANT_ID_PATH_KEY,
    ownerId = OWNER_ID_PATH_KEY,
    tokenStorage,
  }: ResourceAttributionOptions) {
    this.tenantIdPathKey = tenantId;
    this.ownerIdPathKey = ownerId;
    this.tokenStorage = tokenStorage;
  }

  /**
   * Intercepts outgoing requests and automatically adds tenant and owner ID path parameters
   * if they are defined in the URL template but not provided in the request.
   * @param exchange - The fetch exchange containing the request information
   */
  intercept(exchange: FetchExchange): void {
    const currentToken = this.tokenStorage.get();
    if (!currentToken) {
      return;
    }
    const principal = currentToken.access.payload;
    if (!principal) {
      return;
    }
    if (!principal.tenantId && !principal.sub) {
      return;
    }

    // Extract path parameters from the URL template
    // The URL is resolved from the baseURL and the request URL together, so
    // placeholders are looked for in both — unless an absolute request URL
    // replaces the baseURL.
    const { urlBuilder } = exchange.fetcher;
    const url = exchange.request.url;
    const template = isAbsoluteURL(url)
      ? url
      : combineURLs(urlBuilder.baseURL, url);
    const extractedPathParams =
      urlBuilder.urlTemplateResolver.extractPathParams(template);
    const tenantIdPathKey = this.tenantIdPathKey;
    const requestPathParams = exchange.ensureRequestUrlParams().path;
    const tenantId = principal.tenantId;

    // Add tenant ID to path parameters if it's part of the URL template and not already provided
    if (
      tenantId &&
      extractedPathParams.includes(tenantIdPathKey) &&
      !requestPathParams[tenantIdPathKey]
    ) {
      requestPathParams[tenantIdPathKey] = tenantId;
    }

    const ownerIdPathKey = this.ownerIdPathKey;
    const ownerId = principal.sub;

    // Add owner ID to path parameters if it's part of the URL template and not already provided
    if (
      ownerId &&
      extractedPathParams.includes(ownerIdPathKey) &&
      !requestPathParams[ownerIdPathKey]
    ) {
      requestPathParams[ownerIdPathKey] = ownerId;
    }
  }
}
