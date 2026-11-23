import { TestBed } from '@angular/core/testing';
import { MatIconTestingModule } from '@angular/material/icon/testing';
import { provideRouter } from '@angular/router';

import { es, translocoTesting } from '../../../testing/i18n';
import { Shell } from './shell';

describe('Shell', () => {
  it('renders the header, the navigation and the content area with translated texts', async () => {
    TestBed.configureTestingModule({
      imports: [Shell, translocoTesting(), MatIconTestingModule],
      providers: [provideRouter([])],
    });
    const fixture = TestBed.createComponent(Shell);
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;

    expect(root.querySelector('mat-toolbar')?.textContent).toContain(es.app.name);
    expect(root.querySelector('mat-toolbar button')?.getAttribute('aria-label')).toBe(
      es.layout.toggleMenu,
    );
    expect(root.querySelector('nav')?.getAttribute('aria-label')).toBe(es.layout.mainNavigation);
    expect(root.querySelector('nav a')?.textContent).toContain(es.layout.nav.home);
    expect(root.querySelector('main router-outlet')).not.toBeNull();
  });
});
