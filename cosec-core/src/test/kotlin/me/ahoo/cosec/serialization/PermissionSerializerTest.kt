package me.ahoo.cosec.serialization

import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.policy.Policy
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class PermissionSerializerTest {
    private val json = """
{"id":"manage","condition":{"bool":{"and":[{"authenticated":{}},{"rateLimiter":{"permitsPerSecond":10}},{"inTenant":{"value":"default"}}]}},"groups":[{"name":"order","description":"order management","permissions":[{"name":"Ship","effect":"allow","action":"/order/ship","condition":{"all":{}},"id":"manage.order.ship","description":"Ship"},{"name":"Issue an invoice","effect":"allow","action":"/order/issueInvoice","condition":{"all":{}},"id":"manage.order.issueInvoice","description":"Issue an invoice"}]}]}
    """.trimIndent()

    @Test
    fun serialize() {
        val appPermission = CoSecJsonSerializer.readValue(json, AppPermission::class.java)
        assertThat(appPermission.id, equalTo("manage"))
        val serializedJson = CoSecJsonSerializer.writeValueAsString(appPermission)
        assertThat(serializedJson, equalTo(json))
    }

    @Test
    fun serializeTestResource() {
        val testAppPermission =
            requireNotNull(javaClass.classLoader.getResource("test-app-permission.json")).let { resource ->
                resource.openStream().use {
                    CoSecJsonSerializer.readValue(it, AppPermission::class.java)
                }
            }
        assertThat(testAppPermission, `is`(notNullValue()))
        assertThat(CoSecJsonSerializer.writeValueAsString(testAppPermission), `is`(notNullValue()))
    }

    @ParameterizedTest
    @MethodSource("deserializeAppPermissionErrorProvider")
    fun deserializeAppPermissionError(json: String) {
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            CoSecJsonSerializer.readValue(
                json,
                Policy::class.java,
            )
        }
    }

    companion object {
        @JvmStatic
        fun deserializeAppPermissionErrorProvider(): Stream<String> {
            return Stream.of(
                "{}",
            )
        }
    }
}