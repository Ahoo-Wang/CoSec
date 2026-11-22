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

package me.ahoo.cosec.spring.boot

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spring.boot.apikey.apiKeysEnabled
import me.ahoo.cosec.spring.boot.audit.kafka.kafkaAuditDelivery
import me.ahoo.cosec.spring.boot.control.ControlPlaneCondition
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Conditional
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory
import org.springframework.mock.env.MockEnvironment

/**
 * UB-1: the table of switches the startup check reads ([CoSecSwitches]) is the set of `cosec.*` keys the starter's
 * `@ConditionalOnProperty` conditions read, derived here from the compiled classes (`SimpleMetadataReaderFactory`, every
 * class of the starter, its member classes and methods, meta-annotations and repeated annotations). The running
 * application does no scanning; this test fails when the two differ in either direction.
 */
class CoSecSwitchesTest {
    /** What the scan found: the values each key is compared with, and the keys read as present-or-not. */
    private class Scan(val values: Map<String, Set<String>>, val presence: Set<String>, val conditions: Set<String>)

    private companion object {
        const val CONTAINER = "org.springframework.boot.autoconfigure.condition.ConditionalOnProperties"
    }

    private fun scan(): Scan {
        val resolver = PathMatchingResourcePatternResolver()
        val factory = SimpleMetadataReaderFactory(resolver)
        val values = sortedMapOf<String, MutableSet<String>>()
        val presence = sortedSetOf<String>()
        val conditions = sortedSetOf<String>()
        val resources = resolver.getResources("classpath*:me/ahoo/cosec/spring/boot/**/*.class")
            .filter { it.uri.toString().contains("/main/") }
        resources.size.assert().describedAs("the starter's classes were found").isGreaterThan(100)
        for (resource in resources) {
            val metadata = factory.getMetadataReader(resource).annotationMetadata
            val annotations = listOf(metadata.annotations) + metadata.declaredMethods.map { it.annotations }
            for (merged in annotations) {
                // Repeated annotations are in their container: the metadata of a class file does not unpack them.
                val properties = merged.stream(ConditionalOnProperty::class.java).toList() +
                    merged.stream<Annotation>(CONTAINER).toList()
                        .flatMap { it.getAnnotationArray("value", ConditionalOnProperty::class.java).toList() }
                properties.forEach { property ->
                    val prefix = property.getString(
                        "prefix"
                    ).let { if (it.isEmpty() || it.endsWith(".")) it else "$it." }
                    val names = property.getStringArray("value") + property.getStringArray("name")
                    val having = property.getString("havingValue")
                    for (name in names) {
                        val key = prefix + name
                        if (!key.startsWith("cosec.")) {
                            continue
                        }
                        when {
                            having.isNotEmpty() -> values.getOrPut(key) { sortedSetOf() } += having.lowercase()
                            // No value compared: on for anything but `false`, so `0` or `off` leaves the feature ON.
                            key.endsWith(".enabled") -> values.getOrPut(key) { sortedSetOf() } += "true"
                            else -> presence += key
                        }
                    }
                }
                merged.stream(Conditional::class.java).forEach { conditional ->
                    conditions += conditional.getStringArray("value").filter { it.startsWith("me.ahoo.cosec.") }
                }
            }
        }
        return Scan(values, presence, conditions)
    }

    @Test
    fun `the table holds exactly the cosec keys the conditions compare with a value, and the values they compare with`() {
        val scan = scan()
        val derived = scan.values.mapValues { (_, values) ->
            if (values == setOf("true")) {
                setOf(
                    "true",
                    "false"
                )
            } else {
                values
            }
        }.toMutableMap()
        // The default's own name is written by whoever wants it explicit, and nothing compares it: a condition reads the
        // other value, and the default is its absence (reviewed: `cosec.pipeline.caller=credential`).
        derived.merge("cosec.pipeline.caller", setOf("credential")) { found, default -> found + default }
        val table = CoSecSwitches.ALL.associate { it.key to it.accepted.toSet() }
        table.assert().isEqualTo(derived)
    }

    @Test
    fun `the presence conditions are the ones reviewed, each strict about its value elsewhere`() {
        // A present value, whatever it is, but `false`, matches these: they are not switches. A value that is wrong is
        // refused by the code that reads it (the login paths by LoginRoutesCheck, the generator by its own check).
        scan().presence.assert().containsExactly(
            "cosec.authentication.login.logout.path",
            "cosec.authentication.login.refresh.path",
            // A port number: the service port is on where one is written, and nothing compares it with a value.
            "cosec.control.http.service-port",
            // An address: the HTTP ControlService where one is written (STA-11); HttpControlService refuses one it cannot use.
            "cosec.control.service-url",
            "cosec.cosid.generator",
        )
    }

