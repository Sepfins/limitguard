import { importProvidersFrom, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { LucideAngularModule, icons } from 'lucide-angular';
import { of } from 'rxjs';
import { OperationType } from '../core/admin-api.models';
import { AdminApiService } from '../core/admin-api.service';
import { AuthStore } from '../core/auth.store';
import { OperationTypeListPage } from './operation-type-list-page';

describe('OperationTypeListPage', () => {
  const operation = (id: string, parentId: string | null, isLeaf = false, isGlobal = false): OperationType => ({
    id,
    parentId,
    code: id.toUpperCase(),
    name: id,
    isLeaf,
    isGlobal,
    createdAt: '2026-08-09T10:00:00Z'
  });
  const cards = operation('cards', null);
  const online = operation('online', 'cards');
  const purchase = operation('purchase', 'cards', true);
  const atm = operation('atm', null, true);
  const shared = operation('shared', null, false, true);
  const page = (items: OperationType[]) => of({ items, page: 0, size: 25, totalElements: items.length, totalPages: 1 });
  const api = { listOperationTypes: vi.fn(), moveOperationType: vi.fn() };
  const auth = { isPlatformAdmin: signal(false), canManageTenant: () => true };

  beforeEach(async () => {
    api.listOperationTypes
      .mockReset()
      .mockImplementation((_tenant: string, _search: string, leaf: boolean | null) =>
        page(leaf === false ? [cards, online, shared] : [atm, cards, online, purchase, shared])
      );
    api.moveOperationType.mockReset().mockReturnValue(of(purchase));
    await TestBed.configureTestingModule({
      imports: [OperationTypeListPage],
      providers: [
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ tenantId: 'tenant' }) } } },
        { provide: AdminApiService, useValue: api },
        { provide: AuthStore, useValue: auth },
        importProvidersFrom(LucideAngularModule.pick(icons))
      ]
    }).compileComponents();
  });

  function render() {
    const fixture = TestBed.createComponent(OperationTypeListPage);
    fixture.detectChanges();
    return { fixture, page: fixture.componentInstance as any };
  }

  it('only offers branches outside the moved subtree and the top level when it has a parent', () => {
    const { page: list } = render();
    list.pickUp(cards);

    expect(list.canDrop(online)).toBe(false);
    expect(list.canDrop(atm)).toBe(false);
    expect(list.canDrop(shared)).toBe(true);
    expect(list.canDrop(null)).toBe(false);

    list.pickUp(purchase);
    expect(list.canDrop(cards)).toBe(false);
    expect(list.canDrop(online)).toBe(true);
    expect(list.canDrop(null)).toBe(true);
  });

  it('moves a picked up type onto the clicked branch', () => {
    const { fixture, page: list } = render();
    const handle = fixture.nativeElement.querySelector('[aria-label="Move PURCHASE"]') as HTMLElement;
    handle.click();
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('[aria-label="Move PURCHASE under ONLINE"]') as HTMLElement).click();

    expect(api.moveOperationType).toHaveBeenCalledWith('tenant', 'purchase', 'online');
    expect(list.moving()).toBeNull();
    expect(fixture.nativeElement.querySelector('[aria-label="Move SHARED"]')).toBeNull();
  });
});
