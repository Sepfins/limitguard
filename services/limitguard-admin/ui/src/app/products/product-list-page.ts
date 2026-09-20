import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { LucideAngularModule } from 'lucide-angular';
import { debounceTime, distinctUntilChanged, finalize } from 'rxjs';
import { apiErrorMessage } from '../core/api-error';
import { FundingSourceProduct, PageResponse, Tenant } from '../core/admin-api.models';
import { AdminApiService } from '../core/admin-api.service';
import { AuthStore } from '../core/auth.store';
import { Guilloche } from '../shared/guilloche';
import { CreateProductDialog } from './create-product-dialog';

type ActiveFilter = 'all' | 'active' | 'inactive';

@Component({
  selector: 'app-product-list-page',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    CreateProductDialog,
    LucideAngularModule,
    Guilloche
  ],
  templateUrl: './product-list-page.html',
  styleUrl: './product-list-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class ProductListPage {
  protected readonly tenantId = inject(ActivatedRoute).snapshot.paramMap.get('tenantId') ?? '';
  private readonly api = inject(AdminApiService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly createDialog = viewChild.required(CreateProductDialog);
  protected readonly auth = inject(AuthStore);
  protected readonly tenant = signal<Tenant | null>(null);
  protected readonly search = new FormControl('', { nonNullable: true });
  protected readonly activeFilter = signal<ActiveFilter>('all');
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly parentCatalog = signal<FundingSourceProduct[]>([]);
  protected readonly result = signal<PageResponse<FundingSourceProduct>>({
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
    this.loadTenant();
    this.load(0, true);
  }

  protected load(page = this.result().page, refreshParents = false): void {
    this.loading.set(true);
    this.error.set(null);
    const active = this.activeFilter() === 'all' ? null : this.activeFilter() === 'active';
    this.api
      .listFundingSourceProducts(this.tenantId, this.search.value, active, page)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (result) => this.result.set(result),
        error: (error) => this.error.set(apiErrorMessage(error, 'Products could not be loaded.'))
      });
    if (refreshParents) {
      this.api.listFundingSourceProducts(this.tenantId, '', null, 0, 100).subscribe({
        next: (result) => this.parentCatalog.set(result.items),
        error: () => this.parentCatalog.set([])
      });
    }
  }

  protected setActiveFilter(filter: ActiveFilter): void {
    if (filter === this.activeFilter()) return;
    this.activeFilter.set(filter);
    this.load(0);
  }

  protected openCreateDialog(): void {
    this.createDialog().open();
  }

  protected productCreated(): void {
    this.search.setValue('', { emitEvent: false });
    this.activeFilter.set('all');
    this.load(0, true);
  }

  protected parentName(parentId: string | null): string {
    if (!parentId) return 'Root';
    const parent = this.parentCatalog().find((candidate) => candidate.id === parentId);
    return parent ? `${parent.code} - ${parent.name}` : parentId.slice(0, 8);
  }

  protected label(value: string): string {
    return value.replaceAll('_', ' ');
  }

  private loadTenant(): void {
    this.api.listTenants('', 0, 100).subscribe({
      next: (result) => this.tenant.set(result.items.find((tenant) => tenant.id === this.tenantId) ?? null)
    });
  }
}
