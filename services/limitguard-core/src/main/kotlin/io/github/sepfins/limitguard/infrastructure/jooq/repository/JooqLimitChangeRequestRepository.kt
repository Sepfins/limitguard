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
import io.github.sepfins.limitguard.infrastructure.jooq.eqPostgresEnum
import io.github.sepfins.limitguard.infrastructure.jooq.inPostgresEnums
import io.github.sepfins.limitguard.infrastructure.jooq.jooqOperation
import io.github.sepfins.limitguard.infrastructure.jooq.postgresEnum
import io.github.sepfins.limitguard.infrastructure.jooq.toJsonb
import io.github.sepfins.limitguard.infrastructure.jooq.toMetadata
import io.github.sepfins.limitguard.port.persistence.LimitChangeRequestRepository
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.SelectFieldOrAsterisk
import org.jooq.Table
import org.jooq.impl.DSL
import java.time.Instant
import java.util.UUID

class JooqLimitChangeRequestRepository(
    private val context: JooqPersistenceContext,
) : LimitChangeRequestRepository {
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
            .where(LimitChangeRequestTable.tenantId.eq(tenantId.value))
            .and(LimitChangeRequestTable.idempotencyKey.eq(key.value))
            .fetchOne { it.toDomain() }
    }

    override fun findByLimitRule(
        tenantId: TenantId,
        limitRuleId: LimitRuleId,
    ): List<LimitRuleChangeRequest> = jooqOperation("find limit change requests by rule") {
        selectRequests()
            .where(LimitChangeRequestTable.tenantId.eq(tenantId.value))
            .and(LimitChangeRequestTable.limitRuleId.eq(limitRuleId.value))
            .orderBy(LimitChangeRequestTable.createdAt, LimitChangeRequestTable.id)
            .fetch { it.toDomain() }
    }

    override fun findPendingForTarget(
        tenantId: TenantId,
        targetScopeHash: ScopeHash,
        at: Instant,
    ): List<LimitRuleChangeRequest> = jooqOperation("find pending limit changes for target") {
        selectRequests()
            .where(LimitChangeRequestTable.tenantId.eq(tenantId.value))
            .and(LimitChangeRequestTable.targetScopeHash.eq(targetScopeHash.value))
            .and(LimitChangeRequestTable.status.inPostgresEnums(PENDING_STATUSES, "limit_rule_change_status"))
            .and(LimitChangeRequestTable.expiresAt.isNull.or(LimitChangeRequestTable.expiresAt.gt(at)))
            .orderBy(LimitChangeRequestTable.createdAt, LimitChangeRequestTable.id)
            .fetch { it.toDomain() }
    }

    override fun lockTarget(tenantId: TenantId, targetScopeHash: ScopeHash) =
        jooqOperation("lock limit change target") {
            context.requireTransaction("locking a limit change target")
            val lockKey = "limit-change-target:${tenantId.value}:${targetScopeHash.value}"
            context.dsl().fetch("select pg_advisory_xact_lock(hashtextextended(?, 0))", lockKey)
            Unit
        }

    override fun claimDueForApplication(
        tenantId: TenantId,
        dueAt: Instant,
        limit: Int,
    ): List<LimitRuleChangeRequest> = jooqOperation("claim due limit change requests") {
        context.requireTransaction("claiming due limit change requests")
        require(limit > 0) { "limit must be greater than zero" }
        selectRequests()
            .where(LimitChangeRequestTable.tenantId.eq(tenantId.value))
            .and(LimitChangeRequestTable.status.inPostgresEnums(DUE_STATUSES, "limit_rule_change_status"))
            .and(LimitChangeRequestTable.effectiveAt.isNull.or(LimitChangeRequestTable.effectiveAt.le(dueAt)))
            .and(LimitChangeRequestTable.expiresAt.isNull.or(LimitChangeRequestTable.expiresAt.gt(dueAt)))
            .orderBy(LimitChangeRequestTable.createdAt, LimitChangeRequestTable.id)
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
            RequestActorContext.System ->
                LimitChangeRequestTable.requestedByActorType.eqPostgresEnum("SYSTEM", "limit_change_actor_type")
            is RequestActorContext.PartyProfile ->
                LimitChangeRequestTable.requestedByActorType.eqPostgresEnum(
                    "PARTY_PROFILE",
                    "limit_change_actor_type",
                )
                    .and(
                        LimitChangeRequestTable.requestedByPartyProfileId.eq(
                            requestActorContext.requesterPartyProfileId.value
                        )
                    )
                    .and(LimitChangeRequestTable.requestChannelId.eq(requestActorContext.requesterChannelId.value))
            is RequestActorContext.BankWorker ->
                LimitChangeRequestTable.requestedByActorType.eqPostgresEnum(
                    "BANK_WORKER",
                    "limit_change_actor_type",
                )
                    .and(LimitChangeRequestTable.requestedByBankWorkerId.eq(requestActorContext.bankWorkerId.value))
                    .and(LimitChangeRequestTable.requestChannelId.eq(requestActorContext.requesterChannelId.value))
            else -> error("unsupported request actor context: ${requestActorContext::class.qualifiedName}")
        }
        selectRequests()
            .where(LimitChangeRequestTable.tenantId.eq(tenantId.value))
            .and(condition)
            .orderBy(LimitChangeRequestTable.createdAt, LimitChangeRequestTable.id)
            .fetch { it.toDomain() }
    }

    override fun findByRequester(
        tenantId: TenantId,
        requester: PartyProfileId,
    ): List<LimitRuleChangeRequest> = jooqOperation("find limit changes by represented requester") {
        selectRequests()
            .where(LimitChangeRequestTable.tenantId.eq(tenantId.value))
            .and(LimitChangeRequestTable.requesterPartyProfileId.eq(requester.value))
            .orderBy(LimitChangeRequestTable.createdAt, LimitChangeRequestTable.id)
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
            .insertInto(LimitChangeRequestTable.table)
            .set(LimitChangeRequestTable.id, request.id.value)
            .set(LimitChangeRequestTable.tenantId, tenantId.value)
            .set(LimitChangeRequestTable.limitRuleId, request.limitRuleId?.value)
            .set(LimitChangeRequestTable.changeType, postgresEnum(request.changeType.name, "limit_rule_change_type"))
            .set(LimitChangeRequestTable.status, postgresEnum(request.status.name, "limit_rule_change_status"))
            .set(
                LimitChangeRequestTable.requestedByActorType,
                postgresEnum(request.actorTypeName(), "limit_change_actor_type"),
            )
            .set(LimitChangeRequestTable.requestedByPartyProfileId, request.actorPartyProfileId())
            .set(LimitChangeRequestTable.requestedByBankWorkerId, request.actorBankWorkerId())
            .set(LimitChangeRequestTable.requesterPartyProfileId, request.requesterPartyProfileId?.value)
            .set(LimitChangeRequestTable.authorityDelegationId, request.authorityDelegationId?.value)
            .set(LimitChangeRequestTable.requestChannelId, request.requestChannelId())
            .set(LimitChangeRequestTable.targetOwnerPartyProfileId, request.targetOwnerPartyProfileId.value)
            .set(LimitChangeRequestTable.targetMandateId, request.targetMandateId?.value)
            .set(LimitChangeRequestTable.targetScope, request.targetScope.toJsonb())
            .set(LimitChangeRequestTable.targetScopeHash, request.targetScopeHash.value)
            .set(LimitChangeRequestTable.beforeRule, request.beforeRule?.toJsonb())
            .set(LimitChangeRequestTable.requestedRule, request.requestedRule.toJsonb())
            .set(LimitChangeRequestTable.approvedRule, request.approvedRule?.toJsonb())
            .set(LimitChangeRequestTable.policyContext, request.policyContext.toJsonb())
            .set(LimitChangeRequestTable.effectiveAt, request.effectiveAt)
            .set(LimitChangeRequestTable.expiresAt, request.expiresAt)
            .set(LimitChangeRequestTable.decidedAt, request.decidedAt)
            .set(LimitChangeRequestTable.appliedAt, request.appliedAt)
            .set(LimitChangeRequestTable.cancelledAt, request.cancelledAt)
            .set(
                LimitChangeRequestTable.supersededByChangeRequestId,
                request.supersededByChangeRequestId?.value,
            )
            .set(LimitChangeRequestTable.decisionReason, request.decisionReason)
            .set(LimitChangeRequestTable.idempotencyKey, request.idempotencyKey?.value)
            .set(LimitChangeRequestTable.metadata, request.metadata.toJsonb())
        request.createdAt?.let { insert.set(LimitChangeRequestTable.createdAt, it) }
        request.updatedAt?.let { insert.set(LimitChangeRequestTable.updatedAt, it) }
        requireNotNull(insert.returning(LimitChangeRequestTable.fields).fetchOne()).toDomain()
    }

    override fun update(
        tenantId: TenantId,
        request: LimitRuleChangeRequest,
    ): LimitRuleChangeRequest = jooqOperation("update limit change request") {
        require(request.tenantId == tenantId) { "limit change request tenant does not match repository tenant" }
        val update = context.dsl()
            .update(LimitChangeRequestTable.table)
            .set(LimitChangeRequestTable.limitRuleId, request.limitRuleId?.value)
            .set(LimitChangeRequestTable.status, postgresEnum(request.status.name, "limit_rule_change_status"))
            .set(LimitChangeRequestTable.targetMandateId, request.targetMandateId?.value)
            .set(LimitChangeRequestTable.targetScope, request.targetScope.toJsonb())
            .set(LimitChangeRequestTable.targetScopeHash, request.targetScopeHash.value)
            .set(LimitChangeRequestTable.beforeRule, request.beforeRule?.toJsonb())
            .set(LimitChangeRequestTable.requestedRule, request.requestedRule.toJsonb())
            .set(LimitChangeRequestTable.approvedRule, request.approvedRule?.toJsonb())
            .set(LimitChangeRequestTable.policyContext, request.policyContext.toJsonb())
            .set(LimitChangeRequestTable.effectiveAt, request.effectiveAt)
            .set(LimitChangeRequestTable.expiresAt, request.expiresAt)
            .set(LimitChangeRequestTable.decidedAt, request.decidedAt)
            .set(LimitChangeRequestTable.appliedAt, request.appliedAt)
            .set(LimitChangeRequestTable.cancelledAt, request.cancelledAt)
            .set(
                LimitChangeRequestTable.supersededByChangeRequestId,
                request.supersededByChangeRequestId?.value,
            )
            .set(LimitChangeRequestTable.decisionReason, request.decisionReason)
            .set(LimitChangeRequestTable.metadata, request.metadata.toJsonb())
        request.updatedAt?.let { update.set(LimitChangeRequestTable.updatedAt, it) }
        requireNotNull(
            update.where(LimitChangeRequestTable.tenantId.eq(tenantId.value))
                .and(LimitChangeRequestTable.id.eq(request.id.value))
                .returning(LimitChangeRequestTable.fields)
                .fetchOne()
        ) { "limit change request not found: ${request.id.value}" }.toDomain()
    }

    private fun findById(
        tenantId: TenantId,
        id: LimitChangeRequestId,
        forUpdate: Boolean,
    ): LimitRuleChangeRequest? = jooqOperation("find limit change request by id") {
        val query = selectRequests()
            .where(LimitChangeRequestTable.tenantId.eq(tenantId.value))
            .and(LimitChangeRequestTable.id.eq(id.value))
        if (forUpdate) query.forUpdate().fetchOne { it.toDomain() } else query.fetchOne { it.toDomain() }
    }

    private fun selectRequests() = context.dsl()
        .select(LimitChangeRequestTable.fields)
        .from(LimitChangeRequestTable.table)

    private fun Record.toDomain(): LimitRuleChangeRequest {
        val requestedBy = when (val actorType = requireNotNull(get(LimitChangeRequestTable.requestedByActorType))) {
            "SYSTEM" -> ChangeActor.System
            "PARTY_PROFILE" -> ChangeActor.PartyProfileActor(
                PartyProfileId.of(requireNotNull(get(LimitChangeRequestTable.requestedByPartyProfileId)))
            )
            "BANK_WORKER" -> ChangeActor.BankWorkerActor(
                BankWorkerId.of(requireNotNull(get(LimitChangeRequestTable.requestedByBankWorkerId)))
            )
            else -> error("unsupported limit change actor type: $actorType")
        }
        val requestActorContext = when (requestedBy) {
            ChangeActor.System -> RequestActorContext.System
            is ChangeActor.PartyProfileActor -> RequestActorContext.PartyProfile(
                requesterPartyProfileId = requestedBy.partyProfileId,
                requesterChannelId = ChannelId.of(requireNotNull(get(LimitChangeRequestTable.requestChannelId))),
            )
            is ChangeActor.BankWorkerActor -> RequestActorContext.BankWorker(
                bankWorkerId = requestedBy.bankWorkerId,
                requesterChannelId = ChannelId.of(requireNotNull(get(LimitChangeRequestTable.requestChannelId))),
            )
        }

        return LimitRuleChangeRequest(
            id = LimitChangeRequestId.of(requireNotNull(get(LimitChangeRequestTable.id))),
            tenantId = TenantId.of(requireNotNull(get(LimitChangeRequestTable.tenantId))),
            changeType = LimitRuleChangeType.valueOf(requireNotNull(get(LimitChangeRequestTable.changeType))),
            requestedBy = requestedBy,
            targetOwnerPartyProfileId = PartyProfileId.of(
                requireNotNull(get(LimitChangeRequestTable.targetOwnerPartyProfileId))
            ),
            targetScope = get(LimitChangeRequestTable.targetScope).toMetadata(),
            targetScopeHash = ScopeHash.of(requireNotNull(get(LimitChangeRequestTable.targetScopeHash))),
            requestedRule = get(LimitChangeRequestTable.requestedRule).toMetadata(),
            status = LimitRuleChangeStatus.valueOf(requireNotNull(get(LimitChangeRequestTable.status))),
            limitRuleId = get(LimitChangeRequestTable.limitRuleId)?.let(LimitRuleId::of),
            authorityDelegationId = get(LimitChangeRequestTable.authorityDelegationId)
                ?.let(PartyProfileDelegationId::of),
            requestActorContext = requestActorContext,
            requesterPartyProfileId = get(LimitChangeRequestTable.requesterPartyProfileId)?.let(PartyProfileId::of),
            targetMandateId = get(LimitChangeRequestTable.targetMandateId)?.let(MandateId::of),
            beforeRule = get(LimitChangeRequestTable.beforeRule)?.toMetadata(),
            approvedRule = get(LimitChangeRequestTable.approvedRule)?.toMetadata(),
            policyContext = get(LimitChangeRequestTable.policyContext).toMetadata(),
            effectiveAt = get(LimitChangeRequestTable.effectiveAt),
            expiresAt = get(LimitChangeRequestTable.expiresAt),
            decidedAt = get(LimitChangeRequestTable.decidedAt),
            appliedAt = get(LimitChangeRequestTable.appliedAt),
            cancelledAt = get(LimitChangeRequestTable.cancelledAt),
            supersededByChangeRequestId = get(LimitChangeRequestTable.supersededByChangeRequestId)
                ?.let(LimitChangeRequestId::of),
            decisionReason = get(LimitChangeRequestTable.decisionReason),
            idempotencyKey = get(LimitChangeRequestTable.idempotencyKey)?.let(IdempotencyKey::of),
            metadata = get(LimitChangeRequestTable.metadata).toMetadata(),
            createdAt = get(LimitChangeRequestTable.createdAt),
            updatedAt = get(LimitChangeRequestTable.updatedAt),
        )
    }

    private fun LimitRuleChangeRequest.actorTypeName(): String = when (requestedBy) {
        ChangeActor.System -> "SYSTEM"
        is ChangeActor.PartyProfileActor -> "PARTY_PROFILE"
        is ChangeActor.BankWorkerActor -> "BANK_WORKER"
    }

    private fun LimitRuleChangeRequest.actorPartyProfileId(): UUID? =
        (requestedBy as? ChangeActor.PartyProfileActor)?.partyProfileId?.value

    private fun LimitRuleChangeRequest.actorBankWorkerId(): UUID? =
        (requestedBy as? ChangeActor.BankWorkerActor)?.bankWorkerId?.value

    private fun LimitRuleChangeRequest.requestChannelId(): UUID? = when (val context = requestActorContext) {
        RequestActorContext.System -> null
        is RequestActorContext.PartyProfile -> context.requesterChannelId.value
        is RequestActorContext.BankWorker -> context.requesterChannelId.value
        else -> error("unsupported request actor context: ${context::class.qualifiedName}")
    }

    private companion object {
        val PENDING_STATUSES = listOf("REQUESTED", "APPROVED", "PENDING_EFFECTIVE_DATE")
        val DUE_STATUSES = listOf("APPROVED", "PENDING_EFFECTIVE_DATE")
    }
}

