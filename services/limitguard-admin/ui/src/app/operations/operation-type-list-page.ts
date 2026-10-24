import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { LucideAngularModule } from 'lucide-angular';
import { debounceTime, distinctUntilChanged, finalize } from 'rxjs';
import { apiErrorMessage } from '../core/api-error';
import { OperationType, PageResponse } from '../core/admin-api.models';
import { AdminApiService } from '../core/admin-api.service';
import { AuthStore } from '../core/auth.store';
import { CopyId } from '../shared/copy-id';
import { CreateOperationTypeDialog } from './create-operation-type-dialog';
import { Guilloche } from '../shared/guilloche';

const emptyPage = { items: [], page: 0, size: 25, totalElements: 0, totalPages: 0 };

@Component({
  selector: 'app-operation-type-list-page',
  imports: [ReactiveFormsModule, CreateOperationTypeDialog, CopyId, LucideAngularModule, Guilloche],
  templateUrl: './operation-type-list-page.html',
  styleUrl: '../shared/list-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class OperationTypeListPage {
  protected readonly tenantId = inject(ActivatedRoute).snapshot.paramMap.get('tenantId') ?? '';
  private readonly api = inject(AdminApiService);
  protected readonly auth = inject(AuthStore);
  protected readonly search = new FormControl('', { nonNullable: true });
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly operations = signal<PageResponse<OperationType>>(emptyPage);
  // Non-leaf types, for the parent picker and for the depth of rows whose parent is on another page.
  protected readonly parents = signal<OperationType[]>([]);

  constructor() {
    this.search.valueChanges
      .pipe(debounceTime(250), distinctUntilChanged(), takeUntilDestroyed(inject(DestroyRef)))
      .subscribe(() => this.load(0));
    this.load(0);
    this.loadParents();
  }

  protected load(page = this.operations().page): void {
    this.loading.set(true);
    this.error.set(null);
    this.api
      .listOperationTypes(this.tenantId, this.search.value, null, page)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (result) => this.operations.set(result),
        error: (error) => this.error.set(apiErrorMessage(error, 'Operation types could not be loaded.'))
      });
  }

  protected loadParents(): void {
    this.api.listOperationTypes(this.tenantId, '', false, 0, 100).subscribe({
      next: (result) => this.parents.set(result.items),
      error: () => this.parents.set([])
    });
  }

  protected created(): void {
    this.search.setValue('', { emitEvent: false });
    this.load(0);
    this.loadParents();
  }

  protected depth(operation: OperationType): number {
    let depth = 0;
    let parentId = operation.parentId;
    while (parentId && depth < 20) {
      depth++;
      parentId = this.parents().find((parent) => parent.id === parentId)?.parentId ?? null;
    }
    return depth;
  }
}
