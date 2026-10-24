import { ChangeDetectionStrategy, Component, ElementRef, inject, input, output, signal, viewChild } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { LucideAngularModule } from 'lucide-angular';
import { finalize } from 'rxjs';
import { apiErrorMessage } from '../core/api-error';
import { OperationType } from '../core/admin-api.models';
import { AdminApiService } from '../core/admin-api.service';
import { AuthStore } from '../core/auth.store';

@Component({
  selector: 'app-create-operation-type-dialog',
  imports: [ReactiveFormsModule, LucideAngularModule],
  template: `
    <dialog #dialog class="admin-dialog" (cancel)="$event.preventDefault(); close()">
      <form [formGroup]="form" (ngSubmit)="submit()">
        <header>
          <div>
            <h2>Create operation type</h2>
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
            <span>Parent operation type</span>
            <select formControlName="parentId">
              <option value="">No parent</option>
              @for (operation of parentOptions(); track operation.id) {
                @if (operation.isGlobal || !form.controls.isGlobal.value) {
                  <option [value]="operation.id">{{ operation.code }} - {{ operation.name }}</option>
                }
              }
            </select>
          </label>

          <div class="toggle-group">
            <label class="check-field">
              <input type="checkbox" formControlName="isLeaf" />
              <span>
                <strong>Leaf</strong>
                <small>No operation type can be nested under it</small>
              </span>
            </label>
            @if (auth.isPlatformAdmin()) {
              <label class="check-field">
                <input type="checkbox" formControlName="isGlobal" />
                <span>
                  <strong>Global</strong>
                  <small>Visible to every tenant; only a global parent is allowed</small>
                </span>
              </label>
            }
          </div>
        </div>

        <footer>
          <button class="button secondary" type="button" (click)="close()">Cancel</button>
          <button class="button primary" type="submit" [disabled]="submitting()">
            {{ submitting() ? 'Creating...' : 'Create operation type' }}
          </button>
        </footer>
      </form>
    </dialog>
  `,
  styleUrl: '../shared/dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class CreateOperationTypeDialog {
  readonly tenantId = input.required<string>();
  // Non-leaf operation types visible to the tenant.
  readonly parentOptions = input<OperationType[]>([]);
  readonly created = output<void>();
  private readonly api = inject(AdminApiService);
  protected readonly auth = inject(AuthStore);
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly form = new FormGroup({
    code: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(100)] }),
    name: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(255)] }),
    parentId: new FormControl('', { nonNullable: true }),
    isLeaf: new FormControl(false, { nonNullable: true }),
    isGlobal: new FormControl(false, { nonNullable: true })
  });

  open(): void {
    this.form.reset();
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
    const value = this.form.getRawValue();
    this.submitting.set(true);
    this.error.set(null);
    this.api
      .registerOperationType(this.tenantId(), { ...value, parentId: value.parentId || null })
      .pipe(finalize(() => this.submitting.set(false)))
      .subscribe({
        next: () => {
          this.created.emit();
          this.dialog().nativeElement.close();
        },
        error: (error) => this.error.set(apiErrorMessage(error, 'The operation type could not be created.'))
      });
  }
}
