import { ChangeDetectionStrategy, Component, ElementRef, output, signal, viewChild } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { LucideAngularModule } from 'lucide-angular';
import { finalize } from 'rxjs';
import { apiErrorMessage } from '../core/api-error';
import { NoMatchingRuleBehavior, Tenant } from '../core/admin-api.models';
import { AdminApiService } from '../core/admin-api.service';

const browserTimeZone = (): string => Intl.DateTimeFormat().resolvedOptions().timeZone;

@Component({
  selector: 'app-create-tenant-dialog',
  imports: [ReactiveFormsModule, LucideAngularModule],
  templateUrl: './create-tenant-dialog.html',
  styleUrl: './create-tenant-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class CreateTenantDialog {
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');
  readonly created = output<Tenant>();
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly timeZones = Intl.supportedValuesOf('timeZone');
  protected readonly form = new FormGroup({
    name: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(255)]
    }),
    timeZone: new FormControl(browserTimeZone(), { nonNullable: true, validators: [Validators.required] }),
    noMatchingRuleBehavior: new FormControl<NoMatchingRuleBehavior>('DENY', { nonNullable: true })
  });

  constructor(private readonly api: AdminApiService) {}

  open(): void {
    this.form.reset({ name: '', timeZone: browserTimeZone(), noMatchingRuleBehavior: 'DENY' });
    this.error.set(null);
    this.dialog().nativeElement.showModal();
  }

  close(): void {
    if (!this.submitting()) this.dialog().nativeElement.close();
  }

  submit(): void {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    this.api
      .createTenant(this.form.getRawValue())
      .pipe(finalize(() => this.submitting.set(false)))
      .subscribe({
        next: (tenant) => {
          this.created.emit(tenant);
          this.dialog().nativeElement.close();
        },
        error: (error) => this.error.set(apiErrorMessage(error, 'The tenant could not be created.'))
      });
  }
}
