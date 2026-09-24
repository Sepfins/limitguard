package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.LimitRuleChangeType
import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import java.time.Instant

internal class ChangeCommandValidator {
    fun validate(command: RequestLimitRuleChangeCommand, now: Instant) {
        validateStructure(command)
        validateEligibility(command, now)
    }

    fun validateStructure(command: RequestLimitRuleChangeCommand) {
        when (command.changeType) {
            LimitRuleChangeType.CREATE -> {
                require(command.limitRuleId == null) { "limitRuleId must be null for CREATE changes" }
                require(command.expectedVersion == null) { "expectedVersion must be null for CREATE changes" }
                requireNotNull(command.requestedRule) { "requestedRule is required for CREATE changes" }
            }
            LimitRuleChangeType.UPDATE -> {
                requireNotNull(command.limitRuleId) { "limitRuleId is required for UPDATE changes" }
                requireNotNull(command.requestedRule) { "requestedRule is required for UPDATE changes" }
                requirePositiveExpectedVersion(command)
            }
            LimitRuleChangeType.REACTIVATE -> {
                requireNotNull(command.limitRuleId) { "limitRuleId is required for REACTIVATE changes" }
                requirePositiveExpectedVersion(command)
            }
            LimitRuleChangeType.DEACTIVATE,
            LimitRuleChangeType.DELETE -> {
                requireNotNull(command.limitRuleId) { "limitRuleId is required for ${command.changeType} changes" }
                require(command.requestedRule == null) {
                    "requestedRule must be null for ${command.changeType} changes"
                }
                requirePositiveExpectedVersion(command)
            }
        }
        require(command.effectiveAt == null || command.expiresAt == null || command.expiresAt > command.effectiveAt) {
            "expiresAt must be after effectiveAt"
        }

        val actor = command.toChangeActor()
        command.toRequestActorContext(actor)
        if (command.requestedRule != null) {
            command.toTargetMandateId()
        }
        command.idempotencyKey?.let { IdempotencyKey.of(it) }
    }

    fun validateEligibility(command: RequestLimitRuleChangeCommand, now: Instant) {
        require(command.expiresAt == null || command.expiresAt > now) {
            "expiresAt must be after the current time"
        }
    }

    private fun requirePositiveExpectedVersion(command: RequestLimitRuleChangeCommand) {
        requireNotNull(command.expectedVersion) {
            "expectedVersion is required for ${command.changeType} changes"
        }
        require(command.expectedVersion > 0) { "expectedVersion must be positive" }
    }
}
