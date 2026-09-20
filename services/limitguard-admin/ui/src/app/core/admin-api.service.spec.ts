import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AdminApiService } from './admin-api.service';

describe('AdminApiService', () => {
  let api: AdminApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [AdminApiService, provideHttpClient(), provideHttpClientTesting()]
    });
    api = TestBed.inject(AdminApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('maps tenant paging and search into query parameters', () => {
    api.listTenants('  retail  ', 2).subscribe();

    const request = http.expectOne(
      (candidate) => candidate.url.endsWith('/tenants') && candidate.params.get('search') === 'retail'
    );
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('size')).toBe('25');
    request.flush({ items: [], page: 2, size: 25, totalElements: 0, totalPages: 0 });
  });

  it('posts products within the tenant route', () => {
    const body = {
      code: 'CARD',
      name: 'Cards',
      category: 'CREDIT' as const,
      nodeType: 'FAMILY' as const,
      parentId: null,
      isLeaf: false,
      isActive: true
    };
    api.createFundingSourceProduct('tenant-id', body).subscribe();

    const request = http.expectOne(
      '/limitguard/admin/api/v1/tenants/tenant-id/funding-source-products'
    );
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual(body);
    request.flush({});
  });
});
