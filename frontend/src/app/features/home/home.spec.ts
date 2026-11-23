import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatIconTestingModule } from '@angular/material/icon/testing';

import { es, translocoTesting } from '../../../testing/i18n';
import { RuntimeConfigStore } from '../../core/config/runtime-config';
import { Home } from './home';

const HEALTH_URL = 'https://api.dentasoft.test/actuator/health';

describe('Home', () => {
  let fixture: ComponentFixture<Home>;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [Home, translocoTesting(), MatIconTestingModule],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: RuntimeConfigStore,
          useValue: {
            config: {
              apiUrl: 'https://api.dentasoft.test',
              authUrl: 'https://auth.dentasoft.test',
            },
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Home);
  });

  afterEach(() => http.verify());

  /** Renders while the health request is pending (the app is not stable until it ends). */
  function render(): void {
    TestBed.tick();
  }

  /** Renders once the health request has an answer. */
  async function settle(): Promise<void> {
    await fixture.whenStable();
  }

  function root(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function status(): string | null {
    return root().querySelector('[role="status"]')?.getAttribute('data-status') ?? null;
  }

  it('shows the translated title while it checks the API from config.json', () => {
    render();

    expect(root().textContent).toContain(es.home.title);
    expect(root().textContent).toContain(es.home.health.checking);
    expect(status()).toBe('checking');
    // The spinner is decorative: the status text already says what is happening.
    expect(root().querySelector('mat-spinner')?.getAttribute('aria-hidden')).toBe('true');
    http.expectOne(HEALTH_URL).flush({ status: 'UP' });
  });

  it('reports a healthy server', async () => {
    render();
    http.expectOne(HEALTH_URL).flush({ status: 'UP' });
    await settle();

    expect(status()).toBe('up');
    expect(root().textContent).toContain(es.home.health.up);
  });

  it('reports a server that answers but is down', async () => {
    render();
    http
      .expectOne(HEALTH_URL)
      .flush({ status: 'DOWN' }, { status: 503, statusText: 'Service Unavailable' });
    await settle();

    expect(status()).toBe('down');
    expect(root().textContent).toContain(es.home.health.down);
  });

  it('reports an unreachable server and checks again on retry', async () => {
    render();
    http.expectOne(HEALTH_URL).error(new ProgressEvent('error'));
    await settle();

    expect(status()).toBe('unreachable');
    expect(root().textContent).toContain(es.home.health.unreachable);

    const retry = root().querySelector('button');
    expect(retry?.textContent).toContain(es.home.health.retry);
    retry?.click();
    render();
    http.expectOne(HEALTH_URL).flush({ status: 'UP' });
    await settle();

    expect(status()).toBe('up');
  });
});
