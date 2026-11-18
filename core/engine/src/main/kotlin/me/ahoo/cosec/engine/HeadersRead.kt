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

package me.ahoo.cosec.engine

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.condition.Attribute

/**
 * The request headers attributes read, in lower case: `http.header.<name>` reads `<name>`, `http.origin` and the
 * attributes derived from it read `origin`, and `http.referer` and its derived ones `referer` (`PIP-12`). The names
 * come from attributes that resolved, so a credential header (`CND-5`) is never one of them.
 */
internal object HeadersRead {
    private const val ORIGIN = "origin"
    private const val REFERER = "referer"

    fun of(attributes: Iterable<Attribute>): Array<String> {
        val names = attributes.mapNotNullTo(LinkedHashSet(), ::headerOf)
        return if (names.isEmpty()) NO_HEADERS else names.toTypedArray()
    }

    /** The distinct names of [first] and [rest], in order; [NO_HEADERS] for none. */
    fun merge(first: Array<String>, rest: Iterable<Array<String>>): Array<String> {
        val names = LinkedHashSet<String>()
        names.addAll(first)
        rest.forEach { names.addAll(it) }
        return if (names.isEmpty()) NO_HEADERS else names.toTypedArray()
    }

    fun headerOf(attribute: Attribute): String? = when (attribute.kind) {
        Attribute.Kind.HTTP_HEADER -> attribute.name
        Attribute.Kind.HTTP_ORIGIN, Attribute.Kind.HTTP_ORIGIN_HOST, Attribute.Kind.HTTP_ORIGIN_HOST_LABELS -> ORIGIN
        Attribute.Kind.HTTP_REFERER, Attribute.Kind.HTTP_REFERER_HOST, Attribute.Kind.HTTP_REFERER_HOST_LABELS -> REFERER
        else -> null
    }
}
