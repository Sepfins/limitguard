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
  noMatchingRuleBehavior: NoMatchingRuleBehavior;
  createdAt: string;
}

export interface CreateTenantRequest {
  name: string;
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
