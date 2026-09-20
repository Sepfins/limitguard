import { importProvidersFrom } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { LucideAngularModule, X } from 'lucide-angular';
import { of } from 'rxjs';
import { AdminApiService } from '../core/admin-api.service';
import { CreateProductDialog } from './create-product-dialog';

describe('CreateProductDialog', () => {
  const product = {
    id: 'b82321f4-2e96-4112-9d63-7a412dba72ae',
    tenantId: '61f2e9e5-6651-47df-9f37-e463669020db',
    parentId: null,
    code: 'CARDS',
    name: 'Cards',
    category: 'CREDIT' as const,
    nodeType: 'FAMILY' as const,
    isLeaf: false,
    isActive: true,
    createdAt: '2026-08-09T10:00:00Z'
  };
  const api = { createFundingSourceProduct: vi.fn() };

  beforeAll(() => {
    HTMLDialogElement.prototype.close ??= vi.fn();
    HTMLDialogElement.prototype.showModal ??= vi.fn();
  });

  beforeEach(async () => {
    api.createFundingSourceProduct.mockReset().mockReturnValue(of(product));
    await TestBed.configureTestingModule({
      imports: [CreateProductDialog],
      providers: [
        { provide: AdminApiService, useValue: api },
        importProvidersFrom(LucideAngularModule.pick({ X }))
      ]
    }).compileComponents();
  });

  it('creates a product with empty metadata kept outside the UI contract', () => {
    const fixture = TestBed.createComponent(CreateProductDialog);
    fixture.componentRef.setInput('tenantId', product.tenantId);
    fixture.detectChanges();
    const component = fixture.componentInstance as any;
    component.form.setValue({
      code: 'CARDS',
      name: 'Cards',
      category: 'CREDIT',
      nodeType: 'FAMILY',
      parentId: '',
      isLeaf: false,
      isActive: true
    });

    component.submit();

    expect(api.createFundingSourceProduct).toHaveBeenCalledWith(product.tenantId, {
      code: 'CARDS',
      name: 'Cards',
      category: 'CREDIT',
      nodeType: 'FAMILY',
      parentId: null,
      isLeaf: false,
      isActive: true
    });
  });
});
