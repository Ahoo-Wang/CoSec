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

package me.ahoo.cosec.conformance

/** The build guards of the cross-version formats (MIX-*, RFC 0014 T): the cross-version tests of test/compat. */
internal object CompatGuards {
    val BUILD: Set<String> = setOf(
        "compat:BaselineTest#a case before its release must fail, and one that already holds says so",
        "compat:CounterCompatTest#a rate limit taken half by each version allows no more than its quota",
        "compat:CounterCompatTest#login states are counted together, and one version takes what the other recorded",
        "compat:CounterCompatTest#sessions begun half by each version are held to the principal's limit",
        "compat:CredentialCompatTest#a revocation either version writes, the other reads",
        "compat:CredentialCompatTest#an API key either version saves, the other reads alike",
        "compat:DocumentCompatTest#a document either version compiles, the other compiles too",
        "compat:DocumentCompatTest#a pattern the current version reserves is refused by it, though the previous one took it as a literal",
        "compat:IdentityHeaderCompatTest#a header written without memberships is read without them, never as empty ones",
        "compat:IdentityHeaderCompatTest#a later member crit names is refused as an outage by a reader that does not know it",
        "compat:IdentityHeaderCompatTest#a member a later version adds is ignored by every reader, which reads the header it knows",
        "compat:IdentityHeaderCompatTest#each version reads what the other writes as the current reader does",
        "compat:RecordCompatTest#a later member without crit is dropped by a rewrite, which leaves the same record",
        "compat:RecordCompatTest#a record whose crit names a member a version does not know is refused by it",
        "compat:RecordCompatTest#each version reads and writes back the record the other wrote, unchanged",
        "compat:SessionRecordCompatTest#a later member without crit is dropped by a rewrite, which leaves the same session",
        "compat:SessionRecordCompatTest#a record whose crit names a member a version does not know is refused by it, and never rewritten",
        "compat:SessionRecordCompatTest#a rewrite by either version keeps every member, and the other reads it",
        "compat:SessionRecordCompatTest#each version reads the session the other wrote",
        "compat:SideIsolationTest#neither version is visible to the test's own class loader",
        "compat:SideIsolationTest#the previous side runs the published release and the current side this build",
        "compat:TokenCompatTest#a refresh token is refused as an access token by every version",
        "compat:TokenCompatTest#each version verifies what the other issues as the current verifier does",
    )
}
