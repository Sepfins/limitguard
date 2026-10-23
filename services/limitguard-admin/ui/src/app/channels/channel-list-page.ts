import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { LucideAngularModule } from 'lucide-angular';
import { debounceTime, distinctUntilChanged, finalize } from 'rxjs';
import { apiErrorMessage } from '../core/api-error';
import { Channel, ChannelGroup, CodeNameRequest, PageResponse } from '../core/admin-api.models';
import { AdminApiService } from '../core/admin-api.service';
import { AuthStore } from '../core/auth.store';
import { CopyId } from '../shared/copy-id';
import { AddMemberDialog } from './add-member-dialog';
import { CodeNameDialog } from './code-name-dialog';
import { Guilloche } from '../shared/guilloche';

type ActiveFilter = 'all' | 'active' | 'inactive';

const emptyPage = { items: [], page: 0, size: 25, totalElements: 0, totalPages: 0 };

@Component({
  selector: 'app-channel-list-page',
  imports: [ReactiveFormsModule, CodeNameDialog, AddMemberDialog, CopyId, LucideAngularModule, Guilloche],
  templateUrl: './channel-list-page.html',
  styleUrl: '../shared/list-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class ChannelListPage {
  protected readonly tenantId = inject(ActivatedRoute).snapshot.paramMap.get('tenantId') ?? '';
  private readonly api = inject(AdminApiService);
  protected readonly auth = inject(AuthStore);
  protected readonly search = new FormControl('', { nonNullable: true });
  protected readonly groupSearch = new FormControl('', { nonNullable: true });
  protected readonly activeFilter = signal<ActiveFilter>('all');
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly channels = signal<PageResponse<Channel>>(emptyPage);
  protected readonly groupsLoading = signal(true);
  protected readonly groupsError = signal<string | null>(null);
  protected readonly groups = signal<PageResponse<ChannelGroup>>(emptyPage);
  // Every channel of the tenant, for member codes and the add-member picker.
  protected readonly channelCatalog = signal<Channel[]>([]);
  protected readonly registerChannel = (request: CodeNameRequest) => this.api.registerChannel(this.tenantId, request);
  protected readonly createGroup = (request: CodeNameRequest) => this.api.createChannelGroup(this.tenantId, request);

  constructor() {
    const destroyRef = inject(DestroyRef);
    this.search.valueChanges
      .pipe(debounceTime(250), distinctUntilChanged(), takeUntilDestroyed(destroyRef))
      .subscribe(() => this.load(0));
    this.groupSearch.valueChanges
      .pipe(debounceTime(250), distinctUntilChanged(), takeUntilDestroyed(destroyRef))
      .subscribe(() => this.loadGroups(0));
    this.load(0);
    this.loadCatalog();
    this.loadGroups(0);
  }

  protected load(page = this.channels().page): void {
    this.loading.set(true);
    this.error.set(null);
    const active = this.activeFilter() === 'all' ? null : this.activeFilter() === 'active';
    this.api
      .listChannels(this.tenantId, this.search.value, active, page)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (result) => this.channels.set(result),
        error: (error) => this.error.set(apiErrorMessage(error, 'Channels could not be loaded.'))
      });
  }

  // One page at the maximum size; members beyond it show as an id prefix.
  protected loadCatalog(): void {
    this.api.listChannels(this.tenantId, '', null, 0, 100).subscribe({
      next: (result) => this.channelCatalog.set(result.items),
      error: () => this.channelCatalog.set([])
    });
  }

  protected loadGroups(page = this.groups().page): void {
    this.groupsLoading.set(true);
    this.groupsError.set(null);
    this.api
      .listChannelGroups(this.tenantId, this.groupSearch.value, page)
      .pipe(finalize(() => this.groupsLoading.set(false)))
      .subscribe({
        next: (result) => this.groups.set(result),
        error: (error) => this.groupsError.set(apiErrorMessage(error, 'Channel groups could not be loaded.'))
      });
  }

  protected setActiveFilter(filter: ActiveFilter): void {
    if (filter === this.activeFilter()) return;
    this.activeFilter.set(filter);
    this.load(0);
  }

  protected channelCreated(): void {
    this.search.setValue('', { emitEvent: false });
    this.activeFilter.set('all');
    this.load(0);
    this.loadCatalog();
  }

  protected toggleActive(channel: Channel): void {
    const question = channel.isActive
      ? `Deactivate ${channel.code}? Payments and change requests arriving through it stop being recognised. ` +
        'Configurations, group memberships and history stay as they are.'
      : `Reactivate ${channel.code}? It becomes a valid request channel again.`;
    if (!window.confirm(question)) return;
    this.api.setChannelActive(this.tenantId, channel.id, !channel.isActive).subscribe({
      next: () => {
        this.load();
        this.loadCatalog();
      },
      error: (error) => this.error.set(apiErrorMessage(error, 'The channel could not be changed.'))
    });
  }

  protected memberCodes(group: ChannelGroup): string {
    if (group.memberChannelIds.length === 0) return 'No members';
    return group.memberChannelIds
      .map((id) => this.channelCatalog().find((channel) => channel.id === id)?.code ?? id.slice(0, 8))
      .join(', ');
  }
}
