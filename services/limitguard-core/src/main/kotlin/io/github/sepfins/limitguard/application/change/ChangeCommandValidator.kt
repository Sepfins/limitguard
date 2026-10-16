package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import java.time.Instant

internal class ChangeCommandValidator {
    fun validateStructure(command: RequestLimitRuleChangeCommand) {
        require(command.expectedVersion == null || command.expectedVersion > 0) { "expectedVersion must be positive" }
        require(command.effectiveAt == null || command.expiresAt == null || command.expiresAt > command.effectiveAt) {
            "expiresAt must be after effectiveAt"
        }

        val actor = command.toChangeActor()
        command.toRequestActorContext(actor)
        command.toRequesterPartyProfileId(actor)
        command.idempotencyKey?.let { IdempotencyKey.of(it) }
    }

    fun validateEligibility(command: RequestLimitRuleChangeCommand, now: Instant) {
        require(command.expiresAt == null || command.expiresAt > now) {
            "expiresAt must be after the current time"
        }
    }
}
