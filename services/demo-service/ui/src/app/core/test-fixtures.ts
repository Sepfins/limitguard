import { Account, BankCard, LimitSetting, Overview, PaymentSummary } from './models';

export const accountFixture: Account = {
  id: 'account-current',
  accountType: 'CURRENT',
  displayName: 'Household current',
  maskedIban: 'IE00 WREN 9000 •••• 2048',
  accessLabel: 'Authorized user',
  bookedBalance: { amount: '18420.40', currency: 'EUR' },
  availableBalance: { amount: '18320.40', currency: 'EUR' }
};

export const cardFixture: BankCard = {
  id: 'card-1',
  accountId: accountFixture.id,
  displayName: 'Everyday Debit',
  lastFour: '4821',
  expiresMonth: 8,
  expiresYear: 2029,
  status: 'ACTIVE',
  network: 'Debit'
};

export const cardPaymentFixture: PaymentSummary = {
  externalPaymentId: 'payment-card-1',
  accountId: accountFixture.id,
  cardId: cardFixture.id,
  operation: 'CARD_PURCHASE',
  description: 'Corner Grocer',
  category: 'GROCERIES',
  amount: { amount: '85.40', currency: 'EUR' },
  decision: 'ALLOWED',
  displayStatus: 'PENDING',
  transactionStatus: 'AUTHORIZED',
  createdAt: '2026-08-09T10:00:00Z',
  updatedAt: '2026-08-09T10:00:00Z'
};

export const atmPaymentFixture: PaymentSummary = {
  ...cardPaymentFixture,
  externalPaymentId: 'payment-atm-1',
  operation: 'ATM_WITHDRAWAL',
  description: 'Station Road ATM',
  category: 'CASH',
  amount: { amount: '150.00', currency: 'EUR' },
  decision: 'DENIED',
  displayStatus: 'DECLINED',
  transactionStatus: null
};

export const limitFixture: LimitSetting = {
  id: 'limit-card',
  operation: 'CARD_PURCHASE',
  displayName: 'Card purchases',
  limitKind: 'PERIODIC',
  period: 'DAILY',
  currentAmount: { amount: '1500.00', currency: 'EUR' },
  minimumAmount: { amount: '100.00', currency: 'EUR' },
  maximumAmount: { amount: '3000.00', currency: 'EUR' },
  stepAmount: { amount: '50.00', currency: 'EUR' },
  consumedAmount: { amount: '85.40', currency: 'EUR' },
  reservedAmount: { amount: '0.00', currency: 'EUR' },
  availableAmount: { amount: '1414.60', currency: 'EUR' },
  currentExposure: { amount: '85.40', currency: 'EUR' },
  capacityStatus: 'AVAILABLE',
  resetsAt: '2026-08-10T00:00:00Z',
  version: 1,
  agreedMaximum: null,
  pendingChange: null
};

export const overviewFixture: Overview = {
  customer: {
    displayName: 'Emma Clarke',
    initials: 'EC',
    relationship: 'Personal and household banking'
  },
  totalBookedBalance: { amount: '36670.40', currency: 'EUR' },
  totalAvailableBalance: { amount: '36570.40', currency: 'EUR' },
  accounts: [accountFixture],
  primaryCard: cardFixture,
  recentPayments: [cardPaymentFixture, atmPaymentFixture],
  limits: [limitFixture],
  refreshedAt: '2026-08-09T10:00:00Z'
};
