export type AdminRole = 'PLATFORM_ADMIN' | 'TENANT_ADMIN' | 'VIEWER';
export type NoMatchingRuleBehavior = 'ALLOW' | 'DENY' | 'NOT_APPLICABLE';
export type FundingSourceCategory =
  | 'TRANSACTIONAL'
  | 'SAVINGS'
  | 'TERM_DEPOSIT'
  | 'PREPAID'
  | 'CREDIT'
  | 'SECURITIES'
  | 'EWALLET';
export type ProductNodeType = 'FAMILY' | 'SUBFAMILY' | 'PRODUCT';

export interface MeResponse {
  subject: string;
  displayName: string;
  email: string | null;
  roles: AdminRole[];
  tenantIds: string[];
  logoutUrl: string;
  csrfParameterName: string;
  csrfToken: string;
}

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface Tenant {
  id: string;
  name: string;
  timeZone: string;
  noMatchingRuleBehavior: NoMatchingRuleBehavior;
  createdAt: string;
}

export interface CreateTenantRequest {
  name: string;
  timeZone: string;
  noMatchingRuleBehavior: NoMatchingRuleBehavior;
}

export interface FundingSourceProduct {
  id: string;
  tenantId: string;
  parentId: string | null;
  category: FundingSourceCategory;
  nodeType: ProductNodeType;
  code: string;
  name: string;
  isLeaf: boolean;
  isActive: boolean;
  createdAt: string;
}

export interface CreateFundingSourceProductRequest {
  code: string;
  name: string;
  category: FundingSourceCategory;
  nodeType: ProductNodeType;
  parentId: string | null;
  isLeaf: boolean;
  isActive: boolean;
}

export interface Channel {
  id: string;
  code: string;
  name: string;
  isActive: boolean;
  createdAt: string;
}

export interface ChannelGroup {
  id: string;
  code: string;
  name: string;
  memberChannelIds: string[];
  createdAt: string;
}

export interface OperationType {
  id: string;
  parentId: string | null;
  code: string;
  name: string;
  isLeaf: boolean;
  isGlobal: boolean;
  createdAt: string;
}

export interface CreateOperationTypeRequest {
  code: string;
  name: string;
  parentId: string | null;
  isLeaf: boolean;
  isGlobal: boolean;
}

export type LimitKind = 'PER_TRANSACTION' | 'PERIODIC';
export type LimitPeriod = 'DAILY' | 'MONTHLY';
export type LimitConfigurationFundingScope = 'ALL_FUNDING_SOURCES' | 'FUNDING_SOURCE_CATEGORY' | 'FUNDING_SOURCE_PRODUCT';
export type LimitChannelScope = 'ALL_CHANNELS' | 'CHANNEL' | 'CHANNEL_GROUP';
export type LimitUsageConsumptionMode = 'CASCADING' | 'ISOLATED';

// Amounts are decimal strings; an unlimited maximum has no amount.
export interface LimitConfiguration {
  id: string;
  parentConfigurationId: string | null;
  code: string;
  name: string;
  description: string | null;
  limitKind: LimitKind;
  period: LimitPeriod | null;
  currency: string;
  fundingScope: LimitConfigurationFundingScope;
  fundingSourceCategory: FundingSourceCategory | null;
  fundingSourceProductId: string | null;
  channelScope: LimitChannelScope;
  channelId: string | null;
  channelGroupId: string | null;
  operationTypeId: string | null;
  maxAssignableAmount: string | null;
  maxAssignableUnlimited: boolean;
  defaultAmount: string | null;
  priority: number;
  usageConsumptionMode: LimitUsageConsumptionMode;
  isActive: boolean;
  startsAt: string;
  endsAt: string | null;
  createdAt: string;
}

export interface CreateLimitConfigurationRequest {
  code: string;
  name: string;
  description: string | null;
  limitKind: LimitKind;
  period: LimitPeriod | null;
  currency: string;
  maxAssignableAmount: string | null;
  maxAssignableUnlimited: boolean;
  defaultAmount: string | null;
  parentConfigurationId: string | null;
  fundingScope: LimitConfigurationFundingScope;
  fundingSourceCategory: FundingSourceCategory | null;
  fundingSourceProductId: string | null;
  channelScope: LimitChannelScope;
  channelId: string | null;
  channelGroupId: string | null;
  operationTypeId: string | null;
  priority: number;
  usageConsumptionMode: LimitUsageConsumptionMode;
  startsAt: string | null;
  endsAt: string | null;
}

export interface UpdateLimitConfigurationRequest {
  name: string;
  description: string | null;
  defaultAmount: string | null;
}

export type LimitChangePolicyPhase = 'AUTHORIZATION';
export type LimitChangePolicyDecision = 'ALLOW' | 'DENY' | 'REQUIRE_APPROVAL' | 'NO_OP';

// Condition and actions are JSON Logic values stored as the core received them.
export interface LimitChangePolicy {
  id: string;
  code: string;
  name: string;
  description: string | null;
  phase: LimitChangePolicyPhase;
  decision: LimitChangePolicyDecision;
  limitConfigurationId: string | null;
  priority: number;
  isActive: boolean;
  conditionExpression: Record<string, unknown>;
  actionExpression: Record<string, unknown>[];
  denialCode: string | null;
  denialMessage: string | null;
  startsAt: string;
  endsAt: string | null;
  createdAt: string;
}

export interface CreateLimitChangePolicyRequest {
  code: string;
  name: string;
  description: string | null;
  phase: LimitChangePolicyPhase;
  decision: LimitChangePolicyDecision;
  limitConfigurationId: string | null;
  priority: number;
  conditionExpression: unknown;
  actionExpression: unknown;
  denialCode: string | null;
  denialMessage: string | null;
  startsAt: string | null;
  endsAt: string | null;
}

export interface CodeNameRequest {
  code: string;
  name: string;
}

export interface FieldViolation {
  field: string;
  message: string;
}

export interface AdminErrorPayload {
  code: string;
  message: string;
  correlationId: string;
  fieldViolations: FieldViolation[];
}
