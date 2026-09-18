import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { AbstractControl, FormControl, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { LucideAngularModule } from 'lucide-angular';
import { bankName } from '../core/brand';
import { formatDateTime, formatMoney, operationLabel } from '../core/format';
import { Account, BankCard, DemoOperation, PaymentCategory } from '../core/models';
import { PaymentSimulatorStore } from './payment-simulator.store';
import { PaymentMutationAction } from './payment-simulator.models';

interface OperationOption {
  value: DemoOperation;
  label: string;
  hint: string;
  icon: string;
}

interface CategoryOption {
  value: PaymentCategory;
  label: string;
}

function positiveAmount(control: AbstractControl): ValidationErrors | null {
  const amount = Number(control.value);
  return Number.isFinite(amount) && amount > 0 ? null : { positive: true };
}

@Component({
  selector: 'app-payment-simulator-page',
  imports: [LucideAngularModule, ReactiveFormsModule, RouterLink],
  templateUrl: './payment-simulator-page.html',
  styleUrl: './payment-simulator-page.css',
  providers: [PaymentSimulatorStore],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PaymentSimulatorPage {
  readonly store = inject(PaymentSimulatorStore);
  readonly bankName = bankName;

  readonly operation = signal<DemoOperation>('CARD_PURCHASE');
  readonly selectedAccountId = signal('');
  readonly operationOptions: OperationOption[] = [
    {
      value: 'CARD_PURCHASE',
      label: 'Card purchase',
      hint: 'Debit card payment',
      icon: 'credit-card',
    },
    {
      value: 'ATM_WITHDRAWAL',
      label: 'ATM withdrawal',
      hint: 'Cash withdrawal',
      icon: 'banknote',
    },
    {
      value: 'BANK_TRANSFER',
      label: 'Bank transfer',
      hint: 'Online transfer',
      icon: 'send',
    },
  ];
  readonly categories: CategoryOption[] = [
    { value: 'GROCERIES', label: 'Groceries' },
    { value: 'DINING', label: 'Dining' },
    { value: 'TRANSPORT', label: 'Transport' },
    { value: 'CASH', label: 'Cash' },
    { value: 'TRANSFER', label: 'Transfer' },
    { value: 'SHOPPING', label: 'Shopping' },
    { value: 'OTHER', label: 'Other' },
  ];

  readonly accountControl = new FormControl('', {
    nonNullable: true,
    validators: [Validators.required],
  });
  readonly cardControl = new FormControl('', { nonNullable: true });
  readonly amountControl = new FormControl('40.00', {
    nonNullable: true,
    validators: [Validators.required, Validators.pattern(/^[0-9]+(?:\.[0-9]{1,2})?$/), positiveAmount],
  });
  readonly descriptionControl = new FormControl('Coffee and groceries', {
    nonNullable: true,
    validators: [Validators.required, Validators.maxLength(160)],
  });
  readonly categoryControl = new FormControl<PaymentCategory>('GROCERIES', {
    nonNullable: true,
    validators: [Validators.required],
  });
  readonly externalPaymentIdControl = new FormControl(this.newReference(), {
    nonNullable: true,
    validators: [Validators.required, Validators.maxLength(100)],
  });

  readonly selectedAccount = computed<Account | null>(
    () => this.store.accounts().find((account) => account.id === this.selectedAccountId()) ?? null,
  );
  readonly accountCards = computed<BankCard[]>(() =>
    this.store.cards().filter((card) => card.accountId === this.selectedAccountId()),
  );
  readonly selectedLimit = computed(() => this.store.limitFor(this.operation()));
  readonly requiresCard = computed(() => this.operation() === 'CARD_PURCHASE' || this.operation() === 'ATM_WITHDRAWAL');
  readonly lifecycleStatus = computed(() => this.store.currentPaymentStatus());
  readonly requestLocked = computed(() => this.store.authorization() !== null);
  readonly canCapture = computed(
    () =>
      this.store.authorization()?.decision === 'ALLOWED' &&
      (this.lifecycleStatus() === 'AUTHORIZED' || this.lifecycleStatus() === 'PARTIALLY_CAPTURED'),
  );
  readonly canRelease = computed(
    () =>
      this.store.authorization()?.decision === 'ALLOWED' &&
      (this.lifecycleStatus() === 'AUTHORIZED' || this.lifecycleStatus() === 'PARTIALLY_CAPTURED'),
  );
  readonly canReverse = computed(
    () => this.store.authorization()?.decision === 'ALLOWED' && this.lifecycleStatus() === 'CAPTURED',
  );
  /** A declined, released, reversed, or expired payment has nothing left to do but start the next one. */
  readonly paymentSettled = computed(() => {
    const authorization = this.store.authorization();
    if (!authorization) return false;
    const status = this.lifecycleStatus();
    return authorization.decision !== 'ALLOWED' || status === 'VOIDED' || status === 'REVERSED' || status === 'EXPIRED';
  });

  readonly formatMoney = formatMoney;
  readonly formatDateTime = formatDateTime;
  readonly operationLabel = operationLabel;

  constructor() {
    effect(() => {
      const requestLocked = this.requestLocked();
      this.requestControls.forEach((control) => {
        if (requestLocked) control.disable({ emitEvent: false });
        else control.enable({ emitEvent: false });
      });

      const accounts = this.store.accounts();
      if (accounts.length > 0 && !this.accountControl.value) {
        const defaultAccount = accounts.find((account) => account.accountType === 'CURRENT') ?? accounts[0];
        this.accountControl.setValue(defaultAccount.id);
        this.selectedAccountId.set(defaultAccount.id);
      }

      const cards = this.accountCards();
      if (cards.length > 0 && !this.cardControl.value) {
        this.cardControl.setValue(cards[0].id);
      }
    });
  }

  ngOnInit(): void {
    this.store.load();
  }

  selectOperation(operation: DemoOperation): void {
    if (this.requestLocked()) return;
    this.operation.set(operation);
    this.store.clearResult();
    if (operation === 'ATM_WITHDRAWAL') this.categoryControl.setValue('CASH');
    if (operation === 'BANK_TRANSFER') {
      this.categoryControl.setValue('TRANSFER');
      this.cardControl.setValue('');
    } else if (!this.cardControl.value && this.accountCards().length > 0) {
      this.cardControl.setValue(this.accountCards()[0].id);
    }
  }

  onAccountChange(): void {
    if (this.requestLocked()) return;
    this.selectedAccountId.set(this.accountControl.value);
    const firstCard = this.accountCards()[0];
    const nextCard = firstCard?.id ?? '';
    this.cardControl.setValue(nextCard);
  }

  canSubmit(): boolean {
    const accountIsCurrent = this.selectedAccount()?.accountType === 'CURRENT';
    const cardIsValid = !this.requiresCard() || this.accountCards().some((card) => card.id === this.cardControl.value);
    return (
      !this.requestLocked() &&
      accountIsCurrent &&
      cardIsValid &&
      this.accountControl.valid &&
      this.amountControl.valid &&
      this.descriptionControl.valid &&
      this.categoryControl.valid &&
      this.externalPaymentIdControl.valid
    );
  }

  authorize(): void {
    this.amountControl.markAsTouched();
    this.descriptionControl.markAsTouched();
    this.externalPaymentIdControl.markAsTouched();
    if (!this.canSubmit()) return;

    this.store.authorize({
      externalPaymentId: this.externalPaymentIdControl.value.trim(),
      operation: this.operation(),
      accountId: this.accountControl.value,
      cardId: this.requiresCard() ? this.cardControl.value : null,
      description: this.descriptionControl.value.trim(),
      category: this.categoryControl.value,
      amount: this.amountControl.value.trim(),
      currency: 'EUR',
    });
  }

  onSubmit(event: SubmitEvent): void {
    event.preventDefault();
    this.authorize();
  }

  runMutation(action: PaymentMutationAction): void {
    const externalPaymentId = this.store.authorization()?.externalPaymentId;
    if (!externalPaymentId) return;
    this.store.mutate(action, externalPaymentId, {
      reason: `Payment simulator ${action}`,
    });
  }

  newPayment(): void {
    this.store.clearResult();
    this.externalPaymentIdControl.setValue(this.newReference());
    this.externalPaymentIdControl.markAsPristine();
    this.externalPaymentIdControl.markAsUntouched();
  }

  retryAuthorization(): void {
    const failedAction = this.store.failedAction();
    if (failedAction) this.runMutation(failedAction);
    else this.authorize();
  }

  statusClass(status: string | null): string {
    return (status === 'VOIDED' ? 'released' : (status ?? 'pending')).toLowerCase().replaceAll('_', '-');
  }

  statusLabel(status: string | null): string {
    const label = (status === 'VOIDED' ? 'RELEASED' : (status ?? 'PENDING')).replaceAll('_', ' ').toLowerCase();
    return label.charAt(0).toUpperCase() + label.slice(1);
  }

  operationCapacityLabel(): string {
    const limit = this.selectedLimit();
    if (!limit) return 'No limit configured';
    return `${formatMoney(limit.availableAmount)} available`;
  }

  accountLabel(account: Account): string {
    return `${account.displayName} · •••• ${account.maskedIban.slice(-4)}`;
  }

  utilization(): number {
    const limit = this.selectedLimit();
    if (!limit || Number(limit.currentAmount.amount) <= 0) return 0;
    return Math.min(
      100,
      Math.max(0, (Number(limit.currentExposure.amount) / Number(limit.currentAmount.amount)) * 100),
    );
  }

  private newReference(): string {
    const random =
      typeof crypto.randomUUID === 'function'
        ? crypto.randomUUID().slice(0, 8)
        : Math.random().toString(16).slice(2, 10);
    return `sim-${Date.now().toString(36)}-${random}`;
  }

  private readonly requestControls = [
    this.accountControl,
    this.cardControl,
    this.amountControl,
    this.descriptionControl,
    this.categoryControl,
    this.externalPaymentIdControl,
  ];
}
