package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.TrustedChangeActorContext
import io.github.sepfins.limitguard.api.commands.LimitChangeActorCommand
import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.application.configuration.resolveHierarchy
import io.github.sepfins.limitguard.domain.change.ChangeActor
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.common.PartyProfileDelegationId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.limit.LimitChannelScope
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationFundingScope
import io.github.sepfins.limitguard.domain.limit.LimitOperationScope
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.mandate.MandateScope
import io.github.sepfins.limitguard.port.persistence.ChannelGroupRepository
import io.github.sepfins.limitguard.port.persistence.ChannelRepository
import io.github.sepfins.limitguard.port.persistence.FundingSourceProductRepository
import io.github.sepfins.limitguard.port.persistence.LimitConfigurationRepository
import io.github.sepfins.limitguard.port.persistence.MandateRepository
import io.github.sepfins.limitguard.port.persistence.OperationTypeRepository
import io.github.sepfins.limitguard.port.persistence.PartyProfileDelegationRepository
import io.github.sepfins.limitguard.port.persistence.PartyProfileRepository
import io.github.sepfins.limitguard.port.persistence.TenantRepository
import java.time.Instant

internal fun LimitChangeActorCommand.requireTrustedAuthority(context: TrustedChangeActorContext?) {
    val valid = when (toChangeActor()) {
        ChangeActor.System -> context == TrustedChangeActorContext.InternalSystem
        is ChangeActor.PartyProfileActor -> context == null
    }
    if (!valid) throw ValidationException("actor requires a matching host-asserted trusted context")
}

