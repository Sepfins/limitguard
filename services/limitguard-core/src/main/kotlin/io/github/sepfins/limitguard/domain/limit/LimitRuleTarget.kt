package io.github.sepfins.limitguard.domain.limit

import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.mandate.MandateScope
import java.util.UUID

/**
 * The identity of a rule within a tenant: one rule and one pending change per target. Lock
 * order is the tuple's natural order: owner, configuration, then mandate, with all owner
 * mandates first.
 */
data class LimitRuleTarget(
    val owner: PartyProfileId,
    val configurationId: LimitConfigurationId,
    val mandateScope: MandateScope,
) : Comparable<LimitRuleTarget> {
    override fun compareTo(other: LimitRuleTarget): Int = ORDER.compare(this, other)

    private companion object {
        val ORDER: Comparator<LimitRuleTarget> =
            compareBy<LimitRuleTarget> { it.owner.value }
                .thenBy { it.configurationId.value }
                .thenBy(nullsFirst<UUID>()) { (it.mandateScope as? MandateScope.MandateSpecific)?.mandateId?.value }
    }
}
