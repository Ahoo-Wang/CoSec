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

package me.ahoo.cosec.model.condition

/**
 * The cost bounds of a condition (RFC 0005 §3.5, Q9). The defaults are the decided values, to be calibrated
 * against the converter's measurements of 7.x policies. The platform document's `limits` section sets them for every
 * document (RFC 0007 §3.1 N3), so the control plane and every instance compile against the same bounds.
 */
class ConditionLimits(
    val maxNodes: Int = 64,
    val maxDepth: Int = 8,
    val maxInValues: Int = 1024,
    val maxCidrBlocks: Int = 256,
    /** In UTF-8 bytes. */
    val maxLiteralBytes: Int = 1024,
    /** The sum of the node weights ([CheckedCondition.Weights]). */
    val maxCost: Int = 256,
) {
    init {
        val limits = intArrayOf(maxNodes, maxDepth, maxInValues, maxCidrBlocks, maxLiteralBytes, maxCost)
        require(limits.all { it > 0 }) { "Condition limits must be positive." }
    }

    companion object {
        val DEFAULT: ConditionLimits = ConditionLimits()
    }
}

/** A reason a condition does not compile, at a JSON [path] relative to the condition, e.g. `$.and[1].eq[0]`. */
data class ConditionError(val path: String, val message: String)

sealed interface CheckResult {
    class Valid(val condition: CheckedCondition) : CheckResult

    /** At least one error. */
    class Invalid(val errors: List<ConditionError>) : CheckResult
}

/**
 * Type-checks a [Condition] and bounds its cost (RFC 0005 §3.2, §3.5). Errors name the JSON path; a condition with
 * any error does not compile.
 *
 * Checking never recurses deeper than [ConditionLimits.maxDepth] or looks at more than [ConditionLimits.maxNodes]
 * nodes, so a hostile document cannot exhaust the stack or the compile thread.
 */
