import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, input, output, signal, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { LucideAngularModule } from 'lucide-angular';
import { finalize } from 'rxjs';
import { apiErrorMessage } from '../core/api-error';
import {
  FundingSourceCategory,
  LimitChannelScope,
  LimitConfigurationFundingScope,
  LimitKind,
  LimitPeriod,
  LimitUsageConsumptionMode
} from '../core/admin-api.models';
import { AdminApiService } from '../core/admin-api.service';
import {
  amountLabel,
  ConfigurationCatalog,
  decimalAmount,
  emptyCatalog,
  isoOrNull,
  kindLabel,
  scopeSummary
} from './configuration-catalog';

const categories: FundingSourceCategory[] = [
  'TRANSACTIONAL',
  'SAVINGS',
  'TERM_DEPOSIT',
  'PREPAID',
  'CREDIT',
  'SECURITIES',
  'EWALLET'
];

@Component({
  selector: 'app-create-limit-configuration-dialog',
  imports: [ReactiveFormsModule, LucideAngularModule],
  template: `
    <dialog #dialog class="admin-dialog" (cancel)="$event.preventDefault(); close()">
      <form [formGroup]="form" (ngSubmit)="submit()">
        <header>
          <div>
            <h2>Create limit configuration</h2>
          </div>
          <button class="icon-button" type="button" title="Close dialog" aria-label="Close dialog" (click)="close()">
            <lucide-icon name="x" [size]="19" />
          </button>
        </header>

        <div class="dialog-body">
          @if (error()) {
            <div class="inline-alert" role="alert">{{ error() }}</div>
          }
          <div class="form-grid two-columns">
            <label class="field">
              <span>Code</span>
              <input formControlName="code" autocomplete="off" maxlength="100" />
              @if (form.controls.code.touched && form.controls.code.hasError('required')) {
                <small>A code is required.</small>
              }
            </label>
            <label class="field">
              <span>Name</span>
              <input formControlName="name" autocomplete="off" maxlength="255" />
              @if (form.controls.name.touched && form.controls.name.hasError('required')) {
                <small>A name is required.</small>
              }
            </label>
          </div>
          <label class="field">
            <span>Description</span>
            <input formControlName="description" autocomplete="off" />
          </label>

          <label class="field">
            <span>Configuration hierarchy: parent</span>
            <select formControlName="parentConfigurationId" (change)="parentChosen()">
              <option value="">No parent</option>
              @for (configuration of parentOptions(); track configuration.id) {
                <option [value]="configuration.id">{{ configuration.code }} - {{ configuration.name }}</option>
              }
            </select>
          </label>
          @if (parent(); as parent) {
            <aside class="envelope" aria-label="Parent envelope">
              <strong>The child must fit inside {{ parent.code }}</strong>
              <span>{{ kindLabel(parent) }}, {{ parent.currency }}, at most {{ amountLabel(parent) }}</span>
              <span>{{ scopeSummary(parent, catalog()) }}</span>
            </aside>
          }

          <div class="form-grid two-columns">
            <label class="field">
              <span>Kind</span>
              <select formControlName="limitKind">
                <option value="PER_TRANSACTION">Per transaction</option>
                <option value="PERIODIC">Periodic</option>
              </select>
            </label>
            @if (form.controls.limitKind.value === 'PERIODIC') {
              <label class="field">
                <span>Period</span>
                <select formControlName="period">
                  <option value="DAILY">Daily</option>
                  <option value="MONTHLY">Monthly</option>
                </select>
              </label>
            }
          </div>

          <div class="form-grid two-columns">
            <label class="field">
              <span>Currency</span>
              <input formControlName="currency" autocomplete="off" maxlength="3" placeholder="EUR" />
              @if (form.controls.currency.touched && form.controls.currency.invalid) {
                <small>A three-letter currency code is required.</small>
              }
            </label>
            <label class="field">
              <span>Default amount</span>
              <input formControlName="defaultAmount" inputmode="decimal" autocomplete="off" />
            </label>
          </div>

          <div class="form-grid two-columns">
            <label class="field">
              <span>Maximum assignable amount</span>
              <input formControlName="maxAssignableAmount" inputmode="decimal" autocomplete="off" />
              @if (form.controls.maxAssignableAmount.touched && form.hasError('maxRequired')) {
                <small>Enter an amount or mark it unlimited.</small>
              } @else if (form.controls.maxAssignableAmount.invalid) {
                <small>Enter a decimal amount such as 1500.00.</small>
              }
            </label>
            <label class="check-field">
              <input type="checkbox" formControlName="maxAssignableUnlimited" />
              <span>
                <strong>Unlimited</strong>
                <small>Customers may be granted an explicit unlimited rule</small>
              </span>
            </label>
          </div>

          <div class="form-grid two-columns">
            <label class="field">
              <span>Configuration funding scope</span>
              <select formControlName="fundingScope">
                <option value="ALL_FUNDING_SOURCES">All funding sources</option>
                <option value="FUNDING_SOURCE_CATEGORY">One category</option>
                <option value="FUNDING_SOURCE_PRODUCT">One product</option>
              </select>
            </label>
            @if (form.controls.fundingScope.value === 'FUNDING_SOURCE_CATEGORY') {
              <label class="field">
                <span>Category</span>
                <select formControlName="fundingSourceCategory">
                  @for (category of categories; track category) {
                    <option [value]="category">{{ category }}</option>
                  }
                </select>
              </label>
            } @else if (form.controls.fundingScope.value === 'FUNDING_SOURCE_PRODUCT') {
              <label class="field">
                <span>Product</span>
                <select formControlName="fundingSourceProductId">
                  @for (product of catalog().products; track product.id) {
                    <option [value]="product.id">{{ product.code }} - {{ product.name }}</option>
                  }
                </select>
              </label>
            }
          </div>

          <div class="form-grid two-columns">
            <label class="field">
              <span>Channel scope</span>
              <select formControlName="channelScope">
                <option value="ALL_CHANNELS">All channels</option>
                <option value="CHANNEL">One channel</option>
                <option value="CHANNEL_GROUP">One channel group</option>
              </select>
            </label>
            @if (form.controls.channelScope.value === 'CHANNEL') {
              <label class="field">
                <span>Channel</span>
                <select formControlName="channelId">
                  @for (channel of catalog().channels; track channel.id) {
                    <option [value]="channel.id">{{ channel.code }} - {{ channel.name }}</option>
                  }
                </select>
              </label>
            } @else if (form.controls.channelScope.value === 'CHANNEL_GROUP') {
              <label class="field">
                <span>Channel group</span>
                <select formControlName="channelGroupId">
                  @for (group of catalog().groups; track group.id) {
                    <option [value]="group.id">{{ group.code }} - {{ group.name }}</option>
                  }
                </select>
              </label>
            }
          </div>

          <label class="field">
            <span>Operation scope</span>
            <select formControlName="operationTypeId">
              <option value="">All operations</option>
              @for (operation of catalog().operations; track operation.id) {
                <option [value]="operation.id">{{ operation.code }} - {{ operation.name }}</option>
              }
            </select>
          </label>

          <div class="form-grid two-columns">
            <label class="field">
              <span>Priority</span>
              <input formControlName="priority" type="number" step="1" />
            </label>
            <label class="field">
              <span>Usage consumption</span>
              <select formControlName="usageConsumptionMode">
                <option value="CASCADING">Cascading usage</option>
                <option value="ISOLATED">Isolated usage</option>
              </select>
            </label>
          </div>

          <div class="form-grid two-columns">
            <label class="field">
              <span>Effective from (empty for now)</span>
              <input formControlName="startsAt" type="datetime-local" />
            </label>
            <label class="field">
              <span>Effective until (optional)</span>
              <input formControlName="endsAt" type="datetime-local" />
            </label>
          </div>
        </div>

        <footer>
          <button class="button secondary" type="button" (click)="close()">Cancel</button>
          <button class="button primary" type="submit" [disabled]="submitting()">
            {{ submitting() ? 'Creating...' : 'Create configuration' }}
          </button>
        </footer>
      </form>
    </dialog>
  `,
  styleUrl: '../shared/dialog.css',
  styles: `
    .envelope {
      display: grid;
      gap: 4px;
      padding: 12px 14px;
      font-size: 12px;
      background: var(--surface-subtle);
      border: 1px solid var(--border);
      border-radius: var(--radius);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class CreateLimitConfigurationDialog {
  readonly tenantId = input.required<string>();
  readonly catalog = input<ConfigurationCatalog>(emptyCatalog);
  readonly created = output<void>();
  private readonly api = inject(AdminApiService);
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly categories = categories;
  protected readonly kindLabel = kindLabel;
  protected readonly amountLabel = amountLabel;
  protected readonly scopeSummary = scopeSummary;
  protected readonly form = new FormGroup(
    {
      code: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(100)] }),
      name: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(255)] }),
      description: new FormControl('', { nonNullable: true }),
      parentConfigurationId: new FormControl('', { nonNullable: true }),
      limitKind: new FormControl<LimitKind>('PERIODIC', { nonNullable: true }),
      period: new FormControl<LimitPeriod>('DAILY', { nonNullable: true }),
      currency: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required, Validators.pattern(/^[A-Za-z]{3}$/)]
      }),
      maxAssignableAmount: new FormControl('', { nonNullable: true, validators: Validators.pattern(decimalAmount) }),
      maxAssignableUnlimited: new FormControl(false, { nonNullable: true }),
      defaultAmount: new FormControl('', { nonNullable: true, validators: Validators.pattern(decimalAmount) }),
      fundingScope: new FormControl<LimitConfigurationFundingScope>('ALL_FUNDING_SOURCES', { nonNullable: true }),
      fundingSourceCategory: new FormControl<FundingSourceCategory>('TRANSACTIONAL', { nonNullable: true }),
      fundingSourceProductId: new FormControl('', { nonNullable: true }),
      channelScope: new FormControl<LimitChannelScope>('ALL_CHANNELS', { nonNullable: true }),
      channelId: new FormControl('', { nonNullable: true }),
      channelGroupId: new FormControl('', { nonNullable: true }),
      operationTypeId: new FormControl('', { nonNullable: true }),
      priority: new FormControl(100, { nonNullable: true }),
      usageConsumptionMode: new FormControl<LimitUsageConsumptionMode>('CASCADING', { nonNullable: true }),
      startsAt: new FormControl('', { nonNullable: true }),
      endsAt: new FormControl('', { nonNullable: true })
    },
    {
      validators: (group) => {
        const value = (group as FormGroup).getRawValue();
        return value.maxAssignableUnlimited || value.maxAssignableAmount !== '' ? null : { maxRequired: true };
      }
    }
  );
  private readonly parentId = toSignal(this.form.controls.parentConfigurationId.valueChanges, { initialValue: '' });
  protected readonly parentOptions = computed(() => this.catalog().configurations.filter((c) => c.isActive));
  protected readonly parent = computed(() => this.catalog().configurations.find((c) => c.id === this.parentId()));

  constructor() {
    this.form.controls.maxAssignableUnlimited.valueChanges.subscribe((unlimited) => {
      const amount = this.form.controls.maxAssignableAmount;
      if (unlimited) {
        amount.setValue('');
        amount.disable();
      } else {
        amount.enable();
      }
    });
  }

  open(): void {
    this.form.reset();
    this.error.set(null);
    this.dialog().nativeElement.showModal();
  }

  close(): void {
    if (!this.submitting()) this.dialog().nativeElement.close();
  }

  // A child shares its parent's kind, period and currency, so those are copied as a starting point.
  protected parentChosen(): void {
    const parent = this.parent();
    if (!parent) return;
    this.form.patchValue({
      limitKind: parent.limitKind,
      period: parent.period ?? 'DAILY',
      currency: parent.currency
    });
  }

  submit(): void {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    this.submitting.set(true);
    this.error.set(null);
    this.api
      .createLimitConfiguration(this.tenantId(), {
        code: value.code,
        name: value.name,
        description: value.description || null,
        limitKind: value.limitKind,
        period: value.limitKind === 'PERIODIC' ? value.period : null,
        currency: value.currency.toUpperCase(),
        maxAssignableAmount: value.maxAssignableUnlimited ? null : value.maxAssignableAmount,
        maxAssignableUnlimited: value.maxAssignableUnlimited,
        defaultAmount: value.defaultAmount === '' ? null : value.defaultAmount,
        parentConfigurationId: value.parentConfigurationId || null,
        fundingScope: value.fundingScope,
        fundingSourceCategory: value.fundingScope === 'FUNDING_SOURCE_CATEGORY' ? value.fundingSourceCategory : null,
        fundingSourceProductId:
          value.fundingScope === 'FUNDING_SOURCE_PRODUCT' ? value.fundingSourceProductId || null : null,
        channelScope: value.channelScope,
        channelId: value.channelScope === 'CHANNEL' ? value.channelId || null : null,
        channelGroupId: value.channelScope === 'CHANNEL_GROUP' ? value.channelGroupId || null : null,
        operationTypeId: value.operationTypeId || null,
        priority: value.priority,
        usageConsumptionMode: value.usageConsumptionMode,
        startsAt: isoOrNull(value.startsAt),
        endsAt: isoOrNull(value.endsAt)
      })
      .pipe(finalize(() => this.submitting.set(false)))
      .subscribe({
        next: () => {
          this.created.emit();
          this.dialog().nativeElement.close();
        },
        error: (error) => this.error.set(apiErrorMessage(error, 'The limit configuration could not be created.'))
      });
  }
}
