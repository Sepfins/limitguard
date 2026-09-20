import { ApplicationConfig, importProvidersFrom, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withXsrfConfiguration } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import {
  ArrowLeft,
  ArrowRight,
  ChevronLeft,
  ChevronRight,
  LogOut,
  LucideAngularModule,
  Plus,
  RefreshCw,
  Search,
  X
} from 'lucide-angular';

import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    importProvidersFrom(
      LucideAngularModule.pick({
        ArrowLeft,
        ArrowRight,
        ChevronLeft,
        ChevronRight,
        LogOut,
        Plus,
        RefreshCw,
        Search,
        X
      })
    ),
    provideHttpClient(
      withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' })
    ),
    provideRouter(routes)
  ]
};
