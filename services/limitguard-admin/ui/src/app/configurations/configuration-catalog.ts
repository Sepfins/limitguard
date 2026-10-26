import { Channel, ChannelGroup, FundingSourceProduct, LimitConfiguration, OperationType } from '../core/admin-api.models';

// Amounts stay decimal strings end to end, as the API expects.
export const decimalAmount = /^\d{1,16}(\.\d{1,4})?$/;

// What a configuration can point at, loaded once per page for the pickers and the scope summaries.
export interface ConfigurationCatalog {
  products: FundingSourceProduct[];
  channels: Channel[];
  groups: ChannelGroup[];
  operations: OperationType[];
  configurations: LimitConfiguration[];
}

export const emptyCatalog: ConfigurationCatalog = {
  products: [],
  channels: [],
  groups: [],
  operations: [],
  configurations: []
};

// A datetime-local value as an ISO instant; empty means "not set".
export function isoOrNull(localDateTime: string): string | null {
  return localDateTime ? new Date(localDateTime).toISOString() : null;
}

function codeOf(items: { id: string; code: string }[], id: string | null): string {
  return items.find((item) => item.id === id)?.code ?? id?.slice(0, 8) ?? '';
}

export function kindLabel(configuration: LimitConfiguration): string {
  return configuration.limitKind === 'PERIODIC' ? `Periodic, ${configuration.period?.toLowerCase()}` : 'Per transaction';
}

export function amountLabel(configuration: LimitConfiguration): string {
  return configuration.maxAssignableUnlimited ? 'Unlimited' : `${configuration.maxAssignableAmount}`;
}

export function scopeSummary(configuration: LimitConfiguration, catalog: ConfigurationCatalog): string {
  const funding =
    configuration.fundingScope === 'FUNDING_SOURCE_CATEGORY'
      ? `Category ${configuration.fundingSourceCategory}`
      : configuration.fundingScope === 'FUNDING_SOURCE_PRODUCT'
        ? `Product ${codeOf(catalog.products, configuration.fundingSourceProductId)}`
        : 'All funding sources';
  const channel =
    configuration.channelScope === 'CHANNEL'
      ? `Channel ${codeOf(catalog.channels, configuration.channelId)}`
      : configuration.channelScope === 'CHANNEL_GROUP'
        ? `Group ${codeOf(catalog.groups, configuration.channelGroupId)}`
        : 'All channels';
  const operation = configuration.operationTypeId
    ? `Operation ${codeOf(catalog.operations, configuration.operationTypeId)}`
    : 'All operations';
  return `${funding} · ${channel} · ${operation}`;
}
