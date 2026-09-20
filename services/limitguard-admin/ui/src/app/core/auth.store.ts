import { HttpErrorResponse } from '@angular/common/http';
import { computed, Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AdminRole, MeResponse } from './admin-api.models';
import { AdminApiService } from './admin-api.service';

type AuthStatus = 'idle' | 'loading' | 'authenticated' | 'unauthenticated' | 'error';

@Injectable({ providedIn: 'root' })
export class AuthStore {
  private readonly userState = signal<MeResponse | null>(null);
  private readonly statusState = signal<AuthStatus>('idle');
  private readonly errorState = signal<string | null>(null);
  private loadPromise?: Promise<boolean>;

  readonly user = this.userState.asReadonly();
  readonly status = this.statusState.asReadonly();
  readonly error = this.errorState.asReadonly();
  readonly isPlatformAdmin = computed(() => this.hasRole('PLATFORM_ADMIN'));

  constructor(private readonly api: AdminApiService) {}

  ensureLoaded(): Promise<boolean> {
    if (this.statusState() === 'authenticated') return Promise.resolve(true);
    if (this.loadPromise) return this.loadPromise;

    this.statusState.set('loading');
    this.errorState.set(null);
    this.loadPromise = firstValueFrom(this.api.me())
      .then((user) => {
        this.userState.set(user);
        this.statusState.set('authenticated');
        return true;
      })
      .catch((error: unknown) => {
        this.userState.set(null);
        if (error instanceof HttpErrorResponse && error.status === 401) {
          this.statusState.set('unauthenticated');
        } else {
          this.statusState.set('error');
          this.errorState.set('Your session could not be loaded.');
        }
        return false;
      })
      .finally(() => {
        this.loadPromise = undefined;
      });
    return this.loadPromise;
  }

  hasRole(role: AdminRole): boolean {
    return this.userState()?.roles.includes(role) ?? false;
  }

  canManageTenant(tenantId: string): boolean {
    return this.isPlatformAdmin() ||
      (this.hasRole('TENANT_ADMIN') && (this.userState()?.tenantIds.includes(tenantId) ?? false));
  }

  beginLogin(): void {
    window.location.assign('/oauth2/authorization/keycloak');
  }

}
