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

package me.ahoo.cosec.control.lifecycle

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.allow
import me.ahoo.cosec.control.store.Head
import me.ahoo.cosec.control.store.ReferrerEntry
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * R2C-34: what a change is checked, analysed and approved on is the document the data plane will read, the candidate
 * encoded and decoded again, never the in-memory one the operations built.
 */
class ChangeChecksDecodedCandidateTest {
    private val none = object : CurrentDocuments {
        override suspend fun head(key: SnapshotKey): Head? = null
        override suspend fun referrers(target: SnapshotKey, policy: PolicyId) = emptySet<ReferrerEntry>()
        override suspend fun apps() = null
    }

    @Test
    fun `the check answers the decoded candidate, which encodes as the built one does`() = runBlocking<Unit> {
        val built: PolicyDocument = StoreFixtures.candidate(SnapshotKey.Platform, listOf(allow("p")))
        val result = ChangeChecks().check(SnapshotKey.Platform, built, ProvenanceKind.PUBLISH, emptyList(), null, none)

        result.clean.assert().describedAs(result.findings.toString()).isTrue()
        val decoded = checkNotNull(result.candidate)
        decoded.assert().isNotSameAs(built)
        PolicyDocumentCodec.encode(decoded).assert().isEqualTo(PolicyDocumentCodec.encode(built))
        CandidateDigest.of(decoded).assert().isEqualTo(CandidateDigest.of(built))
    }
}
