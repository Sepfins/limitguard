package io.github.sepfins.limitguard.infrastructure.jooq.repository

import io.github.sepfins.limitguard.domain.channel.Channel
import io.github.sepfins.limitguard.domain.channel.ChannelCode
import io.github.sepfins.limitguard.domain.channel.ChannelGroup
import io.github.sepfins.limitguard.domain.channel.ChannelGroupCode
import io.github.sepfins.limitguard.domain.channel.ChannelGroupMember
import io.github.sepfins.limitguard.domain.channel.ChannelGroupName
import io.github.sepfins.limitguard.domain.channel.ChannelName
import io.github.sepfins.limitguard.domain.common.ChannelGroupId
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.infrastructure.jooq.JooqPersistenceContext
import io.github.sepfins.limitguard.infrastructure.jooq.containsIgnoreCaseLiteral
import io.github.sepfins.limitguard.infrastructure.jooq.jooqOperation
import io.github.sepfins.limitguard.infrastructure.jooq.toJsonb
import io.github.sepfins.limitguard.infrastructure.jooq.toMetadata
import io.github.sepfins.limitguard.infrastructure.jooq.generated.tables.Channel as GeneratedChannelTable
import io.github.sepfins.limitguard.infrastructure.jooq.generated.tables.ChannelGroup as GeneratedChannelGroupTable
import io.github.sepfins.limitguard.infrastructure.jooq.generated.tables.ChannelGroupMember as GeneratedChannelGroupMemberTable
import io.github.sepfins.limitguard.port.persistence.ChannelGroupMemberRepository
import io.github.sepfins.limitguard.port.persistence.ChannelGroupRepository
import io.github.sepfins.limitguard.port.persistence.ChannelRepository
import org.jooq.Condition
import org.jooq.Record
import org.jooq.SelectFieldOrAsterisk
import org.jooq.impl.DSL
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class JooqChannelRepository(
    private val context: JooqPersistenceContext,
) : ChannelRepository {
    override fun findActive(tenantId: TenantId): List<Channel> = jooqOperation("find active channels") {
        selectChannels()
            .where(ChannelTable.tenantId.eq(tenantId.value))
            .and(ChannelTable.isActive.isTrue)
            .orderBy(ChannelTable.code, ChannelTable.id)
            .fetch { it.toChannel() }
    }

    override fun findById(tenantId: TenantId, channelId: ChannelId): Channel? =
        jooqOperation("find channel by id") {
            selectChannels()
                .where(ChannelTable.tenantId.eq(tenantId.value))
                .and(ChannelTable.id.eq(channelId.value))
                .fetchOne { it.toChannel() }
        }

    override fun findByIdForUpdate(tenantId: TenantId, channelId: ChannelId): Channel? =
        jooqOperation("lock channel by id") {
            context.requireTransaction("locking a channel row")
            selectChannels()
                .where(ChannelTable.tenantId.eq(tenantId.value))
                .and(ChannelTable.id.eq(channelId.value))
                .forUpdate()
                .fetchOne { it.toChannel() }
        }

    override fun findByCode(tenantId: TenantId, code: ChannelCode): Channel? =
        jooqOperation("find channel by code") {
            selectChannels()
                .where(ChannelTable.tenantId.eq(tenantId.value))
                .and(ChannelTable.code.eq(code.value))
                .fetchOne { it.toChannel() }
        }

    override fun findPage(
        tenantId: TenantId,
        search: String?,
        active: Boolean?,
        offset: Int,
        limit: Int,
    ): List<Channel> = jooqOperation("list channels") {
        selectChannels()
            .where(channelFilter(tenantId, search, active))
            .orderBy(ChannelTable.code, ChannelTable.id)
            .offset(offset)
            .limit(limit)
            .fetch { it.toChannel() }
    }

    override fun count(tenantId: TenantId, search: String?, active: Boolean?): Long =
        jooqOperation("count channels") {
            requireNotNull(
                context.dsl()
                    .select(DSL.count())
                    .from(ChannelTable.table)
                    .where(channelFilter(tenantId, search, active))
                    .fetchOne(0, Long::class.java),
            )
        }

    override fun insert(tenantId: TenantId, channel: Channel): Channel = jooqOperation("insert channel") {
        require(channel.tenantId == tenantId) { "channel tenant does not match repository tenant" }
        val insert = context.dsl()
            .insertInto(ChannelTable.table)
            .set(ChannelTable.id, channel.id.value)
            .set(ChannelTable.tenantId, tenantId.value)
            .set(ChannelTable.code, channel.code.value)
            .set(ChannelTable.name, channel.name.value)
            .set(ChannelTable.isActive, channel.isActive)
            .set(ChannelTable.metadata, channel.metadata.toJsonb())
        channel.createdAt?.let { insert.set(ChannelTable.createdAt, it.toOffsetDateTime()) }
        requireNotNull(insert.returning(ChannelTable.fields).fetchOne()).toChannel()
    }

    override fun update(tenantId: TenantId, channel: Channel): Channel = jooqOperation("update channel") {
        require(channel.tenantId == tenantId) { "channel tenant does not match repository tenant" }
        requireNotNull(
            context.dsl()
                .update(ChannelTable.table)
                .set(ChannelTable.code, channel.code.value)
                .set(ChannelTable.name, channel.name.value)
                .set(ChannelTable.isActive, channel.isActive)
                .set(ChannelTable.metadata, channel.metadata.toJsonb())
                .where(ChannelTable.tenantId.eq(tenantId.value))
                .and(ChannelTable.id.eq(channel.id.value))
                .returning(ChannelTable.fields)
                .fetchOne()
        ) { "channel not found: ${channel.id.value}" }.toChannel()
    }

    private fun selectChannels() = context.dsl().select(ChannelTable.fields).from(ChannelTable.table)

    private fun channelFilter(tenantId: TenantId, search: String?, active: Boolean?): Condition {
        var condition: Condition = ChannelTable.tenantId.eq(tenantId.value)
        if (search != null) {
            condition = condition.and(
                ChannelTable.code.containsIgnoreCaseLiteral(search)
                    .or(ChannelTable.name.containsIgnoreCaseLiteral(search)),
            )
        }
        if (active != null) {
            condition = condition.and(ChannelTable.isActive.eq(active))
        }
        return condition
    }

    private fun Record.toChannel(): Channel = Channel(
        id = ChannelId.of(requireNotNull(get(ChannelTable.id))),
        tenantId = TenantId.of(requireNotNull(get(ChannelTable.tenantId))),
        code = ChannelCode.of(requireNotNull(get(ChannelTable.code))),
        name = ChannelName.of(requireNotNull(get(ChannelTable.name))),
        isActive = requireNotNull(get(ChannelTable.isActive)),
        metadata = get(ChannelTable.metadata).toMetadata(),
        createdAt = get(ChannelTable.createdAt)?.toInstant(),
    )
}

