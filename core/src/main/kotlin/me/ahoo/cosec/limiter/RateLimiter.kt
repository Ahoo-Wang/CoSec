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

package me.ahoo.cosec.limiter

import reactor.core.publisher.Mono

/**
 * Counts permits per bucket. The backend of [RateLimitingAuthorization].
 *
 * Implementations must not block the subscribing thread, which is a Netty event loop under WebFlux and
 * Gateway: run blocking I/O (such as a Redis round trip) on `Schedulers.boundedElastic()`.
 * A failure is signalled as an error; [RateLimitRule.strictFailure] decides what it means.
 */
fun interface RateLimiter {
    /**
     * Acquires one permit of [rateLimit] from the bucket [bucket] of rule [ruleId].
     */
    fun tryAcquire(ruleId: String, bucket: String, rateLimit: RateLimit): Mono<RateLimitResult>
}
