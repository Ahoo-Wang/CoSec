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

package me.ahoo.cosec.runtime.attribute

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.engine.flat.ExternalValues
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.AttributeCatalog

private val log = KotlinLogging.logger {}

/**
 * The registered [AttributeProvider]s and the attribute catalog they make (RFC 0006 §5.4.1). Give the same instance's
 * [catalog] to the document compiler and the instance to the pipeline: a policy then reads only attributes some
 * provider here fetches, and one whose provider is missing anyway is unavailable, never absent.
 *
 * Every path is an `ext.<provider>.<name>` path of an external type, declared once across all providers; the blacklist
 * attributes ([BlacklistAttributeProvider.ATTRIBUTES]) only in stage A.
 */
class AttributeProviders(providers: List<AttributeProvider>) {
    /** One provider and what it declares in one stage. */
    internal class Registered(
        val provider: AttributeProvider,
        val attributes: Map<String, Attribute>,
    ) {
        val names: Set<String> = attributes.keys
    }

    private val byPath: Map<String, Registered>

    /** The providers that declare stage-A attributes, with only those; fetched on every request. */
    internal val stageA: List<Registered>

    /** The catalog of every declared attribute, for `PolicyDocumentCompiler(catalog)`. */
    val catalog: AttributeCatalog

    val isEmpty: Boolean get() = byPath.isEmpty()

    init {
        val byPath = LinkedHashMap<String, Registered>()
        val stageA = ArrayList<Registered>()
        for (provider in providers) {
            val declared = provider.attributes.groupBy({ it.stage }) { Attribute.external(it.path, it.type) }
            for ((stage, attributes) in declared) {
                val registered = Registered(provider, attributes.associateBy { it.path })
                for (path in registered.names) {
                    require(byPath.put(path, registered) == null) { "External attribute $path is declared twice." }
                    // The pipeline reads them before it loads the view: in stage B they would come too late.
                    require(stage == AttributeDeclaration.Stage.A || path !in BlacklistAttributeProvider.ATTRIBUTES) {
                        "$provider declares the blacklist attribute $path in stage B; it must be stage A."
                    }
                }
                require(registered.names.size == attributes.size) { "$provider declares an attribute twice." }
                if (stage == AttributeDeclaration.Stage.A) {
                    stageA += registered
                }
            }
        }
        this.byPath = byPath
        this.stageA = stageA
        val attributes = byPath.mapValues { (path, registered) -> registered.attributes.getValue(path) }
        catalog = if (attributes.isEmpty()) AttributeCatalog.NONE else AttributeCatalog { attributes[it] }
    }

    /** Who fetches [path], or null when nobody does. */
    internal fun registered(path: String): Registered? = byPath[path]

    /** Whether some provider declares [path]. */
    fun declares(path: String): Boolean = path in byPath

    override fun toString(): String = "AttributeProviders${byPath.keys}"

    companion object {
        val NONE: AttributeProviders = AttributeProviders(emptyList())
    }
}

/**
 * What the pipeline fetched for one request, stage A and stage B together: the paths it asked for, the values, and
 * the paths fetched without failure. Not thread-safe: the pipeline records each answer after awaiting it.
 */
internal class FetchedAttributes {
    private val values = HashMap<String, Any>()
    private val available = HashSet<String>()
    private val asked = HashSet<String>()

    /** Nothing asked for, so nothing available. */
    val isEmpty: Boolean get() = asked.isEmpty()

    fun asked(path: String): Boolean = path in asked

    fun value(path: String): Any? = values[path]

    fun available(path: String): Boolean = path in available

    /** Records what [registered] answered for [names]; null when it failed or did not answer in time. */
    fun record(registered: AttributeProviders.Registered, names: Set<String>, answer: Map<String, Any>?) {
        asked += names
        if (answer == null) {
            return
        }
        for (path in names) {
            val value = answer[path]
            if (value == null) {
                available += path
                continue
            }
            val attribute = registered.attributes.getValue(path)
            if (ExternalValues.fits(value, attribute.type)) {
                values[path] = value
                available += path
            } else {
                // Unavailable, never absent: a wrong type is the provider's defect, not "no value".
                log.warn { "${registered.provider} answered a ${value.javaClass.name} for $path, declared ${attribute.type}." }
            }
        }
    }

    fun toExternalValues(): ExternalValues = ExternalValues(values, available)
}
