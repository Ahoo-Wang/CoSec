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

package me.ahoo.cosec.webflux

import me.ahoo.cosec.api.context.request.RequestIdCapable
import me.ahoo.cosid.jvm.UuidGenerator
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders

class HttpHeadersGuardTest {
    @Test
    fun trySet() {
        val httpHeaders = HttpHeaders()
        val value = UuidGenerator.INSTANCE.generateAsString()
        httpHeaders.trySet(RequestIdCapable.REQUEST_ID_KEY, value).assert().isTrue()
        httpHeaders.getFirst(RequestIdCapable.REQUEST_ID_KEY).assert().isEqualTo(value)
    }

    @Test
    fun trySetIfReadOnly() {
        val httpHeaders = HttpHeaders()
        val value = UuidGenerator.INSTANCE.generateAsString()
        val readOnlyHttpHeaders = HttpHeaders.readOnlyHttpHeaders(httpHeaders)
        readOnlyHttpHeaders.trySet(RequestIdCapable.REQUEST_ID_KEY, value).assert().isFalse()
        readOnlyHttpHeaders.getFirst(RequestIdCapable.REQUEST_ID_KEY).assert().isNull()
    }
}
