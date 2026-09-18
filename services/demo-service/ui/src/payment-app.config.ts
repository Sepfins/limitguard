import { provideHttpClient } from '@angular/common/http';
import { ApplicationConfig, importProvidersFrom, provideBrowserGlobalErrorListeners } from '@angular/core';
import {
  AlertCircle,
  Banknote,
  Check,
  CreditCard,
  LucideAngularModule,
  RefreshCw,
  Send,
  ShieldCheck,
  X,
} from 'lucide-angular';
import { provideRouter } from '@angular/router';
import { paymentRoutes } from './payment-routes';

export const paymentAppConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    importProvidersFrom(
      LucideAngularModule.pick({
        AlertCircle,
        Banknote,
        Check,
        CreditCard,
        RefreshCw,
        Send,
        ShieldCheck,
        X,
      }),
    ),
    provideHttpClient(),
    provideRouter(paymentRoutes),
  ],
};
