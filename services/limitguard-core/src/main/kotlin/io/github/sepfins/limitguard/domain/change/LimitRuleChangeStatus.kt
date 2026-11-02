package io.github.sepfins.limitguard.domain.change

enum class LimitRuleChangeStatus {
    REQUESTED,
    APPROVED,
    PENDING_EFFECTIVE_DATE,
    APPLIED,
    REJECTED,
    CANCELLED,
    SUPERSEDED,
    FAILED,
    ;

    /** Not yet applied or closed: a decision, a cancellation, or the deadline can still reach it. */
    val isOpen: Boolean
        get() = this in OPEN

    val isAwaitingApproval: Boolean
        get() = this == REQUESTED

    /** Approved, and waiting for its application time or for the due pass. */
    val isAwaitingApplication: Boolean
        get() = this in AWAITING_APPLICATION

    companion object {
        val OPEN: Set<LimitRuleChangeStatus> = setOf(REQUESTED, APPROVED, PENDING_EFFECTIVE_DATE)
        val AWAITING_APPLICATION: Set<LimitRuleChangeStatus> = setOf(APPROVED, PENDING_EFFECTIVE_DATE)
    }
}
