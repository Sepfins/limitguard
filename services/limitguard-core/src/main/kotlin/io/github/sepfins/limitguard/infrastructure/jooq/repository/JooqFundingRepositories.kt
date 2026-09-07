package io.github.sepfins.limitguard.infrastructure.jooq.repository

import io.github.sepfins.limitguard.domain.common.ExternalReference
import io.github.sepfins.limitguard.domain.common.FundingSourceGroupId
import io.github.sepfins.limitguard.domain.common.FundingSourceId
import io.github.sepfins.limitguard.domain.common.FundingSourceOwnerId
import io.github.sepfins.limitguard.domain.common.FundingSourceProductId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.funding.FundingSource
import io.github.sepfins.limitguard.domain.funding.FundingSourceCategory
import io.github.sepfins.limitguard.domain.funding.FundingSourceGroup
import io.github.sepfins.limitguard.domain.funding.FundingSourceGroupCode
import io.github.sepfins.limitguard.domain.funding.FundingSourceGroupMember
import io.github.sepfins.limitguard.domain.funding.FundingSourceGroupName
import io.github.sepfins.limitguard.domain.funding.FundingSourceOwner
import io.github.sepfins.limitguard.domain.funding.FundingSourceOwnerRole
import io.github.sepfins.limitguard.domain.funding.FundingSourceProduct
import io.github.sepfins.limitguard.domain.funding.FundingSourceProductCode
import io.github.sepfins.limitguard.domain.funding.FundingSourceProductName
import io.github.sepfins.limitguard.domain.funding.ProductNodeType
import io.github.sepfins.limitguard.infrastructure.jooq.JooqPersistenceContext
import io.github.sepfins.limitguard.infrastructure.jooq.eqPostgresEnum
import io.github.sepfins.limitguard.infrastructure.jooq.jooqField
import io.github.sepfins.limitguard.infrastructure.jooq.jooqOperation
import io.github.sepfins.limitguard.infrastructure.jooq.jooqTable
import io.github.sepfins.limitguard.infrastructure.jooq.postgresEnum
import io.github.sepfins.limitguard.infrastructure.jooq.toJsonb
import io.github.sepfins.limitguard.infrastructure.jooq.toMetadata
import io.github.sepfins.limitguard.port.persistence.FundingSourceGroupMemberRepository
import io.github.sepfins.limitguard.port.persistence.FundingSourceGroupRepository
import io.github.sepfins.limitguard.port.persistence.FundingSourceOwnerRepository
import io.github.sepfins.limitguard.port.persistence.FundingSourceProductRepository
import io.github.sepfins.limitguard.port.persistence.FundingSourceRepository
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.SelectFieldOrAsterisk
import java.time.Instant
import java.util.UUID

