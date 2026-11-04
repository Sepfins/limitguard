import { importProvidersFrom } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { LucideAngularModule, X } from 'lucide-angular';
import { of } from 'rxjs';
import { AdminApiService } from '../core/admin-api.service';
import { CreateTenantDialog } from './create-tenant-dialog';

describe('CreateTenantDialog', () => {
  const tenant = {
    id: '7f032ab4-d514-4f84-8264-12b5cfc2a126',
    name: 'Retail Italy',
    timeZone: 'Europe/Rome',
    noMatchingRuleBehavior: 'DENY' as const,
    createdAt: '2026-08-09T10:00:00Z'
  };
  const api = { createTenant: vi.fn() };

  beforeAll(() => {
    HTMLDialogElement.prototype.close ??= vi.fn();
    HTMLDialogElement.prototype.showModal ??= vi.fn();
  });

  beforeEach(async () => {
    api.createTenant.mockReset().mockReturnValue(of(tenant));
    await TestBed.configureTestingModule({
      imports: [CreateTenantDialog],
      providers: [
        { provide: AdminApiService, useValue: api },
        importProvidersFrom(LucideAngularModule.pick({ X }))
      ]
    }).compileComponents();
  });

  it('submits the validated tenant contract and emits the created tenant', () => {
    const fixture = TestBed.createComponent(CreateTenantDialog);
    fixture.detectChanges();
    const component = fixture.componentInstance as any;
    const emitted = vi.fn();
    fixture.componentInstance.created.subscribe(emitted);
    component.form.setValue({ name: 'Retail Italy', timeZone: 'Europe/Rome', noMatchingRuleBehavior: 'DENY' });

    component.submit();

    expect(api.createTenant).toHaveBeenCalledWith({
      name: 'Retail Italy',
      timeZone: 'Europe/Rome',
      noMatchingRuleBehavior: 'DENY'
    });
    expect(emitted).toHaveBeenCalledWith(tenant);
  });
});
