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

import type { CompositeToken } from './tokenRefresher.js';
import type { TokenStorage } from './tokenStorage.js';

/**
 * The time of the first refresh attempt of a pair whose outcome is unknown.
 *
 * CoSec 8.0 answers a refresh presented again within `rotation-leeway` of the
 * move, and revokes the session after it (IDN-16). The server moves at some
 * time at or after the moment the request was sent, so a retry sent before
 * `at + leeway` is safe whatever became of the first attempt; one sent after
 * it may find the move recorded. The mark is kept next to the stored pair, so
 * a reload, or another tab on the same storage, keeps the notion. It never
 * slides: a retry whose outcome is unknown too leaves it where it was.
 */
interface AttemptMark {
  fingerprint: string;
  at: number;
}

const marks = new WeakMap<object, AttemptMark>();

/** A non-cryptographic fingerprint of a pair: the mark must not copy the tokens. */
export function pairFingerprint(token: CompositeToken): string {
  let hash = 0x811c9dc5;
  const text = `${token.accessToken}\n${token.refreshToken}`;
  for (let index = 0; index < text.length; index++) {
    hash = Math.imul(hash ^ text.charCodeAt(index), 0x01000193) >>> 0;
  }
  return `${text.length}:${hash.toString(16)}`;
}

function markKey(storage: TokenStorage): string {
  return `${storage.key}:refresh-attempt`;
}

function readMark(storage: TokenStorage): AttemptMark | undefined {
  try {
    const stored = storage.storage.getItem(markKey(storage));
    if (stored) {
      const mark: unknown = JSON.parse(stored);
      if (
        typeof mark === 'object' &&
        mark !== null &&
        typeof (mark as AttemptMark).fingerprint === 'string' &&
        typeof (mark as AttemptMark).at === 'number'
      ) {
        return mark as AttemptMark;
      }
    }
  } catch {
    // Unreadable: fall back to what this context remembers.
  }
  return marks.get(storage);
}

/** When the first unresolved attempt on this pair was sent, if there is one. */
export function attemptStartedAt(
  storage: TokenStorage,
  token: CompositeToken,
): number | undefined {
  const mark = readMark(storage);
  return mark?.fingerprint === pairFingerprint(token) ? mark.at : undefined;
}

/**
 * Records that a refresh of this pair is about to be sent, unless one is
 * already unresolved (its time is kept).
 *
 * @returns the time of the first unresolved attempt, and whether this call
 * made it
 */
export function beginAttempt(
  storage: TokenStorage,
  token: CompositeToken,
  now: number,
): { at: number; fresh: boolean } {
  const known = attemptStartedAt(storage, token);
  if (known !== undefined) return { at: known, fresh: false };
  const mark: AttemptMark = { fingerprint: pairFingerprint(token), at: now };
  marks.set(storage, mark);
  try {
    storage.storage.setItem(markKey(storage), JSON.stringify(mark));
  } catch {
    // A storage that cannot be written (quota, private mode) keeps it in memory.
  }
  return { at: now, fresh: true };
}

/** Forgets the mark: the pair was replaced, the session ended, or nothing moved. */
export function clearAttempt(storage: TokenStorage): void {
  marks.delete(storage);
  try {
    storage.storage.removeItem(markKey(storage));
  } catch {
    // Nothing to clean in a storage that cannot be written.
  }
}
