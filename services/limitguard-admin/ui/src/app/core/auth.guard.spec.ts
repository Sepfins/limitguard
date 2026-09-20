import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, RouterStateSnapshot } from '@angular/router';
import { authGuard } from './auth.guard';
import { AuthStore } from './auth.store';

describe('authGuard', () => {
  const route = {} as ActivatedRouteSnapshot;
  const state = {} as RouterStateSnapshot;

  it('allows an authenticated session', async () => {
    const auth = {
      ensureLoaded: vi.fn().mockResolvedValue(true),
      status: signal('authenticated'),
      beginLogin: vi.fn()
    };
    TestBed.configureTestingModule({ providers: [{ provide: AuthStore, useValue: auth }] });

    const result = await TestBed.runInInjectionContext(() => authGuard(route, state));

    expect(result).toBe(true);
    expect(auth.beginLogin).not.toHaveBeenCalled();
  });

  it('starts OIDC login after a 401 session probe', async () => {
    const auth = {
      ensureLoaded: vi.fn().mockResolvedValue(false),
      status: signal('unauthenticated'),
      beginLogin: vi.fn()
    };
    TestBed.configureTestingModule({ providers: [{ provide: AuthStore, useValue: auth }] });

    const result = await TestBed.runInInjectionContext(() => authGuard(route, state));

    expect(result).toBe(false);
    expect(auth.beginLogin).toHaveBeenCalledOnce();
  });
});