private object LimitChangeRequestTable {
    val table: Table<Record> = DSL.table(DSL.name("limit_rule_change_request"))
    val id: Field<UUID> = field("id", UUID::class.java)
    val tenantId: Field<UUID> = field("tenant_id", UUID::class.java)
    val limitRuleId: Field<UUID> = field("limit_rule_id", UUID::class.java)
    val changeType: Field<String> = field("change_type", String::class.java)
    val status: Field<String> = field("status", String::class.java)
    val requestedByActorType: Field<String> = field("requested_by_actor_type", String::class.java)
    val requestedByPartyProfileId: Field<UUID> = field("requested_by_party_profile_id", UUID::class.java)
    val requestedByBankWorkerId: Field<UUID> = field("requested_by_bank_worker_id", UUID::class.java)
    val requesterPartyProfileId: Field<UUID> = field("requester_party_profile_id", UUID::class.java)
    val authorityDelegationId: Field<UUID> = field("authority_delegation_id", UUID::class.java)
    val requestChannelId: Field<UUID> = field("request_channel_id", UUID::class.java)
    val targetOwnerPartyProfileId: Field<UUID> = field("target_owner_party_profile_id", UUID::class.java)
    val targetMandateId: Field<UUID> = field("target_mandate_id", UUID::class.java)
    val targetScope: Field<JSONB> = field("target_scope", JSONB::class.java)
    val targetScopeHash: Field<String> = field("target_scope_hash", String::class.java)
    val beforeRule: Field<JSONB> = field("before_rule", JSONB::class.java)
    val requestedRule: Field<JSONB> = field("requested_rule", JSONB::class.java)
    val approvedRule: Field<JSONB> = field("approved_rule", JSONB::class.java)
    val policyContext: Field<JSONB> = field("policy_context", JSONB::class.java)
    val effectiveAt: Field<Instant> = field("effective_at", Instant::class.java)
    val expiresAt: Field<Instant> = field("expires_at", Instant::class.java)
    val decidedAt: Field<Instant> = field("decided_at", Instant::class.java)
    val appliedAt: Field<Instant> = field("applied_at", Instant::class.java)
    val cancelledAt: Field<Instant> = field("cancelled_at", Instant::class.java)
    val supersededByChangeRequestId: Field<UUID> = field("superseded_by_change_request_id", UUID::class.java)
    val decisionReason: Field<String> = field("decision_reason", String::class.java)
    val idempotencyKey: Field<String> = field("idempotency_key", String::class.java)
    val metadata: Field<JSONB> = field("metadata", JSONB::class.java)
    val createdAt: Field<Instant> = field("created_at", Instant::class.java)
    val updatedAt: Field<Instant> = field("updated_at", Instant::class.java)
    val fields: List<SelectFieldOrAsterisk> = listOf(
        id,
        tenantId,
        limitRuleId,
        changeType,
        status,
        requestedByActorType,
        requestedByPartyProfileId,
        requestedByBankWorkerId,
        requesterPartyProfileId,
        authorityDelegationId,
        requestChannelId,
        targetOwnerPartyProfileId,
        targetMandateId,
        targetScope,
        targetScopeHash,
        beforeRule,
        requestedRule,
        approvedRule,
        policyContext,
        effectiveAt,
        expiresAt,
        decidedAt,
        appliedAt,
        cancelledAt,
        supersededByChangeRequestId,
        decisionReason,
        idempotencyKey,
        metadata,
        createdAt,
        updatedAt,
    )

    private fun <T> field(name: String, type: Class<T>): Field<T> =
        DSL.field(DSL.name("limit_rule_change_request", name), type)
}
