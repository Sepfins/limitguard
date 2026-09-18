import { ChangeDetectionStrategy, Component, HostListener, inject, signal } from '@angular/core';
import {
  AbstractControl,
  FormControl,
  ReactiveFormsModule,
  ValidationErrors,
  ValidatorFn,
  Validators
} from '@angular/forms';
import { LucideAngularModule } from 'lucide-angular';
import { BankStore } from '../core/bank.store';
import { DemoApiError, DemoApiService } from '../core/demo-api.service';
import { formatDateTime, formatMoney, formatTime } from '../core/format';
import { LimitSetting } from '../core/models';
import { LimitBar } from '../shared/limit-bar';

type EditState = 'idle' | 'pending' | 'applied' | 'held' | 'rejected' | 'conflict';

@Component({
  selector: 'app-limits-page',
  imports: [LucideAngularModule, ReactiveFormsModule, LimitBar],
  templateUrl: './limits-page.html',
  styleUrl: './limits-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class LimitsPage {
  private readonly api = inject(DemoApiService);

  readonly store = inject(BankStore);
  readonly selectedLimit = signal<LimitSetting | null>(null);
  readonly editState = signal<EditState>('idle');
  readonly editMessage = signal<string | null>(null);
  readonly amountControl = new FormControl(0, { nonNullable: true });
  readonly withdrawing = signal<string | null>(null);
  readonly withdrawError = signal<string | null>(null);

  readonly formatMoney = formatMoney;
  readonly formatTime = formatTime;

  openEditor(limit: LimitSetting): void {
    const minimum = Number(limit.minimumAmount.amount);
    const maximum = Number(limit.maximumAmount.amount);
    this.amountControl.setValidators([
      Validators.required,
      Validators.min(minimum),
      Validators.max(maximum),
      this.stepValidator(minimum, Number(limit.stepAmount.amount))
    ]);
    this.amountControl.setValue(Number(limit.currentAmount.amount));
    this.amountControl.markAsPristine();
    this.amountControl.markAsUntouched();
    this.editState.set('idle');
    this.editMessage.set(null);
    this.selectedLimit.set(limit);
  }

  closeEditor(): void {
    if (this.editState() === 'pending') return;
    this.selectedLimit.set(null);
    this.editState.set('idle');
    this.editMessage.set(null);
  }

  save(): void {
    const limit = this.selectedLimit();
    if (!limit) return;
    this.amountControl.markAsTouched();
    if (this.amountControl.invalid) return;

    this.editState.set('pending');
    this.editMessage.set(null);
    this.api.updateLimit(limit.id, Number(this.amountControl.value).toFixed(2)).subscribe({
      next: (response) => {
        this.store.replaceLimit(response.limit);
        if (response.status === 'APPLIED') {
          this.editState.set('applied');
          this.editMessage.set('Your new limit is active now.');
        } else if (response.status === 'REQUESTED') {
          this.editState.set('held');
          this.editMessage.set('Sent to the account holder. The new limit starts once they approve it.');
        } else if (response.status === 'PENDING_EFFECTIVE_DATE') {
          const startsAt = response.limit.pendingChange?.effectiveAt;
          this.editState.set('held');
          this.editMessage.set(
            startsAt
              ? `For your security, a raised limit starts after a cooling-off period, at ${formatTime(startsAt)}.`
              : 'For your security, a raised limit starts after a cooling-off period.'
          );
        } else {
          this.editState.set('rejected');
          this.editMessage.set(response.message ?? 'This change was not approved.');
        }
      },
      error: (error: DemoApiError) => {
        if (error.status === 409) {
          this.editState.set('conflict');
          this.editMessage.set('The limit changed while you were editing. Review the latest value and try again.');
          this.store.refresh();
        } else {
          this.editState.set('rejected');
          this.editMessage.set(error.fieldErrors[0]?.message ?? error.message);
        }
      }
    });
  }

  withdraw(limit: LimitSetting): void {
    this.withdrawing.set(limit.id);
    this.withdrawError.set(null);
    this.api.withdrawLimitChange(limit.id).subscribe({
      next: (response) => {
        this.withdrawing.set(null);
        this.store.replaceLimit(response.limit);
      },
      error: (error: DemoApiError) => {
        this.withdrawing.set(null);
        this.withdrawError.set(error.message);
        this.store.refresh();
      }
    });
  }

  /** Whether the amount in the editor goes past what the account holder agreed to. */
  needsApproval(limit: LimitSetting): boolean {
    return limit.agreedMaximum !== null && Number(this.amountControl.value) > Number(limit.agreedMaximum.amount);
  }

  onSliderInput(event: Event): void {
    const value = Number((event.target as HTMLInputElement).value);
    this.amountControl.setValue(value);
    this.amountControl.markAsDirty();
  }

  /** When the limit starts over, in the words a customer would use. */
  resetWords(limit: LimitSetting): string {
    if (limit.limitKind === 'PER_TRANSACTION') return 'Checked on every payment';
    if (!limit.resetsAt) return `${limit.period?.toLowerCase() ?? ''} limit`;
    const resets = new Date(limit.resetsAt);
    const day = (date: Date) => new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Dublin' }).format(date);
    const time = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Dublin' }).format(resets);
    const tomorrow = new Date(Date.now() + 24 * 60 * 60 * 1000);
    if (day(resets) === day(new Date())) return `Resets today at ${time}`;
    if (day(resets) === day(tomorrow)) return `Resets ${Number(time.slice(0, 2)) < 6 ? 'tonight' : 'tomorrow'} at ${time}`;
    return `Resets ${formatDateTime(limit.resetsAt)}`;
  }

  editorValue(limit: LimitSetting): string {
    return formatMoney({ amount: Number(this.amountControl.value).toFixed(2), currency: limit.currentAmount.currency });
  }

  controlError(limit: LimitSetting): string | null {
    if (!this.amountControl.touched || !this.amountControl.errors) return null;
    if (this.amountControl.hasError('min')) return `Minimum ${formatMoney(limit.minimumAmount)}`;
    if (this.amountControl.hasError('max')) return `Maximum ${formatMoney(limit.maximumAmount)}`;
    if (this.amountControl.hasError('step')) return `Use ${formatMoney(limit.stepAmount)} increments`;
    return 'Enter a valid amount.';
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    if (this.selectedLimit()) this.closeEditor();
  }

  private stepValidator(minimum: number, step: number): ValidatorFn {
    return (control: AbstractControl): ValidationErrors | null => {
      const value = Number(control.value);
      if (!Number.isFinite(value) || step <= 0) return null;
      const increments = (value - minimum) / step;
      return Math.abs(increments - Math.round(increments)) < 0.000001 ? null : { step: true };
    };
  }
}
