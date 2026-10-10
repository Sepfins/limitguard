package io.github.sepfins.limitguard.application.configuration

import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.domain.channel.ChannelGroupMember
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.limit.LimitChannelScope
import io.github.sepfins.limitguard.domain.limit.LimitConfiguration
import io.github.sepfins.limitguard.port.persistence.ChannelGroupMemberRepository
import io.github.sepfins.limitguard.port.persistence.ChannelGroupRepository
import io.github.sepfins.limitguard.port.persistence.ChannelRepository
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.port.persistence.LimitConfigurationRepository

internal class ChannelConfigurationEnvelopes(
    private val configurations: LimitConfigurationRepository,
    private val channels: ChannelRepository,
    private val groups: ChannelGroupRepository,
    private val members: ChannelGroupMemberRepository,
) {
    fun validateHierarchy(configuration: LimitConfiguration) {
        validateHierarchy(configuration, ChannelCoverage(configuration.tenantId)) {
            configurations.findById(configuration.tenantId, it)
        }
    }

    fun validateMembershipGrowth(member: ChannelGroupMember) {
        val coverage = ChannelCoverage(member.tenantId, member)
        val all = configurations.findAll(member.tenantId)
        val byId = all.associateBy { it.id }
        all.forEach { configuration -> validateHierarchy(configuration, coverage) { byId[it] } }
    }

    private fun validateHierarchy(
        configuration: LimitConfiguration,
        coverage: ChannelCoverage,
        find: (LimitConfigurationId) -> LimitConfiguration?,
    ) {
        val hierarchy = listOf(configuration) + configuration.parentConfigurationId
            ?.let { resolveHierarchy(it, find) }.orEmpty()
        hierarchy.forEach { coverage.requireReference(it.channelScope) }
        hierarchy.zipWithNext().forEach { (child, parent) ->
            child.validateAgainstParent(parent)
            require(coverage.contains(parent.channelScope, child.channelScope)) {
                "configuration ${child.id.value} channel coverage exceeds parent ${parent.id.value}; correct the channel envelope explicitly"
            }
        }
    }

    private inner class ChannelCoverage(
        private val tenantId: TenantId,
        private val growth: ChannelGroupMember? = null,
    ) {
        private val covered = mutableMapOf<LimitChannelScope, Set<ChannelId>>()

        fun requireReference(scope: LimitChannelScope) {
            if (scope != LimitChannelScope.AllChannels) coveredChannels(scope)
        }

        fun contains(parent: LimitChannelScope, child: LimitChannelScope): Boolean {
            if (parent == LimitChannelScope.AllChannels || parent == child) return true
            if (child == LimitChannelScope.AllChannels) return false
            return coveredChannels(parent).containsAll(coveredChannels(child))
        }

        private fun coveredChannels(scope: LimitChannelScope): Set<ChannelId> = covered.getOrPut(scope) {
            when (scope) {
                LimitChannelScope.AllChannels -> error("ALL coverage must be compared structurally")
                is LimitChannelScope.ChannelSpecific -> {
                    channels.findById(tenantId, scope.channelId)
                        ?: throw NotFoundException("channel not found: ${scope.channelId.value}")
                    setOf(scope.channelId)
                }
                is LimitChannelScope.ChannelGroupSpecific -> {
                    groups.findById(tenantId, scope.channelGroupId)
                        ?: throw NotFoundException("channel group not found: ${scope.channelGroupId.value}")
                    members.findByGroup(tenantId, scope.channelGroupId).mapTo(linkedSetOf()) { it.channelId }.also {
                        if (growth?.groupId == scope.channelGroupId) it += growth.channelId
                    }
                }
            }
        }
    }
}
