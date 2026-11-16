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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.api

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi

/** A statement as a decision reports it to a caller: by policy, index and the binding it applied through. */
@InternalCoSecApi
data class StatementView(
    val policy: String,
    val statement: Int,
    val name: String,
    val via: String,
    val document: String?
)

/**
 * A decision as the control plane answers it (RFC 0007 §10.3): its kind, and the statement that made it only when the
 * caller may read the document that statement came through. An indeterminate decision names its cause's type, never
 * its message.
 */
@InternalCoSecApi
data class DecisionView(val decision: String, val by: StatementView?, val cause: String?, val redacted: Boolean)

/** One trace entry, from a document the caller may read; [redacted] counts the entries left out. */
@InternalCoSecApi
data class TraceView(val entries: List<TraceEntryView>, val redacted: Int)

@InternalCoSecApi
data class TraceEntryView(val statement: StatementView, val effect: String, val outcome: String)
