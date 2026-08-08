package io.github.sepfins.limitguard.internal.hashing

import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.limit.LimitChannelScope
import io.github.sepfins.limitguard.domain.limit.LimitKind
import io.github.sepfins.limitguard.domain.limit.LimitOperationScope
import io.github.sepfins.limitguard.domain.limit.LimitPeriod
import io.github.sepfins.limitguard.domain.limit.LimitRuleTargetScope
import io.github.sepfins.limitguard.domain.mandate.MandateScope
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class TargetScopeCanonicalizerTest {
    @Test
    fun `target scope delegates to the shared canonical JSON format`() {
        val scope = LimitRuleTargetScope(
            owner = PartyProfileId.of(UUID(0L, 1L)),
            mandateScope = MandateScope.AllOwnerMandates,
            channelScope = LimitChannelScope.AllChannels,
            operationScope = LimitOperationScope.AllOperations,
            currency = CurrencyCode.of("EUR"),
            kind = LimitKind.Periodic(LimitPeriod.DAILY),
        )

        assertEquals(
            CanonicalJson.encodeObject(
                mapOf(
                    "owner" to "00000000-0000-0000-0000-000000000001",
                    "mandateScope" to "ALL_OWNER_MANDATES",
                    "mandateId" to null,
                    "channelScope" to "ALL_CHANNELS",
                    "channelGroupId" to null,
                    "channelId" to null,
                    "operationScope" to "ALL_OPERATIONS",
                    "operationTypeId" to null,
                    "currency" to "EUR",
                    "kind" to "PERIODIC",
                    "period" to "DAILY",
                ),
            ),
            TargetScopeCanonicalizer.canonicalize(scope),
        )
    }
}
