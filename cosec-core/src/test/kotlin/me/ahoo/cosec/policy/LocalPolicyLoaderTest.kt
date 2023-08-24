package me.ahoo.cosec.policy

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test

class LocalPolicyLoaderTest {

    private val localPolicyRepository = LocalPolicyLoader(
        setOf(
            "classpath:cosec-policy",
            "classpath:cosec-policy/test-policy.json"
        )
    )

    @Test
    fun getPolicies() {
        assertThat(localPolicyRepository.policies, hasSize(1))
    }
}
