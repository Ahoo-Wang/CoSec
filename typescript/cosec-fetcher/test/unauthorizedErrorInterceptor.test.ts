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

import { describe, expect, it, vi } from 'vitest';
import type { Fetcher } from '@ahoo-wang/fetcher';
import { FetchExchange } from '@ahoo-wang/fetcher';
import {
  UnauthorizedErrorInterceptor,
  UNAUTHORIZED_ERROR_INTERCEPTOR_NAME,
  UNAUTHORIZED_ERROR_INTERCEPTOR_ORDER,
} from '../src/unauthorizedErrorInterceptor';
import { RefreshTokenError } from '../src/jwtTokenManager';
import { ResponseCodes } from '../src/types';

describe('UnauthorizedErrorInterceptor', () => {
  const mockFetcher = {} as Fetcher;
  const mockRequest = { url: '/test' };

  describe('constructor and properties', () => {
    it('should create interceptor with correct name and order', () => {
      const onUnauthorized = vi.fn();
      const interceptor = new UnauthorizedErrorInterceptor({ onUnauthorized });

      expect(interceptor.name).toBe(UNAUTHORIZED_ERROR_INTERCEPTOR_NAME);
      expect(interceptor.order).toBe(UNAUTHORIZED_ERROR_INTERCEPTOR_ORDER);
    });

    it('should store options correctly', () => {
      const onUnauthorized = vi.fn();
      const interceptor = new UnauthorizedErrorInterceptor({ onUnauthorized });

      // Access private options via intercept call
      const exchange = new FetchExchange({
        fetcher: mockFetcher,
        request: mockRequest,
        response: new Response('test', { status: 401 }),
      });

      interceptor.intercept(exchange);
      expect(onUnauthorized).toHaveBeenCalledWith(exchange);
    });
  });

  describe('intercept method', () => {
    it('should call onUnauthorized when response status is 401', () => {
      const onUnauthorized = vi.fn();
      const interceptor = new UnauthorizedErrorInterceptor({ onUnauthorized });

      const exchange = new FetchExchange({
        fetcher: mockFetcher,
        request: mockRequest,
        response: new Response('Unauthorized', {
          status: ResponseCodes.UNAUTHORIZED,
        }),
      });

      interceptor.intercept(exchange);

      expect(onUnauthorized).toHaveBeenCalledTimes(1);
      expect(onUnauthorized).toHaveBeenCalledWith(exchange);
    });

    it('should call onUnauthorized when exchange.error is RefreshTokenError', () => {
      const onUnauthorized = vi.fn();
      const interceptor = new UnauthorizedErrorInterceptor({ onUnauthorized });

      const refreshError = new RefreshTokenError('Token refresh failed');
      const exchange = new FetchExchange({
        fetcher: mockFetcher,
        request: mockRequest,
        error: refreshError,
      });

      interceptor.intercept(exchange);

      expect(onUnauthorized).toHaveBeenCalledTimes(1);
      expect(onUnauthorized).toHaveBeenCalledWith(exchange);
    });

    it('should call onUnauthorized when both 401 response and RefreshTokenError occur', () => {
      const onUnauthorized = vi.fn();
      const interceptor = new UnauthorizedErrorInterceptor({ onUnauthorized });

      const refreshError = new RefreshTokenError('Token refresh failed');
      const exchange = new FetchExchange({
        fetcher: mockFetcher,
        request: mockRequest,
        response: new Response('Unauthorized', {
          status: ResponseCodes.UNAUTHORIZED,
        }),
        error: refreshError,
      });

      interceptor.intercept(exchange);

      expect(onUnauthorized).toHaveBeenCalledTimes(1);
      expect(onUnauthorized).toHaveBeenCalledWith(exchange);
    });

    it('should not call onUnauthorized when response status is not 401', () => {
      const onUnauthorized = vi.fn();
      const interceptor = new UnauthorizedErrorInterceptor({ onUnauthorized });

      const exchange = new FetchExchange({
        fetcher: mockFetcher,
        request: mockRequest,
        response: new Response('OK', { status: 200 }),
      });

      interceptor.intercept(exchange);

      expect(onUnauthorized).not.toHaveBeenCalled();
    });

    it('should not call onUnauthorized when error is not RefreshTokenError', () => {
      const onUnauthorized = vi.fn();
      const interceptor = new UnauthorizedErrorInterceptor({ onUnauthorized });

      const exchange = new FetchExchange({
        fetcher: mockFetcher,
        request: mockRequest,
        error: new Error('Some other error'),
      });

      interceptor.intercept(exchange);

      expect(onUnauthorized).not.toHaveBeenCalled();
    });

    it('should not call onUnauthorized when neither condition is met', () => {
      const onUnauthorized = vi.fn();
      const interceptor = new UnauthorizedErrorInterceptor({ onUnauthorized });

      const exchange = new FetchExchange({
        fetcher: mockFetcher,
        request: mockRequest,
        response: new Response('OK', { status: 200 }),
      });

      interceptor.intercept(exchange);

      expect(onUnauthorized).not.toHaveBeenCalled();
    });

    it('should not call onUnauthorized when response is null', () => {
      const onUnauthorized = vi.fn();
      const interceptor = new UnauthorizedErrorInterceptor({ onUnauthorized });

      const exchange = new FetchExchange({
        fetcher: mockFetcher,
        request: mockRequest,
        response: undefined,
      });

      interceptor.intercept(exchange);

      expect(onUnauthorized).not.toHaveBeenCalled();
    });

    it('should not call onUnauthorized when error is undefined', () => {
      const onUnauthorized = vi.fn();
      const interceptor = new UnauthorizedErrorInterceptor({ onUnauthorized });

      const exchange = new FetchExchange({
        fetcher: mockFetcher,
        request: mockRequest,
        error: undefined,
      });

      interceptor.intercept(exchange);

      expect(onUnauthorized).not.toHaveBeenCalled();
    });

    it('should handle different error types correctly', () => {
      const onUnauthorized = vi.fn();
      const interceptor = new UnauthorizedErrorInterceptor({ onUnauthorized });

      // Test with TypeError
      const typeErrorExchange = new FetchExchange({
        fetcher: mockFetcher,
        request: mockRequest,
        error: new TypeError('Network error'),
      });

      interceptor.intercept(typeErrorExchange);
      expect(onUnauthorized).not.toHaveBeenCalled();

      // Test with custom error that is not RefreshTokenError
      class CustomError extends Error {
        constructor(message: string) {
          super(message);
          this.name = 'CustomError';
        }
      }

      const customErrorExchange = new FetchExchange({
        fetcher: mockFetcher,
        request: mockRequest,
        error: new CustomError('Custom error'),
      });

      interceptor.intercept(customErrorExchange);
      expect(onUnauthorized).not.toHaveBeenCalled();
    });

    it('should handle edge case with 401 response and non-RefreshTokenError', () => {
      const onUnauthorized = vi.fn();
      const interceptor = new UnauthorizedErrorInterceptor({ onUnauthorized });

      const exchange = new FetchExchange({
        fetcher: mockFetcher,
        request: mockRequest,
        response: new Response('Unauthorized', {
          status: ResponseCodes.UNAUTHORIZED,
        }),
        error: new Error('Some other error'),
      });

      interceptor.intercept(exchange);

      expect(onUnauthorized).toHaveBeenCalledTimes(1);
      expect(onUnauthorized).toHaveBeenCalledWith(exchange);
    });

    it('should handle edge case with non-401 response and RefreshTokenError', () => {
      const onUnauthorized = vi.fn();
      const interceptor = new UnauthorizedErrorInterceptor({ onUnauthorized });

      const refreshError = new RefreshTokenError('Token refresh failed');
      const exchange = new FetchExchange({
        fetcher: mockFetcher,
        request: mockRequest,
        response: new Response('Server Error', { status: 500 }),
        error: refreshError,
      });

      interceptor.intercept(exchange);

      expect(onUnauthorized).toHaveBeenCalledTimes(1);
      expect(onUnauthorized).toHaveBeenCalledWith(exchange);
    });

    it('should pass the correct exchange object to onUnauthorized callback', () => {
      const onUnauthorized = vi.fn();
      const interceptor = new UnauthorizedErrorInterceptor({ onUnauthorized });

      const customRequest = {
        url: '/api/protected',
        method: 'GET',
        headers: { Authorization: 'Bearer token' },
      };

      const response = new Response('Unauthorized', {
        status: ResponseCodes.UNAUTHORIZED,
        statusText: 'Unauthorized',
        headers: { 'WWW-Authenticate': 'Bearer' },
      });

      const exchange = new FetchExchange({
        fetcher: mockFetcher,
        request: customRequest,
        response,
      });

      interceptor.intercept(exchange);

      expect(onUnauthorized).toHaveBeenCalledWith(exchange);
      const calledExchange = onUnauthorized.mock.calls[0][0];
      expect(calledExchange.request).toBe(customRequest);
      expect(calledExchange.response).toBe(response);
      expect(calledExchange.fetcher).toBe(mockFetcher);
    });
  });
});
