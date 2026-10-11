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

package me.ahoo.cosec.spring.boot.starter.authorization.cache

/**
 * Redis failures on policy and permission caches must fail closed.
 *
 * These caches have no `CacheSource`: Redis is the store of record. With CoCache's default degraded mode, a
 * failed read becomes a miss, which is then cached as a negative entry. For the global policy index that
 * means "no global policies" — explicit DENY rules are silently skipped (fail-open) for up to the missing TTL.
 * Strict failure propagates the error instead, and the security pipeline turns it into a denied (500) response.
 *
 * The revoked-token cache intentionally keeps CoCache's degraded mode: tokens are still signature-verified
 * and expire on their own, and failing closed there would reject every authenticated request during a
 * Redis outage.
 */
internal const val STRICT_CACHE_FAILURE = true
