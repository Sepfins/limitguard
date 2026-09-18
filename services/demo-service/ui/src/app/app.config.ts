import { provideHttpClient } from '@angular/common/http';
import {
  ApplicationConfig,
  importProvidersFrom,
  provideBrowserGlobalErrorListeners
} from '@angular/core';
import { provideRouter } from '@angular/router';
import {
  Activity,
  AlertCircle,
  ArrowDownLeft,
  ArrowLeft,
  Banknote,
  Check,
  ChevronRight,
  CircleDollarSign,
  Clock,
  Copy,
  CreditCard,
  Eye,
  EyeOff,
  Home,
  Landmark,
  LucideAngularModule,
  RefreshCw,
  Send,
  ShieldCheck,
  ShoppingBag,
  SlidersHorizontal,
  TramFront,
  Utensils,
  WalletCards,
  Wifi,
  WifiOff,
  X
} from 'lucide-angular';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    importProvidersFrom(
      LucideAngularModule.pick({
        Activity,
        AlertCircle,
        ArrowDownLeft,
        ArrowLeft,
        Banknote,
        Check,
        ChevronRight,
        CircleDollarSign,
        Clock,
        Copy,
        CreditCard,
        Eye,
        EyeOff,
        Home,
        Landmark,
        RefreshCw,
        Send,
        ShieldCheck,
        ShoppingBag,
        SlidersHorizontal,
        TramFront,
        Utensils,
        WalletCards,
        Wifi,
        WifiOff,
        X
      })
    ),
    provideHttpClient(),
    provideRouter(routes)
  ]
};
