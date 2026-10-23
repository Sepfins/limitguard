import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, input, output, signal, viewChild } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { LucideAngularModule } from 'lucide-angular';
import { finalize } from 'rxjs';
import { apiErrorMessage } from '../core/api-error';
import { Channel, ChannelGroup } from '../core/admin-api.models';
import { AdminApiService } from '../core/admin-api.service';

@Component({
  selector: 'app-add-member-dialog',
  imports: [ReactiveFormsModule, LucideAngularModule],
  template: `
    <dialog #dialog class="admin-dialog" (cancel)="$event.preventDefault(); close()">
      <form [formGroup]="form" (ngSubmit)="submit()">
        <header>
          <div>
            <h2>Add channel</h2>
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
            <span>Channel</span>
            <select formControlName="channelId">
              <option value="">Choose a channel</option>
              @for (channel of candidates(); track channel.id) {
                <option [value]="channel.id">{{ channel.code }} - {{ channel.name }}</option>
              }
            </select>
            @if (channelId.touched && channelId.hasError('required')) {
              <small>Choose a channel to add.</small>
            }
          </label>
        </div>

        <footer>
          <button class="button secondary" type="button" (click)="close()">Cancel</button>
          <button class="button primary" type="submit" [disabled]="submitting()">
            {{ submitting() ? 'Adding...' : 'Add channel' }}
          </button>
        </footer>
      </form>
    </dialog>
  `,
  styleUrl: '../shared/dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class AddMemberDialog {
  readonly tenantId = input.required<string>();
  readonly channels = input<Channel[]>([]);
  readonly added = output<void>();
  private readonly api = inject(AdminApiService);
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');
  protected readonly group = signal<ChannelGroup | null>(null);
  protected readonly candidates = computed(() =>
    this.channels().filter((channel) => !this.group()?.memberChannelIds.includes(channel.id))
  );
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly channelId = new FormControl('', { nonNullable: true, validators: Validators.required });
  // Without a form group, (ngSubmit) never fires and the browser submits the page instead.
  protected readonly form = new FormGroup({ channelId: this.channelId });

  open(group: ChannelGroup): void {
    this.group.set(group);
    this.channelId.reset();
    this.error.set(null);
    this.dialog().nativeElement.showModal();
  }

  close(): void {
    if (!this.submitting()) this.dialog().nativeElement.close();
  }

  submit(): void {
    const group = this.group();
    if (!group || this.channelId.invalid || this.submitting()) {
      this.channelId.markAsTouched();
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    this.api
      .addChannelGroupMember(this.tenantId(), group.id, this.channelId.value)
      .pipe(finalize(() => this.submitting.set(false)))
      .subscribe({
        next: () => {
          this.added.emit();
          this.dialog().nativeElement.close();
        },
        error: (error) =>
          this.error.set(
            error instanceof HttpErrorResponse && error.status === 409
              ? 'That channel is already a member of this group.'
              : apiErrorMessage(error, 'The channel could not be added.')
          )
      });
  }
}
