package io.github.sepfins.limitguard.infrastructure.jooq.repository

import io.github.sepfins.limitguard.domain.change.ChangeActor
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeStatus
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeType
import io.github.sepfins.limitguard.domain.change.RequestActorContext
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.MandateId
import io.github.sepfins.limitguard.domain.common.PartyProfileDelegationId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.limit.LimitAmount
import io.github.sepfins.limitguard.infrastructure.jooq.JooqPersistenceContext
import io.github.sepfins.limitguard.infrastructure.jooq.jooqOperation
import io.github.sepfins.limitguard.infrastructure.jooq.storedAmount
import io.github.sepfins.limitguard.infrastructure.jooq.storedLimitAmount
import io.github.sepfins.limitguard.infrastructure.jooq.toJsonb
import io.github.sepfins.limitguard.infrastructure.jooq.toMetadata
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.LimitChangeActorType as GeneratedLimitChangeActorType
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.LimitRuleChangeStatus as GeneratedLimitRuleChangeStatus
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.LimitRuleChangeType as GeneratedLimitRuleChangeType
import io.github.sepfins.limitguard.infrastructure.jooq.generated.tables.LimitConfiguration as GeneratedLimitConfigurationTable
import io.github.sepfins.limitguard.infrastructure.jooq.generated.tables.LimitRuleChangeRequest as GeneratedLimitRuleChangeRequestTable
import io.github.sepfins.limitguard.port.persistence.LimitChangeRequestRepository
import org.jooq.Record
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class JooqLimitChangeRequestRepository(
    private val context: JooqPersistenceContext,
) : LimitChangeRequestRepository {
    private val table = GeneratedLimitRuleChangeRequestTable.LIMIT_RULE_CHANGE_REQUEST
    private val configuration = GeneratedLimitConfigurationTable.LIMIT_CONFIGURATION

    override fun findById(tenantId: TenantId, id: LimitChangeRequestId): LimitRuleChangeRequest? =
        findById(tenantId, id, forUpdate = false)

    override fun findByIdForUpdate(
        tenantId: TenantId,
        id: LimitChangeRequestId,
    ): LimitRuleChangeRequest? {
        context.requireTransaction("locking a limit change request row")
        return findById(tenantId, id, forUpdate = true)
    }

    override fun findByIdempotencyKey(
        tenantId: TenantId,
        key: IdempotencyKey,
    ): LimitRuleChangeRequest? = jooqOperation("find limit change request by idempotency key") {
        selectRequests()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.IDEMPOTENCY_KEY.eq(key.value))
            .fetchOne { it.toDomain() }
    }

    override fun findIdsDueForApplication(
        tenantId: TenantId,
        dueAt: Instant,
        limit: Int,
    ): List<LimitChangeRequestId> = jooqOperation("discover due limit change requests") {
        require(limit > 0) { "limit must be greater than zero" }
        context.dsl().select(table.ID).from(table).where(dueCondition(tenantId, dueAt))
            .orderBy(table.CREATED_AT, table.ID).limit(limit)
            .fetch { LimitChangeRequestId.of(requireNotNull(it.get(table.ID))) }
    }

    override fun claimForApplication(
        tenantId: TenantId,
        id: LimitChangeRequestId,
        dueAt: Instant,
    ): LimitRuleChangeRequest? = jooqOperation("claim due limit change request") {
        context.requireTransaction("claiming a due limit change request")
        selectRequests().where(dueCondition(tenantId, dueAt)).and(table.ID.eq(id.value))
            .forUpdate().of(table).skipLocked().fetchOne { it.toDomain() }
    }

    override fun findByRequesterContext(
        tenantId: TenantId,
        requestActorContext: RequestActorContext,
    ): List<LimitRuleChangeRequest> = jooqOperation("find limit changes by requester context") {
        val condition = when (requestActorContext) {
            is RequestActorContext.System -> table.REQUESTED_BY_ACTOR_TYPE.eq(GeneratedLimitChangeActorType.SYSTEM)
                .and(requestActorContext.requestChannelId?.let { table.REQUEST_CHANNEL_ID.eq(it.value) }
                    ?: table.REQUEST_CHANNEL_ID.isNull)
            is RequestActorContext.PartyProfile -> table.REQUESTED_BY_ACTOR_TYPE
                .eq(GeneratedLimitChangeActorType.PARTY_PROFILE)
                .and(table.REQUESTED_BY_PARTY_PROFILE_ID.eq(requestActorContext.actorPartyProfileId.value))
                .and(table.REQUEST_CHANNEL_ID.eq(requestActorContext.requestChannelId.value))
        }
        selectRequests()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(condition)
            .orderBy(table.CREATED_AT, table.ID)
            .fetch { it.toDomain() }
    }

    override fun findByRequester(
        tenantId: TenantId,
        requester: PartyProfileId,
    ): List<LimitRuleChangeRequest> = jooqOperation("find limit changes by represented requester") {
        selectRequests()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.REQUESTER_PARTY_PROFILE_ID.eq(requester.value))
            .orderBy(table.CREATED_AT, table.ID)
            .fetch { it.toDomain() }
    }

    override fun insert(
        tenantId: TenantId,
        request: LimitRuleChangeRequest,
    ): LimitRuleChangeRequest = jooqOperation(
        operation = "insert limit change request",
        idempotencyConstraint = "uq_limit_rule_change_request_idempotency",
    ) {
        require(request.tenantId == tenantId) { "limit change request tenant does not match repository tenant" }
        val insert = context.dsl()
            .insertInto(table)
            .set(table.ID, request.id.value)
            .set(table.TENANT_ID, tenantId.value)
            .set(table.LIMIT_RULE_ID, request.limitRuleId?.value)
            .set(table.CHANGE_TYPE, request.changeType.toGenerated())
            .set(table.STATUS, request.status.toGenerated())
            .set(table.REQUESTED_BY_ACTOR_TYPE, request.requestedBy.toGenerated())
            .set(table.REQUESTED_BY_PARTY_PROFILE_ID, request.actorPartyProfileId())
            .set(table.REQUESTER_PARTY_PROFILE_ID, request.requesterPartyProfileId?.value)
            .set(table.AUTHORITY_DELEGATION_ID, request.authorityDelegationId?.value)
            .set(table.REQUEST_CHANNEL_ID, request.requestChannelId())
            .set(table.TARGET_OWNER_PARTY_PROFILE_ID, request.targetOwnerPartyProfileId.value)
            .set(table.TARGET_MANDATE_ID, request.targetMandateId?.value)
            .set(table.CONFIGURATION_ID, request.configurationId.value)
            .set(table.BEFORE_AMOUNT, request.beforeAmount?.storedAmount)
            .set(table.BEFORE_UNLIMITED, request.beforeAmount?.let { it is LimitAmount.Unlimited })
            .set(table.REQUESTED_AMOUNT, request.requestedAmount.storedAmount)
            .set(table.REQUESTED_UNLIMITED, request.requestedAmount is LimitAmount.Unlimited)
            .set(table.POLICY_CONTEXT, request.policyContext.toJsonb())
            .set(table.EFFECTIVE_AT, request.effectiveAt?.toOffsetDateTime())
            .set(table.EXPIRES_AT, request.expiresAt?.toOffsetDateTime())
            .set(table.DECIDED_AT, request.decidedAt?.toOffsetDateTime())
            .set(table.APPLIED_AT, request.appliedAt?.toOffsetDateTime())
            .set(table.CANCELLED_AT, request.cancelledAt?.toOffsetDateTime())
            .set(table.SUPERSEDED_BY_CHANGE_REQUEST_ID, request.supersededByChangeRequestId?.value)
            .set(table.DECISION_REASON, request.decisionReason)
            .set(table.IDEMPOTENCY_KEY, request.idempotencyKey?.value)
            .set(table.METADATA, request.metadata.toJsonb())
        request.createdAt?.let { insert.set(table.CREATED_AT, it.toOffsetDateTime()) }
        request.updatedAt?.let { insert.set(table.UPDATED_AT, it.toOffsetDateTime()) }
        requireNotNull(insert.returning().fetchOne()).toDomain(request.requestedAmount.currency)
    }

    override fun update(
        tenantId: TenantId,
        request: LimitRuleChangeRequest,
    ): LimitRuleChangeRequest = jooqOperation("update limit change request") {
        require(request.tenantId == tenantId) { "limit change request tenant does not match repository tenant" }
        val update = context.dsl()
            .update(table)
            .set(table.LIMIT_RULE_ID, request.limitRuleId?.value)
            .set(table.STATUS, request.status.toGenerated())
            .set(table.REQUESTED_AMOUNT, request.requestedAmount.storedAmount)
            .set(table.REQUESTED_UNLIMITED, request.requestedAmount is LimitAmount.Unlimited)
            .set(table.POLICY_CONTEXT, request.policyContext.toJsonb())
            .set(table.EFFECTIVE_AT, request.effectiveAt?.toOffsetDateTime())
            .set(table.EXPIRES_AT, request.expiresAt?.toOffsetDateTime())
            .set(table.DECIDED_AT, request.decidedAt?.toOffsetDateTime())
            .set(table.APPLIED_AT, request.appliedAt?.toOffsetDateTime())
            .set(table.CANCELLED_AT, request.cancelledAt?.toOffsetDateTime())
            .set(table.SUPERSEDED_BY_CHANGE_REQUEST_ID, request.supersededByChangeRequestId?.value)
            .set(table.DECISION_REASON, request.decisionReason)
            .set(table.METADATA, request.metadata.toJsonb())
        request.updatedAt?.let { update.set(table.UPDATED_AT, it.toOffsetDateTime()) }
        val updated = update.where(table.TENANT_ID.eq(tenantId.value))
            .and(table.ID.eq(request.id.value))
            .returning()
            .fetchOne()
        require(updated != null) { "limit change request not found: ${request.id.value}" }
        updated.toDomain(request.requestedAmount.currency)
    }

    private fun findById(
        tenantId: TenantId,
        id: LimitChangeRequestId,
        forUpdate: Boolean,
    ): LimitRuleChangeRequest? = jooqOperation("find limit change request by id") {
        val query = selectRequests()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.ID.eq(id.value))
        if (forUpdate) query.forUpdate().of(table).fetchOne { it.toDomain() } else query.fetchOne { it.toDomain() }
    }

    /** Amounts are in the target configuration's currency, the only currency column. */
    private fun selectRequests() = context.dsl()
        .select(table.fields().toList() + configuration.CURRENCY)
        .from(table)
        .join(configuration)
        .on(configuration.TENANT_ID.eq(table.TENANT_ID))
        .and(configuration.ID.eq(table.CONFIGURATION_ID))

    /** Approved changes whose time has come, and open requests whose deadline has passed. */
    private fun dueCondition(tenantId: TenantId, dueAt: Instant) =
        table.TENANT_ID.eq(tenantId.value).and(
            table.STATUS.`in`(DUE_STATUSES)
                .and(table.EFFECTIVE_AT.isNull.or(table.EFFECTIVE_AT.le(dueAt.toOffsetDateTime())))
                .or(table.STATUS.`in`(PENDING_STATUSES).and(table.EXPIRES_AT.le(dueAt.toOffsetDateTime()))),
        )

    private fun Record.toDomain(): LimitRuleChangeRequest =
        toDomain(CurrencyCode.of(requireNotNull(get(configuration.CURRENCY))))

    private fun Record.toDomain(currency: CurrencyCode): LimitRuleChangeRequest {
        val requestedBy = when (requireNotNull(get(table.REQUESTED_BY_ACTOR_TYPE))) {
            GeneratedLimitChangeActorType.SYSTEM -> ChangeActor.System
            GeneratedLimitChangeActorType.PARTY_PROFILE -> ChangeActor.PartyProfileActor(
                PartyProfileId.of(requireNotNull(get(table.REQUESTED_BY_PARTY_PROFILE_ID)))
            )
        }
        val requestActorContext = when (requestedBy) {
            ChangeActor.System -> RequestActorContext.System(get(table.REQUEST_CHANNEL_ID)?.let(ChannelId::of))
            is ChangeActor.PartyProfileActor -> RequestActorContext.PartyProfile(
                actorPartyProfileId = requestedBy.partyProfileId,
                requestChannelId = ChannelId.of(requireNotNull(get(table.REQUEST_CHANNEL_ID))),
            )
        }

        return LimitRuleChangeRequest(
            id = LimitChangeRequestId.of(requireNotNull(get(table.ID))),
            tenantId = TenantId.of(requireNotNull(get(table.TENANT_ID))),
            changeType = LimitRuleChangeType.valueOf(requireNotNull(get(table.CHANGE_TYPE)).name),
            requestedBy = requestedBy,
            targetOwnerPartyProfileId = PartyProfileId.of(requireNotNull(get(table.TARGET_OWNER_PARTY_PROFILE_ID))),
            configurationId = LimitConfigurationId.of(requireNotNull(get(table.CONFIGURATION_ID))),
            requestedAmount = storedLimitAmount(get(table.REQUESTED_AMOUNT), requireNotNull(get(table.REQUESTED_UNLIMITED)), currency),
            status = LimitRuleChangeStatus.valueOf(requireNotNull(get(table.STATUS)).name),
            limitRuleId = get(table.LIMIT_RULE_ID)?.let(LimitRuleId::of),
            authorityDelegationId = get(table.AUTHORITY_DELEGATION_ID)?.let(PartyProfileDelegationId::of),
            requestActorContext = requestActorContext,
            requesterPartyProfileId = get(table.REQUESTER_PARTY_PROFILE_ID)?.let(PartyProfileId::of),
            targetMandateId = get(table.TARGET_MANDATE_ID)?.let(MandateId::of),
            beforeAmount = get(table.BEFORE_UNLIMITED)?.let { storedLimitAmount(get(table.BEFORE_AMOUNT), it, currency) },
            policyContext = get(table.POLICY_CONTEXT).toMetadata(),
            effectiveAt = get(table.EFFECTIVE_AT)?.toInstant(),
            expiresAt = get(table.EXPIRES_AT)?.toInstant(),
            decidedAt = get(table.DECIDED_AT)?.toInstant(),
            appliedAt = get(table.APPLIED_AT)?.toInstant(),
            cancelledAt = get(table.CANCELLED_AT)?.toInstant(),
            supersededByChangeRequestId = get(table.SUPERSEDED_BY_CHANGE_REQUEST_ID)?.let(LimitChangeRequestId::of),
            decisionReason = get(table.DECISION_REASON),
            idempotencyKey = get(table.IDEMPOTENCY_KEY)?.let(IdempotencyKey::of),
            metadata = get(table.METADATA).toMetadata(),
            createdAt = get(table.CREATED_AT)?.toInstant(),
            updatedAt = get(table.UPDATED_AT)?.toInstant(),
        )
    }

    private fun LimitRuleChangeType.toGenerated() = GeneratedLimitRuleChangeType.valueOf(name)

    private fun LimitRuleChangeStatus.toGenerated() = GeneratedLimitRuleChangeStatus.valueOf(name)

    private fun ChangeActor.toGenerated(): GeneratedLimitChangeActorType = when (this) {
        ChangeActor.System -> GeneratedLimitChangeActorType.SYSTEM
        is ChangeActor.PartyProfileActor -> GeneratedLimitChangeActorType.PARTY_PROFILE
    }

    private fun LimitRuleChangeRequest.actorPartyProfileId(): java.util.UUID? =
        (requestedBy as? ChangeActor.PartyProfileActor)?.partyProfileId?.value

    private fun LimitRuleChangeRequest.requestChannelId(): java.util.UUID? = requestActorContext.requestChannelId?.value

    private companion object {
        val PENDING_STATUSES = listOf(
            GeneratedLimitRuleChangeStatus.REQUESTED,
            GeneratedLimitRuleChangeStatus.APPROVED,
            GeneratedLimitRuleChangeStatus.PENDING_EFFECTIVE_DATE,
        )
        val DUE_STATUSES = listOf(
            GeneratedLimitRuleChangeStatus.APPROVED,
            GeneratedLimitRuleChangeStatus.PENDING_EFFECTIVE_DATE,
        )
    }
}

private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
