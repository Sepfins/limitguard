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
import io.github.sepfins.limitguard.infrastructure.jooq.containsIgnoreCaseLiteral
import io.github.sepfins.limitguard.infrastructure.jooq.jooqOperation
import io.github.sepfins.limitguard.infrastructure.jooq.toJsonb
import io.github.sepfins.limitguard.infrastructure.jooq.toMetadata
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.FundingSourceCategory as GeneratedFundingSourceCategory
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.FundingSourceOwnerRole as GeneratedFundingSourceOwnerRole
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.ProductNodeType as GeneratedProductNodeType
import io.github.sepfins.limitguard.infrastructure.jooq.generated.tables.FundingSource as GeneratedFundingSourceTable
import io.github.sepfins.limitguard.infrastructure.jooq.generated.tables.FundingSourceGroup as GeneratedFundingSourceGroupTable
import io.github.sepfins.limitguard.infrastructure.jooq.generated.tables.FundingSourceGroupMember as GeneratedFundingSourceGroupMemberTable
import io.github.sepfins.limitguard.infrastructure.jooq.generated.tables.FundingSourceOwner as GeneratedFundingSourceOwnerTable
import io.github.sepfins.limitguard.infrastructure.jooq.generated.tables.FundingSourceProduct as GeneratedFundingSourceProductTable
import io.github.sepfins.limitguard.port.persistence.FundingSourceGroupMemberRepository
import io.github.sepfins.limitguard.port.persistence.FundingSourceGroupRepository
import io.github.sepfins.limitguard.port.persistence.FundingSourceOwnerRepository
import io.github.sepfins.limitguard.port.persistence.FundingSourceProductRepository
import io.github.sepfins.limitguard.port.persistence.FundingSourceRepository
import org.jooq.Condition
import org.jooq.Record
import org.jooq.SelectFieldOrAsterisk
import org.jooq.impl.DSL
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

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
                FundingSourceProductTable.accountCategory.eq(
                    GeneratedFundingSourceCategory.valueOf(accountCategory.name),
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

    override fun findPath(tenantId: TenantId, leafId: FundingSourceProductId): List<FundingSourceProduct> =
        jooqOperation("find funding source product path") {
            val dsl = context.dsl()
            val ancestor = FundingSourceProductTable.table.`as`("ancestor")
            val pathTable = DSL.table(DSL.name("product_path"))
            val pathParent = DSL.field(DSL.name("product_path", "parent_id"), java.util.UUID::class.java)
            // UNION ends a corrupt cycle here, and the caller reports it.
            val path = DSL.name("product_path").fields("id", "parent_id").`as`(
                dsl.select(FundingSourceProductTable.id, FundingSourceProductTable.parentId)
                    .from(FundingSourceProductTable.table)
                    .where(FundingSourceProductTable.tenantId.eq(tenantId.value))
                    .and(FundingSourceProductTable.id.eq(leafId.value))
                    .union(
                        dsl.select(ancestor.ID, ancestor.PARENT_ID).from(ancestor)
                            .join(pathTable).on(ancestor.ID.eq(pathParent))
                            .where(ancestor.TENANT_ID.eq(tenantId.value)),
                    ),
            )
            dsl.withRecursive(path)
                .select(FundingSourceProductTable.fields)
                .from(FundingSourceProductTable.table)
                .where(FundingSourceProductTable.id.`in`(dsl.select(path.field("id", java.util.UUID::class.java)).from(path)))
                .fetch { it.toProduct() }
        }

    override fun findPage(
        tenantId: TenantId,
        search: String?,
        active: Boolean?,
        offset: Int,
        limit: Int,
    ): List<FundingSourceProduct> = jooqOperation("list funding source products") {
        selectProducts()
            .where(productFilter(tenantId, search, active))
            .orderBy(FundingSourceProductTable.code, FundingSourceProductTable.id)
            .offset(offset)
            .limit(limit)
            .fetch { it.toProduct() }
    }

    override fun count(tenantId: TenantId, search: String?, active: Boolean?): Long =
        jooqOperation("count funding source products") {
            requireNotNull(
                context.dsl()
                    .select(DSL.count())
                    .from(FundingSourceProductTable.table)
                    .where(productFilter(tenantId, search, active))
                    .fetchOne(0, Long::class.java),
            )
        }

    override fun findByIdForUpdate(
        tenantId: TenantId,
        id: FundingSourceProductId,
    ): FundingSourceProduct? = jooqOperation("lock funding source product by id") {
        context.requireTransaction("locking a funding source product row")
        selectProducts()
            .where(FundingSourceProductTable.tenantId.eq(tenantId.value))
            .and(FundingSourceProductTable.id.eq(id.value))
            .forUpdate()
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
                    GeneratedFundingSourceCategory.valueOf(product.accountCategory.name),
                )
                .set(
                    FundingSourceProductTable.nodeType,
                    GeneratedProductNodeType.valueOf(product.nodeType.name),
                )
                .set(FundingSourceProductTable.code, product.code.value)
                .set(FundingSourceProductTable.name, product.name.value)
                .set(FundingSourceProductTable.isLeaf, product.isLeaf)
                .set(FundingSourceProductTable.isActive, product.isActive)
                .set(FundingSourceProductTable.metadata, product.metadata.toJsonb())
            product.createdAt?.let { insert.set(FundingSourceProductTable.createdAt, it.toOffsetDateTime()) }
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
                        GeneratedFundingSourceCategory.valueOf(product.accountCategory.name),
                    )
                    .set(
                        FundingSourceProductTable.nodeType,
                        GeneratedProductNodeType.valueOf(product.nodeType.name),
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

    private fun productFilter(tenantId: TenantId, search: String?, active: Boolean?): Condition {
        var condition: Condition = FundingSourceProductTable.tenantId.eq(tenantId.value)
        if (search != null) {
            condition = condition.and(
                FundingSourceProductTable.code.containsIgnoreCaseLiteral(search)
                    .or(FundingSourceProductTable.name.containsIgnoreCaseLiteral(search)),
            )
        }
        if (active != null) {
            condition = condition.and(FundingSourceProductTable.isActive.eq(active))
        }
        return condition
    }

    private fun Record.toProduct(): FundingSourceProduct = FundingSourceProduct(
        id = FundingSourceProductId.of(requireNotNull(get(FundingSourceProductTable.id))),
        tenantId = TenantId.of(requireNotNull(get(FundingSourceProductTable.tenantId))),
        parentId = get(FundingSourceProductTable.parentId)?.let(FundingSourceProductId::of),
        accountCategory = FundingSourceCategory.valueOf(
            requireNotNull(get(FundingSourceProductTable.accountCategory)).name
        ),
        nodeType = ProductNodeType.valueOf(requireNotNull(get(FundingSourceProductTable.nodeType)).name),
        code = FundingSourceProductCode.of(requireNotNull(get(FundingSourceProductTable.code))),
        name = FundingSourceProductName.of(requireNotNull(get(FundingSourceProductTable.name))),
        isLeaf = requireNotNull(get(FundingSourceProductTable.isLeaf)),
        isActive = requireNotNull(get(FundingSourceProductTable.isActive)),
        metadata = get(FundingSourceProductTable.metadata).toMetadata(),
        createdAt = get(FundingSourceProductTable.createdAt)?.toInstant(),
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

    override fun findByIdForUpdate(tenantId: TenantId, id: FundingSourceId): FundingSource? =
        jooqOperation("lock funding source by id") {
            context.requireTransaction("locking a funding source row")
            selectSources()
                .where(FundingSourceTable.tenantId.eq(tenantId.value))
                .and(FundingSourceTable.id.eq(id.value))
                .forUpdate()
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
            fundingSource.createdAt?.let { insert.set(FundingSourceTable.createdAt, it.toOffsetDateTime()) }
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
        createdAt = get(FundingSourceTable.createdAt)?.toInstant(),
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
                    GeneratedFundingSourceOwnerRole.valueOf(fundingSourceOwner.ownerRole.name),
                )
            fundingSourceOwner.createdAt?.let {
                insert.set(FundingSourceOwnerTable.createdAt, it.toOffsetDateTime())
            }
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
                        GeneratedFundingSourceOwnerRole.valueOf(fundingSourceOwner.ownerRole.name),
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
        ownerRole = FundingSourceOwnerRole.valueOf(requireNotNull(get(FundingSourceOwnerTable.ownerRole)).name),
        createdAt = get(FundingSourceOwnerTable.createdAt)?.toInstant(),
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
            fundingSourceGroup.createdAt?.let {
                insert.set(FundingSourceGroupTable.createdAt, it.toOffsetDateTime())
            }
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
        createdAt = get(FundingSourceGroupTable.createdAt)?.toInstant(),
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
            member.createdAt?.let {
                insert.set(FundingSourceGroupMemberTable.createdAt, it.toOffsetDateTime())
            }
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
        createdAt = get(FundingSourceGroupMemberTable.createdAt)?.toInstant(),
    )
}

