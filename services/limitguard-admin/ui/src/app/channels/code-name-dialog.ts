import { ChangeDetectionStrategy, Component, ElementRef, input, output, signal, viewChild } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { LucideAngularModule } from 'lucide-angular';
import { Observable, finalize } from 'rxjs';
import { apiErrorMessage } from '../core/api-error';
import { CodeNameRequest } from '../core/admin-api.models';

// Creates a catalog entry that is only a code and a name, such as a channel or a channel group.
@Component({
  selector: 'app-code-name-dialog',
  imports: [ReactiveFormsModule, LucideAngularModule],
  template: `
    <dialog #dialog class="admin-dialog" (cancel)="$event.preventDefault(); close()">
      <form [formGroup]="form" (ngSubmit)="submit()">
        <header>
          <div>
            <h2>Create {{ noun() }}</h2>
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
        </div>

        <footer>
          <button class="button secondary" type="button" (click)="close()">Cancel</button>
          <button class="button primary" type="submit" [disabled]="submitting()">
            {{ submitting() ? 'Creating...' : 'Create ' + noun() }}
          </button>
        </footer>
      </form>
    </dialog>
  `,
  styleUrl: '../shared/dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class CodeNameDialog {
  readonly noun = input.required<string>();
  readonly save = input.required<(request: CodeNameRequest) => Observable<unknown>>();
  readonly created = output<void>();
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly form = new FormGroup({
    code: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(100)] }),
    name: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(255)] })
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
    this.submitting.set(true);
    this.error.set(null);
    this.save()(this.form.getRawValue())
      .pipe(finalize(() => this.submitting.set(false)))
      .subscribe({
        next: () => {
          this.created.emit();
          this.dialog().nativeElement.close();
        },
        error: (error) => this.error.set(apiErrorMessage(error, `The ${this.noun()} could not be created.`))
      });
  }
}
