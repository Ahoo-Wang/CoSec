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

package me.ahoo.cosec.social

import me.ahoo.cosec.annotations.CoSecSpi
import java.time.Duration

/**
 * The OAuth states CoSec issued, each with the SHA-256 of the binding of the browser it was issued to
 * ([SocialAuthorization]). A state is single use: [take] removes it atomically, so of two callbacks holding it at most
 * one finds it. A store that cannot be read throws, so a sign-in fails rather than proceed unchecked.
 *
 * Both calls suspend and never block the caller's thread, which may be an event loop: an implementation over a blocking
 * client moves its calls to a dispatcher of its own, as `RedisSocialStateStore` does.
 */
@CoSecSpi
interface SocialStateStore {
    /** Records [state] with [bindingHash] for [lifetime]. */
    suspend fun put(state: String, bindingHash: String, lifetime: Duration)

    /** Removes [state] and returns its binding hash, or null when it was never issued, has expired or was taken. */
    suspend fun take(state: String): String?
}