class JooqFundingSourceProductRepository(
    private val context: JooqPersistenceContext,
) : FundingSourceProductRepository {
    override fun findActive(tenantId: TenantId): List<FundingSourceProduct> =
        jooqOperation("find active funding source products") {
            selectProducts()
                .where(FundingSourceProductTable.tenantId.eq(tenantId.value))
                .and(FundingSourceProductTable.isActive.isTrue)
                .orderBy(FundingSourceProductTable.code, FundingSourceProductTable.id)
                .fetch { it.toProduct() }
        }

    override fun findByParent(
        tenantId: TenantId,
        parentId: FundingSourceProductId,
    ): List<FundingSourceProduct> = jooqOperation("find funding source products by parent") {
        selectProducts()
            .where(FundingSourceProductTable.tenantId.eq(tenantId.value))
            .and(FundingSourceProductTable.parentId.eq(parentId.value))
            .orderBy(FundingSourceProductTable.code, FundingSourceProductTable.id)
            .fetch { it.toProduct() }
    }

    override fun findByAccountCategory(
        tenantId: TenantId,
        accountCategory: FundingSourceCategory,
    ): List<FundingSourceProduct> = jooqOperation("find funding source products by category") {
        selectProducts()
            .where(FundingSourceProductTable.tenantId.eq(tenantId.value))
            .and(
                FundingSourceProductTable.accountCategory.eqPostgresEnum(
                    accountCategory.name,
                    "funding_source_category",
                )
            )
            .orderBy(FundingSourceProductTable.code, FundingSourceProductTable.id)
            .fetch { it.toProduct() }
    }

    override fun findByCode(
        tenantId: TenantId,
        code: FundingSourceProductCode,
    ): FundingSourceProduct? = jooqOperation("find funding source product by code") {
        selectProducts()
            .where(FundingSourceProductTable.tenantId.eq(tenantId.value))
            .and(FundingSourceProductTable.code.eq(code.value))
            .fetchOne { it.toProduct() }
    }

    override fun findById(tenantId: TenantId, id: FundingSourceProductId): FundingSourceProduct? =
        jooqOperation("find funding source product by id") {
            selectProducts()
                .where(FundingSourceProductTable.tenantId.eq(tenantId.value))
                .and(FundingSourceProductTable.id.eq(id.value))
                .fetchOne { it.toProduct() }
        }

    override fun insert(tenantId: TenantId, product: FundingSourceProduct): FundingSourceProduct =
        jooqOperation("insert funding source product") {
            require(product.tenantId == tenantId) { "funding source product tenant does not match repository tenant" }
            val insert = context.dsl()
                .insertInto(FundingSourceProductTable.table)
                .set(FundingSourceProductTable.id, product.id.value)
                .set(FundingSourceProductTable.tenantId, tenantId.value)
                .set(FundingSourceProductTable.parentId, product.parentId?.value)
                .set(
                    FundingSourceProductTable.accountCategory,
                    postgresEnum(product.accountCategory.name, "funding_source_category"),
                )
                .set(FundingSourceProductTable.nodeType, postgresEnum(product.nodeType.name, "product_node_type"))
                .set(FundingSourceProductTable.code, product.code.value)
                .set(FundingSourceProductTable.name, product.name.value)
                .set(FundingSourceProductTable.isLeaf, product.isLeaf)
                .set(FundingSourceProductTable.isActive, product.isActive)
                .set(FundingSourceProductTable.metadata, product.metadata.toJsonb())
            product.createdAt?.let { insert.set(FundingSourceProductTable.createdAt, it) }
            requireNotNull(insert.returning(FundingSourceProductTable.fields).fetchOne()).toProduct()
        }

    override fun update(tenantId: TenantId, product: FundingSourceProduct): FundingSourceProduct =
        jooqOperation("update funding source product") {
            require(product.tenantId == tenantId) { "funding source product tenant does not match repository tenant" }
            requireNotNull(
                context.dsl()
                    .update(FundingSourceProductTable.table)
                    .set(FundingSourceProductTable.parentId, product.parentId?.value)
                    .set(
                        FundingSourceProductTable.accountCategory,
                        postgresEnum(product.accountCategory.name, "funding_source_category"),
                    )
                    .set(
                        FundingSourceProductTable.nodeType,
                        postgresEnum(product.nodeType.name, "product_node_type"),
                    )
                    .set(FundingSourceProductTable.code, product.code.value)
                    .set(FundingSourceProductTable.name, product.name.value)
                    .set(FundingSourceProductTable.isLeaf, product.isLeaf)
                    .set(FundingSourceProductTable.isActive, product.isActive)
                    .set(FundingSourceProductTable.metadata, product.metadata.toJsonb())
                    .where(FundingSourceProductTable.tenantId.eq(tenantId.value))
                    .and(FundingSourceProductTable.id.eq(product.id.value))
                    .returning(FundingSourceProductTable.fields)
                    .fetchOne()
            ) { "funding source product not found: ${product.id.value}" }.toProduct()
        }

    private fun selectProducts() = context.dsl()
        .select(FundingSourceProductTable.fields)
        .from(FundingSourceProductTable.table)

    private fun Record.toProduct(): FundingSourceProduct = FundingSourceProduct(
        id = FundingSourceProductId.of(requireNotNull(get(FundingSourceProductTable.id))),
        tenantId = TenantId.of(requireNotNull(get(FundingSourceProductTable.tenantId))),
        parentId = get(FundingSourceProductTable.parentId)?.let(FundingSourceProductId::of),
        accountCategory = FundingSourceCategory.valueOf(
            requireNotNull(get(FundingSourceProductTable.accountCategory))
        ),
        nodeType = ProductNodeType.valueOf(requireNotNull(get(FundingSourceProductTable.nodeType))),
        code = FundingSourceProductCode.of(requireNotNull(get(FundingSourceProductTable.code))),
        name = FundingSourceProductName.of(requireNotNull(get(FundingSourceProductTable.name))),
        isLeaf = requireNotNull(get(FundingSourceProductTable.isLeaf)),
        isActive = requireNotNull(get(FundingSourceProductTable.isActive)),
        metadata = get(FundingSourceProductTable.metadata).toMetadata(),
        createdAt = get(FundingSourceProductTable.createdAt),
    )
}

