package io.github.sepfins.limitguard.infrastructure.jooq.repository

import io.github.sepfins.limitguard.domain.common.BankWorkerId
import io.github.sepfins.limitguard.domain.common.ExternalReference
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.worker.BankWorker
import io.github.sepfins.limitguard.domain.worker.BranchCode
import io.github.sepfins.limitguard.domain.worker.DisplayName
import io.github.sepfins.limitguard.infrastructure.jooq.JooqPersistenceContext
import io.github.sepfins.limitguard.infrastructure.jooq.jooqOperation
import io.github.sepfins.limitguard.infrastructure.jooq.toJsonb
import io.github.sepfins.limitguard.infrastructure.jooq.toMetadata
import io.github.sepfins.limitguard.infrastructure.jooq.generated.tables.BankWorker as GeneratedBankWorkerTable
import io.github.sepfins.limitguard.port.persistence.BankWorkerRepository
import org.jooq.Record
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class JooqBankWorkerRepository(
    private val context: JooqPersistenceContext,
) : BankWorkerRepository {
    private val table = GeneratedBankWorkerTable.BANK_WORKER

    override fun findById(tenantId: TenantId, id: BankWorkerId): BankWorker? =
        jooqOperation("find bank worker by id") {
            selectWorkers()
                .where(table.TENANT_ID.eq(tenantId.value))
                .and(table.ID.eq(id.value))
                .fetchOne { it.toDomain() }
        }

    override fun findByExternalReference(
        tenantId: TenantId,
        externalReference: ExternalReference,
    ): BankWorker? = jooqOperation("find bank worker by external reference") {
        selectWorkers()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.EXTERNAL_REFERENCE.eq(externalReference.value))
            .fetchOne { it.toDomain() }
    }

    override fun insert(tenantId: TenantId, bankWorker: BankWorker): BankWorker =
        jooqOperation("insert bank worker") {
            require(bankWorker.tenantId == tenantId) { "bank worker tenant does not match repository tenant" }
            val insert = context.dsl()
                .insertInto(table)
                .set(table.ID, bankWorker.id.value)
                .set(table.TENANT_ID, tenantId.value)
                .set(table.EXTERNAL_REFERENCE, bankWorker.externalReference.value)
                .set(table.DISPLAY_NAME, bankWorker.displayName.value)
                .set(table.BRANCH_CODE, bankWorker.branchCode?.value)
                .set(table.IS_ACTIVE, bankWorker.isActive)
                .set(table.METADATA, bankWorker.metadata.toJsonb())
            bankWorker.createdAt?.let {
                insert.set(table.CREATED_AT, it.toOffsetDateTime())
            }
            requireNotNull(insert.returning(table.fields().toList()).fetchOne()).toDomain()
        }

    override fun update(tenantId: TenantId, bankWorker: BankWorker): BankWorker =
        jooqOperation("update bank worker") {
            require(bankWorker.tenantId == tenantId) { "bank worker tenant does not match repository tenant" }
            requireNotNull(
                context.dsl()
                    .update(table)
                    .set(table.EXTERNAL_REFERENCE, bankWorker.externalReference.value)
                    .set(table.DISPLAY_NAME, bankWorker.displayName.value)
                    .set(table.BRANCH_CODE, bankWorker.branchCode?.value)
                    .set(table.IS_ACTIVE, bankWorker.isActive)
                    .set(table.METADATA, bankWorker.metadata.toJsonb())
                    .where(table.TENANT_ID.eq(tenantId.value))
                    .and(table.ID.eq(bankWorker.id.value))
                    .returning(table.fields().toList())
                    .fetchOne()
            ) { "bank worker not found: ${bankWorker.id.value}" }.toDomain()
        }

    private fun selectWorkers() = context.dsl().select(
        table.ID,
        table.TENANT_ID,
        table.EXTERNAL_REFERENCE,
        table.DISPLAY_NAME,
        table.BRANCH_CODE,
        table.IS_ACTIVE,
        table.METADATA,
        table.CREATED_AT,
    ).from(table)

    private fun Record.toDomain(): BankWorker = BankWorker(
        id = BankWorkerId.of(requireNotNull(get(table.ID))),
        tenantId = TenantId.of(requireNotNull(get(table.TENANT_ID))),
        externalReference = ExternalReference.of(
            requireNotNull(get(table.EXTERNAL_REFERENCE))
        ),
        displayName = DisplayName.of(requireNotNull(get(table.DISPLAY_NAME))),
        branchCode = get(table.BRANCH_CODE)?.let(BranchCode::of),
        isActive = requireNotNull(get(table.IS_ACTIVE)),
        metadata = get(table.METADATA).toMetadata(),
        createdAt = get(table.CREATED_AT)?.toInstant(),
    )

    private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
}
