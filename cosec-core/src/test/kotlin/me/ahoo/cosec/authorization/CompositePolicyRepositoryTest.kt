package me.ahoo.cosec.authorization

import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class CompositePolicyRepositoryTest {
    private val localPolicyRepository = LocalPolicyRepository(
        setOf(
            "classpath:build-in",
            "classpath:build-in/test-policy.json"
        )
    )
    private val compositePolicyRepository = CompositePolicyRepository(
        listOf(
            localPolicyRepository,
            localPolicyRepository
        )
    )

    @Test
    fun getGlobalPolicy() {
        compositePolicyRepository.getGlobalPolicy()
            .test()
            .consumeNextWith {
                MatcherAssert.assertThat(it, Matchers.hasSize(2))
            }.verifyComplete()
    }

    @Test
    fun getPolicies() {
        compositePolicyRepository.getPolicies(setOf("id"))
            .test()
            .consumeNextWith {
                MatcherAssert.assertThat(it, Matchers.hasSize(2))
            }.verifyComplete()
    }
}
