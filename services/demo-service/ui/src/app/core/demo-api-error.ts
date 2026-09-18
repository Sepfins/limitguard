export class DemoApiError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly fieldErrors: { field: string; message: string }[] = [],
    readonly correlationId?: string,
    readonly status?: number,
  ) {
    super(message);
  }
}
