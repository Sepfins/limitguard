import { routes } from './app.routes';

describe('banking routes', () => {
  it('exposes only the five customer banking areas', () => {
    const children = routes[0].children ?? [];
    expect(children.map((route) => route.path)).toEqual([
      '',
      'home',
      'accounts',
      'cards',
      'activity',
      'limits'
    ]);
    expect(children.some((route) => route.path?.includes('payment'))).toBe(false);
  });

  it('lazy loads every customer page', async () => {
    const children = routes[0].children ?? [];
    const pages = children.filter((route) => route.loadComponent);
    const components = await Promise.all(
      pages.map((route) => (route.loadComponent as () => Promise<unknown>)())
    );

    expect(
      components.map((component) => (component as { name: string }).name.replace(/^_/, ''))
    ).toEqual(['HomePage', 'AccountsPage', 'CardsPage', 'ActivityPage', 'LimitsPage']);
  });
});
