package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.mandate.MandateScope
import io.github.sepfins.limitguard.port.persistence.MandateRepository
import java.time.Instant

internal class LimitRuleMandateValidator(
    private val mandateRepository: MandateRepository,
) {
    fun validate(rule: LimitRule, applicationTime: Instant) {
        val mandateScope = rule.mandateScope as? MandateScope.MandateSpecific ?: return
        val mandate = mandateRepository.findById(rule.tenantId, mandateScope.mandateId)
            ?: throw NotFoundException("mandate not found: ${mandateScope.mandateId.value}")

        if (mandate.ownerPartyProfileId != rule.ownerPartyProfileId) {
            throw ConflictException("mandate does not belong to target owner: ${mandate.id.value}")
        }
        if (!rule.isActive) return
        if (!mandate.isApplicableAt(applicationTime)) {
            throw ConflictException("mandate is not active at rule application time: ${mandate.id.value}")
        }
    }
}