internal class LimitChangeEligibilityValidator(
    private val tenants: TenantRepository,
    private val profiles: PartyProfileRepository,
    private val delegations: PartyProfileDelegationRepository,
    private val channels: ChannelRepository,
    private val channelGroups: ChannelGroupRepository,
    private val operations: OperationTypeRepository,
    private val products: FundingSourceProductRepository,
    private val configurations: LimitConfigurationRepository,
    private val mandates: MandateRepository,
) {
    fun validateTenant(tenantId: TenantId) {
        if (!tenants.exists(tenantId)) throw NotFoundException("tenant not found: ${tenantId.value}")
    }

    fun validateActionActor(tenantId: TenantId, actor: ChangeActor) {
        validateTenant(tenantId)
        when (actor) {
            ChangeActor.System -> Unit
            is ChangeActor.PartyProfileActor -> requireProfile(tenantId, actor.partyProfileId)
        }
    }

    fun validateSubmission(command: RequestLimitRuleChangeCommand, candidate: LimitRule, at: Instant) {
        val actor = command.toChangeActor()
        val requester = command.toRequesterPartyProfileId(actor)
        requireProfiles(candidate.tenantId, listOfNotNull(actor.partyProfileId(), candidate.ownerPartyProfileId, requester))
        validateRepresentation(
            candidate.tenantId, actor, requester, candidate.ownerPartyProfileId,
            command.authorityDelegationId?.let(PartyProfileDelegationId::of), at,
        )
        command.toRequestActorContext(actor).requestChannelId?.let { id ->
            val channel = channels.findById(candidate.tenantId, id)
                ?: throw NotFoundException("request channel not found: ${id.value}")
            if (!channel.isActive) throw ConflictException("request channel is not active: ${id.value}")
        }
        validateRuleReferences(candidate, at)
    }

    fun validateApprovedIntent(request: LimitRuleChangeRequest, candidate: LimitRule, at: Instant) {
        requireProfiles(
            request.tenantId,
            listOfNotNull(request.targetOwnerPartyProfileId, request.requesterPartyProfileId, request.requestedBy.partyProfileId()),
        )
        // The original submitter/channel describe submission, not the authority of today's approver or SYSTEM.
        validateRepresentation(
            request.tenantId, request.requestedBy, request.requesterPartyProfileId,
            request.targetOwnerPartyProfileId, request.authorityDelegationId, at,
        )
        if (candidate.tenantId != request.tenantId || candidate.ownerPartyProfileId != request.targetOwnerPartyProfileId) {
            throw ConflictException("approved rule does not belong to the request's tenant and owner")
        }
        validateRuleReferences(candidate, at)
    }

    private fun validateRepresentation(
        tenantId: TenantId,
        actor: ChangeActor,
        requester: PartyProfileId?,
        owner: PartyProfileId,
        delegationId: PartyProfileDelegationId?,
        at: Instant,
    ) {
        if (requester == null && actor != ChangeActor.System) {
            throw ValidationException("human changes require a Requester")
        }
        if (requester == null || requester == owner) {
            if (delegationId != null) throw ValidationException("Delegation is not applicable to direct owner or internal SYSTEM intent")
            return
        }
        val id = delegationId ?: throw ValidationException("representation requires an explicit Delegation")
        requireDelegation(tenantId, id, owner, requester, at)
    }

    private fun requireProfiles(tenantId: TenantId, ids: List<PartyProfileId>) {
        ids.distinct().forEach { requireProfile(tenantId, it) }
    }

    private fun requireProfile(tenantId: TenantId, id: PartyProfileId) {
        if (profiles.findById(tenantId, id) == null) throw NotFoundException("party profile not found: ${id.value}")
    }

    private fun ChangeActor.partyProfileId(): PartyProfileId? = (this as? ChangeActor.PartyProfileActor)?.partyProfileId

    private fun requireDelegation(
        tenantId: TenantId,
        id: PartyProfileDelegationId,
        owner: PartyProfileId,
        representative: PartyProfileId,
        at: Instant?,
    ) {
        val delegation = delegations.findById(tenantId, id)
            ?: throw NotFoundException("delegation not found: ${id.value}")
        if (delegation.ownerPartyProfileId != owner || delegation.delegatePartyProfileId != representative) {
            throw ConflictException("Delegation does not match the asserted representation: ${id.value}")
        }
        if (at != null && !delegation.effectiveWindow.contains(at)) {
            throw ConflictException("Delegation is not applicable at the action time: ${id.value}")
        }
    }

    private fun validateRuleReferences(rule: LimitRule, at: Instant) {
        requireProfile(rule.tenantId, rule.ownerPartyProfileId)
        validateScopes(rule.tenantId, rule.channelScope, rule.operationScope)
        rule.configurationId?.let { id ->
            configurations.resolveHierarchy(rule.tenantId, id).forEach { configuration ->
                validateScopes(rule.tenantId, configuration.channelScope, configuration.operationScope)
                val funding = configuration.fundingScope as? LimitConfigurationFundingScope.FundingSourceProductSpecific
                if (funding != null && products.findById(rule.tenantId, funding.productId) == null) {
                    throw NotFoundException("funding source product not found: ${funding.productId.value}")
                }
            }
        }
        val scope = rule.mandateScope as? MandateScope.MandateSpecific ?: return
        val mandate = mandates.findById(rule.tenantId, scope.mandateId)
            ?: throw NotFoundException("mandate not found: ${scope.mandateId.value}")
        if (mandate.ownerPartyProfileId != rule.ownerPartyProfileId) {
            throw ConflictException("mandate does not belong to target owner: ${mandate.id.value}")
        }
        requireProfile(rule.tenantId, mandate.assigneePartyProfileId)
        mandate.authorityDelegationId?.let { id ->
            requireDelegation(rule.tenantId, id, mandate.ownerPartyProfileId, mandate.assigneePartyProfileId,
                if (rule.isActive) maxOf(at, rule.effectiveWindow.startsAt) else null)
        }
    }

    private fun validateScopes(tenantId: TenantId, channel: LimitChannelScope, operation: LimitOperationScope) {
        when (channel) {
            LimitChannelScope.AllChannels -> Unit
            is LimitChannelScope.ChannelSpecific -> if (channels.findById(tenantId, channel.channelId) == null) {
                throw NotFoundException("channel not found: ${channel.channelId.value}")
            }
            is LimitChannelScope.ChannelGroupSpecific -> if (channelGroups.findById(tenantId, channel.channelGroupId) == null) {
                throw NotFoundException("channel group not found: ${channel.channelGroupId.value}")
            }
        }
        if (operation is LimitOperationScope.OperationTypeSpecific && operations.findById(tenantId, operation.operationTypeId) == null) {
            throw NotFoundException("operation type not found: ${operation.operationTypeId.value}")
        }
    }
}
