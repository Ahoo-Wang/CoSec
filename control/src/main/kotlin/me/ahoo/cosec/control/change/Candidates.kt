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

package me.ahoo.cosec.control.change

import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.condition.ConditionLimits
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.document.PolicyDefinition
import me.ahoo.cosec.snapshot.document.PolicyDocument
import me.ahoo.cosec.snapshot.document.PolicyDocumentCodec
import tools.jackson.databind.JsonNode
import java.security.MessageDigest
import java.time.Instant

/**
 * The digest an operation's `ifMatch` names: SHA-256 of the entry as the document encodes it, in lowercase hex. Two
 * entries with the same digest are written the same way, so they mean the same.
 */
object EntryDigest {
    fun of(policy: PolicyDefinition): String = sha256(PolicyDocumentCodec.policyTree(policy))

    fun of(binding: Binding, kind: DocumentKind, owner: String): String =
        sha256(PolicyDocumentCodec.bindingTree(binding, kind, owner))

    internal fun sha256(tree: JsonNode): String = hex(CoSecJsonSerializer.writeValueAsBytes(tree))

    internal fun hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/**
 * The digest of a candidate (RFC 0007 §7.5): SHA-256 of its body encoded with a fixed header, so it depends only on
 * the policies, bindings and sections a change would publish. Approvals and shadow reports name it.
 */
object CandidateDigest {
    private val EPOCH: Instant = Instant.EPOCH

    fun of(candidate: PolicyDocument): String = EntryDigest.hex(
        PolicyDocumentCodec.encode(
            PolicyDocument(
                kind = candidate.kind,
                owner = candidate.owner,
                revision = 1,
                publishedAt = EPOCH,
                policies = candidate.policies,
                bindings = candidate.bindings,
                catalog = candidate.catalog,
                limits = candidate.limits,
                attributes = candidate.attributes,
            ),
        ),
    )
}

/** The candidate a change's operations make of its base, or the findings that keep them from applying. */
class Applied(val candidate: PolicyDocument?, val findings: List<Finding>)

/**
 * Applies entry-level operations to a base document (RFC 0007 §10.1), in order. An operation whose `ifMatch` is not
 * the entry's digest is a 412; removing what is not there and a section the document's kind does not have are 422s.
 * The candidate keeps the base's order of entries: a replaced entry stays where it was, a new one goes last.
 */
object Operations {
    fun apply(key: SnapshotKey, base: PolicyDocument?, operations: List<Operation>): Applied {
        val draft = Draft(key, base)
        operations.forEachIndexed { i, operation -> draft.apply("operations[$i]", operation) }
        return if (draft.findings.isEmpty()) Applied(draft.candidate(), emptyList()) else Applied(null, draft.findings)
    }

    /** A candidate being made of its base, one operation at a time. */
    private class Draft(key: SnapshotKey, private val base: PolicyDocument?) {
        private val kind: DocumentKind
        private val owner: String
        private val policies = LinkedHashMap<String, PolicyDefinition>()
        private val bindings = LinkedHashMap<String, Binding>()
        private var catalog = base?.catalog
        private var limits = base?.limits
        private var attributes = base?.attributes
        val findings = ArrayList<Finding>()

        init {
            val (kind, owner) = Revisions.ownerOf(key)
            this.kind = kind
            this.owner = owner
            base?.policies?.forEach { policies[it.id.value] = it }
            base?.bindings?.forEach { bindings[it.id.value] = it }
        }

        fun apply(path: String, operation: Operation) {
            when (operation) {
                is Operation.PutPolicy -> {
                    val id = operation.policy.id.value
                    if (ifMatch(path, operation.ifMatch, policies[id]?.let(EntryDigest::of))) {
                        policies[id] = operation.policy
                    }
                }

                is Operation.RemovePolicy -> remove(
                    path,
                    "policy",
                    policies,
                    operation.id.value,
                    operation.ifMatch,
                    EntryDigest::of
                )
                is Operation.PutBinding -> {
                    val id = operation.binding.id.value
                    if (ifMatch(path, operation.ifMatch, bindings[id]?.let(::digest))) {
                        bindings[id] = operation.binding
                    }
                }

                is Operation.RemoveBinding -> remove(
                    path,
                    "binding",
                    bindings,
                    operation.id.value,
                    operation.ifMatch,
                    ::digest
                )
                is Operation.PutCatalog -> section(path, DocumentKind.APP, "only an app document has a catalog") {
                    catalog = operation.catalog
                }

                is Operation.SetLimits -> section(
                    path,
                    DocumentKind.PLATFORM,
                    "only the platform document has limits"
                ) {
                    limits = operation.limits
                }

                is Operation.SetAttributes -> section(
                    path,
                    DocumentKind.PLATFORM,
                    "only the platform document declares attributes"
                ) {
                    attributes = operation.attributes
                }
            }
        }

        private fun digest(binding: Binding): String = EntryDigest.of(binding, kind, owner)

        private fun <T> remove(
            path: String,
            entry: String,
            entries: MutableMap<String, T>,
            id: String,
            ifMatch: String?,
            digest: (T) -> String,
        ) {
            val current = entries[id]
            if (current == null) {
                findings += Finding("OPERATION", path, "$entry [$id] is not in the document", UNPROCESSABLE)
            } else if (ifMatch(path, ifMatch, digest(current))) {
                entries.remove(id)
            }
        }

