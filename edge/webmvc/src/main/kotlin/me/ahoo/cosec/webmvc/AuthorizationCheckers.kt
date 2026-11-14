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

@file:JvmName("AuthorizationCheckers")
@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.webmvc

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.runtime.AuthorizationChecker
import reactor.core.scheduler.Schedulers

/**
 * [AuthorizationChecker.check] for servlet code (RFC 0006 §7.6): blocks the calling request thread. Refuses to run on
 * a non-blocking thread, such as a Netty event loop: use `checkMono` from `cosec-webflux` there.
 */
fun AuthorizationChecker.checkBlocking(security: SecurityContext, action: String, resource: String): Boolean {
    check(!Schedulers.isInNonBlockingThread()) {
        "checkBlocking would block the non-blocking thread [${Thread.currentThread().name}]; use checkMono."
    }
    return runBlocking { check(security, action, resource) }
}
