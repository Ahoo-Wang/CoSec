package me.ahoo.cosec.authorization

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class LocalPolicyRepositoryTest {
    private val localPolicyRepository = LocalPolicyRepository(
        setOf(
            "classpath:build-in",
            "classpath:build-in/test-policy.json"
        )
    )

    @Test
    fun getGlobalPolicy() {
        localPolicyRepository.getGlobalPolicy()
            .test()
            .consumeNextWith {
                assertThat(it, hasSize(1))
            }.verifyComplete()
    }

    @Test
    fun getPolicies() {
        localPolicyRepository.getPolicies(setOf("id"))
            .test()
            .consumeNextWith {
                assertThat(it, hasSize(1))
            }.verifyComplete()
    }
}