        private inline fun section(path: String, only: DocumentKind, message: String, set: () -> Unit) {
            if (kind == only) set() else findings += Finding("OPERATION", path, message, UNPROCESSABLE)
        }

        /** Whether [ifMatch] holds for an entry whose digest is [current] (`null`: absent); records a 412 otherwise. */
        private fun ifMatch(path: String, ifMatch: String?, current: String?): Boolean {
            if (ifMatch == null || ifMatch == current) {
                return true
            }
            findings += Finding("IF_MATCH", "$path.ifMatch", "the entry is ${current ?: "absent"}, not $ifMatch", PRECONDITION_FAILED)
            return false
        }

        fun candidate(): PolicyDocument = PolicyDocument(
            kind = kind,
            owner = owner,
            revision = (base?.revision ?: 0) + 1,
            publishedAt = base?.publishedAt ?: Instant.EPOCH,
            policies = policies.values.toList(),
            bindings = bindings.values.toList(),
            catalog = catalog,
            limits = limits,
            attributes = attributes,
        )
    }

    /**
     * The operations that make [base] into [desired] (RFC 0007 §6.7): puts for what is new or different, each guarded
     * by the digest of the entry it replaces; with [ApplyMode.REPLACE] also removes for what [desired] leaves out and
     * the sections it does not set.
     */
    fun diff(key: SnapshotKey, base: PolicyDocument?, desired: PolicyDocument, mode: ApplyMode): List<Operation> {
        val (kind, owner) = Revisions.ownerOf(key)
        val operations = ArrayList<Operation>()
        val policies = base?.policies?.associateBy { it.id.value }.orEmpty()
        for (policy in desired.policies) {
            val current = policies[policy.id.value]?.let(EntryDigest::of)
            if (current != EntryDigest.of(policy)) operations += Operation.PutPolicy(policy, current)
        }
        val bindings = base?.bindings?.associateBy { it.id.value }.orEmpty()
        for (binding in desired.bindings) {
            val current = bindings[binding.id.value]?.let { EntryDigest.of(it, kind, owner) }
            if (current != EntryDigest.of(binding, kind, owner)) operations += Operation.PutBinding(binding, current)
        }
        if (mode == ApplyMode.REPLACE) {
            val keepPolicies = desired.policies.map { it.id.value }.toSet()
            base?.policies?.filter { it.id.value !in keepPolicies }
                ?.forEach { operations += Operation.RemovePolicy(it.id, EntryDigest.of(it)) }
            val keepBindings = desired.bindings.map { it.id.value }.toSet()
            base?.bindings?.filter { it.id.value !in keepBindings }
                ?.forEach { operations += Operation.RemoveBinding(it.id, EntryDigest.of(it, kind, owner)) }
        }
        operations += sections(kind, base, desired, mode == ApplyMode.REPLACE)
        return operations
    }

    /** The section operations: a section [desired] sets, or with [all] any it does not match, is set. */
    private fun sections(
        kind: DocumentKind,
        base: PolicyDocument?,
        desired: PolicyDocument,
        all: Boolean
    ): List<Operation> {
        val operations = ArrayList<Operation>()
        val catalog = all || desired.catalog != null
        if (kind == DocumentKind.APP && catalog && !sameCatalog(base, desired)) {
            operations += Operation.PutCatalog(desired.catalog)
        }
        if (kind != DocumentKind.PLATFORM) {
            return operations
        }
        if ((all || desired.limits != null) && !sameLimits(base, desired)) {
            operations += Operation.SetLimits(desired.limits)
        }
        if ((all || desired.attributes != null) && !sameAttributes(base, desired)) {
            operations += Operation.SetAttributes(desired.attributes)
        }
        return operations
    }

    private fun sameCatalog(base: PolicyDocument?, desired: PolicyDocument): Boolean =
        base?.catalog?.let(PolicyDocumentCodec::catalogTree) == desired.catalog?.let(PolicyDocumentCodec::catalogTree)

    /** Attributes compare by path and declared type ([me.ahoo.cosec.model.condition.Attribute] equality has no type). */
    private fun sameAttributes(base: PolicyDocument?, desired: PolicyDocument): Boolean =
        base?.attributes?.map { it.path to it.type } == desired.attributes?.map { it.path to it.type }

    private fun sameLimits(base: PolicyDocument?, desired: PolicyDocument): Boolean {
        val a = base?.limits
        val b = desired.limits
        if (a == null || b == null) return a == null && b == null
        return limitsOf(a) == limitsOf(b)
    }

    private fun limitsOf(limits: ConditionLimits): List<Int> = with(limits) {
        listOf(maxNodes, maxDepth, maxInValues, maxCidrBlocks, maxLiteralBytes, maxCost)
    }

    const val UNPROCESSABLE: Int = 422
    const val PRECONDITION_FAILED: Int = 412
}

/** How [Changes.apply] treats what the desired document leaves out (RFC 0007 §6.7). */
enum class ApplyMode {
    /** Adds and changes only: entries and sections the desired document does not mention stay. */
    MERGE,

    /** The document becomes exactly the desired one. */
    REPLACE,
}
