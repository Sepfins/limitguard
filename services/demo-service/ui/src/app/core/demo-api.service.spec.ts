import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { DemoApiError, DemoApiService } from './demo-api.service';

describe('DemoApiService', () => {
  let api: DemoApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [DemoApiService, provideHttpClient(), provideHttpClientTesting()]
    });
    api = TestBed.inject(DemoApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('maps an operation filter onto the customer payment endpoint', () => {
    api.payments('ATM_WITHDRAWAL').subscribe();

    const request = http.expectOne(
      '/limitguard/demo/api/v1/customer/payments?operation=ATM_WITHDRAWAL'
    );
    expect(request.request.method).toBe('GET');
    request.flush([]);
  });

  it('sends decimal limit changes with a generated idempotency key', () => {
    api.updateLimit('limit-1', '750.00').subscribe();

    const request = http.expectOne('/limitguard/demo/api/v1/customer/limits/limit-1');
    expect(request.request.method).toBe('PATCH');
    expect(request.request.body).toEqual({ amount: '750.00' });
    expect(request.request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
    request.flush({});
  });

  it('maps the backend error envelope and HTTP status', () => {
    let received: DemoApiError | undefined;
    api.overview().subscribe({ error: (error) => (received = error) });

    http.expectOne('/limitguard/demo/api/v1/customer/overview').flush(
      {
        code: 'CONFLICT',
        message: 'State changed',
        correlationId: 'correlation-1',
        fieldErrors: [{ field: 'amount', message: 'is stale' }]
      },
      { status: 409, statusText: 'Conflict' }
    );

    expect(received).toBeInstanceOf(DemoApiError);
    expect(received?.status).toBe(409);
    expect(received?.code).toBe('CONFLICT');
    expect(received?.fieldErrors).toEqual([{ field: 'amount', message: 'is stale' }]);
  });
});
