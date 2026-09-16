package io.github.sepfins.limitguard.demo.application

import io.github.sepfins.limitguard.api.LimitChangeActorType
import io.github.sepfins.limitguard.api.LimitRuleChangeStatus
import io.github.sepfins.limitguard.api.LimitRuleChangeType
import io.github.sepfins.limitguard.api.Limitguard
import io.github.sepfins.limitguard.api.commands.ApproveLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.CancelLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.LimitChangeActorCommand
import io.github.sepfins.limitguard.api.commands.LimitRuleDraft
import io.github.sepfins.limitguard.api.commands.RejectLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.queries.GetLimitRuleQuery
import io.github.sepfins.limitguard.api.results.LimitChangeRequestResult
import io.github.sepfins.limitguard.demo.configuration.DemoProperties
import io.github.sepfins.limitguard.demo.domain.DemoLimitSetting
import io.github.sepfins.limitguard.demo.persistence.DemoBankRepository
import io.github.sepfins.limitguard.demo.web.dto.LimitUpdateResponse
import io.github.sepfins.limitguard.demo.web.error.DemoFieldError
import io.github.sepfins.limitguard.demo.web.error.DemoNotFoundException
import io.github.sepfins.limitguard.demo.web.error.DemoPolicyDeniedException
import io.github.sepfins.limitguard.demo.web.error.DemoValidationException
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Service
class LimitManagementService(
    private val limitguard: Limitguard,
    private val scenarioService: DemoScenarioService,
    private val customerBankingService: CustomerBankingService,
    private val repository: DemoBankRepository,
    private val eventStream: DemoEventStream,
    private val properties: DemoProperties,
) {
    fun update(settingId: UUID, amountText: String, idempotencyKey: String): LimitUpdateResponse {
        val scenario = scenarioService.current()
        val setting = repository.findLimitSetting(scenario.id, settingId)
            ?: throw DemoNotFoundException("Limit setting not found")
        val amount = parseAmount(amountText)
        validateStaticEnvelope(amount, setting.minimumAmount, setting.maximumAmount, setting.stepAmount)

        val tenantId = requireNotNull(scenario.tenantId)
        val customerId = requireNotNull(scenario.customerPartyProfileId)
        val ownerId = requireNotNull(scenario.ownerPartyProfileId)
        val delegationId = requireNotNull(scenario.authorityDelegationId)
        val mandateId = requireNotNull(scenario.mandateId)
        val currentRule = limitguard.limitInquiryService.getLimitRule(
            GetLimitRuleQuery(tenantId, setting.limitRuleId),
        )
        val currentLimit = customerBankingService.limit(settingId)
        if (amount.compareTo(currentRule.amount) == 0) {
            return LimitUpdateResponse(
                status = LimitRuleChangeStatus.APPLIED,
                message = "This limit is already active",
                limit = currentLimit,
            )
        }
        val currentExposure = BigDecimal(currentLimit.currentExposure.amount)
        val result = limitguard.limitChangeService.requestLimitRuleChange(
            RequestLimitRuleChangeCommand(
                tenantId = tenantId,
                changeType = LimitRuleChangeType.UPDATE,
                requestedBy = LimitChangeActorCommand(
                    actorType = LimitChangeActorType.PARTY_PROFILE,
                    partyProfileId = customerId,
                ),
                targetOwnerPartyProfileId = ownerId,
                limitRuleId = currentRule.id,
                expectedVersion = currentRule.version,
                requesterPartyProfileId = customerId,
                authorityDelegationId = delegationId,
                requestChannelId = setting.channelId,
                targetMandateId = mandateId,
                requestedRule = LimitRuleDraft(
                    name = currentRule.name,
                    description = currentRule.description,
                    limitKind = currentRule.limitKind,
                    period = currentRule.period,
                    amount = amount,
                    currency = currentRule.currency,
                    mandateScope = currentRule.mandateScope,
                    mandateId = currentRule.mandateId,
                    configurationId = currentRule.configurationId,
                    channelScope = currentRule.channelScope,
                    channelId = currentRule.channelId,
                    channelGroupId = currentRule.channelGroupId,
                    operationTypeId = currentRule.operationTypeId,
                    usageConsumptionMode = currentRule.usageConsumptionMode,
                    priority = currentRule.priority,
                    startsAt = currentRule.startsAt,
                    endsAt = currentRule.endsAt,
                    metadata = currentRule.metadata,
                ),
                policyContext = buildMap {
                    put("journey", "SELF_SERVICE")
                    put("currentExposure", currentExposure.toPlainString())
                    // JSON Logic cannot do date arithmetic, so the bank supplies the instant a delay ends.
                    put("coolingOffEndsAt", Instant.now().plus(properties.coolingOff).toString())
                },
                idempotencyKey = "demo-limit:$settingId:$idempotencyKey",
                metadata = mapOf("journey" to "SELF_SERVICE"),
            ),
        )
        if (result.status == LimitRuleChangeStatus.REJECTED) {
            throw DemoPolicyDeniedException(result.decisionReason ?: "The limit change was not approved")
        }
        when (result.status) {
            LimitRuleChangeStatus.REQUESTED -> repository.updatePendingChange(settingId, result.id, amount, null)
            LimitRuleChangeStatus.PENDING_EFFECTIVE_DATE ->
                repository.updatePendingChange(settingId, result.id, amount, result.effectiveAt)
            else -> Unit
        }
        if (result.status in VISIBLE_OUTCOMES) {
            eventStream.publish("limits.updated", "limit", settingId.toString())
        }
        return LimitUpdateResponse(
            status = result.status,
            message = when (result.status) {
                LimitRuleChangeStatus.REQUESTED -> "The account holder has to approve this limit"
                LimitRuleChangeStatus.PENDING_EFFECTIVE_DATE -> "The new limit starts after a cooling-off period"
                else -> result.decisionReason
            },
            limit = customerBankingService.limit(settingId),
        )
    }

    /** The customer takes back a change that still waits for the account holder. */
    fun withdraw(settingId: UUID): LimitUpdateResponse {
        val scenario = scenarioService.current()
        val setting = repository.findLimitSetting(scenario.id, settingId)
            ?: throw DemoNotFoundException("Limit setting not found")
        val changeRequestId = setting.pendingChangeRequestId
            ?: throw DemoNotFoundException("No limit change is waiting for approval")
        val result = limitguard.limitChangeService.cancelLimitRuleChange(
            CancelLimitRuleChangeCommand(
                tenantId = requireNotNull(scenario.tenantId),
                changeRequestId = changeRequestId,
                cancelledBy = partyProfile(requireNotNull(scenario.customerPartyProfileId)),
                decisionReason = "Withdrawn by the customer",
            ),
        )
        return settle(setting, result)
    }

    /** The account holder's answer, as their own banking app would send it through the integration API. */
    fun approve(changeRequestId: UUID): LimitUpdateResponse {
        val scenario = scenarioService.current()
        val setting = pendingSetting(scenario.id, changeRequestId)
        val result = limitguard.limitChangeService.approveLimitRuleChange(
            ApproveLimitRuleChangeCommand(
                tenantId = requireNotNull(scenario.tenantId),
                changeRequestId = changeRequestId,
                approvedBy = partyProfile(requireNotNull(scenario.ownerPartyProfileId)),
                decisionReason = "Approved by the account holder",
            ),
        )
        return settle(setting, result)
    }

    fun reject(changeRequestId: UUID, reason: String): LimitUpdateResponse {
        val scenario = scenarioService.current()
        val setting = pendingSetting(scenario.id, changeRequestId)
        val result = limitguard.limitChangeService.rejectLimitRuleChange(
            RejectLimitRuleChangeCommand(
                tenantId = requireNotNull(scenario.tenantId),
                changeRequestId = changeRequestId,
                rejectedBy = partyProfile(requireNotNull(scenario.ownerPartyProfileId)),
                decisionReason = reason,
            ),
        )
        return settle(setting, result)
    }

    private fun pendingSetting(scenarioId: UUID, changeRequestId: UUID): DemoLimitSetting =
        repository.findLimitSettingByPendingChange(scenarioId, changeRequestId)
            ?: throw DemoNotFoundException("No limit change is waiting for approval")

    private fun settle(setting: DemoLimitSetting, result: LimitChangeRequestResult): LimitUpdateResponse {
        repository.updatePendingChange(setting.id, null, null, null)
        eventStream.publish("limits.updated", "limit", setting.id.toString())
        return LimitUpdateResponse(
            status = result.status,
            message = result.decisionReason,
            limit = customerBankingService.limit(setting.id),
        )
    }

    private fun partyProfile(partyProfileId: UUID) =
        LimitChangeActorCommand(actorType = LimitChangeActorType.PARTY_PROFILE, partyProfileId = partyProfileId)

    private fun parseAmount(value: String): BigDecimal =
        try {
            BigDecimal(value)
        } catch (_: NumberFormatException) {
            throw DemoValidationException(
                "Limit amount is invalid",
                listOf(DemoFieldError("amount", "must be a decimal amount")),
            )
        }

    private fun validateStaticEnvelope(
        amount: BigDecimal,
        minimum: BigDecimal,
        maximum: BigDecimal,
        step: BigDecimal,
    ) {
        val error = when {
            amount < minimum -> "must be at least ${minimum.toPlainString()}"
            amount > maximum -> "must not exceed ${maximum.toPlainString()}"
            amount.subtract(minimum).remainder(step).signum() != 0 ->
                "must use ${step.toPlainString()} increments"
            else -> null
        }
        if (error != null) {
            throw DemoValidationException(
                "Limit amount is outside the allowed range",
                listOf(DemoFieldError("amount", error)),
            )
        }
    }

    private companion object {
        val VISIBLE_OUTCOMES = setOf(
            LimitRuleChangeStatus.APPLIED,
            LimitRuleChangeStatus.REQUESTED,
            LimitRuleChangeStatus.PENDING_EFFECTIVE_DATE,
        )
    }
}
