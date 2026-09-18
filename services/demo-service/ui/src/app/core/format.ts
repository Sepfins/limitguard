import { DemoOperation, Money, PaymentCategory } from './models';

export function formatMoney(value: Money, visible = true): string {
  if (!visible) return '••••••';
  return new Intl.NumberFormat('en-IE', {
    style: 'currency',
    currency: value.currency,
    minimumFractionDigits: 2
  }).format(Number(value.amount));
}

export function formatDateTime(value: string): string {
  return new Intl.DateTimeFormat('en-GB', {
    day: '2-digit',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
    timeZone: 'Europe/Dublin'
  }).format(new Date(value));
}

export function formatTime(value: string): string {
  return new Intl.DateTimeFormat('en-GB', {
    hour: '2-digit',
    minute: '2-digit',
    timeZone: 'Europe/Dublin'
  }).format(new Date(value));
}

export function operationLabel(operation: DemoOperation): string {
  switch (operation) {
    case 'CARD_PURCHASE':
      return 'Card';
    case 'ATM_WITHDRAWAL':
      return 'ATM';
    case 'BANK_TRANSFER':
      return 'Transfer';
  }
}

export function categoryIcon(category: PaymentCategory): string {
  switch (category) {
    case 'DINING':
      return 'utensils';
    case 'TRANSPORT':
      return 'tram-front';
    case 'CASH':
      return 'banknote';
    case 'TRANSFER':
      return 'send';
    case 'GROCERIES':
    case 'SHOPPING':
      return 'shopping-bag';
    default:
      return 'circle-dollar-sign';
  }
}

/** A balance split so the cents can be set smaller than the whole amount. */
export function splitMoney(value: Money, visible = true): { whole: string; rest: string } {
  if (!visible) return { whole: '••••', rest: '' };
  const parts = new Intl.NumberFormat('en-IE', {
    style: 'currency',
    currency: value.currency,
    minimumFractionDigits: 2
  }).formatToParts(Number(value.amount));
  const cut = parts.findIndex((part) => part.type === 'decimal');
  return {
    whole: parts.slice(0, cut).map((part) => part.value).join(''),
    rest: parts.slice(cut).map((part) => part.value).join('')
  };
}

const statusLabels: Record<string, string> = {
  PENDING: 'Held',
  PARTIALLY_COMPLETED: 'Partly completed',
  COMPLETED: 'Completed',
  DECLINED: 'Declined',
  RELEASED: 'Released',
  REVERSED: 'Reversed',
  EXPIRED: 'Expired',
  PROCESSING: 'Processing',
  NOT_APPLICABLE: 'No limit applied'
};

export function statusLabel(displayStatus: string): string {
  return statusLabels[displayStatus] ?? displayStatus.replaceAll('_', ' ').toLowerCase();
}
