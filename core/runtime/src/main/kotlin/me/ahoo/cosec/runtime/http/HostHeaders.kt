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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.runtime.http

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.condition.HttpNames

/**
 * [Headers.view]: the host's headers, read on each lookup until [freeze] copies them. The copy is made while the request
 * is in progress, by its own thread before it hands a shadow replay on, or by a check under the lock the request's end
 * takes (`RequestSecurity.admit`, `PIP-32`); [copy] is volatile, since a check may run on another thread. Two copies made
 * at once hold the same values.
 */
internal class HostHeaders(
    private val lookup: (String) -> List<String>?,
    private val names: () -> Iterable<String>,
) : Headers {
    @Volatile
    private var copy: Headers? = null

    override fun values(name: String): List<String> {
        copy?.let { return it.values(name) }
        return lookup(name).orEmpty()
    }

    /** Copies every header now, once per name as the documents fold it: a host's names may differ only by case. */
    fun freeze() {
        if (copy != null) return
        val values = HashMap<String, List<String>>()
        for (name in names()) {
            val key = HttpNames.fold(name)
            if (key !in values) values[key] = lookup(name).orEmpty().toList()
        }
        copy = Headers.of(values)
    }
}
