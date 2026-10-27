import { ChangeDetectionStrategy, Component, DestroyRef, HostListener, inject, signal } from '@angular/core';
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
  styleUrls: ['../shared/list-page.css', './operation-type-list-page.css'],
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
  // A tenant type is moved by dragging its handle onto a branch, or by clicking the handle and then the branch.
  protected readonly moving = signal<OperationType | null>(null);
  // The branch id, or 'top', under the pointer while dragging.
  protected readonly dropOver = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly moveError = signal<string | null>(null);

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

  protected catalogChanged(): void {
    this.search.setValue('', { emitEvent: false });
    this.load(0);
    this.loadParents();
  }

  protected canMove(operation: OperationType): boolean {
    return !operation.isGlobal && this.auth.canManageTenant(this.tenantId);
  }

  /** A null target is the top level. A type never goes beneath a leaf, itself, or its own subtree. */
  protected canDrop(target: OperationType | null): boolean {
    const moving = this.moving();
    if (!moving || this.saving()) return false;
    if (target === null) return moving.parentId !== null;
    if (target.isLeaf || target.id === moving.parentId) return false;
    const branches = new Map(this.parents().map((branch) => [branch.id, branch]));
    for (let node: OperationType | undefined = target; node; node = branches.get(node.parentId ?? '')) {
      if (node.id === moving.id) return false;
    }
    return true;
  }

  protected pickUp(operation: OperationType): void {
    this.moveError.set(null);
    this.moving.set(this.moving()?.id === operation.id ? null : operation);
  }

  protected dragStart(event: DragEvent, operation: OperationType, row: HTMLElement): void {
    this.moveError.set(null);
    this.moving.set(operation);
    event.dataTransfer?.setData('text/plain', operation.code);
    if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move';
    event.dataTransfer?.setDragImage(row, 20, 20);
  }

  protected dragOver(event: DragEvent, target: OperationType | null): void {
    if (!this.canDrop(target)) return;
    event.preventDefault();
    this.dropOver.set(target?.id ?? 'top');
  }

  protected dragLeave(target: OperationType | null): void {
    if (this.dropOver() === (target?.id ?? 'top')) this.dropOver.set(null);
  }

  protected dragEnd(): void {
    this.dropOver.set(null);
    if (!this.saving()) this.moving.set(null);
  }

  protected drop(target: OperationType | null, event?: Event): void {
    event?.preventDefault();
    const moving = this.moving();
    if (!moving || !this.canDrop(target)) return;
    this.saving.set(true);
    this.dropOver.set(null);
    this.api
      .moveOperationType(this.tenantId, moving.id, target?.id ?? null)
      .pipe(
        finalize(() => {
          this.saving.set(false);
          this.moving.set(null);
        })
      )
      .subscribe({
        next: () => this.catalogChanged(),
        error: (error) => this.moveError.set(apiErrorMessage(error, `${moving.code} could not be moved.`))
      });
  }

  @HostListener('document:keydown.escape')
  protected cancelMove(): void {
    if (this.saving()) return;
    this.moving.set(null);
    this.dropOver.set(null);
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
