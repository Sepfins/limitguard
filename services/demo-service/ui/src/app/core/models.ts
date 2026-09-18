export type AccountType = 'CURRENT' | 'SAVINGS';
export type CardStatus = 'ACTIVE' | 'INACTIVE';
export type DemoOperation = 'CARD_PURCHASE' | 'ATM_WITHDRAWAL' | 'BANK_TRANSFER';
export type PaymentCategory =
  | 'GROCERIES'
  | 'DINING'
  | 'TRANSPORT'
  | 'CASH'
  | 'TRANSFER'
  | 'SHOPPING'
  | 'OTHER';
export type AuthorizationDecision = 'ALLOWED' | 'DENIED' | 'NOT_APPLICABLE';
export type TransactionStatus =
  | 'AUTHORIZED'
  | 'CAPTURED'
  | 'PARTIALLY_CAPTURED'
  | 'VOIDED'
  | 'REVERSED'
  | 'EXPIRED';
export type LimitKind = 'PER_TRANSACTION' | 'PERIODIC';
export type LimitPeriod = 'DAILY' | 'MONTHLY' | null;
export type CapacityStatus = 'AVAILABLE' | 'EXHAUSTED' | 'UNLIMITED' | 'UNAVAILABLE' | 'NOT_APPLICABLE';

export interface Money {
  amount: string;
  currency: string;
}

export interface Customer {
  displayName: string;
  initials: string;
  relationship: string;
}

export interface Account {
  id: string;
  accountType: AccountType;
  displayName: string;
  maskedIban: string;
  accessLabel: string;
  bookedBalance: Money;
  availableBalance: Money;
}

export interface BankCard {
  id: string;
  accountId: string;
  displayName: string;
  lastFour: string;
  expiresMonth: number;
  expiresYear: number;
  status: CardStatus;
  network: string;
}

export interface PaymentSummary {
  externalPaymentId: string;
  accountId: string;
  cardId: string | null;
  operation: DemoOperation;
  description: string;
  category: PaymentCategory;
  amount: Money;
  decision: AuthorizationDecision | null;
  displayStatus: string;
  transactionStatus: TransactionStatus | null;
  createdAt: string;
  updatedAt: string;
}

export interface PaymentViolation {
  code: string;
  message: string;
  limitRuleId: string | null;
  limitAmount: Money | null;
  attemptedAmount: Money;
  remainingAmount: Money | null;
}

export interface PaymentTimelineItem {
  type: string;
  label: string;
  amount: Money | null;
  occurredAt: string;
}

export interface PaymentDetail {
  payment: PaymentSummary;
  authorizedAmount: Money;
  capturedAmount: Money;
  uncapturedAmount: Money;
  violations: PaymentViolation[];
  timeline: PaymentTimelineItem[];
}

export interface LimitSetting {
  id: string;
  operation: DemoOperation;
  displayName: string;
  limitKind: LimitKind;
  period: LimitPeriod;
  currentAmount: Money;
  minimumAmount: Money;
  maximumAmount: Money;
  stepAmount: Money;
  consumedAmount: Money;
  reservedAmount: Money;
  availableAmount: Money;
  currentExposure: Money;
  capacityStatus: CapacityStatus;
  resetsAt: string | null;
  version: number;
  agreedMaximum: Money | null;
  pendingChange: PendingLimitChange | null;
}

export interface PendingLimitChange {
  id: string;
  requestedAmount: Money;
  /** When it starts after a cooling-off period; null while it waits for the account holder. */
  effectiveAt: string | null;
}

export interface Overview {
  customer: Customer;
  totalBookedBalance: Money;
  totalAvailableBalance: Money;
  accounts: Account[];
  primaryCard: BankCard;
  recentPayments: PaymentSummary[];
  limits: LimitSetting[];
  refreshedAt: string;
}

export interface LimitUpdateResponse {
  status: string;
  message: string | null;
  limit: LimitSetting;
}

export interface ApiError {
  code: string;
  message: string;
  correlationId: string;
  fieldErrors: { field: string; message: string }[];
}
