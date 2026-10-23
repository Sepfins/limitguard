import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { LucideAngularModule } from 'lucide-angular';
import { filter, map } from 'rxjs';
import { AdminApiService } from '../core/admin-api.service';
import { Guilloche } from '../shared/guilloche';

@Component({
  selector: 'app-tenant-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, LucideAngularModule, Guilloche],
  templateUrl: './tenant-shell.html',
  styleUrl: './tenant-shell.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class TenantShell {
  protected readonly tenantId = inject(ActivatedRoute).snapshot.paramMap.get('tenantId') ?? '';
  protected readonly sections = [
    { path: 'products', label: 'Products' },
    { path: 'channels', label: 'Channels' },
    { path: 'operations', label: 'Operations' },
    { path: 'configurations', label: 'Configurations' },
    { path: 'policies', label: 'Policies' }
  ];
  protected readonly navOpen = signal(false);
  protected readonly tenantName = signal('Tenant');
  // Banknote microprint: the tenant repeated in tiny type along the band's edge.
  protected readonly microprint = computed(() =>
    Array(24).fill(`${this.tenantName().toUpperCase()} ${this.tenantId.slice(-8)}`).join(' · ')
  );

  private readonly router = inject(Router);
  private readonly url = toSignal(
    this.router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      map(() => this.router.url)
    ),
    { initialValue: this.router.url }
  );
  protected readonly currentSection = computed(
    () => this.sections.find((section) => this.url().includes(`/${section.path}`))?.label ?? 'Sections'
  );

  constructor() {
    inject(AdminApiService)
      .listTenants('', 0, 100)
      .subscribe((page) => {
        const tenant = page.items.find((candidate) => candidate.id === this.tenantId);
        if (tenant) this.tenantName.set(tenant.name);
      });
  }
}