class JooqChannelGroupRepository(
    private val context: JooqPersistenceContext,
) : ChannelGroupRepository {
    override fun findById(tenantId: TenantId, id: ChannelGroupId): ChannelGroup? =
        jooqOperation("find channel group by id") {
            selectGroups()
                .where(ChannelGroupTable.tenantId.eq(tenantId.value))
                .and(ChannelGroupTable.id.eq(id.value))
                .fetchOne { it.toGroup() }
        }

    override fun findByCode(tenantId: TenantId, code: ChannelGroupCode): ChannelGroup? =
        jooqOperation("find channel group by code") {
            selectGroups()
                .where(ChannelGroupTable.tenantId.eq(tenantId.value))
                .and(ChannelGroupTable.code.eq(code.value))
                .fetchOne { it.toGroup() }
        }

    override fun findPage(
        tenantId: TenantId,
        search: String?,
        offset: Int,
        limit: Int,
    ): List<ChannelGroup> = jooqOperation("list channel groups") {
        selectGroups()
            .where(groupFilter(tenantId, search))
            .orderBy(ChannelGroupTable.code, ChannelGroupTable.id)
            .offset(offset)
            .limit(limit)
            .fetch { it.toGroup() }
    }

    override fun count(tenantId: TenantId, search: String?): Long = jooqOperation("count channel groups") {
        requireNotNull(
            context.dsl()
                .select(DSL.count())
                .from(ChannelGroupTable.table)
                .where(groupFilter(tenantId, search))
                .fetchOne(0, Long::class.java),
        )
    }

    override fun insert(tenantId: TenantId, channelGroup: ChannelGroup): ChannelGroup =
        jooqOperation("insert channel group") {
            require(channelGroup.tenantId == tenantId) { "channel group tenant does not match repository tenant" }
            val insert = context.dsl()
                .insertInto(ChannelGroupTable.table)
                .set(ChannelGroupTable.id, channelGroup.id.value)
                .set(ChannelGroupTable.tenantId, tenantId.value)
                .set(ChannelGroupTable.code, channelGroup.code.value)
                .set(ChannelGroupTable.name, channelGroup.name.value)
                .set(ChannelGroupTable.metadata, channelGroup.metadata.toJsonb())
            channelGroup.createdAt?.let { insert.set(ChannelGroupTable.createdAt, it.toOffsetDateTime()) }
            requireNotNull(insert.returning(ChannelGroupTable.fields).fetchOne()).toGroup()
        }

    override fun update(tenantId: TenantId, channelGroup: ChannelGroup): ChannelGroup =
        jooqOperation("update channel group") {
            require(channelGroup.tenantId == tenantId) { "channel group tenant does not match repository tenant" }
            requireNotNull(
                context.dsl()
                    .update(ChannelGroupTable.table)
                    .set(ChannelGroupTable.code, channelGroup.code.value)
                    .set(ChannelGroupTable.name, channelGroup.name.value)
                    .set(ChannelGroupTable.metadata, channelGroup.metadata.toJsonb())
                    .where(ChannelGroupTable.tenantId.eq(tenantId.value))
                    .and(ChannelGroupTable.id.eq(channelGroup.id.value))
                    .returning(ChannelGroupTable.fields)
                    .fetchOne()
            ) { "channel group not found: ${channelGroup.id.value}" }.toGroup()
        }

    private fun selectGroups() = context.dsl().select(ChannelGroupTable.fields).from(ChannelGroupTable.table)

    private fun groupFilter(tenantId: TenantId, search: String?): Condition {
        val condition = ChannelGroupTable.tenantId.eq(tenantId.value)
        if (search == null) return condition
        return condition.and(
            ChannelGroupTable.code.containsIgnoreCaseLiteral(search)
                .or(ChannelGroupTable.name.containsIgnoreCaseLiteral(search)),
        )
    }

    private fun Record.toGroup(): ChannelGroup = ChannelGroup(
        id = ChannelGroupId.of(requireNotNull(get(ChannelGroupTable.id))),
        tenantId = TenantId.of(requireNotNull(get(ChannelGroupTable.tenantId))),
        code = ChannelGroupCode.of(requireNotNull(get(ChannelGroupTable.code))),
        name = ChannelGroupName.of(requireNotNull(get(ChannelGroupTable.name))),
        metadata = get(ChannelGroupTable.metadata).toMetadata(),
        createdAt = get(ChannelGroupTable.createdAt)?.toInstant(),
    )
}

