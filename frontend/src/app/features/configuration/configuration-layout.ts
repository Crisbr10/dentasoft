import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

import { ConfigurationSections } from './configuration-sections';

/** The configuration area: the menu of the sections the user sees, and the open section. */
@Component({
  selector: 'app-configuration-layout',
  imports: [
    MatIconModule,
    MatListModule,
    RouterLink,
    RouterLinkActive,
    RouterOutlet,
    TranslocoPipe,
  ],
  template: `
    <h1>{{ 'configuration.title' | transloco }}</h1>
    <div class="configuration">
      <nav class="configuration__menu" [attr.aria-label]="'configuration.menu' | transloco">
        <mat-nav-list>
          @for (section of sections.visible(); track section.id) {
            <a
              mat-list-item
              [routerLink]="section.path"
              routerLinkActive
              ariaCurrentWhenActive="page"
              #link="routerLinkActive"
              [activated]="link.isActive"
            >
              <mat-icon matListItemIcon [svgIcon]="section.icon" />
              <span matListItemTitle>{{ section.labelKey | transloco }}</span>
              @if (section.access === 'VIEW') {
                <span matListItemLine>{{ 'configuration.viewOnly' | transloco }}</span>
              }
            </a>
          }
        </mat-nav-list>
      </nav>
      <section class="configuration__content">
        <router-outlet />
      </section>
    </div>
  `,
  styles: `
    .configuration {
      display: flex;
      flex-wrap: wrap;
      gap: 24px;
    }

    .configuration__menu {
      flex: 0 0 240px;
    }

    .configuration__content {
      flex: 1 1 320px;
      min-width: 0;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConfigurationLayout {
  protected readonly sections = inject(ConfigurationSections);
}
