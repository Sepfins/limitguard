import { ChangeDetectionStrategy, Component, ElementRef, inject, input, output, signal, viewChild } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { LucideAngularModule } from 'lucide-angular';
import { finalize } from 'rxjs';
import { apiErrorMessage } from '../core/api-error';
import { LimitConfiguration } from '../core/admin-api.models';
import { AdminApiService } from '../core/admin-api.service';
import { decimalAmount } from './configuration-catalog';

@Component({
  selector: 'app-edit-limit-configuration-dialog',
  imports: [ReactiveFormsModule, LucideAngularModule],
  template: `
    <dialog #dialog class="admin-dialog" (cancel)="$event.preventDefault(); close()">
      <form [formGroup]="form" (ngSubmit)="submit()">
        <header>
          <div>
            <h2>Edit limit configuration</h2>
            <p class="dialog-sub"><code>{{ configuration()?.code }}</code></p>
          </div>
          <button class="icon-button" type="button" title="Close dialog" aria-label="Close dialog" (click)="close()">
            <lucide-icon name="x" [size]="19" />
          </button>
        </header>

        <div class="dialog-body">
          @if (error()) {
            <div class="inline-alert" role="alert">{{ error() }}</div>
          }
          <label class="field">
            <span>Name</span>
            <input formControlName="name" autocomplete="off" maxlength="255" />
            @if (form.controls.name.touched && form.controls.name.hasError('required')) {
              <small>A name is required.</small>
            }
          </label>
          <label class="field">
            <span>Description</span>
            <input formControlName="description" autocomplete="off" />
          </label>
          <label class="field">
            <span>Default amount ({{ configuration()?.currency }})</span>
            <input formControlName="defaultAmount" inputmode="decimal" autocomplete="off" />
          </label>
        </div>

        <footer>
          <button class="button secondary" type="button" (click)="close()">Cancel</button>
          <button class="button primary" type="submit" [disabled]="submitting()">
            {{ submitting() ? 'Saving...' : 'Save changes' }}
          </button>
        </footer>
      </form>
    </dialog>
  `,
  styleUrl: '../shared/dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class EditLimitConfigurationDialog {
  readonly tenantId = input.required<string>();
  readonly saved = output<void>();
  private readonly api = inject(AdminApiService);
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');
  protected readonly configuration = signal<LimitConfiguration | null>(null);
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(255)] }),
    description: new FormControl('', { nonNullable: true }),
    defaultAmount: new FormControl('', { nonNullable: true, validators: Validators.pattern(decimalAmount) })
  });

  open(configuration: LimitConfiguration): void {
    this.configuration.set(configuration);
    this.form.reset({
      name: configuration.name,
      description: configuration.description ?? '',
      defaultAmount: configuration.defaultAmount ?? ''
    });
    this.error.set(null);
    this.dialog().nativeElement.showModal();
  }

  close(): void {
    if (!this.submitting()) this.dialog().nativeElement.close();
  }

  submit(): void {
    const configuration = this.configuration();
    if (!configuration || this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    this.submitting.set(true);
    this.error.set(null);
    this.api
      .updateLimitConfiguration(this.tenantId(), configuration.id, {
        name: value.name,
        description: value.description || null,
        defaultAmount: value.defaultAmount === '' ? null : value.defaultAmount
      })
      .pipe(finalize(() => this.submitting.set(false)))
      .subscribe({
        next: () => {
          this.saved.emit();
          this.dialog().nativeElement.close();
        },
        error: (error) => this.error.set(apiErrorMessage(error, 'The limit configuration could not be saved.'))
      });
  }
}