class ConditionChecker(
    val limits: ConditionLimits = ConditionLimits.DEFAULT,
    /** Resolves `time` zones. Region zones need the host's resolver ([ZoneResolver.withRegions]). */
    val zones: ZoneResolver = ZoneResolver.FIXED,
    /** The external attributes the registered providers declare (RFC 0006 §5.4.1). */
    val catalog: AttributeCatalog = AttributeCatalog.NONE,
    /**
     * The headers this host reads credentials from besides [CREDENTIAL_HEADERS], such as an API key header renamed with
     * `cosec.pipeline.api-key-header` (RC1-21): refused the same way. Matched with the ASCII fold, as header names are.
     */
    credentialHeaders: Set<String> = emptySet(),
) {
    private val credentialHeaders: Set<String> =
        if (credentialHeaders.isEmpty()) CREDENTIAL_HEADERS else CREDENTIAL_HEADERS + credentialHeaders.map(HttpNames::fold)

    /**
     * The attribute [path] names, a declared external one included; null when there is none. Every attribute reference
     * of a document resolves here (conditions, rate limit keys), so this is where a credential header is refused: it
     * is null for one, whoever asks (`CND-5`, [isCredential]).
     */
    fun attribute(path: String): Attribute? {
        val attribute = Attribute.parse(path)
            ?: return if (Attribute.isExternalPath(path)) catalog.external(path) else null
        return attribute.takeUnless { isCredential(it) }
    }

    /** Whether [attribute] is a credential header of this host: [CREDENTIAL_HEADERS] or one it was given (`CND-5`). */
    fun isCredential(attribute: Attribute): Boolean =
        attribute.kind == Attribute.Kind.HTTP_HEADER && attribute.name in credentialHeaders

    /** Why [attribute] has no attribute for [path], as an error a caller reports. */
    fun unresolved(path: String): String {
        val parsed = Attribute.parse(path)
        val header = Attribute.Kind.HTTP_HEADER.path
        return when {
            parsed == null && path.startsWith(header) && path.length > header.length ->
                "`$path` is not a header name: a header name is an ASCII token (RFC 9110)"
            parsed != null && isCredential(parsed) ->
                "`$path` is a credential header, which no attribute reference may read"
            parsed == null && Attribute.isExternalPath(path) ->
                "external attribute `$path` is not declared by any registered AttributeProvider"
            else -> "unknown attribute `$path`"
        }
    }

    fun check(condition: Condition): CheckResult {
        val run = Run()
        val checked = run.check(condition, ROOT, 1)
        if (checked != null && checked.cost > limits.maxCost) {
            run.error(ROOT, "costs ${checked.cost}, more than ${limits.maxCost}")
        }
        return if (checked == null || run.errors.isNotEmpty()) {
            CheckResult.Invalid(run.errors)
        } else {
            CheckResult.Valid(checked)
        }
    }

    private inner class Run {
        val errors = ArrayList<ConditionError>()
        private var nodes = 0

        fun error(path: String, message: String) {
            errors += ConditionError(path, message)
        }

        fun check(condition: Condition, path: String, depth: Int): CheckedCondition? {
            if (nodes >= limits.maxNodes) {
                if (nodes == limits.maxNodes) {
                    nodes++
                    error(path, "has more than ${limits.maxNodes} nodes")
                }
                return null
            }
            nodes++
            if (depth > limits.maxDepth) {
                error(path, "is nested deeper than ${limits.maxDepth}")
                return null
            }
            return when (condition) {
                is Condition.Eq -> eq(condition, path)
                is Condition.In -> inValues(condition, path)
                is Condition.Contains -> contains(condition, path)
                is Condition.Prefix -> prefix(condition, path)
                is Condition.Cidr -> cidr(condition, path)
                is Condition.Time -> TimeChecker(path, limits, this::error).check(condition, zones)
                is Condition.Exists -> exists(condition, path)
                is Condition.And -> group(condition.conditions, "$path.and", depth, isAnd = true)
                is Condition.Or -> group(condition.conditions, "$path.or", depth, isAnd = false)
                is Condition.Not -> not(condition, path, depth)
            }
        }

        private fun exists(condition: Condition.Exists, path: String): CheckedCondition? {
            val attribute = attribute(condition.attribute, "$path.exists") ?: return null
            return CheckedCondition.Exists(attribute)
        }

        private fun not(condition: Condition.Not, path: String, depth: Int): CheckedCondition? {
            val operand = check(condition.condition, "$path.not", depth + 1) ?: return null
            return CheckedCondition.Not(operand)
        }

        private fun group(conditions: List<Condition>, path: String, depth: Int, isAnd: Boolean): CheckedCondition? {
            if (conditions.isEmpty()) {
                error(path, "needs at least one condition")
                return null
            }
            val checked = ArrayList<CheckedCondition>(conditions.size)
            for ((i, condition) in conditions.withIndex()) {
                check(condition, "$path[$i]", depth + 1)?.let { checked += it }
                if (nodes > limits.maxNodes) {
                    return null
                }
            }
            return when {
                checked.size != conditions.size -> null
                isAnd -> CheckedCondition.And(checked)
                else -> CheckedCondition.Or(checked)
            }
        }

        private fun eq(condition: Condition.Eq, path: String): CheckedCondition? {
            val attribute = attribute(condition.attribute, "$path.eq[0]") ?: return null
            if (condition.ignoreCase && !ignoreCase(attribute, path)) {
                return null
            }
            val valuePath = "$path.eq[1]"
            return when (val value = condition.value) {
                is Operand.Attr -> eqAttribute(attribute, value, valuePath, condition.ignoreCase)
                is Literal.Bool -> if (attribute.type == AttributeType.BOOL) {
                    CheckedCondition.BoolEq(attribute, value.value)
                } else {
                    mismatch(valuePath, "a Bool", attribute)
                }

                is Literal.Str -> eqString(attribute, value.value, valuePath, condition.ignoreCase)
            }
        }

        private fun eqAttribute(
            left: Attribute,
            value: Operand.Attr,
            path: String,
            ignoreCase: Boolean,
        ): CheckedCondition? {
            val right = attribute(value.path, "$path.attr") ?: return null
            if (right.type != left.type) {
                val rightType = name(right.type)
                error(path, "${right.path} ($rightType) does not compare with ${left.path} (${name(left.type)})")
                return null
            }
            if (left.type !in COMPARABLE) {
                return mismatch(path, "eq", left)
            }
            return CheckedCondition.AttributeEq(left, right, ignoreCase)
        }

        private fun eqString(
            attribute: Attribute,
            value: String,
            path: String,
            ignoreCase: Boolean,
        ): CheckedCondition? =
            when (attribute.type) {
                AttributeType.STRING -> string(value, path)?.let {
                    CheckedCondition.Eq(attribute, if (ignoreCase) Ascii.lowercase(it) else it, ignoreCase)
                }

                AttributeType.IP -> address(value, path)?.let { CheckedCondition.IpEq(attribute, it) }
                else -> mismatch(path, "a String", attribute)
            }

        private fun inValues(condition: Condition.In, path: String): CheckedCondition? {
            val attribute = attribute(condition.attribute, "$path.in[0]") ?: return null
            val valuesPath = "$path.in[1]"
            val values = condition.values
            if (values.isEmpty()) {
                error(valuesPath, "needs at least one value")
                return null
            }
            if (values.size > limits.maxInValues) {
                error(valuesPath, "has ${values.size} values, more than ${limits.maxInValues}")
                return null
            }
            return when (attribute.type) {
                AttributeType.STRING -> strings(values, valuesPath, attribute)
                    ?.let { CheckedCondition.In(attribute, it.toHashSet()) }

                AttributeType.IP -> inAddresses(attribute, values, valuesPath)
                AttributeType.BOOL -> inBools(attribute, values, valuesPath)
                else -> mismatch(path, "in", attribute)
            }
        }

        /** `in` of addresses is `cidr` of single-address blocks. */
        private fun inAddresses(attribute: Attribute, values: List<Literal>, path: String): CheckedCondition? {
            val texts = strings(values, path, attribute) ?: return null
            val blocks = texts.mapIndexed { i, text ->
                address(text, "$path[$i]")?.let { CidrBlock.of(it, if (it.v4) IPV4_BITS else IPV6_BITS) }
            }
            return if (null in blocks) null else CheckedCondition.Cidr(attribute, IpRanges(blocks.filterNotNull()))
        }

        /** Only true and false exist: `in` of one of them is `eq`, of both is `exists`. */
        private fun inBools(attribute: Attribute, values: List<Literal>, path: String): CheckedCondition? {
            val bools = values.mapIndexed { i, value ->
                (value as? Literal.Bool)?.value ?: mismatch(path + "[$i]", "a String", attribute)
            }
            return when {
                null in bools -> null
                bools.toSet().size == 2 -> CheckedCondition.Exists(attribute)
                else -> CheckedCondition.BoolEq(attribute, bools.first() == true)
            }
        }

        private fun contains(condition: Condition.Contains, path: String): CheckedCondition? {
            val attributePath = "$path.in[1]"
            val attribute = attribute(condition.attribute, attributePath) ?: return null
            if (attribute.type != AttributeType.STRING_SET) {
                val type = name(attribute.type)
                val message = "${attribute.path} is $type; `in` with the value first needs a Set<String> attribute"
                error(attributePath, message)
                return null
            }
            val value = condition.value as? Literal.Str ?: return mismatch("$path.in[0]", "a Bool", attribute)
            return string(value.value, "$path.in[0]")?.let { CheckedCondition.Contains(it, attribute) }
        }

        private fun prefix(condition: Condition.Prefix, path: String): CheckedCondition? {
            val attribute = attribute(condition.attribute, "$path.prefix[0]") ?: return null
            if (attribute.type != AttributeType.STRING && attribute.type != AttributeType.LABELS) {
                return mismatch(path, "prefix", attribute)
            }
            if (condition.ignoreCase && !ignoreCase(attribute, path)) {
                return null
            }
            val valuePath = "$path.prefix[1]"
            val literal = condition.value as? Literal.Str ?: return mismatch(valuePath, "a Bool", attribute)
            val value = string(literal.value, valuePath) ?: return null
            if (attribute.type == AttributeType.STRING) {
                val folded = if (condition.ignoreCase) Ascii.lowercase(value) else value
                return CheckedCondition.Prefix(attribute, folded, condition.ignoreCase)
            }
            if (value.split('.').any { it.isEmpty() }) {
                error(valuePath, "is not a list of labels such as `com.example`: labels must not be empty")
                return null
            }
            // Labels are built in lower case (host names are case-insensitive), so the literal is folded too.
            return CheckedCondition.LabelsPrefix(attribute, Ascii.lowercase(value))
        }

        private fun cidr(condition: Condition.Cidr, path: String): CheckedCondition? {
            val attribute = attribute(condition.attribute, "$path.cidr[0]") ?: return null
            if (attribute.type != AttributeType.IP) {
                return mismatch(path, "cidr", attribute)
            }
            val blocksPath = "$path.cidr[1]"
            val blocks = condition.blocks
            if (blocks.isEmpty()) {
                error(blocksPath, "needs at least one block")
                return null
            }
            if (blocks.size > limits.maxCidrBlocks) {
                error(blocksPath, "has ${blocks.size} blocks, more than ${limits.maxCidrBlocks}")
                return null
            }
            val texts = strings(blocks, blocksPath, attribute) ?: return null
            val parsed = texts.mapIndexed { i, text ->
                CidrBlock.parse(text) ?: null.also { error("$blocksPath[$i]", "is not a CIDR block: `$text`") }
            }
            return if (null in parsed) null else CheckedCondition.Cidr(attribute, IpRanges(parsed.filterNotNull()))
        }

        private fun ignoreCase(attribute: Attribute, path: String): Boolean {
            if (attribute.type == AttributeType.STRING) {
                return true
            }
            val type = name(attribute.type)
            error("$path.ignoreCase", "applies to String attributes only, not ${attribute.path} ($type)")
            return false
        }

        /**
         * Paths take names (`subject.attributes.<name>`), so they are bounded like literals. A credential header is
         * never readable ([CREDENTIAL_HEADERS]): [attribute] refuses it.
         */
        private fun attribute(path: String, at: String): Attribute? {
            string(path, at) ?: return null
            this@ConditionChecker.attribute(path)?.let { return it }
            error(at, unresolved(path))
            return null
        }

        private fun strings(values: List<Literal>, path: String, attribute: Attribute): List<String>? {
            val strings = values.mapIndexed { i, value ->
                val at = "$path[$i]"
                if (value is Literal.Str) string(value.value, at) else mismatch(at, "a Bool", attribute)
            }
            return if (null in strings) null else strings.filterNotNull()
        }

        private fun string(value: String, path: String): String? {
            val bytes = utf8Length(value)
            if (bytes > limits.maxLiteralBytes) {
                error(path, "is $bytes bytes long, more than ${limits.maxLiteralBytes}")
                return null
            }
            return value
        }

        private fun address(text: String, path: String): IpAddress? {
            val value = string(text, path) ?: return null
            return IpAddress.parse(value) ?: null.also { error(path, "is not an IP address: `$text`") }
        }

        /** Reports that a literal ("a Bool") or an operator ("eq") does not fit [attribute]'s type. */
        private fun <T> mismatch(path: String, what: String, attribute: Attribute): T? {
            val subject = "${attribute.path} (${name(attribute.type)})"
            val literal = what.startsWith("a ")
            error(path, if (literal) "$what does not compare with $subject" else "`$what` does not apply to $subject")
            return null
        }
    }

    companion object {
        /**
         * Headers that carry credentials (RFC 0005 §3.2, `CND-5`). A condition on one would let a document's author
         * learn another principal's token through decisions: a `prefix` DENY on `authorization`, and the statement the
         * audit names, tell one character at a time. `x-api-key` is the starter's default API key header; a host that
         * reads keys from another header names it to the checker (`credentialHeaders`). `cosec-identity` is what a
         * gateway forwards to the services behind it: at the gateway it is the client's claim, which a condition must
         * never trust.
         */
        val CREDENTIAL_HEADERS: Set<String> =
            setOf("authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key", "cosec-identity")

        /** Whether [attribute] is a credential header, which no attribute reference may read (`CND-5`). */
        fun isCredentialHeader(attribute: Attribute): Boolean =
            attribute.kind == Attribute.Kind.HTTP_HEADER && attribute.name in CREDENTIAL_HEADERS

        private const val ROOT = "$"
        private const val IPV4_BITS = 32
        private const val IPV6_BITS = 128
        private val COMPARABLE = setOf(AttributeType.STRING, AttributeType.BOOL, AttributeType.IP)

        internal fun name(type: AttributeType): String = when (type) {
            AttributeType.STRING -> "String"
            AttributeType.BOOL -> "Bool"
            AttributeType.STRING_SET -> "Set<String>"
            AttributeType.IP -> "Ip"
            AttributeType.INSTANT -> "Instant"
            AttributeType.LABELS -> "Labels"
        }

        internal fun utf8Length(value: String): Int {
            var bytes = 0
            var i = 0
            while (i < value.length) {
                val c = value[i]
                val pair = c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate()
                bytes += when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    pair -> 4
                    else -> 3
                }
                i += if (pair) 2 else 1
            }
            return bytes
        }
    }
}