class JooqFundingSourceRepository(
    private val context: JooqPersistenceContext,
) : FundingSourceRepository {
    override fun findById(tenantId: TenantId, id: FundingSourceId): FundingSource? =
        jooqOperation("find funding source by id") {
            selectSources()
                .where(FundingSourceTable.tenantId.eq(tenantId.value))
                .and(FundingSourceTable.id.eq(id.value))
                .fetchOne { it.toSource() }
        }

    override fun findByExternalReference(
        tenantId: TenantId,
        externalReference: ExternalReference,
    ): FundingSource? = jooqOperation("find funding source by external reference") {
        selectSources()
            .where(FundingSourceTable.tenantId.eq(tenantId.value))
            .and(FundingSourceTable.externalReference.eq(externalReference.value))
            .fetchOne { it.toSource() }
    }

    override fun insert(tenantId: TenantId, fundingSource: FundingSource): FundingSource =
        jooqOperation("insert funding source") {
            require(fundingSource.tenantId == tenantId) { "funding source tenant does not match repository tenant" }
            val insert = context.dsl()
                .insertInto(FundingSourceTable.table)
                .set(FundingSourceTable.id, fundingSource.id.value)
                .set(FundingSourceTable.tenantId, tenantId.value)
                .set(FundingSourceTable.productId, fundingSource.productId.value)
                .set(FundingSourceTable.externalReference, fundingSource.externalReference?.value)
                .set(FundingSourceTable.isActive, fundingSource.isActive)
                .set(FundingSourceTable.metadata, fundingSource.metadata.toJsonb())
            fundingSource.createdAt?.let { insert.set(FundingSourceTable.createdAt, it) }
            requireNotNull(insert.returning(FundingSourceTable.fields).fetchOne()).toSource()
        }

    override fun update(tenantId: TenantId, fundingSource: FundingSource): FundingSource =
        jooqOperation("update funding source") {
            require(fundingSource.tenantId == tenantId) { "funding source tenant does not match repository tenant" }
            requireNotNull(
                context.dsl()
                    .update(FundingSourceTable.table)
                    .set(FundingSourceTable.productId, fundingSource.productId.value)
                    .set(FundingSourceTable.externalReference, fundingSource.externalReference?.value)
                    .set(FundingSourceTable.isActive, fundingSource.isActive)
                    .set(FundingSourceTable.metadata, fundingSource.metadata.toJsonb())
                    .where(FundingSourceTable.tenantId.eq(tenantId.value))
                    .and(FundingSourceTable.id.eq(fundingSource.id.value))
                    .returning(FundingSourceTable.fields)
                    .fetchOne()
            ) { "funding source not found: ${fundingSource.id.value}" }.toSource()
        }

    private fun selectSources() = context.dsl().select(FundingSourceTable.fields).from(FundingSourceTable.table)

    private fun Record.toSource(): FundingSource = FundingSource(
        id = FundingSourceId.of(requireNotNull(get(FundingSourceTable.id))),
        tenantId = TenantId.of(requireNotNull(get(FundingSourceTable.tenantId))),
        productId = FundingSourceProductId.of(requireNotNull(get(FundingSourceTable.productId))),
        externalReference = get(FundingSourceTable.externalReference)?.let(ExternalReference::of),
        isActive = requireNotNull(get(FundingSourceTable.isActive)),
        metadata = get(FundingSourceTable.metadata).toMetadata(),
        createdAt = get(FundingSourceTable.createdAt),
    )
}

