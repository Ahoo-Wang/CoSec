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

import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import type { Fetcher } from '@ahoo-wang/fetcher';
import { FetchExchange } from '@ahoo-wang/fetcher';
import {
  ForbiddenErrorInterceptor,
  FORBIDDEN_ERROR_INTERCEPTOR_NAME,
  FORBIDDEN_ERROR_INTERCEPTOR_ORDER,
} from '../src';
import { ResponseCodes } from '../src';

describe('ForbiddenErrorInterceptor', () => {
  const mockFetcher = {} as Fetcher;
  const mockRequest = { url: '/test', method: 'GET' };

  let onForbiddenMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    onForbiddenMock = vi.fn();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  describe('constructor and properties', () => {
    it('should create interceptor with correct name and order', () => {
      const interceptor = new ForbiddenErrorInterceptor({
        onForbidden: onForbiddenMock,
      });

      expect(interceptor.name).toBe(FORBIDDEN_ERROR_INTERCEPTOR_NAME);
      expect(interceptor.order).toBe(FORBIDDEN_ERROR_INTERCEPTOR_ORDER);
    });
  });

  describe('intercept method - core functionality', () => {
    it('should call onForbidden when response status is 403', async () => {
      const interceptor = new ForbiddenErrorInterceptor({
        onForbidden: onForbiddenMock,
      });

      const exchange = new FetchExchange({
        fetcher: mockFetcher,
        request: mockRequest,
        response: new Response('Forbidden', {
          status: ResponseCodes.FORBIDDEN,
        }),
      });

      await interceptor.intercept(exchange);

      expect(onForbiddenMock).toHaveBeenCalledTimes(1);
      expect(onForbiddenMock).toHaveBeenCalledWith(exchange);
    });

    it('should call onForbidden with correct exchange object', async () => {
      const interceptor = new ForbiddenErrorInterceptor({
        onForbidden: onForbiddenMock,
      });

      const customRequest = {
        url: '/api/admin/users',
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: 'Bearer token',
        },
        body: JSON.stringify({ name: 'test' }),
      };

      const response = new Response('Forbidden: Insufficient permissions', {
        status: ResponseCodes.FORBIDDEN,
        statusText: 'Forbidden',
        headers: {
          'Content-Type': 'application/json',
          'X-Error-Code': 'INSUFFICIENT_PERMISSIONS',
        },
      });

      const exchange = new FetchExchange({
        fetcher: mockFetcher,
        request: customRequest,
        response,
      });

      await interceptor.intercept(exchange);

      expect(onForbiddenMock).toHaveBeenCalledWith(exchange);
      const calledExchange = onForbiddenMock.mock.calls[0][0];
      expect(calledExchange.request).toBe(customRequest);
      expect(calledExchange.response).toBe(response);
      expect(calledExchange.fetcher).toBe(mockFetcher);
    });

    it('should handle async onForbidden callback correctly', async () => {
      let callbackExecuted = false;
      const asyncOnForbidden = vi.fn().mockImplementation(async () => {
        await new Promise(resolve => setTimeout(resolve, 10));
        callbackExecuted = true;
      });

      const interceptor = new ForbiddenErrorInterceptor({
        onForbidden: asyncOnForbidden,
      });

      const exchange = new FetchExchange({
        fetcher: mockFetcher,
        request: mockRequest,
        response: new Response('Forbidden', { status: 403 }),
      });

      await interceptor.intercept(exchange);

      expect(asyncOnForbidden).toHaveBeenCalledTimes(1);
      expect(callbackExecuted).toBe(true);
    });
  });

  describe('intercept method - non-triggering conditions', () => {
    it('should not call onForbidden when response status is not 403', async () => {
      const interceptor = new ForbiddenErrorInterceptor({
        onForbidden: onForbiddenMock,
      });

      const testCases = [
        { status: 200, statusText: 'OK' },
        { status: 201, statusText: 'Created' },
        { status: 400, statusText: 'Bad Request' },
        { status: 401, statusText: 'Unauthorized' },
        { status: 404, statusText: 'Not Found' },
        { status: 500, statusText: 'Internal Server Error' },
        { status: 502, statusText: 'Bad Gateway' },
      ];

      for (const testCase of testCases) {
        const exchange = new FetchExchange({
          fetcher: mockFetcher,
          request: mockRequest,
          response: new Response(testCase.statusText, {
            status: testCase.status,
          }),
        });

        await interceptor.intercept(exchange);
      }

      expect(onForbiddenMock).not.toHaveBeenCalled();
    });

    it('should not call onForbidden when response is null', async () => {
      const interceptor = new ForbiddenErrorInterceptor({
        onForbidden: onForbiddenMock,
      });

      const exchange = new FetchExchange({
        fetcher: mockFetcher,
        request: mockRequest,
        response: null as any,
      });

      await interceptor.intercept(exchange);

      expect(onForbiddenMock).not.toHaveBeenCalled();
    });

    it('should not call onForbidden when response is undefined', async () => {
      const interceptor = new ForbiddenErrorInterceptor({
        onForbidden: onForbiddenMock,
      });

      const exchange = new FetchExchange({
        fetcher: mockFetcher,
        request: mockRequest,
        response: undefined,
      });

      await interceptor.intercept(exchange);

      expect(onForbiddenMock).not.toHaveBeenCalled();
    });

    it('should not call onForbidden when exchange has no response property', async () => {
      const interceptor = new ForbiddenErrorInterceptor({
        onForbidden: onForbiddenMock,
      });

      const exchange = new FetchExchange({
        fetcher: mockFetcher,
        request: mockRequest,
        // response property is omitted
      });

      await interceptor.intercept(exchange);

      expect(onForbiddenMock).not.toHaveBeenCalled();
    });
  });

  describe('intercept method - real response scenarios', () => {
    it('should handle real Response object with 403 status', async () => {
      const interceptor = new ForbiddenErrorInterceptor({
        onForbidden: onForbiddenMock,
      });

      // Create a real Response object
      const response = new Response(
        JSON.stringify({ error: 'Forbidden', code: 'ACCESS_DENIED' }),
        {
          status: 403,
          statusText: 'Forbidden',
          headers: {
            'Content-Type': 'application/json',
            'X-Request-ID': 'req-12345',
          },
        },
      );

      const exchange = new FetchExchange({
        fetcher: mockFetcher,
        request: mockRequest,
        response,
      });

      await interceptor.intercept(exchange);

      expect(onForbiddenMock).toHaveBeenCalledTimes(1);
      expect(onForbiddenMock).toHaveBeenCalledWith(exchange);

      // Verify response properties are accessible
      const calledExchange = onForbiddenMock.mock.calls[0][0];
      expect(calledExchange.response?.status).toBe(403);
      expect(calledExchange.response?.statusText).toBe('Forbidden');
      expect(calledExchange.response?.headers.get('Content-Type')).toBe(
        'application/json',
      );
    });

    it('should handle response with custom status text', async () => {
      const interceptor = new ForbiddenErrorInterceptor({
        onForbidden: onForbiddenMock,
      });

      const response = new Response('Access Denied: Insufficient Privileges', {
        status: 403,
        statusText: 'Access Denied',
      });

      const exchange = new FetchExchange({
        fetcher: mockFetcher,
        request: mockRequest,
        response,
      });

      await interceptor.intercept(exchange);

      expect(onForbiddenMock).toHaveBeenCalledTimes(1);
      const calledExchange = onForbiddenMock.mock.calls[0][0];
      expect(calledExchange.response?.statusText).toBe('Access Denied');
    });

    it('should handle response with complex headers', async () => {
      const interceptor = new ForbiddenErrorInterceptor({
        onForbidden: onForbiddenMock,
      });

      const response = new Response('Forbidden', {
        status: 403,
        headers: {
          'WWW-Authenticate': 'Bearer realm="api", error="insufficient_scope"',
          'X-Rate-Limit-Remaining': '0',
          'X-Rate-Limit-Reset': '1640995200',
          'X-Error-Details': 'User lacks required role: admin',
        },
      });

      const exchange = new FetchExchange({
        fetcher: mockFetcher,
        request: mockRequest,
        response,
      });

      await interceptor.intercept(exchange);

      expect(onForbiddenMock).toHaveBeenCalledTimes(1);
      const calledExchange = onForbiddenMock.mock.calls[0][0];
      expect(calledExchange.response?.headers.get('X-Error-Details')).toBe(
        'User lacks required role: admin',
      );
    });
  });

  describe('intercept method - concurrent execution', () => {
    it('should handle concurrent 403 responses correctly', async () => {
      const interceptor = new ForbiddenErrorInterceptor({
        onForbidden: onForbiddenMock,
      });

      const exchanges = Array.from(
        { length: 10 },
        (_, i) =>
          new FetchExchange({
            fetcher: mockFetcher,
            request: { ...mockRequest, url: `/api/resource/${i}` },
            response: new Response(`Forbidden ${i}`, { status: 403 }),
          }),
      );

      // Execute all intercepts concurrently
      await Promise.all(
        exchanges.map(exchange => interceptor.intercept(exchange)),
      );

      expect(onForbiddenMock).toHaveBeenCalledTimes(10);
      // Verify all exchanges were passed to callback
      const calledExchanges = onForbiddenMock.mock.calls.map(call => call[0]);
      expect(calledExchanges).toHaveLength(10);
      exchanges.forEach(exchange => {
        expect(calledExchanges).toContain(exchange);
      });
    });

    it('should handle mixed status codes in concurrent requests', async () => {
      const interceptor = new ForbiddenErrorInterceptor({
        onForbidden: onForbiddenMock,
      });

      const exchanges = [
        // 403 responses (should trigger callback)
        new FetchExchange({
          fetcher: mockFetcher,
          request: mockRequest,
          response: new Response('Forbidden', { status: 403 }),
        }),
        new FetchExchange({
          fetcher: mockFetcher,
          request: mockRequest,
          response: new Response('Forbidden', { status: 403 }),
        }),
        // Non-403 responses (should not trigger callback)
        new FetchExchange({
          fetcher: mockFetcher,
          request: mockRequest,
          response: new Response('OK', { status: 200 }),
        }),
        new FetchExchange({
          fetcher: mockFetcher,
          request: mockRequest,
          response: new Response('Not Found', { status: 404 }),
        }),
        new FetchExchange({
          fetcher: mockFetcher,
          request: mockRequest,
          response: new Response('Server Error', { status: 500 }),
        }),
      ];

      await Promise.all(
        exchanges.map(exchange => interceptor.intercept(exchange)),
      );

      // Should only be called for 403 responses
      expect(onForbiddenMock).toHaveBeenCalledTimes(2);
    });
  });

  describe('intercept method - callback validation', () => {
    it('should validate callback is called with correct parameter types', async () => {
      let receivedExchange: FetchExchange | null = null;

      const validatingCallback = vi
        .fn()
        .mockImplementation(async (exchange: FetchExchange) => {
          receivedExchange = exchange;
        });

      const interceptor = new ForbiddenErrorInterceptor({
        onForbidden: validatingCallback,
      });

      const exchange = new FetchExchange({
        fetcher: mockFetcher,
        request: mockRequest,
        response: new Response('Forbidden', { status: 403 }),
      });

      await interceptor.intercept(exchange);

      expect(validatingCallback).toHaveBeenCalledTimes(1);
      expect(validatingCallback).toHaveBeenCalledWith(exchange);
      expect(receivedExchange).toBeInstanceOf(FetchExchange);
      expect(receivedExchange).toBe(exchange);
    });

    it('should handle callback that expects specific exchange properties', async () => {
      const propertyCheckingCallback = vi
        .fn()
        .mockImplementation(async (exchange: FetchExchange) => {
          // Verify all expected properties are present
          expect(exchange).toHaveProperty('fetcher');
          expect(exchange).toHaveProperty('request');
          expect(exchange).toHaveProperty('response');

          // Verify specific values
          expect(exchange.fetcher).toBe(mockFetcher);
          expect(exchange.request).toBe(mockRequest);
          expect(exchange.response?.status).toBe(403);
        });

      const interceptor = new ForbiddenErrorInterceptor({
        onForbidden: propertyCheckingCallback,
      });

      const exchange = new FetchExchange({
        fetcher: mockFetcher,
        request: mockRequest,
        response: new Response('Forbidden', { status: 403 }),
      });

      await interceptor.intercept(exchange);

      expect(propertyCheckingCallback).toHaveBeenCalledTimes(1);
    });
  });

  describe('integration scenarios', () => {
    it('should work correctly in a complete request flow simulation', async () => {
      const interceptor = new ForbiddenErrorInterceptor({
        onForbidden: onForbiddenMock,
      });

      // Simulate a complete request that results in 403
      const request = {
        url: '/api/admin/delete-user',
        method: 'DELETE',
        headers: {
          Authorization: 'Bearer expired-token',
          'Content-Type': 'application/json',
        },
      };

      const response = new Response(
        JSON.stringify({
          error: 'Forbidden',
          message: 'Admin privileges required',
          code: 'INSUFFICIENT_PRIVILEGES',
          requiredRole: 'admin',
          userRole: 'user',
        }),
        {
          status: 403,
          statusText: 'Forbidden',
          headers: {
            'Content-Type': 'application/json',
            'X-Error-Code': 'INSUFFICIENT_PRIVILEGES',
          },
        },
      );

      const exchange = new FetchExchange({
        fetcher: mockFetcher,
        request,
        response,
      });

      await interceptor.intercept(exchange);

      expect(onForbiddenMock).toHaveBeenCalledTimes(1);
      const calledExchange = onForbiddenMock.mock.calls[0][0];

      // Verify all exchange properties
      expect(calledExchange.request.url).toBe('/api/admin/delete-user');
      expect(calledExchange.request.method).toBe('DELETE');
      expect(calledExchange.response?.status).toBe(403);
      expect(calledExchange.response?.headers.get('X-Error-Code')).toBe(
        'INSUFFICIENT_PRIVILEGES',
      );
    });

    it('should handle interceptor chain scenarios', async () => {
      // Simulate being part of an interceptor chain
      const interceptor1 = new ForbiddenErrorInterceptor({
        onForbidden: onForbiddenMock,
      });
      const interceptor2Callback = vi.fn();
      const interceptor2 = new ForbiddenErrorInterceptor({
        onForbidden: interceptor2Callback,
      });

      const exchange = new FetchExchange({
        fetcher: mockFetcher,
        request: mockRequest,
        response: new Response('Forbidden', { status: 403 }),
      });

      // Both interceptors should trigger (simulating chain behavior)
      await Promise.all([
        interceptor1.intercept(exchange),
        interceptor2.intercept(exchange),
      ]);

      expect(onForbiddenMock).toHaveBeenCalledTimes(1);
      expect(onForbiddenMock).toHaveBeenCalledWith(exchange);
      expect(interceptor2Callback).toHaveBeenCalledTimes(1);
      expect(interceptor2Callback).toHaveBeenCalledWith(exchange);
    });
  });
});
