import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { LucideAngularModule } from 'lucide-angular';
import { debounceTime, distinctUntilChanged, finalize, forkJoin } from 'rxjs';
import { apiErrorMessage } from '../core/api-error';
import { LimitConfiguration, PageResponse } from '../core/admin-api.models';
import { AdminApiService } from '../core/admin-api.service';
import { AuthStore } from '../core/auth.store';
import { CopyId } from '../shared/copy-id';
import { amountLabel, ConfigurationCatalog, emptyCatalog, kindLabel, scopeSummary } from './configuration-catalog';
import { CreateLimitConfigurationDialog } from './create-limit-configuration-dialog';
import { EditLimitConfigurationDialog } from './edit-limit-configuration-dialog';
import { Guilloche } from '../shared/guilloche';

type ActiveFilter = 'all' | 'active' | 'inactive';

const emptyPage = { items: [], page: 0, size: 25, totalElements: 0, totalPages: 0 };

@Component({
  selector: 'app-limit-configuration-list-page',
  imports: [ReactiveFormsModule, CreateLimitConfigurationDialog, EditLimitConfigurationDialog, CopyId, LucideAngularModule, Guilloche],
  templateUrl: './limit-configuration-list-page.html',
  styleUrl: '../shared/list-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class LimitConfigurationListPage {
  protected readonly tenantId = inject(ActivatedRoute).snapshot.paramMap.get('tenantId') ?? '';
  private readonly api = inject(AdminApiService);
  protected readonly auth = inject(AuthStore);
  protected readonly search = new FormControl('', { nonNullable: true });
  protected readonly parentFilter = new FormControl('', { nonNullable: true });
  protected readonly activeFilter = signal<ActiveFilter>('all');
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly configurations = signal<PageResponse<LimitConfiguration>>(emptyPage);
  protected readonly catalog = signal<ConfigurationCatalog>(emptyCatalog);
  protected readonly kindLabel = kindLabel;
  protected readonly amountLabel = amountLabel;
  protected readonly scopeSummary = scopeSummary;

  constructor() {
    const destroyRef = inject(DestroyRef);
    this.search.valueChanges
      .pipe(debounceTime(250), distinctUntilChanged(), takeUntilDestroyed(destroyRef))
      .subscribe(() => this.load(0));
    this.parentFilter.valueChanges.pipe(takeUntilDestroyed(destroyRef)).subscribe(() => this.load(0));
    this.load(0);
    this.loadCatalog();
  }

  protected load(page = this.configurations().page): void {
    const filter = this.activeFilter();
    this.loading.set(true);
    this.error.set(null);
    this.api
      .listLimitConfigurations(
        this.tenantId,
        this.search.value,
        filter === 'all' ? null : filter === 'active',
        this.parentFilter.value || null,
        page
      )
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (result) => this.configurations.set(result),
        error: (error) => this.error.set(apiErrorMessage(error, 'Limit configurations could not be loaded.'))
      });
  }

  protected loadCatalog(): void {
    forkJoin({
      products: this.api.listFundingSourceProducts(this.tenantId, '', null, 0, 100),
      channels: this.api.listChannels(this.tenantId, '', null, 0, 100),
      groups: this.api.listChannelGroups(this.tenantId, '', 0, 100),
      operations: this.api.listOperationTypes(this.tenantId, '', null, 0, 100),
      configurations: this.api.listLimitConfigurations(this.tenantId, '', null, null, 0, 100)
    }).subscribe({
      next: (pages) =>
        this.catalog.set({
          products: pages.products.items,
          channels: pages.channels.items,
          groups: pages.groups.items,
          operations: pages.operations.items,
          configurations: pages.configurations.items
        }),
      error: () => this.catalog.set(emptyCatalog)
    });
  }

  protected setActiveFilter(filter: ActiveFilter): void {
    if (filter === this.activeFilter()) return;
    this.activeFilter.set(filter);
    this.load(0);
  }

  protected created(): void {
    this.search.setValue('', { emitEvent: false });
    this.parentFilter.setValue('', { emitEvent: false });
    this.activeFilter.set('all');
    this.load(0);
    this.loadCatalog();
  }

  protected changed(): void {
    this.load();
    this.loadCatalog();
  }

  protected toggleActive(configuration: LimitConfiguration): void {
    const question = configuration.isActive
      ? `Deactivate ${configuration.code}? No new limit rules can be granted under it. ` +
        'Rules already granted to customers stay as they are.'
      : `Reactivate ${configuration.code}? New limit rules can be granted under it again.`;
    if (!window.confirm(question)) return;
    this.api.setLimitConfigurationActive(this.tenantId, configuration.id, !configuration.isActive).subscribe({
      next: () => this.changed(),
      error: (error) => this.error.set(apiErrorMessage(error, 'The limit configuration could not be changed.'))
    });
  }
}
