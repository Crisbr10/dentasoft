import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { ConfigurationSections } from '../configuration/configuration-sections';
import { ClinicDataForm } from './clinic-data-form';
import { ClinicLocations } from './clinic-locations';
import { ClinicSettingsForm } from './clinic-settings-form';
import { OpeningHoursForm } from './opening-hours-form';

/**
 * «Clínica» of the configuration area (M01-T06, M01-T10): the active clinic's data and time zone,
 * its settings, its opening hours and its treatment rooms. Each block reads and saves its own
 * data, so one that fails leaves the others usable. ADMIN_CLINICA (access EDIT) edits them; the
 * other roles (access VIEW) see them, which the backend also allows them and nothing more.
 */
@Component({
  selector: 'app-clinic-home',
  imports: [ClinicDataForm, ClinicLocations, ClinicSettingsForm, OpeningHoursForm, TranslocoPipe],
  template: `
    <h2>{{ 'clinic.title' | transloco }}</h2>
    <p>{{ (canEdit() ? 'clinic.intro' : 'clinic.introViewOnly') | transloco }}</p>

    <section class="clinic-home__block" [attr.aria-labelledby]="'clinic-data-title'">
      <app-clinic-data-form [canEdit]="canEdit()" />
    </section>
    <section class="clinic-home__block" [attr.aria-labelledby]="'clinic-settings-title'">
      <app-clinic-settings-form [canEdit]="canEdit()" />
    </section>
    <section class="clinic-home__block" [attr.aria-labelledby]="'clinic-hours-title'">
      <app-opening-hours-form [canEdit]="canEdit()" />
    </section>
    <section class="clinic-home__block" [attr.aria-labelledby]="'clinic-locations-title'">
      <app-clinic-locations [canEdit]="canEdit()" />
    </section>
  `,
  styles: `
    .clinic-home__block + .clinic-home__block {
      margin-block-start: 32px;
      padding-block-start: 16px;
      border-block-start: 1px solid var(--mat-sys-outline-variant);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ClinicHome {
  private readonly sections = inject(ConfigurationSections);

  protected readonly canEdit = computed(() => this.sections.accessTo('CLINIC') === 'EDIT');
}
