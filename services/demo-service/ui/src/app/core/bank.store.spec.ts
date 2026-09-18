import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { BankStore } from './bank.store';
import { DemoApiService } from './demo-api.service';
import { cardFixture, cardPaymentFixture, limitFixture, overviewFixture } from './test-fixtures';

class FakeEventSource {
  static latest: FakeEventSource;
  onopen: ((event: Event) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  readonly listeners = new Map<string, EventListener[]>();
  readonly close = vi.fn();

  constructor(readonly url: string) {
    FakeEventSource.latest = this;
  }

  addEventListener(type: string, listener: EventListener): void {
    const listeners = this.listeners.get(type) ?? [];
    listeners.push(listener);
    this.listeners.set(type, listeners);
  }

  emit(type: string): void {
    this.listeners.get(type)?.forEach((listener) => listener(new Event(type)));
  }
}

describe('BankStore', () => {
  const originalEventSource = globalThis.EventSource;
  const api = {
    overview: vi.fn(),
    payments: vi.fn(),
    limits: vi.fn(),
    cards: vi.fn(),
    payment: vi.fn()
  };

  beforeEach(() => {
    vi.useFakeTimers();
    globalThis.EventSource = FakeEventSource as unknown as typeof EventSource;
    api.overview.mockReset().mockReturnValue(of(overviewFixture));
    api.payments.mockReset().mockReturnValue(of([cardPaymentFixture]));
    api.limits.mockReset().mockReturnValue(of([limitFixture]));
    api.cards.mockReset().mockReturnValue(of([cardFixture]));
    TestBed.configureTestingModule({
      providers: [BankStore, { provide: DemoApiService, useValue: api }]
    });
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    globalThis.EventSource = originalEventSource;
    vi.useRealTimers();
  });

  it('loads the four customer projections and hides balances on request', () => {
    const store = TestBed.inject(BankStore);
    store.load();

    expect(store.loading()).toBe(false);
    expect(store.accounts()).toEqual(overviewFixture.accounts);
    expect(store.payments()).toEqual([cardPaymentFixture]);
    expect(store.balancesVisible()).toBe(true);

    store.toggleBalances();
    expect(store.balancesVisible()).toBe(false);
  });

  it('refetches after SSE invalidation and when a visible tab resumes', () => {
    const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
    const store = TestBed.inject(BankStore);
    store.load();
    expect(FakeEventSource.latest.url).toContain('/customer/events');

    FakeEventSource.latest.onopen?.(new Event('open'));
    expect(store.connected()).toBe(true);
    FakeEventSource.latest.emit('payments.updated');
    vi.advanceTimersByTime(121);
    expect(api.overview).toHaveBeenCalledTimes(2);

    FakeEventSource.latest.onerror?.(new Event('error'));
    expect(store.connected()).toBe(false);
    document.dispatchEvent(new Event('visibilitychange'));
    expect(api.overview).toHaveBeenCalledTimes(3);
    visibility.mockRestore();
  });
});
