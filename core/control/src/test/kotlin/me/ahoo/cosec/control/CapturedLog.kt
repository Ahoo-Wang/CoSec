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

package me.ahoo.cosec.control

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender

/**
 * What [this] captured so far, copied under the appender's own lock. Logback appends under it
 * (`AppenderBase.doAppend` is synchronized on the appender), and other threads keep logging while a test reads: the
 * test's own background work, or work an earlier test left running. Iterating `list` directly walks an `ArrayList`
 * another thread may be growing, which throws `ConcurrentModificationException`.
 */
internal fun ListAppender<ILoggingEvent>.captured(): List<ILoggingEvent> = synchronized(this) { list.toList() }

/** Forgets what [this] captured, under the same lock. */
internal fun ListAppender<ILoggingEvent>.forget(): Unit = synchronized(this) { list.clear() }
