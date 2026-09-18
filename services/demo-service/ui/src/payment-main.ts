import { bootstrapApplication } from '@angular/platform-browser';
import { PaymentApp } from './payment-app';
import { paymentAppConfig } from './payment-app.config';

bootstrapApplication(PaymentApp, paymentAppConfig).catch((error) => console.error(error));
