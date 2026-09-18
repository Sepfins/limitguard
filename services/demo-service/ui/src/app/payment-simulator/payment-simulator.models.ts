import {
  Account,
  BankCard,
  Customer,
  DemoOperation,
  LimitSetting,
  Money,
  PaymentCategory,
  PaymentViolation,
} from '../core/models';

export interface DemoScenario {
  scenarioKey: string;
  customer: Customer;
  accountIds: string[];
  cardIds: string[];
}

export interface PaymentAuthorizationRequest {
  externalPaymentId: string;
  operation: DemoOperation;
  accountId: string;
  cardId: string | null;
  description: string;
  category: PaymentCategory;
  amount: string;
  currency: string;
}

export interface PaymentAuthorizationResponse {
  externalPaymentId: string;
  decision: 'ALLOWED' | 'DENIED' | 'NOT_APPLICABLE';
  authorizationDecisionId: string;
  paymentTransactionId: string | null;
  transactionStatus: string | null;
  amount: Money;
  violations: PaymentViolation[];
  replayed: boolean;
}

export interface PaymentMutationRequest {
  amount?: string;
  reason?: string;
}

export type PaymentMutationAction = 'capture' | 'release' | 'reverse';

export type LimitChangeDecision = 'approve' | 'reject';

export interface PaymentMutationResponse {
  externalPaymentId: string;
  transactionStatus: string;
  mutationAmount: Money;
  authorizedAmount: Money;
  capturedAmount: Money;
  uncapturedAmount: Money;
  transactionEventId: string | null;
  replayed: boolean;
}

export interface PaymentSimulatorSnapshot {
  scenario: DemoScenario;
  accounts: Account[];
  cards: BankCard[];
  limits: LimitSetting[];
}
