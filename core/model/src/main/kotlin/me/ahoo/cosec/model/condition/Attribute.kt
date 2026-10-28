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

/** The static type of an attribute (RFC 0005 §3.2). Values are never converted from one type to another. */
enum class AttributeType {
    STRING,
    BOOL,
    STRING_SET,
    IP,

    /** Epoch milliseconds. Only `time` reads it. */
    INSTANT,

    /** The reversed labels of a host name, derived from the host (`api.example.com` is `[com, example, api]`). */
    LABELS,
}

/**
 * An attribute of the catalog (RFC 0005 §3.2): a [kind], and for the kinds that take one a [name], as in
 * `subject.attributes.<name>`. Paths outside the catalog do not compile.
 *
 * Header names are case-insensitive, so `http.header.<name>` keeps the name in ASCII lower case.
 */
class Attribute private constructor(
    val kind: Kind,
    val name: String,
    /** The declared type of an [Kind.EXTERNAL] attribute; every other kind has its own. */
    private val externalType: AttributeType? = null,
) {
    val type: AttributeType get() = externalType ?: kind.type

    /** Whether an `AttributeProvider` supplies it (RFC 0006 §5.4): its value can be unavailable, unlike any other. */
    val external: Boolean get() = kind == Kind.EXTERNAL

    val path: String = kind.path + name

    /** For a [AttributeType.LABELS] attribute, the host attribute its labels are derived from. */
    internal val host: Attribute?
        get() = when (kind) {
            Kind.HTTP_ORIGIN_HOST_LABELS -> of(Kind.HTTP_ORIGIN_HOST)
            Kind.HTTP_REFERER_HOST_LABELS -> of(Kind.HTTP_REFERER_HOST)
            else -> null
        }

    override fun equals(other: Any?): Boolean = other is Attribute && other.kind == kind && other.name == name

    override fun hashCode(): Int = 31 * kind.hashCode() + name.hashCode()

    override fun toString(): String = path

    /**
     * The attributes of the catalog. A [parameterized] kind's [path] ends with a dot and takes a non-empty name.
     */
    enum class Kind(val path: String, val type: AttributeType, val parameterized: Boolean = false) {
        SUBJECT_ID("subject.id", AttributeType.STRING),
        SUBJECT_TENANT("subject.tenant", AttributeType.STRING),
        SUBJECT_AUTHENTICATED("subject.authenticated", AttributeType.BOOL),

        /** Bare role ids the subject holds in any space. */
        SUBJECT_ROLES("subject.roles", AttributeType.STRING_SET),

        /** Roles as the token claims them: `role` in the default space, `role@space` elsewhere. */
        SUBJECT_SCOPED_ROLES("subject.scopedRoles", AttributeType.STRING_SET),
        SUBJECT_ATTRIBUTE("subject.attributes.", AttributeType.STRING, parameterized = true),
        SCOPE_TENANT("scope.tenant", AttributeType.STRING),
        SCOPE_APP("scope.app", AttributeType.STRING),
        SCOPE_SPACE("scope.space", AttributeType.STRING),

        /** Roles in effect in the space of the request. */
        SCOPE_ROLES("scope.roles", AttributeType.STRING_SET),
        ACTION_SERVICE("action.service", AttributeType.STRING),
        ACTION_VERB("action.verb", AttributeType.STRING),
        RESOURCE_TYPE("resource.type", AttributeType.STRING),
        RESOURCE_ID("resource.id", AttributeType.STRING),
        RESOURCE_ATTRIBUTE("resource.attributes.", AttributeType.STRING, parameterized = true),

        /** A variable the statement's action or resource pattern captured. Visible to that statement only. */
        RESOURCE_VAR("resource.var.", AttributeType.STRING, parameterized = true),
        ENVIRONMENT_IP("environment.ip", AttributeType.IP),
        ENVIRONMENT_TIME("environment.time", AttributeType.INSTANT),
        ENVIRONMENT_DEVICE("environment.device", AttributeType.STRING),
        ENVIRONMENT_BLOCKED("environment.blocked", AttributeType.BOOL),
        HTTP_HEADER("http.header.", AttributeType.STRING, parameterized = true),
        HTTP_ORIGIN("http.origin", AttributeType.STRING),
        HTTP_REFERER("http.referer", AttributeType.STRING),
        HTTP_ORIGIN_HOST("http.origin.host", AttributeType.STRING),
        HTTP_REFERER_HOST("http.referer.host", AttributeType.STRING),
        HTTP_ORIGIN_HOST_LABELS("http.origin.host.labels", AttributeType.LABELS),
        HTTP_REFERER_HOST_LABELS("http.referer.host.labels", AttributeType.LABELS),
        REQUEST_ATTRIBUTE("request.attributes.", AttributeType.STRING, parameterized = true),

        /**
         * `ext.<provider>.<name>`, supplied by an `AttributeProvider` (RFC 0006 §5.4.1) with the type it declares (the
         * type here is a placeholder). Not in the built-in catalog: only an [AttributeCatalog] resolves these paths.
         */
        EXTERNAL("ext.", AttributeType.STRING, parameterized = true),
    }

    companion object {
        private val FIXED: Map<String, Attribute> = Kind.entries.filterNot { it.parameterized }
            .associate { it.path to Attribute(it, "") }
        private val PARAMETERIZED: List<Kind> = Kind.entries.filter { it.parameterized && it != Kind.EXTERNAL }

        /** What an external attribute can be: a provider supplies plain values, never times or derived labels. */
        val EXTERNAL_TYPES: Set<AttributeType> =
            setOf(AttributeType.STRING, AttributeType.BOOL, AttributeType.STRING_SET, AttributeType.IP)

        /** The attribute of a kind that takes no name. */
        fun of(kind: Kind): Attribute {
            require(!kind.parameterized) { "Attribute ${kind.path}<name> needs a name." }
            return FIXED.getValue(kind.path)
        }

        /** The attribute [name] of a parameterized kind. */
        fun of(kind: Kind, name: String): Attribute {
            require(kind.parameterized) { "Attribute ${kind.path} takes no name." }
            require(kind != Kind.EXTERNAL) { "External attributes have a declared type: use Attribute.external." }
            require(name.isNotEmpty()) { "Attribute ${kind.path}<name> needs a name." }
            if (kind == Kind.HTTP_HEADER) {
                require(HttpNames.isToken(name)) { "A header name is an ASCII token (RFC 9110), not `$name`." }
                return Attribute(kind, HttpNames.fold(name))
            }
            return Attribute(kind, name)
        }

        /**
         * The external attribute at [path] (`ext.<provider>.<name>`) of [type]. [path] has the `ext.` prefix, a
         * provider segment and a name, and [type] is one of [EXTERNAL_TYPES].
         */
        fun external(path: String, type: AttributeType): Attribute {
            require(type in EXTERNAL_TYPES) { "External attribute $path cannot be ${type.name}." }
            require(isExternalPath(path)) { "External attribute paths are ext.<provider>.<name>, not $path." }
            return Attribute(Kind.EXTERNAL, path.substring(Kind.EXTERNAL.path.length), type)
        }

        /** Whether [path] has the form of an external attribute: `ext.<provider>.<name>`, neither part empty. */
        fun isExternalPath(path: String): Boolean {
            if (!path.startsWith(Kind.EXTERNAL.path)) {
                return false
            }
            val rest = path.substring(Kind.EXTERNAL.path.length)
            val dot = rest.indexOf('.')
            return dot > 0 && dot < rest.length - 1
        }

        /**
         * The built-in catalog attribute at [path], or null if there is none. External attributes are never built in,
         * and a header whose name is not an ASCII token ([HttpNames.isToken]) is none: it could fold onto another.
         */
        fun parse(path: String): Attribute? {
            FIXED[path]?.let { return it }
            for (kind in PARAMETERIZED) {
                if (path.length > kind.path.length && path.startsWith(kind.path)) {
                    val name = path.substring(kind.path.length)
                    if (kind == Kind.HTTP_HEADER && !HttpNames.isToken(name)) {
                        return null
                    }
                    return of(kind, name)
                }
            }
            return null
        }
    }
}

/**
 * The external attributes the registered `AttributeProvider`s declare (RFC 0006 §5.4.1). Conditions are checked
 * against the built-in catalog and this one; a path in `ext.` that no provider declares does not compile.
 */
fun interface AttributeCatalog {
    /** The declared attribute at [path], or null when no provider declares it. */
    fun external(path: String): Attribute?

    companion object {
        /** No provider: every `ext.` path is unknown. */
        val NONE: AttributeCatalog = AttributeCatalog { null }

        /** The catalog of [attributes], all external, with distinct paths. */
        fun of(attributes: Collection<Attribute>): AttributeCatalog {
            val byPath = HashMap<String, Attribute>(attributes.size * 2)
            for (attribute in attributes) {
                require(attribute.external) { "Not an external attribute: $attribute" }
                require(
                    byPath.put(attribute.path, attribute) == null
                ) { "External attribute $attribute is declared twice." }
            }
            return AttributeCatalog { byPath[it] }
        }
    }
}