class JooqFundingSourceOwnerRepository(
    private val context: JooqPersistenceContext,
) : FundingSourceOwnerRepository {
    override fun findById(tenantId: TenantId, id: FundingSourceOwnerId): FundingSourceOwner? =
        jooqOperation("find funding source owner by id") {
            selectOwners()
                .where(FundingSourceOwnerTable.tenantId.eq(tenantId.value))
                .and(FundingSourceOwnerTable.id.eq(id.value))
                .fetchOne { it.toOwner() }
        }

    override fun findByOwner(tenantId: TenantId, owner: PartyProfileId): List<FundingSourceOwner> =
        jooqOperation("find funding source ownerships by owner") {
            selectOwners()
                .where(FundingSourceOwnerTable.tenantId.eq(tenantId.value))
                .and(FundingSourceOwnerTable.partyProfileId.eq(owner.value))
                .orderBy(FundingSourceOwnerTable.createdAt, FundingSourceOwnerTable.id)
                .fetch { it.toOwner() }
        }

    override fun findByFundingSource(
        tenantId: TenantId,
        fundingSource: FundingSourceId,
    ): List<FundingSourceOwner> = jooqOperation("find owners by funding source") {
        selectOwners()
            .where(FundingSourceOwnerTable.tenantId.eq(tenantId.value))
            .and(FundingSourceOwnerTable.fundingSourceId.eq(fundingSource.value))
            .orderBy(FundingSourceOwnerTable.createdAt, FundingSourceOwnerTable.id)
            .fetch { it.toOwner() }
    }

    override fun insert(tenantId: TenantId, fundingSourceOwner: FundingSourceOwner): FundingSourceOwner =
        jooqOperation("insert funding source owner") {
            require(fundingSourceOwner.tenantId == tenantId) {
                "funding source owner tenant does not match repository tenant"
            }
            val insert = context.dsl()
                .insertInto(FundingSourceOwnerTable.table)
                .set(FundingSourceOwnerTable.id, fundingSourceOwner.id.value)
                .set(FundingSourceOwnerTable.tenantId, tenantId.value)
                .set(FundingSourceOwnerTable.partyProfileId, fundingSourceOwner.partyProfileId.value)
                .set(FundingSourceOwnerTable.fundingSourceId, fundingSourceOwner.fundingSourceId.value)
                .set(
                    FundingSourceOwnerTable.ownerRole,
                    postgresEnum(fundingSourceOwner.ownerRole.name, "funding_source_owner_role"),
                )
            fundingSourceOwner.createdAt?.let { insert.set(FundingSourceOwnerTable.createdAt, it) }
            requireNotNull(insert.returning(FundingSourceOwnerTable.fields).fetchOne()).toOwner()
        }

    override fun update(tenantId: TenantId, fundingSourceOwner: FundingSourceOwner): FundingSourceOwner =
        jooqOperation("update funding source owner") {
            require(fundingSourceOwner.tenantId == tenantId) {
                "funding source owner tenant does not match repository tenant"
            }
            requireNotNull(
                context.dsl()
                    .update(FundingSourceOwnerTable.table)
                    .set(FundingSourceOwnerTable.partyProfileId, fundingSourceOwner.partyProfileId.value)
                    .set(FundingSourceOwnerTable.fundingSourceId, fundingSourceOwner.fundingSourceId.value)
                    .set(
                        FundingSourceOwnerTable.ownerRole,
                        postgresEnum(fundingSourceOwner.ownerRole.name, "funding_source_owner_role"),
                    )
                    .where(FundingSourceOwnerTable.tenantId.eq(tenantId.value))
                    .and(FundingSourceOwnerTable.id.eq(fundingSourceOwner.id.value))
                    .returning(FundingSourceOwnerTable.fields)
                    .fetchOne()
            ) { "funding source owner not found: ${fundingSourceOwner.id.value}" }.toOwner()
        }

    private fun selectOwners() = context.dsl()
        .select(FundingSourceOwnerTable.fields)
        .from(FundingSourceOwnerTable.table)

    private fun Record.toOwner(): FundingSourceOwner = FundingSourceOwner(
        id = FundingSourceOwnerId.of(requireNotNull(get(FundingSourceOwnerTable.id))),
        tenantId = TenantId.of(requireNotNull(get(FundingSourceOwnerTable.tenantId))),
        partyProfileId = PartyProfileId.of(requireNotNull(get(FundingSourceOwnerTable.partyProfileId))),
        fundingSourceId = FundingSourceId.of(requireNotNull(get(FundingSourceOwnerTable.fundingSourceId))),
        ownerRole = FundingSourceOwnerRole.valueOf(requireNotNull(get(FundingSourceOwnerTable.ownerRole))),
        createdAt = get(FundingSourceOwnerTable.createdAt),
    )
}

