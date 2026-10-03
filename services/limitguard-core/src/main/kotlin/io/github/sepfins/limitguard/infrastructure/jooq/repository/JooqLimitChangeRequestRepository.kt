package io.github.sepfins.limitguard.infrastructure.jooq.repository

import io.github.sepfins.limitguard.domain.change.ChangeActor
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeStatus
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeType
import io.github.sepfins.limitguard.domain.change.RequestActorContext
import io.github.sepfins.limitguard.domain.change.ScopeHash
import io.github.sepfins.limitguard.domain.common.BankWorkerId
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.MandateId
import io.github.sepfins.limitguard.domain.common.PartyProfileDelegationId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.infrastructure.jooq.JooqPersistenceContext
import io.github.sepfins.limitguard.infrastructure.jooq.jooqOperation
import io.github.sepfins.limitguard.infrastructure.jooq.toJsonb
import io.github.sepfins.limitguard.infrastructure.jooq.toMetadata
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.LimitChangeActorType as GeneratedLimitChangeActorType
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.LimitRuleChangeStatus as GeneratedLimitRuleChangeStatus
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.LimitRuleChangeType as GeneratedLimitRuleChangeType
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

    override fun findByLimitRule(
        tenantId: TenantId,
        limitRuleId: LimitRuleId,
    ): List<LimitRuleChangeRequest> = jooqOperation("find limit change requests by rule") {
        selectRequests()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.LIMIT_RULE_ID.eq(limitRuleId.value))
            .orderBy(table.CREATED_AT, table.ID)
            .fetch { it.toDomain() }
    }

    override fun findPendingForTarget(
        tenantId: TenantId,
        targetScopeHash: ScopeHash,
        at: Instant,
    ): List<LimitRuleChangeRequest> = jooqOperation("find pending limit changes for target") {
        val timestamp = at.toOffsetDateTime()
        selectRequests()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.TARGET_SCOPE_HASH.eq(targetScopeHash.value))
            .and(table.STATUS.`in`(PENDING_STATUSES))
            .and(table.EXPIRES_AT.isNull.or(table.EXPIRES_AT.gt(timestamp)))
            .orderBy(table.CREATED_AT, table.ID)
            .fetch { it.toDomain() }
    }

    override fun lockTarget(tenantId: TenantId, targetScopeHash: ScopeHash) =
        jooqOperation("lock limit change target") {
            context.requireTransaction("locking a limit change target")
            val lockKey = "limit-change-target:${tenantId.value}:${targetScopeHash.value}"
            context.dsl().fetch("select pg_advisory_xact_lock(hashtextextended(?, 0))", lockKey)
            Unit
        }

    override fun tryLockTarget(tenantId: TenantId, targetScopeHash: ScopeHash): Boolean =
        jooqOperation("try lock limit change target") {
            context.requireTransaction("locking a limit change target")
            val lockKey = "limit-change-target:${tenantId.value}:${targetScopeHash.value}"
            context.dsl().fetchValue("select pg_try_advisory_xact_lock(hashtextextended(?, 0))", lockKey) == true
        }

    override fun findDueForApplication(
        tenantId: TenantId,
        dueAt: Instant,
        limit: Int,
    ): List<LimitRuleChangeRequest> = jooqOperation("discover due limit change requests") {
        require(limit > 0) { "limit must be greater than zero" }
        selectRequests().where(dueCondition(tenantId, dueAt))
            .orderBy(table.CREATED_AT, table.ID).limit(limit).fetch { it.toDomain() }
    }

    override fun claimForApplication(
        tenantId: TenantId,
        id: LimitChangeRequestId,
        dueAt: Instant,
    ): LimitRuleChangeRequest? = jooqOperation("claim due limit change request") {
        context.requireTransaction("claiming a due limit change request")
        selectRequests().where(dueCondition(tenantId, dueAt)).and(table.ID.eq(id.value))
            .forUpdate().skipLocked().fetchOne { it.toDomain() }
    }

    override fun claimDueForApplication(
        tenantId: TenantId,
        dueAt: Instant,
        limit: Int,
    ): List<LimitRuleChangeRequest> = jooqOperation("claim due limit change requests") {
        context.requireTransaction("claiming due limit change requests")
        require(limit > 0) { "limit must be greater than zero" }
        selectRequests()
            .where(dueCondition(tenantId, dueAt))
            .orderBy(table.CREATED_AT, table.ID)
            .limit(limit)
            .forUpdate()
            .skipLocked()
            .fetch { it.toDomain() }
    }

    override fun findByRequesterContext(
        tenantId: TenantId,
        requestActorContext: RequestActorContext,
    ): List<LimitRuleChangeRequest> = jooqOperation("find limit changes by requester context") {
        val condition = when (requestActorContext) {
            RequestActorContext.System -> table.REQUESTED_BY_ACTOR_TYPE.eq(GeneratedLimitChangeActorType.SYSTEM)
            is RequestActorContext.PartyProfile -> table.REQUESTED_BY_ACTOR_TYPE
                .eq(GeneratedLimitChangeActorType.PARTY_PROFILE)
                .and(table.REQUESTED_BY_PARTY_PROFILE_ID.eq(requestActorContext.requesterPartyProfileId.value))
                .and(table.REQUEST_CHANNEL_ID.eq(requestActorContext.requesterChannelId.value))
            is RequestActorContext.BankWorker -> table.REQUESTED_BY_ACTOR_TYPE
                .eq(GeneratedLimitChangeActorType.BANK_WORKER)
                .and(table.REQUESTED_BY_BANK_WORKER_ID.eq(requestActorContext.bankWorkerId.value))
                .and(table.REQUEST_CHANNEL_ID.eq(requestActorContext.requesterChannelId.value))
            else -> error("unsupported request actor context: ${requestActorContext::class.qualifiedName}")
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
            .set(table.REQUESTED_BY_BANK_WORKER_ID, request.actorBankWorkerId())
            .set(table.REQUESTER_PARTY_PROFILE_ID, request.requesterPartyProfileId?.value)
            .set(table.AUTHORITY_DELEGATION_ID, request.authorityDelegationId?.value)
            .set(table.REQUEST_CHANNEL_ID, request.requestChannelId())
            .set(table.TARGET_OWNER_PARTY_PROFILE_ID, request.targetOwnerPartyProfileId.value)
            .set(table.TARGET_MANDATE_ID, request.targetMandateId?.value)
            .set(table.TARGET_SCOPE, request.targetScope.toJsonb())
            .set(table.TARGET_SCOPE_HASH, request.targetScopeHash.value)
            .set(table.BEFORE_RULE, request.beforeRule?.toJsonb())
            .set(table.REQUESTED_RULE, request.requestedRule.toJsonb())
            .set(table.APPROVED_RULE, request.approvedRule?.toJsonb())
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
        requireNotNull(insert.returning(table.fields().toList()).fetchOne()).toDomain()
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
            .set(table.TARGET_MANDATE_ID, request.targetMandateId?.value)
            .set(table.TARGET_SCOPE, request.targetScope.toJsonb())
            .set(table.TARGET_SCOPE_HASH, request.targetScopeHash.value)
            .set(table.BEFORE_RULE, request.beforeRule?.toJsonb())
            .set(table.REQUESTED_RULE, request.requestedRule.toJsonb())
            .set(table.APPROVED_RULE, request.approvedRule?.toJsonb())
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
        requireNotNull(
            update.where(table.TENANT_ID.eq(tenantId.value))
                .and(table.ID.eq(request.id.value))
                .returning(table.fields().toList())
                .fetchOne()
        ) { "limit change request not found: ${request.id.value}" }.toDomain()
    }

    private fun findById(
        tenantId: TenantId,
        id: LimitChangeRequestId,
        forUpdate: Boolean,
    ): LimitRuleChangeRequest? = jooqOperation("find limit change request by id") {
        val query = selectRequests()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.ID.eq(id.value))
        if (forUpdate) query.forUpdate().fetchOne { it.toDomain() } else query.fetchOne { it.toDomain() }
    }

    private fun selectRequests() = context.dsl()
        .select(table.fields().toList())
        .from(table)

    private fun dueCondition(tenantId: TenantId, dueAt: Instant) =
        table.TENANT_ID.eq(tenantId.value)
            .and(table.STATUS.`in`(DUE_STATUSES))
            .and(table.EFFECTIVE_AT.isNull.or(table.EFFECTIVE_AT.le(dueAt.toOffsetDateTime())))
            .and(table.EXPIRES_AT.isNull.or(table.EXPIRES_AT.gt(dueAt.toOffsetDateTime())))

    private fun Record.toDomain(): LimitRuleChangeRequest {
        val requestedBy = when (requireNotNull(get(table.REQUESTED_BY_ACTOR_TYPE))) {
            GeneratedLimitChangeActorType.SYSTEM -> ChangeActor.System
            GeneratedLimitChangeActorType.PARTY_PROFILE -> ChangeActor.PartyProfileActor(
                PartyProfileId.of(requireNotNull(get(table.REQUESTED_BY_PARTY_PROFILE_ID)))
            )
            GeneratedLimitChangeActorType.BANK_WORKER -> ChangeActor.BankWorkerActor(
                BankWorkerId.of(requireNotNull(get(table.REQUESTED_BY_BANK_WORKER_ID)))
            )
        }
        val requestActorContext = when (requestedBy) {
            ChangeActor.System -> RequestActorContext.System
            is ChangeActor.PartyProfileActor -> RequestActorContext.PartyProfile(
                requesterPartyProfileId = requestedBy.partyProfileId,
                requesterChannelId = ChannelId.of(requireNotNull(get(table.REQUEST_CHANNEL_ID))),
            )
            is ChangeActor.BankWorkerActor -> RequestActorContext.BankWorker(
                bankWorkerId = requestedBy.bankWorkerId,
                requesterChannelId = ChannelId.of(requireNotNull(get(table.REQUEST_CHANNEL_ID))),
            )
        }

        return LimitRuleChangeRequest(
            id = LimitChangeRequestId.of(requireNotNull(get(table.ID))),
            tenantId = TenantId.of(requireNotNull(get(table.TENANT_ID))),
            changeType = LimitRuleChangeType.valueOf(requireNotNull(get(table.CHANGE_TYPE)).name),
            requestedBy = requestedBy,
            targetOwnerPartyProfileId = PartyProfileId.of(requireNotNull(get(table.TARGET_OWNER_PARTY_PROFILE_ID))),
            targetScope = get(table.TARGET_SCOPE).toMetadata(),
            targetScopeHash = ScopeHash.of(requireNotNull(get(table.TARGET_SCOPE_HASH))),
            requestedRule = get(table.REQUESTED_RULE).toMetadata(),
            status = LimitRuleChangeStatus.valueOf(requireNotNull(get(table.STATUS)).name),
            limitRuleId = get(table.LIMIT_RULE_ID)?.let(LimitRuleId::of),
            authorityDelegationId = get(table.AUTHORITY_DELEGATION_ID)?.let(PartyProfileDelegationId::of),
            requestActorContext = requestActorContext,
            requesterPartyProfileId = get(table.REQUESTER_PARTY_PROFILE_ID)?.let(PartyProfileId::of),
            targetMandateId = get(table.TARGET_MANDATE_ID)?.let(MandateId::of),
            beforeRule = get(table.BEFORE_RULE)?.toMetadata(),
            approvedRule = get(table.APPROVED_RULE)?.toMetadata(),
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
        is ChangeActor.BankWorkerActor -> GeneratedLimitChangeActorType.BANK_WORKER
    }

    private fun LimitRuleChangeRequest.actorPartyProfileId(): java.util.UUID? =
        (requestedBy as? ChangeActor.PartyProfileActor)?.partyProfileId?.value

    private fun LimitRuleChangeRequest.actorBankWorkerId(): java.util.UUID? =
        (requestedBy as? ChangeActor.BankWorkerActor)?.bankWorkerId?.value

    private fun LimitRuleChangeRequest.requestChannelId(): java.util.UUID? = when (val context = requestActorContext) {
        RequestActorContext.System -> null
        is RequestActorContext.PartyProfile -> context.requesterChannelId.value
        is RequestActorContext.BankWorker -> context.requesterChannelId.value
        else -> error("unsupported request actor context: ${context::class.qualifiedName}")
    }

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
