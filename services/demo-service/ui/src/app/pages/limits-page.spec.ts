import { importProvidersFrom, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { LucideAngularModule, icons } from 'lucide-angular';
import { of, throwError } from 'rxjs';
import { BankStore } from '../core/bank.store';
import { DemoApiError, DemoApiService } from '../core/demo-api.service';
import { limitFixture } from '../core/test-fixtures';
import { LimitsPage } from './limits-page';

describe('LimitsPage', () => {
  const api = { updateLimit: vi.fn(), withdrawLimitChange: vi.fn() };
  const store = {
    limits: signal([limitFixture]),
    loading: signal(false),
    error: signal<string | null>(null),
    load: vi.fn(),
    refresh: vi.fn(),
    replaceLimit: vi.fn()
  };

  beforeEach(async () => {
    api.updateLimit.mockReset();
    api.withdrawLimitChange.mockReset();
    store.refresh.mockReset();
    store.replaceLimit.mockReset();
    await TestBed.configureTestingModule({
      imports: [LimitsPage],
      providers: [
        { provide: DemoApiService, useValue: api },
        { provide: BankStore, useValue: store },
        importProvidersFrom(LucideAngularModule.pick(icons))
      ]
    }).compileComponents();
  });

  it('keeps slider edits in the reactive amount control and applies immediately', () => {
    const updated = {
      ...limitFixture,
      currentAmount: { amount: '1600.00', currency: 'EUR' },
      availableAmount: { amount: '1514.60', currency: 'EUR' },
      version: 2
    };
    api.updateLimit.mockReturnValue(of({ status: 'APPLIED', message: null, limit: updated }));
    const fixture = TestBed.createComponent(LimitsPage);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    component.openEditor(limitFixture);
    component.onSliderInput({ target: { value: '1600' } } as unknown as Event);

    expect(component.amountControl.value).toBe(1600);
    component.save();

    expect(api.updateLimit).toHaveBeenCalledWith(limitFixture.id, '1600.00');
    expect(store.replaceLimit).toHaveBeenCalledWith(updated);
    expect(component.editState()).toBe('applied');
  });

  it('shows a conflict and refreshes stale limit data', () => {
    api.updateLimit.mockReturnValue(
      throwError(() => new DemoApiError('CONFLICT', 'stale', [], 'correlation', 409))
    );
    const fixture = TestBed.createComponent(LimitsPage);
    const component = fixture.componentInstance;
    component.openEditor(limitFixture);
    component.amountControl.setValue(1550);
    component.save();

    expect(component.editState()).toBe('conflict');
    expect(store.refresh).toHaveBeenCalledOnce();
  });

  it('asks for approval above the agreed maximum and shows the held change', () => {
    const transferLimit = {
      ...limitFixture,
      id: 'limit-transfer',
      operation: 'BANK_TRANSFER' as const,
      currentAmount: { amount: '5000.00', currency: 'EUR' },
      maximumAmount: { amount: '10000.00', currency: 'EUR' },
      agreedMaximum: { amount: '7500.00', currency: 'EUR' }
    };
    const held = {
      ...transferLimit,
      pendingChange: { id: 'change-1', requestedAmount: { amount: '9000.00', currency: 'EUR' }, effectiveAt: null }
    };
    api.updateLimit.mockReturnValue(of({ status: 'REQUESTED', message: 'The account holder has to approve this limit', limit: held }));
    const fixture = TestBed.createComponent(LimitsPage);
    const component = fixture.componentInstance;
    component.openEditor(transferLimit);
    component.amountControl.setValue(7000);
    expect(component.needsApproval(transferLimit)).toBe(false);
    component.amountControl.setValue(9000);
    expect(component.needsApproval(transferLimit)).toBe(true);

    component.save();

    expect(component.editState()).toBe('held');
    expect(store.replaceLimit).toHaveBeenCalledWith(held);
  });

  it('withdraws a held change', () => {
    const held = {
      ...limitFixture,
      pendingChange: { id: 'change-1', requestedAmount: { amount: '2000.00', currency: 'EUR' }, effectiveAt: null }
    };
    api.withdrawLimitChange.mockReturnValue(of({ status: 'CANCELLED', message: null, limit: limitFixture }));
    const component = TestBed.createComponent(LimitsPage).componentInstance;

    component.withdraw(held);

    expect(api.withdrawLimitChange).toHaveBeenCalledWith(held.id);
    expect(store.replaceLimit).toHaveBeenCalledWith(limitFixture);
    expect(component.withdrawing()).toBeNull();
  });

  it('tells the customer when a cooling-off raise starts', () => {
    const scheduled = {
      ...limitFixture,
      pendingChange: { id: 'change-2', requestedAmount: { amount: '2000.00', currency: 'EUR' }, effectiveAt: '2026-08-09T10:02:00Z' }
    };
    api.updateLimit.mockReturnValue(of({ status: 'PENDING_EFFECTIVE_DATE', message: null, limit: scheduled }));
    const component = TestBed.createComponent(LimitsPage).componentInstance;
    component.openEditor(limitFixture);
    component.amountControl.setValue(2000);

    component.save();

    expect(component.editState()).toBe('held');
    expect(component.editMessage()).toContain('11:02');
  });
});
