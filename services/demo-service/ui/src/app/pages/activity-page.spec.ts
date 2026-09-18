import { importProvidersFrom, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { LucideAngularModule, icons } from 'lucide-angular';
import { BankStore } from '../core/bank.store';
import { atmPaymentFixture, cardPaymentFixture } from '../core/test-fixtures';
import { ActivityPage } from './activity-page';

describe('ActivityPage', () => {
  const store = {
    payments: signal([cardPaymentFixture, atmPaymentFixture]),
    loading: signal(false),
    error: signal<string | null>(null),
    detailLoading: signal(false),
    paymentDetail: signal(null),
    load: vi.fn(),
    loadPayment: vi.fn(),
    clearPayment: vi.fn()
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ActivityPage],
      providers: [
        provideRouter([]),
        { provide: BankStore, useValue: store },
        importProvidersFrom(LucideAngularModule.pick(icons))
      ]
    }).compileComponents();
  });

  it('filters activity without exposing integration controls', () => {
    const fixture = TestBed.createComponent(ActivityPage);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.payment-row')).toHaveLength(2);

    const atm = Array.from<HTMLButtonElement>(fixture.nativeElement.querySelectorAll('.filter-control button'))
      .find((button) => button.textContent?.trim() === 'ATM');
    atm?.click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll('.payment-row')).toHaveLength(1);
    expect(fixture.nativeElement.textContent).toContain('Station Road ATM');
    expect(fixture.nativeElement.textContent).not.toContain('Create payment');
  });
});