class JooqFundingSourceGroupRepository(
    private val context: JooqPersistenceContext,
) : FundingSourceGroupRepository {
    override fun findById(tenantId: TenantId, id: FundingSourceGroupId): FundingSourceGroup? =
        jooqOperation("find funding source group by id") {
            selectGroups()
                .where(FundingSourceGroupTable.tenantId.eq(tenantId.value))
                .and(FundingSourceGroupTable.id.eq(id.value))
                .fetchOne { it.toGroup() }
        }

    override fun findByCode(
        tenantId: TenantId,
        ownerPartyProfileId: PartyProfileId,
        code: FundingSourceGroupCode,
    ): FundingSourceGroup? = jooqOperation("find funding source group by code") {
        selectGroups()
            .where(FundingSourceGroupTable.tenantId.eq(tenantId.value))
            .and(FundingSourceGroupTable.ownerPartyProfileId.eq(ownerPartyProfileId.value))
            .and(FundingSourceGroupTable.code.eq(code.value))
            .fetchOne { it.toGroup() }
    }

    override fun insert(tenantId: TenantId, fundingSourceGroup: FundingSourceGroup): FundingSourceGroup =
        jooqOperation("insert funding source group") {
            require(fundingSourceGroup.tenantId == tenantId) {
                "funding source group tenant does not match repository tenant"
            }
            val insert = context.dsl()
                .insertInto(FundingSourceGroupTable.table)
                .set(FundingSourceGroupTable.id, fundingSourceGroup.id.value)
                .set(FundingSourceGroupTable.tenantId, tenantId.value)
                .set(FundingSourceGroupTable.ownerPartyProfileId, fundingSourceGroup.ownerPartyProfileId.value)
                .set(FundingSourceGroupTable.code, fundingSourceGroup.code.value)
                .set(FundingSourceGroupTable.name, fundingSourceGroup.name.value)
                .set(FundingSourceGroupTable.metadata, fundingSourceGroup.metadata.toJsonb())
            fundingSourceGroup.createdAt?.let { insert.set(FundingSourceGroupTable.createdAt, it) }
            requireNotNull(insert.returning(FundingSourceGroupTable.fields).fetchOne()).toGroup()
        }

    override fun update(tenantId: TenantId, fundingSourceGroup: FundingSourceGroup): FundingSourceGroup =
        jooqOperation("update funding source group") {
            require(fundingSourceGroup.tenantId == tenantId) {
                "funding source group tenant does not match repository tenant"
            }
            requireNotNull(
                context.dsl()
                    .update(FundingSourceGroupTable.table)
                    .set(FundingSourceGroupTable.ownerPartyProfileId, fundingSourceGroup.ownerPartyProfileId.value)
                    .set(FundingSourceGroupTable.code, fundingSourceGroup.code.value)
                    .set(FundingSourceGroupTable.name, fundingSourceGroup.name.value)
                    .set(FundingSourceGroupTable.metadata, fundingSourceGroup.metadata.toJsonb())
                    .where(FundingSourceGroupTable.tenantId.eq(tenantId.value))
                    .and(FundingSourceGroupTable.id.eq(fundingSourceGroup.id.value))
                    .returning(FundingSourceGroupTable.fields)
                    .fetchOne()
            ) { "funding source group not found: ${fundingSourceGroup.id.value}" }.toGroup()
        }

    private fun selectGroups() = context.dsl()
        .select(FundingSourceGroupTable.fields)
        .from(FundingSourceGroupTable.table)

    private fun Record.toGroup(): FundingSourceGroup = FundingSourceGroup(
        id = FundingSourceGroupId.of(requireNotNull(get(FundingSourceGroupTable.id))),
        tenantId = TenantId.of(requireNotNull(get(FundingSourceGroupTable.tenantId))),
        ownerPartyProfileId = PartyProfileId.of(
            requireNotNull(get(FundingSourceGroupTable.ownerPartyProfileId))
        ),
        code = FundingSourceGroupCode.of(requireNotNull(get(FundingSourceGroupTable.code))),
        name = FundingSourceGroupName.of(requireNotNull(get(FundingSourceGroupTable.name))),
        metadata = get(FundingSourceGroupTable.metadata).toMetadata(),
        createdAt = get(FundingSourceGroupTable.createdAt),
    )
}

