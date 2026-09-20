import { HttpErrorResponse } from '@angular/common/http';
import { AdminErrorPayload } from './admin-api.models';

export function apiErrorMessage(error: unknown, fallback: string): string {
  if (!(error instanceof HttpErrorResponse)) return fallback;
  const payload = error.error as Partial<AdminErrorPayload> | null;
  if (error.status === 409) {
    return 'That identifying code or name is already in use.';
  }
  if (error.status === 403) {
    return 'Your account is not allowed to perform this operation.';
  }
  if (error.status === 503) {
    return 'LimitGuard is temporarily unavailable. Try again shortly.';
  }
  return typeof payload?.message === 'string' && payload.message.length > 0
    ? payload.message
    : fallback;
}
