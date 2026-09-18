import { expect, test } from '@playwright/test';

const demoPath = '/limitguard/demo';
const paymentPath = '/limitguard/payments';

test('desktop rail and mobile navigation fit their viewports', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(`${demoPath}/home`);
  await expect(page.getByRole('heading', { name: 'Good morning, Emma' })).toBeVisible();
  await expect(page.locator('.sidebar')).toBeVisible();
  await expect(page.locator('.mobile-nav')).toBeHidden();
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(1440);

  await page.setViewportSize({ width: 390, height: 844 });
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Good morning, Emma' })).toBeVisible();
  await expect(page.locator('.sidebar')).toBeHidden();
  await expect(page.locator('.mobile-nav')).toBeVisible();
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});

test('external authorization appears through SSE without reloading the SPA', async ({ page, request }) => {
  const scenarioResponse = await request.get(`${demoPath}/api/v1/scenario`);
  expect(scenarioResponse.ok()).toBeTruthy();
  const scenario = (await scenarioResponse.json()) as {
    accountIds: string[];
    cardIds: string[];
  };
  const externalPaymentId = `playwright-live-${Date.now()}`;
  const description = `Live coffee ${Date.now()}`;

  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(`${demoPath}/activity`);
  await expect(page.getByRole('heading', { name: 'Activity' })).toBeVisible();
  await expect
    .poll(async () => (await page.locator('.connection').getAttribute('class')) ?? '')
    .not.toContain('offline');
  const urlBeforeMutation = page.url();

  const authorization = await request.post(`${demoPath}/api/v1/integrations/payment-authorizations`, {
    headers: { 'Idempotency-Key': `playwright-auth-${externalPaymentId}` },
    data: {
      externalPaymentId,
      operation: 'CARD_PURCHASE',
      accountId: scenario.accountIds[0],
      cardId: scenario.cardIds[0],
      amount: '12.30',
      currency: 'EUR',
      description,
      category: 'DINING',
    },
  });

  expect(authorization.ok()).toBeTruthy();
  await expect(page.getByText(description, { exact: true })).toBeVisible();
  expect(page.url()).toBe(urlBeforeMutation);

  const release = await request.post(`${demoPath}/api/v1/integrations/payments/${externalPaymentId}/releases`, {
    headers: { 'Idempotency-Key': `playwright-release-${externalPaymentId}` },
    data: { reason: 'Playwright cleanup' },
  });
  expect(release.ok()).toBeTruthy();
});

test('payment lab consumes, restores, and denies limit capacity', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto(paymentPath);
  await expect(page.getByRole('heading', { name: 'Payment Lab' })).toBeVisible();
  await expect(page.locator('#funding-account')).toHaveValue(/.+/);
  await expect(page.locator('#payment-card')).toHaveValue(/.+/);

  await page.locator('#payment-amount').fill('1.00');
  await page.locator('#payment-description').fill(`Payment lab ${Date.now()}`);
  await page.locator('#external-payment-id').fill(`playwright-payment-lab-${Date.now()}`);
  await page.getByRole('button', { name: 'Authorize payment' }).click();

  await expect(page.locator('.result-summary.allowed')).toBeVisible();
  await page.getByRole('button', { name: 'Capture' }).click();
  await expect(page.getByText('Latest captured')).toBeVisible();

  await page.getByRole('button', { name: 'Reverse' }).click();
  await expect(page.getByText('Latest reversed')).toBeVisible();
  await expect(page.locator('.lifecycle-step').nth(1)).toHaveClass(/done/);

  await page.getByRole('button', { name: 'New payment' }).click();
  await page.getByRole('button', { name: /Bank transfer/ }).click();
  await page.locator('#payment-amount').fill('9999.00');
  await page.locator('#payment-description').fill(`Denied transfer ${Date.now()}`);
  await page.locator('#external-payment-id').fill(`playwright-denied-transfer-${Date.now()}`);
  await page.getByRole('button', { name: 'Authorize payment' }).click();

  await expect(page.locator('.result-summary.denied')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Decision details' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Capture' })).toHaveCount(0);
});
