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
 * Whether a request body can be read only once: a `ReadableStream` (from any
 * realm) or another async iterable, which the first fetch consumes. Strings,
 * `Blob`, `FormData`, `URLSearchParams` and buffers can be sent again.
 */
export function isOneShotBody(body: unknown): boolean {
  if (body === null || typeof body !== 'object') return false;
  if (typeof ReadableStream === 'function') {
    if (body instanceof ReadableStream) return true;
    // A stream from another realm fails instanceof; the `locked` getter of
    // this realm's ReadableStream throws for anything that is not a stream.
    const locked = Object.getOwnPropertyDescriptor(
      ReadableStream.prototype,
      'locked',
    )?.get;
    try {
      if (locked) {
        locked.call(body);
        return true;
      }
    } catch {
      // Not a ReadableStream.
    }
  }
  return (
    typeof (body as { [Symbol.asyncIterator]?: unknown })[
      Symbol.asyncIterator
    ] === 'function'
  );
}
