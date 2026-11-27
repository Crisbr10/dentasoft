import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

import { CONFIGURATION_ROUTE, NO_ACCESS_ROUTE } from '../../core/auth/routes';
import { ConfigurationSections } from './configuration-sections';

/**
 * The index of the area when the sections could not be read (otherwise firstSectionGuard opens
 * the first section): says so and lets the user try again.
 */
@Component({
  selector: 'app-configuration-index',
  imports: [MatButtonModule, MatIconModule, TranslocoPipe],
  template: `
    <p role="alert">{{ 'configuration.loadError' | transloco }}</p>
    <button mat-stroked-button type="button" [disabled]="retrying()" (click)="retry()">
      <mat-icon svgIcon="refresh" />
      {{ 'configuration.retry' | transloco }}
    </button>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConfigurationIndex {
  private readonly sections = inject(ConfigurationSections);
  private readonly router = inject(Router);
  protected readonly retrying = signal(false);

  protected async retry(): Promise<void> {
    this.retrying.set(true);
    try {
      await this.sections.ensureLoaded();
      const first = this.sections.visible()[0];
      await this.router.navigateByUrl(
        first ? `${CONFIGURATION_ROUTE}/${first.path}` : NO_ACCESS_ROUTE,
      );
    } catch {
      // Still failing: the message stays.
    } finally {
      this.retrying.set(false);
    }
  }
}

/**
 * A section whose screens come with a later task (M01-T10, M02-T08): its title and a notice.
 * The route's data.labelKey names the section.
 */
@Component({
  selector: 'app-section-placeholder',
  imports: [TranslocoPipe],
  template: `
    <h2>{{ labelKey | transloco }}</h2>
    <p>{{ 'configuration.placeholder' | transloco }}</p>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SectionPlaceholder {
  protected readonly labelKey = inject(ActivatedRoute).snapshot.data['labelKey'] as string;
}

/** Where sectionGuard sends a user who opens, by URL, a section they do not see. */
@Component({
  selector: 'app-no-access',
  imports: [MatButtonModule, MatIconModule, RouterLink, TranslocoPipe],
  template: `
    <h2><mat-icon svgIcon="lock" /> {{ 'configuration.noAccess.title' | transloco }}</h2>
    <p>{{ 'configuration.noAccess.text' | transloco }}</p>
    <a mat-stroked-button [routerLink]="home">{{ 'configuration.noAccess.back' | transloco }}</a>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NoAccess {
  protected readonly home = CONFIGURATION_ROUTE;
}