class JooqChannelGroupMemberRepository(
    private val context: JooqPersistenceContext,
) : ChannelGroupMemberRepository {
    override fun findByGroup(tenantId: TenantId, groupId: ChannelGroupId): List<ChannelGroupMember> =
        jooqOperation("find channel group members") {
            selectMembers()
                .where(ChannelGroupMemberTable.tenantId.eq(tenantId.value))
                .and(ChannelGroupMemberTable.groupId.eq(groupId.value))
                .orderBy(ChannelGroupMemberTable.createdAt, ChannelGroupMemberTable.channelId)
                .fetch { it.toMember() }
        }

    override fun findByGroups(
        tenantId: TenantId,
        groupIds: Collection<ChannelGroupId>,
    ): List<ChannelGroupMember> = jooqOperation("find members of channel groups") {
        if (groupIds.isEmpty()) return@jooqOperation emptyList()
        selectMembers()
            .where(ChannelGroupMemberTable.tenantId.eq(tenantId.value))
            .and(ChannelGroupMemberTable.groupId.`in`(groupIds.map { it.value }))
            .orderBy(ChannelGroupMemberTable.createdAt, ChannelGroupMemberTable.channelId)
            .fetch { it.toMember() }
    }

    override fun findGroupIdsContaining(tenantId: TenantId, channelId: ChannelId): Set<ChannelGroupId> =
        jooqOperation("find channel groups containing channel") {
            context.dsl()
                .select(ChannelGroupMemberTable.groupId)
                .from(ChannelGroupMemberTable.table)
                .where(ChannelGroupMemberTable.tenantId.eq(tenantId.value))
                .and(ChannelGroupMemberTable.channelId.eq(channelId.value))
                .fetchSet(ChannelGroupMemberTable.groupId)
                .mapTo(linkedSetOf(), ChannelGroupId::of)
        }

    override fun insert(tenantId: TenantId, member: ChannelGroupMember): ChannelGroupMember =
        jooqOperation("insert channel group member") {
            require(member.tenantId == tenantId) { "channel group member tenant does not match repository tenant" }
            val insert = context.dsl()
                .insertInto(ChannelGroupMemberTable.table)
                .set(ChannelGroupMemberTable.tenantId, tenantId.value)
                .set(ChannelGroupMemberTable.groupId, member.groupId.value)
                .set(ChannelGroupMemberTable.channelId, member.channelId.value)
            member.createdAt?.let { insert.set(ChannelGroupMemberTable.createdAt, it.toOffsetDateTime()) }
            requireNotNull(insert.returning(ChannelGroupMemberTable.fields).fetchOne()).toMember()
        }

    private fun selectMembers() = context.dsl()
        .select(ChannelGroupMemberTable.fields)
        .from(ChannelGroupMemberTable.table)

    private fun Record.toMember(): ChannelGroupMember = ChannelGroupMember(
        tenantId = TenantId.of(requireNotNull(get(ChannelGroupMemberTable.tenantId))),
        groupId = ChannelGroupId.of(requireNotNull(get(ChannelGroupMemberTable.groupId))),
        channelId = ChannelId.of(requireNotNull(get(ChannelGroupMemberTable.channelId))),
        createdAt = get(ChannelGroupMemberTable.createdAt)?.toInstant(),
    )
}

private object ChannelTable {
    val table = GeneratedChannelTable.CHANNEL
    val id = table.ID
    val tenantId = table.TENANT_ID
    val code = table.CODE
    val name = table.NAME
    val isActive = table.IS_ACTIVE
    val metadata = table.METADATA
    val createdAt = table.CREATED_AT
    val fields: List<SelectFieldOrAsterisk> = table.fields().toList()
}

private object ChannelGroupTable {
    val table = GeneratedChannelGroupTable.CHANNEL_GROUP
    val id = table.ID
    val tenantId = table.TENANT_ID
    val code = table.CODE
    val name = table.NAME
    val metadata = table.METADATA
    val createdAt = table.CREATED_AT
    val fields: List<SelectFieldOrAsterisk> = table.fields().toList()
}

private object ChannelGroupMemberTable {
    val table = GeneratedChannelGroupMemberTable.CHANNEL_GROUP_MEMBER
    val tenantId = table.TENANT_ID
    val groupId = table.GROUP_ID
    val channelId = table.CHANNEL_ID
    val createdAt = table.CREATED_AT
    val fields: List<SelectFieldOrAsterisk> = table.fields().toList()
}

private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
