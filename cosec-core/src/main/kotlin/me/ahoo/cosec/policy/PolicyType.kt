package me.ahoo.cosec.policy

enum class PolicyType {
    /**
     * Global strategy.
     * For example, defining access to global anonymous resources
     */
    GLOBAL,

    /**
     * System policy, users cannot delete, only platform administrators can update.
     */
    SYSTEM,

    /**
     * User-defined policies.
     */
    CUSTOM
}
