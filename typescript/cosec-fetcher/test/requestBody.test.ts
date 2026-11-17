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

import { describe, expect, it } from 'vitest';
import { isOneShotBody } from '../src/requestBody';

describe('isOneShotBody', () => {
  it.each([
    ['a ReadableStream', new ReadableStream()],
    // Fails instanceof like a stream from another realm (an iframe, a vm
    // context) but still has the ReadableStream internal slots.
    [
      'a ReadableStream that fails instanceof',
      Object.setPrototypeOf(new ReadableStream(), null),
    ],
    [
      'an async iterable',
      (async function* () {
        yield 'chunk';
      })(),
    ],
  ])('is true for %s', (_, body) => {
    expect(isOneShotBody(body)).toBe(true);
  });

  it.each([
    ['undefined', undefined],
    ['null', null],
    ['a string', 'body'],
    ['a Blob', new Blob(['body'])],
    ['FormData', new FormData()],
    ['URLSearchParams', new URLSearchParams('a=1')],
    ['an ArrayBuffer', new ArrayBuffer(1)],
    ['a typed array', new Uint8Array(1)],
    ['a plain object', { getReader: () => undefined }],
  ])('is false for %s', (_, body) => {
    expect(isOneShotBody(body)).toBe(false);
  });
});
