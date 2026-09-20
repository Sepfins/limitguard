import { importProvidersFrom, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { LucideAngularModule, icons } from 'lucide-angular';
import { of, throwError } from 'rxjs';
import { AdminApiService } from '../core/admin-api.service';
import { AuthStore } from '../core/auth.store';
import { TenantListPage } from './tenant-list-page';

describe('TenantListPage', () => {
  const api = { listTenants: vi.fn(), createTenant: vi.fn() };
  const auth = { isPlatformAdmin: signal(true) };

  beforeEach(async () => {
    api.listTenants.mockReset();
    await TestBed.configureTestingModule({
      imports: [TenantListPage],
      providers: [
        provideRouter([]),
        { provide: AdminApiService, useValue: api },
        { provide: AuthStore, useValue: auth },
        importProvidersFrom(LucideAngularModule.pick(icons))
      ]
    }).compileComponents();
  });

  it('loads the next page from the pagination control', () => {
    api.listTenants.mockImplementation((_search: string, page: number) =>
      of({
        items: [
          {
            id: `tenant-${page}`,
            name: `Tenant ${page}`,
            noMatchingRuleBehavior: 'DENY',
            createdAt: '2026-08-09T10:00:00Z'
          }
        ],
        page,
        size: 25,
        totalElements: 26,
        totalPages: 2
      })
    );
    const fixture = TestBed.createComponent(TenantListPage);
    fixture.detectChanges();

    const next = fixture.nativeElement.querySelector('[aria-label="Next page"]') as HTMLButtonElement;
    next.click();
    fixture.detectChanges();

    expect(api.listTenants).toHaveBeenLastCalledWith('', 1);
    expect(fixture.nativeElement.textContent).toContain('Tenant 1');
  });

  it('renders a retryable failure state', () => {
    api.listTenants.mockReturnValue(throwError(() => new Error('offline')));
    const fixture = TestBed.createComponent(TenantListPage);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Tenants could not be loaded.');
    expect(fixture.nativeElement.querySelector('.error-state button')).not.toBeNull();
  });
});
