package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest

/** What Change Application made of a due request: adopted as its rule's state, or recorded as a Change Failure. */
internal sealed interface LimitChangeApplicationOutcome {
    val request: LimitRuleChangeRequest

    data class Applied(override val request: LimitRuleChangeRequest) : LimitChangeApplicationOutcome

    data class Failed(override val request: LimitRuleChangeRequest) : LimitChangeApplicationOutcome
}
