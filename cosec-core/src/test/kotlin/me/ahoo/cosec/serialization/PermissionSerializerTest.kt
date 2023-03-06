package me.ahoo.cosec.serialization

import me.ahoo.cosec.api.permission.AppPermission
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

class PermissionSerializerTest {
    private val json = """
{"id":"appId","groups":[{"name":"name","description":"description","permissions":[{"name":"Anonymous","effect":"allow","actions":[{"type":"path","pattern":"/auth/register"},{"type":"path","pattern":"/auth/login"}],"condition":{"type":"all"},"id":"permissionId","description":"description"}]}]}
    """.trimIndent()

    @Test
    fun serialize() {
        val appPermission = CoSecJsonSerializer.readValue(json, AppPermission::class.java)
        assertThat(appPermission.id, equalTo("appId"))
        val serializedJson = CoSecJsonSerializer.writeValueAsString(appPermission)
        assertThat(serializedJson, equalTo(json))

    }
}