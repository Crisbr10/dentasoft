import { BreakpointObserver, Breakpoints } from '@angular/cdk/layout';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatSidenav, MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { map } from 'rxjs';

import { Session } from '../auth/session';
import { ActiveClinic } from '../clinic/active-clinic';
import { CLINIC_SELECTOR_ROUTE } from '../http/api-interceptor';
import { NAVIGATION, visibleNavigation } from './navigation';

/**
 * Base layout: header with the active clinic and the logout, side navigation according to the
 * user's roles, and content area. The content is created again when the active clinic changes,
 * so no page keeps the data of the previous clinic.
 */
@Component({
  selector: 'app-shell',
  imports: [
    MatButtonModule,
    MatIconModule,
    MatListModule,
    MatSidenavModule,
    MatToolbarModule,
    RouterLink,
    RouterLinkActive,
    RouterOutlet,
    TranslocoPipe,
  ],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Shell {
  protected readonly clinic = inject(ActiveClinic);
  protected readonly session = inject(Session);
  protected readonly selectorRoute = CLINIC_SELECTOR_ROUTE;

  protected readonly navigation = computed(() =>
    visibleNavigation(NAVIGATION, this.clinic.role(), this.session.isPlatformAdmin()),
  );

  /** One key per clinic: @for creates the outlet again when it changes. */
  protected readonly contentKey = computed(() => [this.clinic.id() ?? 'none']);

  protected readonly isHandset = toSignal(
    inject(BreakpointObserver)
      .observe(Breakpoints.Handset)
      .pipe(map((state) => state.matches)),
    { initialValue: false },
  );

  /** On phones the menu covers the content, so close it after choosing an option. */
  protected closeOnHandset(sidenav: MatSidenav): void {
    if (this.isHandset()) {
      void sidenav.close();
    }
  }

  protected logout(): void {
    this.session.logout();
  }
}
