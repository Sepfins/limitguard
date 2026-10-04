package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.commands.LimitRuleDraft
import io.github.sepfins.limitguard.domain.change.ChangeActor
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeType
import io.github.sepfins.limitguard.domain.change.RequestActorContext
import io.github.sepfins.limitguard.domain.change.ScopeHash
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.limit.LimitChannelScope
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationFundingScope
import io.github.sepfins.limitguard.internal.hashing.TargetScopeCanonicalizer
import io.github.sepfins.limitguard.domain.limit.LimitKind
import io.github.sepfins.limitguard.domain.limit.LimitOperationScope
import io.github.sepfins.limitguard.domain.limit.LimitUsageConsumptionMode
import io.github.sepfins.limitguard.domain.mandate.MandateScope
import io.github.sepfins.limitguard.domain.policy.PolicyAction
import io.github.sepfins.limitguard.infrastructure.jooq.toJsonb
import io.github.sepfins.limitguard.infrastructure.jooq.toMetadata
import io.github.sepfins.limitguard.internal.json.JsonValueEquality
import org.jooq.JSONB
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LimitRuleSnapshotterTest {
    private val now = Instant.parse("2026-06-15T00:00:00Z")

    @Test
    fun `rule snapshot round trips through persistence JSON`() {
        val snapshot = storedSnapshot()
        val rule = LimitRuleSnapshotter.restoreRule(snapshot, createdAt = now, updatedAt = now)

        assertEquals("Daily transfer limit", rule.name.value)
        assertEquals(Money.positive("500", CurrencyCode.of("EUR")), rule.amount)
        assertEquals(now, rule.createdAt)
        assertEquals(now, rule.updatedAt)
        assertEquals(snapshot, LimitRuleSnapshotter.ruleSnapshot(rule))
        assertEquals(rule.copy(createdAt = null, updatedAt = null),
            LimitRuleSnapshotter.restoreRule(snapshot.toJsonb().toMetadata()))
        assertEquals(1, LimitRuleSnapshotter.version(snapshot).value)
    }

    @Test
    fun `every selector and nullable window round trips through persistence JSON`() {
        val original = LimitRuleSnapshotter.restoreRule(storedSnapshot())
        val channels = listOf(
            original.channelScope,
            LimitChannelScope.AllChannels,
            LimitChannelScope.ChannelSpecific(ChannelId.of(UUID(0, 8))),
        )
        for (channel in channels) {
            for (kind in listOf(original.limitKind, LimitKind.PerTransaction)) {
                val rule = original.copy(
                    description = null,
                    configurationId = null,
                    mandateScope = MandateScope.AllOwnerMandates,
                    operationScope = LimitOperationScope.AllOperations,
                    channelScope = channel,
                    limitKind = kind,
                    usageConsumptionMode = LimitUsageConsumptionMode.CASCADING,
                    isActive = false,
                    effectiveWindow = original.effectiveWindow.copy(endsAt = null),
                )
                val snapshot = LimitRuleSnapshotter.ruleSnapshot(rule).toJsonb().toMetadata()
                assertEquals(rule, LimitRuleSnapshotter.restoreRule(snapshot))
            }
        }
    }

    @Test
    fun `integer fields accept equivalent JSON numbers without truncating corrupt values`() {
        val snapshot = storedSnapshot()
        for (key in listOf("version", "priority")) {
            for (number in listOf(1, 1L, 1.0, BigDecimal("1.000"))) {
                val modified = Metadata(snapshot.value + (key to number))
                val rule = LimitRuleSnapshotter.restoreRule(modified)
                assertEquals(1, if (key == "version") rule.version.value else rule.priority.value)
            }
            for (number in listOf(BigDecimal("1.5"), 4294967297L)) {
                val modified = Metadata(snapshot.value + (key to number))
                assertFailsWith<IllegalArgumentException>("$key = $number") {
                    LimitRuleSnapshotter.restoreRule(modified)
                }
            }
        }
    }

    @Test
    fun `draft matching normalizes money and metadata while respecting resolved defaults`() {
        val rule = LimitRuleSnapshotter.restoreRule(storedSnapshot())
        val draft = draft().copy(
            name = " Daily transfer limit ",
            amount = BigDecimal("500.00000"),
            metadata = rule.metadata.value + ("attempts" to BigDecimal("1.00")),
        )

        assertTrue(draft.matches(rule))
        assertTrue(draft.copy(configurationId = rule.configurationId!!.value).matches(rule))
        assertTrue(draft.copy(startsAt = rule.effectiveWindow.startsAt).matches(rule))
        assertFalse(draft.copy(configurationId = UUID(0, 99)).matches(rule))
        assertFalse(draft.copy(startsAt = now).matches(rule))
        assertFalse(draft.copy(amount = BigDecimal("501")).matches(rule))
        assertFalse(draft.copy(metadata = mapOf("attempts" to 2)).matches(rule))
        assertFalse(draft.copy(operationTypeId = null).matches(rule))
        assertFalse(draft.copy(endsAt = null).matches(rule))
    }

    @Test
    fun `cap edits change only the amount and leave the original unchanged`() {
        val snapshot = storedSnapshot()
        val requestedRule = LimitRuleSnapshotter.restoreRule(snapshot)
        val request = LimitRuleChangeRequest(
            id = LimitChangeRequestId.of(UUID(0, 9)),
            tenantId = requestedRule.tenantId,
            changeType = LimitRuleChangeType.CREATE,
            requestedBy = ChangeActor.System,
            targetOwnerPartyProfileId = requestedRule.ownerPartyProfileId,
            targetScope = TargetScopeCanonicalizer.snapshot(requestedRule.toTargetScope(LimitConfigurationFundingScope.AllFundingSources)),
            targetScopeHash = ScopeHash.of("a".repeat(64)),
            requestedRule = snapshot,
            requestActorContext = RequestActorContext.System(),
        )
        val cap = Money.positive("400", requestedRule.amount.currency)
        val result = LimitChangePolicyActionExecutor().execute(
            request, listOf(PolicyAction.CapRequestedAmount(cap)), now,
        )

        assertTrue(JsonValueEquality.objectsEqual(
            snapshot.value + ("amount" to "400.0000"), result.request.requestedRule.value,
        ))
        assertEquals("500.0000", snapshot.value["amount"])
        assertEquals(cap, LimitRuleSnapshotter.restoreRule(result.request.requestedRule).amount)
        assertEquals(true, result.results.single()["changed"])
        assertEquals(request.targetScopeHash, result.request.targetScopeHash)
    }

    private fun storedSnapshot(): Metadata =
        JSONB.valueOf(requireNotNull(javaClass.getResource("/snapshots/limit-rule.json")).readText())
            .toMetadata()

    private fun draft(): LimitRuleDraft = LimitRuleDraft(
        name = "Daily transfer limit",
        description = "Daily transfer cap",
        limitKind = io.github.sepfins.limitguard.api.LimitKind.PERIODIC,
        period = io.github.sepfins.limitguard.api.LimitPeriod.DAILY,
        amount = BigDecimal("500"),
        currency = "EUR",
        mandateScope = io.github.sepfins.limitguard.api.LimitMandateScope.MANDATE,
        mandateId = UUID(0, 4),
        channelScope = io.github.sepfins.limitguard.api.LimitChannelScope.CHANNEL_GROUP,
        channelGroupId = UUID(0, 6),
        operationTypeId = UUID(0, 7),
        usageConsumptionMode = io.github.sepfins.limitguard.api.LimitUsageConsumptionMode.ISOLATED,
        priority = 10,
        endsAt = Instant.parse("2026-07-01T00:00:00Z"),
        metadata = LimitRuleSnapshotter.restoreRule(storedSnapshot()).metadata.value,
    )
}