class JooqFundingSourceGroupMemberRepository(
    private val context: JooqPersistenceContext,
) : FundingSourceGroupMemberRepository {
    override fun findByGroup(
        tenantId: TenantId,
        groupId: FundingSourceGroupId,
    ): List<FundingSourceGroupMember> = jooqOperation("find funding source group members") {
        selectMembers()
            .where(FundingSourceGroupMemberTable.tenantId.eq(tenantId.value))
            .and(FundingSourceGroupMemberTable.groupId.eq(groupId.value))
            .orderBy(FundingSourceGroupMemberTable.createdAt, FundingSourceGroupMemberTable.fundingSourceId)
            .fetch { it.toMember() }
    }

    override fun findGroupIdsContaining(
        tenantId: TenantId,
        owner: PartyProfileId,
        fundingSourceId: FundingSourceId,
    ): Set<FundingSourceGroupId> = jooqOperation("find funding source groups containing source") {
        context.dsl()
            .select(FundingSourceGroupMemberTable.groupId)
            .from(FundingSourceGroupMemberTable.table)
            .where(FundingSourceGroupMemberTable.tenantId.eq(tenantId.value))
            .and(FundingSourceGroupMemberTable.ownerPartyProfileId.eq(owner.value))
            .and(FundingSourceGroupMemberTable.fundingSourceId.eq(fundingSourceId.value))
            .fetchSet(FundingSourceGroupMemberTable.groupId)
            .map(FundingSourceGroupId::of)
            .toSet()
    }

    override fun insert(tenantId: TenantId, member: FundingSourceGroupMember): FundingSourceGroupMember =
        jooqOperation("insert funding source group member") {
            require(member.tenantId == tenantId) {
                "funding source group member tenant does not match repository tenant"
            }
            val insert = context.dsl()
                .insertInto(FundingSourceGroupMemberTable.table)
                .set(FundingSourceGroupMemberTable.tenantId, tenantId.value)
                .set(FundingSourceGroupMemberTable.groupId, member.groupId.value)
                .set(FundingSourceGroupMemberTable.ownerPartyProfileId, member.ownerPartyProfileId.value)
                .set(FundingSourceGroupMemberTable.fundingSourceId, member.fundingSourceId.value)
            member.createdAt?.let { insert.set(FundingSourceGroupMemberTable.createdAt, it) }
            requireNotNull(insert.returning(FundingSourceGroupMemberTable.fields).fetchOne()).toMember()
        }

    private fun selectMembers() = context.dsl()
        .select(FundingSourceGroupMemberTable.fields)
        .from(FundingSourceGroupMemberTable.table)

    private fun Record.toMember(): FundingSourceGroupMember = FundingSourceGroupMember(
        tenantId = TenantId.of(requireNotNull(get(FundingSourceGroupMemberTable.tenantId))),
        groupId = FundingSourceGroupId.of(requireNotNull(get(FundingSourceGroupMemberTable.groupId))),
        ownerPartyProfileId = PartyProfileId.of(
            requireNotNull(get(FundingSourceGroupMemberTable.ownerPartyProfileId))
        ),
        fundingSourceId = FundingSourceId.of(requireNotNull(get(FundingSourceGroupMemberTable.fundingSourceId))),
        createdAt = get(FundingSourceGroupMemberTable.createdAt),
    )
}

