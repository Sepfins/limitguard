import { inject } from '@angular/core';
import { CanActivateFn } from '@angular/router';
import { AuthStore } from './auth.store';

export const authGuard: CanActivateFn = async () => {
  const auth = inject(AuthStore);
  const authenticated = await auth.ensureLoaded();
  if (!authenticated && auth.status() === 'unauthenticated') auth.beginLogin();
  return authenticated;
};
