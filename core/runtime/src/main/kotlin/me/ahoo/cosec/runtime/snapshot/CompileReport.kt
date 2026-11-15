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

package me.ahoo.cosec.runtime.snapshot

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotCompiler
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * One slot's account of a document that does not compile (drill entry 26): one ERROR line when an entry that does not
 * compile is installed, naming the revision and what it compiled against, and one INFO line when one that compiles is
 * installed after it, so the log shows the fault begin and end. It remembers one failure, the last it logged: a retry
 * or a revalidation of the same revision, digest and inputs logs nothing again; another revision, or the same against
 * other inputs, logs once. Lines are written by [offRequestThread], never on a request's thread.
 */
internal class CompileReport(
    private val key: SnapshotKey,
    private val compiler: SnapshotCompiler<*>,
    private val offRequestThread: (() -> Unit) -> Unit,
) {
    private data class Failure(val revision: Long, val digest: SnapshotDigest?, val against: String?)

    /** What the document compiled against, as the last compile saw it; read by the install that follows. */
    @Volatile
    private var against: String? = null

    private var logged: Failure? = null

    /** The message of a failure to compile revision [revision], noting what it compiled against for the log line. */
    fun failedToCompile(revision: Long): String {
        val inputs = (compiler as? CompileInputs)?.against(key)
        against = inputs
        return "revision $revision does not compile" + inputs?.let { " against $it" }.orEmpty()
    }

    /** After [entry] is installed: the failure once, or the recovery once. */
    fun installed(entry: SnapshotEntry<*>) {
        val state = entry.state
        when {
            entry.origin == EntryOrigin.UNCOMPILABLE && state is DocumentState.Failed -> onFailed(entry, state.error)
            entry.origin == EntryOrigin.DOCUMENT -> onCompiled(entry)
            else -> Unit
        }
    }

    private fun onFailed(entry: SnapshotEntry<*>, error: Throwable) {
        val failure = Failure(entry.revision, entry.digest, against)
        val first = synchronized(this) { (failure != logged).also { if (it) logged = failure } }
        if (first) {
            offRequestThread { failed(entry.revision, failure.against, error) }
        }
    }

    private fun onCompiled(entry: SnapshotEntry<*>) {
        val recovered = synchronized(this) { (logged != null).also { logged = null } }
        if (recovered) {
            offRequestThread {
                log.info { "Snapshot document [$key] revision ${entry.revision} compiles; it is decided with again." }
            }
        }
    }

    private fun failed(revision: Long, against: String?, error: Throwable) {
        log.error(error.cause ?: error) {
            "Snapshot document [$key] revision $revision does not compile" + against?.let { " against $it" }.orEmpty() +
                "; every request that needs it fails closed (503) until a revision that compiles is published."
        }
    }

    private companion object {
        /** The source's own logger, so that whoever watches the snapshot source sees these lines too. */
        val log = KotlinLogging.logger(SnapshotSource::class.java.name)
    }
}