private object FundingSourceProductTable {
    const val NAME = "funding_source_product"
    val table = jooqTable(NAME)
    val id: Field<UUID> = jooqField(NAME, "id", UUID::class.java)
    val tenantId: Field<UUID> = jooqField(NAME, "tenant_id", UUID::class.java)
    val parentId: Field<UUID> = jooqField(NAME, "parent_id", UUID::class.java)
    val accountCategory: Field<String> = jooqField(NAME, "account_category", String::class.java)
    val nodeType: Field<String> = jooqField(NAME, "node_type", String::class.java)
    val code: Field<String> = jooqField(NAME, "code", String::class.java)
    val name: Field<String> = jooqField(NAME, "name", String::class.java)
    val isLeaf: Field<Boolean> = jooqField(NAME, "is_leaf", Boolean::class.java)
    val isActive: Field<Boolean> = jooqField(NAME, "is_active", Boolean::class.java)
    val metadata: Field<JSONB> = jooqField(NAME, "metadata", JSONB::class.java)
    val createdAt: Field<Instant> = jooqField(NAME, "created_at", Instant::class.java)
    val fields: List<SelectFieldOrAsterisk> =
        listOf(id, tenantId, parentId, accountCategory, nodeType, code, name, isLeaf, isActive, metadata, createdAt)
}

private object FundingSourceTable {
    const val NAME = "funding_source"
    val table = jooqTable(NAME)
    val id: Field<UUID> = jooqField(NAME, "id", UUID::class.java)
    val tenantId: Field<UUID> = jooqField(NAME, "tenant_id", UUID::class.java)
    val productId: Field<UUID> = jooqField(NAME, "product_id", UUID::class.java)
    val externalReference: Field<String> = jooqField(NAME, "external_reference", String::class.java)
    val isActive: Field<Boolean> = jooqField(NAME, "is_active", Boolean::class.java)
    val metadata: Field<JSONB> = jooqField(NAME, "metadata", JSONB::class.java)
    val createdAt: Field<Instant> = jooqField(NAME, "created_at", Instant::class.java)
    val fields: List<SelectFieldOrAsterisk> =
        listOf(id, tenantId, productId, externalReference, isActive, metadata, createdAt)
}

private object FundingSourceOwnerTable {
    const val NAME = "funding_source_owner"
    val table = jooqTable(NAME)
    val id: Field<UUID> = jooqField(NAME, "id", UUID::class.java)
    val tenantId: Field<UUID> = jooqField(NAME, "tenant_id", UUID::class.java)
    val partyProfileId: Field<UUID> = jooqField(NAME, "party_profile_id", UUID::class.java)
    val fundingSourceId: Field<UUID> = jooqField(NAME, "funding_source_id", UUID::class.java)
    val ownerRole: Field<String> = jooqField(NAME, "owner_role", String::class.java)
    val createdAt: Field<Instant> = jooqField(NAME, "created_at", Instant::class.java)
    val fields: List<SelectFieldOrAsterisk> =
        listOf(id, tenantId, partyProfileId, fundingSourceId, ownerRole, createdAt)
}

private object FundingSourceGroupTable {
    const val NAME = "funding_source_group"
    val table = jooqTable(NAME)
    val id: Field<UUID> = jooqField(NAME, "id", UUID::class.java)
    val tenantId: Field<UUID> = jooqField(NAME, "tenant_id", UUID::class.java)
    val ownerPartyProfileId: Field<UUID> = jooqField(NAME, "owner_party_profile_id", UUID::class.java)
    val code: Field<String> = jooqField(NAME, "code", String::class.java)
    val name: Field<String> = jooqField(NAME, "name", String::class.java)
    val metadata: Field<JSONB> = jooqField(NAME, "metadata", JSONB::class.java)
    val createdAt: Field<Instant> = jooqField(NAME, "created_at", Instant::class.java)
    val fields: List<SelectFieldOrAsterisk> =
        listOf(id, tenantId, ownerPartyProfileId, code, name, metadata, createdAt)
}

private object FundingSourceGroupMemberTable {
    const val NAME = "funding_source_group_member"
    val table = jooqTable(NAME)
    val tenantId: Field<UUID> = jooqField(NAME, "tenant_id", UUID::class.java)
    val groupId: Field<UUID> = jooqField(NAME, "group_id", UUID::class.java)
    val ownerPartyProfileId: Field<UUID> = jooqField(NAME, "owner_party_profile_id", UUID::class.java)
    val fundingSourceId: Field<UUID> = jooqField(NAME, "funding_source_id", UUID::class.java)
    val createdAt: Field<Instant> = jooqField(NAME, "created_at", Instant::class.java)
    val fields: List<SelectFieldOrAsterisk> =
        listOf(tenantId, groupId, ownerPartyProfileId, fundingSourceId, createdAt)
}
