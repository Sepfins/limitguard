package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.domain.change.LimitRuleRevisionEventType
import io.github.sepfins.limitguard.domain.change.ResolvedLimitChangeFacts
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.port.persistence.LimitRuleRevisionRepository

internal class ChangeReferenceResolver(
    private val limitRuleRevisionRepository: LimitRuleRevisionRepository,
) {
    /** Call while holding the target's locks. */
    fun resolve(command: RequestLimitRuleChangeCommand, locked: LockedLimitChangeTarget): ResolvedLimitChangeFacts {
        val deleted = locked.rule?.takeIf { isDeleted(it) }
        val currentRule = locked.rule?.takeIf { deleted == null }
        if (command.expectedVersion != null && currentRule?.version?.value != command.expectedVersion) {
            throw ConflictException(
                "limit rule version conflict: expected ${command.expectedVersion}, found ${currentRule?.version?.value ?: "no rule"}"
            )
        }
        return ResolvedLimitChangeFacts(
            currentRule = currentRule,
            configuration = locked.configuration,
            deletedRule = deleted,
        )
    }

    private fun isDeleted(rule: LimitRule): Boolean =
        !rule.isActive && limitRuleRevisionRepository.findLatestByLimitRule(rule.tenantId, rule.id)
            ?.revisionEventType == LimitRuleRevisionEventType.DELETED
}
