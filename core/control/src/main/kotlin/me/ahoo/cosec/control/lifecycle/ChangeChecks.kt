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

@file:OptIn(me.ahoo.cosec.annotations.InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.Finding
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.store.AppList
import me.ahoo.cosec.control.store.Head
import me.ahoo.cosec.control.store.Versioned
import me.ahoo.cosec.document.DocumentCompileException
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.document.PolicyDocumentCompiler
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.document.RoleExpansion
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.condition.AttributeCatalog
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/** RFC 0007 §8: a break-glass binding never lives longer than this, whatever is configured. */
@InternalCoSecApi
val MAX_BREAK_GLASS: java.time.Duration = java.time.Duration.ofHours(24)

/**
 * The checks of a candidate (RFC 0007 §6.2, §6.3), in order: V1 format, V2 size and names, then the strict compile
 * with the data plane's compiler ([PolicyDocumentCompiler]), against the current heads it combines with.
 *
 * - V1: the candidate must read back with the document codec; what fails is a 400 at its JSON path.
 * - V2: at most [ChangeOptions.maxDocumentBytes] encoded; the ids a change puts must not use the reserved prefixes
 *   `cosec:`, `g:`, `r:` and `role:` (`perm:` is the app document's, which the compiler checks), unless it migrates or
 *   bootstraps, and must be one segment of the entry's resource (RFC 0007 §7.1): no `/`, but for a permission policy's
 *   `perm:<app>/<id>`; a tenant policy must not reuse a platform policy's id (RFC 0007 §3.1 N2).
 * - V3, compile: every entry must compile, with the limits and external attributes of the platform document
 *   (RFC 0007 §3.1 N3): a `Broken` entry is a 422, never published. An app or tenant candidate's bindings must
 *   resolve, in the candidate, the platform, or for a tenant binding of one app, that app's document (N1), and never to
 *   a platform boundary (§7.6, §9.1). A platform candidate must not remove a policy other documents refer to, nor turn
 *   one into a boundary or a boundary into a policy (§5.8).
 * - V4 has nothing to check: catalogs declare permission ids, not services, verbs or types (RFC 0004 §4.9). V5's
 *   break-glass rules and the `nomatch` confirmation (ATR-6) are the compiler's; a candidate's boundary must be one the
 *   platform defines (below); protected entries and grants are [ChangeAuthority]'s.
 *
 * Changing the platform's limits or attributes does not recompile the other documents; it is a warning. So are the two
 * things about a tenant's role that deny nothing but surprise (RFC 0012 §7.5).
 */
@InternalCoSecApi
class ChangeChecks(
    private val options: ChangeOptions = ChangeOptions(),
    /** The host's `AttributeProvider` catalog: a candidate compiles against it as an instance would ([PolicyDocumentCompiler.forPlatform]). */
    private val attributes: AttributeCatalog = AttributeCatalog.NONE,
    /** The headers the host reads credentials from besides the fixed ones, which no reference may read (`CND-5`). */
    private val credentialHeaders: Set<String> = emptySet(),
) {
    /**
     * Reads what the checks and the grant analysis combine a candidate with: current heads, the back-references of the
     * platform's and apps' policies, and the app list ([me.ahoo.cosec.control.store.ControlStore.apps], RC1-4).
     */
    @InternalCoSecApi
    interface Context {
        suspend fun head(key: SnapshotKey): Head?

        suspend fun referrers(target: SnapshotKey, policy: PolicyId): Set<SnapshotKey>

        suspend fun apps(): Versioned<AppList>?
    }

    suspend fun check(
        key: SnapshotKey,
        candidate: PolicyDocument,
        kind: ProvenanceKind,
        operations: List<Operation>,
        base: PolicyDocument?,
        context: Context,
    ): CheckResult {
        val findings = ArrayList<Finding>()
        val bytes = PolicyDocumentCodec.encode(candidate)
        try {
            PolicyDocumentCodec.decode(candidate.kind, candidate.owner, bytes)
        } catch (error: SnapshotFormatException) {
            findings += Finding("V1", "$", error.message.orEmpty(), BAD_REQUEST)
            return CheckResult(findings, emptyMap())
        }
        if (bytes.size > options.maxDocumentBytes) {
            findings += Finding("V2", "$", "the document is ${bytes.size} bytes, more than ${options.maxDocumentBytes}", BAD_REQUEST)
        }
        EntryChecks.reservedNames(operations, kind, findings)
        EntryChecks.entryIds(operations, findings)
        EntryChecks.appIds(candidate, findings)
        val checkedWith = LinkedHashMap<SnapshotKey, SnapshotDigest?>()
        val platform = if (key == SnapshotKey.Platform) {
            candidate
        } else {
            val head = context.head(SnapshotKey.Platform)
            checkedWith[SnapshotKey.Platform] = head?.digest
            head?.document
        }
        if (candidate.kind == DocumentKind.TENANT && platform != null) {
            EntryChecks.sharesPlatformIds(candidate, platform, findings)
        }
        try {
            PolicyDocumentCompiler.forPlatform(platform, attributes, options.principalBindingLimit, credentialHeaders)
                .compile(candidate)
        } catch (error: DocumentCompileException) {
            error.errors.forEach { findings += Finding("COMPILE", it.path, it.message, UNPROCESSABLE) }
        }
        when (candidate.kind) {
            DocumentKind.PLATFORM -> {
                PlatformChecks.removedReferences(base, candidate, context, findings)
                PlatformChecks.sharedIds(base, candidate, context, findings)
                SectionChecks.sectionWarnings(base, candidate, findings)
            }

            else -> BindingChecks.unresolved(candidate, platform, context, checkedWith, findings)
        }
        return CheckResult(findings, checkedWith)
    }

    internal companion object {
        private const val BAD_REQUEST = 400
        private const val UNPROCESSABLE = 422
        val RESERVED = listOf("cosec:", "g:", "r:", RoleExpansion.BINDING_PREFIX)
    }
}
