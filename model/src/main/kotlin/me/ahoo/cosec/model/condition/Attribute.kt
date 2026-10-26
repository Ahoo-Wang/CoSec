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
class Attribute private constructor(val kind: Kind, val name: String) {
    val type: AttributeType get() = kind.type

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
    }

    companion object {
        private val FIXED: Map<String, Attribute> = Kind.entries.filterNot { it.parameterized }
            .associate { it.path to Attribute(it, "") }
        private val PARAMETERIZED: List<Kind> = Kind.entries.filter { it.parameterized }

        /** The attribute of a kind that takes no name. */
        fun of(kind: Kind): Attribute {
            require(!kind.parameterized) { "Attribute ${kind.path}<name> needs a name." }
            return FIXED.getValue(kind.path)
        }

        /** The attribute [name] of a parameterized kind. */
        fun of(kind: Kind, name: String): Attribute {
            require(kind.parameterized) { "Attribute ${kind.path} takes no name." }
            require(name.isNotEmpty()) { "Attribute ${kind.path}<name> needs a name." }
            return Attribute(kind, if (kind == Kind.HTTP_HEADER) Ascii.lowercase(name) else name)
        }

        /** The catalog attribute at [path], or null if there is none. */
        fun parse(path: String): Attribute? {
            FIXED[path]?.let { return it }
            for (kind in PARAMETERIZED) {
                if (path.length > kind.path.length && path.startsWith(kind.path)) {
                    return of(kind, path.substring(kind.path.length))
                }
            }
            return null
        }
    }
}