    @Test
    fun `every condition class of the starter is composed of covered conditions, strict itself, or reviewed`() {
        val reviewed = mapOf(
            "me.ahoo.cosec.spring.boot.jwt.OnJwtEnabled" to "any-of two @ConditionalOnProperty on cosec.jwt.enabled: covered",
            "me.ahoo.cosec.spring.boot.jwt.OnRevocationStores" to
                "any-of OnJwtEnabled and cosec.pipeline.caller=forwarded-identity: both covered",
            "me.ahoo.cosec.spring.boot.pipeline.OnIdentityGate" to
                "any-of cosec.pipeline.mode=gateway and cosec.pipeline.caller=forwarded-identity: both covered",
            "me.ahoo.cosec.spring.boot.authentication.route.OnLoginRoute" to "presence of the two login paths: reviewed above",
            "me.ahoo.cosec.spring.boot.control.ControlPlaneCondition" to "reads cosec.control.enabled strictly: held by a test",
            "me.ahoo.cosec.spring.boot.OnCoCacheUsed" to "class presence only",
        )
        val found = scan().conditions
        val unreviewed = found - reviewed.keys
        // Names the first class that reads the environment without being in the table or here.
        unreviewed.assert().describedAs("condition classes that are neither covered nor reviewed").isEmpty()
    }

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoSecPropertyCheckAutoConfiguration::class.java))

    private fun failure(vararg properties: String): String {
        var message = ""
        runner.withPropertyValues(*properties).run { context ->
            context.startupFailure.assert().isNotNull()
            message = generateSequence<Throwable>(context.startupFailure) { it.cause }.last().message.orEmpty()
        }
        return message
    }

    @Test
    fun `a value the conditions cannot read fails startup naming the key, the value and what is accepted`() {
        // 1, on, yes and an empty value are true to Spring's binder and "off" to the condition.
        listOf("1", "on", "yes", "", "enabled").forEach { value ->
            val message = failure("cosec.enabled=$value")
            message.assert().contains("cosec.enabled=[$value]").contains("write true or false")
                .contains("CoSec cannot read 1 cosec.* switch;")
        }
        failure("cosec.pipeline.mode=").assert().contains("cosec.pipeline.mode=[]: write enforce or gateway")
        failure("cosec.pipeline.mode=gatway").assert().contains("cosec.pipeline.mode=[gatway]")
        failure("cosec.api-key.store=file").assert().contains("cosec.api-key.store=[file]: write memory or redis")
        // Shadow's condition compares no value: `off` leaves it ON, the opposite of what was written.
        failure("cosec.authorization.shadow.enabled=off").assert().contains("cosec.authorization.shadow.enabled=[off]")
    }

    @Test
    fun `a value with surrounding spaces is not read as true by the condition, so it is refused`() {
        CoSecSwitches.unreadable(MockEnvironment().withProperty("cosec.enabled", " true ")).map { it.toString() }
            .assert().containsExactly("  cosec.enabled=[ true ]: write true or false")
    }

    @Test
    fun `every unreadable switch is in one message`() {
        val message = failure("cosec.enabled=1", "cosec.jwt.enabled=on", "cosec.pipeline.mode=")
        message.assert().contains("CoSec cannot read 3 cosec.* switches")
            .contains("cosec.enabled=[1]").contains("cosec.jwt.enabled=[on]").contains("cosec.pipeline.mode=[]")
            .contains(CoSecSwitches.DOCUMENTATION)
    }

    @Test
    fun `an absent switch, true and false in any case are read, and false is reported as switched off`() {
        runner.withPropertyValues(
            "cosec.enabled=TRUE",
            "cosec.audit.enabled=False",
            "cosec.pipeline.mode=Gateway",
            "cosec.metrics.enabled=false",
        ).run { context ->
            context.startupFailure.assert().isNull()
            CoSecSwitches.switchedOff(context.environment).assert()
                .containsExactlyInAnyOrder("cosec.audit.enabled", "cosec.metrics.enabled")
        }
        runner.run { it.startupFailure.assert().isNull() }
    }

    @Test
    fun `the custom conditions that read their own switch fail startup naming the property`() {
        assertThrownBy<IllegalStateException> {
            ControlPlaneCondition.written(MockEnvironment().withProperty("cosec.control.enabled", "maybe"))
        }.hasMessageContaining("cosec.control.enabled must be true or false, not [maybe]")
        assertThrownBy<IllegalArgumentException> {
            apiKeysEnabled(MockEnvironment().withProperty("cosec.api-key.enabled", "on"))
        }.hasMessageContaining("cosec.api-key.enabled=[on] is neither true nor false")
        assertThrownBy<IllegalArgumentException> {
            kafkaAuditDelivery(MockEnvironment().withProperty("cosec.audit.kafka.delivery", "sometimes"))
        }.hasMessageContaining("cosec.audit.kafka.delivery=[sometimes] is neither required nor best-effort")
    }
}
