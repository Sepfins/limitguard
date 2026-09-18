import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { DemoApiError } from '../core/demo-api-error';
import { PaymentSimulatorApiService } from './payment-simulator-api.service';
import { PaymentSimulatorStore } from './payment-simulator.store';
import { PaymentAuthorizationRequest } from './payment-simulator.models';

describe('PaymentSimulatorStore', () => {
  const account = {
    id: 'account-1',
    accountType: 'CURRENT' as const,
    displayName: 'Household current',
    maskedIban: 'IE00 2048',
    accessLabel: 'Authorized user',
    bookedBalance: { amount: '1000.00', currency: 'EUR' },
    availableBalance: { amount: '900.00', currency: 'EUR' },
  };
  const card = {
    id: 'card-1',
    accountId: 'account-1',
    displayName: 'Everyday Debit',
    lastFour: '4821',
    expiresMonth: 8,
    expiresYear: 2029,
    status: 'ACTIVE' as const,
    network: 'Debit',
  };
  const limit = {
    id: 'limit-1',
    operation: 'CARD_PURCHASE' as const,
    displayName: 'Card purchases',
    limitKind: 'PERIODIC' as const,
    period: 'DAILY' as const,
    currentAmount: { amount: '1500.00', currency: 'EUR' },
    minimumAmount: { amount: '100.00', currency: 'EUR' },
    maximumAmount: { amount: '3000.00', currency: 'EUR' },
    stepAmount: { amount: '50.00', currency: 'EUR' },
    consumedAmount: { amount: '100.00', currency: 'EUR' },
    reservedAmount: { amount: '40.00', currency: 'EUR' },
    availableAmount: { amount: '1360.00', currency: 'EUR' },
    currentExposure: { amount: '140.00', currency: 'EUR' },
    capacityStatus: 'AVAILABLE' as const,
    resetsAt: '2026-08-28T00:00:00Z',
    version: 1,
    agreedMaximum: null,
    pendingChange: null,
  };
  const scenario = {
    scenarioKey: 'default-household',
    customer: {
      displayName: 'Emma Clarke',
      initials: 'EC',
      relationship: 'Household',
    },
    accountIds: [account.id],
    cardIds: [card.id],
  };
  const request: PaymentAuthorizationRequest = {
    externalPaymentId: 'sim-1',
    operation: 'CARD_PURCHASE',
    accountId: account.id,
    cardId: card.id,
    description: 'Coffee',
    category: 'DINING',
    amount: '12.30',
    currency: 'EUR',
  };
  const authorization = {
    externalPaymentId: request.externalPaymentId,
    decision: 'ALLOWED' as const,
    authorizationDecisionId: 'decision-1',
    paymentTransactionId: 'transaction-1',
    transactionStatus: 'AUTHORIZED',
    amount: { amount: '12.30', currency: 'EUR' },
    violations: [],
    replayed: false,
  };
  const api = {
    scenario: vi.fn(),
    accounts: vi.fn(),
    cards: vi.fn(),
    limits: vi.fn(),
    authorize: vi.fn(),
    mutate: vi.fn(),
    decideLimitChange: vi.fn(),
  };

  beforeEach(() => {
    localStorage.clear();
    api.scenario.mockReset().mockReturnValue(of(scenario));
    api.accounts.mockReset().mockReturnValue(of([account]));
    api.cards.mockReset().mockReturnValue(of([card]));
    api.limits.mockReset().mockReturnValue(of([limit]));
    api.authorize.mockReset().mockReturnValue(of(authorization));
    api.mutate.mockReset().mockReturnValue(
      of({
        externalPaymentId: request.externalPaymentId,
        transactionStatus: 'CAPTURED',
        mutationAmount: { amount: '12.30', currency: 'EUR' },
        authorizedAmount: { amount: '12.30', currency: 'EUR' },
        capturedAmount: { amount: '12.30', currency: 'EUR' },
        uncapturedAmount: { amount: '0.00', currency: 'EUR' },
        transactionEventId: 'event-1',
        replayed: false,
      }),
    );
    TestBed.configureTestingModule({
      providers: [PaymentSimulatorStore, { provide: PaymentSimulatorApiService, useValue: api }],
    });
  });

  afterEach(() => TestBed.resetTestingModule());

  it('selects the lowest numeric capacity for the requested operation', () => {
    api.limits.mockReturnValue(of([
      { ...limit, id: 'other-operation', operation: 'ATM_WITHDRAWAL', availableAmount: { amount: '1.00', currency: 'EUR' } },
      { ...limit, id: 'broad-limit', availableAmount: { amount: '100.00', currency: 'EUR' } },
      { ...limit, id: 'narrow-limit', availableAmount: { amount: '20.00', currency: 'EUR' } },
    ]));
    const store = TestBed.inject(PaymentSimulatorStore);
    store.load();

    expect(store.limitFor('CARD_PURCHASE')?.id).toBe('narrow-limit');
    expect(store.limitFor('BANK_TRANSFER')).toBeNull();
  });

  it('keeps authorization and mutation keys stable across retries', () => {
    const store = TestBed.inject(PaymentSimulatorStore);
    store.authorize(request);
    store.authorize(request);
    expect(api.authorize.mock.calls[0][1]).toBe(api.authorize.mock.calls[1][1]);

    store.mutate('capture', request.externalPaymentId, { reason: 'capture' });
    store.mutate('capture', request.externalPaymentId, { reason: 'capture' });
    expect(api.mutate.mock.calls[0][3]).toBe(api.mutate.mock.calls[1][3]);
    expect(api.authorize.mock.calls[0][1]).not.toBe(api.mutate.mock.calls[0][3]);
  });

  it('remembers the failed lifecycle action for a targeted retry', () => {
    api.mutate
      .mockReset()
      .mockReturnValueOnce(throwError(() => new DemoApiError('CONFLICT', 'temporary failure', [], 'corr-1', 409)));
    const store = TestBed.inject(PaymentSimulatorStore);
    store.authorize(request);
    store.mutate('release', request.externalPaymentId, { reason: 'release' });

    expect(store.failedAction()).toBe('release');
    expect(store.resultError()).toBe('temporary failure');
  });

  it('lists held limit changes and refreshes after the holder decides', () => {
    const held = {
      ...limit,
      pendingChange: { id: 'change-1', requestedAmount: { amount: '2500.00', currency: 'EUR' }, effectiveAt: null },
    };
    const scheduled = {
      ...limit,
      id: 'limit-2',
      pendingChange: { id: 'change-2', requestedAmount: { amount: '2000.00', currency: 'EUR' }, effectiveAt: '2026-08-27T10:02:00Z' },
    };
    api.limits.mockReturnValueOnce(of([held, scheduled])).mockReturnValue(of([limit]));
    api.decideLimitChange.mockReset().mockReturnValue(of({ status: 'APPLIED', message: null, limit }));
    const store = TestBed.inject(PaymentSimulatorStore);
    store.load();
    expect(store.heldLimits().map((item) => item.id)).toEqual(['limit-1']);

    store.decideLimitChange('change-1', 'approve');

    expect(api.decideLimitChange).toHaveBeenCalledWith('change-1', 'approve');
    expect(store.decidingChange()).toBeNull();
    expect(store.heldLimits()).toEqual([]);
  });
});
