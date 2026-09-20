import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import { LucideAngularModule } from 'lucide-angular';
import { AuthStore } from '../core/auth.store';

@Component({
  selector: 'app-admin-shell',
  imports: [RouterOutlet, RouterLink, LucideAngularModule],
  templateUrl: './admin-shell.html',
  styleUrl: './admin-shell.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class AdminShell {
  protected readonly auth = inject(AuthStore);
  protected readonly roleLabel = computed(() => {
    const roles = this.auth.user()?.roles ?? [];
    if (roles.includes('PLATFORM_ADMIN')) return 'Platform admin';
    if (roles.includes('TENANT_ADMIN')) return 'Tenant admin';
    if (roles.includes('VIEWER')) return 'Viewer';
    return 'No access';
  });
}