private object FundingSourceProductTable {
    val table = GeneratedFundingSourceProductTable.FUNDING_SOURCE_PRODUCT
    val id = table.ID
    val tenantId = table.TENANT_ID
    val parentId = table.PARENT_ID
    val accountCategory = table.ACCOUNT_CATEGORY
    val nodeType = table.NODE_TYPE
    val code = table.CODE
    val name = table.NAME
    val isLeaf = table.IS_LEAF
    val isActive = table.IS_ACTIVE
    val metadata = table.METADATA
    val createdAt = table.CREATED_AT
    val fields: List<SelectFieldOrAsterisk> = table.fields().toList()
}

private object FundingSourceTable {
    val table = GeneratedFundingSourceTable.FUNDING_SOURCE
    val id = table.ID
    val tenantId = table.TENANT_ID
    val productId = table.PRODUCT_ID
    val externalReference = table.EXTERNAL_REFERENCE
    val isActive = table.IS_ACTIVE
    val metadata = table.METADATA
    val createdAt = table.CREATED_AT
    val fields: List<SelectFieldOrAsterisk> = table.fields().toList()
}

private object FundingSourceOwnerTable {
    val table = GeneratedFundingSourceOwnerTable.FUNDING_SOURCE_OWNER
    val id = table.ID
    val tenantId = table.TENANT_ID
    val partyProfileId = table.PARTY_PROFILE_ID
    val fundingSourceId = table.FUNDING_SOURCE_ID
    val ownerRole = table.OWNER_ROLE
    val createdAt = table.CREATED_AT
    val fields: List<SelectFieldOrAsterisk> = table.fields().toList()
}

private object FundingSourceGroupTable {
    val table = GeneratedFundingSourceGroupTable.FUNDING_SOURCE_GROUP
    val id = table.ID
    val tenantId = table.TENANT_ID
    val ownerPartyProfileId = table.OWNER_PARTY_PROFILE_ID
    val code = table.CODE
    val name = table.NAME
    val metadata = table.METADATA
    val createdAt = table.CREATED_AT
    val fields: List<SelectFieldOrAsterisk> = table.fields().toList()
}

private object FundingSourceGroupMemberTable {
    val table = GeneratedFundingSourceGroupMemberTable.FUNDING_SOURCE_GROUP_MEMBER
    val tenantId = table.TENANT_ID
    val groupId = table.GROUP_ID
    val ownerPartyProfileId = table.OWNER_PARTY_PROFILE_ID
    val fundingSourceId = table.FUNDING_SOURCE_ID
    val createdAt = table.CREATED_AT
    val fields: List<SelectFieldOrAsterisk> = table.fields().toList()
}

private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
