import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { LucideAngularModule } from 'lucide-angular';
import { debounceTime, distinctUntilChanged, finalize } from 'rxjs';
import { apiErrorMessage } from '../core/api-error';
import { NoMatchingRuleBehavior, PageResponse, Tenant } from '../core/admin-api.models';
import { AdminApiService } from '../core/admin-api.service';
import { AuthStore } from '../core/auth.store';
import { Guilloche } from '../shared/guilloche';
import { CreateTenantDialog } from './create-tenant-dialog';

@Component({
  selector: 'app-tenant-list-page',
  imports: [
    ReactiveFormsModule,
    DatePipe,
    RouterLink,
    CreateTenantDialog,
    LucideAngularModule,
    Guilloche
  ],
  templateUrl: './tenant-list-page.html',
  styleUrls: ['../shared/list-page.css', './tenant-list-page.css'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class TenantListPage {
  private readonly createDialog = viewChild.required(CreateTenantDialog);
  private readonly api = inject(AdminApiService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly auth = inject(AuthStore);
  protected readonly search = new FormControl('', { nonNullable: true });
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly result = signal<PageResponse<Tenant>>({
    items: [],
    page: 0,
    size: 25,
    totalElements: 0,
    totalPages: 0
  });

  constructor() {
    this.search.valueChanges
      .pipe(debounceTime(250), distinctUntilChanged(), takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.load(0));
    this.load(0);
  }

  protected load(page = this.result().page): void {
    this.loading.set(true);
    this.error.set(null);
    this.api
      .listTenants(this.search.value, page)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (result) => this.result.set(result),
        error: (error) => this.error.set(apiErrorMessage(error, 'Tenants could not be loaded.'))
      });
  }

  protected openCreateDialog(): void {
    this.createDialog().open();
  }

  protected tenantCreated(): void {
    this.search.setValue('', { emitEvent: false });
    this.load(0);
  }

  protected fallbackLabel(behavior: NoMatchingRuleBehavior): string {
    return { ALLOW: 'Allow', DENY: 'Deny', NOT_APPLICABLE: 'Not applicable' }[behavior];
  }
}
