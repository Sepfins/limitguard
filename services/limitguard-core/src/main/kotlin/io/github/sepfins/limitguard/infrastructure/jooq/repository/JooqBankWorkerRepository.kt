package io.github.sepfins.limitguard.infrastructure.jooq.repository

import io.github.sepfins.limitguard.domain.common.BankWorkerId
import io.github.sepfins.limitguard.domain.common.ExternalReference
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.worker.BankWorker
import io.github.sepfins.limitguard.domain.worker.BranchCode
import io.github.sepfins.limitguard.domain.worker.DisplayName
import io.github.sepfins.limitguard.infrastructure.jooq.JooqPersistenceContext
import io.github.sepfins.limitguard.infrastructure.jooq.jooqField
import io.github.sepfins.limitguard.infrastructure.jooq.jooqOperation
import io.github.sepfins.limitguard.infrastructure.jooq.jooqTable
import io.github.sepfins.limitguard.infrastructure.jooq.toJsonb
import io.github.sepfins.limitguard.infrastructure.jooq.toMetadata
import io.github.sepfins.limitguard.port.persistence.BankWorkerRepository
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.SelectFieldOrAsterisk
import java.time.Instant
import java.util.UUID

class JooqBankWorkerRepository(
    private val context: JooqPersistenceContext,
) : BankWorkerRepository {
    override fun findById(tenantId: TenantId, id: BankWorkerId): BankWorker? =
        jooqOperation("find bank worker by id") {
            selectWorkers()
                .where(BankWorkerTable.tenantId.eq(tenantId.value))
                .and(BankWorkerTable.id.eq(id.value))
                .fetchOne { it.toDomain() }
        }

    override fun findByExternalReference(
        tenantId: TenantId,
        externalReference: ExternalReference,
    ): BankWorker? = jooqOperation("find bank worker by external reference") {
        selectWorkers()
            .where(BankWorkerTable.tenantId.eq(tenantId.value))
            .and(BankWorkerTable.externalReference.eq(externalReference.value))
            .fetchOne { it.toDomain() }
    }

    override fun insert(tenantId: TenantId, bankWorker: BankWorker): BankWorker =
        jooqOperation("insert bank worker") {
            require(bankWorker.tenantId == tenantId) { "bank worker tenant does not match repository tenant" }
            val insert = context.dsl()
                .insertInto(BankWorkerTable.table)
                .set(BankWorkerTable.id, bankWorker.id.value)
                .set(BankWorkerTable.tenantId, tenantId.value)
                .set(BankWorkerTable.externalReference, bankWorker.externalReference.value)
                .set(BankWorkerTable.displayName, bankWorker.displayName.value)
                .set(BankWorkerTable.branchCode, bankWorker.branchCode?.value)
                .set(BankWorkerTable.isActive, bankWorker.isActive)
                .set(BankWorkerTable.metadata, bankWorker.metadata.toJsonb())
            bankWorker.createdAt?.let { insert.set(BankWorkerTable.createdAt, it) }
            requireNotNull(insert.returning(BankWorkerTable.fields).fetchOne()).toDomain()
        }

    override fun update(tenantId: TenantId, bankWorker: BankWorker): BankWorker =
        jooqOperation("update bank worker") {
            require(bankWorker.tenantId == tenantId) { "bank worker tenant does not match repository tenant" }
            requireNotNull(
                context.dsl()
                    .update(BankWorkerTable.table)
                    .set(BankWorkerTable.externalReference, bankWorker.externalReference.value)
                    .set(BankWorkerTable.displayName, bankWorker.displayName.value)
                    .set(BankWorkerTable.branchCode, bankWorker.branchCode?.value)
                    .set(BankWorkerTable.isActive, bankWorker.isActive)
                    .set(BankWorkerTable.metadata, bankWorker.metadata.toJsonb())
                    .where(BankWorkerTable.tenantId.eq(tenantId.value))
                    .and(BankWorkerTable.id.eq(bankWorker.id.value))
                    .returning(BankWorkerTable.fields)
                    .fetchOne()
            ) { "bank worker not found: ${bankWorker.id.value}" }.toDomain()
        }

    private fun selectWorkers() = context.dsl().select(BankWorkerTable.fields).from(BankWorkerTable.table)

    private fun Record.toDomain(): BankWorker = BankWorker(
        id = BankWorkerId.of(requireNotNull(get(BankWorkerTable.id))),
        tenantId = TenantId.of(requireNotNull(get(BankWorkerTable.tenantId))),
        externalReference = ExternalReference.of(requireNotNull(get(BankWorkerTable.externalReference))),
        displayName = DisplayName.of(requireNotNull(get(BankWorkerTable.displayName))),
        branchCode = get(BankWorkerTable.branchCode)?.let(BranchCode::of),
        isActive = requireNotNull(get(BankWorkerTable.isActive)),
        metadata = get(BankWorkerTable.metadata).toMetadata(),
        createdAt = get(BankWorkerTable.createdAt),
    )
}

private object BankWorkerTable {
    const val NAME = "bank_worker"
    val table = jooqTable(NAME)
    val id: Field<UUID> = jooqField(NAME, "id", UUID::class.java)
    val tenantId: Field<UUID> = jooqField(NAME, "tenant_id", UUID::class.java)
    val externalReference: Field<String> = jooqField(NAME, "external_reference", String::class.java)
    val displayName: Field<String> = jooqField(NAME, "display_name", String::class.java)
    val branchCode: Field<String> = jooqField(NAME, "branch_code", String::class.java)
    val isActive: Field<Boolean> = jooqField(NAME, "is_active", Boolean::class.java)
    val metadata: Field<JSONB> = jooqField(NAME, "metadata", JSONB::class.java)
    val createdAt: Field<Instant> = jooqField(NAME, "created_at", Instant::class.java)
    val fields: List<SelectFieldOrAsterisk> =
        listOf(id, tenantId, externalReference, displayName, branchCode, isActive, metadata, createdAt)
}
