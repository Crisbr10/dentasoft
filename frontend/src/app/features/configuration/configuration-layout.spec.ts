import { Type, computed, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { es, translocoTesting } from '../../../testing/i18n';
import { ConfigurationLayout } from './configuration-layout';
import { ConfigurationIndex, NoAccess } from './configuration-pages';
import { ConfigurationSections, SECTIONS, VisibleEntry } from './configuration-sections';

describe('configuration area', () => {
  let visible: ReturnType<typeof signal<readonly VisibleEntry[]>>;
  let ensureLoaded: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    visible = signal<readonly VisibleEntry[]>([
      { ...SECTIONS[0], access: 'EDIT' },
      { ...SECTIONS[2], access: 'VIEW' },
    ]);
    ensureLoaded = vi.fn(() => Promise.resolve());
    TestBed.configureTestingModule({
      imports: [translocoTesting()],
      providers: [
        provideRouter([]),
        {
          provide: ConfigurationSections,
          useValue: { visible: computed(() => visible()), ensureLoaded },
        },
      ],
    });
  });

  async function render<T>(component: Type<T>): Promise<ComponentFixture<T>> {
    const fixture = TestBed.createComponent(component);
    await fixture.whenStable();
    return fixture;
  }

  function root(fixture: ComponentFixture<unknown>): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  describe('ConfigurationLayout', () => {
    it('lists only the sections the user sees, marking those they only see', async () => {
      const links = [...root(await render(ConfigurationLayout)).querySelectorAll('nav a')];

      expect(links.map((link) => link.getAttribute('href'))).toEqual([
        '/clinica',
        '/profesionales',
      ]);
      expect(links[0].textContent).toContain(es.configuration.sections.CLINIC);
      expect(links[0].textContent).not.toContain(es.configuration.viewOnly);
      expect(links[1].textContent).toContain(es.configuration.sections.PRACTITIONERS);
      expect(links[1].textContent).toContain(es.configuration.viewOnly);
    });
  });

  describe('NoAccess', () => {
    it('says the user may not see the section and leads back to the area', async () => {
      const page = root(await render(NoAccess));

      expect(page.textContent).toContain(es.configuration.noAccess.title);
      expect(page.textContent).toContain(es.configuration.noAccess.text);
      expect(page.querySelector('a')?.getAttribute('href')).toBe('/configuracion');
    });
  });

  describe('ConfigurationIndex', () => {
    it('says the sections could not be read and opens the first one when they can', async () => {
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
      const page = root(await render(ConfigurationIndex));
      expect(page.textContent).toContain(es.configuration.loadError);

      (page.querySelector('button') as HTMLButtonElement).click();

      await vi.waitFor(() => expect(navigate).toHaveBeenCalledWith('/configuracion/clinica'));
      expect(ensureLoaded).toHaveBeenCalled();
    });

    it('keeps the message when reading them fails again', async () => {
      ensureLoaded.mockRejectedValue(new Error('offline'));
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl');
      const fixture = await render(ConfigurationIndex);

      (root(fixture).querySelector('button') as HTMLButtonElement).click();
      await fixture.whenStable();

      expect(root(fixture).textContent).toContain(es.configuration.loadError);
      expect(navigate).not.toHaveBeenCalled();
    });
  });
});
